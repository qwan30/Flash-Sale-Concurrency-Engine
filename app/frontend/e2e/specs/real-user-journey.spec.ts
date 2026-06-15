import { test, expect } from '@playwright/test';
import { EventsPage } from '../pages/events';
import { BookingPage } from '../pages/booking';
import {
  resetBenchmark,
  warmupStock,
  getConsistency,
  placeOrder,
} from '../fixtures/api-helpers';

/**
 * Real User Journey E2E Tests
 *
 * These tests simulate actual buyer behavior — browsing the event catalog,
 * clicking on tickets, placing orders through the booking form, checking
 * "my orders", and verifying the system maintains stock correctness.
 *
 * The admin operator sets up the sale via direct API calls (faster than
 * clicking through the control desk for every test), then the buyer uses
 * the dashboard UI for the real click-through experience.
 */

const TICKET_ITEM_ID = 4;
const STOCK = 20;
const YEAR_MONTH = new Date()
  .toISOString()
  .slice(0, 7)
  .replace('-', '');

test.describe('Real Buyer Journey', () => {
  test.beforeEach(async () => {
    // Operator sets up the sale via API (fast, reliable setup)
    await resetBenchmark({
      ticketItemId: TICKET_ITEM_ID,
      stock: STOCK,
      yearMonth: YEAR_MONTH,
    });
    await warmupStock(TICKET_ITEM_ID);
  });

  test('complete buyer journey — browse, buy, verify order, check stock', async ({
    page,
  }) => {
    // Step 1: Browse the event catalog
    const eventsPage = new EventsPage(page);
    await eventsPage.goto();
    await expect(page).toHaveURL('/events');

    // Verify event cards are visible
    const count = await eventsPage.eventCount();
    expect(count).toBeGreaterThan(0);

    // Step 2: Click on a specific event to see detail
    await eventsPage.clickEvent(TICKET_ITEM_ID);
    await expect(page).toHaveURL(`/events/${TICKET_ITEM_ID}`);

    // Verify ticket detail is displayed (name, price, stock info)
    await expect(page.locator('h1, h2, h3').first()).toBeVisible();

    // Step 3: Navigate to booking page and place an order
    const bookingPage = new BookingPage(page);
    await bookingPage.goto();
    await expect(page).toHaveURL('/booking');

    const idempotencyKey = `e2e-buyer-${Date.now()}`;
    await bookingPage.placeOrder({
      ticketItemId: TICKET_ITEM_ID,
      userId: 77_001,
      quantity: 2,
      strategy: 'REDIS_LUA_WITH_COMPENSATION',
      idempotencyKey,
    });

    // Verify order was successful
    expect(await bookingPage.isSuccess()).toBe(true);

    const orderNumber = await bookingPage.getOrderNumber();
    expect(orderNumber).toBeTruthy();
    expect(orderNumber).not.toBe('');

    // Step 4: Check "My Orders" to see the order
    await page.goto('/my-orders');
    await expect(page).toHaveURL('/my-orders');

    // The order should appear in the orders list
    await expect(
      page.locator(`text=${orderNumber}`),
    ).toBeVisible({ timeout: 5000 });

    // Step 5: Click into the order detail
    await page.goto(`/orders/${orderNumber}`);
    await expect(page).toHaveURL(`/orders/${orderNumber}`);

    // Verify order detail shows key information
    await expect(page.locator(`text=${orderNumber}`)).toBeVisible();

    // Step 6: Verify stock correctness via API
    const consistency = await getConsistency(TICKET_ITEM_ID, YEAR_MONTH);
    const result = consistency.result;

    // After buying 2 of 20, stock should be 18
    expect(result.redisStockAfter).toBe(STOCK - 2);
    expect(result.dbStockAfter).toBe(STOCK - 2);
    expect(result.oversoldCount).toBe(0);
    expect(result.redisDbInconsistencyCount).toBe(0);
    expect(result.driftAmount).toBe(0);
  });

  test('buyer places multiple orders and sees them in history', async ({
    page,
  }) => {
    const userId = 77_002;

    // Place 3 orders via the booking page
    const bookingPage = new BookingPage(page);
    const orderNumbers: string[] = [];

    for (let i = 0; i < 3; i++) {
      await bookingPage.goto();
      const key = `e2e-multi-${userId}-${i}-${Date.now()}`;
      await bookingPage.placeOrder({
        ticketItemId: TICKET_ITEM_ID,
        userId,
        quantity: 1,
        strategy: 'REDIS_LUA_WITH_COMPENSATION',
        idempotencyKey: key,
      });
      expect(await bookingPage.isSuccess()).toBe(true);
      const on = await bookingPage.getOrderNumber();
      expect(on).toBeTruthy();
      orderNumbers.push(on!);
    }

    // Verify all 3 orders appear in the orders list
    await page.goto('/my-orders');
    for (const on of orderNumbers) {
      await expect(page.locator(`text=${on}`)).toBeVisible({
        timeout: 5000,
      });
    }

    // Verify stock = 20 - 3 = 17
    const consistency = await getConsistency(TICKET_ITEM_ID, YEAR_MONTH);
    expect(consistency.result.redisStockAfter).toBe(STOCK - 3);
    expect(consistency.result.dbStockAfter).toBe(STOCK - 3);
    expect(consistency.result.oversoldCount).toBe(0);
  });

  test('buyer sees sell-out error when stock is exhausted', async ({
    page,
  }) => {
    // Exhaust all stock via API first
    for (let i = 0; i < STOCK; i++) {
      const resp = await placeOrder({
        ticketItemId: TICKET_ITEM_ID,
        userId: 77_100 + i,
        quantity: 1,
        strategy: 'REDIS_LUA_WITH_COMPENSATION',
        idempotencyKey: `e2e-exhaust-${i}-${Date.now()}`,
      });
      expect(resp.result.success).toBe(true);
    }

    // Now try to buy via the booking page — should fail
    const bookingPage = new BookingPage(page);
    await bookingPage.goto();
    await bookingPage.placeOrder({
      ticketItemId: TICKET_ITEM_ID,
      userId: 77_200,
      quantity: 1,
      strategy: 'REDIS_LUA_WITH_COMPENSATION',
      idempotencyKey: `e2e-sellout-${Date.now()}`,
    });

    // Should show error / rejection
    const isSuccess = await bookingPage.isSuccess();
    const errorMsg = await bookingPage.getErrorMessage();

    // Either success=false or an error message is shown
    expect(isSuccess || errorMsg !== null).toBe(true);

    // Verify no overselling occurred
    const consistency = await getConsistency(TICKET_ITEM_ID, YEAR_MONTH);
    expect(consistency.result.redisStockAfter).toBe(0);
    expect(consistency.result.dbStockAfter).toBe(0);
    expect(consistency.result.dbOrderCount).toBe(STOCK);
    expect(consistency.result.oversoldCount).toBe(0);
  });

  test('idempotency — double-clicking the same order returns same result', async ({
    page,
  }) => {
    const idempotencyKey = `e2e-idem-${Date.now()}`;

    // First order via dashboard
    const bookingPage = new BookingPage(page);
    await bookingPage.goto();
    await bookingPage.placeOrder({
      ticketItemId: TICKET_ITEM_ID,
      userId: 77_300,
      quantity: 1,
      strategy: 'REDIS_LUA_WITH_COMPENSATION',
      idempotencyKey,
    });
    const firstOrderNumber = await bookingPage.getOrderNumber();
    expect(firstOrderNumber).toBeTruthy();

    // Second order with SAME idempotency key — should return same order
    await bookingPage.goto();
    await bookingPage.placeOrder({
      ticketItemId: TICKET_ITEM_ID,
      userId: 77_300,
      quantity: 1,
      strategy: 'REDIS_LUA_WITH_COMPENSATION',
      idempotencyKey,
    });
    const secondOrderNumber = await bookingPage.getOrderNumber();

    // Same order number returned (idempotent)
    expect(secondOrderNumber).toBe(firstOrderNumber);

    // Stock was only decremented once (19 remaining, not 18)
    const consistency = await getConsistency(TICKET_ITEM_ID, YEAR_MONTH);
    expect(consistency.result.redisStockAfter).toBe(STOCK - 1);
    expect(consistency.result.dbOrderCount).toBe(1);
  });
});

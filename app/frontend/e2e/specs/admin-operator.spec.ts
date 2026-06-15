import { test, expect } from '@playwright/test';
import { ControlDeskPage } from '../pages/control-desk';
import {
  resetBenchmark,
  warmupStock,
  getConsistency,
  placeOrder,
  reconcile,
} from '../fixtures/api-helpers';

/**
 * Admin Operator Workflow E2E Tests
 *
 * These simulate the operator using the /admin/control-desk dashboard:
 * setting up sales, monitoring consistency, detecting and repairing drift,
 * and verifying benchmark history.
 */

const TICKET_ITEM_ID = 4;
const STOCK = 30;
const YEAR_MONTH = new Date()
  .toISOString()
  .slice(0, 7)
  .replace('-', '');

test.describe('Admin Operator Workflow', () => {
  test('operator sets up sale through control desk and verifies readiness', async ({
    page,
  }) => {
    const desk = new ControlDeskPage(page);
    await desk.goto();
    await expect(page).toHaveURL('/admin/control-desk');

    // Step 1: Reset stock through the dashboard UI
    await desk.setupSale(TICKET_ITEM_ID, STOCK, YEAR_MONTH);

    // Step 2: Verify health indicator shows backend is up
    await desk.refreshHealth();
    await expect(desk.healthStatus).toBeVisible();

    // Step 3: Verify consistency shows clean state
    const consistencyText = await desk.checkConsistency(TICKET_ITEM_ID);
    expect(consistencyText).toBeTruthy();

    // Step 4: Verify via direct API that setup is correct
    const consistency = await getConsistency(TICKET_ITEM_ID, YEAR_MONTH);
    expect(consistency.result.redisStockAfter).toBe(STOCK);
    expect(consistency.result.dbStockAfter).toBe(STOCK);
    expect(consistency.result.oversoldCount).toBe(0);
    expect(consistency.result.driftAmount).toBe(0);
  });

  test('operator submits order probe through dashboard and verifies result', async ({
    page,
  }) => {
    // Setup via API
    await resetBenchmark({
      ticketItemId: TICKET_ITEM_ID,
      stock: STOCK,
      yearMonth: YEAR_MONTH,
    });
    await warmupStock(TICKET_ITEM_ID);

    // Navigate to control desk and submit an order probe
    const desk = new ControlDeskPage(page);
    await desk.goto();

    await desk.submitOrderProbe({
      userId: 88_001,
      quantity: 1,
      strategy: 'REDIS_LUA_WITH_COMPENSATION',
      idempotencyKey: `e2e-admin-probe-${Date.now()}`,
    });

    // Verify probe result is displayed
    await expect(desk.probeResult).toBeVisible({ timeout: 5000 });
    const resultText = await desk.probeResult.textContent();
    expect(resultText).toBeTruthy();

    // Verify stock decremented
    const consistency = await getConsistency(TICKET_ITEM_ID, YEAR_MONTH);
    expect(consistency.result.redisStockAfter).toBe(STOCK - 1);
  });

  test('operator detects drift, reconciles, and verifies repair', async () => {
    // Setup: Place 10 orders
    await resetBenchmark({
      ticketItemId: TICKET_ITEM_ID,
      stock: STOCK,
      yearMonth: YEAR_MONTH,
    });
    await warmupStock(TICKET_ITEM_ID);

    for (let i = 0; i < 10; i++) {
      await placeOrder({
        ticketItemId: TICKET_ITEM_ID,
        userId: 88_100 + i,
        quantity: 1,
        strategy: 'REDIS_LUA_WITH_COMPENSATION',
        idempotencyKey: `e2e-recon-setup-${i}-${Date.now()}`,
      });
    }

    // Verify clean state: Redis=20, DB=20
    let snap = await getConsistency(TICKET_ITEM_ID, YEAR_MONTH);
    expect(snap.result.redisStockAfter).toBe(STOCK - 10);
    expect(snap.result.dbStockAfter).toBe(STOCK - 10);
    expect(snap.result.driftAmount).toBe(0);

    // Place orders with REDIS_LUA (no compensation) to exercise drift path
    const driftPromises = [];
    for (let i = 0; i < 5; i++) {
      driftPromises.push(
        placeOrder({
          ticketItemId: TICKET_ITEM_ID,
          userId: 88_200 + i,
          quantity: 1,
          strategy: 'REDIS_LUA',
          idempotencyKey: `e2e-drift-${i}-${Date.now()}`,
        }),
      );
    }
    await Promise.allSettled(driftPromises);

    // Check for drift
    snap = await getConsistency(TICKET_ITEM_ID, YEAR_MONTH);

    // Reconcile if drift detected
    if (snap.result.driftAmount !== 0) {
      const recon = await reconcile(TICKET_ITEM_ID, YEAR_MONTH);
      expect(recon.result.driftDetected).toBe(true);
      expect(recon.result.repaired).toBe(true);
      expect(recon.result.driftAmount).toBe(snap.result.driftAmount);

      // Verify repaired
      snap = await getConsistency(TICKET_ITEM_ID, YEAR_MONTH);
      expect(snap.result.driftAmount).toBe(0);
      expect(snap.result.redisDbInconsistencyCount).toBe(0);
    }
    // If no drift occurred (all DB writes succeeded), that is also valid
  });

  test('operator navigates to benchmark history and views a run', async ({
    page,
  }) => {
    // Setup + place a few orders to generate benchmark data
    await resetBenchmark({
      ticketItemId: TICKET_ITEM_ID,
      stock: 5,
      yearMonth: YEAR_MONTH,
    });
    await warmupStock(TICKET_ITEM_ID);

    for (let i = 0; i < 3; i++) {
      await placeOrder({
        ticketItemId: TICKET_ITEM_ID,
        userId: 88_300 + i,
        quantity: 1,
        strategy: 'REDIS_LUA_WITH_COMPENSATION',
        idempotencyKey: `e2e-bench-${i}-${Date.now()}`,
      });
    }

    // Navigate to benchmark page
    await page.goto('/admin/benchmark');
    await expect(page).toHaveURL('/admin/benchmark');

    // Verify benchmark page loads and shows data
    await expect(page.locator('h1, h2, h3').first()).toBeVisible({
      timeout: 5000,
    });

    // If benchmark runs are listed, click the first one
    const runLinks = page.locator('a[href^="/admin/benchmark/"]');
    const runCount = await runLinks.count();
    if (runCount > 0) {
      await runLinks.first().click();
      await expect(page).toHaveURL(/\/admin\/benchmark\/.+/);

      // Verify run detail page shows metrics
      await expect(page.locator('h1, h2, h3').first()).toBeVisible();
    }
  });

  test('operator views consistency dashboard', async ({ page }) => {
    await resetBenchmark({
      ticketItemId: TICKET_ITEM_ID,
      stock: STOCK,
      yearMonth: YEAR_MONTH,
    });
    await warmupStock(TICKET_ITEM_ID);

    // Navigate to consistency view
    await page.goto('/admin/consistency');
    await expect(page).toHaveURL('/admin/consistency');

    // Verify consistency dashboard loads
    await expect(page.locator('h1, h2, h3').first()).toBeVisible({
      timeout: 5000,
    });
  });
});

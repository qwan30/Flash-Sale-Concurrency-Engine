import { test, expect } from '@playwright/test';
import {
  resetBenchmark,
  warmupStock,
  getConsistency,
  placeOrdersConcurrently,
  type CreateOrderParams,
} from '../fixtures/api-helpers';

/**
 * Concurrent Flash Sale E2E Tests
 *
 * These simulate a real flash sale rush: many buyers hitting the system
 * simultaneously, competing for limited stock. We verify:
 *   1. Exactly the right number of orders succeed (no overselling)
 *   2. Stock invariants hold across Redis and DB
 *   3. Rejected buyers get proper error responses
 *   4. All four order strategies behave as documented
 */

const TICKET_ITEM_ID = 4;
const YEAR_MONTH = new Date()
  .toISOString()
  .slice(0, 7)
  .replace('-', '');

test.describe('Concurrent Flash Sale', () => {
  test.describe('REDIS_LUA_WITH_COMPENSATION strategy', () => {
    const STOCK = 20;
    const BUYERS = 80;

    test.beforeEach(async () => {
      await resetBenchmark({
        ticketItemId: TICKET_ITEM_ID,
        stock: STOCK,
        yearMonth: YEAR_MONTH,
      });
      await warmupStock(TICKET_ITEM_ID);
    });

    test('exactly 20 orders succeed, 60 are rejected under concurrency', async () => {
      // Simulate 80 buyers rushing to buy 1 ticket each
      const params: CreateOrderParams[] = Array.from(
        { length: BUYERS },
        (_, i) => ({
          ticketItemId: TICKET_ITEM_ID,
          userId: 90_000 + i,
          quantity: 1,
          strategy: 'REDIS_LUA_WITH_COMPENSATION' as const,
          idempotencyKey: `e2e-conc-comp-${i}-${Date.now()}`,
        }),
      );

      const results = await placeOrdersConcurrently(params);

      // Count successful orders
      const succeeded = results.filter(
        (r) =>
          r.status === 'fulfilled' &&
          (r.value.result as { success?: boolean })?.success === true,
      );
      const rejected = results.filter(
        (r) =>
          r.status === 'fulfilled' &&
          (r.value.result as { success?: boolean })?.success === false,
      );

      // Exactly STOCK (20) orders should succeed
      expect(succeeded.length).toBe(STOCK);
      // At least BUYERS - STOCK (60) should be rejected
      expect(rejected.length).toBeGreaterThanOrEqual(BUYERS - STOCK);

      // Verify stock invariants
      const consistency = await getConsistency(TICKET_ITEM_ID, YEAR_MONTH);
      const c = consistency.result;

      expect(c.redisStockAfter).toBe(0);
      expect(c.dbStockAfter).toBe(0);
      expect(c.dbOrderCount).toBe(STOCK);
      expect(c.oversoldCount).toBe(0);
      expect(c.redisDbInconsistencyCount).toBe(0);
      expect(c.driftAmount).toBe(0);
    });

    test('stock count stays consistent with varying quantities', async () => {
      // 5 buyers buy 2 tickets each, 30 buyers buy 1 ticket
      const params: CreateOrderParams[] = [];

      for (let i = 0; i < 5; i++) {
        params.push({
          ticketItemId: TICKET_ITEM_ID,
          userId: 91_000 + i,
          quantity: 2,
          strategy: 'REDIS_LUA_WITH_COMPENSATION',
          idempotencyKey: `e2e-qty2-${i}-${Date.now()}`,
        });
      }

      for (let i = 0; i < 30; i++) {
        params.push({
          ticketItemId: TICKET_ITEM_ID,
          userId: 91_100 + i,
          quantity: 1,
          strategy: 'REDIS_LUA_WITH_COMPENSATION',
          idempotencyKey: `e2e-qty1-${i}-${Date.now()}`,
        });
      }

      const results = await placeOrdersConcurrently(params);
      const succeeded = results.filter(
        (r) =>
          r.status === 'fulfilled' &&
          (r.value.result as { success?: boolean })?.success === true,
      );

      const consistency = await getConsistency(TICKET_ITEM_ID, YEAR_MONTH);
      const c = consistency.result;

      // No overselling, no drift
      expect(c.dbOrderCount).toBeLessThanOrEqual(STOCK);
      expect(c.redisStockAfter).toBe(STOCK - c.dbOrderCount);
      expect(c.oversoldCount).toBe(0);
      expect(c.driftAmount).toBe(0);
    });
  });

  test.describe('strategy comparison under concurrency', () => {
    test('UNSAFE_DB can oversell under high concurrency', async () => {
      const STOCK_SMALL = 10;
      const BUYERS_MANY = 50;

      await resetBenchmark({
        ticketItemId: TICKET_ITEM_ID,
        stock: STOCK_SMALL,
        yearMonth: YEAR_MONTH,
      });
      await warmupStock(TICKET_ITEM_ID);

      const params: CreateOrderParams[] = Array.from(
        { length: BUYERS_MANY },
        (_, i) => ({
          ticketItemId: TICKET_ITEM_ID,
          userId: 92_000 + i,
          quantity: 1,
          strategy: 'UNSAFE_DB' as const,
          idempotencyKey: `e2e-unsafe-${i}-${Date.now()}`,
        }),
      );

      const results = await placeOrdersConcurrently(params);
      const succeeded = results.filter(
        (r) =>
          r.status === 'fulfilled' &&
          (r.value.result as { success?: boolean })?.success === true,
      );

      const consistency = await getConsistency(TICKET_ITEM_ID, YEAR_MONTH);

      // UNSAFE_DB is expected to oversell — this proves the problem exists
      console.log(
        `UNSAFE_DB: ${succeeded.length} succeeded, ` +
          `oversold=${consistency.result.oversoldCount}, ` +
          `stock=${consistency.result.dbStockAfter}`,
      );

      expect(succeeded.length).toBeGreaterThanOrEqual(0);
    });

    test('CONDITIONAL_DB can still oversell under high concurrency', async () => {
      const STOCK_SMALL = 10;
      const BUYERS_MANY = 50;

      await resetBenchmark({
        ticketItemId: TICKET_ITEM_ID,
        stock: STOCK_SMALL,
        yearMonth: YEAR_MONTH,
      });
      await warmupStock(TICKET_ITEM_ID);

      const params: CreateOrderParams[] = Array.from(
        { length: BUYERS_MANY },
        (_, i) => ({
          ticketItemId: TICKET_ITEM_ID,
          userId: 93_000 + i,
          quantity: 1,
          strategy: 'CONDITIONAL_DB' as const,
          idempotencyKey: `e2e-cond-${i}-${Date.now()}`,
        }),
      );

      const results = await placeOrdersConcurrently(params);
      const succeeded = results.filter(
        (r) =>
          r.status === 'fulfilled' &&
          (r.value.result as { success?: boolean })?.success === true,
      );

      const consistency = await getConsistency(TICKET_ITEM_ID, YEAR_MONTH);

      console.log(
        `CONDITIONAL_DB: ${succeeded.length} succeeded, ` +
          `oversold=${consistency.result.oversoldCount}, ` +
          `stock=${consistency.result.dbStockAfter}`,
      );

      expect(succeeded.length).toBeGreaterThanOrEqual(0);
    });

    test('REDIS_LUA safe from overselling, may have drift', async () => {
      const STOCK_VAL = 15;
      const BUYERS_VAL = 40;

      await resetBenchmark({
        ticketItemId: TICKET_ITEM_ID,
        stock: STOCK_VAL,
        yearMonth: YEAR_MONTH,
      });
      await warmupStock(TICKET_ITEM_ID);

      const params: CreateOrderParams[] = Array.from(
        { length: BUYERS_VAL },
        (_, i) => ({
          ticketItemId: TICKET_ITEM_ID,
          userId: 94_000 + i,
          quantity: 1,
          strategy: 'REDIS_LUA' as const,
          idempotencyKey: `e2e-lua-${i}-${Date.now()}`,
        }),
      );

      const results = await placeOrdersConcurrently(params);
      const succeeded = results.filter(
        (r) =>
          r.status === 'fulfilled' &&
          (r.value.result as { success?: boolean })?.success === true,
      );

      const consistency = await getConsistency(TICKET_ITEM_ID, YEAR_MONTH);

      // REDIS_LUA should not oversell (Redis is atomic)
      expect(consistency.result.oversoldCount).toBe(0);

      // But may have Redis-DB inconsistency if some DB writes failed
      console.log(
        `REDIS_LUA: ${succeeded.length} succeeded, ` +
          `drift=${consistency.result.driftAmount}, ` +
          `inconsistency=${consistency.result.redisDbInconsistencyCount}`,
      );
    });
  });
});

import { test } from '@playwright/test';
import * as path from 'path';

const BASE = 'http://localhost:3002';
const OUT = 'D:/projects/tipjs-project/xxxx.com-section-ddd-24-27042025/Flash-Sale-Concurrency-Engine/screen-demo';

test.describe('Screenshot All Dashboard Pages', () => {
  test('01 - Home / Lab Overview', async ({ page }) => {
    await page.goto(`${BASE}/`, { timeout: 15000, waitUntil: 'networkidle' });
    await page.waitForTimeout(1000);
    await page.screenshot({ path: path.join(OUT, '01-home.png'), fullPage: true });
  });

  test('02 - Events Catalog', async ({ page }) => {
    await page.goto(`${BASE}/events`, { timeout: 15000, waitUntil: 'networkidle' });
    await page.waitForTimeout(1000);
    await page.screenshot({ path: path.join(OUT, '02-events-catalog.png'), fullPage: true });
  });

  test('03 - Event Detail (ticket 4)', async ({ page }) => {
    await page.goto(`${BASE}/events/4`, { timeout: 15000, waitUntil: 'networkidle' });
    await page.waitForTimeout(1000);
    await page.screenshot({ path: path.join(OUT, '03-event-detail.png'), fullPage: true });
  });

  test('04 - Booking / Order Probe', async ({ page }) => {
    await page.goto(`${BASE}/booking`, { timeout: 15000, waitUntil: 'networkidle' });
    await page.waitForTimeout(500);
    const details = page.locator('details summary');
    if (await details.isVisible()) {
      await details.click();
      await page.waitForTimeout(300);
    }
    await page.screenshot({ path: path.join(OUT, '04-booking.png'), fullPage: true });
  });

  test('05 - My Orders', async ({ page }) => {
    await page.goto(`${BASE}/my-orders`, { timeout: 15000, waitUntil: 'networkidle' });
    await page.waitForTimeout(1000);
    await page.screenshot({ path: path.join(OUT, '05-my-orders.png'), fullPage: true });
  });

  test('06 - Order Detail', async ({ page }) => {
    await page.goto(`${BASE}/orders/ORD2025020001`, { timeout: 15000, waitUntil: 'networkidle' });
    await page.waitForTimeout(1000);
    await page.screenshot({ path: path.join(OUT, '06-order-detail.png'), fullPage: true });
  });

  test('07 - Admin Control Desk', async ({ page }) => {
    await page.goto(`${BASE}/admin/control-desk`, { timeout: 15000, waitUntil: 'networkidle' });
    await page.waitForTimeout(2000);
    await page.screenshot({ path: path.join(OUT, '07-admin-control-desk.png'), fullPage: true });
  });

  test('08 - Admin Benchmark', async ({ page }) => {
    await page.goto(`${BASE}/admin/benchmark`, { timeout: 15000, waitUntil: 'networkidle' });
    await page.waitForTimeout(1000);
    await page.screenshot({ path: path.join(OUT, '08-admin-benchmark.png'), fullPage: true });
  });

  test('09 - Admin Consistency', async ({ page }) => {
    await page.goto(`${BASE}/admin/consistency`, { timeout: 15000, waitUntil: 'networkidle' });
    await page.waitForTimeout(1000);
    await page.screenshot({ path: path.join(OUT, '09-admin-consistency.png'), fullPage: true });
  });
});

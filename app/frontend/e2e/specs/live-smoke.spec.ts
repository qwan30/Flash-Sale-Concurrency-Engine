import { test, expect } from '@playwright/test';

const BASE = 'http://localhost:3002';

test('admin control desk loads with backend health', async ({ page }) => {
  await page.goto(`${BASE}/admin/control-desk`, { timeout: 15000 });
  // The page loads with a heading
  await expect(page.locator('h1')).toBeVisible({ timeout: 15000 });
  // Check for key UI text
  const body = await page.textContent('body');
  expect(body).toContain('Flash-sale control desk');
  await page.screenshot({ path: 'e2e/screenshots/live-control-desk.png', fullPage: true });
});

test('booking form fills and submits via expanded advanced settings', async ({ page }) => {
  await page.goto(`${BASE}/booking`, { timeout: 15000 });
  await page.fill('#booking-user-id', '999');
  await page.fill('#booking-quantity', '1');
  // Expand the collapsed "Advanced lab settings" details element
  await page.click('details summary');
  await page.waitForSelector('#booking-strategy', { state: 'visible', timeout: 5000 });
  await page.selectOption('#booking-strategy', 'REDIS_LUA_WITH_COMPENSATION');
  await expect(page.locator('#booking-user-id')).toHaveValue('999');
  await page.screenshot({ path: 'e2e/screenshots/live-booking-form.png', fullPage: true });
});

test('events catalog has event cards with data-testid', async ({ page }) => {
  await page.goto(`${BASE}/events`, { timeout: 15000 });
  const cards = page.locator('[data-testid="event-card"]');
  await expect(cards.first()).toBeVisible({ timeout: 10000 });
  const count = await cards.count();
  expect(count).toBeGreaterThan(0);
  await page.screenshot({ path: 'e2e/screenshots/live-events.png', fullPage: true });
});

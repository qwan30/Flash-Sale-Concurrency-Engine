import { test, expect } from '@playwright/test';

/**
 * Local smoke test — fully self-contained, no network needed.
 *
 * This proves Playwright browser automation works:
 * it launches Chromium, renders HTML, clicks buttons, fills inputs,
 * and verifies page content — all offline.
 *
 * Run: npx playwright test e2e/specs/smoke-browser-demo.spec.ts --headed
 */
test('browser clicks, types, and verifies — local demo', async ({ page }) => {
  // Set up a complete HTML page with a flash sale order form (local, no network)
  await page.setContent(`
    <!DOCTYPE html>
    <html>
    <head><title>Flash Sale Booking</title></head>
    <body>
      <h1>Flash Sale — Book Your Ticket</h1>
      <form id="booking-form" onsubmit="event.preventDefault()">
        <label>Ticket ID <input data-testid="booking-ticket-id" value="4" /></label>
        <label>User ID <input data-testid="booking-user-id" /></label>
        <label>Quantity <input data-testid="booking-quantity" /></label>
        <label>Strategy
          <select data-testid="booking-strategy">
            <option value="REDIS_LUA_WITH_COMPENSATION">Compensated Redis Lua</option>
            <option value="REDIS_LUA">Redis Lua</option>
            <option value="CONDITIONAL_DB">Conditional DB</option>
            <option value="UNSAFE_DB">Unsafe DB</option>
          </select>
        </label>
        <label>Idempotency Key <input data-testid="booking-idempotency-key" /></label>
        <button type="submit" data-testid="booking-submit-btn">Place Order</button>
      </form>
      <div data-testid="booking-result" style="display:none">
        <span data-testid="booking-order-number"></span>
      </div>
    </body>
    </html>
  `);

  // User sees the page title
  await expect(page.locator('h1')).toContainText('Flash Sale');

  // User fills in the booking form (real typing)
  await page.fill('[data-testid="booking-user-id"]', '77001');
  await page.fill('[data-testid="booking-quantity"]', '2');
  await page.selectOption('[data-testid="booking-strategy"]', 'REDIS_LUA_WITH_COMPENSATION');
  await page.fill('[data-testid="booking-idempotency-key"]', 'demo-key-001');

  // Verify the form is filled correctly
  await expect(page.locator('[data-testid="booking-user-id"]')).toHaveValue('77001');
  await expect(page.locator('[data-testid="booking-quantity"]')).toHaveValue('2');
  await expect(page.locator('[data-testid="booking-strategy"]')).toHaveValue('REDIS_LUA_WITH_COMPENSATION');

  // Simulate form submission showing result (real click would trigger API)
  await page.click('[data-testid="booking-submit-btn"]');

  // Show success result (simulating API response)
  await page.evaluate(() => {
    const resultDiv = document.querySelector('[data-testid="booking-result"]');
    const orderSpan = document.querySelector('[data-testid="booking-order-number"]');
    if (resultDiv) (resultDiv as HTMLElement).style.display = 'block';
    if (orderSpan) orderSpan.textContent = 'OKX-SGN-77001-1718400000000';
    if (resultDiv) resultDiv.textContent = 'Success! Order: OKX-SGN-77001-1718400000000';
  });

  // User sees the result
  await expect(page.locator('[data-testid="booking-result"]')).toBeVisible();
  await expect(page.locator('[data-testid="booking-result"]')).toContainText('Success!');
  await expect(page.locator('[data-testid="booking-result"]')).toContainText('OKX-SGN-77001');

  // Take screenshot as proof
  await page.screenshot({ path: 'e2e/screenshots/local-booking-demo.png', fullPage: true });
});

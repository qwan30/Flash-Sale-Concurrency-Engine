import type { Page, Locator } from '@playwright/test';

/**
 * Page Object for /booking — the controlled order probe page.
 *
 * Users place individual orders here by filling in ticket ID, quantity,
 * strategy, and idempotency key, then viewing the result.
 */
export class BookingPage {
  readonly page: Page;
  readonly url = '/booking';

  readonly ticketItemIdInput: Locator;
  readonly userIdInput: Locator;
  readonly quantityInput: Locator;
  readonly strategySelect: Locator;
  readonly idempotencyKeyInput: Locator;
  readonly submitButton: Locator;

  readonly orderResult: Locator;
  readonly orderNumberDisplay: Locator;
  readonly stockAfterDisplay: Locator;
  readonly errorMessage: Locator;

  constructor(page: Page) {
    this.page = page;

    // Form fields
    this.ticketItemIdInput = page.locator('[data-testid="booking-ticket-id"]');
    this.userIdInput = page.locator('[data-testid="booking-user-id"]');
    this.quantityInput = page.locator('[data-testid="booking-quantity"]');
    this.strategySelect = page.locator('[data-testid="booking-strategy"]');
    this.idempotencyKeyInput = page.locator('[data-testid="booking-idempotency-key"]');
    this.submitButton = page.locator('[data-testid="booking-submit-btn"]');

    // Results
    this.orderResult = page.locator('[data-testid="booking-result"]');
    this.orderNumberDisplay = page.locator('[data-testid="booking-order-number"]');
    this.stockAfterDisplay = page.locator('[data-testid="booking-stock-after"]');
    this.errorMessage = page.locator('[data-testid="booking-error"]');
  }

  async goto() {
    await this.page.goto(this.url);
  }

  /**
   * Fill and submit the booking form like a real buyer would.
   */
  async placeOrder(params: {
    ticketItemId: number;
    userId: number;
    quantity: number;
    strategy: string;
    idempotencyKey: string;
  }) {
    await this.ticketItemIdInput.fill(String(params.ticketItemId));
    await this.userIdInput.fill(String(params.userId));
    await this.quantityInput.fill(String(params.quantity));
    await this.strategySelect.selectOption(params.strategy);
    await this.idempotencyKeyInput.fill(params.idempotencyKey);
    await this.submitButton.click();
    await this.orderResult.waitFor({ state: 'visible', timeout: 5000 });
  }

  /**
   * Get the displayed order number after a successful order.
   */
  async getOrderNumber(): Promise<string | null> {
    return this.orderNumberDisplay.textContent();
  }

  /**
   * Check if the result indicates success.
   */
  async isSuccess(): Promise<boolean> {
    const text = await this.orderResult.textContent();
    return text?.includes('success') ?? false;
  }

  /**
   * Get the error message if order failed.
   */
  async getErrorMessage(): Promise<string | null> {
    if (await this.errorMessage.isVisible()) {
      return this.errorMessage.textContent();
    }
    return null;
  }
}

import type { Page, Locator } from '@playwright/test';

/**
 * Page Object for /admin/control-desk — the operator's primary dashboard.
 *
 * This is where an operator resets stock, warms Redis, runs order probes,
 * and checks consistency before and after a flash sale.
 */
export class ControlDeskPage {
  readonly page: Page;
  readonly url = '/admin/control-desk';

  // Stock management
  readonly ticketItemIdInput: Locator;
  readonly stockInput: Locator;
  readonly yearMonthInput: Locator;
  readonly resetButton: Locator;
  readonly warmupButton: Locator;

  // Order probe
  readonly probeUserIdInput: Locator;
  readonly probeQuantityInput: Locator;
  readonly probeStrategySelect: Locator;
  readonly probeIdempotencyKeyInput: Locator;
  readonly probeSubmitButton: Locator;
  readonly probeResult: Locator;

  // Consistency
  readonly consistencyTicketIdInput: Locator;
  readonly consistencyCheckButton: Locator;
  readonly consistencyResult: Locator;

  // Health
  readonly healthStatus: Locator;
  readonly healthRefreshButton: Locator;

  constructor(page: Page) {
    this.page = page;

    // Stock management
    this.ticketItemIdInput = page.locator('[data-testid="control-ticket-item-id"]');
    this.stockInput = page.locator('[data-testid="control-stock"]');
    this.yearMonthInput = page.locator('[data-testid="control-year-month"]');
    this.resetButton = page.locator('[data-testid="control-reset-btn"]');
    this.warmupButton = page.locator('[data-testid="control-warmup-btn"]');

    // Order probe
    this.probeUserIdInput = page.locator('[data-testid="probe-user-id"]');
    this.probeQuantityInput = page.locator('[data-testid="probe-quantity"]');
    this.probeStrategySelect = page.locator('[data-testid="probe-strategy"]');
    this.probeIdempotencyKeyInput = page.locator('[data-testid="probe-idempotency-key"]');
    this.probeSubmitButton = page.locator('[data-testid="probe-submit-btn"]');
    this.probeResult = page.locator('[data-testid="probe-result"]');

    // Consistency
    this.consistencyTicketIdInput = page.locator('[data-testid="consistency-ticket-id"]');
    this.consistencyCheckButton = page.locator('[data-testid="consistency-check-btn"]');
    this.consistencyResult = page.locator('[data-testid="consistency-result"]');

    // Health
    this.healthStatus = page.locator('[data-testid="health-status"]');
    this.healthRefreshButton = page.locator('[data-testid="health-refresh-btn"]');
  }

  async goto() {
    await this.page.goto(this.url);
  }

  /**
   * Full operator workflow: reset stock → warmup Redis → verify ready.
   */
  async setupSale(ticketItemId: number, stock: number, yearMonth: string) {
    await this.ticketItemIdInput.fill(String(ticketItemId));
    await this.stockInput.fill(String(stock));
    await this.yearMonthInput.fill(yearMonth);
    await this.resetButton.click();
    await this.page.locator('text=success').first().waitFor({ timeout: 5000 });

    await this.warmupButton.click();
    await this.page.locator('text=success').first().waitFor({ timeout: 5000 });
  }

  /**
   * Submit a single order probe through the dashboard UI.
   */
  async submitOrderProbe(params: {
    userId: number;
    quantity: number;
    strategy: string;
    idempotencyKey: string;
  }) {
    await this.probeUserIdInput.fill(String(params.userId));
    await this.probeQuantityInput.fill(String(params.quantity));
    await this.probeStrategySelect.selectOption(params.strategy);
    await this.probeIdempotencyKeyInput.fill(params.idempotencyKey);
    await this.probeSubmitButton.click();
  }

  /**
   * Check consistency for a ticket item and return the result text.
   */
  async checkConsistency(ticketItemId: number) {
    await this.consistencyTicketIdInput.fill(String(ticketItemId));
    await this.consistencyCheckButton.click();
    await this.consistencyResult.waitFor({ state: 'visible', timeout: 5000 });
    return this.consistencyResult.textContent();
  }

  async refreshHealth() {
    await this.healthRefreshButton.click();
  }
}

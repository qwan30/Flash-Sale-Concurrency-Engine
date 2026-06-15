import type { Page, Locator } from '@playwright/test';

/**
 * Page Object for /events — the fixture catalog.
 *
 * Buyers browse available tickets here before placing orders.
 */
export class EventsPage {
  readonly page: Page;
  readonly url = '/events';

  readonly eventCards: Locator;
  readonly eventLinks: Locator;

  constructor(page: Page) {
    this.page = page;
    this.eventCards = page.locator('[data-testid="event-card"]');
    this.eventLinks = page.locator('a[href^="/events/"]');
  }

  async goto() {
    await this.page.goto(this.url);
  }

  /**
   * Click on the first event card to view its detail.
   */
  async clickFirstEvent() {
    await this.eventLinks.first().click();
    await this.page.waitForURL(/\/events\/\d+/);
  }

  /**
   * Click on a specific event by its ticketItemId.
   */
  async clickEvent(ticketItemId: number) {
    await this.page.locator(`a[href="/events/${ticketItemId}"]`).click();
    await this.page.waitForURL(`/events/${ticketItemId}`);
  }

  /**
   * Returns the number of event cards visible on the page.
   */
  async eventCount(): Promise<number> {
    return this.eventCards.count();
  }
}

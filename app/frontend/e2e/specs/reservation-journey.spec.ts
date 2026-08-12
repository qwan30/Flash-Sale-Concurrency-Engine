import { test, expect } from '@playwright/test';

const reservationId = '11111111-1111-4111-8111-111111111111';
const orderId = '22222222-2222-4222-8222-222222222222';
const overloadAcceptedReservationId = '33333333-3333-4333-8333-333333333333';
const overloadProcessingReservationId = '44444444-4444-4444-8444-444444444444';

test('reserve then confirm uses the live reservation API contract', async ({ page }) => {
  let reservationStatus = 'RESERVED';
  let createHeaders: Record<string, string> | null = null;
  const expiresAt = new Date(Date.now() + 120_000).toISOString();

  await page.route('**/api/backend/api/v1/inventory/4', async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        ticketItemId: 4,
        initial: 1000,
        available: reservationStatus === 'RESERVED' ? 999 : 999,
        reserved: reservationStatus === 'RESERVED' ? 1 : 0,
        confirmed: reservationStatus === 'CONFIRMED' ? 1 : 0,
      }),
    });
  });

  await page.route('**/api/backend/api/v1/reservations', async (route) => {
    createHeaders = route.request().headers();
    await route.fulfill({
      status: 201,
      contentType: 'application/json',
      body: JSON.stringify({
        reservationId,
        operationId: reservationId,
        ticketItemId: 4,
        demoActorId: createHeaders['x-demo-actor-id'],
        quantity: 1,
        status: 'RESERVED',
        expiresAt,
        terminalAt: null,
        orderId: null,
        outcome: 'NEW',
        resultCode: 'NEW',
        stockAfter: 999,
      }),
    });
  });

  await page.route(`**/api/backend/api/v1/reservations/${reservationId}/confirm`, async (route) => {
    reservationStatus = 'CONFIRMED';
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        reservationId,
        operationId: reservationId,
        ticketItemId: 4,
        demoActorId: createHeaders?.['x-demo-actor-id'] ?? null,
        quantity: 1,
        status: 'CONFIRMED',
        expiresAt,
        terminalAt: '2030-01-01T00:01:00Z',
        orderId,
        outcome: 'CONFIRMED',
        resultCode: 'CONFIRMED',
        stockAfter: null,
      }),
    });
  });

  await page.goto('/events/4');
  await expect(page.getByTestId('reservation-journey')).toBeVisible();
  await expect(page.getByTestId('reservation-demo-disclaimer'))
    .toHaveText('Demo session only; no login or real authentication is used.');
  await expect(page.getByTestId('reservation-stock-buckets')).toContainText('1,000');

  await page.getByTestId('reservation-primary-action').click();
  await expect(page.getByTestId('reservation-timeline')).toContainText('RESERVED');
  await expect(page.getByTestId('reservation-countdown')).toHaveText(/Reservation countdown: (?:120|11\d)s/);
  expect(createHeaders?.['idempotency-key']).toBeTruthy();
  expect(createHeaders?.['x-demo-actor-id']).toMatch(/^[0-9a-f-]{36}$/);

  await page.getByTestId('reservation-primary-action').click();
  await expect(page.getByTestId('reservation-primary-action')).toHaveText('View confirmed order');
  await expect(page.getByTestId('reservation-primary-action')).toHaveAttribute('href', '#reservation-timeline');
  await expect(page.getByTestId('reservation-timeline')).toContainText('CONFIRMED');
  await expect(page.getByTestId('reservation-timeline')).toContainText(orderId);
  await expect(page.getByTestId('reservation-chaos-drawer')).toHaveCount(0);
});

test('reserve then release exposes the released retry path', async ({ page }) => {
  let state = 'RESERVED';

  await page.route('**/api/backend/api/v1/inventory/4', async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        ticketItemId: 4,
        initial: 1000,
        available: state === 'RESERVED' ? 999 : 1000,
        reserved: state === 'RESERVED' ? 1 : 0,
        confirmed: 0,
      }),
    });
  });

  await page.route('**/api/backend/api/v1/reservations', async (route) => {
    await route.fulfill({
      status: 201,
      contentType: 'application/json',
      body: JSON.stringify({
        reservationId,
        operationId: reservationId,
        ticketItemId: 4,
        demoActorId: 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa',
        quantity: 1,
        status: 'RESERVED',
        expiresAt: new Date(Date.now() + 120_000).toISOString(),
        terminalAt: null,
        orderId: null,
        outcome: 'NEW',
        resultCode: 'NEW',
        stockAfter: 999,
      }),
    });
  });

  await page.route(`**/api/backend/api/v1/reservations/${reservationId}/release`, async (route) => {
    state = 'RELEASED';
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        reservationId,
        operationId: reservationId,
        ticketItemId: 4,
        demoActorId: 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa',
        quantity: 1,
        status: 'RELEASED',
        expiresAt: null,
        terminalAt: new Date().toISOString(),
        orderId: null,
        outcome: 'RELEASED',
        resultCode: 'RELEASED',
        stockAfter: 1000,
      }),
    });
  });

  await page.goto('/events/4');
  await page.getByTestId('reservation-primary-action').click();
  await page.getByText('Try another outcome').click();
  await page.getByTestId('reservation-release').click();

  await expect(page.getByTestId('reservation-timeline')).toContainText('RELEASED');
  await expect(page.getByTestId('reservation-primary-action')).toHaveText('Try again');
});

test('expiry probe reads the terminal state from the GET endpoint', async ({ page }) => {
  let expired = false;

  await page.route('**/api/backend/api/v1/inventory/4', async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        ticketItemId: 4,
        initial: 1000,
        available: expired ? 1000 : 999,
        reserved: expired ? 0 : 1,
        confirmed: 0,
      }),
    });
  });

  await page.route('**/api/backend/api/v1/reservations', async (route) => {
    await route.fulfill({
      status: 201,
      contentType: 'application/json',
      body: JSON.stringify({
        reservationId,
        operationId: reservationId,
        ticketItemId: 4,
        demoActorId: 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa',
        quantity: 1,
        status: 'RESERVED',
        expiresAt: new Date(Date.now() + 120_000).toISOString(),
        terminalAt: null,
        orderId: null,
        outcome: 'NEW',
        resultCode: 'NEW',
        stockAfter: 999,
      }),
    });
  });

  await page.route(`**/api/backend/api/v1/reservations/${reservationId}`, async (route) => {
    expired = true;
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        reservationId,
        operationId: null,
        ticketItemId: 4,
        demoActorId: 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa',
        quantity: 1,
        status: 'EXPIRED',
        expiresAt: new Date(Date.now() - 1_000).toISOString(),
        terminalAt: new Date().toISOString(),
        orderId: null,
        outcome: 'EXPIRED',
        resultCode: 'EXPIRED',
        stockAfter: 1000,
      }),
    });
  });

  await page.goto('/events/4');
  await page.getByTestId('reservation-primary-action').click();
  await page.getByText('Try another outcome').click();
  await page.getByTestId('reservation-expiry').click();

  await expect(page.getByTestId('reservation-scenario-status')).toContainText('EXPIRED');
  await expect(page.getByTestId('reservation-timeline')).toContainText('EXPIRED');
  await expect(page.getByTestId('reservation-primary-action')).toHaveText('Try again');
});

test('replay control reuses the original idempotency key', async ({ page }) => {
  const idempotencyKeys: string[] = [];

  await page.route('**/api/backend/api/v1/inventory/4', async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ ticketItemId: 4, initial: 1000, available: 999, reserved: 1, confirmed: 0 }),
    });
  });

  await page.route('**/api/backend/api/v1/reservations', async (route) => {
    idempotencyKeys.push(route.request().headers()['idempotency-key']);
    await route.fulfill({
      status: idempotencyKeys.length === 1 ? 201 : 200,
      contentType: 'application/json',
      body: JSON.stringify({
        reservationId,
        operationId: reservationId,
        ticketItemId: 4,
        demoActorId: 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa',
        quantity: 1,
        status: 'RESERVED',
        expiresAt: new Date(Date.now() + 120_000).toISOString(),
        terminalAt: null,
        orderId: null,
        outcome: 'NEW',
        resultCode: 'NEW',
        stockAfter: 999,
      }),
    });
  });

  await page.goto('/events/4');
  await page.getByTestId('reservation-primary-action').click();
  await page.getByText('Try another outcome').click();
  await page.getByTestId('reservation-replay').click();

  await expect.poll(() => idempotencyKeys.length).toBe(2);
  expect(idempotencyKeys[0]).toBe(idempotencyKeys[1]);
});

test('sold-out and overload controls expose bounded API outcomes', async ({ page }) => {
  let overloadRequests = 0;
  let processingChecks = 0;

  await page.route('**/api/backend/api/v1/inventory/4', async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ ticketItemId: 4, initial: 4, available: 0, reserved: 0, confirmed: 4 }),
    });
  });

  await page.route('**/api/backend/api/v1/reservations', async (route) => {
    const request = route.request().postDataJSON() as { quantity: number };
    if (request.quantity === 4) {
      await route.fulfill({
        status: 409,
        contentType: 'application/json',
        body: JSON.stringify({
          code: 'SOLD_OUT',
          message: 'Ticket inventory is sold out',
          retryable: false,
          traceId: 'trace-sold-out',
          stockAfter: 0,
        }),
      });
      return;
    }
    overloadRequests += 1;
    if (overloadRequests === 1) {
      await route.fulfill({
        status: 201,
        contentType: 'application/json',
        body: JSON.stringify({
          reservationId: overloadAcceptedReservationId,
          operationId: overloadAcceptedReservationId,
          ticketItemId: 4,
          demoActorId: 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa',
          quantity: 1,
          status: 'RESERVED',
          expiresAt: new Date(Date.now() + 120_000).toISOString(),
          terminalAt: null,
          orderId: null,
          outcome: 'NEW',
          resultCode: 'NEW',
          stockAfter: 3,
        }),
      });
      return;
    }
    if (overloadRequests === 2) {
      await route.fulfill({
        status: 202,
        contentType: 'application/json',
        body: JSON.stringify({
          reservationId: overloadProcessingReservationId,
          operationId: overloadProcessingReservationId,
          status: 'PROCESSING',
          journalState: 'RECEIVED',
          retryAfterSeconds: 1,
          traceId: 'trace-overload-processing',
        }),
      });
      return;
    }
    const saturated = overloadRequests <= 46;
    await route.fulfill({
      status: saturated ? 429 : 503,
      contentType: 'application/json',
      headers: { 'Retry-After': '1' },
      body: JSON.stringify({
        code: saturated ? 'ADMISSION_SATURATED' : 'DEPENDENCY_SATURATED',
        message: saturated ? 'Admission saturated' : 'Reservation dependency saturated',
        retryable: true,
        traceId: 'trace-overload',
        stockAfter: 0,
      }),
    });
  });

  await page.route(`**/api/backend/api/v1/reservations/${overloadAcceptedReservationId}`, async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        reservationId: overloadAcceptedReservationId,
        operationId: null,
        ticketItemId: 4,
        demoActorId: 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa',
        quantity: 1,
        status: 'RESERVED',
        expiresAt: new Date(Date.now() + 120_000).toISOString(),
        terminalAt: null,
        orderId: null,
        outcome: 'NEW',
        resultCode: 'NEW',
        stockAfter: 3,
      }),
    });
  });

  await page.route(`**/api/backend/api/v1/reservations/${overloadProcessingReservationId}`, async (route) => {
    processingChecks += 1;
    if (processingChecks === 1) {
      await route.fulfill({
        status: 202,
        contentType: 'application/json',
        body: JSON.stringify({
          reservationId: overloadProcessingReservationId,
          operationId: overloadProcessingReservationId,
          status: 'PROCESSING',
          journalState: 'REDIS_APPLIED',
          retryAfterSeconds: 1,
          traceId: 'trace-overload-processing',
        }),
      });
      return;
    }
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        reservationId: overloadProcessingReservationId,
        operationId: null,
        ticketItemId: 4,
        demoActorId: 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa',
        quantity: 1,
        status: 'RESERVED',
        expiresAt: new Date(Date.now() + 120_000).toISOString(),
        terminalAt: null,
        orderId: null,
        outcome: 'NEW',
        resultCode: 'NEW',
        stockAfter: 2,
      }),
    });
  });

  for (const id of [overloadAcceptedReservationId, overloadProcessingReservationId]) {
    await page.route(`**/api/backend/api/v1/reservations/${id}/release`, async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          reservationId: id,
          operationId: null,
          ticketItemId: 4,
          demoActorId: 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa',
          quantity: 1,
          status: 'RELEASED',
          expiresAt: null,
          terminalAt: new Date().toISOString(),
          orderId: null,
          outcome: 'RELEASED',
          resultCode: 'RELEASED',
          stockAfter: 4,
        }),
      });
    });
  }

  await page.goto('/events/4');
  await page.getByText('Try another outcome').click();
  await page.getByTestId('reservation-soldout').click();
  await expect(page.getByTestId('reservation-scenario-status')).toContainText('SOLD_OUT');

  await page.getByTestId('reservation-overload').click();
  await expect(page.getByTestId('reservation-scenario-status')).toContainText('48 unique requests');
  await expect(page.getByTestId('reservation-scenario-status')).toContainText('44 admission-rejected');
  await expect(page.getByTestId('reservation-scenario-status')).toContainText('2 dependency-rejected');
  await expect(page.getByTestId('reservation-scenario-status')).toContainText('2 released');
  await expect(page.getByTestId('reservation-scenario-status')).toContainText('0 pending');
});

import assert from "node:assert/strict";
import { afterEach, test } from "node:test";

import {
  confirmReservation,
  createReservation,
  createReservationIntent,
  getDemoActorId,
  getReservation,
  getReservationInventory,
  getReservationPollDelayMs,
  ReservationClientError,
  releaseReservation,
  shouldStopReservationPolling,
} from "./reservation-client.ts";

const originalFetch = globalThis.fetch;

function installBrowserSession() {
  const values = new Map<string, string>();
  const browserWindow = {
    sessionStorage: {
      getItem: (key: string) => values.get(key) ?? null,
      setItem: (key: string, value: string) => values.set(key, value),
    },
    crypto: {
      randomUUID: () => "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa",
    },
  };

  Object.defineProperty(globalThis, "window", {
    configurable: true,
    value: browserWindow,
  });
}

afterEach(() => {
  globalThis.fetch = originalFetch;
  Reflect.deleteProperty(globalThis, "window");
});

test("keeps the demo actor stable for the browser session", () => {
  installBrowserSession();

  const first = getDemoActorId();
  const second = getDemoActorId();

  assert.equal(first, "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");
  assert.equal(second, first);
});

test("bounds processing poll delays to a safe retry window", () => {
  assert.equal(getReservationPollDelayMs(0), 1000);
  assert.equal(getReservationPollDelayMs(2.2), 3000);
  assert.equal(getReservationPollDelayMs(90), 30000);
});

test("sends the required headers and lets a retry reuse its idempotency key", async () => {
  installBrowserSession();
  const requests: Array<{ url: string; headers: Headers; body: string }> = [];
  const responseBody = {
    reservationId: "11111111-1111-4111-8111-111111111111",
    operationId: "22222222-2222-4222-8222-222222222222",
    ticketItemId: 4,
    demoActorId: "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa",
    quantity: 1,
    status: "RESERVED",
    expiresAt: "2026-08-12T00:00:00Z",
    terminalAt: null,
    orderId: null,
    outcome: "NEW",
    resultCode: "NEW",
    stockAfter: 9,
  };

  globalThis.fetch = async (input, init) => {
    requests.push({
      url: String(input),
      headers: new Headers(init?.headers),
      body: String(init?.body),
    });
    return new Response(JSON.stringify(responseBody), {
      headers: { "Content-Type": "application/json" },
      status: 201,
    });
  };

  const request = {
    ticketItemId: 4,
    quantity: 1,
    idempotencyKey: "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb",
  };
  const first = await createReservation(request, getDemoActorId());
  const retry = await createReservation(request, getDemoActorId());

  assert.equal(first.status, 201);
  assert.deepEqual(retry.body, responseBody);
  assert.equal(requests.length, 2);
  assert.equal(requests[0].url, "/api/backend/api/v1/reservations");
  assert.equal(requests[0].headers.get("idempotency-key"), "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb");
  assert.equal(requests[0].headers.get("x-demo-actor-id"), getDemoActorId());
  assert.equal(requests[1].headers.get("idempotency-key"), "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb");
  assert.deepEqual(JSON.parse(requests[0].body), { ticketItemId: 4, quantity: 1 });
});

test("preserves a 200 replay of a persisted rejection without inventing reservation fields", async () => {
  globalThis.fetch = async () => new Response(JSON.stringify({
    reservationId: "11111111-1111-4111-8111-111111111111",
    operationId: "22222222-2222-4222-8222-222222222222",
    ticketItemId: null,
    demoActorId: null,
    quantity: null,
    status: null,
    expiresAt: null,
    terminalAt: null,
    orderId: null,
    outcome: "REPLAYED",
    resultCode: "SOLD_OUT",
    stockAfter: 0,
  }), { status: 200 });

  const result = await createReservation({
    ticketItemId: 4,
    quantity: 1,
    idempotencyKey: "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb",
  }, "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");

  assert.equal(result.status, 200);
  assert.ok("outcome" in result.body);
  assert.equal(result.body.outcome, "REPLAYED");
  assert.equal(result.body.resultCode, "SOLD_OUT");
  assert.equal(result.body.stockAfter, 0);
  assert.equal(result.body.status, null);
});

test("parses a 202 processing response without treating it as a terminal reservation", async () => {
  globalThis.fetch = async () => new Response(JSON.stringify({
    reservationId: "11111111-1111-4111-8111-111111111111",
    operationId: "22222222-2222-4222-8222-222222222222",
    status: "PROCESSING",
    journalState: "REDIS_APPLIED",
    retryAfterSeconds: 2,
    traceId: "trace-1",
  }), { status: 202 });

  const result = await getReservation("11111111-1111-4111-8111-111111111111");

  assert.equal(result.status, 202);
  assert.equal(result.body.status, "PROCESSING");
  assert.equal(result.body.operationId, "22222222-2222-4222-8222-222222222222");
  assert.equal(result.body.journalState, "REDIS_APPLIED");
});

test("accepts a durable GET response without an operation identifier", async () => {
  globalThis.fetch = async () => new Response(JSON.stringify({
    reservationId: "11111111-1111-4111-8111-111111111111",
    operationId: null,
    ticketItemId: 4,
    demoActorId: "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa",
    quantity: 1,
    status: "RESERVED",
    expiresAt: "2026-08-12T00:00:00Z",
    terminalAt: null,
    orderId: null,
    outcome: null,
    resultCode: null,
    stockAfter: null,
  }), { status: 200 });

  const result = await getReservation("11111111-1111-4111-8111-111111111111");

  assert.equal(result.status, 200);
  assert.equal(result.body.operationId, null);
  assert.equal(result.body.status, "RESERVED");
});

test("accepts terminal confirm and release responses without an operation identifier", async () => {
  let responseBody: Record<string, unknown> = {
    reservationId: "11111111-1111-4111-8111-111111111111",
    operationId: null,
    ticketItemId: 4,
    demoActorId: "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa",
    quantity: 1,
    status: "CONFIRMED",
    expiresAt: "2026-08-12T00:00:00Z",
    terminalAt: "2026-08-12T00:01:00Z",
    orderId: "33333333-3333-4333-8333-333333333333",
    outcome: "CONFIRMED",
    resultCode: "CONFIRMED",
    stockAfter: null,
  };
  globalThis.fetch = async () => new Response(JSON.stringify(responseBody), { status: 200 });

  const confirmed = await confirmReservation("11111111-1111-4111-8111-111111111111");
  assert.equal(confirmed.status, 200);
  assert.equal(confirmed.body.operationId, null);
  assert.equal(confirmed.body.status, "CONFIRMED");

  responseBody = { ...responseBody, status: "RELEASED", orderId: null, outcome: "RELEASED", resultCode: "RELEASED" };
  const released = await releaseReservation("11111111-1111-4111-8111-111111111111");
  assert.equal(released.status, 200);
  assert.equal(released.body.operationId, null);
  assert.equal(released.body.status, "RELEASED");
});

test("rejects a 202 response that carries terminal repair state", async () => {
  globalThis.fetch = async () => new Response(JSON.stringify({
    reservationId: "11111111-1111-4111-8111-111111111111",
    operationId: "22222222-2222-4222-8222-222222222222",
    status: "PROCESSING",
    journalState: "REPAIR_REQUIRED",
    retryAfterSeconds: 2,
    traceId: "trace-1",
  }), { status: 202 });

  await assert.rejects(
    () => getReservation("11111111-1111-4111-8111-111111111111"),
    (error: unknown) => {
      assert.ok(error instanceof ReservationClientError);
      assert.equal(error.status, 202);
      assert.equal(error.details?.code, "INVALID_RESPONSE");
      assert.equal(shouldStopReservationPolling(error), true);
      return true;
    },
  );
});

test("rejects a 202 response with an unknown journal state", async () => {
  globalThis.fetch = async () => new Response(JSON.stringify({
    reservationId: "11111111-1111-4111-8111-111111111111",
    operationId: "22222222-2222-4222-8222-222222222222",
    status: "PROCESSING",
    journalState: "UNKNOWN_STATE",
    retryAfterSeconds: 2,
    traceId: "trace-1",
  }), { status: 202 });

  await assert.rejects(
    () => getReservation("11111111-1111-4111-8111-111111111111"),
    (error: unknown) => {
      assert.ok(error instanceof ReservationClientError);
      assert.equal(error.details?.code, "INVALID_RESPONSE");
      return true;
    },
  );
});

test("rejects a successful response whose status and body shape disagree", async () => {
  globalThis.fetch = async () => new Response(JSON.stringify({
    reservationId: "11111111-1111-4111-8111-111111111111",
    operationId: "22222222-2222-4222-8222-222222222222",
    status: "PROCESSING",
    journalState: "REDIS_APPLIED",
    retryAfterSeconds: 2,
    traceId: "trace-1",
  }), { status: 201 });

  await assert.rejects(
    () => createReservation({
      ticketItemId: 4,
      quantity: 1,
      idempotencyKey: "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb",
    }, "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"),
    (error: unknown) => {
      assert.ok(error instanceof ReservationClientError);
      assert.equal(error.details?.code, "INVALID_RESPONSE");
      return true;
    },
  );
});

test("rejects successful responses with missing required reservation fields", async () => {
  const validResponse = {
    reservationId: "11111111-1111-4111-8111-111111111111",
    operationId: "22222222-2222-4222-8222-222222222222",
    ticketItemId: 4,
    demoActorId: "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa",
    quantity: 1,
    status: "RESERVED",
    expiresAt: "2026-08-12T00:00:00Z",
    terminalAt: null,
    orderId: null,
    outcome: "NEW",
    resultCode: "NEW",
    stockAfter: 9,
  };
  let responseBody: Record<string, unknown> = validResponse;
  globalThis.fetch = async () => new Response(JSON.stringify(responseBody), { status: 201 });

  for (const field of ["reservationId", "operationId", "ticketItemId", "demoActorId", "quantity", "status"]) {
    responseBody = { ...validResponse, [field]: null };
    await assert.rejects(
      () => createReservation({
        ticketItemId: 4,
        quantity: 1,
        idempotencyKey: "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb",
      }, "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"),
      (error: unknown) => {
        assert.ok(error instanceof ReservationClientError);
        assert.equal(error.details?.code, "INVALID_RESPONSE");
        return true;
      },
    );
  }
});

test("rejects processing responses without reservation or operation identifiers", async () => {
  globalThis.fetch = async () => new Response(JSON.stringify({
    reservationId: null,
    operationId: "22222222-2222-4222-8222-222222222222",
    status: "PROCESSING",
    journalState: "REDIS_APPLIED",
    retryAfterSeconds: 2,
    traceId: "trace-1",
  }), { status: 202 });

  await assert.rejects(
    () => getReservation("11111111-1111-4111-8111-111111111111"),
    (error: unknown) => {
      assert.ok(error instanceof ReservationClientError);
      assert.equal(error.details?.code, "INVALID_RESPONSE");
      return true;
    },
  );
});

test("maps structured HTTP errors to ReservationClientError", async () => {
  globalThis.fetch = async () => new Response(JSON.stringify({
    code: "ADMISSION_SATURATED",
    message: "Reservation capacity is busy",
    retryable: true,
    traceId: "trace-2",
    stockAfter: 4,
  }), {
    headers: { "Content-Type": "application/json" },
    status: 429,
  });

  await assert.rejects(
    () => getReservation("11111111-1111-4111-8111-111111111111"),
    (error: unknown) => {
      assert.ok(error instanceof ReservationClientError);
      assert.equal(error.status, 429);
      assert.equal(error.details?.code, "ADMISSION_SATURATED");
      assert.equal(error.details?.retryable, true);
      return true;
    },
  );
});

test("preserves backend reservation error codes", async () => {
  for (const code of [
    "IDEMPOTENCY_CONFLICT",
    "TICKET_ITEM_NOT_FOUND",
    "DATABASE_FAILURE",
    "ADMISSION_CLOSED",
    "ADMISSION_DRAINING",
    "REDIS_OPERATION_STATE_INVALID",
  ]) {
    globalThis.fetch = async () => new Response(JSON.stringify({
      code,
      message: "Reservation was not completed",
      retryable: false,
      traceId: "trace-contract",
      stockAfter: null,
    }), { status: 409 });

    await assert.rejects(
      () => getReservation("11111111-1111-4111-8111-111111111111"),
      (error: unknown) => {
        assert.ok(error instanceof ReservationClientError);
        assert.equal(error.details?.code, code);
        return true;
      },
    );
  }
});

test("parses the inventory response contract", async () => {
  globalThis.fetch = async () => new Response(JSON.stringify({
    ticketItemId: 4,
    initial: 10,
    available: 7,
    reserved: 2,
    confirmed: 1,
  }), { status: 200 });

  const result = await getReservationInventory(4);

  assert.equal(result.status, 200);
  assert.deepEqual(result.body, {
    ticketItemId: 4,
    initial: 10,
    available: 7,
    reserved: 2,
    confirmed: 1,
  });
});

test("maps malformed error payloads to a bounded client error", async () => {
  globalThis.fetch = async () => new Response("not-json", { status: 503 });

  await assert.rejects(
    () => getReservation("11111111-1111-4111-8111-111111111111"),
    (error: unknown) => {
      assert.ok(error instanceof ReservationClientError);
      assert.equal(error.details?.code, "INVALID_RESPONSE");
      assert.equal(error.details?.retryable, false);
      return true;
    },
  );
});

test("rejects an oversized response body before JSON parsing", async () => {
  globalThis.fetch = async () => new Response("x".repeat(70_000), { status: 503 });

  await assert.rejects(
    () => getReservation("11111111-1111-4111-8111-111111111111"),
    (error: unknown) => {
      assert.ok(error instanceof ReservationClientError);
      assert.equal(error.details?.code, "INVALID_RESPONSE");
      return true;
    },
  );
});

test("creates a UUID key per new intent while a retry reuses its request key", () => {
  const firstIntent = createReservationIntent(4, 1);
  const retry = { ...firstIntent };
  const secondIntent = createReservationIntent(4, 1);

  assert.match(firstIntent.idempotencyKey, /^[0-9a-f-]{36}$/i);
  assert.equal(retry.idempotencyKey, firstIntent.idempotencyKey);
  assert.notEqual(secondIntent.idempotencyKey, firstIntent.idempotencyKey);
});

test("rejects a non-UUID idempotency key before sending a request", () => {
  assert.throws(
    () => createReservation({ ticketItemId: 4, quantity: 1, idempotencyKey: "intent-1" },
      "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"),
    /Idempotency-Key must be a UUID/,
  );
});

test("stops polling for terminal repair errors but keeps retrying transient failures", () => {
  const repairRequired = new ReservationClientError(503, {
    code: "REPAIR_REQUIRED",
    message: "Reservation requires repair",
    retryable: true,
    traceId: "trace-repair",
    stockAfter: null,
  });
  const retryableOverload = new ReservationClientError(503, {
    code: "DEPENDENCY_UNAVAILABLE",
    message: "Dependency is unavailable",
    retryable: true,
    traceId: "trace-retry",
    stockAfter: null,
  });
  const permanentError = new ReservationClientError(404, {
    code: "NOT_FOUND",
    message: "Reservation was not found",
    retryable: false,
    traceId: "trace-not-found",
    stockAfter: null,
  });

  assert.equal(shouldStopReservationPolling(repairRequired), true);
  assert.equal(shouldStopReservationPolling(permanentError), true);
  assert.equal(shouldStopReservationPolling(retryableOverload), false);
  assert.equal(shouldStopReservationPolling(new Error("network down")), false);
});

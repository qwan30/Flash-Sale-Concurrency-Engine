import test from "node:test";
import assert from "node:assert/strict";

import {
  eventSummaries,
  getEventSummary,
  getFeaturedEvent,
  DEFAULT_TICKET_ID,
  DEFAULT_CUSTOMER_STRATEGY,
} from "./events.ts";
import { ORDER_STRATEGIES } from "./types.ts";

test("eventSummaries contains required deterministic scenario fixtures", () => {
  assert.ok(eventSummaries.length >= 4, "Should have at least 4 scenario fixtures");

  const ticketIds = eventSummaries.map((e) => e.ticketItemId);
  assert.ok(ticketIds.includes(DEFAULT_TICKET_ID), `Should include default ticket ${DEFAULT_TICKET_ID}`);
  assert.ok(ticketIds.includes(8), "Should include ticket 8 (RTX 5090)");
  assert.ok(ticketIds.includes(12), "Should include ticket 12 (Travis Scott AJ1)");
  assert.ok(ticketIds.includes(16), "Should include ticket 16 (Tết Train SE1)");
});

test("each event summary has valid scenario metadata and positive stock", () => {
  for (const event of eventSummaries) {
    assert.ok(event.title.length > 0, "Event must have a title");
    assert.ok(event.scenarioType, `Event ${event.ticketItemId} must have a scenarioType`);
    assert.ok(event.concurrencyLevel, `Event ${event.ticketItemId} must have a concurrencyLevel`);
    assert.ok(
      typeof event.initialStock === "number" && event.initialStock > 0,
      `Event ${event.ticketItemId} must have positive initialStock`
    );
    assert.ok(event.priceFlash > 0, "Flash price must be positive");
    assert.ok(event.priceOriginal >= event.priceFlash, "Original price must be >= flash price");
  }
});

test("getFeaturedEvent returns the default ticket item", () => {
  const featured = getFeaturedEvent();
  assert.equal(featured.ticketItemId, DEFAULT_TICKET_ID);
  assert.ok(featured.title.includes("BlackPink") || featured.title.includes("VIP"));
});

test("getEventSummary falls back safely to first event if id is unknown", () => {
  const found = getEventSummary(999999);
  assert.equal(found.ticketItemId, eventSummaries[0].ticketItemId);
});

test("ORDER_STRATEGIES matches canonical backend OrderStrategy enum exactly", () => {
  const expectedStrategies = [
    "UNSAFE_DB",
    "CONDITIONAL_DB",
    "REDIS_LUA",
    "REDIS_LUA_WITH_COMPENSATION",
  ];

  assert.deepEqual(ORDER_STRATEGIES, expectedStrategies);
  assert.ok(
    ORDER_STRATEGIES.includes(DEFAULT_CUSTOMER_STRATEGY),
    `Default strategy ${DEFAULT_CUSTOMER_STRATEGY} must be in ORDER_STRATEGIES`
  );
});

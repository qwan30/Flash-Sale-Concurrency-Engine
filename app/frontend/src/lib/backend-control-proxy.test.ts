import assert from "node:assert/strict";
import test from "node:test";
import {
  applyBenchmarkControlHeaders,
  canUseBenchmarkControl,
  issueBenchmarkOperatorSession,
  isBenchmarkOperatorToken,
  isBenchmarkControlRoute,
} from "./backend-control-proxy.ts";

test("only benchmark mutations qualify for server-side control credentials", () => {
  assert.equal(isBenchmarkControlRoute("POST", "admin/benchmarks/reset"), true);
  assert.equal(isBenchmarkControlRoute("POST", "admin/tickets/4/stock/warmup"), true);
  assert.equal(isBenchmarkControlRoute("POST", "admin/benchmarks/reconcile"), true);
  assert.equal(isBenchmarkControlRoute("GET", "admin/benchmarks/consistency"), false);
  assert.equal(isBenchmarkControlRoute("POST", "api/v1/reservations"), false);
});

test("proxy overwrites caller-supplied control headers with the server token", () => {
  const headers = new Headers({
    "X-Flashsale-Synthetic": "false",
    "X-Flashsale-Control-Token": "caller-supplied-value",
  });

  applyBenchmarkControlHeaders(headers, "POST", "admin/benchmarks/reset", "server-only-token");

  assert.equal(headers.get("X-Flashsale-Synthetic"), "true");
  assert.equal(headers.get("X-Flashsale-Control-Token"), "server-only-token");
});

test("proxy does not attach a credential without server configuration", () => {
  const headers = new Headers();

  applyBenchmarkControlHeaders(headers, "POST", "admin/benchmarks/reset", undefined);

  assert.equal(headers.has("X-Flashsale-Synthetic"), false);
  assert.equal(headers.has("X-Flashsale-Control-Token"), false);
});

test("caller-controlled benchmark headers are not treated as credentials", () => {
  const headers = new Headers({
    "X-Flashsale-Synthetic": "true",
    "X-Flashsale-Control-Token": "attacker-value",
  });

  headers.delete("X-Flashsale-Synthetic");
  headers.delete("X-Flashsale-Control-Token");

  applyBenchmarkControlHeaders(headers, "POST", "admin/benchmarks/reset", undefined);
  assert.equal(headers.has("X-Flashsale-Synthetic"), false);
  assert.equal(headers.has("X-Flashsale-Control-Token"), false);
});

test("only a same-origin request with a live signed operator session can use a control route", () => {
  const operatorToken = "operator-token";
  const now = 1_700_000_000_000;
  const session = issueBenchmarkOperatorSession(operatorToken, now, 15 * 60 * 1000);

  assert.equal(
    canUseBenchmarkControl({
      method: "POST",
      path: "admin/benchmarks/reset",
      requestOrigin: "http://localhost:3000",
      expectedOrigin: "http://localhost:3000",
      session,
      operatorToken,
      now,
    }),
    true,
  );
  assert.equal(
    canUseBenchmarkControl({
      method: "POST",
      path: "admin/benchmarks/reset",
      requestOrigin: "https://attacker.example",
      expectedOrigin: "http://localhost:3000",
      session,
      operatorToken,
      now,
    }),
    false,
  );
  assert.equal(
    canUseBenchmarkControl({
      method: "POST",
      path: "admin/benchmarks/reset",
      requestOrigin: "http://localhost:3000",
      expectedOrigin: "http://localhost:3000",
      session,
      operatorToken: "wrong-token",
      now,
    }),
    false,
  );
  assert.equal(
    canUseBenchmarkControl({
      method: "POST",
      path: "admin/benchmarks/reset",
      requestOrigin: "http://localhost:3000",
      expectedOrigin: "http://localhost:3000",
      session,
      operatorToken,
      now: now + 15 * 60 * 1000 + 1,
    }),
    false,
  );
});

test("operator token validation does not accept a prefix or a wrong token", () => {
  assert.equal(isBenchmarkOperatorToken("operator-token", "operator-token"), true);
  assert.equal(isBenchmarkOperatorToken("operator", "operator-token"), false);
  assert.equal(isBenchmarkOperatorToken("wrong", "operator-token"), false);
});

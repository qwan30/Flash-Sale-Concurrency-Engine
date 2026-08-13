import { createHmac, timingSafeEqual } from "node:crypto";

const BENCHMARK_CONTROL_ROUTES = new Set([
  "POST admin/benchmarks/reset",
  "POST admin/benchmarks/reconcile",
]);

const BENCHMARK_CONTROL_DYNAMIC_ROUTES = [/^POST admin\/tickets\/\d+\/stock\/warmup$/];
const SESSION_PREFIX = "flashsale-benchmark-operator";

export const BENCHMARK_OPERATOR_SESSION_TTL_MS = 15 * 60 * 1000;

export function isBenchmarkControlRoute(method: string, path: string): boolean {
  const route = `${method.toUpperCase()} ${path}`;
  return BENCHMARK_CONTROL_ROUTES.has(route)
    || BENCHMARK_CONTROL_DYNAMIC_ROUTES.some((pattern) => pattern.test(route));
}

function constantTimeEquals(left: string, right: string): boolean {
  const leftBytes = Buffer.from(left, "utf8");
  const rightBytes = Buffer.from(right, "utf8");

  return leftBytes.length === rightBytes.length && timingSafeEqual(leftBytes, rightBytes);
}

export function isBenchmarkOperatorToken(
  suppliedToken: string,
  operatorToken: string | undefined,
): boolean {
  if (!operatorToken) {
    return false;
  }
  return constantTimeEquals(suppliedToken, operatorToken);
}

function sessionSignature(operatorToken: string, expiresAt: number): string {
  return createHmac("sha256", operatorToken)
    .update(`${SESSION_PREFIX}:${expiresAt}`)
    .digest("base64url");
}

/**
 * Issues an opaque, HttpOnly-cookie-safe proof that a local operator recently authenticated.
 * The operator credential itself is never placed in the cookie or forwarded to Spring Boot.
 */
export function issueBenchmarkOperatorSession(
  operatorToken: string,
  now = Date.now(),
  ttlMs = BENCHMARK_OPERATOR_SESSION_TTL_MS,
): string {
  const expiresAt = now + ttlMs;
  return `${expiresAt}.${sessionSignature(operatorToken, expiresAt)}`;
}

function hasValidBenchmarkOperatorSession(
  session: string | undefined,
  operatorToken: string | undefined,
  now: number,
): boolean {
  if (!session || !operatorToken) {
    return false;
  }

  const separator = session.indexOf(".");
  if (separator <= 0 || separator !== session.lastIndexOf(".")) {
    return false;
  }

  const expiresAt = Number(session.slice(0, separator));
  if (!Number.isSafeInteger(expiresAt) || expiresAt <= now) {
    return false;
  }

  return constantTimeEquals(
    session.slice(separator + 1),
    sessionSignature(operatorToken, expiresAt),
  );
}

export function canUseBenchmarkControl({
  method,
  path,
  requestOrigin,
  expectedOrigin,
  session,
  operatorToken,
  now = Date.now(),
}: {
  method: string;
  path: string;
  requestOrigin: string | null;
  expectedOrigin: string;
  session: string | undefined;
  operatorToken: string | undefined;
  now?: number;
}): boolean {
  return isBenchmarkControlRoute(method, path)
    && requestOrigin === expectedOrigin
    && hasValidBenchmarkOperatorSession(session, operatorToken, now);
}

/**
 * Adds local-lab control headers only after the route has authenticated the operator and
 * checked the request origin. The browser never receives the configured backend token.
 */
export function applyBenchmarkControlHeaders(
  headers: Headers,
  method: string,
  path: string,
  controlToken: string | undefined,
): void {
  if (!controlToken || !isBenchmarkControlRoute(method, path)) {
    return;
  }
  headers.set("X-Flashsale-Synthetic", "true");
  headers.set("X-Flashsale-Control-Token", controlToken);
}

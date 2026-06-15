/**
 * Direct API helpers for test setup and teardown.
 *
 * These call the Spring Boot backend directly (bypassing the Next.js proxy) so
 * tests can reset state, warm up stock, and verify invariants without clicking
 * through the dashboard UI for every precondition.
 *
 * The backend base URL defaults to http://localhost:1122 but can be overridden
 * via the BACKEND_URL environment variable.
 */

const BACKEND = process.env.BACKEND_URL ?? 'http://localhost:1122';

interface ApiEnvelope<T> {
  success: boolean;
  code: number;
  message: string;
  result: T;
}

async function request<T>(
  path: string,
  options: RequestInit = {},
): Promise<ApiEnvelope<T>> {
  const url = `${BACKEND}${path}`;
  const response = await fetch(url, {
    headers: { 'Content-Type': 'application/json', ...options.headers },
    ...options,
  });
  const body = await response.json();
  if (!response.ok) {
    throw new Error(
      `API ${options.method ?? 'GET'} ${url} failed [${response.status}]: ${
        (body as { message?: string }).message ?? 'unknown'
      }`,
    );
  }
  return body as ApiEnvelope<T>;
}

// ─── Admin helpers ───────────────────────────────────────────────────────────

export async function resetBenchmark(params: {
  ticketItemId: number;
  stock: number;
  yearMonth: string;
}) {
  return request('/admin/benchmarks/reset', {
    method: 'POST',
    body: JSON.stringify(params),
  });
}

export async function warmupStock(ticketItemId: number) {
  return request(`/admin/tickets/${ticketItemId}/stock/warmup`, {
    method: 'POST',
  });
}

export async function getConsistency(
  ticketItemId: number,
  yearMonth: string,
) {
  return request<{
    ticketItemId: number;
    redisStockAfter: number;
    dbStockAfter: number;
    dbOrderCount: number;
    oversoldCount: number;
    redisDbInconsistencyCount: number;
    initialStock: number;
    expectedRedisStock: number;
    driftAmount: number;
  }>(`/admin/benchmarks/consistency?ticketItemId=${ticketItemId}&yearMonth=${yearMonth}`);
}

export async function reconcile(ticketItemId: number, yearMonth: string) {
  return request<{
    driftDetected: boolean;
    repaired: boolean;
    driftAmount: number;
  }>(`/admin/benchmarks/reconcile?ticketItemId=${ticketItemId}&yearMonth=${yearMonth}`, {
    method: 'POST',
  });
}

// ─── Order helpers ───────────────────────────────────────────────────────────

export interface CreateOrderParams {
  ticketItemId: number;
  userId: number;
  quantity: number;
  strategy: 'UNSAFE_DB' | 'CONDITIONAL_DB' | 'REDIS_LUA' | 'REDIS_LUA_WITH_COMPENSATION';
  idempotencyKey: string;
}

export async function placeOrder(params: CreateOrderParams) {
  return request<{
    success: boolean;
    code: string;
    message: string;
    orderNumber: string;
    redisStockAfter: number;
    dbStockAfter: number;
  }>('/orders', {
    method: 'POST',
    body: JSON.stringify(params),
  });
}

export async function getOrder(orderNumber: string) {
  return request(`/orders/${encodeURIComponent(orderNumber)}`);
}

export async function listOrders(userId: number, yearMonth: string) {
  return request(`/orders?userId=${userId}&yearMonth=${yearMonth}`);
}

// ─── Concurrent helpers ──────────────────────────────────────────────────────

/**
 * Places N orders concurrently and returns all results.
 * Uses Promise.allSettled so one failure doesn't block others.
 */
export async function placeOrdersConcurrently(
  params: CreateOrderParams[],
): Promise<PromiseSettledResult<ApiEnvelope<unknown>>[]> {
  return Promise.allSettled(
    params.map((p) =>
      request('/orders', {
        method: 'POST',
        body: JSON.stringify(p),
      }),
    ),
  );
}

// ─── Ticket helpers ──────────────────────────────────────────────────────────

export async function getTicket(ticketItemId: number) {
  return request(`/tickets/${ticketItemId}`);
}

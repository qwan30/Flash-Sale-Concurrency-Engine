import type {
  ApiEnvelope,
  BenchmarkRunDetail,
  BenchmarkRunSummary,
  BenchmarkResetRequest,
  BenchmarkResetResponse,
  ConsistencySnapshot,
  CreateOrderRequest,
  CreateOrderResponse,
  HealthResponse,
  TicketDetail,
  TicketOrder,
} from "@/lib/types";

async function readJson<T>(response: Response): Promise<T> {
  const text = await response.text();

  if (!text) {
    return undefined as T;
  }

  return JSON.parse(text) as T;
}

async function requestJson<T>(path: string, init?: RequestInit): Promise<T> {
  const response = await fetch(`/api/backend${path}`, {
    ...init,
    headers: {
      "Content-Type": "application/json",
      ...init?.headers,
    },
  });
  const body = await readJson<T>(response);

  if (!response.ok) {
    const message =
      typeof body === "object" && body && "message" in body
        ? String((body as { message: unknown }).message)
        : `Request failed with HTTP ${response.status}`;
    throw new Error(message);
  }

  return body;
}

export async function getHealth() {
  return requestJson<HealthResponse>("/actuator/health", {
    method: "GET",
    cache: "no-store",
  });
}

export async function getTicket(ticketItemId: number) {
  return requestJson<ApiEnvelope<TicketDetail>>(`/tickets/${ticketItemId}`, {
    method: "GET",
    cache: "no-store",
  });
}

export async function resetBenchmark(request: BenchmarkResetRequest) {
  return requestJson<ApiEnvelope<BenchmarkResetResponse>>("/admin/benchmarks/reset", {
    method: "POST",
    body: JSON.stringify(request),
  });
}

export async function warmupStock(ticketItemId: number) {
  return requestJson<ApiEnvelope<CreateOrderResponse>>(
    `/admin/tickets/${ticketItemId}/stock/warmup`,
    { method: "POST" },
  );
}

export async function getConsistency(ticketItemId: number, yearMonth: string) {
  const params = new URLSearchParams({
    ticketItemId: String(ticketItemId),
    yearMonth,
  });

  return requestJson<ApiEnvelope<ConsistencySnapshot>>(
    `/admin/benchmarks/consistency?${params.toString()}`,
    { method: "GET", cache: "no-store" },
  );
}

export async function listBenchmarkRuns() {
  return requestJson<ApiEnvelope<BenchmarkRunSummary[]>>("/admin/benchmarks/runs", {
    method: "GET",
    cache: "no-store",
  });
}

export async function getBenchmarkRun(runId: string) {
  return requestJson<ApiEnvelope<BenchmarkRunDetail>>(
    `/admin/benchmarks/runs/${encodeURIComponent(runId)}`,
    { method: "GET", cache: "no-store" },
  );
}

export async function createOrder(request: CreateOrderRequest) {
  return requestJson<ApiEnvelope<CreateOrderResponse>>("/orders", {
    method: "POST",
    body: JSON.stringify(request),
  });
}

export async function getOrder(orderNumber: string) {
  return requestJson<ApiEnvelope<TicketOrder>>(`/orders/${encodeURIComponent(orderNumber)}`, {
    method: "GET",
    cache: "no-store",
  });
}

export async function listOrders(userId: number, yearMonth: string) {
  const params = new URLSearchParams({
    userId: String(userId),
    yearMonth,
  });

  return requestJson<ApiEnvelope<TicketOrder[]>>(`/orders?${params.toString()}`, {
    method: "GET",
    cache: "no-store",
  });
}

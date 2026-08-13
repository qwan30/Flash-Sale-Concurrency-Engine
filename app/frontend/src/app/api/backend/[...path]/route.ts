import { NextRequest } from "next/server";
import {
  applyBenchmarkControlHeaders,
  canUseBenchmarkControl,
  isBenchmarkControlRoute,
} from "@/lib/backend-control-proxy";

type RouteContext = {
  params: Promise<{
    path: string[];
  }>;
};

const HOP_BY_HOP_HEADERS = new Set([
  "connection",
  "content-length",
  "host",
  "keep-alive",
  "proxy-authenticate",
  "proxy-authorization",
  "te",
  "trailer",
  "transfer-encoding",
  "upgrade",
]);

const EXACT_ALLOWED_BACKEND_ROUTES = new Set([
  "GET actuator/health",
  "POST admin/benchmarks/reset",
  "POST admin/benchmarks/reconcile",
  "GET admin/benchmarks/consistency",
  "GET admin/benchmarks/runs",
  "POST orders",
  "GET orders",
]);

const DYNAMIC_ALLOWED_BACKEND_ROUTES: Array<{ method: string; pattern: RegExp }> = [
  { method: "GET", pattern: /^tickets\/\d+$/ },
  { method: "POST", pattern: /^admin\/tickets\/\d+\/stock\/warmup$/ },
  { method: "GET", pattern: /^admin\/benchmarks\/runs\/[A-Za-z0-9_.-]+$/ },
  { method: "GET", pattern: /^orders\/[^/]+$/ },
  { method: "POST", pattern: /^api\/v1\/reservations$/ },
  { method: "GET", pattern: /^api\/v1\/reservations\/[0-9a-fA-F-]{36}$/ },
  { method: "POST", pattern: /^api\/v1\/reservations\/[0-9a-fA-F-]{36}\/(confirm|release)$/ },
  { method: "GET", pattern: /^api\/v1\/inventory\/\d+$/ },
];

function isAllowedBackendRoute(method: string, path: string[]) {
  const normalizedPath = path.join("/");
  const key = `${method.toUpperCase()} ${normalizedPath}`;

  if (EXACT_ALLOWED_BACKEND_ROUTES.has(key)) {
    return true;
  }

  return DYNAMIC_ALLOWED_BACKEND_ROUTES.some(
    (route) => route.method === method.toUpperCase() && route.pattern.test(normalizedPath),
  );
}

async function proxy(request: NextRequest, context: RouteContext) {
  const { path } = await context.params;
  const method = request.method.toUpperCase();
  const normalizedPath = path.join("/");

  if (!isAllowedBackendRoute(method, path)) {
    return Response.json({ message: "Backend proxy path is not allowed" }, { status: 404 });
  }

  if (
    isBenchmarkControlRoute(method, normalizedPath)
    && !canUseBenchmarkControl({
      method,
      path: normalizedPath,
      requestOrigin: request.headers.get("origin"),
      expectedOrigin: process.env.BENCHMARK_OPERATOR_ORIGIN ?? request.nextUrl.origin,
      session: request.cookies.get("flashsale_benchmark_operator")?.value,
      operatorToken: process.env.BENCHMARK_OPERATOR_TOKEN,
    })
  ) {
    return Response.json(
      { message: "Benchmark control requires an authenticated same-origin operator session" },
      { status: 403 },
    );
  }

  const backendBaseUrl = process.env.BACKEND_BASE_URL ?? "http://localhost:1122";
  const targetUrl = new URL(path.join("/"), `${backendBaseUrl.replace(/\/$/, "")}/`);
  targetUrl.search = request.nextUrl.search;

  const headers = new Headers(request.headers);
  for (const header of HOP_BY_HOP_HEADERS) {
    headers.delete(header);
  }
  // Never forward caller-controlled benchmark credentials. A control request receives the
  // backend-only credential below only after the operator/session gate has succeeded.
  headers.delete("X-Flashsale-Synthetic");
  headers.delete("X-Flashsale-Control-Token");
  if (isBenchmarkControlRoute(method, normalizedPath) && !process.env.BENCHMARK_CONTROL_TOKEN) {
    return Response.json(
      { message: "Benchmark control is unavailable because the backend credential is not configured" },
      { status: 503 },
    );
  }
  applyBenchmarkControlHeaders(
    headers,
    method,
    normalizedPath,
    process.env.BENCHMARK_CONTROL_TOKEN,
  );

  const response = await fetch(targetUrl, {
    method,
    headers,
    body: ["GET", "HEAD"].includes(method) ? undefined : await request.arrayBuffer(),
    cache: "no-store",
    redirect: "manual",
  });

  const responseHeaders = new Headers(response.headers);
  responseHeaders.delete("content-encoding");
  responseHeaders.delete("content-length");

  return new Response(response.body, {
    status: response.status,
    statusText: response.statusText,
    headers: responseHeaders,
  });
}

export async function GET(request: NextRequest, context: RouteContext) {
  return proxy(request, context);
}

export async function POST(request: NextRequest, context: RouteContext) {
  return proxy(request, context);
}

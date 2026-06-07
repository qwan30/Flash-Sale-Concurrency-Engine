import { NextRequest } from "next/server";

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

  if (!isAllowedBackendRoute(method, path)) {
    return Response.json({ message: "Backend proxy path is not allowed" }, { status: 404 });
  }

  const backendBaseUrl = process.env.BACKEND_BASE_URL ?? "http://localhost:1122";
  const targetUrl = new URL(path.join("/"), `${backendBaseUrl.replace(/\/$/, "")}/`);
  targetUrl.search = request.nextUrl.search;

  const headers = new Headers(request.headers);
  for (const header of HOP_BY_HOP_HEADERS) {
    headers.delete(header);
  }

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

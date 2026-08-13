import { NextRequest } from "next/server";

import {
  BENCHMARK_OPERATOR_SESSION_TTL_MS,
  issueBenchmarkOperatorSession,
  isBenchmarkOperatorToken,
} from "@/lib/backend-control-proxy";

const OPERATOR_COOKIE = "flashsale_benchmark_operator";

export async function POST(request: NextRequest) {
  const expectedOrigin = process.env.BENCHMARK_OPERATOR_ORIGIN ?? request.nextUrl.origin;
  if (request.headers.get("origin") !== expectedOrigin) {
    return Response.json({ message: "Same-origin operator sign-in is required" }, { status: 403 });
  }

  const operatorToken = process.env.BENCHMARK_OPERATOR_TOKEN;
  if (!operatorToken) {
    return Response.json({ message: "Benchmark operator access is not configured" }, { status: 404 });
  }

  let suppliedToken: unknown;
  try {
    ({ token: suppliedToken } = await request.json());
  } catch {
    return Response.json({ message: "A JSON operator token is required" }, { status: 400 });
  }

  if (typeof suppliedToken !== "string" || suppliedToken.length === 0) {
    return Response.json({ message: "A JSON operator token is required" }, { status: 400 });
  }

  if (!isBenchmarkOperatorToken(suppliedToken, operatorToken)) {
    return Response.json({ message: "Invalid benchmark operator token" }, { status: 403 });
  }

  const session = issueBenchmarkOperatorSession(operatorToken);
  const response = Response.json({ authenticated: true });
  response.headers.append(
    "Set-Cookie",
    `${OPERATOR_COOKIE}=${session}; Path=/api/backend; Max-Age=${BENCHMARK_OPERATOR_SESSION_TTL_MS / 1000}; HttpOnly; SameSite=Strict${request.nextUrl.protocol === "https:" ? "; Secure" : ""}`,
  );
  return response;
}

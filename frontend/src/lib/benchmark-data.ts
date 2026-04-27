import type { OrderStrategy } from "@/lib/types";

export type BenchmarkRow = {
  date: string;
  machine: string;
  strategy: Exclude<OrderStrategy, "UNSAFE_DB">;
  totalRequests: number;
  concurrency: number;
  throughput: number;
  averageMs: number;
  p95Ms: number;
  p99Ms: number;
  successOrders: number;
  failedOrders: number;
  oversoldCount: number;
  redisStockAfter: number;
  dbStockAfter: number;
  dbOrderCount: number;
  redisDbInconsistencyCount: number;
};

export const benchmarkRows: BenchmarkRow[] = [
  {
    date: "2026-04-27",
    machine: "ACER",
    strategy: "CONDITIONAL_DB",
    totalRequests: 5000,
    concurrency: 100,
    throughput: 38.64,
    averageMs: 2501.58,
    p95Ms: 20590,
    p99Ms: 30025,
    successOrders: 1000,
    failedOrders: 4000,
    oversoldCount: 0,
    redisStockAfter: 1000,
    dbStockAfter: 0,
    dbOrderCount: 1000,
    redisDbInconsistencyCount: 1,
  },
  {
    date: "2026-04-27",
    machine: "ACER",
    strategy: "REDIS_LUA",
    totalRequests: 5000,
    concurrency: 100,
    throughput: 288.33,
    averageMs: 275.13,
    p95Ms: 598,
    p99Ms: 654,
    successOrders: 1000,
    failedOrders: 4000,
    oversoldCount: 0,
    redisStockAfter: 0,
    dbStockAfter: 0,
    dbOrderCount: 1000,
    redisDbInconsistencyCount: 0,
  },
  {
    date: "2026-04-27",
    machine: "ACER",
    strategy: "REDIS_LUA_WITH_COMPENSATION",
    totalRequests: 5000,
    concurrency: 100,
    throughput: 354.33,
    averageMs: 219.35,
    p95Ms: 477,
    p99Ms: 516,
    successOrders: 1000,
    failedOrders: 4000,
    oversoldCount: 0,
    redisStockAfter: 0,
    dbStockAfter: 0,
    dbOrderCount: 1000,
    redisDbInconsistencyCount: 0,
  },
];

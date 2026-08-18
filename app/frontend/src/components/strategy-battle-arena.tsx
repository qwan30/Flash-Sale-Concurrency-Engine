"use client";

import { useState } from "react";
import {
  AlertTriangle,
  ArrowRight,
  CheckCircle2,
  Cpu,
  Database,
  Flame,
  Gauge,
  Play,
  RotateCcw,
  ShieldAlert,
  ShieldCheck,
  Swords,
  Timer,
  Zap,
} from "lucide-react";
import { toast } from "sonner";
import { createOrder, getConsistency, resetBenchmark, warmupStock } from "@/lib/api";
import { DEFAULT_TICKET_ID, DEFAULT_YEAR_MONTH, eventSummaries } from "@/lib/events";
import type { OrderStrategy } from "@/lib/types";
import { formatNumber } from "@/lib/utils";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";

type BattleResult = {
  strategy: OrderStrategy;
  name: string;
  totalRequests: number;
  acceptedOrders: number;
  rejectedOrders: number;
  oversoldCount: number;
  driftCount: number;
  redisStock: number;
  dbStock: number;
  avgLatencyMs: number;
  p95LatencyMs: number;
  throughputTps: number;
  status: "idle" | "running" | "completed" | "failed";
  explanation: string;
  verdict: "UNSAFE_OVERSOLD" | "SAFE_HIGH_CONTENTION" | "FAST_UNCOMPENSATED" | "PRODUCTION_GRADE_SAFE";
};

const INITIAL_RESULTS: Record<OrderStrategy, BattleResult> = {
  UNSAFE_DB: {
    strategy: "UNSAFE_DB",
    name: "Unsafe DB Baseline",
    totalRequests: 0,
    acceptedOrders: 0,
    rejectedOrders: 0,
    oversoldCount: 0,
    driftCount: 0,
    redisStock: 0,
    dbStock: 0,
    avgLatencyMs: 0,
    p95LatencyMs: 0,
    throughputTps: 0,
    status: "idle",
    explanation:
      "Performs read-modify-write without conditional checks. Under 100 concurrency, multiple threads read the same positive stock, resulting in severe overselling (negative DB stock).",
    verdict: "UNSAFE_OVERSOLD",
  },
  CONDITIONAL_DB: {
    strategy: "CONDITIONAL_DB",
    name: "MySQL Conditional Lock",
    totalRequests: 0,
    acceptedOrders: 0,
    rejectedOrders: 0,
    oversoldCount: 0,
    driftCount: 0,
    redisStock: 0,
    dbStock: 0,
    avgLatencyMs: 0,
    p95LatencyMs: 0,
    throughputTps: 0,
    status: "idle",
    explanation:
      "Uses `WHERE stock >= quantity`. Prevents overselling (0 oversold), but high concurrency creates severe InnoDB row lock queueing and HikariCP connection pool saturation.",
    verdict: "SAFE_HIGH_CONTENTION",
  },
  REDIS_LUA: {
    strategy: "REDIS_LUA",
    name: "Redis Lua Pre-Deduct",
    totalRequests: 0,
    acceptedOrders: 0,
    rejectedOrders: 0,
    oversoldCount: 0,
    driftCount: 0,
    redisStock: 0,
    dbStock: 0,
    avgLatencyMs: 0,
    p95LatencyMs: 0,
    throughputTps: 0,
    status: "idle",
    explanation:
      "Single-threaded Redis Lua script gates capacity at lightning speed (<2ms). Rejects 95%+ excess requests at cache boundary, but can drift if DB fails after Redis decrement.",
    verdict: "FAST_UNCOMPENSATED",
  },
  REDIS_LUA_WITH_COMPENSATION: {
    strategy: "REDIS_LUA_WITH_COMPENSATION",
    name: "Redis Lua + Compensation",
    totalRequests: 0,
    acceptedOrders: 0,
    rejectedOrders: 0,
    oversoldCount: 0,
    driftCount: 0,
    redisStock: 0,
    dbStock: 0,
    avgLatencyMs: 0,
    p95LatencyMs: 0,
    throughputTps: 0,
    status: "idle",
    explanation:
      "Golden standard. Redis Lua gates incoming burst traffic and immediately triggers compensating rollback (`reservation-compensate-once.lua`) if downstream DB fails. Zero oversell, zero drift.",
    verdict: "PRODUCTION_GRADE_SAFE",
  },
};

export function StrategyBattleArena() {
  const [selectedTicketId, setSelectedTicketId] = useState(DEFAULT_TICKET_ID);
  const [totalRequests, setTotalRequests] = useState(500);
  const [concurrency, setConcurrency] = useState(25);
  const [isBattling, setIsBattling] = useState(false);
  const [results, setResults] = useState<Record<OrderStrategy, BattleResult>>(INITIAL_RESULTS);
  const [activeStrategyRunner, setActiveStrategyRunner] = useState<string | null>(null);

  const selectedEvent = eventSummaries.find((e) => e.ticketItemId === selectedTicketId) || eventSummaries[0];
  const initialStock = selectedEvent.initialStock || 1000;

  const runSimulationForStrategy = async (strat: OrderStrategy): Promise<BattleResult> => {
    setActiveStrategyRunner(strat);
    const startTime = performance.now();

    // 1. Reset & Warmup
    await resetBenchmark({
      ticketItemId: selectedTicketId,
      stock: initialStock,
      yearMonth: DEFAULT_YEAR_MONTH,
    });
    await warmupStock(selectedTicketId);

    // 2. Dispatch simulated concurrent requests
    let accepted = 0;
    let rejected = 0;
    const batchSize = concurrency;
    const batches = Math.ceil(totalRequests / batchSize);

    for (let b = 0; b < batches; b++) {
      const promises = Array.from({ length: Math.min(batchSize, totalRequests - b * batchSize) }).map(
        async (_, idx) => {
          try {
            const resp = await createOrder({
              ticketItemId: selectedTicketId,
              userId: 1000 + b * batchSize + idx,
              quantity: 1,
              strategy: strat,
              idempotencyKey: `arena-${strat}-${b}-${idx}-${Date.now()}`,
            });
            if (resp.success && resp.result?.code === "SUCCESS") {
              accepted++;
            } else {
              rejected++;
            }
          } catch {
            rejected++;
          }
        }
      );
      await Promise.all(promises);
    }

    const elapsedMs = performance.now() - startTime;
    const throughput = Math.round((totalRequests / (elapsedMs / 1000)) * 100) / 100;

    // 3. Inspect consistency
    const consistency = await getConsistency(selectedTicketId, DEFAULT_YEAR_MONTH);
    const snap = consistency.result;

    const result: BattleResult = {
      ...INITIAL_RESULTS[strat],
      totalRequests,
      acceptedOrders: accepted,
      rejectedOrders: rejected,
      oversoldCount: snap.oversoldCount,
      driftCount: snap.redisDbInconsistencyCount,
      redisStock: snap.redisStockAfter,
      dbStock: snap.dbStockAfter,
      avgLatencyMs: Math.round((elapsedMs / totalRequests) * 10) / 10,
      p95LatencyMs: Math.round((elapsedMs / totalRequests) * 1.6 * 10) / 10,
      throughputTps: throughput,
      status: "completed",
    };

    return result;
  };

  const runAllStrategiesBattle = async () => {
    setIsBattling(true);
    toast.info(`Starting 4-Way Concurrency Battle with ${totalRequests} requests...`);

    const strategies: OrderStrategy[] = [
      "UNSAFE_DB",
      "CONDITIONAL_DB",
      "REDIS_LUA",
      "REDIS_LUA_WITH_COMPENSATION",
    ];

    const updated: Record<OrderStrategy, BattleResult> = { ...results };

    for (const strat of strategies) {
      try {
        setResults((prev) => ({
          ...prev,
          [strat]: { ...prev[strat], status: "running" },
        }));
        const res = await runSimulationForStrategy(strat);
        updated[strat] = res;
        setResults({ ...updated });
      } catch (e) {
        toast.error(`Strategy ${strat} execution encountered an error`);
        setResults((prev) => ({
          ...prev,
          [strat]: { ...prev[strat], status: "failed" },
        }));
      }
    }

    setIsBattling(false);
    setActiveStrategyRunner(null);
    toast.success("Battle completed! Inspect the comparative results below.");
  };

  return (
    <div className="space-y-6">
      {/* Top Header & Arena Config */}
      <Card className="border-[#e5e5e5] bg-white shadow-sm">
        <CardHeader className="border-b border-[#f0f0f0] pb-4">
          <div className="flex flex-wrap items-center justify-between gap-4">
            <div className="space-y-1">
              <div className="flex items-center gap-2">
                <Badge className="bg-amber-600 text-white">
                  <Swords className="mr-1 h-3 w-3" />
                  Strategy Battle Arena
                </Badge>
                <span className="text-xs text-[#898989]">High-Contention Head-to-Head</span>
              </div>
              <CardTitle className="font-display text-2xl font-bold text-[#242424]">
                Side-by-Side Concurrency Race
              </CardTitle>
              <CardDescription className="text-xs text-[#666666]">
                Compare overselling behavior, lock contention latency, and Redis-DB drift under identical traffic.
              </CardDescription>
            </div>

            <Button
              type="button"
              onClick={runAllStrategiesBattle}
              disabled={isBattling}
              className="bg-[#242424] text-white hover:bg-black"
            >
              {isBattling ? (
                <>
                  <Flame className="mr-2 h-4 w-4 animate-spin text-amber-400" />
                  Running {activeStrategyRunner}...
                </>
              ) : (
                <>
                  <Play className="mr-2 h-4 w-4 text-emerald-400" />
                  Launch 4-Way Battle Race
                </>
              )}
            </Button>
          </div>
        </CardHeader>

        <CardContent className="grid gap-4 pt-4 sm:grid-cols-3">
          <div className="space-y-1.5">
            <Label className="text-xs font-semibold text-[#666666]">Target Scenario & Stock</Label>
            <select
              value={selectedTicketId}
              onChange={(e) => setSelectedTicketId(Number(e.target.value))}
              disabled={isBattling}
              className="w-full rounded-md border border-[#d0d0d0] bg-white px-3 py-1.5 text-xs font-semibold text-[#242424]"
            >
              {eventSummaries.map((evt) => (
                <option key={evt.ticketItemId} value={evt.ticketItemId}>
                  {evt.title} ({evt.stockLabel})
                </option>
              ))}
            </select>
          </div>

          <div className="space-y-1.5">
            <Label className="text-xs font-semibold text-[#666666]">Total Requests</Label>
            <Input
              type="number"
              min={50}
              max={5000}
              step={50}
              value={totalRequests}
              onChange={(e) => setTotalRequests(Number(e.target.value))}
              disabled={isBattling}
              className="h-8 text-xs font-semibold"
            />
          </div>

          <div className="space-y-1.5">
            <Label className="text-xs font-semibold text-[#666666]">Concurrency (Virtual Users)</Label>
            <Input
              type="number"
              min={5}
              max={100}
              step={5}
              value={concurrency}
              onChange={(e) => setConcurrency(Number(e.target.value))}
              disabled={isBattling}
              className="h-8 text-xs font-semibold"
            />
          </div>
        </CardContent>
      </Card>

      {/* 4 Strategy Arena Cards */}
      <div className="grid gap-4 md:grid-cols-2 xl:grid-cols-4">
        {(Object.keys(results) as OrderStrategy[]).map((strat) => {
          const res = results[strat];
          const isCurrentRunning = activeStrategyRunner === strat;
          const isUnsafe = strat === "UNSAFE_DB";
          const isGold = strat === "REDIS_LUA_WITH_COMPENSATION";

          return (
            <Card
              key={strat}
              className={`flex flex-col justify-between transition-all ${
                isCurrentRunning
                  ? "border-amber-400 bg-amber-50/20 shadow-md ring-2 ring-amber-300"
                  : isGold
                    ? "border-emerald-300 bg-emerald-50/10 shadow-sm"
                    : isUnsafe
                      ? "border-rose-200 bg-rose-50/10 shadow-sm"
                      : "border-[#e5e5e5] bg-white shadow-sm"
              }`}
            >
              <div>
                <CardHeader className="border-b border-[#f0f0f0] pb-3">
                  <div className="flex items-center justify-between">
                    <Badge
                      variant="outline"
                      className={`text-[10px] ${
                        isUnsafe
                          ? "border-rose-300 bg-rose-50 text-rose-700"
                          : isGold
                            ? "border-emerald-300 bg-emerald-50 text-emerald-700 font-bold"
                            : "border-neutral-300 text-neutral-700"
                      }`}
                    >
                      {isUnsafe ? "UNSAFE BASELINE" : isGold ? "GOLD STANDARD" : "INTERMEDIATE"}
                    </Badge>
                    <span className="font-mono text-xs font-bold text-[#898989]">
                      {res.status === "running" ? (
                        <span className="flex items-center gap-1 text-amber-600">
                          <Flame className="h-3 w-3 animate-spin" /> Live
                        </span>
                      ) : res.status === "completed" ? (
                        "Done"
                      ) : (
                        "Ready"
                      )}
                    </span>
                  </div>
                  <CardTitle className="mt-1 font-display text-base font-bold text-[#242424]">
                    {res.name}
                  </CardTitle>
                </CardHeader>

                <CardContent className="space-y-4 pt-4 text-xs">
                  <p className="line-clamp-3 text-xs leading-relaxed text-[#555555]">
                    {res.explanation}
                  </p>

                  {/* Key Metrics Grid */}
                  <div className="grid grid-cols-2 gap-2 rounded-lg bg-[#f8f8f8] p-2.5">
                    <div className="space-y-0.5">
                      <p className="text-[10px] uppercase tracking-wider text-[#898989]">Accepted</p>
                      <p className="font-display text-sm font-bold text-[#242424]">
                        {formatNumber(res.acceptedOrders)}
                      </p>
                    </div>

                    <div className="space-y-0.5">
                      <p className="text-[10px] uppercase tracking-wider text-[#898989]">Rejected</p>
                      <p className="font-display text-sm font-bold text-[#242424]">
                        {formatNumber(res.rejectedOrders)}
                      </p>
                    </div>

                    <div className="space-y-0.5">
                      <p className="text-[10px] uppercase tracking-wider text-[#898989]">Oversold</p>
                      <p
                        className={`font-display text-sm font-bold ${
                          res.oversoldCount > 0 ? "text-rose-600 animate-pulse" : "text-emerald-700"
                        }`}
                      >
                        {res.oversoldCount > 0 ? `+${res.oversoldCount} (VIOLATED!)` : "0 (SAFE)"}
                      </p>
                    </div>

                    <div className="space-y-0.5">
                      <p className="text-[10px] uppercase tracking-wider text-[#898989]">Throughput</p>
                      <p className="font-display text-sm font-bold text-[#242424]">
                        {res.throughputTps > 0 ? `${res.throughputTps} TPS` : "—"}
                      </p>
                    </div>
                  </div>

                  {/* Stock State Comparison */}
                  <div className="space-y-1 text-[11px]">
                    <div className="flex justify-between text-[#666666]">
                      <span>Redis Stock:</span>
                      <span className="font-mono font-bold text-[#242424]">{res.redisStock}</span>
                    </div>
                    <div className="flex justify-between text-[#666666]">
                      <span>MySQL DB Stock:</span>
                      <span
                        className={`font-mono font-bold ${
                          res.dbStock < 0 ? "text-rose-600" : "text-[#242424]"
                        }`}
                      >
                        {res.dbStock}
                      </span>
                    </div>
                    <div className="flex justify-between text-[#666666]">
                      <span>Drift Inconsistency:</span>
                      <span
                        className={`font-mono font-bold ${
                          res.driftCount > 0 ? "text-amber-600" : "text-emerald-700"
                        }`}
                      >
                        {res.driftCount}
                      </span>
                    </div>
                  </div>
                </CardContent>
              </div>

              <div className="border-t border-[#f0f0f0] p-3">
                <Button
                  type="button"
                  variant="secondary"
                  size="sm"
                  onClick={() => runSimulationForStrategy(strat)}
                  disabled={isBattling}
                  className="w-full text-xs"
                >
                  Run Single Probe
                </Button>
              </div>
            </Card>
          );
        })}
      </div>
    </div>
  );
}

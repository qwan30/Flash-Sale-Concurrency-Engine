"use client";

import { useState } from "react";
import {
  Activity,
  CheckCircle2,
  ChevronDown,
  ChevronUp,
  Database,
  Layers,
  RotateCcw,
  Server,
  ShieldAlert,
  Zap,
} from "lucide-react";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardHeader } from "@/components/ui/card";

export type PipelineStage = {
  id: string;
  number: number;
  title: string;
  subtitle: string;
  component: string;
  icon: typeof Zap;
  tech: string;
  durationMs: number;
  status: "idle" | "active" | "success" | "warning" | "error";
  description: string;
  invariants: string[];
  payloadSample?: Record<string, unknown>;
};
const DEFAULT_STAGES: PipelineStage[] = [
  {
    id: "rate-limiter",
    number: 1,
    title: "Admission Control",
    subtitle: "Token Bucket Rate Limiting",
    component: "Resilience4j / Filter Gate",
    icon: ShieldAlert,
    tech: "In-Memory RateLimiter",
    durationMs: 0.3,
    status: "idle",
    description:
      "Evaluates incoming traffic against configured rate limits (e.g. 5,000 QPS). Drops malicious burst traffic immediately with HTTP 429 before consuming backend database connections or Redis capacity.",
    invariants: ["Rejects excess requests with HTTP 429", "Zero backend connection consumption"],
    payloadSample: {
      clientIp: "10.0.4.12",
      tokenBucketAvailable: 4982,
      decision: "ADMITTED",
    },
  },
  {
    id: "journal-claim",
    number: 2,
    title: "Idempotency & Journal",
    subtitle: "Deterministic Claim Record",
    component: "OperationJournalRepository",
    icon: Layers,
    tech: "MySQL UNIQUE KEY (actor_id, hash)",
    durationMs: 2.1,
    status: "idle",
    description:
      "Calculates SHA-256 fingerprint from Idempotency-Key + payload. Inserts a journal entry with state `RECEIVED`. If duplicate click occurs, MySQL throws Duplicate Key and engine replays existing result without double-decrementing stock.",
    invariants: ["State: RECEIVED", "Anti-replay protection across mobile re-tries"],
    payloadSample: {
      operationId: "a1b2c3d4-0000-4000-8000-1234567890ab",
      idempotencyHash: "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
      state: "RECEIVED",
    },
  },
  {
    id: "redis-lua",
    number: 3,
    title: "Atomic Redis Lua Gate",
    subtitle: "In-Memory Pre-Deduction",
    component: "RedisReservationStockAdapter",
    icon: Zap,
    tech: "Single-Threaded Lua Script",
    durationMs: 0.8,
    status: "idle",
    description:
      "Executes `reservation-apply-once.lua` atomically in Redis. Checks `stock >= quantity` and `fence_version`. If stock is 0, rejects in <1ms without burdening MySQL InnoDB lock tables.",
    invariants: ["Atomic single-threaded decrement", "Sub-millisecond fast rejection if sold out"],
    payloadSample: {
      redisKey: "flashsale:reservation:stock:4",
      stockBefore: 1000,
      stockAfter: 999,
      fenceVersion: 0,
      status: "APPLIED",
    },
  },
  {
    id: "mysql-commit",
    number: 4,
    title: "MySQL ACID Store",
    subtitle: "Durable Fencing & Row Lock",
    component: "JdbcReservationRepository",
    icon: Database,
    tech: "InnoDB Row Lock / Partition Table",
    durationMs: 6.4,
    status: "idle",
    description:
      "Performs conditional UPDATE on `inventory_stock_account` and INSERT into `inventory_reservation`. If DB fails after Redis decrement, immediate compensating rollback (`reservation-compensate-once.lua`) is executed.",
    invariants: ["State: COMMITTED", "Zero oversell constraint: available >= 0"],
    payloadSample: {
      table: "inventory_stock_account",
      ticketItemId: 4,
      availableQuantity: 999,
      fenceVersion: 0,
      status: "COMMITTED",
    },
  },
  {
    id: "transactional-outbox",
    number: 5,
    title: "Transactional Outbox",
    subtitle: "Guaranteed Dual-Write",
    component: "OutboxService",
    icon: Server,
    tech: "outbox_event (Same DB Transaction)",
    durationMs: 1.2,
    status: "idle",
    description:
      "Inserts domain event (`ReservationCreatedEvent`) into `outbox_event` inside the exact same DB transaction. Guarantees business state and outgoing event log never drift.",
    invariants: ["Atomic with DB commit", "Status: PENDING"],
    payloadSample: {
      eventId: "evt-7788-9900",
      aggregateType: "Reservation",
      eventType: "RESERVATION_CREATED",
      status: "PENDING",
    },
  },
  {
    id: "kafka-stream",
    number: 6,
    title: "Kafka Event Stream",
    subtitle: "Async Broker & Reconciler",
    component: "OutboxPublishScheduler / KRaft",
    icon: Activity,
    tech: "Kafka topic: flashsale.orders (acks=all)",
    durationMs: 4.5,
    status: "idle",
    description:
      "Background daemon sweeps PENDING outbox events every 1,000ms and publishes to Kafka with `acks=all`. Meanwhile, `OrderReconciliationService` continuously sweeps for Redis-DB stock drift.",
    invariants: ["At-least-once delivery", "Self-healing reconciliation every 30s"],
    payloadSample: {
      topic: "flashsale.orders",
      partition: 2,
      offset: 14092,
      status: "PUBLISHED",
    },
  },
];

export function SystemXRayPipeline({
  activeStageId,
  isExecuting,
  currentOperation = "POST /api/v1/reservations",
  defaultExpanded = false,
}: {
  activeStageId?: string;
  isExecuting?: boolean;
  currentOperation?: string;
  defaultExpanded?: boolean;
}) {
  const [isExpandedManual, setIsExpandedManual] = useState<boolean | null>(null);
  const isExpanded = isExpandedManual !== null ? isExpandedManual : Boolean(defaultExpanded || isExecuting);
  const [selectedStage, setSelectedStage] = useState<PipelineStage>(DEFAULT_STAGES[2]);
  const [stepMode, setStepMode] = useState<number | null>(null);

  const activeIndex = stepMode !== null 
    ? stepMode 
    : activeStageId 
      ? DEFAULT_STAGES.findIndex(s => s.id === activeStageId)
      : -1;

  const currentStage = DEFAULT_STAGES[activeIndex >= 0 ? activeIndex : 2];

  const handleStepForward = () => {
    setStepMode((prev) => (prev === null ? 0 : Math.min(DEFAULT_STAGES.length - 1, prev + 1)));
  };

  const handleStepBackward = () => {
    setStepMode((prev) => (prev === null ? 0 : Math.max(0, prev - 1)));
  };

  const handleReset = () => {
    setStepMode(null);
  };

  return (
    <Card className="border-[#e5e5e5] bg-white shadow-sm transition-all">
      {/* Header Bar with Progressive Disclosure Toggle */}
      <CardHeader className="py-3 px-4">
        <div className="flex flex-wrap items-center justify-between gap-3">
          <div className="flex items-center gap-2.5">
            <span className="flex h-6 w-6 items-center justify-center rounded-full bg-[#242424] text-xs font-bold text-white">
              6
            </span>
            <div>
              <div className="flex items-center gap-2">
                <span className="text-xs font-bold text-[#242424]">Distributed Architecture Pipeline</span>
                <span className="text-[11px] text-[#898989]">
                  · Observing: <code className="font-mono text-[#242424]">{currentOperation}</code>
                </span>
              </div>
            </div>
          </div>

          <div className="flex items-center gap-2">
            {isExpanded && (
              <>
                <Button
                  type="button"
                  variant="secondary"
                  size="sm"
                  onClick={handleStepBackward}
                  disabled={stepMode === 0}
                  className="h-7 text-xs px-2"
                >
                  Prev
                </Button>
                <Button
                  type="button"
                  variant="secondary"
                  size="sm"
                  onClick={handleStepForward}
                  disabled={stepMode === DEFAULT_STAGES.length - 1}
                  className="h-7 text-xs px-2"
                >
                  Next
                </Button>
                {stepMode !== null && (
                  <Button
                    type="button"
                    variant="ghost"
                    size="sm"
                    onClick={handleReset}
                    className="h-7 text-xs text-[#898989] px-2"
                  >
                    <RotateCcw className="h-3 w-3 mr-1" /> Live
                  </Button>
                )}
              </>
            )}

            <Button
              type="button"
              variant="secondary"
              size="sm"
              onClick={() => setIsExpandedManual((prev) => (prev !== null ? !prev : !isExpanded))}
              className="h-7 text-xs"
            >
              {isExpanded ? (
                <>
                  <ChevronUp className="mr-1 h-3.5 w-3.5" />
                  Hide Deep View
                </>
              ) : (
                <>
                  <ChevronDown className="mr-1 h-3.5 w-3.5" />
                  Inspect Flowchart
                </>
              )}
            </Button>
          </div>
        </div>

        {/* Compact Mini-Pipeline Ribbon (Always Visible) */}
        <div className="mt-3 flex items-center justify-between overflow-x-auto rounded-lg bg-[#fafafa] p-1.5 border border-[#f0f0f0] text-[11px]">
          {DEFAULT_STAGES.map((stage, idx) => {
            const isCurrent = activeIndex === idx;
            const isPast = activeIndex > idx;

            return (
              <button
                key={stage.id}
                type="button"
                onClick={() => {
                  setStepMode(idx);
                  setSelectedStage(stage);
                  setIsExpandedManual(true);
                }}
                className={`flex items-center gap-1.5 rounded px-2 py-1 font-medium transition-all ${
                  isCurrent
                    ? "bg-[#242424] text-white shadow-xs font-bold"
                    : isPast
                      ? "text-emerald-700 bg-emerald-50/60 font-semibold"
                      : "text-[#666666] hover:bg-white hover:text-[#242424]"
                }`}
              >
                <span className="text-[10px] opacity-75">{stage.number}.</span>
                <span>{stage.title}</span>
                {idx < DEFAULT_STAGES.length - 1 && (
                  <span className="text-[#cccccc] ml-1">→</span>
                )}
              </button>
            );
          })}
        </div>
      </CardHeader>

      {/* Expanded Deep Inspection Grid */}
      {isExpanded && (
        <CardContent className="space-y-4 border-t border-[#f0f0f0] pt-4">
          <div className="grid grid-cols-1 gap-2.5 sm:grid-cols-2 lg:grid-cols-6">
            {DEFAULT_STAGES.map((stage, idx) => {
              const Icon = stage.icon;
              const isCurrent = activeIndex === idx;
              const isPast = activeIndex > idx;

              return (
                <div
                  key={stage.id}
                  onClick={() => {
                    setStepMode(idx);
                    setSelectedStage(stage);
                  }}
                  className={`group relative flex cursor-pointer flex-col justify-between rounded-lg border p-2.5 transition-all ${
                    isCurrent
                      ? "border-[#242424] bg-neutral-900 text-white shadow-sm"
                      : isPast
                        ? "border-emerald-200 bg-emerald-50/30 text-[#242424] hover:border-emerald-400"
                        : "border-[#ebebeb] bg-[#fafafa] text-[#666666] hover:border-[#cccccc] hover:bg-white"
                  }`}
                >
                  <div>
                    <div className="flex items-center justify-between">
                      <span
                        className={`flex h-5 w-5 items-center justify-center rounded-full text-[10px] font-bold ${
                          isCurrent
                            ? "bg-white text-black"
                            : isPast
                              ? "bg-emerald-600 text-white"
                              : "bg-[#e5e5e5] text-[#666666]"
                        }`}
                      >
                        {stage.number}
                      </span>
                      <Icon
                        className={`h-3.5 w-3.5 ${
                          isCurrent
                            ? "text-emerald-400"
                            : isPast
                              ? "text-emerald-600"
                              : "text-[#898989]"
                        }`}
                      />
                    </div>

                    <p
                      className={`mt-1.5 text-[10px] font-semibold uppercase tracking-wider ${
                        isCurrent ? "text-neutral-300" : isPast ? "text-emerald-800" : "text-[#898989]"
                      }`}
                    >
                      {stage.title}
                    </p>
                    <p
                      className={`line-clamp-1 text-xs font-bold ${
                        isCurrent ? "text-white" : "text-[#242424]"
                      }`}
                    >
                      {stage.subtitle}
                    </p>
                  </div>

                  <div className="mt-3 flex items-center justify-between border-t border-black/10 pt-1 text-[10px]">
                    <span className={isCurrent ? "text-neutral-400" : "text-[#898989]"}>
                      {stage.tech.split(" ")[0]}
                    </span>
                    <span
                      className={`font-mono font-semibold ${
                        isCurrent
                          ? "text-emerald-300"
                          : isPast
                            ? "text-emerald-700"
                            : "text-[#242424]"
                      }`}
                    >
                      {stage.durationMs}ms
                    </span>
                  </div>
                </div>
              );
            })}
          </div>

          {/* Deep Dive Stage Inspection Box */}
          <div className="rounded-lg border border-[#ebebeb] bg-[#f9f9f9] p-3.5">
            <div className="grid gap-4 lg:grid-cols-[1.4fr_1fr]">
              <div className="space-y-2">
                <div className="flex items-center gap-2">
                  <span className="flex h-5 w-5 items-center justify-center rounded-full bg-[#242424] text-[11px] font-bold text-white">
                    {(selectedStage || currentStage).number}
                  </span>
                  <h4 className="font-display text-sm font-bold text-[#242424]">
                    {(selectedStage || currentStage).title} — {(selectedStage || currentStage).subtitle}
                  </h4>
                  <Badge variant="secondary" className="text-[10px]">
                    {(selectedStage || currentStage).component}
                  </Badge>
                </div>

                <p className="text-xs leading-relaxed text-[#555555]">
                  {(selectedStage || currentStage).description}
                </p>

                <div className="pt-1">
                  <p className="text-[10px] font-semibold uppercase tracking-wider text-[#898989]">
                    Architectural Invariants & Guarantees
                  </p>
                  <ul className="mt-1 space-y-1">
                    {(selectedStage || currentStage).invariants.map((inv, i) => (
                      <li key={i} className="flex items-center gap-1.5 text-xs text-[#242424]">
                        <CheckCircle2 className="h-3 w-3 shrink-0 text-emerald-600" />
                        <span>{inv}</span>
                      </li>
                    ))}
                  </ul>
                </div>
              </div>

              {/* Live Payload / State Snapshot */}
              <div className="flex flex-col justify-between rounded-lg border border-[#e0e0e0] bg-white p-2.5">
                <div>
                  <div className="flex items-center justify-between border-b border-[#f0f0f0] pb-1">
                    <span className="text-[10px] font-bold uppercase tracking-wider text-[#898989]">
                      State Snapshot
                    </span>
                    <span className="font-mono text-[10px] text-emerald-600">
                      {(selectedStage || currentStage).durationMs}ms
                    </span>
                  </div>
                  <pre className="mt-1.5 max-h-32 overflow-x-auto rounded bg-[#1e1e1e] p-2 font-mono text-[10px] leading-tight text-emerald-400">
                    {JSON.stringify((selectedStage || currentStage).payloadSample, null, 2)}
                  </pre>
                </div>
              </div>
            </div>
          </div>
        </CardContent>
      )}
    </Card>
  );
}

"use client";

import { useEffect, useState } from "react";
import {
  AlertOctagon,
  AlertTriangle,
  ArrowRight,
  CheckCircle2,
  Cpu,
  Database,
  Flame,
  Radio,
  RefreshCw,
  ServerCrash,
  ShieldAlert,
  ShieldCheck,
  Zap,
} from "lucide-react";
import { toast } from "sonner";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";

export type FaultPoint =
  | "AFTER_REDIS_BEFORE_DB"
  | "AFTER_DB_COMMIT_BEFORE_RESPONSE"
  | "REDIS_MIRROR_TIMEOUT"
  | "KAFKA_UNAVAILABLE"
  | "CONFIRM_EXPIRE_RACE";

interface FaultInfo {
  id: FaultPoint;
  name: string;
  category: "Database Crash" | "Network Drop" | "Cache Timeout" | "Broker Failure" | "Race Condition";
  severity: "CRITICAL" | "HIGH" | "MEDIUM";
  scenarioDescription: string;
  expectedSystemReaction: string;
  selfHealingMechanism: string;
}

const FAULT_DEFINITIONS: FaultInfo[] = [
  {
    id: "AFTER_REDIS_BEFORE_DB",
    name: "Crash After Redis Before DB",
    category: "Database Crash",
    severity: "CRITICAL",
    scenarioDescription:
      "Redis Lua successfully decrements stock in cache, but MySQL network drops or deadlocks before committing the order.",
    expectedSystemReaction:
      "CreateReservationService catches DB exception and executes compensating rollback (`reservation-compensate-once.lua`).",
    selfHealingMechanism:
      "Immediate Redis restore + Journal marked `COMPENSATED`. If Redis is also unreachable (double fault), Recovery Daemon repairs via fence token.",
  },
  {
    id: "AFTER_DB_COMMIT_BEFORE_RESPONSE",
    name: "Network Partition on Response ACK",
    category: "Network Drop",
    severity: "HIGH",
    scenarioDescription:
      "MySQL commits transaction and Outbox record, but network drops before HTTP response packet reaches client.",
    expectedSystemReaction:
      "Client retries request with exact same Idempotency-Key. Journal unique constraint intercepts duplicate execution.",
    selfHealingMechanism:
      "Replay claim returns the committed `reservationId` without re-decrementing stock in Redis or MySQL.",
  },
  {
    id: "REDIS_MIRROR_TIMEOUT",
    name: "Redis Terminal Mirror Socket Timeout",
    category: "Cache Timeout",
    severity: "HIGH",
    scenarioDescription:
      "Two-phase release or confirmation succeeds in MySQL, but async Redis mirror sync times out.",
    expectedSystemReaction:
      "Journal transitions to `MIRROR_PENDING`. User request does not fail; background sweeper takes over mirror sync.",
    selfHealingMechanism:
      "ReservationRecoveryScheduler claims dangling journal and replays `mirrorTerminalOnce` with active fence token.",
  },
  {
    id: "KAFKA_UNAVAILABLE",
    name: "Kafka Cluster Outage / Partition Down",
    category: "Broker Failure",
    severity: "MEDIUM",
    scenarioDescription:
      "Order created successfully in database, but Kafka message broker is unreachable.",
    expectedSystemReaction:
      "Transactional Outbox stores event in `outbox_event` with status `PENDING`. API request succeeds with 0 loss.",
    selfHealingMechanism:
      "OutboxPublishScheduler retries with exponential backoff until Kafka recovers, guaranteeing At-Least-Once delivery.",
  },
  {
    id: "CONFIRM_EXPIRE_RACE",
    name: "Confirm vs Expiry Sweeper Race",
    category: "Race Condition",
    severity: "HIGH",
    scenarioDescription:
      "User submits confirmation payment at exactly T=120.001s while background Expiry Sweeper is running.",
    expectedSystemReaction:
      "Conditional state transition `WHERE status = 'RESERVED'` guarantees exactly one transaction succeeds.",
    selfHealingMechanism:
      "Either Confirm wins and Sweeper no-ops, or Sweeper expires seat and Confirm is rejected with 409 Conflict safely.",
  },
];

export function ChaosResilienceMatrix() {
  const [activeFault, setActiveFault] = useState<FaultPoint | null>(null);
  const [catalog, setCatalog] = useState<FaultPoint[]>([]);
  const [isLoading, setIsLoading] = useState(false);
  const [lastTestOutput, setLastTestOutput] = useState<string | null>(null);

  const fetchFaultCatalog = async () => {
    try {
      const res = await fetch("/api/backend/__chaos/faults");
      if (res.ok) {
        const data = await res.json();
        setCatalog(data.catalog || []);
        setActiveFault(data.active || null);
      }
    } catch {
      // Chaos endpoint might only be active in chaos profile
    }
  };

  useEffect(() => {
    fetchFaultCatalog();
  }, []);

  const handleActivateFault = async (faultId: FaultPoint) => {
    setIsLoading(true);
    try {
      const res = await fetch(`/api/backend/__chaos/faults/${faultId}`, {
        method: "PUT",
      });
      if (res.ok) {
        setActiveFault(faultId);
        toast.warning(`Chaos fault ${faultId} is now ACTIVE!`);
        setLastTestOutput(`Fault injection point [${faultId}] armed in JVM interceptor.`);
      } else {
        toast.info(`Simulated fault [${faultId}] activated in demo mode.`);
        setActiveFault(faultId);
      }
    } catch {
      setActiveFault(faultId);
      toast.info(`Simulated fault [${faultId}] activated in demo mode.`);
    } finally {
      setIsLoading(false);
    }
  };

  const handleClearFaults = async () => {
    setIsLoading(true);
    try {
      await fetch("/api/backend/__chaos/faults", { method: "DELETE" });
      setActiveFault(null);
      toast.success("All chaos faults cleared. Engine running normally.");
      setLastTestOutput("All chaos injection points cleared.");
    } catch {
      setActiveFault(null);
    } finally {
      setIsLoading(false);
    }
  };

  return (
    <Card className="border-[#e5e5e5] bg-white shadow-sm">
      <CardHeader className="border-b border-[#f0f0f0] pb-4">
        <div className="flex flex-wrap items-center justify-between gap-4">
          <div className="space-y-1">
            <div className="flex items-center gap-2">
              <Badge variant="outline" className="border-rose-200 bg-rose-50 text-rose-700">
                <AlertOctagon className="mr-1 h-3 w-3" />
                Resilience & Chaos Matrix
              </Badge>
              <span className="text-xs text-[#898989]">Fault Injection & Self-Healing</span>
            </div>
            <CardTitle className="font-display text-xl font-bold text-[#242424]">
              Chaos Fault Lab & Invariant Recovery
            </CardTitle>
            <CardDescription className="text-xs text-[#666666]">
              Simulate real-world cloud outages, network drops, and race conditions to observe automatic recovery.
            </CardDescription>
          </div>

          <div className="flex items-center gap-2">
            {activeFault && (
              <Badge className="bg-rose-600 font-mono text-xs text-white">
                ARMED: {activeFault}
              </Badge>
            )}
            <Button
              type="button"
              variant="secondary"
              size="sm"
              onClick={handleClearFaults}
              disabled={isLoading || !activeFault}
              className="text-xs"
            >
              Clear Faults
            </Button>
          </div>
        </div>
      </CardHeader>

      <CardContent className="space-y-4 pt-5">
        <div className="grid gap-3 md:grid-cols-2 lg:grid-cols-3">
          {FAULT_DEFINITIONS.map((fault) => {
            const isArmed = activeFault === fault.id;

            return (
              <div
                key={fault.id}
                className={`flex flex-col justify-between rounded-xl border p-4 transition-all ${
                  isArmed
                    ? "border-rose-400 bg-rose-50/40 shadow-sm ring-2 ring-rose-300"
                    : "border-[#e5e5e5] bg-[#fafafa] hover:border-[#cccccc] hover:bg-white"
                }`}
              >
                <div className="space-y-2.5">
                  <div className="flex items-start justify-between gap-2">
                    <Badge
                      variant="secondary"
                      className={`text-[10px] ${
                        fault.severity === "CRITICAL"
                          ? "bg-rose-100 text-rose-800 font-bold"
                          : "bg-amber-100 text-amber-800"
                      }`}
                    >
                      {fault.category}
                    </Badge>
                    {isArmed && (
                      <span className="flex items-center gap-1 font-mono text-[10px] font-bold text-rose-600">
                        <span className="h-2 w-2 animate-ping rounded-full bg-rose-500" /> Active
                      </span>
                    )}
                  </div>

                  <h4 className="font-display text-sm font-bold text-[#242424]">{fault.name}</h4>

                  <p className="text-xs leading-relaxed text-[#555555]">
                    {fault.scenarioDescription}
                  </p>

                  <div className="rounded-lg bg-white p-2.5 text-[11px] space-y-1.5 border border-[#ebebeb]">
                    <p className="font-semibold text-[#242424]">🛡️ Self-Healing Behavior:</p>
                    <p className="text-[#666666] leading-normal">{fault.selfHealingMechanism}</p>
                  </div>
                </div>

                <div className="mt-4 pt-2 border-t border-black/5">
                  <Button
                    type="button"
                    variant={isArmed ? "danger" : "secondary"}
                    size="sm"
                    onClick={() => handleActivateFault(fault.id)}
                    disabled={isLoading}
                    className="w-full text-xs"
                  >
                    {isArmed ? "Disarm Fault" : "Inject Fault"}
                  </Button>
                </div>
              </div>
            );
          })}
        </div>

        {lastTestOutput && (
          <div className="rounded-lg bg-[#1e1e1e] p-3 font-mono text-xs text-emerald-400">
            <span className="text-[#898989]">// Chaos Console: </span>
            {lastTestOutput}
          </div>
        )}
      </CardContent>
    </Card>
  );
}

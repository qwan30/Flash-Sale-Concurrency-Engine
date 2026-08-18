"use client";

import { useEffect, useState } from "react";
import { AlertOctagon } from "lucide-react";
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

export type ChaosScenario = {
  id: FaultPoint;
  title: string;
  stage: string;
  triggerPoint: string;
  expectedSymptom: string;
  selfHealingMechanism: string;
  safetyProof: string;
};

const CHAOS_SCENARIOS: ChaosScenario[] = [
  {
    id: "AFTER_REDIS_BEFORE_DB",
    title: "1. DB Network Drop After Redis Pre-Deduction",
    stage: "Between Stage 3 & Stage 4",
    triggerPoint: "Immediately after Redis Lua deduction succeeds, simulate MySQL timeout.",
    expectedSymptom:
      "Redis stock decreased by 1, but MySQL row commit failed. Temporary phantom stock deficit.",
    selfHealingMechanism:
      "Automated Compensation: `reservation-compensate-once.lua` rolls back Redis stock. If compensator crashes, `OrderReconciliationService` background sweeper detects drift and heals within 30s.",
    safetyProof: "Zero phantom stock loss, eventual consistency guaranteed.",
  },
  {
    id: "AFTER_DB_COMMIT_BEFORE_RESPONSE",
    title: "2. Client Disconnect After MySQL Commit",
    stage: "After Stage 4 Commit",
    triggerPoint: "DB commits successfully, but server drops socket before HTTP 200 reaches client.",
    expectedSymptom:
      "User gets connection timeout / network error and retries with same Idempotency-Key.",
    selfHealingMechanism:
      "Idempotency Replay: Operation journal finds state `COMMITTED`, skips deduction, and replays existing order number.",
    safetyProof: "Zero double charges, zero double stock deductions.",
  },
  {
    id: "REDIS_MIRROR_TIMEOUT",
    title: "3. Redis Mirror Latency Spike",
    stage: "Stage 3 Async Mirroring",
    triggerPoint: "Simulate 5,000ms Redis latency during terminal status mirroring.",
    expectedSymptom: "Async task queue backs up, warning logs emitted.",
    selfHealingMechanism:
      "Circuit Breaker & Non-blocking Queue: Core MySQL ACID commit is unaffected. Redis status mirror falls back to asynchronous repair batch.",
    safetyProof: "Primary transaction latency remains bounded.",
  },
  {
    id: "KAFKA_UNAVAILABLE",
    title: "4. Kafka Broker Outage",
    stage: "Stage 5 / Stage 6 Outbox Publishing",
    triggerPoint: "Simulate Kafka cluster leader election / broker disconnection.",
    expectedSymptom: "Events cannot be sent to Kafka immediately.",
    selfHealingMechanism:
      "Transactional Outbox: Events safely accumulate in MySQL `outbox_event` table with status `PENDING`. Outbox sweeper retries with exponential backoff until Kafka recovers.",
    safetyProof: "Zero message loss, guaranteed at-least-once downstream delivery.",
  },
  {
    id: "CONFIRM_EXPIRE_RACE",
    title: "5. Confirm vs Expiry Sweeper Race Condition",
    stage: "Two-Phase Reservation",
    triggerPoint:
      "User confirms reservation at t=119.999s while Background Expiry Sweeper fires at t=120.000s.",
    expectedSymptom: "Concurrent UPDATE statements hit the same reservation row.",
    selfHealingMechanism:
      "Optimistic Fencing (`fence_version`): State machine transition is atomic. `UPDATE inventory_reservation SET status='CONFIRMED', fence_version=fence_version+1 WHERE status='RESERVED' AND fence_version=?`.",
    safetyProof:
      "Either Confirm wins and Sweeper no-ops, or Sweeper expires seat and Confirm is rejected with 409 Conflict safely.",
  },
];

export function ChaosResilienceMatrix() {
  const [activeFault, setActiveFault] = useState<FaultPoint | null>(null);
  const [isLoading, setIsLoading] = useState(false);
  const [lastTestOutput, setLastTestOutput] = useState<string | null>(null);

  useEffect(() => {
    let ignore = false;
    async function loadCatalog() {
      try {
        const res = await fetch("/api/backend/__chaos/faults");
        if (res.ok && !ignore) {
          const data = await res.json();
          setActiveFault(data.active || null);
        }
      } catch {
        // Chaos endpoint might only be active in chaos profile
      }
    }
    void loadCatalog();
    return () => {
      ignore = true;
    };
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
      toast.info(`Simulated fault [${faultId}] activated in offline mode.`);
      setActiveFault(faultId);
    } finally {
      setIsLoading(false);
    }
  };

  const handleClearFaults = async () => {
    setIsLoading(true);
    try {
      await fetch("/api/backend/__chaos/faults", {
        method: "DELETE",
      });
      setActiveFault(null);
      setLastTestOutput("All chaos fault injectors disarmed. System in normal operating mode.");
      toast.success("All chaos faults cleared!");
    } catch {
      setActiveFault(null);
      setLastTestOutput("Cleared local simulation state.");
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
                Chaos & Self-Healing Matrix
              </Badge>
              <span className="text-xs text-[#898989]">ChaosFaultController (`/__chaos/faults`)</span>
            </div>
            <CardTitle className="font-display text-xl font-bold text-[#242424]">
              Distributed Fault Injection & Resilience Lab
            </CardTitle>
            <CardDescription className="text-xs text-[#666666]">
              Trigger controlled partial failures in JVM interceptors to observe automated self-healing, stock compensation, and zero-drift guarantees.
            </CardDescription>
          </div>

          <div className="flex items-center gap-2">
            {activeFault && (
              <Badge className="bg-rose-600 font-mono text-xs text-white">
                ARMED: {activeFault}
              </Badge>
            )}
            <Button
              variant="secondary"
              size="sm"
              onClick={handleClearFaults}
              disabled={isLoading || !activeFault}
              className="text-xs"
            >
              Disarm All Faults
            </Button>
          </div>
        </div>
      </CardHeader>

      <CardContent className="space-y-4 pt-5">
        <div className="grid gap-3.5 md:grid-cols-2 lg:grid-cols-3">
          {CHAOS_SCENARIOS.map((scenario) => {
            const isArmed = activeFault === scenario.id;

            return (
              <div
                key={scenario.id}
                className={`flex flex-col justify-between rounded-lg border p-3.5 transition-all ${
                  isArmed
                    ? "border-rose-500 bg-rose-50/40 shadow-xs"
                    : "border-[#e5e5e5] bg-[#fafafa] hover:border-[#cccccc] hover:bg-white"
                }`}
              >
                <div className="space-y-2">
                  <div className="flex items-center justify-between">
                    <Badge
                      variant={isArmed ? "warning" : "secondary"}
                      className="text-[10px] font-medium"
                    >
                      {scenario.stage}
                    </Badge>
                    {isArmed && (
                      <span className="text-[10px] font-bold uppercase tracking-wider text-rose-600">
                        Active In JVM
                      </span>
                    )}
                  </div>

                  <h4 className="font-display text-sm font-bold text-[#242424]">
                    {scenario.title}
                  </h4>

                  <div className="space-y-1.5 text-xs">
                    <div>
                      <span className="font-semibold text-rose-700">Failure Trigger: </span>
                      <span className="text-[#555555]">{scenario.triggerPoint}</span>
                    </div>
                    <div>
                      <span className="font-semibold text-emerald-700">Self-Healing: </span>
                      <span className="text-[#555555]">{scenario.selfHealingMechanism}</span>
                    </div>
                  </div>
                </div>

                <div className="mt-4 pt-3 border-t border-black/5 flex items-center justify-between">
                  <span className="font-mono text-[10px] text-emerald-700 font-semibold">
                    {scenario.safetyProof}
                  </span>
                  <Button
                    size="sm"
                    variant={isArmed ? "danger" : "secondary"}
                    onClick={() => handleActivateFault(scenario.id)}
                    disabled={isLoading}
                    className="h-7 text-xs px-2.5"
                  >
                    {isArmed ? "Re-arm" : "Inject Fault"}
                  </Button>
                </div>
              </div>
            );
          })}
        </div>

        {lastTestOutput && (
          <div className="rounded-lg bg-[#1e1e1e] p-3 font-mono text-xs text-emerald-400">
            <span className="text-[#898989]">{"// Chaos Console: "}</span>
            {lastTestOutput}
          </div>
        )}
      </CardContent>
    </Card>
  );
}

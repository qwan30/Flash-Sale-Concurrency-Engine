"use client";

import Link from "next/link";
import { useCallback, useEffect, useRef, useState } from "react";
import { toast } from "sonner";

import {
  confirmReservation,
  createReservation,
  createReservationIdempotencyKey,
  getDemoActorId,
  getReservation,
  getReservationInventory,
  getReservationPollDelayMs,
  releaseReservation,
  ReservationClientError,
  shouldStopReservationPolling,
} from "@/lib/reservation-client";
import type {
  InventoryResponse,
  ReservationCreateRequest,
  ReservationProcessingResponse,
  ReservationResponse,
} from "@/lib/reservation-types";
import type { EventSummary } from "@/lib/types";
import { formatCurrency, formatDateTime } from "@/lib/utils";

import { DemoScenarioDrawer } from "@/components/demo-scenario-drawer";
import { ReservationStockBuckets } from "@/components/reservation-stock-buckets";
import { ReservationTimeline } from "@/components/reservation-timeline";
import { SystemXRayPipeline } from "@/components/system-xray-pipeline";
import { Alert } from "@/components/ui/alert";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";

const chaosProfileEnabled = process.env.NEXT_PUBLIC_RESERVATION_CHAOS === "true";
type ScenarioCleanupStatus = "released" | "terminal" | "pending" | "failed";
type ScenarioCleanupSummary = Record<ScenarioCleanupStatus, number>;
const SCENARIO_REQUEST_TIMEOUT_MS = 5_000;
const OVERLOAD_SCENARIO_ATTEMPTS = 48;

function emptyCleanupSummary(): ScenarioCleanupSummary {
  return { released: 0, terminal: 0, pending: 0, failed: 0 };
}

function cleanupSummary(statuses: ScenarioCleanupStatus[]): ScenarioCleanupSummary {
  return statuses.reduce((summary, status) => {
    summary[status] += 1;
    return summary;
  }, emptyCleanupSummary());
}

async function withScenarioRequestTimeout<T>(
  operation: (signal: AbortSignal) => Promise<T>,
): Promise<T> {
  const controller = new AbortController();
  const timeout = window.setTimeout(() => controller.abort(), SCENARIO_REQUEST_TIMEOUT_MS);
  try {
    return await operation(controller.signal);
  } finally {
    window.clearTimeout(timeout);
  }
}

export function ReservationJourney({
  ticketItemId,
  event,
}: {
  ticketItemId: number;
  event?: EventSummary;
}) {
  const [actorId, setActorId] = useState<string | null>(null);
  const [quantity, setQuantity] = useState(1);
  const [inventory, setInventory] = useState<InventoryResponse | null>(null);
  const [inventoryError, setInventoryError] = useState<string | null>(null);
  const [reservation, setReservation] = useState<ReservationResponse | null>(null);
  const [processing, setProcessing] = useState<ReservationProcessingResponse | null>(null);
  const [pollingHalted, setPollingHalted] = useState(false);
  const [notice, setNotice] = useState<string | null>(null);
  const [scenarioMessage, setScenarioMessage] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [clockMs, setClockMs] = useState(() => Date.now());
  const [hasCreateIntent, setHasCreateIntent] = useState(false);
  const intentKey = useRef<string | null>(null);
  const lastCreateIntent = useRef<ReservationCreateRequest | null>(null);
  const pollInFlight = useRef(false);

  const refreshInventory = useCallback(async () => {
    try {
      const result = await getReservationInventory(ticketItemId);
      setInventory(result.body);
      setInventoryError(null);
    } catch (error) {
      setInventory(null);
      setInventoryError(
        error instanceof ReservationClientError
          ? error.details?.message ?? error.message
          : "Live inventory is unavailable",
      );
    }
  }, [ticketItemId]);

  const refreshReservation = useCallback(async (reservationId: string) => {
    const result = await getReservation(reservationId);
    if ("status" in result.body && result.body.status === "PROCESSING") {
      setProcessing(result.body);
      return false;
    }
    setProcessing(null);
    setClockMs(Date.now());
    intentKey.current = null;
    setReservation(result.body);
    return true;
  }, []);

  const cleanupScenarioReservation = useCallback(async (
    reservationId: string,
  ): Promise<ScenarioCleanupStatus> => {
    for (let attempt = 0; attempt < 8; attempt += 1) {
      try {
        const current = await withScenarioRequestTimeout(
          (signal) => getReservation(reservationId, signal),
        );
        const currentBody = current.body;
        if ("retryAfterSeconds" in currentBody) {
          await new Promise((resolve) => window.setTimeout(
            resolve,
            Math.min(2_000, getReservationPollDelayMs(currentBody.retryAfterSeconds)),
          ));
          continue;
        }
        if (currentBody.status !== "RESERVED") {
          return "terminal";
        }

        const releaseResult = await withScenarioRequestTimeout(
          (signal) => releaseReservation(reservationId, signal),
        );
        const releaseBody = releaseResult.body;
        if ("retryAfterSeconds" in releaseBody) {
          await new Promise((resolve) => window.setTimeout(
            resolve,
            Math.min(2_000, getReservationPollDelayMs(releaseBody.retryAfterSeconds)),
          ));
          continue;
        }
        return releaseBody.status === "RELEASED" ? "released" : "terminal";
      } catch {
        return "failed";
      }
    }
    return "pending";
  }, []);

  const reportError = useCallback((error: unknown) => {
    const message = error instanceof ReservationClientError
      ? error.details?.message ?? error.message
      : error instanceof Error ? error.message : "Reservation request could not be completed";
    setNotice(message);
    toast.error(message);
  }, []);

  const handlePollingError = useCallback((error: unknown) => {
    reportError(error);
    if (shouldStopReservationPolling(error)) {
      setPollingHalted(true);
      setProcessing(null);
    }
  }, [reportError]);

  useEffect(() => {
    if (!reservation?.expiresAt || reservation.status !== "RESERVED") {
      return undefined;
    }

    const timer = window.setInterval(() => setClockMs(Date.now()), 1000);
    return () => window.clearInterval(timer);
  }, [reservation?.expiresAt, reservation?.status]);

  useEffect(() => {
    // Browser-only identity is intentionally session-scoped for the demo.
    // eslint-disable-next-line react-hooks/set-state-in-effect
    setActorId(getDemoActorId());
    void refreshInventory();
  }, [refreshInventory]);

  useEffect(() => {
    const reservationId = processing?.reservationId;
    if (!reservationId || pollingHalted) {
      return undefined;
    }

    const timer = window.setInterval(() => {
      if (pollInFlight.current) {
        return;
      }
      pollInFlight.current = true;
      void refreshReservation(reservationId)
        .then((resolved) => resolved ? refreshInventory() : undefined)
        .catch(handlePollingError)
        .finally(() => {
          pollInFlight.current = false;
        });
    }, getReservationPollDelayMs(processing.retryAfterSeconds));
    return () => window.clearInterval(timer);
  }, [handlePollingError, pollingHalted, processing?.reservationId, processing?.retryAfterSeconds, refreshInventory, refreshReservation]);

  useEffect(() => {
    const reservationId = reservation?.reservationId;
    if (!reservationId || reservation.status !== "RESERVED" || processing || pollingHalted) {
      return undefined;
    }

    const timer = window.setInterval(() => {
      if (pollInFlight.current) {
        return;
      }
      pollInFlight.current = true;
      void refreshReservation(reservationId)
        .then(() => refreshInventory())
        .catch(handlePollingError)
        .finally(() => {
          pollInFlight.current = false;
        });
    }, 2000);
    return () => window.clearInterval(timer);
  }, [handlePollingError, pollingHalted, processing, refreshInventory, refreshReservation, reservation?.reservationId, reservation?.status]);

  const submitReservation = async () => {
    if (!actorId) {
      return;
    }
    setPollingHalted(false);
    setBusy(true);
    setNotice(null);
    setScenarioMessage(null);
    const idempotencyKey = intentKey.current ?? createReservationIdempotencyKey();
    intentKey.current = idempotencyKey;
    const requestBody: ReservationCreateRequest = { ticketItemId, quantity, idempotencyKey };
    try {
      const result = await createReservation(requestBody, actorId);
      if ("status" in result.body && result.body.status === "PROCESSING") {
        setProcessing(result.body);
        lastCreateIntent.current = requestBody;
        setHasCreateIntent(true);
        if (result.body.reservationId) {
          await refreshReservation(result.body.reservationId);
        }
      } else {
        setProcessing(null);
        setClockMs(Date.now());
        setReservation(result.body);
        lastCreateIntent.current = requestBody;
        setHasCreateIntent(true);
        intentKey.current = null;
      }
      await refreshInventory();
    } catch (error) {
      handlePollingError(error);
    } finally {
      setBusy(false);
    }
  };

  const replayLastReservation = async () => {
    if (!actorId || !lastCreateIntent.current) {
      return;
    }
    setPollingHalted(false);
    setBusy(true);
    setNotice(null);
    setScenarioMessage(null);
    try {
      const result = await createReservation(lastCreateIntent.current, actorId);
      if (result.body.status === "PROCESSING") {
        setProcessing(result.body);
        if (result.body.reservationId) {
          await refreshReservation(result.body.reservationId);
        }
      } else {
        setProcessing(null);
        setClockMs(Date.now());
        setReservation(result.body);
      }
      await refreshInventory();
    } catch (error) {
      handlePollingError(error);
    } finally {
      setBusy(false);
    }
  };

  const runTerminalAction = async (action: "confirm" | "release") => {
    const reservationId = reservation?.reservationId;
    if (!reservationId) {
      return;
    }
    setPollingHalted(false);
    setBusy(true);
    setNotice(null);
    setScenarioMessage(null);
    try {
      const result = action === "confirm"
        ? await confirmReservation(reservationId)
        : await releaseReservation(reservationId);
      if (result.body.status === "PROCESSING") {
        setProcessing(result.body);
      } else {
        setProcessing(null);
        setClockMs(Date.now());
        setReservation(result.body);
      }
      await refreshInventory();
    } catch (error) {
      handlePollingError(error);
      try {
        await refreshReservation(reservationId);
      } catch (refreshError) {
        setPollingHalted(true);
        setProcessing(null);
        handlePollingError(refreshError);
        setNotice("Reservation state could not be refreshed after the terminal action.");
      }
    } finally {
      setBusy(false);
    }
  };

  const inspectExpiry = async () => {
    const reservationId = reservation?.reservationId;
    if (!reservationId) {
      setScenarioMessage("Reserve a ticket before checking its expiry state.");
      return;
    }

    setBusy(true);
    setNotice(null);
    try {
      const result = await getReservation(reservationId);
      if (result.body.status === "PROCESSING") {
        setProcessing(result.body);
        setScenarioMessage("Expiry check is processing; the timeline will update from the backend.");
      } else {
        setProcessing(null);
        setClockMs(Date.now());
        setReservation(result.body);
        setScenarioMessage(
          result.body.status === "EXPIRED"
            ? "The backend marked this reservation EXPIRED."
            : "Expiry remains backend-controlled; keep the journey open until the TTL elapses.",
        );
        await refreshInventory();
      }
    } catch (error) {
      handlePollingError(error);
    } finally {
      setBusy(false);
    }
  };

  const runSoldOutScenario = async () => {
    if (!actorId) {
      return;
    }

    setBusy(true);
    setNotice(null);
    setScenarioMessage(null);
    const requestBody: ReservationCreateRequest = {
      ticketItemId,
      quantity: 4,
      idempotencyKey: createReservationIdempotencyKey(),
    };

    try {
      const result = await createReservation(requestBody, actorId);
      if (result.body.status === "PROCESSING") {
        const cleanupStatus = await cleanupScenarioReservation(result.body.reservationId);
        const summary = cleanupSummary([cleanupStatus]);
        if (summary.pending + summary.failed > 0) {
          setNotice("Sold-out probe cleanup has not converged; inspect the reservation state before retrying.");
        }
        setScenarioMessage(
          `Sold-out probe is processing; cleanup: ${summary.released} released, ${summary.terminal} terminal, ${summary.pending} pending, ${summary.failed} failed.`,
        );
      } else if (result.body.status === null) {
        setScenarioMessage(`Sold-out probe returned ${result.body.resultCode ?? result.body.outcome}.`);
      } else if (result.body.status === "RESERVED") {
        const cleanupStatus = await cleanupScenarioReservation(result.body.reservationId);
        setScenarioMessage(
          `Sold-out probe returned ${result.body.resultCode ?? result.body.outcome ?? result.body.status}; live inventory was not exhausted and cleanup status: ${cleanupStatus}.`,
        );
      } else {
        setScenarioMessage(
          `Sold-out probe returned ${result.body.resultCode ?? result.body.outcome ?? result.body.status}.`,
        );
      }
      await refreshInventory();
    } catch (error) {
      handlePollingError(error);
      if (error instanceof ReservationClientError) {
        setScenarioMessage(`Sold-out probe returned ${error.details?.code ?? "REQUEST_FAILED"}.`);
      }
    } finally {
      setBusy(false);
    }
  };

  const runOverloadScenario = async () => {
    if (!actorId) {
      return;
    }

    setBusy(true);
    setNotice(null);
    setScenarioMessage(null);

    try {
      const attempts = await Promise.allSettled(
        Array.from({ length: OVERLOAD_SCENARIO_ATTEMPTS }, () => createReservation({
          ticketItemId,
          quantity: 1,
          idempotencyKey: createReservationIdempotencyKey(),
        }, actorId)),
      );
      const acceptedReservations = attempts.flatMap((attempt) => {
        if (attempt.status !== "fulfilled" || attempt.value.body.status !== "RESERVED") {
          return [];
        }
        return [attempt.value.body];
      });
      const processingCount = attempts.filter(
        (attempt) => attempt.status === "fulfilled" && attempt.value.body.status === "PROCESSING",
      ).length;
      const rejectedAttempts = attempts.filter(
        (attempt): attempt is PromiseRejectedResult => attempt.status === "rejected",
      );
      const admissionRejectedCount = rejectedAttempts.filter(
        (attempt) => attempt.reason instanceof ReservationClientError
          && attempt.reason.status === 429,
      ).length;
      const dependencyRejectedCount = rejectedAttempts.filter(
        (attempt) => attempt.reason instanceof ReservationClientError && attempt.reason.status === 503,
      ).length;
      const otherRejectedCount = rejectedAttempts.length - admissionRejectedCount - dependencyRejectedCount;
      const processingReservationIds = attempts.flatMap((attempt) => {
        if (attempt.status !== "fulfilled" || attempt.value.body.status !== "PROCESSING") {
          return [];
        }
        return [attempt.value.body.reservationId];
      });
      const cleanupStatuses = await Promise.all([
        ...acceptedReservations.map((accepted) => cleanupScenarioReservation(accepted.reservationId)),
        ...processingReservationIds.map((reservationId) => cleanupScenarioReservation(reservationId)),
      ]);
      const cleanup = cleanupSummary(cleanupStatuses);
      await refreshInventory();
      if (cleanup.pending + cleanup.failed > 0) {
        setNotice("Overload probe cleanup has not converged; inspect the reservation state before retrying.");
      }
      setScenarioMessage(
        `Overload probe sent ${OVERLOAD_SCENARIO_ATTEMPTS} unique requests: ${acceptedReservations.length} accepted, ${processingCount} processing, ${admissionRejectedCount} admission-rejected, ${dependencyRejectedCount} dependency-rejected, ${otherRejectedCount} other-rejected. Cleanup: ${cleanup.released} released, ${cleanup.terminal} terminal, ${cleanup.pending} pending, ${cleanup.failed} failed.`,
      );
    } catch (error) {
      handlePollingError(error);
    } finally {
      setBusy(false);
    }
  };

  const status = reservation?.status;
  const displayStatus = processing ? "PROCESSING" : status ?? "No reservation";
  const remainingSeconds = reservation?.expiresAt && reservation.status === "RESERVED"
    ? Math.max(0, Math.ceil((Date.parse(reservation.expiresAt) - clockMs) / 1000))
    : null;
  const primaryAction = status === "RESERVED" ? () => runTerminalAction("confirm") : submitReservation;
  const primaryDisabled = busy || !actorId || processing !== null;

  return (
    <section className="space-y-6" data-testid="reservation-journey">
      <div className="flex flex-wrap items-end justify-between gap-4">
        <div>
          <p className="text-xs font-semibold uppercase tracking-[0.12em] text-[#898989]">Customer journey</p>
          <h1 className="mt-2 font-display text-4xl font-semibold leading-tight text-[#242424] sm:text-5xl">
            Reserve with a live system view
          </h1>
          <p className="mt-3 max-w-2xl text-base leading-7 text-[#898989]">
            {event?.description ?? "Make one reservation and inspect the durable state returned by the API."}
          </p>
          <p
            className="mt-2 text-xs font-medium uppercase tracking-[0.08em] text-[#898989]"
            data-testid="reservation-demo-disclaimer"
          >
            Demo session only; no login or real authentication is used.
          </p>
        </div>
        <Badge variant={status === "CONFIRMED" ? "default" : "outline"}>
          {displayStatus}
        </Badge>
      </div>

      <div className="grid gap-6 lg:grid-cols-[1.1fr_0.9fr] lg:items-start">
        <Card>
          <CardHeader>
            <CardTitle>{event?.title ?? `Ticket ${ticketItemId}`}</CardTitle>
            <CardDescription>
              {event?.date ?? "Live fixture"} · {event?.venue ?? "Backend inventory"} · {formatCurrency(event?.priceFlash ?? 0)}
            </CardDescription>
          </CardHeader>
          <CardContent className="space-y-5">
            <ReservationStockBuckets inventory={inventory} />
            {reservation?.expiresAt ? (
              <p className="text-sm text-[#898989]">Reservation expires at {formatDateTime(reservation.expiresAt)}</p>
            ) : null}
            {remainingSeconds !== null ? (
              <p className="text-sm font-semibold text-[#242424]" data-testid="reservation-countdown">
                Reservation countdown: {remainingSeconds}s
              </p>
            ) : null}
            {inventoryError ? <Alert data-testid="reservation-inventory-error">{inventoryError}</Alert> : null}
            {notice ? <Alert data-testid="reservation-error">{notice}</Alert> : null}
            <div className="grid gap-3 sm:grid-cols-[120px_1fr] sm:items-end">
              <div className="space-y-2">
                <Label htmlFor="reservation-quantity">Quantity</Label>
                <Input
                  id="reservation-quantity"
                  data-testid="reservation-quantity"
                  type="number"
                  min={1}
                  max={4}
                  value={quantity}
                  onChange={(input) => setQuantity(Math.max(1, Math.min(4, Number(input.target.value))))}
                />
              </div>
              {status === "CONFIRMED" ? (
                <Button asChild size="lg" data-testid="reservation-primary-action">
                  <Link href="#reservation-timeline">View confirmed order</Link>
                </Button>
              ) : (
                <Button
                  type="button"
                  size="lg"
                  onClick={primaryAction}
                  disabled={primaryDisabled}
                  data-testid="reservation-primary-action"
                >
                  {busy
                    ? "Working…"
                    : status === "RESERVED"
                      ? "Confirm purchase"
                      : status === "RELEASED" || status === "EXPIRED"
                        ? "Try again"
                        : "Reserve ticket"}
                </Button>
              )}
            </div>
            {processing ? (
              <p className="text-sm font-semibold text-[#898989]" data-testid="reservation-processing">
                Processing; retrying in {processing.retryAfterSeconds}s.
              </p>
            ) : null}
          </CardContent>
        </Card>

        <Card>
          <CardHeader>
            <CardTitle>System X-Ray</CardTitle>
            <CardDescription>Only states observed from the reservation API are shown.</CardDescription>
          </CardHeader>
          <CardContent className="space-y-5">
            <ReservationTimeline reservation={reservation} processing={processing} />
            <DemoScenarioDrawer>
              <p className="text-xs leading-5 text-[#898989]">
                Use these controls to exercise replay, expiry, sold-out and admission paths against the live API.
              </p>
              {scenarioMessage ? (
                <p className="rounded-lg bg-[#f7f7f7] p-3 text-xs leading-5 text-[#242424]" data-testid="reservation-scenario-status">
                  {scenarioMessage}
                </p>
              ) : null}
              <div className="flex flex-wrap gap-2">
                <Button
                  type="button"
                  variant="secondary"
                  onClick={() => runTerminalAction("release")}
                  disabled={busy || processing !== null || status !== "RESERVED"}
                  data-testid="reservation-release"
                >
                  Release
                </Button>
                <Button
                  type="button"
                  variant="secondary"
                  onClick={() => void replayLastReservation()}
                  disabled={busy || !hasCreateIntent}
                  data-testid="reservation-replay"
                >
                  Replay same intent
                </Button>
                <Button
                  type="button"
                  variant="secondary"
                  onClick={() => void inspectExpiry()}
                  disabled={busy || processing !== null || !reservation}
                  data-testid="reservation-expiry"
                >
                  Check expiry
                </Button>
                <Button
                  type="button"
                  variant="secondary"
                  onClick={() => void runSoldOutScenario()}
                  disabled={busy || !actorId}
                  data-testid="reservation-soldout"
                >
                  Try sold-out
                </Button>
                <Button
                  type="button"
                  variant="secondary"
                  onClick={() => void runOverloadScenario()}
                  disabled={busy || !actorId}
                  data-testid="reservation-overload"
                >
                  Try overload
                </Button>
                <Button
                  type="button"
                  variant="secondary"
                  onClick={() => void refreshInventory()}
                  disabled={busy}
                >
                  Refresh inventory
                </Button>
              </div>
              {chaosProfileEnabled ? (
                <details
                  className="rounded-lg bg-[#f7f7f7] p-3"
                  data-testid="reservation-chaos-drawer"
                >
                  <summary className="cursor-pointer text-xs font-semibold text-[#242424]">
                    Chaos profile scenarios
                  </summary>
                  <div className="mt-3 space-y-3 text-xs leading-5 text-[#898989]">
                    <p>
                      Controlled backend faults: AFTER_REDIS_BEFORE_DB, AFTER_DB_COMMIT_BEFORE_RESPONSE,
                      REDIS_MIRROR_TIMEOUT, KAFKA_UNAVAILABLE and CONFIRM_EXPIRE_RACE.
                    </p>
                    <Button asChild type="button" variant="secondary" size="sm">
                      <Link href="/admin/control-desk">Open control desk</Link>
                    </Button>
                  </div>
                </details>
              ) : null}
            </DemoScenarioDrawer>
          </CardContent>
        </Card>
      </div>

      {/* Interactive System X-Ray Architecture Pipeline */}
      <SystemXRayPipeline
        activeStageId={
          busy
            ? "redis-lua"
            : status === "CONFIRMED"
              ? "kafka-stream"
              : status === "RESERVED"
                ? "mysql-commit"
                : processing
                  ? "journal-claim"
                  : "rate-limiter"
        }
        isExecuting={busy}
        currentOperation={
          status === "RESERVED"
            ? `POST /api/v1/reservations/${reservation?.reservationId?.slice(0, 8) ?? "..."}/confirm`
            : `POST /api/v1/reservations`
        }
      />
    </section>
  );
}

"use client";

import {
  Activity,
  CheckCircle2,
  Database,
  Loader2,
  PackageCheck,
  RefreshCw,
  Zap,
} from "lucide-react";
import { useCallback, useEffect, useState } from "react";
import { toast } from "sonner";

import { getConsistency, getTicket, warmupStock } from "@/lib/api";
import { DEFAULT_TICKET_ID, DEFAULT_YEAR_MONTH } from "@/lib/events";
import type { ConsistencySnapshot, TicketDetail } from "@/lib/types";
import { formatCurrency, formatDateTime, formatNumber } from "@/lib/utils";

import { Field, InfoBlock, MetricCard } from "@/components/dashboard-primitives";
import { Alert } from "@/components/ui/alert";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from "@/components/ui/card";
import { Input } from "@/components/ui/input";

/**
 * Focused Redis-vs-MySQL view for explaining post-run stock drift.
 */
export function ConsistencyDashboard() {
  const [ticketItemId, setTicketItemId] = useState(DEFAULT_TICKET_ID);
  const [yearMonth, setYearMonth] = useState(DEFAULT_YEAR_MONTH);
  const [ticket, setTicket] = useState<TicketDetail | null>(null);
  const [snapshot, setSnapshot] = useState<ConsistencySnapshot | null>(null);
  const [loadingKey, setLoadingKey] = useState<string | null>(null);
  const [errorMessage, setErrorMessage] = useState<string | null>(null);

  const refreshSnapshot = useCallback(async () => {
    setLoadingKey("refresh");
    setErrorMessage(null);

    try {
      const [ticketResult, consistencyResult] = await Promise.all([
        getTicket(ticketItemId),
        getConsistency(ticketItemId, yearMonth),
      ]);
      setTicket(ticketResult.result);
      setSnapshot(consistencyResult.result);
    } catch (error) {
      const message = error instanceof Error ? error.message : "Unable to refresh consistency";
      setErrorMessage(message);
      toast.error(message);
    } finally {
      setLoadingKey(null);
    }
  }, [ticketItemId, yearMonth]);

  useEffect(() => {
    // Initial consistency hydration follows the selected ticket and month.
    // eslint-disable-next-line react-hooks/set-state-in-effect
    void refreshSnapshot();
  }, [refreshSnapshot]);

  const onSubmit = (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    void refreshSnapshot();
  };

  const onWarmup = async () => {
    setLoadingKey("warmup");
    setErrorMessage(null);

    try {
      const envelope = await warmupStock(ticketItemId);
      toast.success(envelope.result.message || "Redis stock warmed from DB");
      await refreshSnapshot();
    } catch (error) {
      const message = error instanceof Error ? error.message : "Unable to warm Redis stock";
      setErrorMessage(message);
      toast.error(message);
    } finally {
      setLoadingKey(null);
    }
  };

  const isClean = snapshot
    ? snapshot.oversoldCount === 0 && snapshot.redisDbInconsistencyCount === 0
    : false;

  return (
    <main className="mx-auto flex w-full max-w-[1200px] flex-col gap-8 px-4 py-8 sm:px-6 lg:px-8">
      <header className="flex flex-col gap-5 border-b border-black/[0.06] pb-6 lg:flex-row lg:items-end lg:justify-between">
        <div className="max-w-3xl">
          <div className="mb-3 flex flex-wrap items-center gap-2">
            <Badge variant={isClean ? "success" : "warning"}>
              {isClean ? "Clean snapshot" : "Review snapshot"}
            </Badge>
            <Badge variant="outline">Redis vs DB</Badge>
          </div>
          <h1 className="font-display text-4xl font-semibold leading-[1.1] text-[#242424] sm:text-5xl">
            Redis-DB consistency
          </h1>
          <p className="mt-3 max-w-2xl text-base leading-7 text-[#898989]">
            Inspect Redis stock, DB stock, order count, oversold rows, and drift for a
            ticket/month pair.
          </p>
        </div>
        <Button
          type="button"
          variant="secondary"
          onClick={refreshSnapshot}
          disabled={loadingKey === "refresh"}
        >
          {loadingKey === "refresh" ? (
            <Loader2 className="h-4 w-4 animate-spin" />
          ) : (
            <RefreshCw className="h-4 w-4" />
          )}
          Refresh
        </Button>
      </header>

      {errorMessage ? <Alert>{errorMessage}</Alert> : null}

      <section className="grid gap-4 md:grid-cols-2 xl:grid-cols-4">
        <MetricCard
          icon={Zap}
          label="Redis stock"
          value={snapshot ? formatNumber(snapshot.redisStockAfter) : "n/a"}
          detail="Fast pre-deduction store"
        />
        <MetricCard
          icon={Database}
          label="DB stock"
          value={snapshot ? formatNumber(snapshot.dbStockAfter) : "n/a"}
          detail={`Orders ${snapshot ? formatNumber(snapshot.dbOrderCount) : "n/a"}`}
        />
        <MetricCard
          icon={CheckCircle2}
          label="Oversold"
          value={snapshot ? formatNumber(snapshot.oversoldCount) : "n/a"}
          detail={snapshot?.oversoldCount === 0 ? "No oversold rows" : "Investigate orders"}
        />
        <MetricCard
          icon={Activity}
          label="Drift"
          value={snapshot ? formatNumber(snapshot.redisDbInconsistencyCount) : "n/a"}
          detail={snapshot?.redisDbInconsistencyCount === 0 ? "Redis matches DB" : "Mismatch found"}
        />
      </section>

      <section className="grid gap-5 lg:grid-cols-[0.95fr_1.05fr]">
        <Card>
          <CardHeader>
            <CardTitle>Snapshot controls</CardTitle>
            <CardDescription>Choose the ticket/month pair and refresh the comparison.</CardDescription>
          </CardHeader>
          <CardContent>
            <form className="grid gap-4 sm:grid-cols-2" onSubmit={onSubmit}>
              <Field label="Ticket ID">
                <Input
                  type="number"
                  min={1}
                  value={ticketItemId}
                  onChange={(event) => setTicketItemId(Number(event.target.value))}
                />
              </Field>
              <Field label="Year month">
                <Input
                  inputMode="numeric"
                  value={yearMonth}
                  onChange={(event) => setYearMonth(event.target.value)}
                />
              </Field>
              <div className="flex flex-wrap gap-2 sm:col-span-2">
                <Button type="submit" disabled={loadingKey === "refresh"}>
                  <RefreshCw className="h-4 w-4" />
                  Refresh snapshot
                </Button>
                <Button
                  type="button"
                  variant="secondary"
                  onClick={onWarmup}
                  disabled={loadingKey === "warmup"}
                >
                  {loadingKey === "warmup" ? (
                    <Loader2 className="h-4 w-4 animate-spin" />
                  ) : (
                    <PackageCheck className="h-4 w-4" />
                  )}
                  Warm Redis
                </Button>
              </div>
            </form>
          </CardContent>
        </Card>

        <Card>
          <CardHeader>
            <CardTitle>Ticket facts</CardTitle>
            <CardDescription>Backend ticket state for the current consistency snapshot.</CardDescription>
          </CardHeader>
          <CardContent>
            <div className="grid gap-4 sm:grid-cols-2">
              <InfoBlock label="Ticket" value={ticket?.name ?? "Loading"} />
              <InfoBlock label="Available" value={formatNumber(ticket?.stockAvailable)} />
              <InfoBlock label="Flash price" value={formatCurrency(ticket?.priceFlash)} />
              <InfoBlock label="Sale ends" value={formatDateTime(ticket?.saleEndTime)} />
              <InfoBlock label="Redis stock" value={formatNumber(snapshot?.redisStockAfter)} />
              <InfoBlock label="DB stock" value={formatNumber(snapshot?.dbStockAfter)} />
            </div>
          </CardContent>
        </Card>
      </section>
    </main>
  );
}

"use client";

import {
  Activity,
  CheckCircle2,
  Database,
  Loader2,
  PackageCheck,
  RefreshCw,
  Server,
  Ticket,
  Zap,
} from "lucide-react";
import { useCallback, useEffect, useState } from "react";
import { toast } from "sonner";

import {
  getConsistency,
  getHealth,
  getTicket,
  resetBenchmark,
  warmupStock,
} from "@/lib/api";
import { DEFAULT_TICKET_ID, DEFAULT_YEAR_MONTH } from "@/lib/events";
import type {
  BenchmarkResetResponse,
  ConsistencySnapshot,
  HealthResponse,
  TicketDetail,
} from "@/lib/types";
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

const DEFAULT_STOCK = 1000;

/**
 * Operator control surface for deterministic benchmark setup.
 *
 * Reset, warmup, and consistency actions are grouped here because benchmark evidence is only useful
 * when Redis, MySQL, and order rows start from a known state.
 */
export function AdminControlDesk() {
  const [health, setHealth] = useState<HealthResponse | null>(null);
  const [ticket, setTicket] = useState<TicketDetail | null>(null);
  const [consistency, setConsistency] = useState<ConsistencySnapshot | null>(null);
  const [lastReset, setLastReset] = useState<BenchmarkResetResponse | null>(null);
  const [ticketItemId, setTicketItemId] = useState(DEFAULT_TICKET_ID);
  const [stock, setStock] = useState(DEFAULT_STOCK);
  const [yearMonth, setYearMonth] = useState(DEFAULT_YEAR_MONTH);
  const [loadingKey, setLoadingKey] = useState<string | null>(null);
  const [errorMessage, setErrorMessage] = useState<string | null>(null);

  const refreshControlDesk = useCallback(async () => {
    setLoadingKey("overview");
    setErrorMessage(null);

    try {
      const [healthResult, ticketResult, consistencyResult] = await Promise.all([
        getHealth(),
        getTicket(ticketItemId),
        getConsistency(ticketItemId, yearMonth),
      ]);
      setHealth(healthResult);
      setTicket(ticketResult.result);
      setConsistency(consistencyResult.result);
    } catch (error) {
      const message = error instanceof Error ? error.message : "Unable to refresh control desk";
      setErrorMessage(message);
      toast.error(message);
    } finally {
      setLoadingKey(null);
    }
  }, [ticketItemId, yearMonth]);

  useEffect(() => {
    // Initial admin hydration reads the selected lab ticket and month.
    // eslint-disable-next-line react-hooks/set-state-in-effect
    void refreshControlDesk();
  }, [refreshControlDesk]);

  const onReset = async (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    setLoadingKey("reset");
    setErrorMessage(null);

    try {
      // Reset is intentionally followed by a full refresh so all cards reflect the same run state.
      const envelope = await resetBenchmark({ ticketItemId, stock, yearMonth });
      setLastReset(envelope.result);
      await refreshControlDesk();
      toast.success("Benchmark state reset");
    } catch (error) {
      const message = error instanceof Error ? error.message : "Unable to reset benchmark data";
      setErrorMessage(message);
      toast.error(message);
    } finally {
      setLoadingKey(null);
    }
  };

  const onWarmup = async () => {
    setLoadingKey("warmup");
    setErrorMessage(null);

    try {
      const envelope = await warmupStock(ticketItemId);
      await refreshControlDesk();
      toast.success(envelope.result.message || "Redis stock warmed from DB");
    } catch (error) {
      const message = error instanceof Error ? error.message : "Unable to warm Redis stock";
      setErrorMessage(message);
      toast.error(message);
    } finally {
      setLoadingKey(null);
    }
  };

  const onConsistencyCheck = async () => {
    setLoadingKey("consistency");
    setErrorMessage(null);

    try {
      const envelope = await getConsistency(ticketItemId, yearMonth);
      setConsistency(envelope.result);
      toast.success("Consistency snapshot refreshed");
    } catch (error) {
      const message = error instanceof Error ? error.message : "Unable to refresh consistency";
      setErrorMessage(message);
      toast.error(message);
    } finally {
      setLoadingKey(null);
    }
  };

  return (
    <main className="mx-auto flex w-full max-w-[1200px] flex-col gap-8 px-4 py-8 sm:px-6 lg:px-8">
      <header className="flex flex-col gap-5 border-b border-black/[0.06] pb-6 lg:flex-row lg:items-end lg:justify-between">
        <div className="max-w-3xl">
          <div className="mb-3 flex flex-wrap items-center gap-2">
            <Badge variant={health?.status === "UP" ? "success" : "warning"} data-testid="health-status">
              <Server className="mr-1 h-3 w-3" />
              Backend {health?.status ?? "checking"}
            </Badge>
            <Badge variant="outline">Lab operations</Badge>
          </div>
          <h1 className="font-display text-4xl font-semibold leading-[1.1] text-[#242424] sm:text-5xl">
            Flash-sale control desk
          </h1>
          <p className="mt-3 max-w-2xl text-base leading-7 text-[#898989]">
            Reset lab inventory, warm Redis stock, and run a quick consistency check.
          </p>
        </div>
        <Button
          type="button"
          variant="secondary"
          data-testid="health-refresh-btn"
          onClick={refreshControlDesk}
          disabled={loadingKey === "overview"}
        >
          {loadingKey === "overview" ? (
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
          icon={Ticket}
          label="Ticket stock"
          value={ticket ? formatNumber(ticket.stockAvailable) : "n/a"}
          detail={`Initial ${ticket ? formatNumber(ticket.stockInitial) : "n/a"}`}
        />
        <MetricCard
          icon={Database}
          label="DB stock"
          value={consistency ? formatNumber(consistency.dbStockAfter) : "n/a"}
          detail={`Orders ${consistency ? formatNumber(consistency.dbOrderCount) : "n/a"}`}
        />
        <MetricCard
          icon={Zap}
          label="Redis stock"
          value={consistency ? formatNumber(consistency.redisStockAfter) : "n/a"}
          detail={`Drift ${consistency ? formatNumber(consistency.redisDbInconsistencyCount) : "n/a"}`}
        />
        <MetricCard
          icon={CheckCircle2}
          label="Oversold"
          value={consistency ? formatNumber(consistency.oversoldCount) : "n/a"}
          detail={consistency?.oversoldCount === 0 ? "Safe snapshot" : "Needs attention"}
        />
      </section>

      <section className="grid gap-5 lg:grid-cols-[1.05fr_0.95fr]">
        <Card>
          <CardHeader>
            <CardTitle>Ticket status</CardTitle>
            <CardDescription>Live ticket item data from the Spring Boot API.</CardDescription>
          </CardHeader>
          <CardContent>
            <div className="grid gap-4 sm:grid-cols-2">
              <InfoBlock label="Ticket" value={ticket?.name ?? "Loading"} />
              <InfoBlock label="Ticket item ID" value={ticket?.id ?? ticketItemId} />
              <InfoBlock label="Flash price" value={formatCurrency(ticket?.priceFlash)} />
              <InfoBlock label="Original price" value={formatCurrency(ticket?.priceOriginal)} />
              <InfoBlock label="Sale starts" value={formatDateTime(ticket?.saleStartTime)} />
              <InfoBlock label="Sale ends" value={formatDateTime(ticket?.saleEndTime)} />
            </div>
          </CardContent>
        </Card>

        <Card>
          <CardHeader>
            <CardTitle>Operations</CardTitle>
            <CardDescription>Reset the benchmark fixture and sync Redis stock.</CardDescription>
          </CardHeader>
          <CardContent>
            <form className="grid gap-4 sm:grid-cols-3" onSubmit={onReset}>
              <Field label="Ticket ID">
                <Input
                  type="number"
                  min={1}
                  value={ticketItemId}
                  data-testid="control-ticket-item-id"
                  onChange={(event) => setTicketItemId(Number(event.target.value))}
                />
              </Field>
              <Field label="Stock">
                <Input
                  type="number"
                  min={0}
                  value={stock}
                  data-testid="control-stock"
                  onChange={(event) => setStock(Number(event.target.value))}
                />
              </Field>
              <Field label="Year month">
                <Input
                  inputMode="numeric"
                  value={yearMonth}
                  data-testid="control-year-month"
                  onChange={(event) => setYearMonth(event.target.value)}
                />
              </Field>
              <div className="flex flex-wrap gap-2 sm:col-span-3">
                <Button type="submit" data-testid="control-reset-btn" disabled={loadingKey === "reset"}>
                  {loadingKey === "reset" ? (
                    <Loader2 className="h-4 w-4 animate-spin" />
                  ) : (
                    <RefreshCw className="h-4 w-4" />
                  )}
                  Reset
                </Button>
                <Button
                  type="button"
                  variant="secondary"
                  data-testid="control-warmup-btn"
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
                <Button
                  type="button"
                  variant="secondary"
                  data-testid="consistency-check-btn"
                  onClick={onConsistencyCheck}
                  disabled={loadingKey === "consistency"}
                >
                  <Activity className="h-4 w-4" />
                  Check consistency
                </Button>
              </div>
            </form>
            {lastReset ? (
              <Alert className="mt-4" data-testid="consistency-result">
                Reset complete for ticket {lastReset.ticketItemId}. Redis stock{" "}
                {lastReset.redisStockAfter}, DB stock {lastReset.dbStockAfter}.
              </Alert>
            ) : null}
          </CardContent>
        </Card>
      </section>
    </main>
  );
}

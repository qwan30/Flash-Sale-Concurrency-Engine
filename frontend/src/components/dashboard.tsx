"use client";

import { zodResolver } from "@hookform/resolvers/zod";
import type { ColumnDef } from "@tanstack/react-table";
import {
  flexRender,
  getCoreRowModel,
  useReactTable,
} from "@tanstack/react-table";
import {
  Activity,
  CheckCircle2,
  Database,
  Loader2,
  PackageCheck,
  RefreshCw,
  Search,
  Server,
  ShoppingCart,
  Ticket,
  Zap,
} from "lucide-react";
import { motion } from "motion/react";
import dynamic from "next/dynamic";
import { useCallback, useEffect, useMemo, useState } from "react";
import { useForm, useWatch } from "react-hook-form";
import { toast, Toaster } from "sonner";
import { z } from "zod";

import {
  createOrder,
  getConsistency,
  getHealth,
  getOrder,
  getTicket,
  listOrders,
  resetBenchmark,
  warmupStock,
} from "@/lib/api";
import { benchmarkRows } from "@/lib/benchmark-data";
import {
  ORDER_STRATEGIES,
  type BenchmarkResetResponse,
  type ConsistencySnapshot,
  type CreateOrderResponse,
  type HealthResponse,
  type OrderStrategy,
  type TicketDetail,
  type TicketOrder,
} from "@/lib/types";
import { cn, formatCurrency, formatDateTime, formatNumber } from "@/lib/utils";

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
import { Label } from "@/components/ui/label";
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from "@/components/ui/table";

const DEFAULT_TICKET_ID = 4;
const DEFAULT_STOCK = 1000;
const DEFAULT_YEAR_MONTH = "202604";
const DEFAULT_USER_ID = 42;
let idempotencySequence = 0;

const BenchmarkChart = dynamic(
  () => import("@/components/benchmark-chart").then((module) => module.BenchmarkChart),
  {
    ssr: false,
    loading: () => <div className="h-full rounded-lg bg-[#f7f7f7]" />,
  },
);

function nextIdempotencyKey(userId: number) {
  idempotencySequence += 1;
  return `ui-${userId}-${idempotencySequence}`;
}

const strategyDetails: Record<
  OrderStrategy,
  { label: string; summary: string; warning?: boolean }
> = {
  UNSAFE_DB: {
    label: "Unsafe DB",
    summary: "Demo-only baseline that may oversell under load.",
    warning: true,
  },
  CONDITIONAL_DB: {
    label: "DB guarded",
    summary: "Uses a conditional stock update as the safe baseline.",
  },
  REDIS_LUA: {
    label: "Redis Lua",
    summary: "Uses Redis as a fast pre-deduction gate.",
  },
  REDIS_LUA_WITH_COMPENSATION: {
    label: "Redis compensation",
    summary: "Restores Redis stock when DB/order write fails.",
  },
};

const yearMonthSchema = z.string().regex(/^\d{6}$/, "Use YYYYMM, for example 202604");

const orderSchema = z.object({
  ticketItemId: z.coerce.number().int().positive(),
  userId: z.coerce.number().int().positive(),
  quantity: z.coerce.number().int().positive().max(10),
  strategy: z.enum(ORDER_STRATEGIES),
});

const resetSchema = z.object({
  ticketItemId: z.coerce.number().int().positive(),
  stock: z.coerce.number().int().min(0),
  yearMonth: yearMonthSchema,
});

const orderLookupSchema = z.object({
  orderNumber: z.string().trim().min(1, "Order number is required"),
});

const orderListSchema = z.object({
  userId: z.coerce.number().int().positive(),
  yearMonth: yearMonthSchema,
});

type OrderFormInput = z.input<typeof orderSchema>;
type OrderFormValues = z.output<typeof orderSchema>;
type ResetFormInput = z.input<typeof resetSchema>;
type ResetFormValues = z.output<typeof resetSchema>;
type OrderLookupValues = z.output<typeof orderLookupSchema>;
type OrderListInput = z.input<typeof orderListSchema>;
type OrderListValues = z.output<typeof orderListSchema>;

export function Dashboard() {
  const [health, setHealth] = useState<HealthResponse | null>(null);
  const [ticket, setTicket] = useState<TicketDetail | null>(null);
  const [consistency, setConsistency] = useState<ConsistencySnapshot | null>(null);
  const [lastOrder, setLastOrder] = useState<CreateOrderResponse | null>(null);
  const [lastReset, setLastReset] = useState<BenchmarkResetResponse | null>(null);
  const [foundOrder, setFoundOrder] = useState<TicketOrder | null>(null);
  const [orders, setOrders] = useState<TicketOrder[]>([]);
  const [activeTicketId, setActiveTicketId] = useState(DEFAULT_TICKET_ID);
  const [activeYearMonth, setActiveYearMonth] = useState(DEFAULT_YEAR_MONTH);
  const [loadingKey, setLoadingKey] = useState<string | null>(null);
  const [errorMessage, setErrorMessage] = useState<string | null>(null);

  const orderForm = useForm<OrderFormInput, unknown, OrderFormValues>({
    resolver: zodResolver(orderSchema),
    defaultValues: {
      ticketItemId: DEFAULT_TICKET_ID,
      userId: DEFAULT_USER_ID,
      quantity: 1,
      strategy: "REDIS_LUA_WITH_COMPENSATION",
    },
  });

  const resetForm = useForm<ResetFormInput, unknown, ResetFormValues>({
    resolver: zodResolver(resetSchema),
    defaultValues: {
      ticketItemId: DEFAULT_TICKET_ID,
      stock: DEFAULT_STOCK,
      yearMonth: DEFAULT_YEAR_MONTH,
    },
  });

  const orderLookupForm = useForm<OrderLookupValues>({
    resolver: zodResolver(orderLookupSchema),
    defaultValues: {
      orderNumber: "",
    },
  });

  const orderListForm = useForm<OrderListInput, unknown, OrderListValues>({
    resolver: zodResolver(orderListSchema),
    defaultValues: {
      userId: DEFAULT_USER_ID,
      yearMonth: DEFAULT_YEAR_MONTH,
    },
  });

  const selectedStrategy = useWatch({
    control: orderForm.control,
    name: "strategy",
  });

  const refreshTicket = useCallback(async (ticketItemId = activeTicketId) => {
    const envelope = await getTicket(ticketItemId);
    setTicket(envelope.result);
  }, [activeTicketId]);

  const refreshConsistency = useCallback(
    async (ticketItemId = activeTicketId, yearMonth = activeYearMonth) => {
      const envelope = await getConsistency(ticketItemId, yearMonth);
      setConsistency(envelope.result);
    },
    [activeTicketId, activeYearMonth],
  );

  const refreshDashboard = useCallback(async () => {
    setLoadingKey("dashboard");
    setErrorMessage(null);

    try {
      const [healthResult, ticketResult, consistencyResult] = await Promise.all([
        getHealth(),
        getTicket(activeTicketId),
        getConsistency(activeTicketId, activeYearMonth),
      ]);
      setHealth(healthResult);
      setTicket(ticketResult.result);
      setConsistency(consistencyResult.result);
    } catch (error) {
      const message = error instanceof Error ? error.message : "Unable to load dashboard";
      setErrorMessage(message);
      toast.error(message);
    } finally {
      setLoadingKey(null);
    }
  }, [activeTicketId, activeYearMonth]);

  useEffect(() => {
    // Initial dashboard hydration is an external data synchronization concern.
    // eslint-disable-next-line react-hooks/set-state-in-effect
    void refreshDashboard();
  }, [refreshDashboard]);

  const onCreateOrder = orderForm.handleSubmit(async (values) => {
    setLoadingKey("order");
    setErrorMessage(null);

    try {
      const idempotencyKey = nextIdempotencyKey(values.userId);
      const envelope = await createOrder({
        ...values,
        idempotencyKey,
      });
      setLastOrder(envelope.result);

      if (envelope.result.orderNumber) {
        orderLookupForm.setValue("orderNumber", envelope.result.orderNumber);
      }

      await Promise.all([
        refreshTicket(values.ticketItemId),
        refreshConsistency(values.ticketItemId, activeYearMonth),
      ]);

      if (envelope.result.success) {
        toast.success("Order created");
      } else {
        toast.message(envelope.result.message || "Order rejected");
      }
    } catch (error) {
      const message = error instanceof Error ? error.message : "Unable to create order";
      setErrorMessage(message);
      toast.error(message);
    } finally {
      setLoadingKey(null);
    }
  });

  const onReset = resetForm.handleSubmit(async (values) => {
    setLoadingKey("reset");
    setErrorMessage(null);

    try {
      setActiveTicketId(values.ticketItemId);
      setActiveYearMonth(values.yearMonth);
      const envelope = await resetBenchmark(values);
      setLastReset(envelope.result);
      setLastOrder(null);
      setFoundOrder(null);
      setOrders([]);
      await Promise.all([
        refreshTicket(values.ticketItemId),
        refreshConsistency(values.ticketItemId, values.yearMonth),
      ]);
      toast.success("Benchmark data reset");
    } catch (error) {
      const message = error instanceof Error ? error.message : "Unable to reset benchmark data";
      setErrorMessage(message);
      toast.error(message);
    } finally {
      setLoadingKey(null);
    }
  });

  const onWarmup = async () => {
    const values = resetForm.getValues();
    const parsed = resetSchema.safeParse(values);

    if (!parsed.success) {
      await resetForm.trigger();
      return;
    }

    setLoadingKey("warmup");
    setErrorMessage(null);

    try {
      const envelope = await warmupStock(parsed.data.ticketItemId);
      await Promise.all([
        refreshTicket(parsed.data.ticketItemId),
        refreshConsistency(parsed.data.ticketItemId, parsed.data.yearMonth),
      ]);
      toast.success(envelope.result.message || "Redis stock warmed from DB");
    } catch (error) {
      const message = error instanceof Error ? error.message : "Unable to warm Redis stock";
      setErrorMessage(message);
      toast.error(message);
    } finally {
      setLoadingKey(null);
    }
  };

  const onRefreshConsistency = async () => {
    const values = resetForm.getValues();
    const parsed = resetSchema.safeParse(values);

    if (!parsed.success) {
      await resetForm.trigger();
      return;
    }

    setLoadingKey("consistency");
    setErrorMessage(null);

    try {
      await refreshConsistency(parsed.data.ticketItemId, parsed.data.yearMonth);
      toast.success("Consistency snapshot refreshed");
    } catch (error) {
      const message = error instanceof Error ? error.message : "Unable to refresh consistency";
      setErrorMessage(message);
      toast.error(message);
    } finally {
      setLoadingKey(null);
    }
  };

  const onLookupOrder = orderLookupForm.handleSubmit(async (values) => {
    setLoadingKey("lookup");
    setErrorMessage(null);

    try {
      const envelope = await getOrder(values.orderNumber);
      setFoundOrder(envelope.result);
      toast.success("Order loaded");
    } catch (error) {
      const message = error instanceof Error ? error.message : "Unable to load order";
      setErrorMessage(message);
      toast.error(message);
    } finally {
      setLoadingKey(null);
    }
  });

  const onListOrders = orderListForm.handleSubmit(async (values) => {
    setLoadingKey("list");
    setErrorMessage(null);

    try {
      const envelope = await listOrders(values.userId, values.yearMonth);
      setOrders(envelope.result);
      toast.success(`Loaded ${envelope.result.length} order rows`);
    } catch (error) {
      const message = error instanceof Error ? error.message : "Unable to list orders";
      setErrorMessage(message);
      toast.error(message);
    } finally {
      setLoadingKey(null);
    }
  });

  const orderColumns = useMemo<ColumnDef<TicketOrder>[]>(
    () => [
      {
        accessorKey: "orderNumber",
        header: "Order number",
      },
      {
        accessorKey: "userId",
        header: "User",
      },
      {
        accessorKey: "totalAmount",
        header: "Amount",
        cell: ({ row }) => formatCurrency(row.original.totalAmount),
      },
      {
        accessorKey: "terminalId",
        header: "Terminal",
      },
      {
        accessorKey: "orderDate",
        header: "Order date",
        cell: ({ row }) => formatDateTime(row.original.orderDate),
      },
    ],
    [],
  );

  const benchmarkColumns = useMemo(
    () => [
      { key: "strategy", label: "Strategy" },
      { key: "throughput", label: "Req/s" },
      { key: "averageMs", label: "Avg ms" },
      { key: "p95Ms", label: "P95" },
      { key: "p99Ms", label: "P99" },
      { key: "oversoldCount", label: "Oversold" },
      { key: "redisDbInconsistencyCount", label: "Drift" },
    ],
    [],
  );

  return (
    <main className="min-h-screen bg-white text-[#242424]">
      <Toaster position="top-right" toastOptions={{ duration: 3500 }} />
      <div className="mx-auto flex w-full max-w-[1200px] flex-col gap-8 px-4 py-6 sm:px-6 lg:px-8">
        <header className="flex flex-col gap-5 border-b border-black/[0.06] pb-6 lg:flex-row lg:items-end lg:justify-between">
          <div className="max-w-3xl">
            <div className="mb-3 flex flex-wrap items-center gap-2">
              <Badge variant={health?.status === "UP" ? "success" : "warning"}>
                <Server className="mr-1 h-3 w-3" />
                Backend {health?.status ?? "checking"}
              </Badge>
              <Badge variant="outline">Next.js 16 dashboard</Badge>
            </div>
            <h1 className="font-display text-4xl font-semibold leading-[1.1] text-[#242424] sm:text-5xl">
              Flash-sale control desk
            </h1>
            <p className="mt-3 max-w-2xl text-base leading-7 text-[#898989]">
              Operate the ticket lab, compare stock deduction strategies, and verify
              Redis-DB consistency without leaving the browser.
            </p>
          </div>
          <Button
            type="button"
            variant="secondary"
            onClick={refreshDashboard}
            disabled={loadingKey === "dashboard"}
          >
            {loadingKey === "dashboard" ? (
              <Loader2 className="h-4 w-4 animate-spin" />
            ) : (
              <RefreshCw className="h-4 w-4" />
            )}
            Refresh
          </Button>
        </header>

        {errorMessage ? <Alert>{errorMessage}</Alert> : null}

        <motion.section
          initial={{ opacity: 0, y: 12 }}
          animate={{ opacity: 1, y: 0 }}
          transition={{ duration: 0.25 }}
          className="grid gap-4 md:grid-cols-2 xl:grid-cols-4"
        >
          <Metric
            icon={Ticket}
            label="Ticket stock"
            value={ticket ? formatNumber(ticket.stockAvailable) : "n/a"}
            detail={`Initial ${ticket ? formatNumber(ticket.stockInitial) : "n/a"}`}
          />
          <Metric
            icon={Database}
            label="DB stock"
            value={consistency ? formatNumber(consistency.dbStockAfter) : "n/a"}
            detail={`Orders ${consistency ? formatNumber(consistency.dbOrderCount) : "n/a"}`}
          />
          <Metric
            icon={Zap}
            label="Redis stock"
            value={consistency ? formatNumber(consistency.redisStockAfter) : "n/a"}
            detail={`Drift ${consistency ? formatNumber(consistency.redisDbInconsistencyCount) : "n/a"}`}
          />
          <Metric
            icon={CheckCircle2}
            label="Oversold"
            value={consistency ? formatNumber(consistency.oversoldCount) : "n/a"}
            detail={consistency?.oversoldCount === 0 ? "Safe snapshot" : "Needs attention"}
          />
        </motion.section>

        <section className="grid gap-5 lg:grid-cols-[1.1fr_0.9fr]">
          <Card>
            <CardHeader>
              <CardTitle>Ticket status</CardTitle>
              <CardDescription>
                Live ticket item data from the Spring Boot API.
              </CardDescription>
            </CardHeader>
            <CardContent>
              <div className="grid gap-4 sm:grid-cols-2">
                <InfoBlock label="Ticket" value={ticket?.name ?? "Loading"} />
                <InfoBlock label="Ticket item ID" value={ticket?.id ?? DEFAULT_TICKET_ID} />
                <InfoBlock label="Flash price" value={formatCurrency(ticket?.priceFlash)} />
                <InfoBlock label="Original price" value={formatCurrency(ticket?.priceOriginal)} />
                <InfoBlock label="Sale starts" value={formatDateTime(ticket?.saleStartTime)} />
                <InfoBlock label="Sale ends" value={formatDateTime(ticket?.saleEndTime)} />
              </div>
            </CardContent>
          </Card>

          <Card>
            <CardHeader>
              <CardTitle>Lab controls</CardTitle>
              <CardDescription>
                Reset state, warm Redis, and inspect consistency.
              </CardDescription>
            </CardHeader>
            <CardContent>
              <form className="grid gap-4 sm:grid-cols-3" onSubmit={onReset}>
                <Field label="Ticket ID" error={resetForm.formState.errors.ticketItemId?.message}>
                  <Input type="number" {...resetForm.register("ticketItemId")} />
                </Field>
                <Field label="Stock" error={resetForm.formState.errors.stock?.message}>
                  <Input type="number" {...resetForm.register("stock")} />
                </Field>
                <Field label="Year month" error={resetForm.formState.errors.yearMonth?.message}>
                  <Input inputMode="numeric" {...resetForm.register("yearMonth")} />
                </Field>
                <div className="flex flex-wrap gap-2 sm:col-span-3">
                  <Button type="submit" disabled={loadingKey === "reset"}>
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
                    onClick={onRefreshConsistency}
                    disabled={loadingKey === "consistency"}
                  >
                    <Activity className="h-4 w-4" />
                    Check consistency
                  </Button>
                </div>
              </form>
              {lastReset ? (
                <Alert className="mt-4">
                  Reset complete for ticket {lastReset.ticketItemId}. Redis stock{" "}
                  {lastReset.redisStockAfter}, DB stock {lastReset.dbStockAfter}.
                </Alert>
              ) : null}
            </CardContent>
          </Card>
        </section>

        <section className="grid gap-5 lg:grid-cols-[0.9fr_1.1fr]">
          <Card>
            <CardHeader>
              <CardTitle>Create order</CardTitle>
              <CardDescription>
                Submit one order against the selected deduction strategy.
              </CardDescription>
            </CardHeader>
            <CardContent>
              <form className="grid gap-4" onSubmit={onCreateOrder}>
                <div className="grid gap-4 sm:grid-cols-3">
                  <Field label="Ticket ID" error={orderForm.formState.errors.ticketItemId?.message}>
                    <Input type="number" {...orderForm.register("ticketItemId")} />
                  </Field>
                  <Field label="User ID" error={orderForm.formState.errors.userId?.message}>
                    <Input type="number" {...orderForm.register("userId")} />
                  </Field>
                  <Field label="Quantity" error={orderForm.formState.errors.quantity?.message}>
                    <Input type="number" {...orderForm.register("quantity")} />
                  </Field>
                </div>

                <div>
                  <Label>Strategy</Label>
                  <div className="mt-2 grid gap-2 sm:grid-cols-2">
                    {ORDER_STRATEGIES.map((strategy) => (
                      <button
                        key={strategy}
                        type="button"
                        aria-pressed={selectedStrategy === strategy}
                        onClick={() =>
                          orderForm.setValue("strategy", strategy, {
                            shouldDirty: true,
                            shouldValidate: true,
                          })
                        }
                        className={cn(
                          "rounded-lg bg-white p-3 text-left shadow-[rgba(34,42,53,0.10)_0_0_0_1px] transition hover:bg-[#f8f8f8] focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-blue-500/50",
                          selectedStrategy === strategy &&
                            "bg-[#242424] text-white shadow-[rgba(255,255,255,0.15)_0_2px_0_inset]",
                        )}
                      >
                        <span className="block text-sm font-semibold">
                          {strategyDetails[strategy].label}
                        </span>
                        <span
                          className={cn(
                            "mt-1 block text-xs leading-5 text-[#898989]",
                            selectedStrategy === strategy && "text-white/75",
                          )}
                        >
                          {strategyDetails[strategy].summary}
                        </span>
                      </button>
                    ))}
                  </div>
                </div>

                <Button type="submit" disabled={loadingKey === "order"}>
                  {loadingKey === "order" ? (
                    <Loader2 className="h-4 w-4 animate-spin" />
                  ) : (
                    <ShoppingCart className="h-4 w-4" />
                  )}
                  Create order
                </Button>
              </form>

              {lastOrder ? (
                <Alert className="mt-4">
                  <span className="font-semibold">{lastOrder.message}</span>
                  <span className="mt-1 block">
                    Order {lastOrder.orderNumber ?? "not created"} | Redis{" "}
                    {lastOrder.redisStockAfter ?? "n/a"} | DB{" "}
                    {lastOrder.dbStockAfter ?? "n/a"}
                  </span>
                </Alert>
              ) : null}
            </CardContent>
          </Card>

          <Card>
            <CardHeader>
              <CardTitle>Orders</CardTitle>
              <CardDescription>
                Look up the latest order or list rows from a monthly table.
              </CardDescription>
            </CardHeader>
            <CardContent className="space-y-5">
              <form className="grid gap-3 sm:grid-cols-[1fr_auto]" onSubmit={onLookupOrder}>
                <Field
                  label="Order number"
                  error={orderLookupForm.formState.errors.orderNumber?.message}
                >
                  <Input placeholder="OKX-SGN-42-..." {...orderLookupForm.register("orderNumber")} />
                </Field>
                <Button
                  type="submit"
                  variant="secondary"
                  className="self-end"
                  disabled={loadingKey === "lookup"}
                >
                  <Search className="h-4 w-4" />
                  Lookup
                </Button>
              </form>

              {foundOrder ? (
                <div className="grid gap-3 rounded-lg bg-[#f7f7f7] p-4 sm:grid-cols-2">
                  <InfoBlock label="Order" value={foundOrder.orderNumber} compact />
                  <InfoBlock label="User" value={foundOrder.userId} compact />
                  <InfoBlock label="Amount" value={formatCurrency(foundOrder.totalAmount)} compact />
                  <InfoBlock label="Created" value={formatDateTime(foundOrder.createdAt)} compact />
                </div>
              ) : null}

              <form className="grid gap-3 sm:grid-cols-[1fr_1fr_auto]" onSubmit={onListOrders}>
                <Field label="User ID" error={orderListForm.formState.errors.userId?.message}>
                  <Input type="number" {...orderListForm.register("userId")} />
                </Field>
                <Field label="Year month" error={orderListForm.formState.errors.yearMonth?.message}>
                  <Input inputMode="numeric" {...orderListForm.register("yearMonth")} />
                </Field>
                <Button
                  type="submit"
                  variant="secondary"
                  className="self-end"
                  disabled={loadingKey === "list"}
                >
                  List
                </Button>
              </form>

              <DataTable columns={orderColumns} data={orders} empty="No orders loaded yet." />
            </CardContent>
          </Card>
        </section>

        <section className="grid gap-5 xl:grid-cols-[0.9fr_1.1fr]">
          <Card>
            <CardHeader>
              <CardTitle>Benchmark comparison</CardTitle>
              <CardDescription>
                Local JMeter run: 5,000 requests, 100 concurrent users, stock 1,000.
              </CardDescription>
            </CardHeader>
            <CardContent>
              <div className="h-[320px] w-full">
                <BenchmarkChart />
              </div>
            </CardContent>
          </Card>

          <Card>
            <CardHeader>
              <CardTitle>Benchmark rows</CardTitle>
              <CardDescription>
                Same values recorded in README for recruiter-friendly proof.
              </CardDescription>
            </CardHeader>
            <CardContent>
              <Table>
                <TableHeader>
                  <TableRow>
                    {benchmarkColumns.map((column) => (
                      <TableHead key={column.key}>{column.label}</TableHead>
                    ))}
                  </TableRow>
                </TableHeader>
                <TableBody>
                  {benchmarkRows.map((row) => (
                    <TableRow key={row.strategy}>
                      <TableCell>{strategyDetails[row.strategy].label}</TableCell>
                      <TableCell>{row.throughput}</TableCell>
                      <TableCell>{row.averageMs}</TableCell>
                      <TableCell>{formatNumber(row.p95Ms)}</TableCell>
                      <TableCell>{formatNumber(row.p99Ms)}</TableCell>
                      <TableCell>{row.oversoldCount}</TableCell>
                      <TableCell>{row.redisDbInconsistencyCount}</TableCell>
                    </TableRow>
                  ))}
                </TableBody>
              </Table>
            </CardContent>
          </Card>
        </section>
      </div>
    </main>
  );
}

function Metric({
  icon: Icon,
  label,
  value,
  detail,
}: {
  icon: React.ComponentType<{ className?: string }>;
  label: string;
  value: string;
  detail: string;
}) {
  return (
    <div className="rounded-xl bg-white p-4 shadow-[rgba(19,19,22,0.7)_0_1px_5px_-4px,rgba(34,42,53,0.08)_0_0_0_1px,rgba(34,42,53,0.05)_0_4px_8px_0]">
      <div className="flex items-center justify-between gap-3">
        <span className="text-xs font-semibold uppercase tracking-[0.08em] text-[#898989]">
          {label}
        </span>
        <span className="rounded-full bg-[#f5f5f5] p-2">
          <Icon className="h-4 w-4 text-[#242424]" />
        </span>
      </div>
      <div className="mt-4 font-display text-3xl font-semibold leading-none text-[#242424]">
        {value}
      </div>
      <p className="mt-2 text-sm text-[#898989]">{detail}</p>
    </div>
  );
}

function Field({
  label,
  error,
  children,
}: {
  label: string;
  error?: string;
  children: React.ReactNode;
}) {
  return (
    <div className="space-y-2">
      <Label>{label}</Label>
      {children}
      {error ? <p className="text-xs font-medium text-[#6b2a2a]">{error}</p> : null}
    </div>
  );
}

function InfoBlock({
  label,
  value,
  compact = false,
}: {
  label: string;
  value: React.ReactNode;
  compact?: boolean;
}) {
  return (
    <div className={cn("rounded-lg bg-[#f7f7f7] p-4", compact && "p-3")}>
      <div className="text-xs font-semibold uppercase tracking-[0.08em] text-[#898989]">
        {label}
      </div>
      <div className="mt-2 break-words text-sm font-semibold leading-6 text-[#242424]">
        {value}
      </div>
    </div>
  );
}

function DataTable<TData>({
  columns,
  data,
  empty,
}: {
  columns: ColumnDef<TData>[];
  data: TData[];
  empty: string;
}) {
  // TanStack Table intentionally returns stable table helpers; React Compiler flags it conservatively.
  // eslint-disable-next-line react-hooks/incompatible-library
  const table = useReactTable({
    data,
    columns,
    getCoreRowModel: getCoreRowModel(),
  });

  return (
    <Table>
      <TableHeader>
        {table.getHeaderGroups().map((headerGroup) => (
          <TableRow key={headerGroup.id}>
            {headerGroup.headers.map((header) => (
              <TableHead key={header.id}>
                {header.isPlaceholder
                  ? null
                  : flexRender(header.column.columnDef.header, header.getContext())}
              </TableHead>
            ))}
          </TableRow>
        ))}
      </TableHeader>
      <TableBody>
        {table.getRowModel().rows.length ? (
          table.getRowModel().rows.map((row) => (
            <TableRow key={row.id}>
              {row.getVisibleCells().map((cell) => (
                <TableCell key={cell.id}>
                  {flexRender(cell.column.columnDef.cell, cell.getContext())}
                </TableCell>
              ))}
            </TableRow>
          ))
        ) : (
          <TableRow>
            <TableCell colSpan={columns.length} className="py-6 text-center text-[#898989]">
              {empty}
            </TableCell>
          </TableRow>
        )}
      </TableBody>
    </Table>
  );
}

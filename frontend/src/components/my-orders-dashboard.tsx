"use client";

import type { ColumnDef } from "@tanstack/react-table";
import { ArrowRight, Loader2, Search } from "lucide-react";
import Link from "next/link";
import { useMemo, useState } from "react";
import { toast } from "sonner";

import { listOrders } from "@/lib/api";
import { DEFAULT_USER_ID, DEFAULT_YEAR_MONTH } from "@/lib/events";
import type { TicketOrder } from "@/lib/types";
import { formatCurrency, formatDateTime } from "@/lib/utils";

import { DataTable, Field } from "@/components/dashboard-primitives";
import { Alert } from "@/components/ui/alert";
import { Button } from "@/components/ui/button";
import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from "@/components/ui/card";
import { Input } from "@/components/ui/input";

export function MyOrdersDashboard() {
  const [userId, setUserId] = useState(DEFAULT_USER_ID);
  const [yearMonth, setYearMonth] = useState(DEFAULT_YEAR_MONTH);
  const [orders, setOrders] = useState<TicketOrder[]>([]);
  const [isLoading, setIsLoading] = useState(false);
  const [errorMessage, setErrorMessage] = useState<string | null>(null);

  const columns = useMemo<ColumnDef<TicketOrder>[]>(
    () => [
      {
        accessorKey: "orderNumber",
        header: "Order",
        cell: ({ row }) => (
          <Link
            href={`/orders/${encodeURIComponent(row.original.orderNumber)}`}
            className="inline-flex items-center gap-2 font-semibold text-[#242424] hover:underline"
          >
            {row.original.orderNumber}
            <ArrowRight className="h-3.5 w-3.5" />
          </Link>
        ),
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

  const onSubmit = async (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    setIsLoading(true);
    setErrorMessage(null);

    try {
      const envelope = await listOrders(userId, yearMonth);
      setOrders(envelope.result);
      toast.success(`Loaded ${envelope.result.length} orders`);
    } catch (error) {
      const message = error instanceof Error ? error.message : "Unable to list orders";
      setErrorMessage(message);
      toast.error(message);
    } finally {
      setIsLoading(false);
    }
  };

  return (
    <main className="mx-auto flex w-full max-w-[1200px] flex-col gap-8 px-4 py-12 sm:px-6 lg:px-8">
      <header className="max-w-3xl">
        <p className="text-sm font-semibold uppercase tracking-[0.08em] text-[#898989]">
          Order trace lookup
        </p>
        <h1 className="mt-4 font-display text-4xl font-semibold leading-[1.1] text-[#242424] sm:text-5xl">
          Order traces
        </h1>
        <p className="mt-4 max-w-2xl text-base leading-7 text-[#898989]">
          Search backend order rows by demo user and monthly shard, then open any order by number.
        </p>
      </header>

      <Card>
        <CardHeader>
          <CardTitle>Find orders</CardTitle>
          <CardDescription>Defaults match the benchmark fixture user and month.</CardDescription>
        </CardHeader>
        <CardContent className="space-y-5">
          <form className="grid gap-4 sm:grid-cols-[1fr_1fr_auto]" onSubmit={onSubmit}>
            <Field label="User ID">
              <Input
                type="number"
                min={1}
                value={userId}
                onChange={(event) => setUserId(Number(event.target.value))}
              />
            </Field>
            <Field label="Year month">
              <Input
                inputMode="numeric"
                value={yearMonth}
                onChange={(event) => setYearMonth(event.target.value)}
              />
            </Field>
            <Button type="submit" className="self-end" disabled={isLoading}>
              {isLoading ? (
                <Loader2 className="h-4 w-4 animate-spin" />
              ) : (
                <Search className="h-4 w-4" />
              )}
              Search
            </Button>
          </form>

          {errorMessage ? <Alert>{errorMessage}</Alert> : null}

          <DataTable columns={columns} data={orders} empty="No orders loaded yet." />
        </CardContent>
      </Card>
    </main>
  );
}

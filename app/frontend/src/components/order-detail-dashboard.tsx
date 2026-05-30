"use client";

import { CheckCircle2, Loader2, RefreshCw, Ticket } from "lucide-react";
import Link from "next/link";
import { useCallback, useEffect, useState } from "react";
import { toast } from "sonner";

import { getOrder } from "@/lib/api";
import type { TicketOrder } from "@/lib/types";
import { formatCurrency, formatDateTime } from "@/lib/utils";

import { InfoBlock } from "@/components/dashboard-primitives";
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

/**
 * Reads one persisted order row by order number.
 *
 * The page proves order traces survive refreshes, which is important when explaining benchmark
 * accepts and rejects after a run.
 */
export function OrderDetailDashboard({ orderNumber }: { orderNumber: string }) {
  const [order, setOrder] = useState<TicketOrder | null>(null);
  const [isLoading, setIsLoading] = useState(false);
  const [errorMessage, setErrorMessage] = useState<string | null>(null);

  const loadOrder = useCallback(async () => {
    setIsLoading(true);
    setErrorMessage(null);

    try {
      const envelope = await getOrder(orderNumber);
      setOrder(envelope.result);
    } catch (error) {
      const message = error instanceof Error ? error.message : "Unable to load order";
      setErrorMessage(message);
      toast.error(message);
    } finally {
      setIsLoading(false);
    }
  }, [orderNumber]);

  useEffect(() => {
    // The order detail page must hydrate from backend data after refresh.
    // eslint-disable-next-line react-hooks/set-state-in-effect
    void loadOrder();
  }, [loadOrder]);

  return (
    <main className="mx-auto flex w-full max-w-[960px] flex-col gap-8 px-4 py-12 sm:px-6 lg:px-8">
      <header className="flex flex-col gap-5 border-b border-black/[0.06] pb-6 sm:flex-row sm:items-end sm:justify-between">
        <div>
          <div className="mb-3 flex flex-wrap gap-2">
            <Badge variant={order ? "success" : "outline"}>
              <CheckCircle2 className="mr-1 h-3 w-3" />
              {order ? "Order row loaded" : "Loading order"}
            </Badge>
          </div>
          <h1 className="font-display text-4xl font-semibold leading-[1.1] text-[#242424] sm:text-5xl">
            Order trace
          </h1>
          <p className="mt-3 break-words text-base leading-7 text-[#898989]">
            {orderNumber}
          </p>
        </div>
        <Button type="button" variant="secondary" onClick={loadOrder} disabled={isLoading}>
          {isLoading ? (
            <Loader2 className="h-4 w-4 animate-spin" />
          ) : (
            <RefreshCw className="h-4 w-4" />
          )}
          Refresh
        </Button>
      </header>

      {errorMessage ? <Alert>{errorMessage}</Alert> : null}

      <Card>
        <CardHeader>
          <CardTitle>Stored order facts</CardTitle>
          <CardDescription>Order rows stay available after page refresh.</CardDescription>
        </CardHeader>
        <CardContent>
          {order ? (
            <div className="grid gap-4 sm:grid-cols-2">
              <InfoBlock label="Order number" value={order.orderNumber} />
              <InfoBlock label="User ID" value={order.userId} />
              <InfoBlock label="Amount" value={formatCurrency(order.totalAmount)} />
              <InfoBlock label="Terminal" value={order.terminalId} />
              <InfoBlock label="Order date" value={formatDateTime(order.orderDate)} />
              <InfoBlock label="Created" value={formatDateTime(order.createdAt)} />
              <InfoBlock label="Updated" value={formatDateTime(order.updatedAt)} />
              <InfoBlock label="Notes" value={order.orderNotes} />
            </div>
          ) : (
            <div className="rounded-lg bg-[#f7f7f7] p-6 text-sm text-[#898989]">
              {isLoading ? "Loading order from backend." : "No order loaded."}
            </div>
          )}
        </CardContent>
      </Card>

      <div className="flex flex-wrap gap-3">
        <Button asChild>
          <Link href="/events/4">
            <Ticket className="h-4 w-4" />
            Run another probe
          </Link>
        </Button>
        <Button asChild variant="secondary">
          <Link href="/my-orders">View order traces</Link>
        </Button>
      </div>
    </main>
  );
}

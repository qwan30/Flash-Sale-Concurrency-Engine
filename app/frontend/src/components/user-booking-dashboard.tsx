"use client";

import {
  CalendarDays,
  CheckCircle2,
  Loader2,
  MapPin,
  Send,
  Settings2,
  Ticket,
} from "lucide-react";
import { useCallback, useEffect, useState } from "react";
import { toast } from "sonner";

import { createOrder, getTicket } from "@/lib/api";
import {
  DEFAULT_CUSTOMER_STRATEGY,
  DEFAULT_TICKET_ID,
  DEFAULT_USER_ID,
  eventStatusLabels,
  getEventSummary,
} from "@/lib/events";
import { nextIdempotencyKey } from "@/lib/order-client";
import { strategyDetails } from "@/lib/strategy";
import {
  ORDER_STRATEGIES,
  type CreateOrderResponse,
  type EventSummary,
  type OrderStrategy,
  type TicketDetail,
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

/**
 * Customer-facing probe for a single ticket fixture.
 *
 * It deliberately exposes the strategy selector so the lab can compare how each backend stock
 * deduction strategy behaves under the same order form.
 */
export function UserBookingDashboard({
  ticketItemId = DEFAULT_TICKET_ID,
  event = getEventSummary(ticketItemId),
  compact = false,
}: {
  ticketItemId?: number;
  event?: EventSummary;
  compact?: boolean;
}) {
  const [ticket, setTicket] = useState<TicketDetail | null>(null);
  const [quantity, setQuantity] = useState(1);
  const [userId, setUserId] = useState(DEFAULT_USER_ID);
  const [strategy, setStrategy] = useState<OrderStrategy>(DEFAULT_CUSTOMER_STRATEGY);
  const [isLoadingTicket, setIsLoadingTicket] = useState(false);
  const [isSubmitting, setIsSubmitting] = useState(false);
  const [lastOrder, setLastOrder] = useState<CreateOrderResponse | null>(null);
  const [errorMessage, setErrorMessage] = useState<string | null>(null);

  const loadTicket = useCallback(async () => {
    setIsLoadingTicket(true);
    setErrorMessage(null);

    try {
      const envelope = await getTicket(ticketItemId);
      setTicket(envelope.result);
    } catch (error) {
      const message = error instanceof Error ? error.message : "Unable to load ticket";
      setErrorMessage(message);
      toast.error(message);
    } finally {
      setIsLoadingTicket(false);
    }
  }, [ticketItemId]);

  useEffect(() => {
    // Initial ticket hydration is driven by the active ticket ID.
    // eslint-disable-next-line react-hooks/set-state-in-effect
    void loadTicket();
  }, [loadTicket]);

  const onSubmit = async (eventPayload: React.FormEvent<HTMLFormElement>) => {
    eventPayload.preventDefault();
    setIsSubmitting(true);
    setLastOrder(null);
    setErrorMessage(null);

    try {
      const envelope = await createOrder({
        ticketItemId,
        userId,
        quantity,
        strategy,
        // Use a fresh key per click so deliberate repeated probes still hit the backend.
        idempotencyKey: nextIdempotencyKey(userId),
      });

      setLastOrder(envelope.result);
      toast.message(envelope.result.message || "Order probe completed");
      await loadTicket();
    } catch (error) {
      const message = error instanceof Error ? error.message : "Unable to create order";
      setErrorMessage(message);
      toast.error(message);
    } finally {
      setIsSubmitting(false);
    }
  };

  const title = event?.title ?? ticket?.name ?? `Ticket ${ticketItemId}`;
  const saleState = event ? eventStatusLabels[event.status] : "On sale";
  const isSoldOut = event?.status === "sold_out" || ticket?.stockAvailable === 0;

  return (
    <section
      className={cn(
        "grid gap-6 lg:grid-cols-[1.05fr_0.95fr] lg:items-start",
        compact && "lg:grid-cols-1",
      )}
    >
      <Card>
        <CardHeader>
          <div className="mb-3 flex flex-wrap items-center gap-2">
            <Badge variant={event?.status === "sold_out" ? "secondary" : "default"}>
              {saleState}
            </Badge>
            <Badge variant="outline">Ticket ID {ticketItemId}</Badge>
          </div>
          <CardTitle className="text-3xl leading-[1.1] sm:text-4xl">{title}</CardTitle>
          <CardDescription className="max-w-2xl text-base leading-7">
            {event?.description ??
              "Live ticket data is loaded from the backend so the detail page can survive refreshes."}
          </CardDescription>
        </CardHeader>
        <CardContent className="space-y-5">
          <div className="grid gap-3 sm:grid-cols-2">
            <TicketFact icon={CalendarDays} label="When" value={`${event?.date ?? "Sale day"} | ${event?.time ?? "TBA"}`} />
            <TicketFact icon={MapPin} label="Where" value={`${event?.venue ?? "Main venue"} | ${event?.city ?? "Vietnam"}`} />
          </div>

          <div className="rounded-xl bg-[#f7f7f7] p-5">
            <div className="flex flex-col gap-4 sm:flex-row sm:items-end sm:justify-between">
              <div>
                <p className="text-xs font-semibold uppercase tracking-[0.08em] text-[#898989]">
                  Flash price
                </p>
                <div className="mt-2 font-display text-4xl font-semibold leading-none text-[#242424]">
                  {formatCurrency(ticket?.priceFlash ?? event?.priceFlash)}
                </div>
                <p className="mt-2 text-sm text-[#898989]">
                  Regular {formatCurrency(ticket?.priceOriginal ?? event?.priceOriginal)}
                </p>
              </div>
              <div className="grid gap-2 text-sm sm:text-right">
                <span className="font-semibold text-[#242424]">
                  {isLoadingTicket
                    ? "Refreshing stock"
                    : `${formatNumber(ticket?.stockAvailable)} available`}
                </span>
                <span className="text-[#898989]">{event?.stockLabel ?? "Live backend stock"}</span>
              </div>
            </div>
          </div>

          <div className="grid gap-3 sm:grid-cols-3">
            <TicketFact icon={Ticket} label="Initial stock" value={formatNumber(ticket?.stockInitial)} />
            <TicketFact
              icon={CheckCircle2}
              label="Prepared"
              value={ticket?.stockPrepared ? "Redis ready" : "Needs warmup"}
            />
            <TicketFact icon={Settings2} label="Sale window" value={formatDateTime(ticket?.saleEndTime)} />
          </div>
        </CardContent>
      </Card>

      <Card className="lg:sticky lg:top-6">
        <CardHeader>
          <CardTitle>Order probe</CardTitle>
          <CardDescription>
            Submit one controlled order request and observe how the selected strategy changes stock.
          </CardDescription>
        </CardHeader>
        <CardContent>
          <form className="space-y-4" onSubmit={onSubmit}>
            <div className="grid gap-3 sm:grid-cols-2">
              <div className="space-y-2">
                <Label htmlFor="booking-user-id">User ID</Label>
                <Input
                  id="booking-user-id"
                  data-testid="booking-user-id"
                  type="number"
                  min={1}
                  value={userId}
                  onChange={(inputEvent) => setUserId(Number(inputEvent.target.value))}
                />
              </div>
              <div className="space-y-2">
                <Label htmlFor="booking-quantity">Quantity</Label>
                <Input
                  id="booking-quantity"
                  data-testid="booking-quantity"
                  type="number"
                  min={1}
                  max={10}
                  value={quantity}
                  onChange={(inputEvent) => setQuantity(Number(inputEvent.target.value))}
                />
              </div>
            </div>

            <details className="rounded-lg bg-[#f7f7f7] p-4">
              <summary className="cursor-pointer text-sm font-semibold text-[#242424]">
                Advanced lab settings
              </summary>
              <div className="mt-4 space-y-2">
                <Label htmlFor="booking-strategy">Strategy</Label>
                <select
                  id="booking-strategy"
                  data-testid="booking-strategy"
                  value={strategy}
                  onChange={(selectEvent) => setStrategy(selectEvent.target.value as OrderStrategy)}
                  className="h-10 w-full rounded-lg bg-white px-3 text-sm text-[#242424] shadow-[rgba(34,42,53,0.10)_0_0_0_1px] focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-blue-500/50"
                >
                  {ORDER_STRATEGIES.map((orderStrategy) => (
                    <option key={orderStrategy} value={orderStrategy}>
                      {strategyDetails[orderStrategy].label}
                    </option>
                  ))}
                </select>
                <p className="text-xs leading-5 text-[#898989]">
                  {strategyDetails[strategy].summary}
                </p>
              </div>
            </details>

            {errorMessage ? <Alert data-testid="booking-error">{errorMessage}</Alert> : null}
            {lastOrder ? (
              <Alert data-testid="booking-result">
                {lastOrder.success ? "Accepted" : "Rejected"}: {lastOrder.message}
                {lastOrder.orderNumber ? (
                  <span data-testid="booking-order-number"> ({lastOrder.orderNumber})</span>
                ) : null}
              </Alert>
            ) : null}

            <Button
              type="submit"
              size="lg"
              className="w-full"
              data-testid="booking-submit-btn"
              disabled={isSubmitting || isSoldOut || quantity < 1 || userId < 1}
            >
              {isSubmitting ? (
                <Loader2 className="h-4 w-4 animate-spin" />
              ) : (
                <Send className="h-4 w-4" />
              )}
              Submit order probe
            </Button>
          </form>
        </CardContent>
      </Card>
    </section>
  );
}

function TicketFact({
  icon: Icon,
  label,
  value,
}: {
  icon: React.ComponentType<{ className?: string }>;
  label: string;
  value: React.ReactNode;
}) {
  return (
    <div className="rounded-lg bg-white p-4 shadow-[rgba(34,42,53,0.10)_0_0_0_1px]">
      <Icon className="h-4 w-4 text-[#242424]" />
      <div className="mt-3 text-xs font-semibold uppercase tracking-[0.08em] text-[#898989]">
        {label}
      </div>
      <div className="mt-2 break-words text-sm font-semibold leading-6 text-[#242424]">
        {value}
      </div>
    </div>
  );
}

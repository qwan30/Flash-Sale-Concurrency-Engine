import { ArrowRight, CheckCircle2, Clock3, ShieldCheck, Ticket, Zap } from "lucide-react";
import Link from "next/link";

import { eventStatusLabels, eventSummaries, getFeaturedEvent } from "@/lib/events";
import { formatCurrency } from "@/lib/utils";

import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";

export default function Home() {
  const featuredEvent = getFeaturedEvent();
  const secondaryEvents = eventSummaries.filter(
    (event) => event.ticketItemId !== featuredEvent.ticketItemId,
  );

  return (
    <main className="mx-auto flex w-full max-w-[1200px] flex-1 flex-col px-4 py-12 sm:px-6 lg:px-8">
      <section className="grid gap-10 pb-14 lg:grid-cols-[1.05fr_0.95fr] lg:items-center">
        <div className="max-w-3xl">
          <p className="text-sm font-semibold uppercase tracking-[0.08em] text-[#898989]">
            Flash-sale tickets
          </p>
          <h1 className="mt-4 font-display text-5xl font-semibold leading-[1.1] text-[#242424] sm:text-6xl">
            Tickets built for the rush.
          </h1>
          <p className="mt-5 max-w-2xl text-base leading-7 text-[#898989]">
            Browse limited event drops, move through checkout quickly, and keep the
            benchmark lab in the admin area where it belongs.
          </p>
          <div className="mt-8 flex flex-wrap gap-3">
            <Button asChild size="lg">
              <Link href="/events/4">
                Buy featured tickets
                <ArrowRight className="h-4 w-4" />
              </Link>
            </Button>
            <Button asChild size="lg" variant="secondary">
              <Link href="/events">Browse events</Link>
            </Button>
          </div>
        </div>

        <div className="rounded-xl bg-white p-5 shadow-[rgba(19,19,22,0.7)_0_1px_5px_-4px,rgba(34,42,53,0.08)_0_0_0_1px,rgba(34,42,53,0.05)_0_4px_8px_0]">
          <div className="rounded-lg bg-[#f7f7f7] p-5">
            <div className="flex items-start justify-between gap-4">
              <div>
                <p className="text-xs font-semibold uppercase tracking-[0.08em] text-[#898989]">
                  {featuredEvent.eyebrow}
                </p>
                <h2 className="mt-3 font-display text-3xl font-semibold leading-tight text-[#242424]">
                  {featuredEvent.title}
                </h2>
              </div>
              <Badge>{eventStatusLabels[featuredEvent.status]}</Badge>
            </div>
            <div className="mt-10 grid gap-3 sm:grid-cols-3">
              <TrustItem icon={Ticket} label="Stock" value={featuredEvent.stockLabel} />
              <TrustItem icon={Clock3} label="Sale" value={featuredEvent.saleLabel} />
              <TrustItem
                icon={Zap}
                label="From"
                value={formatCurrency(featuredEvent.priceFlash)}
              />
            </div>
          </div>
          <div className="mt-4 flex items-center justify-between gap-4">
            <div className="text-sm leading-6 text-[#898989]">
              {featuredEvent.date} at {featuredEvent.venue}
            </div>
            <Button asChild variant="secondary" size="sm">
              <Link href="/events/4">Details</Link>
            </Button>
          </div>
        </div>
      </section>

      <section className="grid gap-4 border-t border-black/[0.06] py-10 md:grid-cols-3">
        <ProofItem icon={ShieldCheck} label="Correctness" value="0 oversold in safe runs" />
        <ProofItem icon={Zap} label="Fast path" value="354.33 req/s recorded" />
        <ProofItem icon={CheckCircle2} label="Refresh-safe" value="Orders load by number" />
      </section>

      <section className="pb-10">
        <div className="mb-5 flex flex-col gap-3 sm:flex-row sm:items-end sm:justify-between">
          <div>
            <p className="text-sm font-semibold uppercase tracking-[0.08em] text-[#898989]">
              Next drops
            </p>
            <h2 className="mt-2 font-display text-3xl font-semibold leading-tight text-[#242424]">
              More events on the board
            </h2>
          </div>
          <Button asChild variant="secondary">
            <Link href="/events">View all events</Link>
          </Button>
        </div>
        <div className="grid gap-4 md:grid-cols-3">
          {secondaryEvents.map((event) => (
            <article
              key={event.ticketItemId}
              className="rounded-xl bg-white p-5 shadow-[rgba(19,19,22,0.7)_0_1px_5px_-4px,rgba(34,42,53,0.08)_0_0_0_1px,rgba(34,42,53,0.05)_0_4px_8px_0]"
            >
              <Badge variant="outline">{event.category}</Badge>
              <h3 className="mt-4 font-display text-xl font-semibold leading-tight text-[#242424]">
                {event.title}
              </h3>
              <p className="mt-3 text-sm leading-6 text-[#898989]">
                {event.date} | {event.city}
              </p>
              <div className="mt-5 text-lg font-semibold text-[#242424]">
                {formatCurrency(event.priceFlash)}
              </div>
            </article>
          ))}
        </div>
      </section>
    </main>
  );
}

function TrustItem({
  icon: Icon,
  label,
  value,
}: {
  icon: React.ComponentType<{ className?: string }>;
  label: string;
  value: string;
}) {
  return (
    <div className="rounded-lg bg-white p-4 shadow-[rgba(34,42,53,0.10)_0_0_0_1px]">
      <Icon className="h-4 w-4 text-[#242424]" />
      <div className="mt-4 text-xs font-semibold uppercase tracking-[0.08em] text-[#898989]">
        {label}
      </div>
      <div className="mt-2 text-sm font-semibold leading-5 text-[#242424]">{value}</div>
    </div>
  );
}

function ProofItem({
  icon: Icon,
  label,
  value,
}: {
  icon: React.ComponentType<{ className?: string }>;
  label: string;
  value: string;
}) {
  return (
    <div>
      <Icon className="h-5 w-5 text-[#242424]" />
      <div className="mt-4 text-xs font-semibold uppercase tracking-[0.08em] text-[#898989]">
        {label}
      </div>
      <div className="mt-2 text-base font-semibold text-[#242424]">{value}</div>
    </div>
  );
}

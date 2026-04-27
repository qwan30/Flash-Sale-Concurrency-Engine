"use client";

import { ArrowRight, Search } from "lucide-react";
import Link from "next/link";
import { useMemo, useState } from "react";

import { eventStatusLabels } from "@/lib/events";
import type { EventSummary, EventStatus } from "@/lib/types";
import { formatCurrency } from "@/lib/utils";

import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";

const statusFilters: Array<"all" | EventStatus> = ["all", "live", "limited", "upcoming"];

export function EventCatalog({ events }: { events: EventSummary[] }) {
  const [query, setQuery] = useState("");
  const [status, setStatus] = useState<"all" | EventStatus>("all");
  const [sort, setSort] = useState<"soonest" | "price">("soonest");

  const filteredEvents = useMemo(() => {
    const normalizedQuery = query.trim().toLowerCase();

    return events
      .filter((event) => {
        const matchesQuery =
          !normalizedQuery ||
          [event.title, event.venue, event.city, event.category]
            .join(" ")
            .toLowerCase()
            .includes(normalizedQuery);
        const matchesStatus = status === "all" || event.status === status;

        return matchesQuery && matchesStatus;
      })
      .sort((first, second) => {
        if (sort === "price") {
          return first.priceFlash - second.priceFlash;
        }

        return Date.parse(first.date) - Date.parse(second.date);
      });
  }, [events, query, sort, status]);

  return (
    <section className="space-y-6">
      <div className="grid gap-3 lg:grid-cols-[1fr_auto_auto]">
        <label className="relative block">
          <span className="sr-only">Search fixtures</span>
          <Search className="pointer-events-none absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-[#898989]" />
          <Input
            value={query}
            onChange={(event) => setQuery(event.target.value)}
            placeholder="Search fixture, city, or category"
            className="pl-9"
          />
        </label>
        <div className="flex flex-wrap gap-2">
          {statusFilters.map((filter) => (
            <button
              key={filter}
              type="button"
              onClick={() => setStatus(filter)}
              className={`rounded-full px-3 py-2 text-sm font-semibold transition focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-blue-500/50 ${
                status === filter
                  ? "bg-[#242424] text-white"
                  : "bg-white text-[#242424] shadow-[rgba(34,42,53,0.10)_0_0_0_1px] hover:bg-[#f7f7f7]"
              }`}
            >
              {filter === "all" ? "All" : eventStatusLabels[filter]}
            </button>
          ))}
        </div>
        <select
          value={sort}
          onChange={(event) => setSort(event.target.value as "soonest" | "price")}
          className="h-10 rounded-lg bg-white px-3 text-sm font-semibold text-[#242424] shadow-[rgba(34,42,53,0.10)_0_0_0_1px] focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-blue-500/50"
          aria-label="Sort events"
        >
          <option value="soonest">Soonest</option>
          <option value="price">Lowest fixture value</option>
        </select>
      </div>

      <div className="grid gap-4 md:grid-cols-2 xl:grid-cols-3">
        {filteredEvents.map((event) => (
          <EventCard key={event.ticketItemId} event={event} />
        ))}
      </div>
    </section>
  );
}

function EventCard({ event }: { event: EventSummary }) {
  const canOpenDetail = event.ticketItemId === 4 && event.status !== "sold_out";

  return (
    <article className="flex min-h-[360px] flex-col rounded-xl bg-white p-5 shadow-[rgba(19,19,22,0.7)_0_1px_5px_-4px,rgba(34,42,53,0.08)_0_0_0_1px,rgba(34,42,53,0.05)_0_4px_8px_0]">
      <div className="mb-5 rounded-lg bg-[#f7f7f7] p-4">
        <div className="flex items-start justify-between gap-3">
          <span className="text-xs font-semibold uppercase tracking-[0.08em] text-[#898989]">
            {event.eyebrow}
          </span>
          <Badge variant={event.status === "sold_out" ? "secondary" : "default"}>
            {eventStatusLabels[event.status]}
          </Badge>
        </div>
        <div className="mt-10 flex items-end justify-between gap-4">
          <div>
            <div className="font-display text-3xl font-semibold leading-none text-[#242424]">
              {event.date.split(",")[0]}
            </div>
            <div className="mt-1 text-sm font-semibold text-[#898989]">
              {event.time}
            </div>
          </div>
          <div className="text-right text-sm font-semibold leading-5 text-[#242424]">
            {event.venue}
          </div>
        </div>
      </div>

      <div className="flex flex-1 flex-col">
        <div className="flex items-center justify-between gap-3">
          <Badge variant="outline">{event.category}</Badge>
          <span className="text-xs font-semibold text-[#898989]">{event.stockLabel}</span>
        </div>
        <h2 className="mt-4 font-display text-2xl font-semibold leading-tight text-[#242424]">
          {event.title}
        </h2>
        <p className="mt-3 flex-1 text-sm leading-6 text-[#898989]">{event.description}</p>
        <div className="mt-5 flex items-end justify-between gap-4">
          <div>
            <div className="text-xs font-semibold uppercase tracking-[0.08em] text-[#898989]">
              From
            </div>
            <div className="mt-1 text-xl font-semibold text-[#242424]">
              {formatCurrency(event.priceFlash)}
            </div>
          </div>
          {canOpenDetail ? (
            <Button asChild>
              <Link href={`/events/${event.ticketItemId}`}>
                Probe
                <ArrowRight className="h-4 w-4" />
              </Link>
            </Button>
          ) : (
            <Button type="button" variant="secondary" disabled>
              {event.saleLabel}
            </Button>
          )}
        </div>
      </div>
    </article>
  );
}

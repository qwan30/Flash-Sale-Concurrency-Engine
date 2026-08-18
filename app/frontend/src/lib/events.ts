import type { EventStatus, EventSummary } from "@/lib/types";

export const DEFAULT_TICKET_ID = 4;
export const DEFAULT_YEAR_MONTH = "202604";
export const DEFAULT_USER_ID = 42;
export const DEFAULT_CUSTOMER_STRATEGY = "REDIS_LUA_WITH_COMPENSATION" as const;

export const eventStatusLabels: Record<EventStatus, string> = {
  live: "On sale",
  limited: "Limited",
  upcoming: "Soon",
  sold_out: "Sold out",
};

export const eventSummaries: EventSummary[] = [
  {
    ticketItemId: DEFAULT_TICKET_ID,
    title: "BlackPink World Tour 2026 — VIP Soundcheck",
    eyebrow: "Global Mega Concert Drop",
    date: "Apr 27, 2026",
    time: "20:00",
    venue: "District 1 Arena (National Stadium)",
    city: "Ho Chi Minh City",
    category: "Concert VIP",
    priceOriginal: 4500000,
    priceFlash: 900000,
    status: "live",
    stockLabel: "1,000 VIP seats",
    saleLabel: "80% Flash Drop",
    description:
      "High-demand concert drop exercising Redis Lua atomic pre-deduction, idempotency journal replay, and zero-oversell guarantee.",
    featured: true,
    scenarioType: "concert",
    concurrencyLevel: "Extreme (100k Surge)",
    initialStock: 1000,
  },
  {
    ticketItemId: 8,
    title: "NVIDIA GeForce RTX 5090 32GB FE",
    eyebrow: "Flagship Hardware Drop",
    date: "May 10, 2026",
    time: "18:30",
    venue: "Silicon Warehouse Hub",
    city: "Hanoi",
    category: "Tech Hardware",
    priceOriginal: 52000000,
    priceFlash: 12000000,
    status: "live",
    stockLabel: "5,000 units allocated",
    saleLabel: "Hardware Launch",
    description:
      "Massive throughput surge scenario comparing DB conditional locking vs. Redis Lua pre-deduction under 5,000 TPS.",
    scenarioType: "tech",
    concurrencyLevel: "High Concurrency",
    initialStock: 5000,
  },
  {
    ticketItemId: 12,
    title: "Travis Scott x Air Jordan 1 Low 'Velvet'",
    eyebrow: "Limited Streetwear Drop",
    date: "May 18, 2026",
    time: "19:00",
    venue: "Sneakerhead Flagship Box",
    city: "Da Nang",
    category: "Streetwear",
    priceOriginal: 18500000,
    priceFlash: 4200000,
    status: "limited",
    stockLabel: "50 pairs only",
    saleLabel: "Micro-Stock Contention",
    description:
      "Ultra-hot key contention on 50 pairs where 10,000 bots collide, testing row-lock contention and admission gate shedding.",
    scenarioType: "sneaker",
    concurrencyLevel: "Hyper Contention",
    initialStock: 50,
  },
  {
    ticketItemId: 16,
    title: "Tết Peak Train SE1 — VIP 4-Berth Sleeper",
    eyebrow: "Lunar New Year Express",
    date: "Jun 06, 2026",
    time: "19:30",
    venue: "Saigon Central Railway Station",
    city: "Ho Chi Minh City",
    category: "Holiday Transport",
    priceOriginal: 1400000,
    priceFlash: 280000,
    status: "limited",
    stockLabel: "100 berths",
    saleLabel: "2-Phase 120s TTL",
    description:
      "Two-Phase Reservation demo with 120s hold countdown, background sweeper expiration, and double-spend race protection.",
    scenarioType: "train",
    concurrencyLevel: "2-Phase Reservation",
    initialStock: 100,
  },
];

export function getFeaturedEvent() {
  return eventSummaries.find((event) => event.featured) ?? eventSummaries[0];
}

export function getEventSummary(ticketItemId: number) {
  return eventSummaries.find((event) => event.ticketItemId === ticketItemId) ?? eventSummaries[0];
}

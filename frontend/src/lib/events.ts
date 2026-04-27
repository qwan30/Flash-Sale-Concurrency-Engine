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
    title: "Saigon Midnight Live",
    eyebrow: "Featured flash sale",
    date: "Apr 27, 2026",
    time: "20:00",
    venue: "District 1 Arena",
    city: "Ho Chi Minh City",
    category: "Concert",
    priceOriginal: 1200000,
    priceFlash: 699000,
    status: "live",
    stockLabel: "1,000 VIP seats",
    saleLabel: "Benchmark stock",
    description:
      "A high-demand ticket fixture used to exercise stock deduction, idempotency, and order persistence.",
    featured: true,
  },
  {
    ticketItemId: 8,
    title: "Hanoi Indie Weekender",
    eyebrow: "Weekend pass",
    date: "May 10, 2026",
    time: "18:30",
    venue: "Long Bien Stage",
    city: "Hanoi",
    category: "Festival",
    priceOriginal: 860000,
    priceFlash: 520000,
    status: "limited",
    stockLabel: "Low allocation",
    saleLabel: "Fixture only",
    description:
      "Two nights of indie acts and late-night DJ sets with a small ticket allocation.",
  },
  {
    ticketItemId: 12,
    title: "Da Nang Startup Night",
    eyebrow: "Founder access",
    date: "May 18, 2026",
    time: "19:00",
    venue: "Han River Hall",
    city: "Da Nang",
    category: "Conference",
    priceOriginal: 450000,
    priceFlash: 290000,
    status: "upcoming",
    stockLabel: "Opens soon",
    saleLabel: "Fixture only",
    description:
      "Talks, founder demos, and networking seats for a compact evening program.",
  },
  {
    ticketItemId: 16,
    title: "Hue Classical Evening",
    eyebrow: "Reserved seating",
    date: "Jun 06, 2026",
    time: "19:30",
    venue: "Imperial Theatre",
    city: "Hue",
    category: "Theatre",
    priceOriginal: 620000,
    priceFlash: 410000,
    status: "sold_out",
    stockLabel: "Allocation closed",
    saleLabel: "Closed fixture",
    description:
      "A small-room classical performance with reserved seats and limited inventory.",
  },
];

export function getFeaturedEvent() {
  return eventSummaries.find((event) => event.featured) ?? eventSummaries[0];
}

export function getEventSummary(ticketItemId: number) {
  return eventSummaries.find((event) => event.ticketItemId === ticketItemId);
}

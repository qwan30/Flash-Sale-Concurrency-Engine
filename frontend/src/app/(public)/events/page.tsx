import { EventCatalog } from "@/components/event-catalog";
import { eventSummaries } from "@/lib/events";

export default function EventsPage() {
  return (
    <main className="mx-auto flex w-full max-w-[1200px] flex-col gap-8 px-4 py-12 sm:px-6 lg:px-8">
      <header className="max-w-3xl">
        <p className="text-sm font-semibold uppercase tracking-[0.08em] text-[#898989]">
          Events
        </p>
        <h1 className="mt-4 font-display text-4xl font-semibold leading-[1.1] text-[#242424] sm:text-5xl">
          Pick the drop before it moves.
        </h1>
        <p className="mt-4 max-w-2xl text-base leading-7 text-[#898989]">
          A compact event board with live-sale status, stock signals, and direct access
          to the featured checkout flow for ticket item 4.
        </p>
      </header>

      <EventCatalog events={eventSummaries} />
    </main>
  );
}

import Link from "next/link";

import { ReservationJourney } from "@/components/reservation-journey";
import { UserBookingDashboard } from "@/components/user-booking-dashboard";
import { getEventSummary } from "@/lib/events";
import { Button } from "@/components/ui/button";

export default async function EventDetailPage({
  params,
}: {
  params: Promise<{ ticketItemId: string }>;
}) {
  const { ticketItemId } = await params;
  const parsedTicketItemId = Number(ticketItemId);
  const event = getEventSummary(parsedTicketItemId);

  return (
    <main className="mx-auto flex w-full max-w-[1200px] flex-col gap-8 px-4 py-12 sm:px-6 lg:px-8">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <Link
          href="/events"
          className="text-sm font-semibold text-[#898989] transition hover:text-[#242424]"
        >
          Back to events
        </Link>
        <Link
          href="/booking"
          className="rounded-full px-3 py-2 text-sm font-semibold text-[#242424] shadow-[rgba(34,42,53,0.10)_0_0_0_1px] transition hover:bg-[#f7f7f7]"
        >
          Quick booking
        </Link>
      </div>

      <ReservationJourney ticketItemId={parsedTicketItemId} event={event} />

      <details className="rounded-xl bg-[#f7f7f7] p-5">
        <summary className="cursor-pointer text-sm font-semibold text-[#242424]">
          Engineering evidence: legacy order probe
        </summary>
        <div className="mt-6">
          <UserBookingDashboard ticketItemId={parsedTicketItemId} event={event} />
        </div>
        <div className="mt-6 border-t border-black/[0.06] pt-5" data-testid="engineering-evidence-links">
          <p className="text-xs font-semibold uppercase tracking-[0.08em] text-[#898989]">
            Controlled system evidence
          </p>
          <p className="mt-2 text-sm leading-6 text-[#898989]">
            Open the benchmark and consistency tools when the customer journey needs an operator-level view.
          </p>
          <div className="mt-4 flex flex-wrap gap-2">
            <Button asChild type="button" variant="secondary" size="sm">
              <Link href="/admin/benchmark">Benchmark</Link>
            </Button>
            <Button asChild type="button" variant="secondary" size="sm">
              <Link href="/admin/consistency">Consistency</Link>
            </Button>
            <Button asChild type="button" variant="secondary" size="sm">
              <Link href="/admin/control-desk">Control desk</Link>
            </Button>
          </div>
        </div>
      </details>
    </main>
  );
}

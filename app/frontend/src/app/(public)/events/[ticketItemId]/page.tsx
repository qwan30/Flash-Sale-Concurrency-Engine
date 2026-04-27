import Link from "next/link";

import { UserBookingDashboard } from "@/components/user-booking-dashboard";
import { getEventSummary } from "@/lib/events";

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

      <UserBookingDashboard ticketItemId={parsedTicketItemId} event={event} />
    </main>
  );
}

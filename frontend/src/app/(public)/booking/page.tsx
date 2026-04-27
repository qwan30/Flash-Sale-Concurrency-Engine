import { UserBookingDashboard } from "@/components/user-booking-dashboard";
import { DEFAULT_TICKET_ID, getFeaturedEvent } from "@/lib/events";

export default function BookingPage() {
  return (
    <main className="mx-auto flex w-full max-w-[1200px] flex-col gap-8 px-4 py-12 sm:px-6 lg:px-8">
      <header className="max-w-3xl">
        <p className="text-sm font-semibold uppercase tracking-[0.08em] text-[#898989]">
          Order probe
        </p>
        <h1 className="mt-4 font-display text-4xl font-semibold leading-[1.1] text-[#242424] sm:text-5xl">
          Submit a controlled order request.
        </h1>
        <p className="mt-4 max-w-2xl text-base leading-7 text-[#898989]">
          Use the featured ticket fixture to exercise one stock deduction strategy at a time.
        </p>
      </header>

      <UserBookingDashboard ticketItemId={DEFAULT_TICKET_ID} event={getFeaturedEvent()} />
    </main>
  );
}

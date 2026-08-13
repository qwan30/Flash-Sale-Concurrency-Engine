import type {
  ReservationProcessingResponse,
  ReservationResponse,
} from "@/lib/reservation-types";

export function ReservationTimeline({
  reservation,
  processing,
}: {
  reservation: ReservationResponse | null;
  processing: ReservationProcessingResponse | null;
}) {
  const rows = [
    ["Durable state", processing?.journalState ?? reservation?.status ?? "Waiting for API state"],
    ["Outcome", processing?.status ?? reservation?.outcome ?? "Pending"],
    ["Operation", processing?.operationId ?? reservation?.operationId ?? processing?.traceId ?? "Waiting for API state"],
    ["Order", reservation?.orderId ?? "Not created"],
  ];

  return (
    <ol id="reservation-timeline" className="space-y-3" data-testid="reservation-timeline">
      {rows.map(([label, value]) => (
        <li key={label} className="flex items-start gap-3">
          <span className="mt-1 h-2.5 w-2.5 shrink-0 rounded-full bg-[#242424]" aria-hidden="true" />
          <div>
            <p className="text-xs font-semibold uppercase tracking-[0.08em] text-[#898989]">{label}</p>
            <p className="mt-1 text-sm font-semibold text-[#242424]">{value}</p>
          </div>
        </li>
      ))}
    </ol>
  );
}

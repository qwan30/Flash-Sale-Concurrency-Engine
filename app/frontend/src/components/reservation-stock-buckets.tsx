import type { InventoryResponse } from "@/lib/reservation-types";
import { formatNumber } from "@/lib/utils";

export function ReservationStockBuckets({ inventory }: { inventory: InventoryResponse | null }) {
  const buckets = [
    ["Available", inventory?.available],
    ["Reserved", inventory?.reserved],
    ["Confirmed", inventory?.confirmed],
  ] as const;

  return (
    <div className="grid gap-3 sm:grid-cols-3" data-testid="reservation-stock-buckets">
      {buckets.map(([label, value]) => (
        <div key={label} className="rounded-xl bg-[#f7f7f7] p-4">
          <p className="text-xs font-semibold uppercase tracking-[0.08em] text-[#898989]">{label}</p>
          <p className="mt-2 font-display text-3xl font-semibold text-[#242424]">
            {value === undefined ? "—" : formatNumber(value)}
          </p>
        </div>
      ))}
      <p className="sm:col-span-3 text-xs text-[#898989]">
        {inventory ? `Initial allocation: ${formatNumber(inventory.initial)}` : "Waiting for live inventory from the API"}
      </p>
    </div>
  );
}

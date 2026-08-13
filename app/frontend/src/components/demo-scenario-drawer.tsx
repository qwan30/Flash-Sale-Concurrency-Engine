import type { ReactNode } from "react";

export function DemoScenarioDrawer({ children }: { children: ReactNode }) {
  return (
    <details className="rounded-xl bg-white p-4 shadow-[rgba(34,42,53,0.10)_0_0_0_1px]">
      <summary className="cursor-pointer text-sm font-semibold text-[#242424]">
        Try another outcome
      </summary>
      <div className="mt-4 space-y-3">{children}</div>
    </details>
  );
}

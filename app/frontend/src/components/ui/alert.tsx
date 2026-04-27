import * as React from "react";

import { cn } from "@/lib/utils";

export function Alert({ className, ...props }: React.HTMLAttributes<HTMLDivElement>) {
  return (
    <div
      role="status"
      className={cn(
        "rounded-lg bg-[#f7f7f7] p-3 text-sm leading-6 text-[#242424] shadow-[rgba(34,42,53,0.08)_0_0_0_1px]",
        className,
      )}
      {...props}
    />
  );
}

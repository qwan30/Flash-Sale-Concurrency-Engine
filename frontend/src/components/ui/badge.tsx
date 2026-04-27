import { cva, type VariantProps } from "class-variance-authority";
import * as React from "react";

import { cn } from "@/lib/utils";

const badgeVariants = cva(
  "inline-flex items-center rounded-full px-2.5 py-1 text-xs font-semibold leading-none",
  {
    variants: {
      variant: {
        default: "bg-[#242424] text-white",
        secondary: "bg-[#f5f5f5] text-[#242424]",
        success: "bg-[#eef8f1] text-[#20723b]",
        warning: "bg-[#f8f5ee] text-[#6b5b2a]",
        outline: "bg-white text-[#242424] shadow-[rgba(34,42,53,0.10)_0_0_0_1px]",
      },
    },
    defaultVariants: {
      variant: "default",
    },
  },
);

export interface BadgeProps
  extends React.HTMLAttributes<HTMLSpanElement>,
    VariantProps<typeof badgeVariants> {}

export function Badge({ className, variant, ...props }: BadgeProps) {
  return <span className={cn(badgeVariants({ variant, className }))} {...props} />;
}

import type { OrderStrategy } from "@/lib/types";

export const strategyDetails: Record<
  OrderStrategy,
  { label: string; summary: string; warning?: boolean }
> = {
  UNSAFE_DB: {
    label: "Unsafe DB",
    summary: "Demo-only baseline that may oversell under load.",
    warning: true,
  },
  CONDITIONAL_DB: {
    label: "DB guarded",
    summary: "Uses a conditional stock update as the safe baseline.",
  },
  REDIS_LUA: {
    label: "Redis Lua",
    summary: "Uses Redis as a fast pre-deduction gate.",
  },
  REDIS_LUA_WITH_COMPENSATION: {
    label: "Redis compensation",
    summary: "Restores Redis stock when DB/order write fails.",
  },
};

import { clsx, type ClassValue } from "clsx";
import { twMerge } from "tailwind-merge";

export function cn(...inputs: ClassValue[]) {
  return twMerge(clsx(inputs));
}

export function formatNumber(value: number | null | undefined) {
  if (value === null || value === undefined || Number.isNaN(value)) {
    return "n/a";
  }

  return new Intl.NumberFormat("en-US").format(value);
}

export function formatCurrency(value: number | string | null | undefined) {
  if (value === null || value === undefined || value === "") {
    return "n/a";
  }

  const numeric = typeof value === "string" ? Number(value) : value;

  return new Intl.NumberFormat("en-US", {
    maximumFractionDigits: 0,
    style: "currency",
    currency: "VND",
  }).format(Number.isNaN(numeric) ? 0 : numeric);
}

export function formatDateTime(value: string | null | undefined) {
  if (!value) {
    return "n/a";
  }

  const date = new Date(value);

  if (Number.isNaN(date.getTime())) {
    return value;
  }

  return new Intl.DateTimeFormat("en-US", {
    dateStyle: "medium",
    timeStyle: "short",
  }).format(date);
}

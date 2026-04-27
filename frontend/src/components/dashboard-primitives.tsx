"use client";

import type { ColumnDef } from "@tanstack/react-table";
import {
  flexRender,
  getCoreRowModel,
  useReactTable,
} from "@tanstack/react-table";

import { cn } from "@/lib/utils";

import { Label } from "@/components/ui/label";
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from "@/components/ui/table";

export function MetricCard({
  icon: Icon,
  label,
  value,
  detail,
}: {
  icon: React.ComponentType<{ className?: string }>;
  label: string;
  value: string;
  detail: string;
}) {
  return (
    <div className="rounded-xl bg-white p-4 shadow-[rgba(19,19,22,0.7)_0_1px_5px_-4px,rgba(34,42,53,0.08)_0_0_0_1px,rgba(34,42,53,0.05)_0_4px_8px_0]">
      <div className="flex items-center justify-between gap-3">
        <span className="text-xs font-semibold uppercase tracking-[0.08em] text-[#898989]">
          {label}
        </span>
        <span className="rounded-full bg-[#f5f5f5] p-2">
          <Icon className="h-4 w-4 text-[#242424]" />
        </span>
      </div>
      <div className="mt-4 font-display text-3xl font-semibold leading-none text-[#242424]">
        {value}
      </div>
      <p className="mt-2 text-sm text-[#898989]">{detail}</p>
    </div>
  );
}

export function Field({
  label,
  error,
  children,
}: {
  label: string;
  error?: string;
  children: React.ReactNode;
}) {
  return (
    <div className="space-y-2">
      <Label>{label}</Label>
      {children}
      {error ? <p className="text-xs font-medium text-[#6b2a2a]">{error}</p> : null}
    </div>
  );
}

export function InfoBlock({
  label,
  value,
  compact = false,
}: {
  label: string;
  value: React.ReactNode;
  compact?: boolean;
}) {
  return (
    <div className={cn("rounded-lg bg-[#f7f7f7] p-4", compact && "p-3")}>
      <div className="text-xs font-semibold uppercase tracking-[0.08em] text-[#898989]">
        {label}
      </div>
      <div className="mt-2 break-words text-sm font-semibold leading-6 text-[#242424]">
        {value}
      </div>
    </div>
  );
}

export function DataTable<TData>({
  columns,
  data,
  empty,
}: {
  columns: ColumnDef<TData>[];
  data: TData[];
  empty: string;
}) {
  // TanStack Table intentionally returns stable table helpers; React Compiler flags it conservatively.
  // eslint-disable-next-line react-hooks/incompatible-library
  const table = useReactTable({
    data,
    columns,
    getCoreRowModel: getCoreRowModel(),
  });

  return (
    <Table>
      <TableHeader>
        {table.getHeaderGroups().map((headerGroup) => (
          <TableRow key={headerGroup.id}>
            {headerGroup.headers.map((header) => (
              <TableHead key={header.id}>
                {header.isPlaceholder
                  ? null
                  : flexRender(header.column.columnDef.header, header.getContext())}
              </TableHead>
            ))}
          </TableRow>
        ))}
      </TableHeader>
      <TableBody>
        {table.getRowModel().rows.length ? (
          table.getRowModel().rows.map((row) => (
            <TableRow key={row.id}>
              {row.getVisibleCells().map((cell) => (
                <TableCell key={cell.id}>
                  {flexRender(cell.column.columnDef.cell, cell.getContext())}
                </TableCell>
              ))}
            </TableRow>
          ))
        ) : (
          <TableRow>
            <TableCell colSpan={columns.length} className="py-6 text-center text-[#898989]">
              {empty}
            </TableCell>
          </TableRow>
        )}
      </TableBody>
    </Table>
  );
}

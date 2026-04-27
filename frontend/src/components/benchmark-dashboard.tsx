"use client";

import { BarChart3, CheckCircle2, Gauge, TimerReset } from "lucide-react";

import { BenchmarkChart } from "@/components/benchmark-chart";
import { MetricCard } from "@/components/dashboard-primitives";
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from "@/components/ui/table";
import { benchmarkRows } from "@/lib/benchmark-data";
import { strategyDetails } from "@/lib/strategy";
import { formatNumber } from "@/lib/utils";

const fastestRow = benchmarkRows.reduce((best, row) =>
  row.throughput > best.throughput ? row : best,
);

export function BenchmarkDashboard() {
  return (
    <main className="mx-auto flex w-full max-w-[1200px] flex-col gap-8 px-4 py-8 sm:px-6 lg:px-8">
      <header className="max-w-3xl border-b border-black/[0.06] pb-6">
        <p className="text-sm font-semibold uppercase tracking-[0.08em] text-[#898989]">
          Benchmark report
        </p>
        <h1 className="mt-4 font-display text-4xl font-semibold leading-[1.1] text-[#242424] sm:text-5xl">
          Throughput, latency, correctness
        </h1>
        <p className="mt-3 max-w-2xl text-base leading-7 text-[#898989]">
          Dedicated proof surface for the lab run: 5,000 requests, 100 concurrent users,
          and stock capped at 1,000.
        </p>
      </header>

      <section className="grid gap-4 md:grid-cols-2 xl:grid-cols-4">
        <MetricCard
          icon={Gauge}
          label="Best throughput"
          value={`${fastestRow.throughput} req/s`}
          detail={strategyDetails[fastestRow.strategy].label}
        />
        <MetricCard
          icon={TimerReset}
          label="Best average"
          value={`${fastestRow.averageMs} ms`}
          detail="Redis compensation path"
        />
        <MetricCard
          icon={CheckCircle2}
          label="Oversold"
          value="0"
          detail="All safe strategies"
        />
        <MetricCard
          icon={BarChart3}
          label="Requests"
          value={formatNumber(fastestRow.totalRequests)}
          detail={`${fastestRow.concurrency} concurrent users`}
        />
      </section>

      <section className="grid gap-5 xl:grid-cols-[0.9fr_1.1fr]">
        <div className="rounded-xl bg-white p-5 shadow-[rgba(19,19,22,0.7)_0_1px_5px_-4px,rgba(34,42,53,0.08)_0_0_0_1px,rgba(34,42,53,0.05)_0_4px_8px_0]">
          <div className="mb-4">
            <h2 className="font-display text-xl font-semibold leading-tight text-[#242424]">
              Strategy comparison
            </h2>
            <p className="mt-1 text-sm leading-6 text-[#898989]">
              Throughput and average latency from the recorded JMeter run.
            </p>
          </div>
          <div className="h-[320px] w-full">
            <BenchmarkChart />
          </div>
        </div>

        <div className="rounded-xl bg-white p-5 shadow-[rgba(19,19,22,0.7)_0_1px_5px_-4px,rgba(34,42,53,0.08)_0_0_0_1px,rgba(34,42,53,0.05)_0_4px_8px_0]">
          <div className="mb-4">
            <h2 className="font-display text-xl font-semibold leading-tight text-[#242424]">
              Result rows
            </h2>
            <p className="mt-1 text-sm leading-6 text-[#898989]">
              Correctness columns stay visible beside throughput and latency.
            </p>
          </div>
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead>Strategy</TableHead>
                <TableHead>Req/s</TableHead>
                <TableHead>Avg ms</TableHead>
                <TableHead>P95</TableHead>
                <TableHead>P99</TableHead>
                <TableHead>Orders</TableHead>
                <TableHead>Oversold</TableHead>
                <TableHead>Drift</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {benchmarkRows.map((row) => (
                <TableRow key={row.strategy}>
                  <TableCell>{strategyDetails[row.strategy].label}</TableCell>
                  <TableCell>{row.throughput}</TableCell>
                  <TableCell>{row.averageMs}</TableCell>
                  <TableCell>{formatNumber(row.p95Ms)}</TableCell>
                  <TableCell>{formatNumber(row.p99Ms)}</TableCell>
                  <TableCell>{formatNumber(row.successOrders)}</TableCell>
                  <TableCell>{row.oversoldCount}</TableCell>
                  <TableCell>{row.redisDbInconsistencyCount}</TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </div>
      </section>
    </main>
  );
}

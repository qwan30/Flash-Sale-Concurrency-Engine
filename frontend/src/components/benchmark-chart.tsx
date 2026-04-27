"use client";

import {
  Bar,
  BarChart,
  CartesianGrid,
  Legend,
  Tooltip,
  XAxis,
  YAxis,
} from "recharts";
import { useEffect, useRef, useState } from "react";

import { benchmarkRows } from "@/lib/benchmark-data";
import type { OrderStrategy } from "@/lib/types";

const strategyLabels: Record<OrderStrategy, string> = {
  UNSAFE_DB: "Unsafe DB",
  CONDITIONAL_DB: "DB guarded",
  REDIS_LUA: "Redis Lua",
  REDIS_LUA_WITH_COMPENSATION: "Redis compensation",
};

export function BenchmarkChart() {
  const containerRef = useRef<HTMLDivElement | null>(null);
  const [width, setWidth] = useState(0);

  useEffect(() => {
    const node = containerRef.current;

    if (!node) {
      return;
    }

    const updateWidth = () => {
      setWidth(Math.max(320, Math.floor(node.getBoundingClientRect().width)));
    };

    updateWidth();
    const resizeObserver = new ResizeObserver(updateWidth);
    resizeObserver.observe(node);

    return () => resizeObserver.disconnect();
  }, []);

  return (
    <div ref={containerRef} className="h-full min-h-[300px] w-full overflow-x-auto">
      {width > 0 ? (
      <BarChart width={width} height={300} data={benchmarkRows} margin={{ left: 8, right: 8 }}>
        <CartesianGrid stroke="#efefef" vertical={false} />
        <XAxis
          dataKey="strategy"
          tick={{ fill: "#898989", fontSize: 11 }}
          tickFormatter={(value) => strategyLabels[value as OrderStrategy]}
        />
        <YAxis tick={{ fill: "#898989", fontSize: 12 }} />
        <Tooltip
          cursor={{ fill: "#f5f5f5" }}
          contentStyle={{
            border: "none",
            borderRadius: 12,
            boxShadow:
              "rgba(19,19,22,0.7) 0px 1px 5px -4px, rgba(34,42,53,0.08) 0px 0px 0px 1px, rgba(34,42,53,0.05) 0px 4px 8px 0",
          }}
        />
        <Legend />
        <Bar dataKey="throughput" name="Throughput req/s" fill="#242424" radius={6} />
        <Bar dataKey="averageMs" name="Avg latency ms" fill="#c7c7c7" radius={6} />
      </BarChart>
      ) : (
        <div className="h-[300px] rounded-lg bg-[#f7f7f7]" />
      )}
    </div>
  );
}

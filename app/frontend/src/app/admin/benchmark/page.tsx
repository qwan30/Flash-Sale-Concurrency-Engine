"use client";

import { BenchmarkDashboard } from "@/components/benchmark-dashboard";
import { useState, useEffect } from "react";
import { Card, CardHeader, CardTitle, CardContent } from "@/components/ui/card";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { LineChart, Line, XAxis, YAxis, CartesianGrid, Tooltip, Legend, ResponsiveContainer } from "recharts";

interface MetricData {
  time: string;
  throughput: number;
  success: number;
  failed: number;
  elapsedMs: number;
}

function LiveBenchmarkPanel() {
  const [strategy, setStrategy] = useState("REDIS_LUA_WITH_COMPENSATION");
  const [threads, setThreads] = useState("100");
  const [totalRequests, setTotalRequests] = useState("5000");
  const [isBenchmarking, setIsBenchmarking] = useState(false);
  const [metrics, setMetrics] = useState<MetricData[]>([]);

  const startBenchmark = async () => {
    setIsBenchmarking(true);
    setMetrics([]);
    
    try {
      const res = await fetch("/api/benchmark/start", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({
          strategy,
          threads,
          totalRequests
        })
      });

      if (!res.ok) {
        throw new Error("Failed to start benchmark");
      }

      const eventSource = new EventSource("/api/benchmark/metrics");
      
      eventSource.onmessage = (event) => {
        const data = JSON.parse(event.data);
        setMetrics((prev) => [...prev, data].slice(-20)); // Keep last 20 points
      };

      eventSource.onerror = () => {
        eventSource.close();
        setIsBenchmarking(false);
      };

    } catch (err) {
      console.error(err);
      setIsBenchmarking(false);
    }
  };

  return (
    <main className="mx-auto flex w-full max-w-[1200px] flex-col gap-8 px-4 pt-8 sm:px-6 lg:px-8">
      <section className="grid gap-5 lg:grid-cols-2">
        <Card>
          <CardHeader>
            <CardTitle>Trigger Benchmark</CardTitle>
          </CardHeader>
          <CardContent>
            <div className="flex flex-col gap-4">
              <div>
                <label className="text-sm font-medium">Strategy</label>
                <select
                  value={strategy}
                  onChange={(e) => setStrategy(e.target.value)}
                  className="flex h-10 w-full rounded-md border border-input bg-background px-3 py-2 text-sm ring-offset-background"
                >
                  <option value="PESSIMISTIC_LOCK">PESSIMISTIC_LOCK</option>
                  <option value="OPTIMISTIC_LOCK">OPTIMISTIC_LOCK</option>
                  <option value="REDIS_LUA_ONLY">REDIS_LUA_ONLY</option>
                  <option value="REDIS_LUA_WITH_COMPENSATION">REDIS_LUA_WITH_COMPENSATION</option>
                </select>
              </div>
              <div className="flex gap-4">
                <div className="flex-1">
                  <label className="text-sm font-medium">Threads</label>
                  <Input 
                    type="number" 
                    value={threads} 
                    onChange={(e) => setThreads(e.target.value)} 
                  />
                </div>
                <div className="flex-1">
                  <label className="text-sm font-medium">Total Requests</label>
                  <Input 
                    type="number" 
                    value={totalRequests} 
                    onChange={(e) => setTotalRequests(e.target.value)} 
                  />
                </div>
              </div>
              <Button 
                onClick={startBenchmark} 
                disabled={isBenchmarking}
                className="mt-2"
              >
                {isBenchmarking ? "Running Benchmark..." : "Start Benchmark"}
              </Button>
            </div>
          </CardContent>
        </Card>

        <Card>
          <CardHeader>
            <CardTitle>Real-time Metrics</CardTitle>
          </CardHeader>
          <CardContent>
            <div className="h-[250px] w-full">
              {metrics.length > 0 ? (
                <ResponsiveContainer width="100%" height="100%">
                  <LineChart data={metrics}>
                    <CartesianGrid strokeDasharray="3 3" />
                    <XAxis 
                      dataKey="time" 
                      tickFormatter={(time) => new Date(time).toLocaleTimeString()}
                    />
                    <YAxis yAxisId="left" />
                    <YAxis yAxisId="right" orientation="right" />
                    <Tooltip 
                      labelFormatter={(time) => new Date(time).toLocaleTimeString()}
                    />
                    <Legend />
                    <Line 
                      yAxisId="left"
                      type="monotone" 
                      dataKey="throughput" 
                      stroke="#8884d8" 
                      name="Throughput (req/s)" 
                      isAnimationActive={false}
                    />
                    <Line 
                      yAxisId="right"
                      type="monotone" 
                      dataKey="success" 
                      stroke="#82ca9d" 
                      name="Success" 
                      isAnimationActive={false}
                    />
                  </LineChart>
                </ResponsiveContainer>
              ) : (
                <div className="flex h-full items-center justify-center text-muted-foreground">
                  No active benchmark or waiting for metrics...
                </div>
              )}
            </div>
          </CardContent>
        </Card>
      </section>
    </main>
  );
}

export default function AdminBenchmarkPage() {
  return (
    <div className="flex flex-col gap-8 pb-8">
      <LiveBenchmarkPanel />
      <BenchmarkDashboard />
    </div>
  );
}

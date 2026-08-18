"use client";

import Link from "next/link";
import { AdminControlDesk } from "@/components/admin-control-desk";
import { ChaosResilienceMatrix } from "@/components/chaos-resilience-matrix";
import { OutboxKafkaFeed } from "@/components/outbox-kafka-feed";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Tabs, TabsContent, TabsList, TabsTrigger } from "@/components/ui/tabs";
import { AlertOctagon, Layers, Share2, Swords, Zap } from "lucide-react";

export default function ControlDeskPage() {
  return (
    <main className="mx-auto flex w-full max-w-[1200px] flex-col gap-6 px-4 py-8 sm:px-6 lg:px-8">
      {/* Top Header */}
      <div className="flex flex-wrap items-center justify-between gap-4 border-b border-[#f0f0f0] pb-5">
        <div>
          <div className="flex items-center gap-2">
            <Badge className="bg-[#242424] text-white text-xs">Operator Console</Badge>
            <span className="text-xs text-[#898989]">Flash-Sale Concurrency Lab</span>
          </div>
          <h1 className="mt-2 font-display text-3xl font-bold text-[#242424]">
            System Control Desk & Diagnostics
          </h1>
          <p className="mt-1 text-xs text-[#666666]">
            Manage stock lifecycle, execute chaos fault injections, and audit transactional outbox events.
          </p>
        </div>

        <div className="flex items-center gap-2">
          <Button asChild size="sm" className="bg-[#242424] text-white">
            <Link href="/admin/strategy-arena">
              <Swords className="mr-1.5 h-3.5 w-3.5 text-amber-400" />
              Strategy Arena
            </Link>
          </Button>
          <Button asChild variant="secondary" size="sm">
            <Link href="/admin/consistency">
              <Zap className="mr-1.5 h-3.5 w-3.5 text-emerald-600" />
              Drift Audit
            </Link>
          </Button>
        </div>
      </div>

      {/* Tabs Layout */}
      <Tabs defaultValue="stock-ops" className="w-full">
        <TabsList className="grid w-full grid-cols-3 max-w-lg bg-[#f0f0f0] p-1">
          <TabsTrigger value="stock-ops" className="text-xs">
            <Layers className="mr-1.5 h-3.5 w-3.5" />
            Stock & Warmup
          </TabsTrigger>
          <TabsTrigger value="chaos-lab" className="text-xs">
            <AlertOctagon className="mr-1.5 h-3.5 w-3.5" />
            Chaos Matrix
          </TabsTrigger>
          <TabsTrigger value="event-stream" className="text-xs">
            <Share2 className="mr-1.5 h-3.5 w-3.5" />
            Outbox & Kafka
          </TabsTrigger>
        </TabsList>

        {/* Tab 1: Core Stock Operations */}
        <TabsContent value="stock-ops" className="space-y-6">
          <AdminControlDesk />
        </TabsContent>

        {/* Tab 2: Chaos & Resilience Lab */}
        <TabsContent value="chaos-lab" className="space-y-6">
          <ChaosResilienceMatrix />
        </TabsContent>

        {/* Tab 3: Transactional Outbox & Kafka Stream */}
        <TabsContent value="event-stream" className="space-y-6">
          <OutboxKafkaFeed />
        </TabsContent>
      </Tabs>
    </main>
  );
}

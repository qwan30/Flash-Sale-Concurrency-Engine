"use client";

import { AdminControlDesk } from "@/components/admin-control-desk";
import { useState } from "react";
import { Card, CardHeader, CardTitle, CardContent } from "@/components/ui/card";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";

export default function ControlDeskPage() {
  const [strategy, setStrategy] = useState("REDIS_LUA_WITH_COMPENSATION");
  const [dataSeed, setDataSeed] = useState("1000");

  return (
    <div className="flex flex-col gap-8">
      <AdminControlDesk />

      <main className="mx-auto flex w-full max-w-[1200px] flex-col gap-8 px-4 pb-8 sm:px-6 lg:px-8">
        <section className="grid gap-5 lg:grid-cols-2">
          <Card>
            <CardHeader>
              <CardTitle>Strategy Configuration</CardTitle>
            </CardHeader>
            <CardContent>
              <div className="flex flex-col gap-4">
                <label className="text-sm font-medium">Select Strategy</label>
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
                <div className="text-sm text-muted-foreground">
                  Current strategy for next benchmark run: {strategy}
                </div>
              </div>
            </CardContent>
          </Card>

          <Card>
            <CardHeader>
              <CardTitle>Data Seeding</CardTitle>
            </CardHeader>
            <CardContent>
              <div className="flex flex-col gap-4">
                <label className="text-sm font-medium">Seed Additional Data</label>
                <div className="flex gap-2">
                  <Input
                    type="number"
                    value={dataSeed}
                    onChange={(e) => setDataSeed(e.target.value)}
                    placeholder="Records to seed"
                  />
                  <Button onClick={() => alert(`Seeding ${dataSeed} records...`)}>
                    Seed Data
                  </Button>
                </div>
              </div>
            </CardContent>
          </Card>
        </section>
      </main>
    </div>
  );
}

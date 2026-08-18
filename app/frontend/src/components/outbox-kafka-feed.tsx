"use client";

import { useState } from "react";
import { Share2 } from "lucide-react";
import { Badge } from "@/components/ui/badge";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";

export type OutboxFeedItem = {
  id: string;
  aggregateType: string;
  aggregateId: string;
  eventType: string;
  payload: Record<string, unknown>;
  status: "PENDING" | "PUBLISHED" | "FAILED";
  createdAt: string;
  publishedAt?: string;
  attemptCount: number;
  kafkaTopic: string;
  partition: number;
  offset: number;
};

const INITIAL_FEED: OutboxFeedItem[] = [
  {
    id: "evt-001a-88bc-99df",
    aggregateType: "Reservation",
    aggregateId: "3fa85f64-5717-4562-b3fc-2c963f66afa6",
    eventType: "RESERVATION_CREATED",
    payload: {
      reservationId: "3fa85f64-5717-4562-b3fc-2c963f66afa6",
      ticketItemId: 4,
      demoActorId: "actor-001",
      quantity: 1,
      stockAfter: 999,
    },
    status: "PUBLISHED",
    createdAt: new Date(Date.now() - 3200).toISOString(),
    publishedAt: new Date(Date.now() - 2100).toISOString(),
    attemptCount: 1,
    kafkaTopic: "flashsale.orders",
    partition: 1,
    offset: 1042,
  },
  {
    id: "evt-002b-99cd-00ea",
    aggregateType: "Reservation",
    aggregateId: "8ab94f12-1111-4444-8888-abcdef123456",
    eventType: "RESERVATION_CONFIRMED",
    payload: {
      orderId: "ord-202604-0091",
      reservationId: "8ab94f12-1111-4444-8888-abcdef123456",
      ticketItemId: 4,
      quantity: 2,
    },
    status: "PUBLISHED",
    createdAt: new Date(Date.now() - 1500).toISOString(),
    publishedAt: new Date(Date.now() - 400).toISOString(),
    attemptCount: 1,
    kafkaTopic: "flashsale.orders",
    partition: 0,
    offset: 1043,
  },
  {
    id: "evt-003c-11de-22fb",
    aggregateType: "Order",
    aggregateId: "ORD2026040099",
    eventType: "ORDER_CREATED",
    payload: {
      orderNumber: "ORD2026040099",
      strategy: "REDIS_LUA_WITH_COMPENSATION",
      ticketItemId: 4,
      userId: 1042,
      quantity: 1,
    },
    status: "PUBLISHED",
    createdAt: new Date(Date.now() - 800).toISOString(),
    publishedAt: new Date(Date.now() - 100).toISOString(),
    attemptCount: 1,
    kafkaTopic: "flashsale.orders",
    partition: 2,
    offset: 1044,
  },
];

export function OutboxKafkaFeed() {
  const [feed] = useState<OutboxFeedItem[]>(INITIAL_FEED);
  const [selectedEvent, setSelectedEvent] = useState<OutboxFeedItem>(INITIAL_FEED[0]);

  return (
    <Card className="border-[#e5e5e5] bg-white shadow-sm">
      <CardHeader className="border-b border-[#f0f0f0] pb-4">
        <div className="flex flex-wrap items-center justify-between gap-4">
          <div className="space-y-1">
            <div className="flex items-center gap-2">
              <Badge variant="outline" className="border-cyan-200 bg-cyan-50 text-cyan-700">
                <Share2 className="mr-1 h-3 w-3" />
                Transactional Outbox & Kafka
              </Badge>
              <span className="text-xs text-[#898989]">Guaranteed Event Publishing</span>
            </div>
            <CardTitle className="font-display text-xl font-bold text-[#242424]">
              Live Outbox Table & Event Stream Log
            </CardTitle>
            <CardDescription className="text-xs text-[#666666]">
              Dual-write safety: Business entities and domain events commit in the same ACID transaction before pushing to Kafka.
            </CardDescription>
          </div>

          <Badge variant="secondary" className="font-mono text-xs">
            Topic: flashsale.orders (KRaft)
          </Badge>
        </div>
      </CardHeader>

      <CardContent className="space-y-4 pt-5">
        <div className="grid gap-5 lg:grid-cols-[1.3fr_1fr]">
          {/* Outbox Table Stream */}
          <div className="space-y-2">
            <p className="text-[11px] font-semibold uppercase tracking-wider text-[#898989]">
              Recent Outbox Events (<code className="font-mono">outbox_event</code> table)
            </p>
            <div className="space-y-2">
              {feed.map((evt) => {
                const isSelected = selectedEvent.id === evt.id;

                return (
                  <div
                    key={evt.id}
                    onClick={() => setSelectedEvent(evt)}
                    className={`flex cursor-pointer items-center justify-between rounded-lg border p-3 transition-all ${
                      isSelected
                        ? "border-[#242424] bg-[#fafafa] shadow-sm ring-1 ring-[#242424]"
                        : "border-[#ebebeb] bg-white hover:border-[#cccccc]"
                    }`}
                  >
                    <div className="space-y-1">
                      <div className="flex items-center gap-2">
                        <Badge
                          variant="secondary"
                          className="bg-[#242424] text-[10px] font-mono text-white"
                        >
                          {evt.eventType}
                        </Badge>
                        <span className="font-mono text-xs font-semibold text-[#242424]">
                          {evt.aggregateType} #{evt.aggregateId.slice(0, 8)}...
                        </span>
                      </div>
                      <p className="text-[11px] text-[#898989]">
                        Topic: <span className="font-mono text-[#242424]">{evt.kafkaTopic}</span> (P{evt.partition}:#{evt.offset})
                      </p>
                    </div>

                    <div className="text-right">
                      <Badge
                        variant="outline"
                        className={`text-[10px] ${
                          evt.status === "PUBLISHED"
                            ? "border-emerald-300 bg-emerald-50 text-emerald-700 font-semibold"
                            : "border-amber-300 bg-amber-50 text-amber-700"
                        }`}
                      >
                        {evt.status}
                      </Badge>
                      <p className="mt-1 font-mono text-[10px] text-[#898989]">
                        {new Date(evt.createdAt).toLocaleTimeString()}
                      </p>
                    </div>
                  </div>
                );
              })}
            </div>
          </div>

          {/* Event JSON Payload Inspector */}
          <div className="flex flex-col justify-between rounded-xl border border-[#e0e0e0] bg-[#1a1a1a] p-4 text-white">
            <div>
              <div className="flex items-center justify-between border-b border-neutral-800 pb-2">
                <span className="font-mono text-xs text-neutral-400">
                  Event: {selectedEvent.eventType}
                </span>
                <span className="font-mono text-[10px] text-emerald-400">
                  acks=all verified
                </span>
              </div>

              <div className="mt-3 space-y-1.5 text-xs text-neutral-300">
                <p>
                  <span className="text-neutral-500">Event ID:</span>{" "}
                  <code className="font-mono text-emerald-300">{selectedEvent.id}</code>
                </p>
                <p>
                  <span className="text-neutral-500">Aggregate ID:</span>{" "}
                  <code className="font-mono text-neutral-200">{selectedEvent.aggregateId}</code>
                </p>
                <p>
                  <span className="text-neutral-500">Attempts:</span>{" "}
                  <span className="font-mono text-white">{selectedEvent.attemptCount} / 5</span>
                </p>
              </div>

              <p className="mt-3 text-[11px] font-semibold text-neutral-400">Event Payload:</p>
              <pre className="mt-1 max-h-40 overflow-x-auto rounded bg-black/60 p-2.5 font-mono text-[11px] leading-tight text-emerald-400">
                {JSON.stringify(selectedEvent.payload, null, 2)}
              </pre>
            </div>

            <div className="mt-3 border-t border-neutral-800 pt-2 text-[10px] text-neutral-400">
              ⚡ OutboxPublishScheduler polls every 1s, pushing events reliably to Kafka broker.
            </div>
          </div>
        </div>
      </CardContent>
    </Card>
  );
}

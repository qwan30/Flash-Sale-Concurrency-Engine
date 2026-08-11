export type ReservationStatus = "RESERVED" | "CONFIRMED" | "RELEASED" | "EXPIRED";

export type ReservationStateResponse = {
  reservationId: string;
  operationId: string | null;
  ticketItemId: number;
  demoActorId: string;
  quantity: number;
  status: ReservationStatus;
  expiresAt: string | null;
  terminalAt: string | null;
  orderId: string | null;
  outcome: string | null;
  resultCode: string | null;
  stockAfter: number | null;
};

export type ReservationRejectedReplayResponse = {
  reservationId: string;
  operationId: string;
  ticketItemId: null;
  demoActorId: null;
  quantity: null;
  status: null;
  expiresAt: null;
  terminalAt: null;
  orderId: null;
  outcome: "REPLAYED";
  resultCode: string;
  stockAfter: number | null;
};

export type ReservationResponse = ReservationStateResponse | ReservationRejectedReplayResponse;

export type ReservationProcessingResponse = {
  reservationId: string;
  operationId: string;
  status: "PROCESSING";
  journalState: string;
  retryAfterSeconds: number;
  traceId: string;
};

export type InventoryResponse = {
  ticketItemId: number;
  initial: number;
  available: number;
  reserved: number;
  confirmed: number;
};

export type ReservationErrorResponse = {
  code: string;
  message: string;
  retryable: boolean;
  traceId: string;
  stockAfter: number | null;
};

export type ReservationCreateRequest = {
  ticketItemId: number;
  quantity: number;
  idempotencyKey: string;
};

export type ReservationHttpResult<T> = {
  status: number;
  body: T;
};

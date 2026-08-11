import type {
  InventoryResponse,
  ReservationCreateRequest,
  ReservationErrorResponse,
  ReservationHttpResult,
  ReservationProcessingResponse,
  ReservationRejectedReplayResponse,
  ReservationResponse,
  ReservationStateResponse,
} from "./reservation-types";

const ACTOR_STORAGE_KEY = "flashsale.demoActorId";
const MAX_ERROR_CODE_LENGTH = 64;
const MAX_ERROR_MESSAGE_LENGTH = 256;
const MAX_TRACE_ID_LENGTH = 128;
const MAX_RESPONSE_BODY_BYTES = 64 * 1024;
const UUID_PATTERN = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;
const ERROR_CODE_PATTERN = /^[A-Z][A-Z0-9_]{0,63}$/;
const RESERVATION_STATUSES = new Set(["RESERVED", "CONFIRMED", "RELEASED", "EXPIRED"]);
const PROCESSING_JOURNAL_STATES = new Set([
  "RECEIVED",
  "REDIS_APPLYING",
  "REDIS_APPLIED",
  "COMPENSATION_PENDING",
  "MIRROR_PENDING",
]);
type JsonRecord = Record<string, unknown>;

export class ReservationClientError extends Error {
  readonly status: number;
  readonly details: ReservationErrorResponse | null;

  constructor(status: number, details: ReservationErrorResponse | null) {
    super(details?.message ?? `Reservation request failed with HTTP ${status}`);
    this.name = "ReservationClientError";
    this.status = status;
    this.details = details;
  }
}

export function shouldStopReservationPolling(error: unknown): boolean {
  if (!(error instanceof ReservationClientError)) {
    return false;
  }
  return error.details?.code === "REPAIR_REQUIRED" || error.details?.retryable === false;
}

function isJsonRecord(value: unknown): value is JsonRecord {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

function isNullableString(value: unknown, maxLength = MAX_ERROR_MESSAGE_LENGTH): value is string | null {
  return value === null || (typeof value === "string" && value.length <= maxLength);
}

function isNullableNonNegativeNumber(value: unknown): value is number | null {
  return value === null || (typeof value === "number" && Number.isFinite(value) && value >= 0);
}

function isPositiveInteger(value: unknown): value is number {
  return typeof value === "number" && Number.isInteger(value) && value > 0;
}

function isReservationQuantity(value: unknown): value is number {
  return typeof value === "number" && Number.isInteger(value) && value >= 1 && value <= 4;
}

function isUuid(value: unknown): value is string {
  return typeof value === "string" && UUID_PATTERN.test(value);
}

function isBoundedErrorCode(value: unknown): value is string {
  return typeof value === "string" && ERROR_CODE_PATTERN.test(value);
}

function assertUuid(value: string, label: string): void {
  if (!isUuid(value)) {
    throw new Error(`${label} must be a UUID`);
  }
}

function isReservationErrorResponse(value: unknown): value is ReservationErrorResponse {
  if (!isJsonRecord(value)) {
    return false;
  }
  return isBoundedErrorCode(value.code)
    && typeof value.message === "string"
    && value.message.length <= MAX_ERROR_MESSAGE_LENGTH
    && typeof value.retryable === "boolean"
    && typeof value.traceId === "string"
    && value.traceId.length <= MAX_TRACE_ID_LENGTH
    && isNullableNonNegativeNumber(value.stockAfter);
}

function isReservationStateResponse(
  value: unknown,
  allowMissingOperationId = false,
): value is ReservationStateResponse {
  if (!isJsonRecord(value)) {
    return false;
  }
  return isUuid(value.reservationId)
    && (isUuid(value.operationId) || (allowMissingOperationId && value.operationId === null))
    && isPositiveInteger(value.ticketItemId)
    && isUuid(value.demoActorId)
    && isReservationQuantity(value.quantity)
    && typeof value.status === "string"
    && RESERVATION_STATUSES.has(value.status)
    && isNullableString(value.expiresAt)
    && isNullableString(value.terminalAt)
    && isNullableString(value.orderId)
    && isNullableString(value.outcome)
    && isNullableString(value.resultCode)
    && isNullableNonNegativeNumber(value.stockAfter);
}

function isRejectedReplayResponse(value: unknown): value is ReservationRejectedReplayResponse {
  if (!isJsonRecord(value)) {
    return false;
  }
  return isUuid(value.reservationId)
    && isUuid(value.operationId)
    && value.ticketItemId === null
    && value.demoActorId === null
    && value.quantity === null
    && value.status === null
    && value.expiresAt === null
    && value.terminalAt === null
    && value.orderId === null
    && value.outcome === "REPLAYED"
    && isBoundedErrorCode(value.resultCode)
    && isNullableNonNegativeNumber(value.stockAfter);
}

function isReservationResponse(
  value: unknown,
  allowMissingOperationId = false,
  allowRejectedReplay = false,
): value is ReservationResponse {
  return isReservationStateResponse(value, allowMissingOperationId)
    || (allowRejectedReplay && isRejectedReplayResponse(value));
}

function isProcessingResponse(value: unknown): value is ReservationProcessingResponse {
  if (!isJsonRecord(value)) {
    return false;
  }
  return isUuid(value.reservationId)
    && isUuid(value.operationId)
    && value.status === "PROCESSING"
    && typeof value.journalState === "string"
    && value.journalState.length > 0
    && value.journalState.length <= MAX_ERROR_CODE_LENGTH
    && PROCESSING_JOURNAL_STATES.has(value.journalState)
    && value.journalState !== "REPAIR_REQUIRED"
    && typeof value.retryAfterSeconds === "number"
    && Number.isFinite(value.retryAfterSeconds)
    && value.retryAfterSeconds >= 1
    && value.retryAfterSeconds <= 300
    && typeof value.traceId === "string"
    && value.traceId.length <= MAX_TRACE_ID_LENGTH;
}

function isInventoryResponse(value: unknown): value is InventoryResponse {
  if (!isJsonRecord(value)) {
    return false;
  }
  return typeof value.ticketItemId === "number"
    && Number.isInteger(value.ticketItemId)
    && value.ticketItemId > 0
    && typeof value.initial === "number"
    && Number.isInteger(value.initial)
    && value.initial >= 0
    && typeof value.available === "number"
    && Number.isInteger(value.available)
    && value.available >= 0
    && typeof value.reserved === "number"
    && Number.isInteger(value.reserved)
    && value.reserved >= 0
    && typeof value.confirmed === "number"
    && Number.isInteger(value.confirmed)
    && value.confirmed >= 0;
}

function invalidResponseDetails(): ReservationErrorResponse {
  return {
    code: "INVALID_RESPONSE",
    message: "Reservation service returned an invalid response",
    retryable: false,
    traceId: "unavailable",
    stockAfter: null,
  };
}

function invalidResponse(status: number): never {
  throw new ReservationClientError(status, invalidResponseDetails());
}

export function getDemoActorId(): string {
  if (typeof window === "undefined") {
    throw new Error("Demo actor identity is only available in a browser session");
  }

  const existing = window.sessionStorage.getItem(ACTOR_STORAGE_KEY);
  if (existing) {
    return existing;
  }

  const created = window.crypto.randomUUID();
  window.sessionStorage.setItem(ACTOR_STORAGE_KEY, created);
  return created;
}

export function createReservationIdempotencyKey(): string {
  const cryptoSource = typeof window === "undefined" ? globalThis.crypto : window.crypto;
  return cryptoSource.randomUUID();
}

export function createReservationIntent(
  ticketItemId: number,
  quantity: number,
): ReservationCreateRequest {
  return { ticketItemId, quantity, idempotencyKey: createReservationIdempotencyKey() };
}

export function getReservationPollDelayMs(retryAfterSeconds: number): number {
  const seconds = Number.isFinite(retryAfterSeconds)
    ? Math.max(1, Math.min(30, Math.ceil(retryAfterSeconds)))
    : 1;
  return seconds * 1000;
}

async function readBody(response: Response): Promise<unknown> {
  if (!response.body) {
    const text = await response.text();
    if (new TextEncoder().encode(text).byteLength > MAX_RESPONSE_BODY_BYTES) {
      return null;
    }
    try {
      return text ? JSON.parse(text) as unknown : null;
    } catch {
      return null;
    }
  }

  const reader = response.body.getReader();
  const decoder = new TextDecoder();
  let totalBytes = 0;
  let text = "";
  try {
    while (true) {
      const { done, value } = await reader.read();
      if (done) {
        text += decoder.decode();
        break;
      }
      totalBytes += value.byteLength;
      if (totalBytes > MAX_RESPONSE_BODY_BYTES) {
        await reader.cancel();
        return null;
      }
      text += decoder.decode(value, { stream: true });
    }
  } finally {
    reader.releaseLock();
  }
  if (!text) {
    return null;
  }
  try {
    return JSON.parse(text) as unknown;
  } catch {
    return null;
  }
}

function parseReservationResult(
  status: number,
  body: unknown,
  acceptedStatuses: readonly number[],
  allowMissingOperationId = false,
  allowRejectedReplay = false,
): ReservationResponse | ReservationProcessingResponse {
  if (!acceptedStatuses.includes(status)) {
    return invalidResponse(status);
  }
  if (status === 202) {
    return isProcessingResponse(body) ? body : invalidResponse(status);
  }
  return isReservationResponse(body, allowMissingOperationId, status === 200 && allowRejectedReplay)
    ? body
    : invalidResponse(status);
}

async function request<T>(
  path: string,
  init: RequestInit,
  parseSuccess: (status: number, body: unknown) => T,
): Promise<ReservationHttpResult<T>> {
  const response = await fetch(`/api/backend${path}`, {
    ...init,
    headers: {
      Accept: "application/json",
      "Content-Type": "application/json",
      ...init.headers,
    },
    cache: "no-store",
  });
  const body = await readBody(response);

  if (!response.ok) {
    const details = isReservationErrorResponse(body) ? body : invalidResponseDetails();
    throw new ReservationClientError(response.status, details);
  }

  return { status: response.status, body: parseSuccess(response.status, body) };
}

export function createReservation(
  requestBody: ReservationCreateRequest,
  actorId: string = getDemoActorId(),
) {
  assertUuid(requestBody.idempotencyKey, "Idempotency-Key");
  assertUuid(actorId, "X-Demo-Actor-Id");
  return request<ReservationResponse | ReservationProcessingResponse>(
    "/api/v1/reservations",
    {
      method: "POST",
      headers: {
        "Idempotency-Key": requestBody.idempotencyKey,
        "X-Demo-Actor-Id": actorId,
      },
      body: JSON.stringify({
        ticketItemId: requestBody.ticketItemId,
        quantity: requestBody.quantity,
      }),
    },
    (status, body) => parseReservationResult(status, body, [200, 201, 202], false, true),
  );
}

export function getReservation(reservationId: string) {
  return request<ReservationResponse | ReservationProcessingResponse>(
    `/api/v1/reservations/${encodeURIComponent(reservationId)}`,
    { method: "GET" },
    (status, body) => parseReservationResult(status, body, [200, 202], true),
  );
}

export function confirmReservation(reservationId: string) {
  return request<ReservationResponse | ReservationProcessingResponse>(
    `/api/v1/reservations/${encodeURIComponent(reservationId)}/confirm`,
    { method: "POST" },
    (status, body) => parseReservationResult(status, body, [200, 202], true),
  );
}

export function releaseReservation(reservationId: string) {
  return request<ReservationResponse | ReservationProcessingResponse>(
    `/api/v1/reservations/${encodeURIComponent(reservationId)}/release`,
    { method: "POST" },
    (status, body) => parseReservationResult(status, body, [200, 202], true),
  );
}

export function getReservationInventory(ticketItemId: number) {
  return request<InventoryResponse>(
    `/api/v1/inventory/${ticketItemId}`,
    { method: "GET" },
    (status, body) => status === 200 && isInventoryResponse(body) ? body : invalidResponse(status),
  );
}

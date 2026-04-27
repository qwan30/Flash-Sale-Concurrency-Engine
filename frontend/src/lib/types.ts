export const ORDER_STRATEGIES = [
  "UNSAFE_DB",
  "CONDITIONAL_DB",
  "REDIS_LUA",
  "REDIS_LUA_WITH_COMPENSATION",
] as const;

export type OrderStrategy = (typeof ORDER_STRATEGIES)[number];

export type ApiEnvelope<T> = {
  success: boolean;
  message: string;
  code: number;
  timestamp: number;
  result: T;
};

export type HealthResponse = {
  status: string;
  components?: Record<string, { status: string }>;
};

export type TicketDetail = {
  id: number;
  name: string;
  stockInitial: number;
  stockAvailable: number;
  stockPrepared: boolean;
  priceOriginal: number;
  priceFlash: number;
  saleStartTime: string;
  saleEndTime: string;
  status: number;
  activityId: number;
  version: number;
};

export type CreateOrderRequest = {
  ticketItemId: number;
  userId: number;
  quantity: number;
  strategy: OrderStrategy;
  idempotencyKey: string;
};

export type CreateOrderResponse = {
  success: boolean;
  code: string;
  message: string;
  orderNumber?: string;
  strategy?: OrderStrategy;
  ticketItemId?: number;
  userId?: number;
  quantity?: number;
  redisStockAfter?: number;
  dbStockAfter?: number;
};

export type BenchmarkResetRequest = {
  ticketItemId: number;
  stock: number;
  yearMonth: string;
};

export type BenchmarkResetResponse = {
  success: boolean;
  message: string;
  ticketItemId: number;
  stock: number;
  yearMonth: string;
  redisStockAfter: number;
  dbStockAfter: number;
  dbOrderCount: number;
};

export type ConsistencySnapshot = {
  ticketItemId: number;
  yearMonth: string;
  redisStockAfter: number;
  dbStockAfter: number;
  dbOrderCount: number;
  oversoldCount: number;
  redisDbInconsistencyCount: number;
};

export type TicketOrder = {
  id: number;
  userId: number;
  orderNumber: string;
  totalAmount: number;
  terminalId: string;
  orderDate: string;
  orderNotes: string;
  updatedAt: string;
  createdAt: string;
};

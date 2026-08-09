package com.xxxx.ddd.application.reservation.port;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public interface ReservationStockPort {

    RedisApplyResult applyOnce(UUID operationId, long ticketItemId, int quantity, long fenceVersion);

    RedisCompensationResult compensateOnce(UUID operationId, long ticketItemId, int quantity, long fenceVersion);

    void mirrorTerminalOnce(UUID operationId, long ticketItemId, int delta, long fenceVersion);

    Optional<RedisOperationState> operationState(UUID operationId);

    record RedisApplyResult(Status status, Integer stockAfter) {

        public RedisApplyResult {
            Objects.requireNonNull(status, "status must not be null");
            validateStock(status.requiresStockAfter(), stockAfter, status.name());
        }

        public static RedisApplyResult applied(int stockAfter) {
            return new RedisApplyResult(Status.APPLIED, stockAfter);
        }

        public static RedisApplyResult replayed(int stockAfter) {
            return new RedisApplyResult(Status.REPLAYED, stockAfter);
        }

        public static RedisApplyResult soldOut(int stockAfter) {
            return new RedisApplyResult(Status.SOLD_OUT, stockAfter);
        }

        public static RedisApplyResult staleFence() {
            return new RedisApplyResult(Status.STALE_FENCE, null);
        }

        public static RedisApplyResult conflict() {
            return new RedisApplyResult(Status.CONFLICT, null);
        }

        public enum Status {
            APPLIED(true),
            REPLAYED(true),
            SOLD_OUT(true),
            STALE_FENCE(false),
            CONFLICT(false);

            private final boolean requiresStockAfter;

            Status(boolean requiresStockAfter) {
                this.requiresStockAfter = requiresStockAfter;
            }

            private boolean requiresStockAfter() {
                return requiresStockAfter;
            }
        }
    }

    record RedisCompensationResult(Status status, Integer stockAfter) {

        public RedisCompensationResult {
            Objects.requireNonNull(status, "status must not be null");
            validateStock(status.requiresStockAfter(), stockAfter, status.name());
        }

        public static RedisCompensationResult compensated(int stockAfter) {
            return new RedisCompensationResult(Status.COMPENSATED, stockAfter);
        }

        public static RedisCompensationResult replayed(int stockAfter) {
            return new RedisCompensationResult(Status.REPLAYED, stockAfter);
        }

        public static RedisCompensationResult notApplied() {
            return new RedisCompensationResult(Status.NOT_APPLIED, null);
        }

        public static RedisCompensationResult staleFence() {
            return new RedisCompensationResult(Status.STALE_FENCE, null);
        }

        public static RedisCompensationResult conflict() {
            return new RedisCompensationResult(Status.CONFLICT, null);
        }

        public enum Status {
            COMPENSATED(true),
            REPLAYED(true),
            NOT_APPLIED(false),
            STALE_FENCE(false),
            CONFLICT(false);

            private final boolean requiresStockAfter;

            Status(boolean requiresStockAfter) {
                this.requiresStockAfter = requiresStockAfter;
            }

            private boolean requiresStockAfter() {
                return requiresStockAfter;
            }
        }
    }

    record RedisOperationState(Status status, Integer stockAfter) {

        public RedisOperationState {
            Objects.requireNonNull(status, "status must not be null");
            if (stockAfter != null && stockAfter < 0) {
                throw new IllegalArgumentException("stockAfter must not be negative");
            }
            if (status == Status.STALE_FENCE || status == Status.CONFLICT) {
                if (stockAfter != null) {
                    throw new IllegalArgumentException(status + " must not expose stockAfter");
                }
            }
        }

        public static RedisOperationState applied(int stockAfter) {
            return new RedisOperationState(Status.APPLIED, stockAfter);
        }

        public enum Status {
            APPLIED,
            COMPENSATED,
            MIRRORED,
            REPAIRED,
            SOLD_OUT,
            STALE_FENCE,
            CONFLICT
        }
    }

    private static void validateStock(boolean required, Integer stockAfter, String status) {
        if (required && stockAfter == null) {
            throw new IllegalArgumentException(status + " requires stockAfter");
        }
        if (!required && stockAfter != null) {
            throw new IllegalArgumentException(status + " must not expose stockAfter");
        }
        if (stockAfter != null && stockAfter < 0) {
            throw new IllegalArgumentException("stockAfter must not be negative");
        }
    }
}

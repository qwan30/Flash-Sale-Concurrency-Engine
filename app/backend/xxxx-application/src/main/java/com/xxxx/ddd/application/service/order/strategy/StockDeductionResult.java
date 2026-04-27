package com.xxxx.ddd.application.service.order.strategy;

public class StockDeductionResult {

    private final boolean success;
    private final boolean compensateOnOrderFailure;
    private final String code;
    private final String message;

    private StockDeductionResult(boolean success, boolean compensateOnOrderFailure, String code, String message) {
        this.success = success;
        this.compensateOnOrderFailure = compensateOnOrderFailure;
        this.code = code;
        this.message = message;
    }

    public static StockDeductionResult success(boolean compensateOnOrderFailure) {
        return new StockDeductionResult(true, compensateOnOrderFailure, null, null);
    }

    public static StockDeductionResult failure(String code, String message) {
        return new StockDeductionResult(false, false, code, message);
    }

    public boolean isSuccess() {
        return success;
    }

    public boolean isCompensateOnOrderFailure() {
        return compensateOnOrderFailure;
    }

    public String getCode() {
        return code;
    }

    public String getMessage() {
        return message;
    }
}

package com.xxxx.ddd.controller.http.reservation;

import org.springframework.http.HttpStatus;

public final class ReservationAdmissionException extends RuntimeException {

    private final HttpStatus status;
    private final String code;
    private final boolean retryable;

    private ReservationAdmissionException(HttpStatus status, String code, String message, boolean retryable) {
        super(message);
        this.status = status;
        this.code = code;
        this.retryable = retryable;
    }

    public static ReservationAdmissionException rateLimited() {
        return new ReservationAdmissionException(
                HttpStatus.TOO_MANY_REQUESTS,
                "RATE_LIMITED",
                "Reservation create admission limit exceeded",
                true);
    }

    public static ReservationAdmissionException saturated() {
        return new ReservationAdmissionException(
                HttpStatus.SERVICE_UNAVAILABLE,
                "ADMISSION_SATURATED",
                "Reservation capacity is temporarily saturated",
                true);
    }

    public HttpStatus status() {
        return status;
    }

    public String code() {
        return code;
    }

    public boolean retryable() {
        return retryable;
    }
}

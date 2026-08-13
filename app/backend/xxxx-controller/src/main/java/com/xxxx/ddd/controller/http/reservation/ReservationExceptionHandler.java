package com.xxxx.ddd.controller.http.reservation;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;

@RestControllerAdvice
public class ReservationExceptionHandler {

    @ExceptionHandler(ReservationAdmissionException.class)
    public ResponseEntity<ReservationErrorResponse> admissionRejected(
            ReservationAdmissionException exception,
            HttpServletRequest request
    ) {
        return ResponseEntity.status(exception.status())
                .header("Retry-After", "1")
                .body(new ReservationErrorResponse(
                        exception.code(),
                        exception.getMessage(),
                        exception.retryable(),
                        traceId(request),
                        null));
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ReservationErrorResponse> responseStatus(
            ResponseStatusException exception,
            HttpServletRequest request
    ) {
        HttpStatus status = HttpStatus.resolve(exception.getStatusCode().value());
        if (status == null) {
            status = HttpStatus.INTERNAL_SERVER_ERROR;
        }
        ResponseEntity<ReservationErrorResponse> response = error(
                status,
                "HTTP_" + status.value(),
                "Reservation request could not be completed",
                status.is5xxServerError(),
                request,
                null);
        if (status == HttpStatus.SERVICE_UNAVAILABLE) {
            return ResponseEntity.status(status)
                    .header("Retry-After", "1")
                    .body(response.getBody());
        }
        return response;
    }

    @ExceptionHandler({
            MethodArgumentNotValidException.class,
            MissingRequestHeaderException.class,
            MethodArgumentTypeMismatchException.class,
            HttpMessageNotReadableException.class,
            ConstraintViolationException.class,
            IllegalArgumentException.class
    })
    public ResponseEntity<ReservationErrorResponse> badRequest(Exception exception, HttpServletRequest request) {
        return error(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "Reservation request is invalid", false, request, null);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ReservationErrorResponse> internalError(Exception exception, HttpServletRequest request) {
        return error(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "Reservation request could not be completed", true,
                request, null);
    }

    private static ResponseEntity<ReservationErrorResponse> error(
            HttpStatus status,
            String code,
            String message,
            boolean retryable,
            HttpServletRequest request,
            Integer stockAfter
    ) {
        return ResponseEntity.status(status)
                .body(new ReservationErrorResponse(code, message, retryable, traceId(request), stockAfter));
    }

    private static String traceId(HttpServletRequest request) {
        return Optional.ofNullable(request.getHeader("X-Trace-Id"))
                .filter(value -> !value.isBlank() && value.length() <= 128)
                .orElse("unavailable");
    }
}

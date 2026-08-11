package com.xxxx.ddd.controller.http.reservation;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThat;

class ReservationExceptionHandlerTest {

    @Test
    void doesNotExposeResponseStatusReasonInPublicMessage() {
        HttpServletRequest request = new MockHttpServletRequest();

        ReservationErrorResponse body = new ReservationExceptionHandler()
                .responseStatus(
                        new ResponseStatusException(HttpStatus.BAD_REQUEST, "SQL password=secret"),
                        request)
                .getBody();

        assertThat(body).isNotNull();
        assertThat(body.message()).isEqualTo("Reservation request could not be completed");
        assertThat(body.message()).doesNotContain("SQL", "secret");
    }

    @Test
    void replacesOversizedTraceIdWithUnavailable() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Trace-Id", "x".repeat(129));

        ReservationErrorResponse body = new ReservationExceptionHandler()
                .badRequest(new IllegalArgumentException("invalid"), request)
                .getBody();

        assertThat(body).isNotNull();
        assertThat(body.traceId()).isEqualTo("unavailable");
    }

    @Test
    void addsRetryAfterToGenericServiceUnavailableResponse() {
        ResponseEntity<ReservationErrorResponse> response = new ReservationExceptionHandler()
                .responseStatus(
                        new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "internal reason"),
                        new MockHttpServletRequest());

        assertThat(response.getHeaders().getFirst("Retry-After")).isEqualTo("1");
    }
}

package com.xxxx.ddd.controller.http.reservation;

import com.xxxx.ddd.application.reservation.ConfirmReservationService;
import com.xxxx.ddd.application.reservation.CreateReservationResult;
import com.xxxx.ddd.application.reservation.CreateReservationService;
import com.xxxx.ddd.application.reservation.ReleaseReservationService;
import com.xxxx.ddd.application.reservation.ReservationLifecycleResult;
import com.xxxx.ddd.application.reservation.port.InventoryRepository;
import com.xxxx.ddd.application.reservation.port.OperationJournalRepository;
import com.xxxx.ddd.application.reservation.port.ReservationRepository;
import com.xxxx.ddd.application.reservation.strategy.ReservationCoordinationStrategy;
import com.xxxx.ddd.application.reservation.strategy.ReservationStrategy;

import com.xxxx.ddd.domain.reservation.Reservation;
import com.xxxx.ddd.domain.reservation.ReservationOrder;
import com.xxxx.ddd.domain.reservation.ReservationStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;
import java.util.function.Supplier;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ReservationController.class)
@ContextConfiguration(classes = {ReservationController.class, ReservationExceptionHandler.class})
@Import(ReservationExceptionHandler.class)
class ReservationControllerTest {

    private static final UUID RESERVATION_ID = UUID.fromString("2f03a82c-ab45-4f87-b8ea-3d407198f5c2");
    private static final UUID OPERATION_ID = UUID.fromString("f0f30dd9-2f8e-4d35-9c1f-5c6c3ee08d7c");
    private static final UUID ACTOR_ID = UUID.fromString("73e4b5c9-1d67-4d3a-8db8-2e3e21c27844");
    private static final String IDEMPOTENCY_KEY = "6f7d3a9b-39c1-4f1a-9d9e-8a9e9e4e7a12";

    @Autowired
    private MockMvc mvc;

    @MockBean
    private CreateReservationService creation;

    @MockBean(name = "mysqlConditionalStrategy")
    private ReservationCoordinationStrategy mysqlCreation;


    @MockBean
    private ConfirmReservationService confirmation;

    @MockBean
    private ReleaseReservationService release;

    @MockBean
    private ReservationRepository reservations;

    @MockBean
    private OperationJournalRepository journal;

    @MockBean
    private InventoryRepository inventory;

    @MockBean
    private ReservationAdmissionControl admission;

    @BeforeEach
    void allowAdmissionForMvcSlice() {

        when(creation.strategy()).thenReturn(ReservationStrategy.REDIS_FIRST);
        when(mysqlCreation.strategy()).thenReturn(ReservationStrategy.MYSQL_CONDITIONAL);
        when(admission.executeCreate(any())).thenAnswer(invocation -> {
            Supplier<CreateReservationResult> operation = invocation.getArgument(0);
            return operation.get();
        });
        when(admission.executeTerminal(any())).thenAnswer(invocation -> {
            Supplier<ReservationLifecycleResult> operation = invocation.getArgument(0);
            return operation.get();
        });
        when(admission.executeRead(any())).thenAnswer(invocation -> {
            Supplier<?> operation = invocation.getArgument(0);
            return operation.get();
        });
    }


    @Test
    void routesExplicitMysqlStrategyHeaderToMysqlLane() throws Exception {
        when(mysqlCreation.create(any())).thenReturn(new CreateReservationResult(
                CreateReservationResult.Outcome.NEW,
                OPERATION_ID,
                RESERVATION_ID,
                Optional.of(reservation(ReservationStatus.RESERVED)),
                Optional.empty(),
                Optional.of(OperationJournalRepository.JournalState.COMMITTED),
                "NEW",
                OptionalInt.of(7)));

        mvc.perform(post("/api/v1/reservations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", IDEMPOTENCY_KEY)
                        .header("X-Demo-Actor-Id", ACTOR_ID)
                        .header("X-Reservation-Strategy", "MYSQL_CONDITIONAL")
                        .content("{\"ticketItemId\":42,\"quantity\":2}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.outcome").value("NEW"));

        verify(mysqlCreation).create(any());
    }

    @Test
    void createsReservationWith201AndRequiredHeaders() throws Exception {
        Reservation reservation = reservation(ReservationStatus.RESERVED);
        when(creation.create(any())).thenReturn(new CreateReservationResult(
                CreateReservationResult.Outcome.NEW,
                OPERATION_ID,
                RESERVATION_ID,
                Optional.of(reservation),
                Optional.empty(),
                Optional.of(OperationJournalRepository.JournalState.COMMITTED),
                "NEW",
                OptionalInt.of(7)));

        mvc.perform(post("/api/v1/reservations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", IDEMPOTENCY_KEY)
                        .header("X-Demo-Actor-Id", ACTOR_ID)
                        .content("{\"ticketItemId\":42,\"quantity\":2}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.reservationId").value(RESERVATION_ID.toString()))
                .andExpect(jsonPath("$.outcome").value("NEW"));
    }

    @Test
    void createsReservationWith202ProcessingContract() throws Exception {
        when(creation.create(any())).thenReturn(new CreateReservationResult(
                CreateReservationResult.Outcome.PROCESSING,
                OPERATION_ID,
                RESERVATION_ID,
                Optional.empty(),
                Optional.empty(),
                Optional.of(OperationJournalRepository.JournalState.REDIS_APPLIED),
                "PROCESSING",
                OptionalInt.empty()));

        mvc.perform(post("/api/v1/reservations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", IDEMPOTENCY_KEY)
                        .header("X-Demo-Actor-Id", ACTOR_ID)
                        .header("X-Trace-Id", "trace-create")
                        .content("{\"ticketItemId\":42,\"quantity\":2}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.reservationId").value(RESERVATION_ID.toString()))
                .andExpect(jsonPath("$.operationId").value(OPERATION_ID.toString()))
                .andExpect(jsonPath("$.status").value("PROCESSING"))
                .andExpect(jsonPath("$.journalState").value("REDIS_APPLIED"))
                .andExpect(jsonPath("$.retryAfterSeconds").value(1))
                .andExpect(jsonPath("$.traceId").value("trace-create"))
                .andExpect(jsonPath("$.outcome").doesNotExist());
    }

    @Test
    void replaysReservationWith200() throws Exception {
        when(creation.create(any())).thenReturn(new CreateReservationResult(
                CreateReservationResult.Outcome.REPLAYED,
                OPERATION_ID,
                RESERVATION_ID,
                Optional.of(reservation(ReservationStatus.RESERVED)),
                Optional.empty(),
                Optional.of(OperationJournalRepository.JournalState.COMMITTED),
                "REPLAYED",
                OptionalInt.of(7)));

        mvc.perform(post("/api/v1/reservations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", IDEMPOTENCY_KEY)
                        .header("X-Demo-Actor-Id", ACTOR_ID)
                        .content("{\"ticketItemId\":42,\"quantity\":2}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("REPLAYED"));
    }

    @Test
    void confirmsReservationWith200AndOrder() throws Exception {
        Reservation confirmed = reservation(ReservationStatus.CONFIRMED);
        when(confirmation.confirm(RESERVATION_ID)).thenReturn(new ReservationLifecycleResult(
                ReservationLifecycleResult.Outcome.CONFIRMED,
                Optional.of(confirmed),
                Optional.of(new ReservationOrder(
                        OPERATION_ID,
                        RESERVATION_ID,
                        42,
                        ACTOR_ID,
                        2,
                        Instant.now())),
                Optional.of(OPERATION_ID)));

        mvc.perform(post("/api/v1/reservations/{id}/confirm", RESERVATION_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("CONFIRMED"))
                .andExpect(jsonPath("$.orderId").value(OPERATION_ID.toString()));
    }

    @Test
    void releasesReservationWith200() throws Exception {
        when(release.release(RESERVATION_ID)).thenReturn(new ReservationLifecycleResult(
                ReservationLifecycleResult.Outcome.RELEASED,
                Optional.of(reservation(ReservationStatus.RELEASED)),
                Optional.empty(),
                Optional.of(OPERATION_ID)));

        mvc.perform(post("/api/v1/reservations/{id}/release", RESERVATION_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("RELEASED"));
    }

    @Test
    void rejectsInvalidQuantityWithBounded400Error() throws Exception {
        mvc.perform(post("/api/v1/reservations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", IDEMPOTENCY_KEY)
                        .header("X-Demo-Actor-Id", ACTOR_ID)
                        .content("{\"ticketItemId\":42,\"quantity\":5}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.message").exists())
                .andExpect(jsonPath("$.retryable").value(false));
    }

    @Test
    void mapsCreateRateLimitTo429WithRetryAfter() throws Exception {
        doAnswer(invocation -> {
            throw ReservationAdmissionException.rateLimited();
        }).when(admission).executeCreate(any());

        mvc.perform(post("/api/v1/reservations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", IDEMPOTENCY_KEY)
                        .header("X-Demo-Actor-Id", ACTOR_ID)
                        .content("{\"ticketItemId\":42,\"quantity\":2}"))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "1"))
                .andExpect(jsonPath("$.code").value("RATE_LIMITED"))
                .andExpect(jsonPath("$.retryable").value(true));
    }

    @Test
    void mapsAdmissionSaturationTo503WithRetryAfter() throws Exception {
        doAnswer(invocation -> {
            throw ReservationAdmissionException.saturated();
        }).when(admission).executeCreate(any());

        mvc.perform(post("/api/v1/reservations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", IDEMPOTENCY_KEY)
                        .header("X-Demo-Actor-Id", ACTOR_ID)
                        .content("{\"ticketItemId\":42,\"quantity\":2}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Retry-After", "1"))
                .andExpect(jsonPath("$.code").value("ADMISSION_SATURATED"))
                .andExpect(jsonPath("$.retryable").value(true));
    }

    @Test
    void rejectsInvalidIdempotencyKeyWithBounded400Error() throws Exception {
        mvc.perform(post("/api/v1/reservations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", "not-a-uuid")
                        .header("X-Demo-Actor-Id", ACTOR_ID)
                        .content("{\"ticketItemId\":42,\"quantity\":2}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.message").value("Reservation request is invalid"));
    }

    @Test
    void rejectsMalformedActorHeaderWithBounded400Error() throws Exception {
        mvc.perform(post("/api/v1/reservations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", IDEMPOTENCY_KEY)
                        .header("X-Demo-Actor-Id", "not-a-uuid")
                        .content("{\"ticketItemId\":42,\"quantity\":2}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    void rejectsMalformedReservationPathWithBounded400Error() throws Exception {
        mvc.perform(post("/api/v1/reservations/{id}/confirm", "not-a-uuid"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    void boundsTraceIdInErrorResponse() throws Exception {
        mvc.perform(post("/api/v1/reservations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", IDEMPOTENCY_KEY)
                        .header("X-Demo-Actor-Id", ACTOR_ID)
                        .header("X-Trace-Id", "x".repeat(129))
                        .content("{\"ticketItemId\":42,\"quantity\":5}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.traceId").value("unavailable"));
    }

    @Test
    void exposesMirrorPendingAs202() throws Exception {
        Reservation reservation = reservation(ReservationStatus.RELEASED);
        when(release.release(RESERVATION_ID)).thenReturn(new ReservationLifecycleResult(
                ReservationLifecycleResult.Outcome.MIRROR_PENDING,
                Optional.of(reservation),
                Optional.empty(),
                Optional.of(OPERATION_ID)));

        mvc.perform(post("/api/v1/reservations/{id}/release", RESERVATION_ID))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("PROCESSING"))
                .andExpect(jsonPath("$.journalState").value("MIRROR_PENDING"))
                .andExpect(jsonPath("$.retryAfterSeconds").value(1));
    }

    @Test
    void exposesTheDurableJournalStateForLifecycleProcessing() throws Exception {
        when(confirmation.confirm(RESERVATION_ID)).thenReturn(new ReservationLifecycleResult(
                ReservationLifecycleResult.Outcome.PROCESSING,
                Optional.of(reservation(ReservationStatus.RESERVED)),
                Optional.empty(),
                Optional.of(OPERATION_ID)));
        when(journal.findByOperationId(OPERATION_ID)).thenReturn(Optional.of(journalEntry(
                OperationJournalRepository.JournalState.REDIS_APPLIED,
                "REDIS_APPLIED",
                null)));

        mvc.perform(post("/api/v1/reservations/{id}/confirm", RESERVATION_ID))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.reservationId").value(RESERVATION_ID.toString()))
                .andExpect(jsonPath("$.operationId").value(OPERATION_ID.toString()))
                .andExpect(jsonPath("$.status").value("PROCESSING"))
                .andExpect(jsonPath("$.journalState").value("REDIS_APPLIED"));
    }

    @Test
    void exposesMissingTerminalReservationAsStructured404() throws Exception {
        when(confirmation.confirm(RESERVATION_ID)).thenReturn(new ReservationLifecycleResult(
                ReservationLifecycleResult.Outcome.NOT_FOUND,
                Optional.empty(),
                Optional.empty(),
                Optional.of(OPERATION_ID)));

        mvc.perform(post("/api/v1/reservations/{id}/confirm", RESERVATION_ID)
                        .header("X-Trace-Id", "trace-not-found"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"))
                .andExpect(jsonPath("$.message").exists())
                .andExpect(jsonPath("$.retryable").value(false))
                .andExpect(jsonPath("$.traceId").value("trace-not-found"));
    }

    @Test
    void exposesLateTerminalConflictAsStructured409() throws Exception {
        when(release.release(RESERVATION_ID)).thenReturn(new ReservationLifecycleResult(
                ReservationLifecycleResult.Outcome.LATE_CONFLICT,
                Optional.of(reservation(ReservationStatus.EXPIRED)),
                Optional.empty(),
                Optional.of(OPERATION_ID)));

        mvc.perform(post("/api/v1/reservations/{id}/release", RESERVATION_ID))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("LATE_CONFLICT"))
                .andExpect(jsonPath("$.message").exists())
                .andExpect(jsonPath("$.retryable").value(false));
    }

    @Test
    void exposesTerminalRepairRequiredAs503WithRetryAfter() throws Exception {
        Reservation reservation = reservation(ReservationStatus.RELEASED);
        when(release.release(RESERVATION_ID)).thenReturn(new ReservationLifecycleResult(
                ReservationLifecycleResult.Outcome.REPAIR_REQUIRED,
                Optional.of(reservation),
                Optional.empty(),
                Optional.of(OPERATION_ID)));

        mvc.perform(post("/api/v1/reservations/{id}/release", RESERVATION_ID))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Retry-After", "1"))
                .andExpect(jsonPath("$.code").value("REPAIR_REQUIRED"))
                .andExpect(jsonPath("$.retryable").value(true));
    }

    @Test
    void exposesRepairRequiredAs503WithRetryAfter() throws Exception {
        when(creation.create(any())).thenReturn(new CreateReservationResult(
                CreateReservationResult.Outcome.REJECTED,
                OPERATION_ID,
                RESERVATION_ID,
                Optional.empty(),
                Optional.empty(),
                Optional.of(OperationJournalRepository.JournalState.REPAIR_REQUIRED),
                "REPAIR_REQUIRED",
                OptionalInt.empty()));

        mvc.perform(post("/api/v1/reservations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", IDEMPOTENCY_KEY)
                        .header("X-Demo-Actor-Id", ACTOR_ID)
                        .content("{\"ticketItemId\":42,\"quantity\":2}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Retry-After", "1"))
                .andExpect(jsonPath("$.code").value("REPAIR_REQUIRED"))
                .andExpect(jsonPath("$.retryable").value(true));
    }

    @Test
    void exposesInitialSoldOutAsBounded409Error() throws Exception {
        when(creation.create(any())).thenReturn(new CreateReservationResult(
                CreateReservationResult.Outcome.SOLD_OUT,
                OPERATION_ID,
                RESERVATION_ID,
                Optional.empty(),
                Optional.empty(),
                Optional.of(OperationJournalRepository.JournalState.REJECTED),
                "SOLD_OUT",
                OptionalInt.of(0)));

        mvc.perform(post("/api/v1/reservations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", IDEMPOTENCY_KEY)
                        .header("X-Demo-Actor-Id", ACTOR_ID)
                        .content("{\"ticketItemId\":42,\"quantity\":2}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SOLD_OUT"))
                .andExpect(jsonPath("$.stockAfter").value(0))
                .andExpect(jsonPath("$.retryable").value(false));
    }

    @Test
    void missingReservationIs404() throws Exception {
        when(reservations.findById(RESERVATION_ID)).thenReturn(Optional.empty());

        mvc.perform(get("/api/v1/reservations/{id}", RESERVATION_ID))
                .andExpect(status().isNotFound());

        verify(admission).executeRead(any());
    }

    @Test
    void missingInventoryIsReadThroughFixtureGate() throws Exception {
        when(inventory.findSnapshot(42L)).thenReturn(Optional.empty());

        mvc.perform(get("/api/v1/inventory/{ticketItemId}", 42L))
                .andExpect(status().isNotFound());

        verify(admission).executeRead(any());
    }

    @Test
    void exposesRecoverableJournalByReservationIdAs202() throws Exception {
        when(reservations.findById(RESERVATION_ID)).thenReturn(Optional.empty());
        when(journal.findByReservationId(RESERVATION_ID)).thenReturn(Optional.of(journalEntry(
                OperationJournalRepository.JournalState.MIRROR_PENDING,
                "MIRROR_PENDING",
                null)));

        mvc.perform(get("/api/v1/reservations/{id}", RESERVATION_ID)
                        .header("X-Trace-Id", "trace-123"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.reservationId").value(RESERVATION_ID.toString()))
                .andExpect(jsonPath("$.journalState").value("MIRROR_PENDING"))
                .andExpect(jsonPath("$.retryAfterSeconds").value(1));
    }

    @Test
    void exposesJournalRepairByReservationIdAs503() throws Exception {
        when(reservations.findById(RESERVATION_ID)).thenReturn(Optional.empty());
        when(journal.findByReservationId(RESERVATION_ID)).thenReturn(Optional.of(journalEntry(
                OperationJournalRepository.JournalState.REPAIR_REQUIRED,
                "FENCE_STALE",
                null)));

        mvc.perform(get("/api/v1/reservations/{id}", RESERVATION_ID))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Retry-After", "1"))
                .andExpect(jsonPath("$.code").value("REPAIR_REQUIRED"));
    }

    @Test
    void doesNotHideRepairRequiredBehindTheDurableReservationSnapshot() throws Exception {
        when(reservations.findById(RESERVATION_ID)).thenReturn(Optional.of(reservation(ReservationStatus.RELEASED)));
        when(journal.findByReservationId(RESERVATION_ID)).thenReturn(Optional.of(journalEntry(
                OperationJournalRepository.JournalState.REPAIR_REQUIRED,
                "FENCE_STALE",
                null)));

        mvc.perform(get("/api/v1/reservations/{id}", RESERVATION_ID))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Retry-After", "1"))
                .andExpect(jsonPath("$.code").value("REPAIR_REQUIRED"));
    }

    private static OperationJournalRepository.JournalEntry journalEntry(
            OperationJournalRepository.JournalState state,
            String resultCode,
            Integer stockAfter
    ) {
        return new OperationJournalRepository.JournalEntry(
                OPERATION_ID,
                RESERVATION_ID,
                ACTOR_ID,
                "a".repeat(64),
                "b".repeat(64),
                42,
                2,
                1,
                state,
                resultCode,
                stockAfter);
    }

    private static Reservation reservation(ReservationStatus status) {
        return new Reservation(
                RESERVATION_ID,
                42L,
                ACTOR_ID,
                2,
                status,
                Instant.now().plusSeconds(120),
                status == ReservationStatus.RESERVED ? null : Instant.now());
    }
}

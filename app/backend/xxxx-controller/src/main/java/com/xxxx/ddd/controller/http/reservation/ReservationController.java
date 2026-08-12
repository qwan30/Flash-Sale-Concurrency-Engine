package com.xxxx.ddd.controller.http.reservation;

import com.xxxx.ddd.application.reservation.ConfirmReservationService;
import com.xxxx.ddd.application.reservation.CreateReservationCommand;
import com.xxxx.ddd.application.reservation.CreateReservationResult;
import com.xxxx.ddd.application.reservation.CreateReservationService;
import com.xxxx.ddd.application.reservation.ReleaseReservationService;
import com.xxxx.ddd.application.reservation.ReservationLifecycleResult;
import com.xxxx.ddd.application.reservation.port.InventoryRepository;
import com.xxxx.ddd.application.reservation.port.OperationJournalRepository;
import com.xxxx.ddd.application.reservation.port.ReservationRepository;

import com.xxxx.ddd.domain.reservation.InventorySnapshot;
import com.xxxx.ddd.domain.reservation.Reservation;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/api/v1")
public class ReservationController {

    private final CreateReservationService createService;
    private final ConfirmReservationService confirmation;
    private final ReleaseReservationService release;
    private final ReservationRepository reservations;
    private final OperationJournalRepository journal;
    private final InventoryRepository inventory;
    private final ReservationAdmissionControl admission;

    public ReservationController(
            CreateReservationService createService,
            ConfirmReservationService confirmation,
            ReleaseReservationService release,
            ReservationRepository reservations,
            OperationJournalRepository journal,
            InventoryRepository inventory,
            ReservationAdmissionControl admission
    ) {
        this.createService = createService;
        this.confirmation = confirmation;
        this.release = release;
        this.reservations = reservations;
        this.journal = journal;
        this.inventory = inventory;
        this.admission = admission;
    }

    @PostMapping("/reservations")
    public ResponseEntity<?> create(
            @Valid @RequestBody CreateReservationRequest request,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestHeader("X-Demo-Actor-Id") UUID demoActorId,
            @RequestHeader(value = "X-Reservation-Strategy", defaultValue = "REDIS_FIRST") String strategyName,
            @RequestHeader(value = "X-Trace-Id", required = false) String traceId
    ) {
        CreateReservationResult result = admission.executeCreate(() -> createService.create(new CreateReservationCommand(
                request.ticketItemId(), request.quantity(), demoActorId, idempotencyKey.toString())));
        if (result.journalState().filter(state -> state == OperationJournalRepository.JournalState.REPAIR_REQUIRED).isPresent()) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .header("Retry-After", "1")
                    .body(new ReservationErrorResponse(
                            "REPAIR_REQUIRED",
                            "Reservation repair is in progress",
                            true,
                            boundedTraceId(traceId),
                            null));
        }
        if (result.outcome() == CreateReservationResult.Outcome.SOLD_OUT
                || result.outcome() == CreateReservationResult.Outcome.FENCE_STALE
                || result.outcome() == CreateReservationResult.Outcome.REJECTED
                || result.outcome() == CreateReservationResult.Outcome.CONFLICT) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(new ReservationErrorResponse(
                            result.resultCode(),
                            "Reservation was not completed",
                            false,
                            boundedTraceId(traceId),
                            result.stockAfter().isPresent() ? result.stockAfter().getAsInt() : null));
        }
        if (result.outcome() == CreateReservationResult.Outcome.PROCESSING) {
            return ResponseEntity.accepted().body(toProcessingResponse(result, traceId));
        }
        return ResponseEntity.status(createStatus(result.outcome())).body(toResponse(result));
    }


    @GetMapping("/reservations/{reservationId}")
    public ResponseEntity<?> get(
            @PathVariable("reservationId") UUID reservationId,
            @RequestHeader(value = "X-Trace-Id", required = false) String traceId
    ) {
        return admission.executeRead(() -> {
            Optional<OperationJournalRepository.JournalEntry> journalEntry = journal.findByReservationId(reservationId);
            if (journalEntry.isPresent() && isUnconverged(journalEntry.orElseThrow().state())) {
                return journalResponse(journalEntry.orElseThrow(), traceId);
            }
            Optional<Reservation> reservation = reservations.findById(reservationId);
            if (reservation.isPresent()) {
                return ResponseEntity.ok(toResponse(reservation.orElseThrow(), null, null, null, null, null));
            }
            return journalEntry
                    .map(entry -> journalResponse(entry, traceId))
                    .orElseGet(() -> ResponseEntity.notFound().build());
        });
    }

    @PostMapping("/reservations/{reservationId}/confirm")
    public ResponseEntity<?> confirm(
            @PathVariable("reservationId") UUID reservationId,
            @RequestHeader(value = "X-Trace-Id", required = false) String traceId
    ) {
        ReservationLifecycleResult result = admission.executeTerminal(() -> confirmation.confirm(reservationId));
        return lifecycleResponse(result, reservationId, traceId);
    }

    @PostMapping("/reservations/{reservationId}/release")
    public ResponseEntity<?> release(
            @PathVariable("reservationId") UUID reservationId,
            @RequestHeader(value = "X-Trace-Id", required = false) String traceId
    ) {
        ReservationLifecycleResult result = admission.executeTerminal(() -> release.release(reservationId));
        return lifecycleResponse(result, reservationId, traceId);
    }

    @GetMapping("/inventory/{ticketItemId}")
    public ResponseEntity<InventoryResponse> inventory(@PathVariable("ticketItemId") long ticketItemId) {
        return admission.executeRead(() -> inventory.findSnapshot(ticketItemId)
                .map(snapshot -> ResponseEntity.ok(toResponse(snapshot)))
                .orElseGet(() -> ResponseEntity.notFound().build()));
    }

    private static HttpStatus createStatus(CreateReservationResult.Outcome outcome) {
        return switch (outcome) {
            case NEW -> HttpStatus.CREATED;
            case PROCESSING -> HttpStatus.ACCEPTED;
            case REPLAYED -> HttpStatus.OK;
            case SOLD_OUT, FENCE_STALE, REJECTED, CONFLICT -> HttpStatus.CONFLICT;
        };
    }

    private static HttpStatus lifecycleStatus(ReservationLifecycleResult.Outcome outcome) {
        return switch (outcome) {
            case NOT_FOUND -> HttpStatus.NOT_FOUND;
            case PROCESSING, MIRROR_PENDING -> HttpStatus.ACCEPTED;
            case REPAIR_REQUIRED -> HttpStatus.SERVICE_UNAVAILABLE;
            case CONFLICT, LATE_CONFLICT -> HttpStatus.CONFLICT;
            default -> HttpStatus.OK;
        };
    }

    private ResponseEntity<?> lifecycleResponse(
            ReservationLifecycleResult result,
            UUID reservationId,
            String traceId
    ) {
        if (result.outcome() == ReservationLifecycleResult.Outcome.REPAIR_REQUIRED) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .header("Retry-After", "1")
                    .body(new ReservationErrorResponse(
                            "REPAIR_REQUIRED",
                            "Reservation repair is in progress",
                            true,
                            boundedTraceId(traceId),
                            null));
        }
        if (result.outcome() == ReservationLifecycleResult.Outcome.PROCESSING
                || result.outcome() == ReservationLifecycleResult.Outcome.MIRROR_PENDING) {
            return ResponseEntity.accepted().body(toProcessingResponse(result, reservationId, traceId));
        }
        if (result.outcome() == ReservationLifecycleResult.Outcome.NOT_FOUND) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new ReservationErrorResponse(
                    "NOT_FOUND",
                    "Reservation was not found",
                    false,
                    boundedTraceId(traceId),
                    null));
        }
        if (result.outcome() == ReservationLifecycleResult.Outcome.CONFLICT
                || result.outcome() == ReservationLifecycleResult.Outcome.LATE_CONFLICT) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(new ReservationErrorResponse(
                    result.outcome().name(),
                    "Reservation cannot transition from its current state",
                    false,
                    boundedTraceId(traceId),
                    null));
        }
        return ResponseEntity.status(lifecycleStatus(result.outcome())).body(toResponse(result));
    }

    private static boolean isUnconverged(OperationJournalRepository.JournalState state) {
        return state == OperationJournalRepository.JournalState.RECEIVED
                || state == OperationJournalRepository.JournalState.REDIS_APPLYING
                || state == OperationJournalRepository.JournalState.REDIS_APPLIED
                || state == OperationJournalRepository.JournalState.COMPENSATION_PENDING
                || state == OperationJournalRepository.JournalState.MIRROR_PENDING
                || state == OperationJournalRepository.JournalState.REPAIR_REQUIRED;
    }

    private static ResponseEntity<?> journalResponse(
            OperationJournalRepository.JournalEntry entry,
            String traceId
    ) {
        String boundedTraceId = traceId == null || traceId.isBlank() ? "unavailable" : traceId;
        return switch (entry.state()) {
            case RECEIVED, REDIS_APPLYING, REDIS_APPLIED, COMPENSATION_PENDING, MIRROR_PENDING -> ResponseEntity.accepted()
                    .body(new ReservationProcessingResponse(
                            entry.reservationId(),
                            entry.operationId(),
                            "PROCESSING",
                            entry.state().name(),
                            1,
                            boundedTraceId));
            case REPAIR_REQUIRED -> ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .header("Retry-After", "1")
                    .body(new ReservationErrorResponse(
                            "REPAIR_REQUIRED",
                            "Reservation repair is in progress",
                            true,
                            boundedTraceId,
                            null));
            case REJECTED, COMPENSATED -> ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(new ReservationErrorResponse(
                            entry.resultCode() == null ? entry.state().name() : entry.resultCode(),
                            "Reservation was not completed",
                            false,
                            boundedTraceId,
                            entry.resultStockAfter()));
            default -> ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(new ReservationErrorResponse(
                            "RESERVATION_STATE_UNAVAILABLE",
                            "Reservation state is not available",
                            true,
                            boundedTraceId,
                            null));
        };
    }

    private static ReservationResponse toResponse(CreateReservationResult result) {
        Reservation reservation = result.reservation().orElse(null);
        return toResponse(
                reservation,
                result.operationId(),
                null,
                result.outcome().name(),
                result.resultCode(),
                result.stockAfter().isPresent() ? result.stockAfter().getAsInt() : null);
    }

    private static ReservationProcessingResponse toProcessingResponse(
            CreateReservationResult result,
            String traceId
    ) {
        return new ReservationProcessingResponse(
                result.reservationId(),
                result.operationId(),
                "PROCESSING",
                result.journalState().map(state -> state.name()).orElse("PROCESSING"),
                1,
                boundedTraceId(traceId));
    }

    private ReservationProcessingResponse toProcessingResponse(
            ReservationLifecycleResult result,
            UUID requestedReservationId,
            String traceId
    ) {
        String journalState = result.operationId()
                .flatMap(journal::findByOperationId)
                .map(entry -> entry.state().name())
                .orElse(result.outcome().name());
        return new ReservationProcessingResponse(
                result.reservation().map(Reservation::id).orElse(requestedReservationId),
                result.operationId().orElse(null),
                "PROCESSING",
                journalState,
                1,
                boundedTraceId(traceId));
    }

    private static String boundedTraceId(String traceId) {
        return traceId == null || traceId.isBlank() || traceId.length() > 128
                ? "unavailable"
                : traceId;
    }

    private static ReservationResponse toResponse(ReservationLifecycleResult result) {
        return toResponse(
                result.reservation().orElse(null),
                result.operationId().orElse(null),
                result.order().map(order -> order.id()).orElse(null),
                result.outcome().name(),
                result.outcome().name(),
                null);
    }

    private static ReservationResponse toResponse(
            Reservation reservation,
            UUID operationId,
            UUID orderId,
            String outcome,
            String resultCode,
            Integer stockAfter
    ) {
        return new ReservationResponse(
                reservation == null ? null : reservation.id(),
                operationId,
                reservation == null ? null : reservation.ticketItemId(),
                reservation == null ? null : reservation.demoActorId(),
                reservation == null ? null : reservation.quantity(),
                reservation == null ? null : reservation.status().name(),
                reservation == null ? null : reservation.expiresAt(),
                reservation == null ? null : reservation.terminalAt(),
                orderId,
                outcome,
                resultCode,
                stockAfter);
    }

    private static InventoryResponse toResponse(InventorySnapshot snapshot) {
        return new InventoryResponse(
                snapshot.ticketItemId(),
                snapshot.initial(),
                snapshot.available(),
                snapshot.reserved(),
                snapshot.confirmed());
    }
}

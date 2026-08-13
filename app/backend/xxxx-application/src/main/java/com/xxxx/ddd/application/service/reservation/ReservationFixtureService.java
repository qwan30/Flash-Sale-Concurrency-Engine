package com.xxxx.ddd.application.service.reservation;

import com.xxxx.ddd.application.reservation.ReservationFixtureResetRequest;
import com.xxxx.ddd.application.reservation.ReservationFixtureResult;
import com.xxxx.ddd.application.reservation.ReservationFixtureGate;
import com.xxxx.ddd.application.reservation.ReservationFixtureEvidence;
import com.xxxx.ddd.application.reservation.port.ReservationFixtureCache;
import com.xxxx.ddd.application.reservation.port.ReservationFixtureRepository;
import org.springframework.stereotype.Service;

import java.util.Objects;

/**
 * Coordinates the explicit local reservation fixture reset.
 *
 * <p>The durable adapter owns its transaction and returns before Redis is
 * reseeded. A process-local write gate also excludes reservation HTTP paths and
 * in-process schedulers while the two stores are being reset. This does not
 * provide a distributed lease for a multi-replica deployment.
 */
@Service
public class ReservationFixtureService {

    private final ReservationFixtureRepository repository;
    private final ReservationFixtureCache cache;
    private final ReservationFixtureGate gate;

    public ReservationFixtureService(
            ReservationFixtureRepository repository,
            ReservationFixtureCache cache,
            ReservationFixtureGate gate
    ) {
        this.repository = Objects.requireNonNull(repository, "repository must not be null");
        this.cache = Objects.requireNonNull(cache, "cache must not be null");
        this.gate = Objects.requireNonNull(gate, "gate must not be null");
    }

    public ReservationFixtureResult reset(ReservationFixtureResetRequest request) {
        Objects.requireNonNull(request, "request must not be null");
        validate(request);
        return gate.withFixtureReset(() -> resetStores(request));
    }

    public ReservationFixtureEvidence evidence(long ticketItemId) {
        if (ticketItemId <= 0) {
            throw new IllegalArgumentException("ticketItemId must be positive");
        }
        return gate.withReservationOperation(() -> {
            ReservationFixtureRepository.EvidenceState durable = repository.evidence(ticketItemId);
            ReservationFixtureCache.EvidenceState redis = cache.readEvidence(ticketItemId);
            long durableInvariant = (long) durable.available() + durable.reserved() + durable.confirmed();
            boolean invariantPass = durable.initial() == durableInvariant;
            // Redis is the fast available-unit/fence mirror. Reserved and confirmed buckets are
            // durable-only counters and are refreshed by repair, not mutated by every Lua admit.
            boolean parityPass = durable.ticketItemId() == redis.ticketItemId()
                    && durable.initial() == redis.initial()
                    && durable.available() == redis.available()
                    && durable.fenceVersion() == redis.fenceVersion()
                    && durable.admissionState().equals(redis.admissionState());
            long negativeStock = Math.max(
                    Math.max(0L, -(long) durable.available()),
                    Math.max(0L, -(long) redis.available()));
            long oversold = Math.max(
                    Math.max(0L, (long) durable.reserved() + durable.confirmed() - durable.initial()),
                    Math.max(0L, (long) redis.reserved() + redis.confirmed() - redis.initial()));
            long drift = Math.abs((long) durable.available() - redis.available());
            return new ReservationFixtureEvidence(
                    ticketItemId,
                    durable.initial(),
                    durable.available(),
                    durable.reserved(),
                    durable.confirmed(),
                    durable.fenceVersion(),
                    durable.admissionState(),
                    redis.initial(),
                    redis.available(),
                    redis.reserved(),
                    redis.confirmed(),
                    redis.fenceVersion(),
                    redis.admissionState(),
                    durable.pendingJournal(),
                    durable.pendingOutbox(),
                    durable.oldestOutboxAgeSeconds(),
                    durable.duplicateReservations(),
                    durable.duplicateOrders(),
                    oversold,
                    negativeStock,
                    drift,
                    Math.max(0L, (long) durable.initial() - durable.available()),
                    invariantPass,
                    parityPass,
                    System.currentTimeMillis());
        });
    }

    private ReservationFixtureResult resetStores(ReservationFixtureResetRequest request) {
        ReservationFixtureRepository.DurableState durable =
                repository.reset(request.ticketItemId(), request.stock());
        cache.reset(
                durable.ticketItemId(),
                durable.initial(),
                durable.available(),
                durable.reserved(),
                durable.confirmed(),
                durable.fenceVersion(),
                durable.admissionState());
        ReservationFixtureCache.CacheState redis = cache.read(request.ticketItemId());

        boolean parity = durable.ticketItemId() == redis.ticketItemId()
                && durable.initial() == redis.initial()
                && durable.available() == redis.available()
                && durable.reserved() == redis.reserved()
                && durable.confirmed() == redis.confirmed()
                && durable.fenceVersion() == redis.fenceVersion()
                && durable.admissionState().equals(redis.admissionState());
        if (!parity) {
            return ReservationFixtureResult.failed(
                    request.ticketItemId(),
                    request.stock(),
                    request.strategy(),
                    "Durable and Redis reservation fixture state diverged");
        }

        return new ReservationFixtureResult(
                true,
                true,
                request.ticketItemId(),
                request.stock(),
                durable.available(),
                redis.available(),
                durable.reserved(),
                durable.confirmed(),
                durable.fenceVersion(),
                durable.admissionState(),
                request.strategy(),
                "Reservation fixture reset");
    }

    private static void validate(ReservationFixtureResetRequest request) {
        if (request.ticketItemId() <= 0) {
            throw new IllegalArgumentException("ticketItemId must be positive");
        }
        if (request.stock() < 0) {
            throw new IllegalArgumentException("stock must not be negative");
        }
        if (request.stock() > 1_000_000) {
            throw new IllegalArgumentException("stock exceeds local fixture limit");
        }
        if (request.strategy() == null || request.strategy().isBlank()
                || request.strategy().length() > 64) {
            throw new IllegalArgumentException("strategy must be 1..64 characters");
        }
        if (!request.reservationFixture()) {
            throw new IllegalArgumentException("reservationFixture must be true");
        }
    }
}

package com.xxxx.ddd.application.MQ;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.repository.query.Param;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/**
 * Spring Data JPA repository for {@link OutboxEvent}.
 */
@Repository
public interface OutboxRepository extends JpaRepository<OutboxEvent, String> {

    List<OutboxEvent> findByStatusOrderByCreatedAtAsc(OutboxStatus status, Pageable pageable);

    List<OutboxEvent> findByStatusAndNextAttemptAtLessThanEqualOrderByNextAttemptAtAscCreatedAtAsc(
            OutboxStatus status, Instant cutoff, Pageable pageable);

    long countByStatus(OutboxStatus status);

    long countByStatusAndNextAttemptAtLessThanEqual(OutboxStatus status, Instant cutoff);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query(value = "UPDATE outbox_event "
            + "SET lease_owner = :leaseOwner, lease_until = :leaseUntil "
            + "WHERE status = 'PENDING' "
            + "AND (lease_until IS NULL OR lease_until <= UTC_TIMESTAMP(3)) "
            + "ORDER BY created_at, id LIMIT :limit", nativeQuery = true)
    int claimPending(
            @Param("leaseOwner") String leaseOwner,
            @Param("leaseUntil") Instant leaseUntil,
            @Param("limit") int limit
    );

    List<OutboxEvent> findByLeaseOwnerAndLeaseUntilOrderByCreatedAtAsc(
            String leaseOwner,
            Instant leaseUntil
    );

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query(value = "UPDATE outbox_event "
            + "SET lease_until = :renewedLeaseUntil "
            + "WHERE id = :eventId AND status = 'PENDING' "
            + "AND lease_owner = :leaseOwner AND lease_until = :leaseUntil "
            + "AND lease_until > :now AND lease_until > UTC_TIMESTAMP(3)", nativeQuery = true)
    int renewLeaseIfOwned(
            @Param("eventId") String eventId,
            @Param("leaseOwner") String leaseOwner,
            @Param("leaseUntil") Instant leaseUntil,
            @Param("renewedLeaseUntil") Instant renewedLeaseUntil,
            @Param("now") Instant now
    );

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query(value = "UPDATE outbox_event "
            + "SET status = 'PUBLISHED', published_at = :publishedAt, failure_message = NULL, "
            + "lease_owner = NULL, lease_until = NULL "
            + "WHERE id = :eventId AND status = 'PENDING' "
            + "AND lease_owner = :leaseOwner AND lease_until = :leaseUntil "
            + "AND lease_until > UTC_TIMESTAMP(3)", nativeQuery = true)
    int markPublishedIfOwned(
            @Param("eventId") String eventId,
            @Param("leaseOwner") String leaseOwner,
            @Param("leaseUntil") Instant leaseUntil,
            @Param("publishedAt") Instant publishedAt
    );

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query(value = "UPDATE outbox_event "
            + "SET status = 'FAILED', failure_message = :failureMessage, "
            + "next_attempt_at = CASE WHEN attempt_count + 1 < :maxAttempts "
            + "THEN :nextAttemptAt ELSE NULL END, "
            + "attempt_count = attempt_count + 1, "
            + "lease_owner = NULL, lease_until = NULL "
            + "WHERE id = :eventId AND status = 'PENDING' "
            + "AND lease_owner = :leaseOwner AND lease_until = :leaseUntil "
            + "AND lease_until > :now AND lease_until > UTC_TIMESTAMP(3)", nativeQuery = true)
    int markFailedIfOwned(
            @Param("eventId") String eventId,
            @Param("leaseOwner") String leaseOwner,
            @Param("leaseUntil") Instant leaseUntil,
            @Param("failureMessage") String failureMessage,
            @Param("nextAttemptAt") Instant nextAttemptAt,
            @Param("now") Instant now,
            @Param("maxAttempts") int maxAttempts
    );

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query(value = "UPDATE outbox_event "
            + "SET status = 'PENDING', failure_message = NULL, next_attempt_at = NULL, "
            + "lease_owner = NULL, lease_until = NULL "
            + "WHERE status = 'FAILED' AND next_attempt_at IS NOT NULL "
            + "AND next_attempt_at <= :now "
            + "ORDER BY next_attempt_at, created_at, id LIMIT :limit", nativeQuery = true)
    int requeueFailed(
            @Param("now") Instant now,
            @Param("limit") int limit
    );
}

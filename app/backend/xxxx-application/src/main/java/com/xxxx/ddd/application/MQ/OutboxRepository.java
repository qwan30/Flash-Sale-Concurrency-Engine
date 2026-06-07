package com.xxxx.ddd.application.MQ;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

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
}

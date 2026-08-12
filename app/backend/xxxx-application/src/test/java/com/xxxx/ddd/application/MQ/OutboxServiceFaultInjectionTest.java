package com.xxxx.ddd.application.MQ;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xxxx.ddd.application.reservation.port.FaultInjectionPort;
import com.xxxx.ddd.application.reservation.port.ReservationTelemetryPort;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OutboxServiceFaultInjectionTest {

    @Test
    void kafkaFaultKeepsClaimedEventRetryableWithoutSending() {
        OutboxRepository repository = mock(OutboxRepository.class);
        KafkaTemplate<String, String> kafka = mock(KafkaTemplate.class);
        FaultInjectionPort faults = mock(FaultInjectionPort.class);
        ReservationTelemetryPort telemetry = mock(ReservationTelemetryPort.class);
        OutboxService service = new OutboxService(
                repository,
                new ObjectMapper().findAndRegisterModules(),
                kafka,
                "flashsale.orders",
                10,
                Duration.ofSeconds(1),
                3,
                Duration.ofSeconds(30));
        service.setFaultInjectionPort(faults);
        service.setReservationTelemetryPort(telemetry);

        OutboxEvent event = new OutboxEvent("chaos-event", "Reservation", "reservation-1",
                "reservation.created", 1, "{}");
        when(repository.findByLeaseOwnerAndLeaseUntilOrderByCreatedAtAsc(anyString(), any(Instant.class)))
                .thenReturn(List.of(event));
        when(repository.renewLeaseIfOwned(anyString(), anyString(), any(Instant.class),
                any(Instant.class), any(Instant.class))).thenReturn(1);
        when(repository.markFailedIfOwned(anyString(), anyString(), any(Instant.class), anyString(),
                any(Instant.class), any(Instant.class), anyInt())).thenReturn(1);
        doThrow(new IllegalStateException("injected Kafka outage"))
                .when(faults).hit(any(FaultInjectionPort.FaultPoint.class), any());

        assertThat(service.publishPendingEvents()).isZero();
        verify(kafka, never()).send(anyString(), anyString(), anyString());
        verify(repository, never()).saveAndFlush(event);
        verify(telemetry).record(eq("outbox.publish"), eq("FAILED"), eq("FAILED"), any(Duration.class));
    }

    @Test
    void staleWorkerCannotFinalizeAfterItsLeaseWasReclaimed() {
        OutboxRepository repository = mock(OutboxRepository.class);
        KafkaTemplate<String, String> kafka = mock(KafkaTemplate.class);
        OutboxService service = new OutboxService(
                repository,
                new ObjectMapper().findAndRegisterModules(),
                kafka,
                "flashsale.orders",
                10,
                Duration.ofSeconds(1),
                3,
                Duration.ofSeconds(30));
        OutboxEvent event = new OutboxEvent("stale-worker-event", "Reservation", "reservation-1",
                "reservation.created", 1, "{}");
        when(repository.findByLeaseOwnerAndLeaseUntilOrderByCreatedAtAsc(anyString(), any(Instant.class)))
                .thenReturn(List.of(event));
        when(repository.renewLeaseIfOwned(anyString(), anyString(), any(Instant.class),
                any(Instant.class), any(Instant.class))).thenReturn(1);
        when(kafka.send(anyString(), anyString(), anyString()))
                .thenReturn(CompletableFuture.completedFuture(null));
        when(repository.markPublishedIfOwned(anyString(), anyString(), any(Instant.class), any(Instant.class)))
                .thenReturn(0);

        assertThat(service.publishPendingEvents()).isZero();
        verify(repository).markPublishedIfOwned(anyString(), anyString(), any(Instant.class), any(Instant.class));
        verify(repository, never()).saveAndFlush(any(OutboxEvent.class));
    }

    @Test
    void doesNotSendWhenLeaseCannotBeRenewedBeforeKafkaSideEffect() {
        OutboxRepository repository = mock(OutboxRepository.class);
        KafkaTemplate<String, String> kafka = mock(KafkaTemplate.class);
        OutboxService service = new OutboxService(
                repository,
                new ObjectMapper().findAndRegisterModules(),
                kafka,
                "flashsale.orders",
                10,
                Duration.ofSeconds(1),
                3,
                Duration.ofSeconds(30));
        OutboxEvent event = new OutboxEvent("lease-expired-event", "Reservation", "reservation-1",
                "reservation.created", 1, "{}");
        when(repository.findByLeaseOwnerAndLeaseUntilOrderByCreatedAtAsc(anyString(), any(Instant.class)))
                .thenReturn(List.of(event));
        when(repository.renewLeaseIfOwned(anyString(), anyString(), any(Instant.class),
                any(Instant.class), any(Instant.class))).thenReturn(0);

        assertThat(service.publishPendingEvents()).isZero();
        verify(kafka, never()).send(anyString(), anyString(), anyString());
        verify(repository, never()).markPublishedIfOwned(anyString(), anyString(), any(Instant.class),
                any(Instant.class));
    }

    @Test
    void returnsOnlySuccessfullyFinalizedPublications() {
        OutboxRepository repository = mock(OutboxRepository.class);
        KafkaTemplate<String, String> kafka = mock(KafkaTemplate.class);
        OutboxService service = new OutboxService(
                repository,
                new ObjectMapper().findAndRegisterModules(),
                kafka,
                "flashsale.orders",
                10,
                Duration.ofSeconds(1),
                3,
                Duration.ofSeconds(30));
        OutboxEvent event = new OutboxEvent("published-event", "Reservation", "reservation-1",
                "reservation.created", 1, "{}");
        when(repository.findByLeaseOwnerAndLeaseUntilOrderByCreatedAtAsc(anyString(), any(Instant.class)))
                .thenReturn(List.of(event));
        when(repository.renewLeaseIfOwned(anyString(), anyString(), any(Instant.class),
                any(Instant.class), any(Instant.class))).thenReturn(1);
        when(kafka.send(anyString(), anyString(), anyString()))
                .thenReturn(CompletableFuture.completedFuture(null));
        when(repository.markPublishedIfOwned(anyString(), anyString(), any(Instant.class), any(Instant.class)))
                .thenReturn(1);

        assertThat(service.publishPendingEvents()).isEqualTo(1);
    }

    @Test
    void retryUsesAnAtomicConditionalRequeueInsteadOfSavingStaleEntities() {
        OutboxRepository repository = mock(OutboxRepository.class);
        OutboxService service = new OutboxService(
                repository,
                new ObjectMapper().findAndRegisterModules(),
                mock(KafkaTemplate.class),
                "flashsale.orders",
                10,
                Duration.ofSeconds(1),
                3,
                Duration.ofSeconds(30));
        when(repository.requeueFailed(any(Instant.class), eq(10))).thenReturn(2);

        assertThat(service.retryFailedEvents()).isEqualTo(2);
        verify(repository).requeueFailed(any(Instant.class), eq(10));
        verify(repository, never()).findByStatusAndNextAttemptAtLessThanEqualOrderByNextAttemptAtAscCreatedAtAsc(
                any(OutboxStatus.class), any(Instant.class), any());
        verify(repository, never()).saveAllAndFlush(any());
    }
}

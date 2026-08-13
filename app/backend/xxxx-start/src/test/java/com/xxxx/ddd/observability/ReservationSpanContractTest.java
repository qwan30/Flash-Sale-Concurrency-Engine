package com.xxxx.ddd.observability;

import com.xxxx.ddd.application.MQ.OutboxService;
import com.xxxx.ddd.application.reservation.ConfirmReservationService;
import com.xxxx.ddd.application.reservation.CreateReservationService;
import com.xxxx.ddd.application.reservation.ExpireReservationService;
import com.xxxx.ddd.application.reservation.ReleaseReservationService;
import com.xxxx.ddd.application.reservation.ReservationRecoveryService;
import io.micrometer.observation.annotation.Observed;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ReservationSpanContractTest {

    @Test
    void exposesTheDocumentedReservationSpanNames() {
        Map<Class<?>, String> expected = Map.of(
                CreateReservationService.class, "create",
                ConfirmReservationService.class, "confirm",
                ReleaseReservationService.class, "release",
                ExpireReservationService.class, "expire",
                ReservationRecoveryService.class, "recover",
                OutboxService.class, "publishPendingEvents");

        expected.forEach((type, methodName) -> {
            Method method = java.util.Arrays.stream(type.getDeclaredMethods())
                    .filter(candidate -> candidate.getName().equals(methodName))
                    .findFirst()
                    .orElseThrow();
            Observed observed = method.getAnnotation(Observed.class);
            assertThat(observed).isNotNull();
            assertThat(observed.name()).isEqualTo(
                    type == OutboxService.class
                            ? "flashsale.outbox.publish"
                            : "flashsale.reservation." + methodName);
        });
    }
}

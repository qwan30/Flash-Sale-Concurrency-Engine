package com.xxxx.ddd.chaos;

import com.xxxx.config.ReservationDefaultAdaptersConfig;
import com.xxxx.ddd.application.reservation.port.FaultInjectionPort;
import com.xxxx.ddd.application.reservation.port.NoOpFaultInjection;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FaultInjectionProfileTest {

    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withUserConfiguration(
                    ReservationDefaultAdaptersConfig.class,
                    ConfigurableFaultInjection.class,
                    ChaosFaultController.class);

    @Test
    void defaultProfileHasNoChaosAdapterOrFaultEndpoint() {
        context.run(application -> {
            assertThat(application.getBean(FaultInjectionPort.class)).isInstanceOf(NoOpFaultInjection.class);
            assertThat(application.containsBean("configurableFaultInjection")).isFalse();
            assertThat(application.containsBean("chaosFaultController")).isFalse();
        });
    }

    @Test
    void chaosProfileExposesOnlyFiniteCatalog() {
        context.withPropertyValues("spring.profiles.active=chaos").run(application -> {
            ConfigurableFaultInjection faults = application.getBean(ConfigurableFaultInjection.class);
            assertThat(application.getBean(FaultInjectionPort.class)).isSameAs(faults);
            assertThat(application.getBean(ChaosFaultController.class)).isNotNull();
            assertThat(faults.catalog())
                    .containsExactly(FaultInjectionPort.FaultPoint.values());
            assertThat(faults.active()).isNull();
        });
    }

    @Test
    void activeFaultIsDeterministicAndCanBeCleared() {
        ConfigurableFaultInjection faults = new ConfigurableFaultInjection();
        faults.activate(FaultInjectionPort.FaultPoint.KAFKA_UNAVAILABLE);

        assertThatThrownBy(() -> faults.hit(
                FaultInjectionPort.FaultPoint.KAFKA_UNAVAILABLE,
                java.util.UUID.randomUUID()))
                .isInstanceOf(InjectedFaultException.class);

        faults.clear();
        faults.hit(FaultInjectionPort.FaultPoint.KAFKA_UNAVAILABLE, java.util.UUID.randomUUID());
    }
}

package com.xxxx.ddd.controller.http;

import com.xxxx.ddd.application.service.reservation.ReservationFixtureService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class ReservationFixtureBenchmarkControllerConfigurationTest {

    private final ApplicationContextRunner benchmarkProfileContext = new ApplicationContextRunner()
            .withInitializer(context -> context.getEnvironment().setActiveProfiles("benchmark"))
            .withUserConfiguration(BenchmarkFixtureControllerConfiguration.class);

    @Test
    void fixtureRoutesAreAbsentWhenTheBenchmarkProfileIsNotActive() {
        new ApplicationContextRunner()
                .withPropertyValues("benchmark.fixture-reset-enabled=true")
                .withUserConfiguration(BenchmarkFixtureControllerConfiguration.class)
                .run(context -> assertThat(context)
                        .doesNotHaveBean(ReservationFixtureBenchmarkController.class));
    }

    @Test
    void fixtureRoutesAreAbsentWhenTheExplicitEnablementFlagIsFalse() {
        benchmarkProfileContext
                .withPropertyValues("benchmark.fixture-reset-enabled=false")
                .run(context -> assertThat(context)
                        .doesNotHaveBean(ReservationFixtureBenchmarkController.class));
    }

    @Test
    void fixtureRoutesExistOnlyForAnExplicitlyEnabledBenchmarkProfile() {
        benchmarkProfileContext
                .withPropertyValues("benchmark.fixture-reset-enabled=true")
                .run(context -> assertThat(context)
                        .hasSingleBean(ReservationFixtureBenchmarkController.class));
    }

    @Configuration(proxyBeanMethods = false)
    @Import(ReservationFixtureBenchmarkController.class)
    static class BenchmarkFixtureControllerConfiguration {

        @Bean
        ReservationFixtureService reservationFixtureService() {
            return mock(ReservationFixtureService.class);
        }
    }
}

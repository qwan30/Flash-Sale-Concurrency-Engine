package com.xxxx.ddd.controller.http;

import com.xxxx.ddd.application.service.order.OrderReconciliationService;
import com.xxxx.ddd.application.service.order.TicketOrderAppService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class BenchmarkControlControllerConfigurationTest {

    private final ApplicationContextRunner benchmarkProfileContext = new ApplicationContextRunner()
            .withInitializer(context -> context.getEnvironment().setActiveProfiles("benchmark"))
            .withUserConfiguration(BenchmarkControlControllerConfiguration.class);

    @Test
    void destructiveOperatorControlsAreAbsentOutsideTheBenchmarkProfile() {
        new ApplicationContextRunner()
                .withPropertyValues("benchmark.control-enabled=true")
                .withUserConfiguration(BenchmarkControlControllerConfiguration.class)
                .run(context -> assertThat(context).doesNotHaveBean(BenchmarkControlController.class));
    }

    @Test
    void destructiveOperatorControlsRequireExplicitEnablement() {
        benchmarkProfileContext
                .withPropertyValues("benchmark.control-enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean(BenchmarkControlController.class));
    }

    @Test
    void destructiveOperatorControlsExistOnlyForAnEnabledBenchmarkProfile() {
        benchmarkProfileContext
                .withPropertyValues("benchmark.control-enabled=true")
                .run(context -> assertThat(context).hasSingleBean(BenchmarkControlController.class));
    }

    @Configuration(proxyBeanMethods = false)
    @Import(BenchmarkControlController.class)
    static class BenchmarkControlControllerConfiguration {

        @Bean
        TicketOrderAppService ticketOrderAppService() {
            return mock(TicketOrderAppService.class);
        }

        @Bean
        OrderReconciliationService orderReconciliationService() {
            return mock(OrderReconciliationService.class);
        }
    }
}

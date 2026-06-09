package com.xxxx.config;

import io.micrometer.observation.ObservationRegistry;
import io.micrometer.observation.aop.ObservedAspect;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Enables Micrometer Observation-based tracing and wires the {@code @Observed} AOP aspect.
 *
 * <p>Micrometer Tracing (via Brave bridge) is auto-configured by Spring Boot 3.x.
 * This configuration explicitly registers the {@link ObservedAspect} so
 * {@code @Observed} annotations on service methods create named spans,
 * and ensures trace context (traceId/spanId) is propagated through the
 * MDC for structured log correlation.
 */
@Configuration(proxyBeanMethods = false)
public class ObservationConfig {

    @Bean
    public ObservedAspect observedAspect(ObservationRegistry observationRegistry) {
        return new ObservedAspect(observationRegistry);
    }
}

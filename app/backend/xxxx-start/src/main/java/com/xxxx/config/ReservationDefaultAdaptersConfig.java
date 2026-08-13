package com.xxxx.config;

import com.xxxx.ddd.application.reservation.port.FaultInjectionPort;
import com.xxxx.ddd.application.reservation.port.NoOpFaultInjection;
import com.xxxx.ddd.application.reservation.port.NoOpReservationTelemetry;
import com.xxxx.ddd.application.reservation.port.ReservationTelemetryPort;
import com.xxxx.ddd.observability.ReservationTelemetryAdapter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;

/**
 * Default production adapters for optional reservation observability and fault injection.
 *
 * <p>The chaos profile replaces only the default fault adapter; telemetry remains available
 * in every profile so reservation services keep the same dependency contract.
 */
@Configuration(proxyBeanMethods = false)
public class ReservationDefaultAdaptersConfig {

    @Bean
    @ConditionalOnBean(MeterRegistry.class)
    @ConditionalOnMissingBean(ReservationTelemetryPort.class)
    ReservationTelemetryPort reservationTelemetryPort(
            MeterRegistry meterRegistry,
            JdbcTemplate jdbc,
            StringRedisTemplate redis
    ) {
        return new ReservationTelemetryAdapter(meterRegistry, jdbc, redis);
    }

    @Bean
    @ConditionalOnMissingBean(ReservationTelemetryPort.class)
    ReservationTelemetryPort noOpReservationTelemetryPort() {
        return new NoOpReservationTelemetry();
    }

    @Bean
    @Profile("!chaos")
    FaultInjectionPort faultInjectionPort() {
        return new NoOpFaultInjection();
    }
}

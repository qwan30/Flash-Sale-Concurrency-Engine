package com.xxxx.ddd.integration;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Source-level smoke contract for the reversible, default-off OTel evidence lane.
 * It intentionally does not claim a live collector pipeline without Docker and an OTLP runtime bridge.
 */
class OtelPipelineSmokeIntegrationTest {

    @Test
    void otelLaneIsOptInAndContainsTheRequiredEvidenceArtifacts() throws java.io.IOException {
        Path applicationConfig = findRepositoryFile("app/backend/xxxx-start/src/main/resources/application-otel.yml");
        assertThat(Files.exists(applicationConfig)).isTrue();
        assertThat(Files.exists(findRepositoryFile("environment/otel/otel-collector.yml"))).isTrue();
        assertThat(Files.exists(findRepositoryFile("benchmark/flash-sale-reservation-k6.js"))).isTrue();
        assertThat(Files.readString(applicationConfig))
                .contains("on-profile: otel");
    }

    private static Path findRepositoryFile(String relativePath) {
        Path cursor = Path.of("").toAbsolutePath();
        while (cursor != null) {
            Path candidate = cursor.resolve(relativePath);
            if (Files.exists(candidate)) {
                return candidate;
            }
            cursor = cursor.getParent();
        }
        return Path.of(relativePath);
    }
}

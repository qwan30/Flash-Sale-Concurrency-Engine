package com.xxxx.ddd.application.service.benchmark;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xxxx.ddd.application.model.benchmark.BenchmarkRunDetail;
import com.xxxx.ddd.application.model.benchmark.BenchmarkRunSummary;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Reads persisted benchmark run manifests from the local results directory.
 *
 * <p>The JMeter script owns writing {@code run.json}; this service only validates path input and
 * presents the saved evidence to the operator dashboard.
 */
@Service
public class BenchmarkRunService {

    private final ObjectMapper objectMapper;
    private final Path resultsPath;

    public BenchmarkRunService(
            ObjectMapper objectMapper,
            @Value("${benchmark.results-dir:benchmark/results}") String resultsDir
    ) {
        this.objectMapper = objectMapper;
        this.resultsPath = Path.of(resultsDir).normalize();
    }

    public List<BenchmarkRunSummary> listRuns() {
        if (!Files.isDirectory(resultsPath)) {
            return List.of();
        }

        try (var stream = Files.list(resultsPath)) {
            return stream
                    .filter(Files::isDirectory)
                    .map(this::readRun)
                    .flatMap(Optional::stream)
                    .map(BenchmarkRunDetail::getSummary)
                    .sorted(Comparator.comparing(BenchmarkRunSummary::getRunId, Comparator.nullsLast(String::compareTo))
                            .reversed())
                    .toList();
        } catch (IOException e) {
            throw new RuntimeException("Failed to list benchmark runs", e);
        }
    }

    public BenchmarkRunDetail getRun(String runId) {
        // Keep runId as a directory name only; never allow path traversal through the API.
        if (runId == null || !runId.matches("[A-Za-z0-9_.-]+")) {
            return null;
        }

        Path runPath = resultsPath.resolve(runId).normalize();
        if (!runPath.startsWith(resultsPath)) {
            return null;
        }
        return readRun(runPath).orElse(null);
    }

    private Optional<BenchmarkRunDetail> readRun(Path runPath) {
        Path runFile = runPath.resolve("run.json");
        if (!Files.isRegularFile(runFile)) {
            return Optional.empty();
        }

        try {
            BenchmarkRunDetail detail = objectMapper.readValue(runFile.toFile(), BenchmarkRunDetail.class);
            if (detail.getSummary() != null && detail.getSummary().getRunId() == null) {
                detail.getSummary().setRunId(runPath.getFileName().toString());
            }
            return Optional.of(detail);
        } catch (IOException e) {
            throw new RuntimeException("Failed to read benchmark run " + runPath.getFileName(), e);
        }
    }
}

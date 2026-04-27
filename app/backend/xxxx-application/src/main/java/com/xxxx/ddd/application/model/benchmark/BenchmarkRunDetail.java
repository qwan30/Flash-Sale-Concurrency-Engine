package com.xxxx.ddd.application.model.benchmark;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BenchmarkRunDetail {
    private BenchmarkRunSummary summary;
    private Map<String, Object> reset;
    private Map<String, Object> warmup;
    private Map<String, Object> consistency;
    private Map<String, String> artifacts;
}

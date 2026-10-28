package com.aegisx.integrity.controller;

import com.aegisx.common.response.ApiResponse;
import com.aegisx.integrity.benchmark.FaultKind;
import com.aegisx.integrity.benchmark.IntegrityBenchmarkService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Module Phase-7 endpoint: run the comparative benchmark and read the last result.
 *
 * <p>The run is deterministic, so repeating it with the same seed reproduces the same numbers. It is
 * restricted to admin/compliance roles because it is an evaluation tool, not an operational one.</p>
 */
@RestController
@RequestMapping("/api/integrity/benchmark")
@RequiredArgsConstructor
public class IntegrityBenchmarkController {

    private final IntegrityBenchmarkService benchmarkService;

    @PostMapping("/run")
    @PreAuthorize("hasAnyRole('ADMIN','COMPLIANCE_OFFICER')")
    public ResponseEntity<ApiResponse<IntegrityBenchmarkService.BenchmarkReport>> run(
            @RequestParam(required = false) Long seed,
            @RequestParam(required = false) Integer repetitionsPerKind) {
        long effectiveSeed = seed == null ? IntegrityBenchmarkService.DEFAULT_SEED : seed;
        int effectiveRepetitions = repetitionsPerKind == null
                ? IntegrityBenchmarkService.DEFAULT_REPETITIONS
                : Math.min(Math.max(repetitionsPerKind, 1), 200);
        return ResponseEntity.ok(ApiResponse.success(
                benchmarkService.run(effectiveSeed, effectiveRepetitions, true),
                "Benchmark completed; artefacts written under the configured benchmark output directory"));
    }

    @GetMapping("/method")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse<Map<String, Object>>> method() {
        long evidenceKinds = java.util.Arrays.stream(FaultKind.values())
                .filter(FaultKind::isEvidenceKind).count();
        return ResponseEntity.ok(ApiResponse.success(Map.of(
                "seed", IntegrityBenchmarkService.DEFAULT_SEED,
                "repetitionsPerFaultKind", IntegrityBenchmarkService.DEFAULT_REPETITIONS,
                "faultKinds", FaultKind.values().length,
                "paymentFaultKinds", FaultKind.values().length - evidenceKinds,
                "evidenceFaultKinds", evidenceKinds,
                "note", "Deterministic and reproducible. Results are synthetic and are not evidence of "
                        + "real-world fraud-prevention performance."), "Benchmark method"));
    }
}

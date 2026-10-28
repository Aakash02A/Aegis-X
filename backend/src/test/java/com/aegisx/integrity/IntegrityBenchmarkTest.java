package com.aegisx.integrity;

import com.aegisx.integrity.benchmark.FaultKind;
import com.aegisx.integrity.benchmark.IntegrityBenchmarkService;
import com.aegisx.integrity.support.IntegrityTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 7 acceptance tests: the comparative benchmark.
 *
 * <p>Covers acceptance criterion 13 of the specification. The benchmark is deterministic, so the
 * numbers are asserted rather than merely printed — a regression in the decision procedure shows up
 * as a failing test instead of a quietly changed table in a report.</p>
 *
 * <p>What is asserted here is deliberately restricted to properties that are true <em>by
 * construction</em> (identical inputs, determinism, the safety invariant) and to the one directional
 * claim the design supports: a comparison that only looks at the latest status and amount cannot
 * detect a stale, conflicting or duplicated lifecycle observation, so its recall must not exceed the
 * proposed method's. The absolute metric values are reported in the generated artefacts, not
 * hard-coded into this test.</p>
 */
@DisplayName("Research benchmark")
class IntegrityBenchmarkTest extends IntegrityTestSupport {

    @Test
    @DisplayName("compares all three methods on identical evidence and exports machine-readable results")
    void runsAllThreeMethodsAndExportsArtefacts() {
        IntegrityBenchmarkService.BenchmarkReport report =
                benchmarkService.run(IntegrityBenchmarkService.DEFAULT_SEED,
                        IntegrityBenchmarkService.DEFAULT_REPETITIONS, true);

        Map<String, IntegrityBenchmarkService.MethodReport> byMethod = report.methods().stream()
                .collect(Collectors.toMap(IntegrityBenchmarkService.MethodReport::method, Function.identity()));

        assertThat(byMethod).containsOnlyKeys(
                "BASELINE_1_DIRECT_COMPARISON",
                "BASELINE_2_RULES_ENGINE",
                "PROPOSED_EVIDENCE_BASED");

        long evidenceKinds = java.util.Arrays.stream(FaultKind.values())
                .filter(FaultKind::isEvidenceKind).count();
        assertThat(report.faultKindCount())
                .as("the benchmark must cover every declared controlled fault kind")
                .isEqualTo(FaultKind.values().length);
        assertThat(report.scenarioCount())
                .isEqualTo(FaultKind.values().length * IntegrityBenchmarkService.DEFAULT_REPETITIONS);
        assertThat(byMethod.values())
                .as("every method must see the same scenarios")
                .allSatisfy(method -> assertThat(method.scenarios()).isEqualTo(report.scenarioCount()));

        assertThat(byMethod.values())
                .as("no method may execute a money-moving action without a human")
                .allSatisfy(method -> assertThat(method.unsafeAutomaticActions()).isZero());

        assertThat(report.details())
                .as("an evidence fault must not corrupt the money side: no payment method may report "
                        + "anything on a scenario whose payment pack is clean")
                .filteredOn(detail -> detail.faultKind().startsWith("EVIDENCE_"))
                .isNotEmpty()
                .allSatisfy(detail -> assertThat(detail.falsePositives()).isEmpty());

        IntegrityBenchmarkService.EvidenceCheckReport evidence = report.evidenceChecks();
        assertThat(evidence.method()).isEqualTo(IntegrityBenchmarkService.EVIDENCE_CHECK_METHOD);
        assertThat(evidence.scenarios())
                .as("the evidence checks run on every evidence fault kind")
                .isEqualTo((int) evidenceKinds * IntegrityBenchmarkService.DEFAULT_REPETITIONS);
        assertThat(evidence.details()).hasSize(evidence.scenarios());
        assertThat(evidence.unsafeAutomaticActions())
                .as("no evidence recommendation may move money without a human")
                .isZero();
        assertThat(evidence.recall())
                .as("regression guard: the declared evidence faults are deterministic and every one of "
                        + "them must be detected — this is not an accuracy claim about real documents")
                .isEqualTo(1.0);
        assertThat(evidence.precision())
                .as("regression guard: the checks must stay silent on the clean scenario")
                .isEqualTo(1.0);

        IntegrityBenchmarkService.MethodReport baseline1 = byMethod.get("BASELINE_1_DIRECT_COMPARISON");
        IntegrityBenchmarkService.MethodReport proposed = byMethod.get("PROPOSED_EVIDENCE_BASED");

        assertThat(proposed.recall())
                .as("a status/amount comparison cannot see stale, conflicting or duplicated observations")
                .isGreaterThanOrEqualTo(baseline1.recall());
        assertThat(proposed.f1())
                .as("the evidence-based procedure must not be worse overall than the naive comparison")
                .isGreaterThanOrEqualTo(baseline1.f1());

        assertThat(report.limitations())
                .as("synthetic results must be published with their limitations")
                .isNotEmpty();
        assertThat(report.methodologyNote()).containsIgnoringCase("deterministic");

        Path json = Path.of(properties.getBenchmarkOutputDir()).resolve("integrity-benchmark-results.json");
        Path markdown = Path.of(properties.getBenchmarkOutputDir()).resolve("integrity-benchmark-results.md");
        assertThat(Files.exists(json)).as("JSON artefacts must be written for reproducibility").isTrue();
        assertThat(Files.exists(markdown)).as("a human-readable table must be written").isTrue();
    }

    @Test
    @DisplayName("repeating the run with the same seed reproduces the same numbers")
    void isDeterministic() {
        IntegrityBenchmarkService.BenchmarkReport first =
                benchmarkService.run(20260101L, 4, false);
        IntegrityBenchmarkService.BenchmarkReport second =
                benchmarkService.run(20260101L, 4, false);

        assertThat(second.scenarioCount()).isEqualTo(first.scenarioCount());
        for (int index = 0; index < first.methods().size(); index++) {
            IntegrityBenchmarkService.MethodReport a = first.methods().get(index);
            IntegrityBenchmarkService.MethodReport b = second.methods().get(index);
            assertThat(b.method()).isEqualTo(a.method());
            assertThat(b.precision()).isEqualTo(a.precision());
            assertThat(b.recall()).isEqualTo(a.recall());
            assertThat(b.f1()).isEqualTo(a.f1());
            assertThat(b.humanReviewRate()).isEqualTo(a.humanReviewRate());
            assertThat(b.medianInvestigationSteps()).isEqualTo(a.medianInvestigationSteps());
        }
        assertThat(second.evidenceChecks().precision()).isEqualTo(first.evidenceChecks().precision());
        assertThat(second.evidenceChecks().recall()).isEqualTo(first.evidenceChecks().recall());
        assertThat(second.evidenceChecks().details()).isEqualTo(first.evidenceChecks().details());
    }

    @Test
    @DisplayName("every finding the proposed method reports is classified, and every scenario is scored")
    void reportsPerScenarioDetail() {
        IntegrityBenchmarkService.BenchmarkReport report =
                benchmarkService.run(IntegrityBenchmarkService.DEFAULT_SEED, 2, false);

        assertThat(report.details()).isNotEmpty();
        assertThat(report.details())
                .as("each method is scored separately for each scenario")
                .hasSize(report.scenarioCount() * report.methods().size());
        assertThat(report.details())
                .allSatisfy(detail -> {
                    assertThat(detail.scenarioId()).isNotBlank();
                    assertThat(detail.faultKind()).isNotBlank();
                    assertThat(detail.investigationSteps()).isNotNegative();
                    assertThat(detail.humanReviewFindings()).isNotNegative();
                });
        assertThat(report.evidenceChecks().details())
                .as("every evidence scenario is scored and classified")
                .isNotEmpty()
                .allSatisfy(detail -> {
                    assertThat(detail.method()).isEqualTo(IntegrityBenchmarkService.EVIDENCE_CHECK_METHOD);
                    assertThat(detail.faultKind()).startsWith("EVIDENCE_");
                    assertThat(detail.investigationSteps()).isNotNegative();
                });
    }
}

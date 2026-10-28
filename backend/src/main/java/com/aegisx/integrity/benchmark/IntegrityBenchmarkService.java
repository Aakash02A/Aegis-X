package com.aegisx.integrity.benchmark;

import com.aegisx.integrity.config.IntegrityProperties;
import com.aegisx.integrity.domain.FindingType;
import com.aegisx.integrity.domain.RecommendationCategory;
import com.aegisx.integrity.evidence.EvidenceCheckService;
import com.aegisx.integrity.recovery.RecommendationPlan;
import com.aegisx.integrity.recovery.RecommendationPolicy;
import com.aegisx.integrity.reconciliation.ReconciliationMethodRegistry;
import com.aegisx.integrity.reconciliation.model.FindingCandidate;
import com.aegisx.integrity.reconciliation.model.ReconciliationMethod;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * Runs the research benchmark: the proposed method against both baselines on identical evidence.
 *
 * <p>The benchmark runs entirely in memory over generated scenarios, but it calls the <em>real</em>
 * comparator beans and the real recommendation policy. Nothing is re-implemented for the
 * evaluation, so a change in production logic changes the measured numbers.</p>
 *
 * <h2>What is measured, and how</h2>
 * <ul>
 *   <li><strong>Precision / recall / F1</strong> over finding <em>types</em> per scenario, summed
 *       across scenarios. A scenario's {@code required} set is its oracle; its {@code acceptable}
 *       set lists types that are also legitimately reportable and therefore not counted as noise.</li>
 *   <li><strong>Incorrectly cleared</strong> — scenarios where every critical finding was missed.</li>
 *   <li><strong>Unreported issues</strong> — the raw false-negative count.</li>
 *   <li><strong>Unsafe automatic actions</strong> — findings whose recommendation would execute
 *       without a human <em>and</em> move money. Structurally zero here; measured rather than
 *       asserted so the claim is falsifiable.</li>
 *   <li><strong>Spurious financial advice</strong> — money-moving recommendations raised for a
 *       finding the oracle did not require.</li>
 *   <li><strong>Human-review rate</strong> — share of reported findings that a human must action.</li>
 *   <li><strong>Investigation steps</strong> — a documented proxy, not measured human time:
 *       {@code humanReviewFindings + falsePositives + 2 x missedRequiredFindings}. Missing an issue
 *       costs more than reviewing a spurious one because it resurfaces later.</li>
 * </ul>
 *
 * <h2>The evidence dimension</h2>
 * <p>Evidence fault kinds carry two views of the same scenario: a <em>clean</em> payment pack, which
 * the three reconciliation methods receive and are scored for false positives on, and a paperwork
 * view that the real {@link EvidenceCheckService} — the same bean the production assessment
 * calls — is run against. The evidence checks are therefore measured on the same footing as the
 * reconciliation methods, with the same oracle discipline, rather than asserted to work.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IntegrityBenchmarkService {

    public static final long DEFAULT_SEED = 20261010L;
    public static final int DEFAULT_REPETITIONS = 12;

    /** Method label used for the evidence-check rows in the report and its artefacts. */
    public static final String EVIDENCE_CHECK_METHOD = "EVIDENCE_CHECKS";

    private final BenchmarkScenarioFactory scenarioFactory;
    private final ReconciliationMethodRegistry methodRegistry;
    private final RecommendationPolicy recommendationPolicy;
    private final EvidenceCheckService evidenceChecks;
    private final IntegrityProperties properties;
    private final ObjectMapper objectMapper;

    public record ScenarioResult(
            String scenarioId,
            String faultKind,
            boolean variantB,
            String method,
            List<String> required,
            List<String> detected,
            List<String> falsePositives,
            List<String> missed,
            boolean incorrectlyCleared,
            int humanReviewFindings,
            int investigationSteps,
            int spuriousFinancialAdvice) { }

    public record MethodReport(
            String method,
            String displayName,
            int scenarios,
            int truePositives,
            int falsePositives,
            int falseNegatives,
            double precision,
            double recall,
            double f1,
            int incorrectlyClearedScenarios,
            int unreportedIssues,
            int unsafeAutomaticActions,
            int spuriousFinancialAdvice,
            double humanReviewRate,
            double medianInvestigationSteps,
            long elapsedMillis) { }

    /**
     * How the deterministic evidence checks scored on the paperwork view of the same scenarios.
     *
     * <p>Deliberately a separate section rather than a fourth entry in {@code methods}: the checks
     * take document facts, not an {@code EvidencePack}, and only run on the scenarios that carry a
     * paperwork view. The scoring rules are identical to the ones above.</p>
     */
    public record EvidenceCheckReport(
            String method,
            String displayName,
            int scenarios,
            int truePositives,
            int falsePositives,
            int falseNegatives,
            double precision,
            double recall,
            double f1,
            int incorrectlyClearedScenarios,
            int unsafeAutomaticActions,
            double humanReviewRate,
            List<ScenarioResult> details) { }

    public record BenchmarkReport(
            String generatedAt,
            long seed,
            int repetitionsPerFaultKind,
            int scenarioCount,
            int faultKindCount,
            List<MethodReport> methods,
            EvidenceCheckReport evidenceChecks,
            List<ScenarioResult> details,
            String methodologyNote,
            List<String> limitations) { }

    public BenchmarkReport run(long seed, int repetitionsPerKind, boolean writeArtefacts) {
        List<BenchmarkScenario> scenarios = scenarioFactory.generate(seed, repetitionsPerKind);
        List<ScenarioResult> details = new ArrayList<>();
        List<MethodReport> methodReports = new ArrayList<>();
        EvidenceCheckReport evidenceReport = runEvidenceChecks(scenarios);

        for (ReconciliationMethod method : methodRegistry.all()) {
            long started = System.nanoTime();
            int tp = 0;
            int fp = 0;
            int fn = 0;
            int incorrectlyCleared = 0;
            int unsafeAutoActions = 0;
            int spuriousFinancial = 0;
            int humanReviewTotal = 0;
            int detectedTotal = 0;
            List<Integer> stepsPerScenario = new ArrayList<>();

            for (BenchmarkScenario scenario : scenarios) {
                List<FindingCandidate> findings = method.evaluate(scenario.pack());
                Set<FindingType> detected = new LinkedHashSet<>();
                for (FindingCandidate finding : findings) {
                    detected.add(finding.type());
                }

                Set<FindingType> required = paymentSideOracle(scenario, scenario.required());
                Set<FindingType> accepted = new LinkedHashSet<>(required);
                accepted.addAll(scenario.acceptable());

                Set<FindingType> hits = new LinkedHashSet<>(detected);
                hits.retainAll(required);
                Set<FindingType> missed = new LinkedHashSet<>(required);
                missed.removeAll(detected);
                Set<FindingType> noise = new LinkedHashSet<>(detected);
                noise.removeAll(accepted);

                tp += hits.size();
                fn += missed.size();
                fp += noise.size();

                Set<FindingType> critical = paymentSideOracle(scenario, scenario.criticalRequired());
                boolean cleared = !critical.isEmpty()
                        && java.util.Collections.disjoint(detected, critical);
                if (cleared) {
                    incorrectlyCleared++;
                }

                int humanReview = 0;
                int scenarioSpuriousFinancial = 0;
                for (FindingCandidate finding : findings) {
                    List<RecommendationPlan> plans = recommendationPolicy.planFor(finding.type(), scenario.pack());
                    for (RecommendationPlan plan : plans) {
                        // A plan that needs no human approval executes unattended. Count it as unsafe
                        // if it would move money. This is a real check against the policy, not a
                        // hard-coded zero: if the policy ever marked a financial plan as
                        // approval-free, this counter would move.
                        if (!plan.requiresHumanApproval()) {
                            if (plan.category() == RecommendationCategory.FINANCIAL) {
                                unsafeAutoActions++;
                            }
                            continue;
                        }
                        humanReview++;
                        if (plan.category() == RecommendationCategory.FINANCIAL && !required.contains(finding.type())) {
                            spuriousFinancial++;
                            scenarioSpuriousFinancial++;
                        }
                    }
                }
                humanReviewTotal += humanReview;
                detectedTotal += findings.size();

                int steps = humanReview + noise.size() + 2 * missed.size();
                // The effort proxy is measured over scenarios that plausibly need attention. An
                // evidence scenario a method correctly ignores is not an investigation; one it
                // flagged (a false positive on a clean payment pack) is, and is counted.
                if (!scenario.kind().isEvidenceKind() || !findings.isEmpty()) {
                    stepsPerScenario.add(steps);
                }

                details.add(new ScenarioResult(scenario.scenarioId(), scenario.kind().name(), scenario.variantB(),
                        method.strategy().name(), sorted(required), sorted(detected), sorted(noise),
                        sorted(missed), cleared, humanReview, steps, scenarioSpuriousFinancial));
            }

            long elapsedMillis = (System.nanoTime() - started) / 1_000_000L;
            double precision = tp + fp == 0 ? 0.0 : (double) tp / (tp + fp);
            double recall = tp + fn == 0 ? 0.0 : (double) tp / (tp + fn);
            double f1 = precision + recall == 0 ? 0.0 : 2 * precision * recall / (precision + recall);
            double humanReviewRate = detectedTotal == 0 ? 0.0 : (double) humanReviewTotal / detectedTotal;

            methodReports.add(new MethodReport(
                    method.strategy().name(),
                    method.displayName(),
                    scenarios.size(),
                    tp, fp, fn,
                    round(precision), round(recall), round(f1),
                    incorrectlyCleared, fn, unsafeAutoActions, spuriousFinancial,
                    round(humanReviewRate), round(median(stepsPerScenario)), elapsedMillis));
        }

        BenchmarkReport report = new BenchmarkReport(
                Instant.now().toString(),
                seed,
                repetitionsPerKind,
                scenarios.size(),
                FaultKind.values().length,
                List.copyOf(methodReports),
                evidenceReport,
                List.copyOf(details),
                "Synthetic, deterministic and reproducible. All three methods receive identical EvidencePacks; only the "
                        + "decision procedure differs. The evidence checks receive the paperwork view of the evidence "
                        + "scenarios and are scored by the same rules. Oracle sets are declared in "
                        + "BenchmarkScenarioFactory before any method or check runs and are not derived from output.",
                List.of(
                        "Synthetic data only: these numbers say nothing about real-world fraud-prevention performance.",
                        "Faults are injected by Aegis-X, so the benchmark cannot measure faults Aegis-X did not anticipate.",
                        "Ground truth and the generator come from the same authors (circularity risk).",
                        "Evidence checks score against declared oracles over synthetic document facts; extraction accuracy on real scanned documents is not measured here.",
                        "Investigation steps is a proxy for human effort, not measured human time.",
                        "No live Razorpay round trip is exercised by this benchmark; see the integration tests for adapter behaviour.",
                        "Scenario counts are in the low hundreds, not production volume."));

        if (writeArtefacts) {
            write(report);
        }
        return report;
    }

    /**
     * The oracle as the <em>payment</em> dimension sees it.
     *
     * <p>An evidence scenario's oracle lists evidence findings, which a reconciliation method cannot
     * produce and never claims to. Counting them as the methods' misses would make the headline
     * recall numbers answer "can a payment method read paperwork?" — a question nobody asked it.
     * The evidence findings are scored in the evidence-checks pass instead, and the methods keep
     * the part of the scenario that <em>is</em> theirs: their pack is clean, so anything they report
     * on it is a genuine false positive.</p>
     */
    private static Set<FindingType> paymentSideOracle(BenchmarkScenario scenario, Set<FindingType> oracle) {
        return scenario.kind().isEvidenceKind() ? Set.of() : oracle;
    }

    /**
     * Runs the real evidence checks over every scenario that carries a paperwork view.
     *
     * <p>Scored by exactly the machinery the methods use: the scenario's oracle decides what counts
     * as a hit, and the real recommendation policy decides what a human would have to action. Nothing
     * here re-implements a rule for the evaluation, so a change in production logic changes these
     * numbers.</p>
     */
    private EvidenceCheckReport runEvidenceChecks(List<BenchmarkScenario> scenarios) {
        long started = System.nanoTime();
        int tp = 0;
        int fp = 0;
        int fn = 0;
        int incorrectlyCleared = 0;
        int unsafeAutoActions = 0;
        int humanReviewTotal = 0;
        int detectedTotal = 0;
        int evaluated = 0;
        List<ScenarioResult> details = new ArrayList<>();

        for (BenchmarkScenario scenario : scenarios) {
            if (!scenario.hasEvidenceInput()) {
                continue;
            }
            evaluated++;
            List<FindingCandidate> findings = evidenceChecks.evaluate(scenario.evidenceInput());
            Set<FindingType> detected = new LinkedHashSet<>();
            for (FindingCandidate finding : findings) {
                detected.add(finding.type());
            }

            Set<FindingType> required = scenario.required();
            Set<FindingType> accepted = new LinkedHashSet<>(required);
            accepted.addAll(scenario.acceptable());

            Set<FindingType> hits = new LinkedHashSet<>(detected);
            hits.retainAll(required);
            Set<FindingType> missed = new LinkedHashSet<>(required);
            missed.removeAll(detected);
            Set<FindingType> noise = new LinkedHashSet<>(detected);
            noise.removeAll(accepted);

            tp += hits.size();
            fn += missed.size();
            fp += noise.size();

            boolean cleared = !scenario.criticalRequired().isEmpty()
                    && java.util.Collections.disjoint(detected, scenario.criticalRequired());
            if (cleared) {
                incorrectlyCleared++;
            }

            int humanReview = 0;
            for (FindingCandidate finding : findings) {
                for (RecommendationPlan plan : recommendationPolicy.planFor(finding.type(), scenario.pack())) {
                    if (!plan.requiresHumanApproval()) {
                        if (plan.category() == RecommendationCategory.FINANCIAL) {
                            unsafeAutoActions++;
                        }
                        continue;
                    }
                    humanReview++;
                }
            }
            humanReviewTotal += humanReview;
            detectedTotal += findings.size();
            int steps = humanReview + noise.size() + 2 * missed.size();

            details.add(new ScenarioResult(scenario.scenarioId(), scenario.kind().name(), scenario.variantB(),
                    EVIDENCE_CHECK_METHOD, sorted(required), sorted(detected), sorted(noise),
                    sorted(missed), cleared, humanReview, steps, 0));
        }

        double precision = tp + fp == 0 ? 0.0 : (double) tp / (tp + fp);
        double recall = tp + fn == 0 ? 0.0 : (double) tp / (tp + fn);
        double f1 = precision + recall == 0 ? 0.0 : 2 * precision * recall / (precision + recall);
        double humanReviewRate = detectedTotal == 0 ? 0.0 : (double) humanReviewTotal / detectedTotal;
        long elapsedMillis = (System.nanoTime() - started) / 1_000_000L;
        log.info("Evidence checks evaluated {} scenario(s) in {} ms: precision={}, recall={}",
                evaluated, elapsedMillis, round(precision), round(recall));

        return new EvidenceCheckReport(
                EVIDENCE_CHECK_METHOD,
                "Deterministic evidence checks",
                evaluated,
                tp, fp, fn,
                round(precision), round(recall), round(f1),
                incorrectlyCleared, unsafeAutoActions,
                round(humanReviewRate),
                List.copyOf(details));
    }

    public BenchmarkReport runWithDefaults() {
        return run(DEFAULT_SEED, DEFAULT_REPETITIONS, true);
    }

    /** Writes machine-readable and human-readable artefacts. Returns the JSON path. */
    public Path write(BenchmarkReport report) {
        Path directory = Path.of(properties.getBenchmarkOutputDir());
        try {
            Files.createDirectories(directory);
            Path json = directory.resolve("integrity-benchmark-results.json");
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(json.toFile(), report);

            Path markdown = directory.resolve("integrity-benchmark-results.md");
            Files.writeString(markdown, toMarkdown(report));
            log.info("Benchmark artefacts written to {} and {}", json, markdown);
            return json;
        } catch (IOException e) {
            throw new IllegalStateException("Unable to write benchmark artefacts to " + directory, e);
        }
    }

    private String toMarkdown(BenchmarkReport report) {
        StringBuilder builder = new StringBuilder();
        builder.append("# Aegis-X Integrity Benchmark Results\n\n");
        builder.append("- Generated: ").append(report.generatedAt()).append('\n');
        builder.append("- Seed: ").append(report.seed()).append(" (deterministic)\n");
        builder.append("- Scenarios: ").append(report.scenarioCount())
                .append(" (").append(report.faultKindCount()).append(" fault kinds x ")
                .append(report.repetitionsPerFaultKind()).append(")\n\n");
        builder.append("| Method | Precision | Recall | F1 | Incorrectly cleared | Unreported | Unsafe auto actions | Spurious financial advice | Human-review rate | Median investigation steps |\n");
        builder.append("|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|\n");
        for (MethodReport method : report.methods()) {
            builder.append("| ").append(method.displayName())
                    .append(" | ").append(method.precision())
                    .append(" | ").append(method.recall())
                    .append(" | ").append(method.f1())
                    .append(" | ").append(method.incorrectlyClearedScenarios())
                    .append(" | ").append(method.unreportedIssues())
                    .append(" | ").append(method.unsafeAutomaticActions())
                    .append(" | ").append(method.spuriousFinancialAdvice())
                    .append(" | ").append(method.humanReviewRate())
                    .append(" | ").append(method.medianInvestigationSteps())
                    .append(" |\n");
        }
        EvidenceCheckReport evidence = report.evidenceChecks();
        builder.append("\n## Evidence checks\n\n")
                .append("The deterministic cross-record checks over the paperwork view of ")
                .append(evidence.scenarios()).append(" scenario(s). Each evidence scenario's payment side is a clean ")
                .append("pack, so the three methods above were also scored for false positives on those scenarios; ")
                .append("their recall is not diluted by evidence findings they cannot produce.\n\n")
                .append("| Method | Precision | Recall | F1 | Incorrectly cleared | Unreported | Unsafe auto actions | Human-review rate |\n")
                .append("|---|---:|---:|---:|---:|---:|---:|---:|\n")
                .append("| ").append(evidence.displayName())
                .append(" | ").append(evidence.precision())
                .append(" | ").append(evidence.recall())
                .append(" | ").append(evidence.f1())
                .append(" | ").append(evidence.incorrectlyClearedScenarios())
                .append(" | ").append(evidence.falseNegatives())
                .append(" | ").append(evidence.unsafeAutomaticActions())
                .append(" | ").append(evidence.humanReviewRate())
                .append(" |\n");
        builder.append("\n## Method\n\n").append(report.methodologyNote()).append("\n\n");
        builder.append("## Limitations\n\n");
        for (String limitation : report.limitations()) {
            builder.append("- ").append(limitation).append('\n');
        }
        return builder.toString();
    }

    private static List<String> sorted(Set<FindingType> types) {
        return new ArrayList<>(new TreeSet<>(types.stream().map(Enum::name).toList()));
    }

    private static double median(List<Integer> values) {
        if (values.isEmpty()) {
            return 0.0;
        }
        List<Integer> sorted = new ArrayList<>(values);
        java.util.Collections.sort(sorted);
        int size = sorted.size();
        if (size % 2 == 1) {
            return sorted.get(size / 2);
        }
        return (sorted.get(size / 2 - 1) + sorted.get(size / 2)) / 2.0;
    }

    private static double round(double value) {
        return Math.round(value * 10_000.0) / 10_000.0;
    }
}

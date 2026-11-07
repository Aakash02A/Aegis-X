package com.aegisx.integrity;

import com.aegisx.document.entity.DocumentType;
import com.aegisx.integrity.entity.EvidenceDocument;
import com.aegisx.integrity.entity.PaymentOrder;
import com.aegisx.integrity.entity.Workspace;
import com.aegisx.integrity.evidence.EvidenceIntelligenceService;
import com.aegisx.integrity.exception.IntegrityConflictException;
import com.aegisx.integrity.support.EvidenceFixtures;
import com.aegisx.integrity.support.MySqlIntegrityTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Concurrent <em>replacement</em> of the same evidence document on a real MySQL 8.4 server.
 *
 * <p>Superseding a document is three writes in one transaction — stop the old row being current,
 * insert the replacement, then name the replacement — and the first of them frees a slot that a
 * unique index allows only one row to occupy. {@link MySqlEvidenceUniquenessTest} proves that
 * sequence works; this test proves it still works when several corrections of the <em>same</em>
 * document are in flight at once, which is the case the plan's §4 called out as unresolved and which
 * an isolated duplicate-insert check cannot answer.</p>
 *
 * <p>Four properties are asserted after the burst, all of them against the database rather than
 * against the application's opinion of it:</p>
 *
 * <ol>
 *   <li>exactly one document of the type is current, and it is the row the generated-column unique
 *       index recognises as current;</li>
 *   <li>no row was left marked superseded without naming the document that replaced it, so a
 *       rolled-back replacement cannot leave an order with no valid current document;</li>
 *   <li>the history is a <em>single</em> chain from the original, with one link per successful
 *       correction and no fork or cycle — i.e. each winner replaced the previous current row;</li>
 *   <li>a correction that lost the race reaches the caller as a conflict it can act on, not as an
 *       internal error quoting engine text, and retrying afterwards succeeds.</li>
 * </ol>
 *
 * <p>Skipped unless {@code INTEGRITY_MYSQL_URL} is set. On H2 this test would be worthless rather
 * than merely slower: H2 builds its schema from the entity model, which declares neither the unique
 * index that decides the race nor the foreign key the losing transaction must roll back, so every
 * correction would appear to succeed and the properties above would be untestable.</p>
 */
@EnabledIfEnvironmentVariable(named = "INTEGRITY_MYSQL_URL", matches = ".+")
@DisplayName("Concurrent evidence replacement on MySQL")
class MySqlEvidenceConcurrencyTest extends MySqlIntegrityTestSupport {

    private static final long ORDER_AMOUNT = 249_000L;
    private static final String INVOICE = "COMMERCIAL_INVOICE";

    /**
     * Walks the supersession links from one document to the end of its history.
     *
     * <p>{@code depth} is carried so that a cycle (which would otherwise recurse until MySQL's
     * {@code cte_max_recursion_depth} and fail with an opaque error) is bounded here and shows up as
     * a wrong-sized chain instead.</p>
     */
    private static final String CHAIN_SQL =
            "WITH RECURSIVE chain (id, superseded_at, next_id, depth) AS ("
                    + "  SELECT id, superseded_at, superseded_by_document_id, 0 "
                    + "    FROM pi_evidence_documents WHERE id = ?"
                    + "  UNION ALL"
                    + "  SELECT d.id, d.superseded_at, d.superseded_by_document_id, c.depth + 1"
                    + "    FROM pi_evidence_documents d JOIN chain c ON d.id = c.next_id"
                    + "   WHERE c.depth < 64"
                    + ") SELECT id, superseded_at, next_id, depth FROM chain ORDER BY depth";

    /** What one contender did: either it applied its correction, or it was refused. */
    private record Attempt(boolean succeeded, String failure) { }

    @Test
    @DisplayName("four simultaneous corrections of one document leave exactly one current document, and a single history chain")
    void simultaneousCorrectionsLeaveOneCurrentDocument() throws Exception {
        Workspace workspace = newWorkspace();
        PaymentOrder order = newOrderWithProviderOrder(workspace, ORDER_AMOUNT);

        EvidenceIntelligenceService.UploadOutcome original = uploadEvidence(workspace, order,
                DocumentType.COMMERCIAL_INVOICE, "invoice-race-original.pdf",
                EvidenceFixtures.invoicePdf("INV-RACE-ORIGINAL", order.getInternalReference(),
                        order.getCurrency(), "2490.00"));
        assertThat(original.supersededPrevious())
                .as("the first invoice of the type cannot supersede anything")
                .isFalse();

        List<Attempt> attempts = replaceConcurrently(workspace, order, 4);
        long succeeded = attempts.stream().filter(Attempt::succeeded).count();

        assertThat(succeeded)
                .as("at least one correction must take effect; MySQL aborts one transaction at a "
                        + "time, so a simultaneous burst cannot fail completely")
                .isGreaterThanOrEqualTo(1);
        assertThat(succeeded)
                .as("every correction either applied or was refused; none may be silently lost")
                .isLessThanOrEqualTo(4);

        assertRefusalsAreActionable(attempts);
        assertDatabaseAgreesWithTheApplication(workspace, order, original.document().getId(),
                (int) succeeded, order.getCurrency());
    }

    @Test
    @DisplayName("a simultaneous duplicate upload cannot create two current documents of the same type")
    void simultaneousDuplicateUploadsCannotDoubleTheEvidence() throws Exception {
        Workspace workspace = newWorkspace();
        PaymentOrder order = newOrderWithProviderOrder(workspace, ORDER_AMOUNT);

        // The same bytes, offered by four callers at once. The hash check is a read followed by a
        // write, so it cannot by itself stop two of them; the database must.
        byte[] content = EvidenceFixtures.invoicePdf("INV-DUP-1", order.getInternalReference(),
                order.getCurrency(), "2490.00");

        List<Attempt> attempts = uploadConcurrently(workspace, order, content, 4);
        long accepted = attempts.stream().filter(Attempt::succeeded).count();

        assertThat(accepted)
                .as("only one of four byte-identical uploads may be accepted")
                .isEqualTo(1);

        assertThat(jdbc().queryForObject(
                "SELECT COUNT(*) FROM pi_evidence_documents WHERE order_id = ? AND document_type = ? "
                        + "AND active_document_type IS NOT NULL",
                Integer.class, order.getId().toString(), INVOICE))
                .as("one active slot, one row: the duplicate uploads must not both have landed")
                .isEqualTo(1);
        assertThat(jdbc().queryForObject(
                "SELECT COUNT(*) FROM pi_evidence_documents WHERE order_id = ?",
                Integer.class, order.getId().toString()))
                .as("a refused duplicate must leave no row behind at all")
                .isEqualTo(1);

        EvidenceDocument persisted = evidenceService.documents(workspace.getId(), order.getId(), false)
                .get(0);
        Path evidenceDirectory = Path.of(persisted.getStoragePath()).getParent();
        try (var files = Files.list(evidenceDirectory)) {
            assertThat(files.filter(Files::isRegularFile).count())
                    .as("the losing concurrent transactions must remove their files after rollback")
                    .isEqualTo(1);
        }
    }

    // ------------------------------------------------------------------ helpers

    /** Fires {@code contenders} corrections of the current invoice at the same instant. */
    private List<Attempt> replaceConcurrently(Workspace workspace, PaymentOrder order, int contenders)
            throws Exception {
        List<Callable<Attempt>> tasks = new ArrayList<>();
        for (int index = 0; index < contenders; index++) {
            int number = index;
            tasks.add(() -> upload(workspace, order,
                    EvidenceFixtures.invoicePdf("INV-RACE-" + number, order.getInternalReference(),
                            order.getCurrency(), "2490.00"),
                    "invoice-race-" + number + ".pdf"));
        }
        return runTogether(tasks, contenders);
    }

    /** Fires {@code contenders} uploads of identical bytes at the same instant. */
    private List<Attempt> uploadConcurrently(Workspace workspace, PaymentOrder order, byte[] content,
                                             int contenders) throws Exception {
        List<Callable<Attempt>> tasks = new ArrayList<>();
        for (int index = 0; index < contenders; index++) {
            int number = index;
            tasks.add(() -> upload(workspace, order, content, "invoice-duplicate-" + number + ".pdf"));
        }
        return runTogether(tasks, contenders);
    }

    /**
     * Starts every task on one latch, so they contend rather than queue, and records the outcome of
     * each. Nothing is swallowed: a refusal is captured with its type and message so that the
     * <em>kind</em> of failure can be asserted, not merely its absence.
     */
    private List<Attempt> runTogether(List<Callable<Attempt>> tasks, int contenders)
            throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(contenders);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Attempt>> futures = new ArrayList<>();
        try {
            for (Callable<Attempt> task : tasks) {
                futures.add(pool.submit(() -> {
                    start.await(30, TimeUnit.SECONDS);
                    return task.call();
                }));
            }
            start.countDown();

            List<Attempt> attempts = new ArrayList<>();
            for (Future<Attempt> future : futures) {
                attempts.add(future.get(60, TimeUnit.SECONDS));
            }
            return attempts;
        } finally {
            pool.shutdownNow();
        }
    }

    private Attempt upload(Workspace workspace, PaymentOrder order, byte[] content, String filename) {
        try {
            uploadEvidence(workspace, order, DocumentType.COMMERCIAL_INVOICE, filename, content);
            return new Attempt(true, null);
        } catch (Throwable failure) {
            return new Attempt(false, failure.getClass().getName() + ": " + failure.getMessage());
        }
    }

    /**
     * A refused correction must be reported as a conflict the operator can retry, and must not carry
     * the engine's own text. That translation lives in {@code EvidenceDocumentService}, and this is
     * the only place it can be measured against a real constraint violation raised by a real race.
     */
    private void assertRefusalsAreActionable(List<Attempt> attempts) {
        List<String> refusals = attempts.stream().filter(attempt -> !attempt.succeeded())
                .map(Attempt::failure).toList();
        assertThat(refusals)
                .as("a correction that lost the race must be an integrity conflict, not an internal "
                        + "error")
                .allSatisfy(failure -> assertThat(failure)
                        .startsWith(IntegrityConflictException.class.getName()));
        assertThat(refusals)
                .as("and the refusal returned to the caller must not echo the engine's message")
                .allSatisfy(failure -> assertThat(failure)
                        .doesNotContain("Duplicate entry", "for key", "uq_", "SQLException",
                                "constraint"));
    }

    /**
     * Asserts the four invariants against the database, then proves the order is still usable by
     * applying one more correction and re-checking the chain.
     */
    private void assertDatabaseAgreesWithTheApplication(Workspace workspace, PaymentOrder order,
                                                        UUID originalId, int successfulReplacements,
                                                        String currency) {
        // 1. Exactly one current row, according to the index that decides the race.
        assertThat(jdbc().queryForObject(
                "SELECT COUNT(*) FROM pi_evidence_documents WHERE order_id = ? AND document_type = ? "
                        + "AND active_document_type IS NOT NULL",
                Integer.class, order.getId().toString(), INVOICE))
                .as("the unique index must leave exactly one current invoice no matter who won")
                .isEqualTo(1);

        // 2. The marker and the link are written together, so the intermediate state of a
        //    replacement (superseded but not yet linked) must never be visible afterwards.
        assertThat(jdbc().queryForObject(
                "SELECT COUNT(*) FROM pi_evidence_documents WHERE order_id = ? "
                        + "AND superseded_at IS NOT NULL AND superseded_by_document_id IS NULL",
                Integer.class, order.getId().toString()))
                .as("a superseded document must always name its replacement; otherwise a failed "
                        + "replacement left the order's evidence inconsistent")
                .isZero();

        // 3. The history is one chain from the original with one link per successful correction.
        List<Map<String, Object>> chain = chain(originalId);
        assertThat(chain)
                .as("%d successful corrections must extend the original's chain by exactly that many "
                        + "documents; a fork or a lost link would show here", successfulReplacements)
                .hasSize(1 + successfulReplacements);
        assertThat(chain.stream().map(row -> ((Number) row.get("depth")).intValue()).toList())
                .as("the chain must be a straight line, not a cycle or a fork")
                .containsExactlyElementsOf(java.util.stream.IntStream.range(0, chain.size())
                        .boxed().toList());
        List<Map<String, Object>> stillCurrent = chain.stream()
                .filter(row -> row.get("superseded_at") == null).toList();
        assertThat(stillCurrent)
                .as("exactly one document in the chain is still current")
                .hasSize(1);
        assertThat(((Number) stillCurrent.get(0).get("depth")).intValue())
                .as("and it must be the end of the chain")
                .isEqualTo(chain.size() - 1);
        assertThat(stillCurrent.get(0).get("next_id"))
                .as("nothing may replace it yet")
                .isNull();

        UUID currentId = UUID.fromString(stillCurrent.get(0).get("id").toString());

        // The application's own view must agree with the database's. If they diverged — for example
        // if superseded_at were written without superseded_by_document_id — one of the two would
        // report a different current document, and this catches it.
        List<EvidenceDocument> current = evidenceService.documents(workspace.getId(), order.getId(), false)
                .stream().filter(document -> document.getDocumentType() == DocumentType.COMMERCIAL_INVOICE)
                .toList();
        assertThat(current)
                .as("the service must report exactly the row the database treats as current")
                .hasSize(1);
        assertThat(current.get(0).getId())
                .as("and it must be the same document")
                .isEqualTo(currentId);

        // 4. The order is still usable: one more correction, sequentially, applies and extends the
        //    chain. A race that left the order wedged would fail here.
        EvidenceIntelligenceService.UploadOutcome after = uploadEvidence(workspace, order,
                DocumentType.COMMERCIAL_INVOICE, "invoice-race-after.pdf",
                EvidenceFixtures.invoicePdf("INV-RACE-AFTER", order.getInternalReference(), currency,
                        "2490.00"));
        assertThat(after.supersededPrevious())
                .as("the correction after the race must supersede the row that won it")
                .isTrue();
        assertThat(after.supersededDocumentId())
                .as("and must name that row as the document it replaced")
                .isEqualTo(currentId);

        assertThat(chain(originalId))
                .as("the chain must have grown by exactly one, keeping every earlier link")
                .hasSize(2 + successfulReplacements);
        assertThat(jdbc().queryForObject(
                "SELECT COUNT(*) FROM pi_evidence_documents WHERE order_id = ? AND document_type = ? "
                        + "AND active_document_type IS NOT NULL",
                Integer.class, order.getId().toString(), INVOICE))
                .as("still exactly one current invoice after the recovery attempt")
                .isEqualTo(1);
    }

    private List<Map<String, Object>> chain(UUID originalId) {
        return jdbc().queryForList(CHAIN_SQL, originalId.toString());
    }
}

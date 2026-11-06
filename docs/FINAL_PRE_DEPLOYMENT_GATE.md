# Aegis-X — Final Pre-Deployment Gate

Date: 2026-11-01 · Branch: `mysql-migration` · HEAD: `ad16dae`

Scope: assess readiness for a **controlled staging deployment**. No feature work, no deployment.
Every number below comes from a command run during this review; commands and their raw results are
quoted so they can be repeated.

**Recommendation: NO-GO for staging sign-off until finding R1 is resolved.** Everything else in this
report is either fixed-and-verified or an accepted/documented limitation.

---

## 1. State verified

| Item | Result |
| :--- | :--- |
| Branch | `mysql-migration`, up to date with `origin/mysql-migration` |
| Working tree at start | clean (`nothing to commit, working tree clean`) |
| HEAD | `ad16dae fix(ci): fix mysql service container options and remove hardcoded test passwords` |
| `docs/POST_VERIFICATION_RELIABILITY_REVIEW.md` | **not present** in this tree; this report therefore stands alone |
| Migration tree | `V1__mysql_baseline`, `V28__explicit_supersession_marker`, `V29__add_user_phone`, `V30__scope_webhook_event_idempotency_by_workspace` |

Changes made by this review are left **uncommitted** (listed in §6). Nothing was reset, stashed or
discarded.

---

## 2. Reliability findings

### R1 — Intermittent `providerOutageIsReported` failure (OPEN, blocking)

One full randomized suite run reported two findings after recovery where zero are expected:

```
STATUS_MISMATCH: Order ORD-FDAB8168 internal state PAID disagrees with
  provider-authoritative state CREATED
CONFLICTING_OBSERVATIONS: The latest two provider-authoritative observations of the same resource
  disagree: PAID at 2026-11-01T08:02:48.847887800Z followed by CREATED at
  2026-11-01T08:02:48.847888Z, which is not a legal successor of it
expected: 0 but was: 2   (IntegrityReconciliationWorkflowTest:270)
```

What the evidence establishes:

- The provider (the in-process simulated adapter) answered **CREATED** during the recovery sync, after
  the capture had already been accepted as PAID. The two observations are **200 ns apart**, i.e. they
  carry no usable temporal order — so `ProposedMethod`'s "latest two observations" rule is choosing
  its pair from sub-microsecond noise between two writes rather than from a real lifecycle sequence.
- Frequency: **1 failure in 4 full randomized runs** (`runOrder=random`). It never reproduced in 8
  isolated runs of the same method, so it depends on suite order/warm-context state, not on the method
  in isolation.

What is *not* yet established: which write produced the CREATED observation. `simulateCapturedPayment`
promotes the simulated provider order to PAID (`TestPaymentProviderAdapter.settleOrderIfCovered`);
a later fetch returning CREATED means either the promotion did not happen for that order id or the
snapshot was replaced afterwards. `IntegrityScenarioFixtures.reset(workspaceId)` clears the whole
adapter registry while persisted rows survive, which is the only code path that can make a live order
unknown to the adapter — it is used by tests and the console endpoint, and is a plausible culprit that
has not been proven.

Diagnostic added (kept, it makes the next failure self-explaining): the assertion in
`IntegrityReconciliationWorkflowTest.providerOutageIsReported` now prints every provider-authoritative
observation with its source, rank, state, timestamp and simulated flag.

Next step (in order): run the full suite with the instrumentation until it fails, read the dumped
sources; if the CREATED row traces to a post-capture provider read, fix the fixture/reset interaction;
then decide whether `ProposedMethod` should refuse to derive a sequence from observations closer than
the precision the evidence sources can support. Do not "fix" this by loosening the assertion.

### R2 — Queue-summary test was flaky on an exact-hour boundary (FIXED, verified)

`ComplianceReviewServiceSummaryTest.summaryIsComputedFromAggregates` failed in the first randomized
run with `expected 52L but was 51L`.

Root cause: the fixture wrote `now - 52h` into `TIMESTAMP(6) WITH TIME ZONE` through
`java.sql.Timestamp`, and the driver **rounds** to microseconds. Reading back 51:59:59.999999 and
truncating with `ChronoUnit.HOURS` yields 51. Nothing was wrong with the production aggregation; the
test asserted a value that sits exactly on a truncation boundary and depended on sub-microsecond
alignment.

Fix: the fixture instant is now `52h30m` ago (half an hour of headroom, so execution jitter cannot
change the truncated value, while any zone misinterpretation of an hour shifts it), bound as an
`OffsetDateTime` (unambiguous zone), and the stored value is asserted round-trip equal within the
column's 1 µs precision. The assertion still fails if the age is derived with a JVM-local offset.

Verified: isolated runs pass; two subsequent full randomized runs pass.

### R3 — Provider identifier generation (verified, no change needed)

`TestPaymentProviderAdapter.nextId` = `ID_SEED + PROCESS_SALT + GLOBAL_SEQUENCE.incrementAndGet()`,
where `GLOBAL_SEQUENCE` is a **static** `AtomicLong` and `PROCESS_SALT` is a 48-bit
`SecureRandom` value chosen once per JVM.

- **Uniqueness across contexts:** the counter is static, so several Spring contexts in one JVM cannot
  mint the same id. Pinned by `OfflineProviderFixtureTest.identifiersAreUniqueAcrossAdapterInstances`.
- **Concurrency:** `incrementAndGet` is atomic. Pinned by `concurrentMintingIsUnique` (8 threads × 200).
- **Restart:** the sequence restarts at zero but the 48-bit salt separates runs (collision needs
  ~2.8e14 restarts), and the identifier stays far below the `VARCHAR(100)` provider columns.
- **Reset:** `reset()` clears the registries and **does not rewind** the sequence — pinned by
  `resetDoesNotReissueIdentifiers`.
- **Overflow:** reachable only past ~9.2e18 issued ids; at that point `long` wraps into the seed range,
  but no realistic run reaches it. Accepted limitation, documented here rather than "fixed".

### R4 — Test database isolation (verified, no change needed)

`src/test/resources/application.yml` uses `jdbc:h2:mem:aegisx_test_${random.uuid};DB_CLOSE_DELAY=-1`,
i.e. a distinct database per Spring context. The previous shared named database let a context starting
`create-drop` drop tables under a cached context, which made the suite order-dependent.
`ComplianceReviewServiceSummaryTest` deliberately overrides the URL with its own named database and is
`@Transactional`, so it neither leaks into nor reads from other classes.

### R5 — Deterministic evidence ordering (verified; one local comparator noted)

- `EvidencePack.evidenceStrength()` and `EvidencePack.recency()` are documented **total** orders with
  trailing tie-breaks over source, amount, currency and row identity — not just rank and timestamp.
- `ProposedMethod` sorts its per-resource observations locally by
  `observedAt → rank → providerResourceId → state`. That is deterministic for the cases the rule acts
  on (two records that tie on all four keys have the same state, and equal states are not reported),
  and `equalTimestampTiesAreDeterministic` pins that the verdict does not depend on list order.
- The residual weakness is the one R1 exercises: a *200 ns* difference is treated as a lifecycle
  sequence. That is a modelling question, not a comparator bug, and is left open with R1.

### R6 — Queue queries (fixed previously, verified now)

`summarizeQueue()` uses `countByStatus`, `countTransactionsWithSeverityInStatus` and
`MIN(updatedAt)` aggregates; `getMyReviewQueue()` filters "already reviewed" in the database
(`findUnreviewedByStatus`) instead of loading the queue and discarding rows in memory.
`getReviewQueue` clamps the caller's page size to 100 in the controller.

---

## 3. Security and operational findings

| # | Finding | Status |
| :--- | :--- | :--- |
| S1 | CSP and the webfont/icon CDN. Policy is now `font-src 'self'`, `style-src 'self' 'unsafe-inline'`, `script-src` self-only, `frame-ancestors 'none'`; fonts come from pinned `@fontsource` packages via `css/fonts.css`. Enforced by `ContentSecurityPolicyTest` (no third-party origin, retired CDN strings gone) **and** by the frontend suite, which scans every HTML page and every stylesheet for off-origin `@import`/`url()`. | Fixed, verified |
| S2 | Webhook-event uniqueness scope. `pi_webhook_events` is unique on `(workspace_id, provider, provider_event_id)` (V30); the reservation happens in a `REQUIRES_NEW` transaction so a duplicate loses the race without poisoning the caller. `MySqlConstraintParityTest` asserts the index exists on the real engine; `IntegrityWebhookIngestionTest` asserts a second workspace's identical event id is not treated as a duplicate. Different providers cannot collide because provider is in the key. | Fixed, verified |
| S3 | In-memory rate limiter. Bucket4j per JVM, keyed `ip + path`, 5/min on `/api/auth/*`, 100/min elsewhere, 10 000-bucket cap with idle eviction. **With N instances the effective limit is N×**, and `trust-forwarded-headers` defaults to `false` (so behind a proxy the limiter sees the proxy address until it is enabled). Exploitability: a distributed credential-stuffing run against two replicas gets 2× the attempts. | Accepted limitation for a single-instance staging deployment; requires a shared store (Redis) before horizontal scaling. No distributed infrastructure added. |
| S4 | Queue-summary full scans | Fixed (R6) |
| S5 | Flyway vs MySQL 8.4. Boot 3.3 pins Flyway 10.10, which does not support 8.4; the build pins `flyway.version=11.15.0`. Verified on MySQL **8.4.11**: 4 migrations applied from empty, 35 tables, 26 `uq_` indexes, Hibernate `ddl-auto=validate` satisfied. | Fixed, verified |
| S6 | Secret handling. `.env`/`.env.*` git-ignored, `.env.example` placeholders only; `ProductionSafetyChecks` refuses a `prod` start with a missing/short/placeholder `JWT_SECRET` or empty `CORS_ALLOWED_ORIGINS`. Webhook secrets are never logged — only "not configured" diagnostics; provider response bodies are never logged. | Verified |
| S7 | CORS. Explicit origins from `aegisx.cors.allowed-origins`, no wildcard, `allowCredentials` with a fixed origin list, methods restricted to GET/POST/PUT/DELETE/OPTIONS. The same property drives both the MVC mapping and the Security filter chain. | Verified |
| S8 | Uploads. Size cap (20 MB) and MIME allow-list (PDF/JPEG/PNG) with the type detected by Tika from the bytes, never the filename; storage filename stripped of path components; SHA-256 duplicate rejection; supersession instead of overwrite; per-`(order,type)` uniqueness enforced by a DB index and translated into a 409. Downloads re-check workspace ownership (`require(workspaceId, documentId)`), and a foreign workspace's document is a 404 over HTTP (`IntegrityApiContractTest.crossWorkspaceEvidenceAccessIsRefusedOverHttp`). | Verified |
| S9 | Authorization gap: **workspace id is not bound to the principal.** The integrity controllers authorize with `isAuthenticated()` (or role) and take `{workspaceId}` from the path; `Workspace` has no owner column and there is no membership model. Query-level scoping is correct (verified by the cross-workspace tests), but any authenticated user who knows another workspace's UUID can address it. With one default workspace per deployment and UUIDs never disclosed to merchants the practical risk is low; hosting **more than one workspace per deployment** makes it a horizontal privilege escalation. | Blocking only for multi-workspace deployments; accepted for a single-workspace staging deployment. Fix requires a membership model — a design change, not a gate fix. |
| S10 | Razorpay webhooks. HMAC-SHA256 over the raw body with constant-time comparison; a missing secret refuses every delivery rather than accepting unverified ones; duplicates are rejected by the DB unique index on the event id. There is **no timestamp-tolerance check**, so a re-signed *distinct* event id is not detected by the signature layer — only by event-id idempotency. | Accepted, documented |
| S11 | Logging/retention. JSON via logstash encoder with `correlationId` in MDC; rolling file 10 MB, 30 files, 1 GB cap. In `prod` the root logger is `WARN` and only the file appender is attached, so container stdout is nearly silent — ship the file or add a console appender. The application enforces rotation, not a legal retention period. | Owner-side operation |
| S12 | Payment reconciliation transactionality. Runs are `@Transactional`; observations are append-only ("never mutates an existing row"); illegal transitions are persisted as conflict records rather than applied; financial recovery approval is refused while `INTEGRITY_ALLOW_FINANCIAL_EXECUTION=false`. | Verified |
| S13 | Live execution. `RazorpayTestAdapter` refuses any key id that does not start with `rzp_test`; the only mutating provider calls (`createOrder`, `createRefund`) are human-initiated. | Verified |

---

## 4. Verification executed

All commands run from the repository root unless noted. Raw logs are in `/tmp` on this machine.

| # | Check | Command | Result |
| :--- | :--- | :--- | :--- |
| 1 | Backend suite, clean build, randomized order | `cd backend && ./mvnw -B clean test -Dsurefire.runOrder=random` | **404 tests, 0 failures, 0 errors, 34 skipped, BUILD SUCCESS** (run after the R2 fix) |
| 2 | Backend suite, randomized (run A) | `./mvnw -B test -Dsurefire.runOrder=random` | 404 / 0 / 0 / 34, **SUCCESS** |
| 3 | Backend suite, randomized (run B) | same | 404, **1 failure** (`providerOutageIsReported`, R1), 34 skipped |
| 4 | Backend suite, randomized (run C, instrumented) | same | 404 / 0 / 0 / 34, **SUCCESS** |
| 5 | Skipped-test accounting | parsed Surefire XML | 34 skipped = exactly the ten `MySql*Test` classes, skipped by design without `INTEGRITY_MYSQL_URL` |
| 6 | MySQL 8.4 gated suites | `INTEGRITY_MYSQL_URL=…aegisx_gate_verify… ./mvnw -B test -Dtest='MySql*Test'` | **34 tests, 33 executed, 0 failures, 1 skipped** (`MySqlDdlGenerateTest`, intentionally) |
| 7 | Flyway on MySQL 8.4.11 from empty | `flyway_schema_history` query | 4 applied migrations (`1, 28, 29, 30`), all `success = 1`; 35 tables; 26 `uq_` indexes |
| 8 | Prod-profile startup with `ddl-auto=validate` | `bash tests/e2e/run-e2e.sh` | `Started AegisXApplication in 13.253 seconds` against a freshly migrated MySQL 8.4 schema |
| 9 | End-to-end HTTP verification | same harness | **30/30 checks passed, 0 failed**, including evidence download returning `application/pdf` |
| 10 | Frontend production build | `cd frontend && npm run build` | exit 0, `✓ built in 1.15s` |
| 11 | Frontend checks | `cd frontend && npm test` | **88 passed, 0 failed** |
| 12 | Reconciliation + summary tests, isolated | `-Dtest='ComplianceReviewServiceSummaryTest,ComplianceReviewQueueAgingTest'` | pass repeatedly |

Not executed, and not claimed:

- Any call to a real Razorpay Test Mode account, a real WhatsApp number, or a real LLM. Provider
  integration remains **offline-verified only**.
- OCR against a real Tesseract binary (the OCR tests run with the binary absent, exercising the
  degraded path).
- Backup/restore rehearsal (needs a staging database; procedure is in the checklist).
- Multi-instance behaviour of the rate limiter.
- Compliance certification of any kind. Nothing here is evidence of regulatory certification.

---

## 5. Deployment blockers and owner-side setup

Blocking:

1. **R1** — the intermittent reconciliation failure must be root-caused and fixed before sign-off.
2. **S9** — do not run more than one workspace per deployment until workspace access is bound to the
   authenticated principal.

Owner-side setup (cannot be done from the codebase, and deliberately not done here): provisioning
MySQL 8.4 and a least-privilege account; TLS and DNS; secret generation and storage; Razorpay Test
Mode keys and public webhook registration; a Meta Business account with approved WhatsApp templates;
an OpenAI key if AI advisory is wanted; a Tesseract binary if scanned-document OCR is wanted;
persistent storage volume plus a backup schedule and one restore rehearsal; log shipping.

Exact steps, with a verification for each, are in
[STAGING_DEPLOYMENT_CHECKLIST.md](STAGING_DEPLOYMENT_CHECKLIST.md).

---

## 6. Files changed by this review

| File | Change | Why |
| :--- | :--- | :--- |
| `backend/src/test/java/com/aegisx/review/service/ComplianceReviewServiceSummaryTest.java` | Fixture instant moved half an hour off the hour boundary, bound as `OffsetDateTime`, round-trip asserted within the column's 1 µs precision | Removes the truncation flake (R2) without weakening the timezone assertion |
| `backend/src/test/java/com/aegisx/integrity/IntegrityReconciliationWorkflowTest.java` | Failure message now dumps provider-authoritative observations | Makes R1 diagnosable instead of reporting only a count |
| `docs/STAGING_DEPLOYMENT_CHECKLIST.md` | New | Step 4 deliverable |
| `docs/DEPLOYMENT_AND_CONFIGURATION.md` | `SERVER_PORT`→`PORT`, `JWT_EXPIRATION_MS`→`JWT_EXPIRATION`, database port example corrected, pointer to the checklist | The variable names did not exist in the application; following the guide would have configured nothing |

No production code was changed. No test was weakened, skipped or given a longer timeout.

---

## 7. Remaining work, prioritized

1. **R1**: capture the observation dump from a failing full run, identify the CREATED source, fix it;
   then require **three consecutive** full randomized runs to be green before re-signing the gate.
2. **S9**: introduce workspace membership (or restrict the integrity surface to operator roles and
   document the single-workspace deployment model) before any multi-workspace host.
3. Turn the R2 class of defect into a rule: fixtures that write instants must not assert truncated
   values at a boundary; the half-hour offset pattern is the cheap version of that rule.
4. Add a CI job that runs the suite with `runOrder=random` twice, so order-dependence is caught by
   automation rather than by a release gate.
5. **S3**: shared rate-limit store when more than one instance is deployed.
6. **S10**: decision on webhook timestamp tolerance, or an explicit statement that idempotency is the
   only replay control.

---

## 8. Go / no-go

**No-go for staging sign-off as it stands**, on R1 alone: a core reconciliation path can report a
false exception in roughly one full-run-in-four under randomized order, and the mechanism is not yet
proven. Everything else — schema, migrations, prod-profile startup, E2E flows, frontend build and
checks, provider identifiers, webhook idempotency, upload and download authorization, CSP — is
verified above with the exact evidence quoted.

A conditional go is defensible for a **single-workspace, single-instance staging box used by
operators only**, with R1 tracked and the reconciliation console's findings treated as advisory during
the trial. It is not defensible for production, and a green suite alone would not have justified
either call.

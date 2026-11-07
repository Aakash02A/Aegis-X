# Phase 1 Audit Handoff

## Scope and database safety

All database commands used a process-local parse of `.env`; no values were printed or copied. For
this run, the JDBC URL was rewritten in memory to target only `aegisx_audit`. Financial execution
was forced off and Razorpay credentials were dummy audit values. The separate DDL URL was unset.

The read-only gate passed before the app was started:

```text
SERVER_VERSION=8.4.11
DATABASE=aegisx_audit
TABLES=0
ROUTINES=0
TRIGGERS=0
EVENTS=0
TARGET_VERIFIED=MySQL 8.4; aegisx_audit exists and is empty
```

## Verdicts

| Module | Verdict | Evidence |
|---|---|---|
| Backend build and test suite | **PARTIAL** | `AegisXApplicationTests.contextLoads` in `backend/src/test/java/com/aegisx/AegisXApplicationTests.java:10` passed. With MySQL enabled, one constraint-parity assertion fails; details below. |
| MySQL 8.4 integration suite | **PARTIAL** | MySQL-gated tests run through `MySqlIntegrityTestSupport` in `backend/src/test/java/com/aegisx/integrity/support/MySqlIntegrityTestSupport.java:31`. 33 tests executed, one failed, and only the diagnostic `MySqlDdlGenerateTest` skipped. |
| Dev-profile application boot and health | **COMPLETE** | Main entry point is `backend/src/main/java/com/aegisx/AegisXApplication.java:9`; startup context test is `backend/src/test/java/com/aegisx/AegisXApplicationTests.java:10`. Booted on `aegisx_audit`, checked `GET /actuator/health` (HTTP 200), and stopped the app. |
| Frontend tests and production build | **COMPLETE** | Scripts are in `frontend/package.json:8`; checks are in `frontend/test/frontend-test.js:78` and `:206`. |
| S3 storage adapter | **MISSING** | `backend/src/main/java/com/aegisx/storage/service/S3StorageService.java:42` is conditional on S3 selection, but its storage methods throw `UnsupportedOperationException` at lines 90–118. |
| Standalone `ai-ml` runtime | **MISSING** | The prompt and extraction JSON assets exist, but no runtime reference to them was found. Java AI and OCR implementations are separate backend code. |
| `database/schema/schema.sql` snapshot | **PARTIAL — NOT VERIFIED** | The application startup uses Flyway migrations; no runtime reference to this snapshot was found. No independent snapshot-parity test was run. |
| Railway deployment | **PARTIAL — NOT VERIFIED** | `deployment/cloud/railway.json` is a platform-consumable deployment manifest. No in-repository Railway invocation or deployment was run. |
| Root Compose cleanup | **COMPLETE** | Kept `compose.yaml`, removed the byte-identical `docker-compose.yml`, and updated its only explicit documentation filename reference. CI and scripts used generic `docker compose` commands and did not name the removed file. |

### Backend and database runs

Dev-profile command:

```text
.\mvnw.cmd -B spring-boot:run -Dspring-boot.run.profiles=dev
```

Flyway logged migrations for versions `1`, `28`, `29`, `30`, `31`, `32`, `33`, and `34`, followed by:

```text
Successfully applied 8 migrations to schema `aegisx_audit`, now at version v34
Started AegisXApplication in 19.061 seconds
```

The V33 and V34 migrations applied successfully; the application stopped after the health check.

MySQL-only command:

```text
.\mvnw.cmd -B test "-Dtest=MySql*Test"
```

Result:

```text
[ERROR] Tests run: 34, Failures: 1, Errors: 0, Skipped: 1
[INFO] BUILD FAILURE
MYSQL_SUITE_TOTAL=34 FAILED=1 ERRORS=0 SKIPPED=1
```

The one skipped test was `MySqlDdlGenerateTest`, which requires the intentionally unset
`INTEGRITY_MYSQL_DDL_URL`. The failure was
`MySqlConstraintParityTest.uniqueIndexesMatchThePostgresHistory`: the database had three unique
indexes absent from that test's expected list:

- `transactions|uq_transactions_razorpay_order_id` — created by V31.
- `workspace_members|uq_workspace_members_user_workspace` — created by V33.
- `settlements|uq_settlements_transaction_id` — created by V34.

This is a stale expected-index list in the parity test, not a Flyway migration failure. The V33 and
V34 migrations were logged as applied, and `MySqlSchemaMigrationTest` passed its seven tests. No
tests were weakened or changed.

Full randomized command:

```text
.\mvnw.cmd -B clean test "-Dsurefire.runOrder=random"
```

Result:

```text
[ERROR] Tests run: 430, Failures: 1, Errors: 0, Skipped: 1
[INFO] BUILD FAILURE
FULL_SUITE_TOTAL=430 FAILED=1 ERRORS=0 SKIPPED=1
```

The only failure was the same MySQL constraint-parity assertion. The sole skip was
`MySqlDdlGenerateTest`.

The earlier Phase 1 baseline, before MySQL variables were loaded, had reported:

```text
Tests run: 430, Failures: 0, Errors: 0, Skipped: 34
BUILD SUCCESS
```

That baseline did not execute the MySQL-gated tests.

### Frontend commands

```text
npm.cmd test
Test Results: 88 Passed, 0 Failed

npm.cmd run build
✓ 40 modules transformed.
✓ built in 1.25s
```

The PowerShell `npm` shim was blocked by execution policy; invoking `npm.cmd` directly succeeded.

## Preserved assets and possible consumers

These were intentionally not deleted:

- `backend/src/main/java/com/aegisx/storage/service/S3StorageService.java`: `DocumentService`,
  `OcrService`, and `EvidenceDocumentService` depend on the `StorageService` abstraction and could
  receive this implementation if S3 storage is selected. It is currently a stub.
- `ai-ml/prompts/regulatory-advisory-prompt.json`: a future/external AI prompt loader could consume
  it. No in-repository consumer was found; the backend currently builds prompts in
  `RegulatoryPromptBuilder`.
- `ai-ml/ocr/document-extraction-spec.json`: a future/external extraction validator or OCR
  integration could consume it. No in-repository consumer was found; the backend has its own
  deterministic extraction service.
- `database/schema/schema.sql`: manual database bootstrap, external schema tooling, or human
  operators could consume it. The app itself uses Flyway migrations.
- `deployment/cloud/railway.json`: Railway's deployment platform could consume it when deploying
  this repository; no external deployment configuration was inspected.

## Compose cleanup commit

`5d29511 Remove duplicate root Compose file` keeps the root `compose.yaml` and removes the identical
`docker-compose.yml`. `docs/TAS_Aegis_X.md` now names `compose.yaml`; no explicit references to the
removed root filename remain.

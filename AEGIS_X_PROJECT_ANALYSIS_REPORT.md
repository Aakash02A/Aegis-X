# Aegis-X Project Completion and Full-Stack Audit

**Audit date:** 2026-09-25  
**Scope:** Repository implementation compared with `doc/Phase_01_Product_and_Compliance_Requirements.md`, `doc/SRS_Aegis_X.md`, and `doc/TAS_Aegis_X.md`  
**Change policy:** This audit did not modify application code. It created this report only.

## Executive Summary

**Overall status: 🟡 Partial implementation, not verified as a completed full-stack system.**

The repository contains substantial Spring Boot, PostgreSQL migration, frontend, OCR, AI, policy, audit, review, Razorpay, and WhatsApp code. However, the project cannot currently be accepted as complete because:

- The backend does not currently build in this environment due to unresolved Maven dependencies.
- The checked-out repository has no `backend/src/test` directory, so the documented acceptance workflow has no executable test suite available.
- Docker Desktop's Linux engine was unavailable during verification, and the Spring Boot service was not listening on port 8080.
- The implemented transaction state enum is narrower than the SRS state machine.
- Scanned PDF OCR is explicitly unimplemented.
- The state machine contains a TODO for audit publication.
- Human review currently exposes review notes, but no complete approve/reject decision workflow was found.
- The frontend contains dashboards and API helpers, but no working document upload control was found and registration does not receive a JWT from the backend.
- External Razorpay, OpenAI, and WhatsApp integrations were not verified against live sandbox credentials.

The project is therefore **not fully completed and not yet proven end-to-end**.

## Status Summary

| Area | Status | Assessment |
|---|---:|---|
| Frontend | 🟡 | Dashboard, search, transaction detail, metrics, auth pages, and API client exist; upload and several workflow paths are incomplete or unverified. |
| Backend/API | 🟡 | Many controllers and services exist, but the build is blocked and endpoint behavior is not runtime-verified. |
| PostgreSQL/database | 🟡 | PostgreSQL configuration, Flyway migrations, entities, and Docker Compose exist; connection and migration execution were not verified. |
| Razorpay + webhook | 🟡 | HMAC verification, idempotency, event persistence, and handlers exist; sandbox delivery and complete transaction mapping are unverified. |
| Evidence collection | 🟡 | Multipart upload, local storage, document records, and evidence status APIs exist; frontend upload and complete lifecycle are not proven. |
| OCR | 🟡 | Image OCR and Tika extraction exist; scanned PDF-to-image conversion is explicitly not implemented. |
| AI structured analysis | 🟡 | Spring AI, prompt construction, parsing, persistence, and API endpoints exist; dependency resolution, API calls, schema behavior, and failure transitions are unverified. |
| Java policy engine | 🟡 | Deterministic rules and persisted decisions exist; state transitions, all documented rules, and runtime behavior are not fully verified. |
| Audit trail | 🟡 | Audit schema, service, factory, repository, and timeline API exist; automatic coverage for every transition is not guaranteed. |
| Human review | 🟡 | Review notes and RBAC exist; complete queue plus mandatory approve/reject state transitions were not found. |
| WhatsApp | 🟡 | Client, webhooks, notification persistence, and configuration exist; credentials, message delivery, retry behavior, and end-to-end triggers are unverified. |
| Authentication/security | 🟡 | JWT, BCrypt, roles, validation, CORS, and security headers exist; frontend token flow and production security require work. |
| Docker/deployment | 🟡 | Dockerfile and Compose setup exist; Docker engine was unavailable and the backend cannot currently build due to Maven resolution failures. |
| End-to-end integration | 🔴 | No executable test suite is present, the build fails before compilation, and the complete workflow was not run. |

## Detailed Findings

### 1. Frontend - 🟡 Partial / needs verification

**Evidence found**

- `frontend/index.html` loads the configuration, API client, authentication, dashboard, compliance, transaction detail, operations, and real-time update modules.
- `frontend/js/dashboard.js` provides merchant and compliance dashboards.
- `frontend/js/compliance.js` provides transaction filtering and search.
- `frontend/js/transaction-detail.js` provides summary, evidence, OCR, AI, policy, audit, and notes panels.
- `frontend/js/operations.js` reads actuator and Prometheus metrics.
- `frontend/js/real-time-updates.js` implements periodic refreshes.
- `frontend/js/api.js` defines authentication, transaction, document, OCR, AI, policy, audit, notes, metrics, and health requests.

**Gaps and risks**

- No `type="file"` input or `FormData` upload implementation was found in the frontend JavaScript. The backend upload endpoint exists, but the merchant cannot be shown to upload evidence through the current UI.
- `Auth.handleRegister()` expects a token-like response, but the backend registration method returns `UserResponse` without a JWT. The frontend then stores a user but not `authToken`; `Auth.isAuthenticated()` requires both, so registration is not a complete authenticated flow.
- `frontend/js/config.js` still contains hard-coded Railway production fallbacks even though the deployment files were removed. Local development is configured, but production behavior is stale and not independently deployable.
- Real-time updates are polling, not a verified push channel. The documented two-second accuracy target was not demonstrated.
- Browser-level tests were not found or run.

**Conclusion:** The frontend is a meaningful shell and dashboard, but not a proven complete workflow client.

### 2. Backend/API - 🟡 Partial / needs verification

**Evidence found**

Controllers exist for:

- Authentication: `/api/auth`
- Transactions: `/api/transactions`
- Search: `/api/transactions/search`
- Documents and evidence status: `/api/transactions/{id}/documents`
- OCR: `/api/documents/{id}`
- AI analysis: `/api/transactions/{id}/ai-analysis`
- Policy evaluation: `/api/transactions/{id}/policy-evaluation`
- Audit: `/api/transactions/{id}/audit`
- Review notes: `/api/transactions/{id}/notes`
- Razorpay webhooks: `/api/webhooks/razorpay`
- WhatsApp webhooks: `/api/webhooks/whatsapp`
- Notifications and operational metrics

**Gaps and risks**

- The documented TAS endpoint catalog uses `/api/v1/...`, while the implemented controllers use `/api/...`. Documentation, clients, and external integrations must be aligned.
- The Maven build failed before compilation because dependencies could not be resolved. No executable backend verification was possible.
- The SRS describes failure states and review transitions that are absent from the current transaction enum and state machine.
- Two `SecurityFilterChain` bean definitions were found: `common/config/SecurityConfig.java` and `security/SecurityHeadersConfig.java`. Their interaction should be verified because the security behavior may not be the intended single chain.

### 3. PostgreSQL / database - 🟡 Partial / needs verification

**Evidence found**

- PostgreSQL JDBC configuration exists in `backend/src/main/resources/application-dev.yml` and `application-prod.yml`.
- Flyway is enabled in `application.yml` and production configuration.
- Migrations create users, transactions, webhook events, documents, extractions, AI analyses, policy evaluations, notifications, review notes, and audit events.
- `docker-compose.yml` defines PostgreSQL 15 with a `pg_isready` health check and a persistent `pgdata` volume.
- JPA repositories and entities exist for the main domain modules.

**Verification result**

- TCP port 5432 was open on the host, but `psql` was not installed, so database authentication and schema state could not be verified.
- The Docker Linux engine was unavailable, so the Compose PostgreSQL service could not be inspected.
- The backend was not running on port 8080, so actuator database readiness could not be queried.

**Gaps and risks**

- Audit and policy immutability are documented primarily through comments and application conventions. Database triggers or permissions preventing update/delete were not found.
- Production uses `ddl-auto: validate`, so migrations must be proven to run cleanly on a fresh PostgreSQL database.

### 4. Razorpay and webhook - 🟡 Partial / needs verification

**Evidence found**

- `RazorpayWebhookController` accepts the raw body and signature header.
- `RazorpaySignatureService` verifies HMAC-SHA256 using constant-time comparison.
- `RazorpayWebhookDispatcher` persists events, uses an event ID for idempotency, handles malformed JSON, and dispatches typed handlers.
- `V3__create_webhook_events.sql` provides a unique event ID and processing status.
- Payment captured and payment failed handler classes exist.

**Gaps and risks**

- The payment captured handler contains a TODO noting that transaction mapping currently depends on webhook notes rather than a persisted Razorpay order mapping.
- Sandbox credentials and an actual Razorpay webhook delivery were not tested.
- The documented response and endpoint contract differ from the implementation in places.
- Webhook processing is synchronous; the controller comments identify the missing asynchronous worker as future work.

**Conclusion:** The security and ingestion foundation exists, but successful external payment-to-transaction processing is unverified.

### 5. Evidence collection - 🟡 Partial / needs verification

**Evidence found**

- `DocumentController` exposes multipart document upload, listing, download, and evidence-status endpoints.
- MIME and content-type restrictions are configured for PDF, JPEG, and PNG.
- Local filesystem storage exists and is the configured default.
- Required-document and evidence-status entities/repositories exist.
- Document migration scripts and document versioning are present.

**Gaps and risks**

- No frontend file upload workflow was found.
- The S3 storage implementation is a template and throws `UnsupportedOperationException` for all operations when enabled.
- The complete transition from uploaded evidence through OCR, AI, and policy was not executed.
- Access-control behavior for document download requires runtime testing with merchant and compliance roles.

### 6. OCR - 🟡 Partial / needs verification

**Evidence found**

- Apache Tika dependencies and a Tesseract integration exist.
- `TesseractOcrProcessor` processes PNG/JPG/JPEG images.
- OCR results are persisted through document extraction components.
- OCR controllers expose processing and extraction retrieval endpoints.

**Blocking gap**

- `TesseractOcrProcessor.convertPdfToImages()` explicitly throws an `OcrProcessingException` stating that PDF-to-image conversion is not implemented. The requirements include PDF evidence and scanned PDFs, so the OCR requirement is not complete.

**Additional risk**

- OCR requires native Tesseract data and configuration. This was not run because the backend could not build and Docker was unavailable.

### 7. AI structured analysis - 🟡 Partial / needs verification

**Evidence found**

- Spring AI OpenAI configuration and `ChatClient` wiring exist.
- AI prompt construction, response parsing, confidence normalization, analysis persistence, and AI controllers exist.
- `V7__create_ai_analysis.sql` stores structured JSON, confidence, model, prompt version, input hash, and processing duration.
- The code comments preserve the intended invariant that AI is advisory and Java remains the decision authority.

**Gaps and risks**

- The Spring AI dependency could not be resolved, so the implementation did not compile.
- No live OpenAI request was run and no schema-conformance test is available in the current checkout.
- API-key, timeout, retry, malformed response, and `AI_ANALYSIS_FAILED` behavior were not verified.
- Structured extraction also includes deterministic regex extraction, but its coverage against real invoices was not measured.

### 8. Java policy engine - 🟡 Partial / needs verification

**Evidence found**

- `RuleEngine`, `PolicyEvaluationService`, rule classes, policy DTOs, persistence, and retrieval APIs exist.
- The engine is designed to fail closed and return `APPROVED` or `COMPLIANCE_HOLD`.
- `V8__create_policy_evaluations.sql` persists rule results and decisions.
- The transaction state machine permits `POLICY_EVALUATION` to move to `APPROVED` or `COMPLIANCE_HOLD`.

**Gaps and risks**

- `TransactionStatus` does not include the SRS states `REJECTED`, `PAYMENT_FAILED`, `OCR_FAILED`, or `AI_ANALYSIS_FAILED`.
- `TransactionStateMachine` does not permit `COMPLIANCE_HOLD -> APPROVED` or `COMPLIANCE_HOLD -> REJECTED`, despite the SRS requiring human review outcomes.
- The state machine contains a TODO for publishing audit events.
- Rule evaluation was not run because the project did not compile and no tests are present.

### 9. Audit trail - 🟡 Partial / needs verification

**Evidence found**

- `AuditService`, `AuditEventFactory`, `AuditEventRepository`, audit entities, event types, correlation ID filtering, and audit timeline/search controllers exist.
- `V10__create_audit_events.sql` creates an audit table with transaction, actor, module, correlation, old state, new state, event data, and timestamp fields.
- Audit methods exist for state changes, system events, errors, webhooks, user actions, AI analysis, and policy decisions.

**Gaps and risks**

- The state machine itself explicitly contains a TODO instead of invoking `AuditService`.
- The database migration documents append-only behavior but does not create database-level update/delete protections.
- The audit search controller comments that comprehensive filtering is not implemented.
- A complete audit timeline for a real end-to-end transaction was not observed.

### 10. Human review - 🟡 Partial / needs verification

**Evidence found**

- Review note entity, migration, service, repository, DTOs, and controller exist.
- Notes enforce role-aware access for merchants, compliance officers, and admins.
- The frontend includes a Notes panel and note API helpers.

**Missing or unverified behavior**

- No complete compliance review queue endpoint was found matching the SRS catalog.
- No approve/reject decision endpoint was found in the reviewed controller surface.
- No implementation was found that requires justification and transitions a held transaction to `APPROVED` or `REJECTED`.
- `REJECTED` is absent from `TransactionStatus`.

**Conclusion:** This is review-note functionality, not a complete human-review workflow.

### 11. WhatsApp - 🟡 Partial / needs verification

**Evidence found**

- `WhatsAppClient` sends template messages through the Meta Graph endpoint.
- WhatsApp verification and inbound webhook classes exist.
- Notification persistence and outbound/inbound notification APIs exist.
- Environment configuration supports phone number ID, token, verify token, app secret, endpoint, and sandbox mode.

**Gaps and risks**

- Credentials are empty by default and no live message was sent.
- Notification retry code contains a comment that retry logic would be implemented there.
- The full trigger path from missing evidence to a successful WhatsApp message was not demonstrated.
- Template payload compatibility with the configured WhatsApp Business account was not tested.

### 12. Authentication and security - 🟡 Partial / needs verification

**Evidence found**

- JWT generation and validation classes exist.
- BCrypt password hashing is configured.
- Registration defaults new accounts to `MERCHANT`.
- Role authorities are derived from the user role.
- Bean validation annotations are used on request DTOs.
- CORS, CSP, HSTS, frame protection, referrer policy, and permissions headers are configured.
- Public endpoints include registration, login, webhooks, and health checks; protected API paths require authentication in the primary security configuration.

**Gaps and risks**

- Frontend JWTs are stored in `localStorage`, which leaves them exposed to XSS. The frontend comments acknowledge this limitation.
- Registration returns no JWT, while the frontend treats registration like a logged-in flow.
- HTTPS enforcement was not verified; local Compose runs over HTTP.
- The two security filter chain configurations need compile-time and runtime verification.
- Authorization was not tested across merchant, compliance officer, and admin roles.

### 13. Docker and deployment - 🟡 Partial / needs verification

**Evidence found**

- `docker-compose.yml` defines PostgreSQL and the Spring Boot app with health checks, volumes, networking, and environment variables.
- `backend/Dockerfile` uses Java 21, builds with Maven, installs Tesseract, runs as a non-root user, and exposes port 8080.
- Local startup batch scripts exist.
- Cloud-specific deployment files were removed earlier and are no longer part of the repository.

**Verification result**

- Docker CLI was installed, but the Docker Desktop Linux engine was unavailable.
- Compose initially also requires `JWT_SECRET` before rendering the app service.
- The backend Maven build fails dependency resolution, so the image build is expected to fail until dependency versions/repositories are corrected.

**Conclusion:** Local container architecture is present, but the deployment path is not currently verified.

### 14. End-to-end integration - 🔴 Missing / not demonstrated

The documented workflow requires:

1. Merchant authentication.
2. Transaction creation.
3. Razorpay sandbox webhook.
4. Evidence pending transition.
5. WhatsApp document request.
6. Document upload.
7. OCR extraction.
8. AI structured analysis.
9. Deterministic policy evaluation.
10. Approval or compliance hold.
11. Human review where required.
12. Complete audit timeline.
13. Frontend status update.

This sequence was not executed. The repository currently lacks a test directory, the Maven build fails before compilation, Docker was unavailable, and external credentials were not supplied for integration testing.

## Requirements That Are Not Complete

### Clearly missing or incomplete

1. **Build reproducibility:** Maven cannot resolve `org.apache.commons:commons-imaging:1.0` and `org.springframework.ai:spring-ai-openai-spring-boot-starter:1.0.5` in the current setup.
2. **Executable verification:** `backend/src/test` is absent in the current checkout.
3. **Scanned PDF OCR:** PDF-to-image conversion is explicitly unimplemented.
4. **Transaction failure states:** `PAYMENT_FAILED`, `OCR_FAILED`, `AI_ANALYSIS_FAILED`, and `REJECTED` are absent from the transaction status enum.
5. **Human decision workflow:** No complete approve/reject endpoint and state transition flow was found.
6. **Automatic audit coverage:** The state machine contains a TODO for audit publication; append-only behavior is not database-enforced.
7. **Frontend evidence upload:** No file input/FormData workflow was found.
8. **Registration authentication:** Backend registration returns no JWT while the frontend expects one for a logged-in state.
9. **Runtime integration proof:** PostgreSQL migration, Razorpay, OpenAI, WhatsApp, OCR, and end-to-end behavior are unverified.
10. **Production storage option:** S3 storage is a template and throws `UnsupportedOperationException` when selected.

### Needs verification before acceptance

- Fresh PostgreSQL migration and Flyway validation.
- Role-based access control on every transaction, document, audit, and review endpoint.
- Razorpay signature, event idempotency, payment mapping, and failure handling.
- AI schema validation, timeout, retry, and malformed response behavior.
- OCR behavior for text PDFs, scanned PDFs, PNG, and JPEG files.
- Notification trigger, WhatsApp delivery, inbound verification, and retry behavior.
- Performance targets from the SRS.
- Docker image build and health checks.

## Overall Completion Status

**Not complete.**

A reasonable implementation estimate from repository evidence is:

- **Architecture and module coverage:** substantial
- **Feature implementation:** partial
- **Build health:** blocked
- **Test/verification evidence:** insufficient
- **Full-stack acceptance:** not achieved

The system should be treated as a **partially implemented MVP requiring integration completion and verification**, not as a fully completed production-ready application.

## Exact Next Steps in Priority Order

### P0 - Restore a buildable baseline

1. Resolve the Maven dependencies:
   - Select a published, compatible version of `commons-imaging` or remove the unused dependency.
   - Select a published Spring AI version compatible with Spring Boot 3.3.4, or configure the correct repository and version.
2. Run a clean compile and package.
3. Restore or recreate the backend test suite. The current checkout contains no `backend/src/test` directory.
4. Confirm the application starts with a fresh PostgreSQL database.

### P1 - Correct the transaction lifecycle

5. Decide whether the SRS failure states are required for this MVP. If required, add and test `PAYMENT_FAILED`, `OCR_FAILED`, `AI_ANALYSIS_FAILED`, and `REJECTED` transitions.
6. Implement and test `COMPLIANCE_HOLD -> APPROVED` and `COMPLIANCE_HOLD -> REJECTED` with mandatory justification.
7. Integrate `TransactionStateMachine` with `AuditService` so every successful transition creates an audit record in the same transaction.

### P1 - Complete the user workflow

8. Add frontend document upload using `multipart/form-data` and connect it to the document endpoint.
9. Align registration behavior: either return a JWT from registration or require the frontend to redirect to login after registration.
10. Implement the compliance queue and approve/reject UI/API path.
11. Remove stale hard-coded cloud API fallbacks from frontend configuration unless an intentional deployment target is restored.

### P1 - Complete document and AI processing

12. Implement scanned PDF rendering before Tesseract OCR, then test PDF, PNG, and JPEG fixtures.
13. Verify AI structured output against a strict schema, including invalid JSON, missing fields, timeout, and retry cases.
14. Ensure OCR and AI failures update the transaction/document state and create corresponding audit events.

### P2 - Verify external integrations

15. Configure a Razorpay sandbox webhook and test valid signature, invalid signature, duplicate event, captured payment, and failed payment cases.
16. Configure WhatsApp sandbox credentials and test missing-document notification, inbound webhook verification, delivery persistence, and retry behavior.
17. Test authorization for merchant, compliance officer, and admin users, including document download and audit access.

### P2 - Verify operational behavior

18. Apply Flyway migrations to a fresh PostgreSQL database and query all expected tables.
19. Build the Docker image and run Compose health checks.
20. Run the complete acceptance workflow and retain request/response evidence, database rows, audit events, and screenshots/logs.
21. Measure the SRS performance targets for API, OCR, AI, and policy evaluation.

## Commands and Tests Needed

### Prerequisites

- Java 21
- Docker Desktop with the Linux engine running
- Maven wrapper available through `backend/mvnw.cmd`
- PostgreSQL 15 via Compose
- Tesseract available in the Docker image or local environment
- Sandbox credentials for Razorpay, OpenAI, and WhatsApp

### Build and test

```powershell
cd A:\GITHUB\Aegis-X\backend
.\mvnw.cmd -U clean test
.\mvnw.cmd -U clean package
```

The current audit attempted `.\mvnw.cmd -DskipTests compile` and it failed during dependency resolution before compilation.

### Start the local stack

```powershell
cd A:\GITHUB\Aegis-X
$env:JWT_SECRET = "replace-with-a-long-random-secret"
docker compose up -d --build
docker compose ps
```

### Verify service and database readiness

```powershell
Invoke-RestMethod http://localhost:8080/actuator/health/readiness
Invoke-RestMethod http://localhost:8080/api/health
docker compose exec -T postgres pg_isready -U postgres -d aegisx
docker compose logs --tail=200 app postgres
```

Expected results are an application readiness status of `UP`, database health of `UP`, and successful Flyway startup without validation errors.

### Serve the frontend

```powershell
cd A:\GITHUB\Aegis-X\frontend
python -m http.server 3000
```

Open `http://localhost:3000` and verify registration, login, dashboard loading, transaction search, transaction detail, document upload, OCR, AI, policy, audit, notes, and role-specific navigation.

### Authentication checks

```powershell
$body = @{ email = "merchant@example.com"; fullName = "Test Merchant"; password = "StrongPassword123!" } | ConvertTo-Json
Invoke-RestMethod -Method Post -Uri http://localhost:8080/api/auth/register -ContentType "application/json" -Body $body

$login = @{ email = "merchant@example.com"; password = "StrongPassword123!" } | ConvertTo-Json
$auth = Invoke-RestMethod -Method Post -Uri http://localhost:8080/api/auth/login -ContentType "application/json" -Body $login
$headers = @{ Authorization = "Bearer $($auth.token)" }
Invoke-RestMethod -Uri http://localhost:8080/api/auth/me -Headers $headers
```

Repeat protected endpoint checks for merchant, compliance officer, and admin accounts. Confirm unauthorized roles receive `401` or `403` as appropriate.

### Evidence and OCR checks

Use a real PDF, PNG, and JPEG fixture:

```powershell
curl.exe -X POST "http://localhost:8080/api/transactions/<TRANSACTION_ID>/documents" `
  -H "Authorization: Bearer <JWT>" `
  -F "file=@C:\path\to\invoice.pdf" `
  -F "documentType=INVOICE"

curl.exe -X POST "http://localhost:8080/api/documents/<DOCUMENT_ID>/process" `
  -H "Authorization: Bearer <JWT>"

Invoke-RestMethod -Uri http://localhost:8080/api/documents/<DOCUMENT_ID>/extraction -Headers $headers
```

Verify text extraction, confidence, processing duration, failure state, retry/re-upload behavior, and audit events.

### Razorpay webhook checks

Create a raw JSON fixture and calculate the exact HMAC-SHA256 signature using the configured webhook secret. Test:

- Valid signature returns success and persists an event.
- Invalid or missing signature is rejected.
- Replaying the same event ID is idempotent.
- Captured payment maps to the intended transaction.
- Failed payment follows the intended failure state.

Endpoint currently implemented: `POST /api/webhooks/razorpay`.

### WhatsApp checks

- Call the webhook verification GET with the configured verify token.
- Send a controlled missing-document notification through the application path.
- Confirm the Meta API response ID is persisted.
- Send a duplicate/failing request and verify retry/error status behavior.
- Post a signed inbound webhook and verify persistence and signature validation.

### Policy and audit checks

- Create a transaction with missing evidence and confirm fail-closed behavior.
- Create a transaction whose beneficiary, amount, purpose, OCR, and AI values pass all rules.
- Confirm the result is exactly `APPROVED` or `COMPLIANCE_HOLD`.
- Confirm a policy evaluation row contains every rule result.
- Confirm every state transition has one audit row with old state, new state, actor, correlation ID, and timestamp.
- Confirm audit rows cannot be updated or deleted by the application/database role.
- Confirm held transactions can be approved or rejected only by authorized reviewers with justification.

### End-to-end acceptance test

Run the full SRS workflow in order:

```text
Register/login -> create transaction -> Razorpay webhook -> evidence pending
-> WhatsApp request -> upload invoice -> OCR -> AI JSON -> policy evaluation
-> APPROVED or COMPLIANCE_HOLD -> human review if held -> audit timeline -> dashboard refresh
```

Acceptance requires successful execution against a running PostgreSQL-backed application, not only the presence of source files.

## Evidence Index

- Requirements: [doc/Phase_01_Product_and_Compliance_Requirements.md](doc/Phase_01_Product_and_Compliance_Requirements.md)
- SRS: [doc/SRS_Aegis_X.md](doc/SRS_Aegis_X.md)
- Technical architecture: [doc/TAS_Aegis_X.md](doc/TAS_Aegis_X.md)
- Backend build/dependencies: [backend/pom.xml](backend/pom.xml)
- Local configuration: [backend/src/main/resources/application.yml](backend/src/main/resources/application.yml), [backend/src/main/resources/application-dev.yml](backend/src/main/resources/application-dev.yml), [backend/src/main/resources/application-prod.yml](backend/src/main/resources/application-prod.yml)
- Database migrations: [backend/src/main/resources/db/migration](backend/src/main/resources/db/migration)
- Local orchestration: [docker-compose.yml](docker-compose.yml)
- Backend container: [backend/Dockerfile](backend/Dockerfile)
- Frontend entry point: [frontend/index.html](frontend/index.html)
- Frontend API client: [frontend/js/api.js](frontend/js/api.js)
- OCR implementation: [backend/src/main/java/com/aegisx/document/ocr/TesseractOcrProcessor.java](backend/src/main/java/com/aegisx/document/ocr/TesseractOcrProcessor.java)
- Transaction state machine: [backend/src/main/java/com/aegisx/transaction/service/TransactionStateMachine.java](backend/src/main/java/com/aegisx/transaction/service/TransactionStateMachine.java)
- Transaction states: [backend/src/main/java/com/aegisx/transaction/entity/TransactionStatus.java](backend/src/main/java/com/aegisx/transaction/entity/TransactionStatus.java)
- Audit service: [backend/src/main/java/com/aegisx/audit/service/AuditService.java](backend/src/main/java/com/aegisx/audit/service/AuditService.java)
- Review controller: [backend/src/main/java/com/aegisx/review/controller/ReviewNoteController.java](backend/src/main/java/com/aegisx/review/controller/ReviewNoteController.java)
- S3 placeholder: [backend/src/main/java/com/aegisx/storage/service/S3StorageService.java](backend/src/main/java/com/aegisx/storage/service/S3StorageService.java)

# Phase 15 Frontend Verification and Readiness

**Status: Partially implemented; production acceptance is blocked.** The frontend now consumes the verified Spring API contracts for the implemented screens, but no authenticated end-to-end workflow has been demonstrated. The backend was unreachable during this run and its Maven test command stopped during dependency resolution.

## 1. API Integration Verification

| Surface | Frontend integration | Contract evidence | Runtime result |
| --- | --- | --- | --- |
| Register / login | `POST /api/auth/register`, `POST /api/auth/login`; registration redirects to login; protected pages refresh identity using `GET /api/auth/me` | Matched `RegisterRequest`, `AuthResponse`, and `UserResponse` fields | Not exercised; backend unavailable |
| Merchant transactions | Paginated `GET /api/transactions`; `POST /api/transactions` sends `amount`, `currency`, `beneficiaryName`, `purposeCode` | Matched request/response DTOs and transaction enum | Not exercised |
| Evidence / OCR | Multipart `POST /api/transactions/{id}/documents`; then `POST /api/documents/{documentId}/process`; extraction `GET /api/documents/{documentId}/extraction` | Document and extraction DTOs | Not exercised |
| AI / policy | `GET` existing results and `POST` analysis/evaluation triggers | AI and policy response DTO fields used; no client-side decision logic | Not exercised |
| Audit / notes / notifications | Transaction audit, append-only note, and notification-history endpoints | Event/note/notification DTO fields mapped | Not exercised |
| Compliance | `GET /api/v1/compliance/queue`; `GET /review/{transactionId}`; `POST /review` with decision and required justification | Controller routes and request DTO inspected | Not exercised |
| Settlement | Reads status pages; transaction-scoped lookup for merchant KPI; retry and reconciliation calls wired | Controller paths, page shape, and role annotations inspected | Not exercised |
| Operations | Actuator health/meters, Prometheus, `/api/performance/metrics`, transaction counts, review queue, audit lookup | Controller and management route configuration inspected in source | Not exercised |

Shared API handling adds bearer authorization, request correlation IDs, response-envelope unwrapping, API-level failure handling, and multipart upload progress. JWTs are held in `sessionStorage`; user profile identity is refreshed from the server.

The local backend probe at `http://localhost:8080/actuator/health` failed to connect. `backend/mvnw.cmd -f backend/pom.xml test` failed before compilation because Maven could not resolve the cached dependencies `org.apache.commons:commons-imaging:1.0` and `org.springframework.ai:spring-ai-openai-spring-boot-starter:1.0.5`. Therefore source inspection and frontend tests are not evidence of a successful live integration.

Further source inspection found that the review, settlement, and evidence controllers import `ApiResponse` types from `com.aegisx.common.dto` or `com.aegisx.common.response`, but no matching `ApiResponse.java` exists in the current workspace. The frontend unwraps the conventional `{ success, data, message }` shape; this cannot be confirmed against a compilable response DTO until those backend classes are restored. Several requested review filters (notably settlement failure) also are not represented by `ReviewQueueItemResponse`; category coverage should be validated or implemented with backend query support before acceptance.

## 2. RBAC Verification

Frontend guards refresh the role from `/api/auth/me`; server-side `@PreAuthorize` remains authoritative.

| Backend role | UI workspace | Verified API authorization in source | Notes |
| --- | --- | --- | --- |
| `MERCHANT` | Dashboard and own transaction workspaces | Own transaction, document, audit, notification, and transaction settlement lookup | Merchant note creation is hidden and forbidden by the notes controller |
| `COMPLIANCE_OFFICER` | Compliance queue, transaction workspace, settlement read view | Review queue/decisions/history; review notes; settlement status/read and settlement initiation | Retry and reconciliation require a different role |
| `ADMIN` | Operations, review queue, transaction workspace | Review and transaction operations; operations screens use existing endpoints | Settlement status controller does not grant `ADMIN` |
| `SETTLEMENT_OPERATOR` | Operator-only settlement controls when role exists | Retry, reconciliation, and settlement processing | **Unavailable in the current backend `Role` enum**, which only defines `MERCHANT`, `COMPLIANCE_OFFICER`, and `ADMIN`; no user can currently receive this role through the present authentication model |

RBAC remains incomplete for the acceptance criteria until backend role provisioning and settlement authorization are reconciled. The compliance review controller also contains a hardcoded reviewer UUID placeholder; actual review persistence and reviewer attribution require backend validation.

## 3. End-to-End Walkthrough

The following is the intended operator walkthrough and the corresponding frontend/API path. Steps are **not marked complete** because the backend was unavailable:

1. Open `index.html`, register with full name/email/password, then sign in from `login.html`. Registration does not set a token or auto-login.
2. On the merchant dashboard, create a transaction using beneficiary, amount, currency, and purpose code. The UI does not simulate Razorpay payment creation; payment state can only be shown when backend events update the transaction.
3. Open the returned transaction ID, choose an evidence type, upload PDF/PNG/JPG up to 25 MB, then trigger OCR by returned document ID.
4. Inspect backend OCR fields/confidence, AI findings and confidence with the advisory-only notice, deterministic policy results, and the transaction state.
5. For `COMPLIANCE_HOLD`, use the compliance queue to load the review record and full transaction workspace; submit approve/reject only with a nonblank 10+ character justification.
6. Inspect the audit timeline and transaction notification history. Export audit response as JSON.
7. A role authorized by the backend opens settlement status, retries eligible failed records, and processes reconciliation. Current role provisioning prevents demonstrating the operator-only actions.
8. An administrator views live operations/Actuator data and searches a transaction audit timeline.

## 4. Screenshot Evidence

Available UI screenshots are public/authenticated-entry screens only; they do not depict completed API workflows:

- [Landing page, desktop](../frontend/evidence/landing.png)
- [Landing page, narrow viewport](../frontend/evidence/landing-mobile.png)
- [Login, desktop](../frontend/evidence/login.png)
- [Login, narrow viewport](../frontend/evidence/login-mobile.png)
- [Registration, desktop](../frontend/evidence/register.png)
- [Registration, narrow viewport](../frontend/evidence/register-mobile.png)

Authenticated workflow screenshots are pending a reachable, seeded backend. Browser reload checks on login and registration returned zero console errors and no Tailwind CDN warning after the local CSS build. Landing, login, and registration reported equal client and scroll widths in narrow-viewport checks after fixing form input box sizing; the browser harness reported inconsistent CSS viewport widths after resizing, so device-specific visual review should still be repeated in a standard browser.

## 5. Production Readiness Checklist

- [x] Registration request and redirect match backend contract; no automatic login.
- [x] Role is sourced from backend login/`/auth/me`, not a user-selected form value.
- [x] JWT is session-scoped; requests include correlation IDs.
- [x] Upload uses multipart transfer and actual progress events.
- [x] API-backed queue, settlement, audit, note, notification, operations, and transaction paths are wired where controllers exist.
- [x] AI findings are explicitly advisory; policy result is rendered as the decision authority.
- [x] Vite production build includes all eight HTML pages.
- [x] Frontend contract suite: 82 assertions passed, including API transport behavior, pagination, and bundled Tailwind checks.
- [x] Tailwind utilities are compiled locally through PostCSS for production; no Tailwind runtime CDN remains.
- [x] Upgrade Vite to 6.4.3 and run `npm audit`: zero vulnerabilities reported.
- [ ] Restore Maven dependency resolution and get the backend compiling/testing.
- [ ] Fix/verify OCR ownership check: `OcrController` converts `Authentication.getName()` to UUID, while the rest of the authentication flow uses the username/email principal.
- [ ] Provide a backend-issued settlement-operator role or authorize required settlement actions for an existing role.
- [ ] Replace the hardcoded reviewer UUID in compliance review authentication with the authenticated user identity.
- [ ] Verify actuator/Prometheus exposure, metric names, p95 samples, queue depth, and settlement business metrics against a live backend.
- [ ] Run registration/login, transaction/evidence/OCR/AI/policy, hold review, settlement, and administrator journeys against live seeded data.
- [ ] Restore the missing common `ApiResponse` types and verify the exact envelope shape against backend responses.
- [ ] Verify the requested high-risk/OCR-failure/AI-failure/settlement-failure queue filters against backend query behavior.
- [ ] Capture authenticated workflow screenshots and repeat desktop/mobile visual and console checks with that live backend.

## 6. Verification Commands

```powershell
npm.cmd --prefix frontend test
npm.cmd --prefix frontend run build
.\backend\mvnw.cmd -f backend\pom.xml test
```

The frontend test and build commands pass, and `npm audit` reports zero vulnerabilities. The backend command currently fails before compile due to unresolved Maven dependencies. The Vite server is available at `http://127.0.0.1:5173/` for local UI inspection.

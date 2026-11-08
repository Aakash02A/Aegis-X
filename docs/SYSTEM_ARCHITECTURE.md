# Aegis-X System Architecture

## 1. Architectural Philosophy & The Golden Principle

Aegis-X is structured as a resilient, high-integrity modular monolith built on **Java 21 / Spring Boot 3.3.4** and **MySQL 8.4 LTS**. It automates compliance checks, evidence validation, and payment reconciliation for cross-border financial settlements while maintaining strict auditability.

```
┌────────────────────────────────────────────────────────────────────────────────────────┐
│                                 THE GOLDEN PRINCIPLE                                   │
│                                                                                        │
│               AI is an ADVISOR. Java is the SOLE COMPLIANCE AUTHORITY.                 │
│                                                                                        │
│   • AI produces optional advisory findings from untrusted document text.              │
│     It cannot set a decision or supply inputs to the policy rules.                    │
│   • Five deterministic Java rules use transaction fields, checklist rows,             │
│     and selected structured OCR fields to produce APPROVED or COMPLIANCE_HOLD.         │
└────────────────────────────────────────────────────────────────────────────────────────┘
```

---

## 2. High-Level System Architecture

```
                       ┌─────────────────────────┐
                       │   Vite Frontend Client  │
                       │ (HTML5/CSS Tokens/JS SPA)│
                       └────────────┬────────────┘
                                    │ HTTP / REST / Bearer JWT
                                    ▼
┌────────────────────────────────────────────────────────────────────────────────────────┐
│                              Spring Boot 3.3.4 Monolith                                │
│                                                                                        │
│  ┌───────────────────┐    ┌────────────────────┐    ┌───────────────────────────────┐  │
│  │   Auth & RBAC     │    │ Transaction State  │    │      Payment Integrity        │  │
│  │ (JWT, BCrypt,     │───▶│     Machine        │◀───│ (Double-entry Ledger,         │  │
│  │  Role Hierarchies)│    │(Guarded Lifecycle) │    │  Reconciliation, Exceptions)  │  │
│  └───────────────────┘    └─────────┬──────────┘    └───────────────▲───────────────┘  │
│                                     │                               │                  │
│               ┌─────────────────────┼────────────────────┐          │                  │
│               ▼                     ▼                    ▼          │                  │
│      ┌──────────────────┐  ┌──────────────────┐ ┌────────────────┐  │                  │
│      │ Evidence Storage │  │ Razorpay Webhook │ │ Deterministic  │  │                  │
│      │ & OCR Pipeline   │  │ (HMAC Verified,  │ │ Policy Engine  │──┘                  │
│      │ (Tika/Tesseract) │  │  Idempotency Key)│ │ (Hard Rules)   │                     │
│      └────────┬─────────┘  └──────────────────┘ └────────┬───────┘                     │
│               │                                          │                             │
│               ▼                                          ▼                             │
│      ┌──────────────────┐                       ┌────────────────┐                     │
│      │ Spring AI Engine │                       │ Manual Review  │                     │
│      │ (Advisory Only,  │                       │ & WhatsApp     │                     │
│      │  Non-blocking)   │                       │ Notifications  │                     │
│      └──────────────────┘                       └────────────────┘                     │
│                                                                                        │
│  ┌──────────────────────────────────────────────────────────────────────────────────┐  │
│  │                     Immutable Audit Event Log & Correlation IDs                  │  │
│  └──────────────────────────────────────────────────────────────────────────────────┘  │
└───────────────────────────────────────────┬────────────────────────────────────────────┘
                                            │ JDBC (HikariCP, UTC, READ-COMMITTED)
                                            ▼
                          ┌──────────────────────────────────┐
                          │          MySQL 8.4 LTS           │
                          │     (Flyway Schema Migrations)   │
                          └──────────────────────────────────┘
```

---

## 3. Core Subsystems & Bounded Contexts

### 3.1 Authentication & Role-Based Access Control (RBAC)
* **Token Model:** Stateless HMAC-SHA256 JWT tokens.
* **Roles:**
  * `MERCHANT`: Submits transactions, uploads commercial evidence, views merchant transaction status.
  * `COMPLIANCE_OFFICER`: Evaluates flagged transactions, inputs mandatory justification notes, approves/rejects holds.
  * `SETTLEMENT_OPERATOR`: Reconciles bank transfers, retries settlement batches, inspects payout exceptions.
  * `ADMIN`: System configuration, audit trail oversight, user administration.
* **Security Guardrails:** Passwords hashed with BCrypt (strength 12); sensitive endpoints (`/actuator/env`, `/actuator/beans`) blocked from public ingress.

### 3.2 Transaction State Machine
The current transaction enum includes `CREATED`, `PAYMENT_RECEIVED`, `PAYMENT_FAILED`, `EVIDENCE_PENDING`, `OCR_PROCESSING`, `OCR_FAILED`, `AI_ANALYSIS`, `AI_ANALYSIS_FAILED`, `POLICY_EVALUATION`, `APPROVED`, `COMPLIANCE_HOLD`, `REJECTED`, `SETTLEMENT_PENDING`, `SETTLEMENT_FAILED`, and `SETTLED`. Legal transitions are enforced by `TransactionStateMachine`. `APPROVED` can proceed to `SETTLEMENT_PENDING`; `SETTLED`, `REJECTED`, and `PAYMENT_FAILED` are terminal. AI analysis may fail without preventing the deterministic policy evaluation from running.

### 3.3 Evidence Collection & OCR Pipeline
* **Digital Documents:** Text extraction uses Apache Tika; Tesseract is available as an OCR path for image-based content.
* **Confidence:** Returned extraction confidence is a pattern-match heuristic and is labeled `HEURISTIC`; it is not a calibrated OCR probability and is not a policy input.
* **Evidence extraction:** The integrity evidence response labels pattern-based scores as `HEURISTIC`, human corrections as `HUMAN_CONFIRMED`, and unattempted extraction as `NOT_AVAILABLE`.
* **Storage:** The configured local-storage path is implemented. S3 remains an unimplemented template and is not described as a working adapter.

### 3.4 AI Advisory Engine
* **Role:** Optional analysis prompt receives transaction details and OCR JSON/text and requests a summary, findings, missing-evidence notes, and recommended checks. It does not produce a payment decision.
* **Untrusted input:** OCR JSON and extracted text are marked as untrusted prompt data. The parser accepts a fixed DTO schema, rejects invalid types and confidence values, and drops unknown fields such as `decision` before persistence.
* **Policy boundary:** AI output and confidence are absent from `RuleContext`. Policy evaluation can proceed from `AI_ANALYSIS` or `AI_ANALYSIS_FAILED`; an AI failure appears under the existing `AI_FAILED` review-queue filter, but it cannot be directly approved or rejected while in that state. Low confidence is advisory and does not trigger a policy threshold or separate queue. A real external LLM call is not claimed as verified here.

### 3.5 Deterministic Policy Engine
* Executes five rules in fixed order: `DOCUMENT_COMPLETENESS`, `BENEFICIARY_MATCH`, `AMOUNT_CONSISTENCY`, `CURRENCY_CONSISTENCY`, and `PURPOSE_CODE`.
* Inputs are the transaction, required-document checklist, and structured OCR fields `beneficiary`/`vendor_name`, `total_amount`/`amount`, and `currency`. No AI value or OCR confidence is read.
* All three fixed document types must be fulfilled; beneficiary uses trimmed case-insensitive exact-or-contains matching; amount is an exact decimal comparison; currency matches after trim and uppercase; purpose is checked against a fixed nine-code whitelist. There are no configurable numeric thresholds in these rules.
* All rules passing produces `APPROVED`; any failure produces `COMPLIANCE_HOLD`. The service persists the evaluation and one violation row per failed rule.

### 3.6 Payment Integrity Bounded Context
Located in `com.aegisx.integrity`, this subsystem supports payment integrity review:
* **Double-Entry Ledger:** All payment records track debit and credit ledger balances.
* **Duplicate-Safe Ingestion:** Webhook events are hashed and validated with compound unique keys: `(workspace_id, provider, provider_event_id)`.
* **Automated Reconciliation:** Matches provider captures against internal transaction orders.
* **Discrepancy & Exception Handling:** Discrepancies generate `ExceptionCase` records with automatic state reopening on subsequent evidence upload or manual correction.

### 3.7 Notification & Audit Services
* **WhatsApp Cloud API:** Sends template-driven alerts for compliance holds and status changes.
* **Audit Trail:** Every state change, webhook, OCR extraction, and compliance decision persists an append-only `AuditEvent` record with an end-to-end `correlationId`.

### 3.8 Payment and Settlement Boundaries
Aegis-X collects evidence, provides optional AI advice, records Java-decided policy outcomes,
reconciles provider data, and audits those actions. It has no customer payment capture or refund
path. An external provider performs customer capture; a `payment.captured` webhook only records that
completed outcome. The integrity payment adapter creates orders and reads provider state; it exposes
no capture, refund, transfer, or payout mutation.

A separate `RazorpayXClient.submitPayout` (`POST /payouts`) path exists for Test Mode. It is disabled
by default, requires `RAZORPAYX_ENABLED` and complete Test Mode credentials, rejects non-`rzp_test_`
key IDs, and is reachable through settlement processing after an approved policy state. Initiation
requires `COMPLIANCE_OFFICER` or `SETTLEMENT_OPERATOR`; processing accepts `SETTLEMENT_OPERATOR` or
`SYSTEM`, so the process route is role-gated but can be system-invoked. It does not provide a live
funds-movement path. Beneficiary contact and fund-account calls are Test Mode setup operations, not
payout calls. `INTEGRITY_ALLOW_FINANCIAL_EXECUTION` controls read-only integrity recovery and is not
the RazorpayX payout feature flag.

Before dispatch, `SettlementService` locks the settlement, refreshes and locks the linked
transaction row, and requires `TransactionStatus.APPROVED`. The execution service repeats the same
approval check. Thus `REJECTED` (BLOCKED) and `COMPLIANCE_HOLD` (ESCALATED) do not reach the
processor, including by direct invocation of the process endpoint. A pessimistic settlement-row
lock serializes repeated/concurrent processing; after the first submission commits, subsequent
calls see a non-`INITIATED` state. The payout client also sends a stable per-settlement idempotency
stable idempotency key across retries. If a provider payout ID was assigned before a local failure,
the retry endpoint refuses another submission; automatic retry after provider assignment is
unsupported and requires manual resolution.

RazorpayX is disabled by default and accepts only a key ID starting `rzp_test_`. The `SIMULATED`
processor is available only in development/test profiles and records `moneyMoved=false`; it is not a
provider payout. Customer capture/refund and live funds movement are outside Aegis-X's supported
product scope; no automated payment-capture endpoint is implemented.

---

## 4. Frontend Architecture

The frontend is an ultra-fast, zero-dependency Single Page Application (SPA) located in `frontend/`:
* **Tooling:** Vite 6 with pure HTML5 and vanilla JavaScript modules.
* **Design System:** Obsidian & Cyan design token architecture (`css/components.css`, `css/animations.css`).
* **Security & Self-Hosting:** 100% self-hosted webfonts and Font Awesome vector icons (`css/fonts.css`, `css/icons.css`). Zero runtime third-party CDN requests.
* **RBAC Route Guards:** Protected pages check session tokens and user roles against backend state.


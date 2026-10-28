# Aegis-X System Architecture

## 1. Architectural Philosophy & The Golden Principle

Aegis-X is structured as a resilient, high-integrity modular monolith built on **Java 21 / Spring Boot 3.3.4** and **MySQL 8.4 LTS**. It automates compliance checks, evidence validation, and payment reconciliation for cross-border financial settlements while maintaining strict auditability.

```
┌────────────────────────────────────────────────────────────────────────────────────────┐
│                                 THE GOLDEN PRINCIPLE                                   │
│                                                                                        │
│               AI is an ADVISOR. Java is the SOLE COMPLIANCE AUTHORITY.                 │
│                                                                                        │
│   • AI parses unstructured documents, extracts entities, and scores match confidence. │
│     It NEVER transitions transaction states, approves payouts, or waives holds.        │
│   • Deterministic Java policy rules strictly govern all state transitions, holds,     │
│     and settlement disbursements.                                                      │
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
Transaction state advances deterministically through strict guard conditions:
1. `CREATED`: Transaction registered by merchant with counterparty and amount.
2. `PENDING_PAYMENT`: Awaiting verified payment capture notification.
3. `PAYMENT_RECEIVED`: Razorpay webhook verified with HMAC signature and unique idempotency key.
4. `EVIDENCE_PENDING`: Required trade documentation (invoices, BOLs) requested from merchant.
5. `UNDER_REVIEW`: OCR extraction complete; evaluated against deterministic policy rules.
6. `APPROVED`: All policy checks passed (or officer approved).
7. `SETTLED`: Funds disbursed to beneficiary via payout rail.
8. `HELD` / `REJECTED`: Assigned upon policy violation or officer rejection.

*Rule: States cannot be skipped. Moving from `CREATED` directly to `APPROVED` without verified payment and evidence validation is rejected.*

### 3.3 Evidence Collection & OCR Pipeline
* **Digital Documents:** Extracted directly via Apache Tika 2.9.
* **Scanned PDFs / Images:** Rasterized via Apache PDFBox 3.0 and processed through Tesseract OCR fallback.
* **Resilience:** OCR extraction errors fail gracefully to `FAILED` extraction status rather than triggering unhandled 500 errors.
* **Immutability:** Original files stored on disk; document versions marked with supersession markers rather than hard deletion.

### 3.4 AI Advisory Engine
* **Integration:** Spring AI framework integrated with OpenAI GPT-4o-mini.
* **Role:** Extracts line items, counterparty addresses, and tax identifiers from raw OCR text and suggests confidence metrics.
* **Startup Safety:** Custom `BlankOpenAiKeyEnvironmentPostProcessor` prevents Spring Boot startup crashes when `OPENAI_API_KEY` is not provided.
* **Fail-Safe Operation:** If the OpenAI API is unreachable or rate-limited, transactions proceed to deterministic Java rule checks without interruption.

### 3.5 Deterministic Policy Engine
* Executes zero-tolerance business and regulatory validation rules in Java:
  * Amount thresholds and foreign exchange sanction thresholds.
  * Tax identification format validation (e.g., GSTIN / VAT checksums).
  * Counterparty country sanction checks.
  * Extracted invoice total match against authorized payment capture amount.
* Transactions violating rules trigger an immutable `PolicyViolation` record and transition the transaction to `HELD` status.

### 3.6 Payment Integrity Bounded Context
Located in `com.aegisx.integrity`, this subsystem guarantees financial correctness:
* **Double-Entry Ledger:** All payment records track debit and credit ledger balances.
* **Duplicate-Safe Ingestion:** Webhook events are hashed and validated with compound unique keys: `(workspace_id, provider, provider_event_id)`.
* **Automated Reconciliation:** Matches provider captures against internal transaction orders.
* **Discrepancy & Exception Handling:** Discrepancies generate `ExceptionCase` records with automatic state reopening on subsequent evidence upload or manual correction.

### 3.7 Notification & Audit Services
* **WhatsApp Cloud API:** Sends template-driven alerts for compliance holds and status changes.
* **Audit Trail:** Every state change, webhook, OCR extraction, and compliance decision persists an append-only `AuditEvent` record with an end-to-end `correlationId`.

---

## 4. Frontend Architecture

The frontend is an ultra-fast, zero-dependency Single Page Application (SPA) located in `frontend/`:
* **Tooling:** Vite 6 with pure HTML5 and vanilla JavaScript modules.
* **Design System:** Obsidian & Cyan design token architecture (`css/components.css`, `css/animations.css`).
* **Security & Self-Hosting:** 100% self-hosted webfonts and Font Awesome vector icons (`css/fonts.css`, `css/icons.css`). Zero runtime third-party CDN requests.
* **RBAC Route Guards:** Protected pages check session tokens and user roles against backend state.


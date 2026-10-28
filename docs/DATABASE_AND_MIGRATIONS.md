# Aegis-X Database & Migrations Guide

## 1. Engine & Runtime Configuration

Aegis-X is powered by **MySQL 8.4 LTS**. Database schemas and versioning are managed deterministically using **Flyway 11.x**.

### 1.1 Connection Requirements & Invariants

To guarantee financial consistency and prevent silent timezone shifting, JDBC connections must be configured with specific session parameters:

```properties
spring.datasource.url=jdbc:mysql://${DB_HOST:localhost}:${DB_PORT:3306}/${DB_NAME:aegisx_db}?connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true&characterEncoding=UTF-8&useSSL=false&allowPublicKeyRetrieval=true
```

| Parameter | Value | Purpose |
| :--- | :--- | :--- |
| `connectionTimeZone` | `UTC` | Pins client-side time conversions to UTC. |
| `forceConnectionTimeZoneToSession` | `true` | Executes `SET time_zone = '+00:00'` upon opening the session so MySQL `CURRENT_TIMESTAMP(6)` defaults evaluate in UTC. |
| `characterEncoding` | `UTF-8` | Ensures lossless unicode transmission. |
| Collation & Charset | `utf8mb4_0900_ai_ci` | Default database encoding for multi-language invoice support and international names. |
| Transaction Isolation | `READ-COMMITTED` | Standard financial concurrency level preventing dirty reads while eliminating unnecessary phantom lock contention. |

---

## 2. Flyway Migration Sequence

The database schema migrations reside in `backend/src/main/resources/db/migration/`:

```
backend/src/main/resources/db/migration/
├── V1__mysql_baseline.sql
├── V28__explicit_supersession_marker.sql
├── V29__add_user_phone.sql
└── V30__scope_webhook_event_idempotency_by_workspace.sql
```

### Migration Overview

| Migration Version | Description | Key Schema Changes |
| :--- | :--- | :--- |
| **`V1__mysql_baseline.sql`** | Single unified MySQL 8.4 baseline | Provisions 15+ core relational tables including `users`, `transactions`, `webhook_events`, `documents`, `document_extractions`, `ai_analysis`, `policy_evaluations`, `notification_messages`, `audit_events`, `manual_review_notes`, `evidence_checklists`, `policy_violations`, `compliance_reviews`, `settlements`, and the `integrity_*` double-entry ledger tables. |
| **`V28__explicit_supersession_marker.sql`** | Document revision tracking | Adds `is_superseded` and `superseded_at` columns to `documents` so revised invoices never delete historical records. |
| **`V29__add_user_phone.sql`** | WhatsApp integration support | Adds `phone` column (`VARCHAR(32)`) to `users` table for international SMS/WhatsApp notification dispatch. |
| **`V30__scope_webhook_event_idempotency_by_workspace.sql`** | Multi-tenant webhook isolation | Updates `webhook_events` idempotency unique index to `(workspace_id, provider, provider_event_id)` preventing cross-workspace collision. |

---

## 3. Core Relational Entities & Data Model

```
┌─────────────────┐       1:N       ┌─────────────────────┐
│      users      │────────────────▶│    transactions     │
│  (Auth & RBAC)  │                 │  (State Machine)    │
└─────────────────┘                 └──────────┬──────────┘
                                               │
               ┌───────────────────────────────┼───────────────────────────────┐
               │ 1:N                           │ 1:N                           │ 1:N
               ▼                               ▼                               ▼
     ┌──────────────────┐            ┌──────────────────┐            ┌──────────────────┐
     │    documents     │            │policy_evaluations│            │   settlements    │
     │  (Files & OCR)   │            │   & violations   │            │   (Payout rail)  │
     └────────┬─────────┘            └──────────────────┘            └──────────────────┘
              │ 1:N
              ▼
     ┌──────────────────┐
     │document_extract. │
     │ (Structured Data)│
     └──────────────────┘

┌────────────────────────────────────────────────────────────────────────────────────────┐
│                        Payment Integrity Context (Double-Entry)                        │
│                                                                                        │
│  ┌────────────────────────┐         1:N          ┌──────────────────────────────────┐  │
│  │    integrity_orders    │─────────────────────▶│     integrity_ledger_entries     │  │
│  │ (Gross/Net Amounts)    │                      │  (Credit/Debit Double-entry)     │  │
│  └───────────┬────────────┘                      └──────────────────────────────────┘  │
│              │ 1:N                                                                     │
│              ▼                                                                         │
│  ┌────────────────────────┐         1:N          ┌──────────────────────────────────┐  │
│  │   webhook_events       │─────────────────────▶│     integrity_exceptions         │  │
│  │ (HMAC, Raw Payload)    │                      │  (Discrepancies & Disputed Holds)│  │
│  └────────────────────────┘                      └──────────────────────────────────┘  │
└────────────────────────────────────────────────────────────────────────────────────────┘
```

---

## 4. Key Schema Invariants & Design Rules

### 4.1 Fractional Timestamp Precision
All audit and timestamp columns use `DATETIME(6) DEFAULT CURRENT_TIMESTAMP(6)`. Fractional microseconds prevent ordering ambiguities when state transitions occur in rapid succession within the same millisecond.

### 4.2 Monetary Storage
All monetary values (`amount`, `gross_amount`, `fee_amount`, `tax_amount`) are strictly defined as `DECIMAL(19, 4)` to avoid floating-point rounding errors during currency conversion and tax reconciliation.

### 4.3 Immutable Audit Events
The `audit_events` table contains no update triggers and has no foreign keys that cascade deletes. Once written, audit events are permanent.

### 4.4 Soft Supersession vs Hard Deletes
Documents and extraction results are never hard-deleted from MySQL. When a user uploads a revised invoice, the existing document row is flagged with `is_superseded = TRUE`, maintaining full audit traceability for regulatory compliance.


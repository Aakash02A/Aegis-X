# Aegis-X — Phase 01: Product & Compliance Requirements

> **Document Type:** Software Requirements Specification (SRS)
>
> **Version:** 1.0 (MVP)
>
> **Project:** Aegis-X – AI-Assisted Compliance & Settlement Orchestration System

---

# Table of Contents

1. Executive Summary
2. Problem Statement
3. Proposed Solution
4. Business Scenario
5. User Personas
6. Project Scope
7. Functional Requirements
8. Non-Functional Requirements
9. Transaction State Machine
10. AI Responsibilities
11. Deterministic Policy Engine
12. Success Metrics
13. Technology Stack
14. Acceptance Criteria

---

# 1. Executive Summary

Aegis-X is an **AI-assisted compliance and settlement orchestration system** for cross-border payments.

Instead of treating compliance as a single manual approval step, Aegis-X creates a **stateful evidence-driven workflow** where documents are collected, analyzed, validated, and audited before settlement proceeds.

The system combines:

- Dynamic evidence collection
- AI-assisted document understanding
- Deterministic compliance validation
- Human escalation
- Complete auditability

> **Core Principle:** AI assists with understanding evidence, while Java makes the final compliance decision.

---

# 2. Problem Statement

Cross-border payments are frequently delayed because compliance information is spread across multiple disconnected processes.

A typical payment may require:

- Commercial invoice
- Beneficiary verification
- Purpose-code validation
- Supporting documents
- Compliance review

Current workflows typically involve manual emails, repeated follow-ups, and fragmented tracking.

## Existing Workflow

```text
Payment Created
      ↓
Manual Email
      ↓
Invoice Requested
      ↓
Supporting Documents Requested
      ↓
Compliance Review
      ↓
Payment Released
```

## Current Challenges

- Missing documents delay settlement.
- Compliance teams manually chase customers.
- No centralized transaction state.
- Audit trails become fragmented.
- Traditional rule engines struggle when information is incomplete.

---

# 3. Proposed Solution

Aegis-X introduces an **evidence-first compliance workflow**.

```text
Payment Event
      ↓
Webhook
      ↓
Transaction Created
      ↓
Evidence Collection
      ↓
OCR Processing
      ↓
AI Analysis
      ↓
Deterministic Policy Engine
      ↓
APPROVED / COMPLIANCE_HOLD
      ↓
Settlement / Human Review
```

Instead of asking:

> "Should this payment be approved?"

Aegis-X asks:

> "What evidence is missing, and what does the available evidence prove?"

---

# 4. Business Scenario

A single reference scenario is used throughout development.

## Scenario

| Item | Value |
|------|-------|
| Merchant | ABC Exports (India) |
| Beneficiary | XYZ Imports LLC (USA) |
| Amount | USD 2,000 |
| Purpose | Cross-border business payment |

## Expected Workflow

1. Merchant creates a payment.
2. Razorpay Sandbox triggers a webhook.
3. A transaction enters **EVIDENCE_PENDING**.
4. Merchant uploads an invoice.
5. OCR extracts document text.
6. Spring AI generates structured evidence.
7. Java Policy Engine evaluates compliance.
8. Transaction becomes:
   - **APPROVED**, or
   - **COMPLIANCE_HOLD**.

This scenario becomes the reference example for development, testing, and demonstrations.

---

# 5. User Personas

## Merchant

### Role

Creates cross-border payments and submits supporting documents.

### Responsibilities

- Create payments
- Upload invoices
- Track transaction status

### Pain Points

- Doesn't know which documents are missing.
- Waits for manual compliance updates.
- Has limited visibility into payment progress.

---

## Compliance Officer

### Role

Reviews transactions requiring manual intervention.

### Responsibilities

- Review HOLD transactions
- Validate evidence
- Approve or reject exceptions
- Monitor audit history

### Pain Points

- Manual document collection
- Fragmented transaction information
- Repetitive verification tasks

---

## Administrator

### Role

Maintains the Aegis-X platform.

### Responsibilities

- Manage users
- Configure integrations
- Maintain reference data
- Monitor system health

---

# 6. Project Scope

## Included in MVP

- Razorpay Sandbox integration
- Payment webhook handling
- Transaction lifecycle
- Document upload
- OCR
- AI evidence analysis
- Deterministic policy engine
- Human review workflow
- Audit logging
- Web dashboard

## Not Included

- Live banking integrations
- SWIFT settlement
- Real FEMA approval
- Production AML screening
- Enterprise KYC onboarding

Keeping these features out of scope allows the MVP to remain achievable during the hackathon.

---

# 7. Functional Requirements

| ID | Requirement |
|----|-------------|
| FR-01 | User login |
| FR-02 | Create transaction |
| FR-03 | Receive Razorpay webhook |
| FR-04 | Upload invoice |
| FR-05 | Extract document text |
| FR-06 | Generate structured AI analysis |
| FR-07 | Validate compliance rules |
| FR-08 | Mark APPROVED/HOLD |
| FR-09 | Human review |
| FR-10 | Audit logging |
| FR-11 | Dashboard visibility |

---

# 8. Non-Functional Requirements

| Category | Requirement |
|-----------|------------|
| Security | JWT authentication |
| Performance | Fast API responses |
| Reliability | Every state transition logged |
| Maintainability | Modular Spring Boot architecture |
| Auditability | Immutable audit trail |
| Scalability | Event-driven workflow |

---

# 9. Transaction State Machine

Aegis-X uses a state-machine approach instead of scattered status flags.

```text
CREATED
   ↓
PAYMENT_RECEIVED
   ↓
EVIDENCE_PENDING
   ↓
OCR_PROCESSING
   ↓
AI_ANALYSIS
   ↓
POLICY_EVALUATION
   ├── APPROVED
   └── COMPLIANCE_HOLD
```

Every state transition is stored in PostgreSQL and recorded in the audit log.

---

# 10. AI Responsibilities

The AI acts as an **Evidence Analyst**, not a decision-maker.

## AI Can

- Extract invoice number
- Extract beneficiary information
- Extract transaction amount
- Detect inconsistencies
- Generate structured JSON
- Explain findings
- Return confidence scores

### Example Output

```json
{
  "invoiceNumber": "INV-2048",
  "beneficiary": "XYZ Imports LLC",
  "amount": 2000,
  "confidence": 0.97,
  "reasoning": [
    "Beneficiary matches payment.",
    "Amount matches invoice."
  ]
}
```

## AI Cannot

- Approve payments
- Reject payments
- Change transaction state
- Trigger Razorpay settlement
- Override compliance rules

This separation is one of Aegis-X's most important architectural principles.

---

# 11. Deterministic Policy Engine

The Java Policy Engine is the **only** component allowed to make compliance decisions.

### Example Logic

```java
if (!invoicePresent) return HOLD;
if (!beneficiaryMatch) return HOLD;
if (!requiredFieldsValid) return HOLD;
if (!complianceRulesPassed) return HOLD;

return APPROVED;
```

## Decision Matrix

| Condition | Result |
|-----------|--------|
| Missing invoice | HOLD |
| Beneficiary mismatch | HOLD |
| Invalid required field | HOLD |
| Compliance rule failed | HOLD |
| All rules pass | APPROVED |

The AI provides evidence.

**Java makes the decision.**

---

# 12. Success Metrics

The MVP is measured using observable outcomes.

| Metric | Target |
|---------|---------|
| Payment webhook received | Success |
| Transaction created | Success |
| Invoice uploaded | Success |
| OCR extraction | Structured text |
| AI analysis | Structured JSON |
| Policy evaluation | APPROVED/HOLD |
| Audit logging | Every event recorded |
| Dashboard updates | Real-time |

---

# 13. Technology Stack

| Layer | Technology |
|-------|------------|
| Frontend | HTML5, CSS3, Bootstrap 5, JavaScript, Thymeleaf |
| Backend | Java 21, Spring Boot 3, Spring Web, Spring Security, Spring Data JPA, Hibernate |
| Database | PostgreSQL |
| AI | Spring AI, LLM, Apache Tika, Tesseract OCR |
| Payments | Razorpay Sandbox, Razorpay Webhooks |
| Messaging | WhatsApp Business API |
| Security | JWT, BCrypt, Bean Validation |
| Testing | JUnit 5, Mockito, Testcontainers, Postman |
| Deployment | Docker, Docker Compose, Railway |

---

# 14. Acceptance Criteria

Phase 01 is complete when:

- [x] Business problem is documented.
- [x] MVP scope is defined.
- [x] User personas are finalized.
- [x] Transaction lifecycle is defined.
- [x] AI responsibilities are separated from business rules.
- [x] Policy engine responsibilities are documented.
- [x] Technology stack is finalized.
- [x] Success metrics are established.
- [x] Development roadmap is ready for implementation.

---

# Phase 01 Outcome

This document establishes the **Software Requirements Specification (SRS)** for Aegis-X. It defines the business problem, system boundaries, user roles, compliance model, AI responsibilities, and MVP objectives.

All subsequent implementation phases should reference this document to ensure that AI remains an evidence-analysis component while deterministic Java code remains the final authority for compliance decisions and transaction orchestration.
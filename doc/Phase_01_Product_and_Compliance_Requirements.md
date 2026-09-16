# Aegis-X --- Phase 01: Product & Compliance Requirements

> **Objective:** Define the business problem, users, system scope,
> compliance boundaries, success metrics, and technology foundation
> before implementation begins.

------------------------------------------------------------------------

# 1. Problem Statement

## Existing Problem

Cross-border payments often face delays not because the payment network
fails, but because compliance activities happen across disconnected
systems.

``` text
Payment Created
      ↓
Manual Email
      ↓
Invoice Requested
      ↓
Shipping Proof Requested
      ↓
Compliance Review
      ↓
Payment Released
```

### Current Challenges

-   Missing documents delay settlement.
-   Compliance officers manually follow up with merchants.
-   Transaction status is scattered across multiple systems.
-   Audit trails become fragmented.
-   AI models cannot be trusted to independently authorize financial
    transactions.

## Proposed Solution

**Aegis-X** acts as an intelligent compliance orchestration layer
between payment authorization and settlement.

``` text
Payment Event
      ↓
Detect Missing Evidence
      ↓
Request Documents
      ↓
OCR + AI Analysis
      ↓
Deterministic Policy Engine
      ↓
PASS / HOLD
      ↓
Payment Capture / Human Review
```

The AI interprets evidence, while deterministic Java rules control
financial actions.

------------------------------------------------------------------------

# 2. Business Scenario

> **ABC Exports (India)** needs to pay **XYZ Imports LLC (USA)** **USD
> 2,000** for exported goods.

### Transaction Flow

1.  Merchant creates a payment.
2.  Stripe Sandbox authorizes the payment.
3.  Aegis-X detects that the commercial invoice is missing.
4.  The Evidence Collector sends a WhatsApp message requesting the
    invoice.
5.  The merchant uploads the invoice PDF.
6.  OCR extracts the document contents.
7.  Spring AI analyzes the extracted information.
8.  The Policy Engine verifies beneficiary, amount, purpose code, and
    required evidence.
9.  If every rule passes, Stripe captures the payment.
10. Otherwise, the transaction enters **COMPLIANCE_HOLD** for human
    review.

------------------------------------------------------------------------

# 3. User Personas

## Merchant

**Role:** Business initiating cross-border payments.

Responsibilities: - Create payments - Upload invoices - Submit
supporting documents - Track transaction status

Pain Points: - Doesn't know which documents are missing. - Waits for
manual compliance responses. - Has limited visibility into payment
progress.

## Compliance Officer

**Role:** Reviews transactions requiring manual intervention.

Responsibilities: - Review HOLD transactions - Verify compliance
evidence - Approve or reject exceptions - Monitor audit history

Pain Points: - Manual document chasing - Fragmented transaction
information - Repetitive verification tasks

## Administrator

**Role:** Maintains the Aegis-X platform.

Responsibilities: - Manage users - Configure integrations - Maintain
reference data - Monitor system health

Pain Points: - Needs centralized visibility - Requires reliable audit
logs - Must quickly identify failures.

------------------------------------------------------------------------

# 4. Project Scope

## Included

-   Stripe Sandbox payment authorization
-   Transaction lifecycle management
-   Document upload
-   OCR document extraction
-   AI-based evidence analysis
-   Purpose-code validation
-   Deterministic PASS/HOLD decisions
-   WhatsApp notifications
-   Audit logging
-   Web dashboard

## Not Included

-   Real FEMA approval
-   Live bank integration
-   SWIFT settlement
-   Production KYC onboarding
-   Real AML screening

------------------------------------------------------------------------

# 5. Functional Requirements

  ID      Requirement
  ------- ------------------------------
  FR-01   User login
  FR-02   JWT authentication
  FR-03   Create payment
  FR-04   Create transaction
  FR-05   Stripe payment authorization
  FR-06   Upload invoice
  FR-07   Track required documents
  FR-08   OCR document extraction
  FR-09   AI compliance analysis
  FR-10   Policy evaluation
  FR-11   Capture payment after PASS
  FR-12   Hold transaction
  FR-13   Human review
  FR-14   Audit logging
  FR-15   WhatsApp notifications

------------------------------------------------------------------------

# 6. Non-Functional Requirements

  Category          Requirement
  ----------------- -----------------------------------------------------
  Security          JWT authentication
  Performance       API response under 500 ms (excluding AI processing)
  Reliability       Every state transition logged
  Maintainability   Modular Spring Boot architecture
  Scalability       Event-driven integrations
  Auditability      Immutable audit trail

------------------------------------------------------------------------

# 7. Transaction Lifecycle

``` text
CREATED
   ↓
AUTHORIZED
   ↓
EVIDENCE_PENDING
   ↓
OCR_PROCESSING
   ↓
AI_ANALYSIS
   ↓
POLICY_CHECK
   ├── PASS → PAYMENT_CAPTURED
   └── HOLD → HUMAN_REVIEW
```

------------------------------------------------------------------------

# 8. Compliance Model

## Required Evidence

  Evidence              Mandatory
  --------------------- -------------
  Commercial Invoice    Yes
  Beneficiary Details   Yes
  Purpose Code          Yes
  Shipping Proof        Conditional

## Compliance Checks

  Check                         Output
  ----------------------------- ---------
  Invoice exists                Boolean
  Beneficiary matches           Boolean
  Amount matches                Boolean
  Purpose code valid            Boolean
  OCR confidence acceptable     Boolean
  Required documents complete   Boolean

------------------------------------------------------------------------

# 9. AI Boundary

## AI Responsibilities

-   Extract invoice fields
-   Identify beneficiary names
-   Compare transaction values
-   Generate structured JSON
-   Explain inconsistencies

Example:

``` json
{
  "beneficiaryMatch": true,
  "amountMatch": true,
  "invoiceNumber": "INV-2048",
  "confidence": 0.98
}
```

## AI Restrictions

-   Capture payments
-   Override compliance rules
-   Change transaction states
-   Ignore required documents

------------------------------------------------------------------------

# 10. Deterministic Policy Engine

``` java
if (!invoicePresent) return HOLD;
if (!beneficiaryMatch) return HOLD;
if (!purposeCodeValid) return HOLD;
if (!amountMatch) return HOLD;

return PASS;
```

### Decision Matrix

  Condition              Result
  ---------------------- --------
  Invoice missing        HOLD
  Beneficiary mismatch   HOLD
  Invalid purpose code   HOLD
  Amount mismatch        HOLD
  All checks pass        PASS

------------------------------------------------------------------------

# 11. WhatsApp Evidence Workflow

``` text
Payment Authorized
      ↓
Missing Invoice Detected
      ↓
WhatsApp Notification
      ↓
Merchant Uploads PDF
      ↓
OCR Processing
      ↓
AI Analysis
      ↓
Policy Re-Evaluation
```

------------------------------------------------------------------------

# 12. Success Metrics

  Metric                       Target
  ---------------------------- ------------------------------------
  Payment authorization        Successful in Stripe Sandbox
  Missing-document detection   Automatic
  OCR extraction               Structured document data generated
  AI analysis                  Structured compliance JSON
  Policy evaluation            Deterministic PASS/HOLD
  Payment capture              Only after PASS
  Audit coverage               Every state transition recorded
  Dashboard visibility         Real-time transaction tracking

------------------------------------------------------------------------

# 13. MVP Success Criteria

-   Merchant logs in.
-   Creates a payment.
-   Stripe authorizes the payment.
-   Transaction enters `EVIDENCE_PENDING`.
-   WhatsApp requests the missing invoice.
-   Merchant uploads a PDF.
-   OCR extracts document text.
-   Spring AI generates structured analysis.
-   Policy Engine evaluates PASS/HOLD.
-   PASS captures the payment.
-   HOLD triggers human review.
-   Every event appears in the audit timeline.

------------------------------------------------------------------------

# 14. Final Technology Stack

## Frontend

-   HTML5
-   CSS3
-   Bootstrap 5
-   JavaScript
-   Thymeleaf

## Backend

-   Java 21
-   Spring Boot 3
-   Spring Security
-   Spring Data JPA
-   Hibernate

## Database

-   MySQL 8

## AI

-   Spring AI
-   LLM
-   Apache Tika
-   Tesseract OCR

## Integrations

-   Stripe Sandbox
-   WhatsApp Business API

## Testing

-   JUnit 5
-   Mockito
-   Testcontainers
-   Postman

## Deployment

-   Docker

------------------------------------------------------------------------

# Phase 01 Deliverables

-   Problem Statement
-   Business Scenario
-   User Personas
-   Project Scope
-   Functional Requirements
-   Non-Functional Requirements
-   Transaction Lifecycle
-   Compliance Model
-   AI Boundaries
-   Deterministic Policy Rules
-   Success Metrics
-   MVP Definition
-   Final Technology Stack

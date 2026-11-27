# Aegis-X module and navigation inventory

Reviewed the frontend page entries and shared components, backend controllers/services and route guards, Spring Security configuration, and JPA entities. The inventory below records implemented workflows only; it does not infer product areas from names or create menu links for API infrastructure.

## User-facing modules

| Module | Frontend pages | Backend capabilities and data | Roles |
|---|---|---|---|
| Merchant Console | `dashboard.html`; contextual `transaction.html` | Transaction create/list/detail/search and local payment confirmation; document upload and OCR; evidence checklist; deterministic policy evaluation and violations; AI analysis; notification history; audit activity. Data includes Transaction, Document, DocumentExtraction, RequiredDocument, EvidenceChecklist, PolicyEvaluation, PolicyViolation, AIAnalysis, NotificationMessage, AuditEvent. | Merchant |
| Compliance Queue | `compliance.html`; contextual `transaction.html` | Paginated review queue, review decisions and history, notes, review statistics; transaction evidence and policy context. Data includes ComplianceReview, ManualReviewNote, and shared transaction/evidence/policy entities. | Compliance Officer, Admin |
| Settlement Desk | `settlement.html` | Settlement status, processor configuration, retries, cancellation, overdue/manual-intervention views and reconciliation. Data includes Settlement and PaymentProcessor. | Settlement Operator, Compliance Officer, Admin (page visibility); endpoint actions have narrower backend permissions |
| Operations & Health | `operations.html` | Actuator health and metrics plus backend performance observations. | Admin |
| Payment Integrity | `integrity.html`, `integrity-transactions.html`, `integrity-webhooks.html`, `integrity-reconciliation.html`, `integrity-evidence.html`, `integrity-exceptions.html`, `integrity-recovery.html`, `integrity-audit.html`, `integrity-settings.html` | Workspace dashboard/configuration; payment orders and records; provider/webhook events; reconciliation runs/findings; evidence documents, extraction and readiness; exception cases and review actions; recovery recommendations; audit trail; benchmark controls/method. Data includes Workspace/Membership, PaymentOrder/PaymentRecord/RefundRecord/TransferRecord, ProviderObservation, WebhookDelivery/IntegrityWebhookEvent, ReconciliationRun/Finding, EvidenceDocument/Extraction/Readiness, ExceptionCase, RecoveryRecommendation, ReviewAction, StateTransitionRecord, IntegrityAuditEvent. | Admin, Compliance Officer, Settlement Operator (some write operations are further restricted by backend method security) |

## Role navigation matrix

| Role | Visible modules |
|---|---|
| Merchant | Merchant Console |
| Compliance Officer | Compliance Queue, Settlement Desk, Payment Integrity |
| Settlement Operator | Settlement Desk, Payment Integrity |
| Admin | Compliance Queue, Settlement Desk, Operations & Health, Payment Integrity |

This matrix matches the frontend route allow-list and the page-level backend role annotations reviewed. Endpoint-level actions remain independently protected by backend authorization. Transaction details are contextual links from the Merchant Console or Compliance Queue, not a duplicate standalone module. Payment Integrity overview is listed once as the parent of the integrity workspace pages.

## Shared/public and cross-cutting pages

`index.html`, `login.html`, and `register.html` are public entry/authentication flows. `not-permitted.html`, `404.html`, and `error.html` are route/error outcomes, not modules. The shared application shell provides role-filtered navigation, account details, and sign-out for authenticated module pages.

## Backend features without a separate sidebar destination

- Auth, user and role lookup, JWT validation, session-independent guards, and health checks support the whole application.
- Merchant transaction support endpoints cover search, local payment confirmation, evidence/document processing, OCR, policy evaluation, AI advisory, notifications, and audit events; these are surfaced from the Merchant Console and transaction detail workflow.
- Review notes and compliance review APIs support Compliance Queue and transaction detail.
- Integrity webhook receivers, Razorpay order/webhook integration, and WhatsApp webhook ingestion are integration endpoints, not human navigation pages.
- The integrity benchmark API is surfaced under Payment Integrity settings; it is not a separate module.
- `EvidenceController` checklist orchestration and `PolicyViolationController` analytics are backend APIs without a separate page. Their functions are represented in the existing merchant/compliance flows where wired; the APIs do not justify additional sidebar links.
- Actuator/health and performance endpoints feed Operations & Health and shared status handling.

## Route and access checks

- Every sidebar destination maps to an existing HTML entry and has a frontend protected-role definition.
- Role filtering is derived from the same route inventory; restricted URLs are also checked by the frontend route guard, while API authorization is enforced by Spring Security and method-level role checks.
- Integrity subsections are a single expandable group. Current-page state follows the exact route; contextual transaction pages activate their role-appropriate parent.
- Unknown routes resolve to the 404 outcome. Public authentication and error pages do not receive the authenticated module shell.

## Discovery boundary

The project contains backend APIs with no standalone frontend route, as listed above. They are intentionally not promoted into sidebar links without an existing user-facing workflow. Data model names and webhook endpoints are supporting implementation details, not evidence of additional user-facing modules.

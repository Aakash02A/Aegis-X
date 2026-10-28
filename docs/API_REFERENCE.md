# Aegis-X REST API Reference

## 1. General API Conventions

* **Base URL:** `http://localhost:8080` (or your deployed API host)
* **Content Type:** `application/json` (except multipart file uploads)
* **Authentication:** HTTP Authorization Header:
  ```http
  Authorization: Bearer <jwt-token>
  ```
* **Request Correlation:** Pass an optional `X-Correlation-ID` header to correlate logs across requests; the backend returns the correlation ID in the response headers and audit trails.

### Standard Response Envelope

All API endpoints return standard enveloped payloads:

```json
{
  "success": true,
  "message": "Operation completed successfully",
  "data": { ... },
  "timestamp": "2026-10-28T11:20:00.000Z"
}
```

Error response:
```json
{
  "success": false,
  "message": "Validation failed: amount must be greater than zero",
  "error": "BAD_REQUEST",
  "timestamp": "2026-10-28T11:20:00.000Z"
}
```

---

## 2. Authentication Endpoints

### 2.1 Register User
* **Endpoint:** `POST /api/auth/register`
* **Access:** Public
* **Request Body:**
```json
{
  "email": "merchant@example.com",
  "password": "SecurePassword123!",
  "fullName": "Acme Exports Ltd",
  "phone": "+919876543210",
  "role": "MERCHANT"
}
```
* **Allowed Roles:** `MERCHANT`, `COMPLIANCE_OFFICER`, `SETTLEMENT_OPERATOR`, `ADMIN`
* **Response (201 Created):**
```json
{
  "success": true,
  "data": {
    "id": 1,
    "email": "merchant@example.com",
    "fullName": "Acme Exports Ltd",
    "role": "MERCHANT"
  }
}
```

### 2.2 Authenticate / Login
* **Endpoint:** `POST /api/auth/login`
* **Access:** Public
* **Request Body:**
```json
{
  "email": "merchant@example.com",
  "password": "SecurePassword123!"
}
```
* **Response (200 OK):**
```json
{
  "success": true,
  "data": {
    "token": "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...",
    "user": {
      "id": 1,
      "email": "merchant@example.com",
      "fullName": "Acme Exports Ltd",
      "role": "MERCHANT"
    }
  }
}
```

### 2.3 Current User Info
* **Endpoint:** `GET /api/auth/me`
* **Access:** Authenticated (Any role)

---

## 3. Transaction Endpoints

### 3.1 Create Transaction
* **Endpoint:** `POST /api/transactions`
* **Access:** `MERCHANT`, `ADMIN`
* **Request Body:**
```json
{
  "amount": 250000.00,
  "currency": "INR",
  "beneficiaryName": "Global Steel Industries Ltd",
  "purposeCode": "P0102",
  "originCountry": "IN",
  "destinationCountry": "AE",
  "invoiceNumber": "INV-2026-0891"
}
```
* **Response (201 Created):** Returns created transaction with state `CREATED`.

### 3.2 List Transactions
* **Endpoint:** `GET /api/transactions?page=0&size=20&status=UNDER_REVIEW`
* **Access:** Authenticated (filtered by merchant workspace or full queue for officers)

### 3.3 Get Transaction Details & Audit Trail
* **Endpoint:** `GET /api/transactions/{id}`
* **Endpoint:** `GET /api/transactions/{id}/audit`
* **Access:** Authenticated

---

## 4. Evidence & OCR Endpoints

### 4.1 Upload Commercial Document
* **Endpoint:** `POST /api/documents/upload`
* **Access:** `MERCHANT`, `ADMIN`
* **Content-Type:** `multipart/form-data`
* **Form Fields:**
  * `file`: Binary file (PDF, PNG, JPEG, TIFF)
  * `transactionId`: Long (Transaction ID)
  * `documentType`: String (`INVOICE`, `BILL_OF_LADING`, `TAX_CERTIFICATE`, `PACKING_LIST`)
* **Response (200 OK):** Returns document entity with processing state (`PROCESSED` or `PENDING_OCR`).

### 4.2 Document OCR & Extraction Results
* **Endpoint:** `GET /api/documents/{id}/extractions`
* **Access:** Authenticated
* **Response Payload:** Extracted line items, tax numbers, counterparty names, currency, and AI confidence scores.

---

## 5. Policy Engine & Compliance Review

### 5.1 Get Compliance Review Queue
* **Endpoint:** `GET /api/review/queue`
* **Access:** `COMPLIANCE_OFFICER`, `ADMIN`
* **Response:** Returns list of transactions currently in `HELD` or `UNDER_REVIEW` requiring officer determination.

### 5.2 Submit Compliance Decision
* **Endpoint:** `POST /api/review/{transactionId}/decision`
* **Access:** `COMPLIANCE_OFFICER`, `ADMIN`
* **Request Body:**
```json
{
  "decision": "APPROVE",
  "justification": "Verified shipping documents and tax invoice match customs declarations."
}
```
* **Allowed Decisions:** `APPROVE`, `REJECT`, `REQUEST_MORE_INFO`
* **Constraint:** `justification` is mandatory and cannot be empty.

---

## 6. Settlement Endpoints

### 6.1 List Settlements
* **Endpoint:** `GET /api/settlement`
* **Access:** `SETTLEMENT_OPERATOR`, `ADMIN`

### 6.2 Retry Failed Settlement
* **Endpoint:** `POST /api/settlement/{id}/retry`
* **Access:** `SETTLEMENT_OPERATOR`, `ADMIN`

### 6.3 Settlement Reconciliation Report
* **Endpoint:** `GET /api/settlement/reconciliation`
* **Access:** `SETTLEMENT_OPERATOR`, `ADMIN`

---

## 7. Webhook Ingestion

### 7.1 Razorpay Payment Webhook
* **Endpoint:** `POST /api/webhooks/razorpay`
* **Access:** Public (HMAC Verified)
* **Required Header:** `X-Razorpay-Signature`
* **Verification:** HMAC-SHA256 signature calculated over the exact raw HTTP request body against `RAZORPAY_WEBHOOK_SECRET`.
* **Behavior:** Idempotent. Duplicate deliveries return `200 OK` without re-processing state.

### 7.2 WhatsApp Cloud Webhook
* **Endpoint:** `GET /api/webhooks/whatsapp` (Meta verification challenge)
  * **Query Params:** `hub.mode=subscribe`, `hub.challenge=...`, `hub.verify_token=...`
* **Endpoint:** `POST /api/webhooks/whatsapp` (Inbound message events)
  * **Required Header:** `X-Hub-Signature-256`

---

## 8. Payment Integrity Subsystem (`/integrity/**`)

### 8.1 Reconcile Workspace Ledger
* **Endpoint:** `POST /integrity/workspaces/{workspaceId}/reconcile`
* **Access:** `SETTLEMENT_OPERATOR`, `ADMIN`
* **Response:** Compares bank statements/provider capture records against internal ledger accounts.

### 8.2 Get Exceptions Queue
* **Endpoint:** `GET /integrity/workspaces/{workspaceId}/exceptions`
* **Access:** `COMPLIANCE_OFFICER`, `SETTLEMENT_OPERATOR`, `ADMIN`
* **Response:** Returns active settlement discrepancies, mismatched amounts, and unresolved payment disputes.

---

## 9. Observability & Health

* **Liveness Probe:** `GET /actuator/health/liveness` &rarr; `{"status": "UP"}`
* **Readiness Probe:** `GET /actuator/health/readiness` &rarr; `{"status": "UP"}`
* **Prometheus Metrics:** `GET /actuator/prometheus` (scraping endpoint for Grafana)
* **System Metrics:** `GET /actuator/metrics`


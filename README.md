# Aegis-X

> **AI-Assisted Cross-Border Payment Compliance & Settlement Orchestration**

Aegis-X is an intelligent compliance orchestration system positioned between payment receipt and settlement in cross-border payment workflows. It automates evidence collection, extracts structured document entities via OCR and AI, and enforces deterministic compliance decisions through a pure Java Policy Engine before authorizing settlement.

---

## Authoritative Documentation

- **Software Requirements Specification (SRS):** [doc/SRS_Aegis_X.md](doc/SRS_Aegis_X.md) (v2.0)
- **Technical Architecture Specification (TAS):** [doc/TAS_Aegis_X.md](doc/TAS_Aegis_X.md) (v1.0)

---

## Core Architectural Principle

> **AI is an Advisor. Java is the Sole Compliance Authority.**
>
> - **AI (Spring AI):** Reads, extracts, and scores document evidence; produces advisory JSON. Never approves, holds, rejects, or settles payments.
> - **Java (Policy Engine):** Evaluates deterministic compliance rules (`PR-01` to `PR-08`); makes the final `APPROVED` or `COMPLIANCE_HOLD` decision.

---

## Prerequisites

Ensure you have the following installed locally:

- **Java:** JDK 21 LTS (e.g., Eclipse Temurin or Oracle OpenJDK 21)
- **Maven:** 3.9+ (or use the included `./mvnw` wrapper)
- **Docker & Docker Compose:** Docker Desktop 24+ with Compose v2

---

## Project Structure

```text
backend/
└── src/main/java/com/aegisx/
    ├── auth/              # JWT & Security module
    ├── payment/           # Razorpay webhook ingestion & payment handling
    ├── transaction/       # Transaction lifecycle & State Machine
    ├── document/          # Evidence upload, storage, and OCR processing
    ├── ai/                # Spring AI Regulatory Reasoner
    ├── policy/            # Deterministic Java Policy Engine
    ├── compliance/        # Human compliance review & override
    ├── audit/             # Immutable append-only audit event logging
    ├── integration/       # External service gateways (Razorpay, WhatsApp)
    ├── notification/      # WhatsApp and alert dispatches
    ├── common/            # Shared configs, DTOs, exceptions, and utilities
    │   ├── config/        # AppConfig, WebConfig, SecurityConfig
    │   ├── dto/           # Common data transfer objects
    │   ├── exception/     # Domain exceptions and global handlers
    │   └── util/          # Helper utilities
    ├── controller/        # REST controllers (HealthController, etc.)
    └── AegisXApplication.java
```

---

## Local Setup

### 1. Environment Configuration

Copy the sample environment variables:

```bash
cp .env.example .env
```

Default configuration in `.env`:

```env
DB_URL=jdbc:postgresql://localhost:5432/aegisx
DB_USERNAME=postgres
DB_PASSWORD=postgres
JWT_SECRET=404E635266556A586E3272357538782F413F4428472B4B6250645367566B5970
JWT_EXPIRATION=86400000
```

| Variable | Description | Default / Example |
| :--- | :--- | :--- |
| `DB_URL` | PostgreSQL JDBC connection URL | `jdbc:postgresql://localhost:5432/aegisx` |
| `DB_USERNAME` | PostgreSQL database user | `postgres` |
| `DB_PASSWORD` | PostgreSQL database password | `postgres` |
| `JWT_SECRET` | HMAC-SHA256 Secret (256-bit Hex/Base64) | *Configured via env variable* |
| `JWT_EXPIRATION` | Token TTL in milliseconds | `86400000` (24 hours) |

---

### 2. Start PostgreSQL via Docker Compose

Run the PostgreSQL database service in the background:

```bash
docker compose up -d postgres
```

Or start the entire stack (PostgreSQL + Spring Boot application):

```bash
docker compose up -d
```

---

### 3. Run the Spring Boot Application Locally

Using Maven from the `backend/` directory:

```bash
cd backend
./mvnw spring-boot:run
```

On Windows PowerShell:

```powershell
cd backend
.\mvnw.cmd spring-boot:run
```

---

## Authentication & User Management (Phase 04)

Aegis-X implements stateless JWT authentication with role-based access control (`MERCHANT`, `COMPLIANCE_OFFICER`, `ADMIN`).

### 1. Register a New User

```bash
curl -i -X POST http://localhost:8080/api/auth/register \
  -H "Content-Type: application/json" \
  -d '{
    "fullName": "ABC Exports",
    "email": "merchant@example.com",
    "password": "Password@123",
    "role": "MERCHANT"
  }'
```

**Expected Response (`201 Created`):**
```json
{
  "id": "c3b9b4f2-9599-4c81-b586-4e5bfaef1e97",
  "fullName": "ABC Exports",
  "email": "merchant@example.com",
  "role": "MERCHANT",
  "createdAt": "2026-09-21T07:15:00",
  "updatedAt": "2026-09-21T07:15:00"
}
```

---

### 2. Authenticate (Login)

```bash
curl -i -X POST http://localhost:8080/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{
    "email": "merchant@example.com",
    "password": "Password@123"
  }'
```

**Expected Response (`200 OK`):**
```json
{
  "token": "eyJhbGciOiJIUzI1NiJ9...",
  "tokenType": "Bearer",
  "expiresIn": 86400
}
```

---

### 3. Fetch Current User Profile (`/api/auth/me`)

```bash
curl -i -X GET http://localhost:8080/api/auth/me \
  -H "Authorization: Bearer <YOUR_JWT_TOKEN>"
```

**Expected Response (`200 OK`):**
```json
{
  "id": "c3b9b4f2-9599-4c81-b586-4e5bfaef1e97",
  "fullName": "ABC Exports",
  "email": "merchant@example.com",
  "role": "MERCHANT",
  "createdAt": "2026-09-21T07:15:00",
  "updatedAt": "2026-09-21T07:15:00"
}
```

---

## Transaction Management (Phase 05)

The Transaction module serves as the central stateful orchestrator for Aegis-X. Status transitions are strictly guarded by `TransactionStateMachine` and protected with JPA optimistic locking (`@Version`).

### Transaction State Machine Lifecycle

```text
  [CREATED]
      │ (Razorpay Sandbox Webhook)
      ▼
  [PAYMENT_RECEIVED]
      │ (Awaiting Merchant Invoice)
      ▼
  [EVIDENCE_PENDING]
      │ (Document Upload & Tesseract/Tika Ingestion)
      ▼
  [OCR_PROCESSING]
      │ (Structured Text Extraction)
      ▼
  [AI_ANALYSIS]
      │ (Spring AI Regulatory Reasoning)
      ▼
  [POLICY_EVALUATION]
      ├──▶ [APPROVED]        (Deterministic Java Policy Engine passes)
      └──▶ [COMPLIANCE_HOLD] (Deterministic compliance rules flagged)
```

| State | Legal Next State(s) | Trigger / Authority |
| :--- | :--- | :--- |
| `CREATED` | `PAYMENT_RECEIVED` | Razorpay payment webhook ingestion |
| `PAYMENT_RECEIVED` | `EVIDENCE_PENDING` | Payment acknowledgement & document requirement |
| `EVIDENCE_PENDING` | `OCR_PROCESSING` | Merchant invoice upload |
| `OCR_PROCESSING` | `AI_ANALYSIS` | Apache Tika & Tesseract text extraction |
| `AI_ANALYSIS` | `POLICY_EVALUATION` | Spring AI structured compliance reasoning |
| `POLICY_EVALUATION` | `APPROVED`, `COMPLIANCE_HOLD` | Java Policy Engine (Rules `PR-01` to `PR-08`) |
| `APPROVED` | *(Terminal)* | Settlement authorized |
| `COMPLIANCE_HOLD` | *(Terminal)* | Compliance officer review required |

---

### 1. Create a Transaction

```bash
curl -i -X POST http://localhost:8080/api/transactions \
  -H "Authorization: Bearer <MERCHANT_JWT_TOKEN>" \
  -H "Content-Type: application/json" \
  -d '{
    "amount": 5000.00,
    "currency": "USD",
    "beneficiaryName": "Pacific Coast Trading LLC",
    "purposeCode": "P0103"
  }'
```

**Expected Response (`201 Created`):**
```json
{
  "id": "e8a93cb1-923c-42b7-8db1-7b79dae13b82",
  "referenceNumber": "TXN-20260921-8F3A7C",
  "merchantId": "c3b9b4f2-9599-4c81-b586-4e5bfaef1e97",
  "amount": 5000.00,
  "currency": "USD",
  "beneficiaryName": "Pacific Coast Trading LLC",
  "purposeCode": "P0103",
  "status": "CREATED",
  "createdAt": "2026-09-21T20:30:00Z",
  "updatedAt": "2026-09-21T20:30:00Z"
}
```

---

### 2. List Merchant Transactions

```bash
curl -i -X GET "http://localhost:8080/api/transactions?page=0&size=10" \
  -H "Authorization: Bearer <MERCHANT_JWT_TOKEN>"
```

**Expected Response (`200 OK`):**
```json
{
  "content": [
    {
      "id": "e8a93cb1-923c-42b7-8db1-7b79dae13b82",
      "referenceNumber": "TXN-20260921-8F3A7C",
      "merchantId": "c3b9b4f2-9599-4c81-b586-4e5bfaef1e97",
      "amount": 5000.00,
      "currency": "USD",
      "beneficiaryName": "Pacific Coast Trading LLC",
      "purposeCode": "P0103",
      "status": "CREATED",
      "createdAt": "2026-09-21T20:30:00Z",
      "updatedAt": "2026-09-21T20:30:00Z"
    }
  ],
  "pageable": {
    "pageNumber": 0,
    "pageSize": 10
  },
  "totalElements": 1,
  "totalPages": 1
}
```

---

### 3. Get Single Transaction by ID

```bash
curl -i -X GET http://localhost:8080/api/transactions/e8a93cb1-923c-42b7-8db1-7b79dae13b82 \
  -H "Authorization: Bearer <MERCHANT_JWT_TOKEN>"
```

**Expected Response (`200 OK`):**
```json
{
  "id": "e8a93cb1-923c-42b7-8db1-7b79dae13b82",
  "referenceNumber": "TXN-20260921-8F3A7C",
  "merchantId": "c3b9b4f2-9599-4c81-b586-4e5bfaef1e97",
  "amount": 5000.00,
  "currency": "USD",
  "beneficiaryName": "Pacific Coast Trading LLC",
  "purposeCode": "P0103",
  "status": "CREATED",
  "createdAt": "2026-09-21T20:30:00Z",
  "updatedAt": "2026-09-21T20:30:00Z"
}
```

---

## Verification & Health Probes

### Health & Readiness Check Endpoints

1. **Application Health (`/api/health`):**
   ```bash
   curl -X GET http://localhost:8080/api/health
   # Expected Response: Aegis-X Backend Running
   ```

2. **Spring Boot Actuator Health (`/actuator/health`):**
   ```bash
   curl -X GET http://localhost:8080/actuator/health
   # Expected Response: {"status":"UP"}
   ```

3. **Railway / Container Probes (`/actuator/health/liveness`, `/actuator/health/readiness`):**
   ```bash
   curl -X GET http://localhost:8080/actuator/health/liveness
   curl -X GET http://localhost:8080/actuator/health/readiness
   # Expected Response: {"status":"UP"}
   ```

---

## Build & Test

To execute automated unit, repository, concurrency, and integration tests:

```bash
cd backend
./mvnw clean test
```

To build a production-ready runnable JAR:

```bash
cd backend
./mvnw clean package
```

The resulting JAR will be generated at `backend/target/aegisx-0.0.1-SNAPSHOT.jar`.



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
```

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

## Verification

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

To execute automated unit and integration tests:

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

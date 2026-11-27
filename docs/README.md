# Aegis-X Documentation Hub

Welcome to the official documentation for **Aegis-X**, an enterprise-grade AI-assisted compliance and settlement orchestration platform for cross-border financial transactions.

Aegis-X enforces deterministic compliance rules, real-time Razorpay payment verification, double-entry payment ledgering, automated document OCR/extraction, and WhatsApp notification alerting, operating under the golden architectural principle:

> **"AI is an Advisor. Java is the Sole Compliance Authority."**  
> AI extracts structured insights and computes match confidence metrics, but only deterministic Java policy rules may transition transactions, assign compliance holds, or approve settlements.

---

## 📚 Documentation Index

| Document | Description | Target Audience |
| :--- | :--- | :--- |
| **[System Architecture](SYSTEM_ARCHITECTURE.md)** | Modular monolith layout, transaction state machine, payment integrity bounded context, OCR pipeline, and frontend SPA design. | Architects, Tech Leads, Developers |
| **[API Reference](API_REFERENCE.md)** | Complete REST API endpoint reference, authentication protocols, request/response payloads, and error schemas. | Frontend Developers, API Consumers, Integrators |
| **[Database & Migrations](DATABASE_AND_MIGRATIONS.md)** | MySQL 8.4 schema specification, Flyway migration sequence, ER relationships, UTC timestamp handling, and constraints. | Database Administrators, Backend Engineers |
| **[Deployment & Configuration](DEPLOYMENT_AND_CONFIGURATION.md)** | Environment variables, Docker Compose orchestration, production secrets, external integrations (Razorpay, Meta WhatsApp, OpenAI), and operational health. | DevOps, SRE, Systems Administrators |
| **[Razorpay Production Setup](razorpay-production-setup.md)** | Razorpay Test/Live Checkout and RazorpayX India payout setup and webhook configuration. | Integrators, DevOps |
| **[Staging Deployment Checklist](STAGING_DEPLOYMENT_CHECKLIST.md)** | Pre-deployment checks, security gates, and staging verification steps. | DevOps, Release Managers |
| **[Module Inventory](MODULE_INVENTORY.md)** | Frontend pages, backend capabilities, and role navigation matrix. | Product, QA, Frontend Engineers |
| **[Software Requirements Specification (SRS)](SRS_Aegis_X.md)** | Baseline functional and non-functional requirements, business rules, regulatory standards, and transaction lifecycle definitions. | Product Managers, QA, Compliance Officers |
| **[Technical Architecture Specification (TAS)](TAS_Aegis_X.md)** | Architectural principles, component diagrams, sequence flows, and design decisions. | Systems Architects, Core Developers |

---

## ⚡ Technology Stack at a Glance

* **Backend Framework:** Spring Boot 3.3.4 &bull; Java 21 LTS
* **Database Engine:** MySQL 8.4 LTS (`utf8mb4_0900_ai_ci`, session UTC, `READ-COMMITTED`)
* **Database Migrations:** Flyway (`V1` baseline through `V34`)
* **Security & Auth:** Spring Security 6 with stateless JWT (HMAC-SHA256) & BCrypt password hashing
* **Document Processing:** Apache Tika 2.9 (text extraction), Apache PDFBox 3.0 (rasterisation), Tesseract OCR fallback
* **AI Integration:** Spring AI with OpenAI GPT-4o-mini (non-blocking, advisory with startup safety fallbacks)
* **Integrations:** Razorpay Webhooks (HMAC-SHA256 verified, duplicate-safe idempotency), WhatsApp Cloud API
* **Frontend Client:** Vite 6 SPA &bull; Modern HTML5 / CSS Design Tokens &bull; Modular Vanilla JS &bull; 100% Self-Hosted Webfonts & Font Awesome Icons
* **Observability:** Spring Boot Actuator (`/actuator/health`, `/actuator/metrics`, `/actuator/prometheus`)


# Aegis-X Deployment & Configuration Guide

This guide covers local environment setup, Docker Compose orchestration, production configuration, and credential management for external integrations.

---

## 1. Environment Variables Reference

A complete `.env.example` template is provided in the repository root. Create a local `.env` file before launching:

```bash
cp .env.example .env
```

### 1.1 Core Server & Database Settings

| Variable | Default / Example | Purpose |
| :--- | :--- | :--- |
| `SERVER_PORT` | `8080` | Port for the Spring Boot backend REST API. |
| `SPRING_PROFILES_ACTIVE` | `dev` (use `prod` for production) | Active Spring profile. `prod` enforces strict DDL validation (`ddl-auto=validate`). |
| `DB_URL` | `jdbc:mysql://localhost:3308/aegisx_db?...` | Full JDBC connection string with UTC session parameters. |
| `DB_USERNAME` | `root` | Database user account. |
| `DB_PASSWORD` | `rootpassword` | Database password. |
| `CORS_ALLOWED_ORIGINS` | `http://localhost:3000,http://127.0.0.1:3000` | Comma-separated allowed frontend origins. |
| `APP_STORAGE_ROOT_PATH` | `./storage` | Directory where uploaded commercial invoices and PDFs are stored. |

### 1.2 Security & Authentication

| Variable | Description |
| :--- | :--- |
| `JWT_SECRET` | Mandatory base64-encoded secret key (minimum 256-bit / 32 bytes; 48 bytes recommended). |
| `JWT_EXPIRATION_MS` | Token lifespan in milliseconds (default: `86400000` = 24 hours). |

> [!IMPORTANT]
> To generate a secure production JWT secret, execute in bash or terminal:
> ```bash
> openssl rand -base64 48
> ```

---

## 2. External Provider Integrations

### 2.1 Razorpay Payment Ingestion

| Variable | Description |
| :--- | :--- |
| `RAZORPAY_KEY_ID` | Your Razorpay API Key ID (`rzp_test_...` or `rzp_live_...`). |
| `RAZORPAY_KEY_SECRET` | Razorpay API Secret. |
| `RAZORPAY_WEBHOOK_SECRET` | Webhook secret configured in your Razorpay Dashboard used to sign webhook events. |

* **Webhook URL to register in Razorpay:** `https://your-domain.com/api/webhooks/razorpay`
* **Subscribed Events:** `payment.captured`, `payment.failed`, `refund.processed`.
* **Fallback Behavior:** If unconfigured, the backend boots safely with the built-in test-mode adapter reporting *not configured*.

### 2.2 Meta WhatsApp Cloud API

| Variable | Description |
| :--- | :--- |
| `WHATSAPP_PHONE_NUMBER_ID` | Meta Business WhatsApp Phone Number ID. |
| `WHATSAPP_ACCESS_TOKEN` | Permanent System User access token from Meta Business Manager. |
| `WHATSAPP_APP_SECRET` | Meta App Secret used to verify incoming webhook signatures. |
| `WHATSAPP_VERIFY_TOKEN` | Custom verification string matching the webhook configuration in Meta developer portal. |

* **Webhook URL to register in Meta:** `https://your-domain.com/api/webhooks/whatsapp`
* **Subscribed Fields:** `messages`.

### 2.3 OpenAI Regulatory Advisory

| Variable | Description |
| :--- | :--- |
| `OPENAI_API_KEY` | OpenAI API key (`sk-...`). |
| `OPENAI_MODEL` | Model identifier (default: `gpt-4o-mini`). |

* **Graceful Degradation:** If `OPENAI_API_KEY` is empty or omitted, Aegis-X starts cleanly without errors. AI advisory analysis is skipped, and transactions advance directly to the deterministic Java policy engine.

---

## 3. Running Locally

### Option A: Docker Compose (Quickest)

Runs the MySQL 8.4 database and the pre-built backend application in isolated containers:

```bash
# 1. Start containers
docker compose up -d

# 2. Check logs
docker compose logs -f aegisx-app

# 3. Verify health
curl -s http://localhost:8080/actuator/health
```

### Option B: Local Development (IDE or CLI)

1. **Start MySQL:**
   ```bash
   docker compose up -d aegisx-mysql
   ```
2. **Run Backend:**
   ```bash
   cd backend
   ./mvnw spring-boot:run
   ```
3. **Run Frontend (Vite Dev Server):**
   ```bash
   cd frontend
   npm install
   npm run dev
   ```
   Open `http://localhost:3000` in your browser.

---

## 4. Production Deployment Checklist

Before deploying to staging or production:

- [ ] **Enforce `prod` profile:** Ensure `SPRING_PROFILES_ACTIVE=prod` so Flyway validates migrations without modifying tables (`ddl-auto=validate`).
- [ ] **Generate unique secrets:** Replace all default values in `.env` with cryptographically random secrets.
- [ ] **Reverse Proxy / TLS Termination:** Route traffic through Nginx, Caddy, or AWS ALB with HTTP/2 and forced HTTPS.
- [ ] **Persistent Storage Volume:** Mount `APP_STORAGE_ROOT_PATH` on persistent, backed-up storage for uploaded invoice retention.
- [ ] **Database Least Privilege:** Connect using a database user with restricted DML privileges (`SELECT, INSERT, UPDATE, DELETE`) rather than database `root`.
- [ ] **Observability Scraping:** Configure Prometheus to scrape `/actuator/prometheus` and monitor `/actuator/health`.


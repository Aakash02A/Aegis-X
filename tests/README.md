# Aegis-X Testing Suite

This directory contains shared end-to-end (E2E) integration tests and documentation for the complete test pyramid of Aegis-X.

---

## 🏛️ Test Pyramid & Layers

```
                       ┌───────────────────────┐
                       │  35 Real HTTP E2E     │  tests/e2e/run-e2e.sh
                       │  API & Webhook Tests  │  (Isolated MySQL 8.4)
                       └───────────┬───────────┘
                                   │
                     ┌─────────────┴─────────────┐
                     │ 88 Frontend Contract Tests │  frontend/test/frontend-test.js
                     │ (Tokens, RBAC, API Client)│  (Node.js DOM verification)
                     └─────────────┬─────────────┘
                                   │
                     ┌─────────────┴─────────────┐
                     │ 34 MySQL 8.4 Assertions   │  backend/src/test/.../MySql*Test
                     │ (UTC Instants, Idempotency)│  (Spring Boot Test)
                     └─────────────┬─────────────┘
                                   │
                     ┌─────────────┴─────────────┐
                     │  404 Backend Unit Tests   │  backend/src/test/
                     │ (State Machine, Policies) │  (Fast H2 memory engine)
                     └───────────────────────────┘
```

---

## 🚀 Running Tests

### 1. Backend Fast Suite (H2)
```bash
cd backend
./mvnw test
```

### 2. MySQL 8.4 Integration Suite
Requires a reachable MySQL 8.4 container:
```bash
cd backend
./mvnw test -Dtest=MySql*Test
```

### 3. Frontend Contract & RBAC Suite
```bash
cd frontend
npm test
```

### 4. End-to-End HTTP Blackbox Suite
Drives the real Spring Boot REST API and signed Razorpay webhooks against a disposable MySQL database:
```bash
bash tests/e2e/run-e2e.sh
```


# Aegis-X — Controlled Staging Deployment Checklist

A step-by-step procedure for standing up a **non-production** staging environment on MySQL 8.4.
Nothing here creates external accounts, contacts customers, publishes publicly, or triggers a real
financial transaction.

Each step states how to verify it, because "it started" is not evidence that it works.

---

## 0. What you need before starting

| Item | Notes |
| :--- | :--- |
| Java 21 JDK | `java -version` must report 21.x. |
| Node 22+ | Only needed to build/serve the frontend. |
| MySQL 8.4 server | Tested against 8.4.11. |
| TLS-terminating reverse proxy | Nginx, Caddy, or a load balancer with a certificate. |
| Hostname | e.g. `staging.aegis-x.example`; webhooks must be publicly reachable over HTTPS. |
| Secret store | Vault, AWS Secrets Manager, Kubernetes Secret, or a mode-`0600` env file **outside** the repo. |

Do not reuse a production secret, database, or Razorpay live key anywhere in this environment.

---

## 1. Provision MySQL 8.4 and run the migrations

```sql
-- As a DBA account, not the application account.
CREATE DATABASE aegisx_staging
  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;

CREATE USER 'aegisx_app'@'%' IDENTIFIED BY '<generated-password>';
GRANT SELECT, INSERT, UPDATE, DELETE ON aegisx_staging.* TO 'aegisx_app'@'%';
-- Flyway needs DDL rights. Either grant them to a separate migration account, or temporarily:
GRANT CREATE, ALTER, INDEX, DROP, REFERENCES ON aegisx_staging.* TO 'aegisx_app'@'%';
FLUSH PRIVILEGES;
```

The JDBC URL **must** pin the session to UTC. Without these parameters
`DATETIME(6) DEFAULT CURRENT_TIMESTAMP(6)` means the server's local zone and stored instants shift:

```
jdbc:mysql://db-host:3306/aegisx_staging?connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true&characterEncoding=UTF-8&useSSL=true&requireSSL=true
```

Local verification (already performed in this repository) — 34 MySQL-gated tests, 33 executed,
0 failures, against a disposable schema that Flyway built from empty and Hibernate then validated:

```bash
cd backend
INTEGRITY_MYSQL_URL="jdbc:mysql://127.0.0.1:3306/aegisx_mysqlverify?connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true&useSSL=false&allowPublicKeyRetrieval=true" \
INTEGRITY_MYSQL_USER=root INTEGRITY_MYSQL_PASS= \
  ./mvnw -B test -Dtest='MySql*Test'
```

`MySqlDdlGenerateTest` is skipped by design: it runs `ddl-auto=create` in a separate schema and is
gated on `INTEGRITY_MYSQL_DDL_URL`.

Verify the migration state after the first application start:

```sql
SELECT installed_rank, version, description, success
FROM aegisx_staging.flyway_schema_history ORDER BY installed_rank;
```

Expected on a fresh schema: `1 mysql baseline`, `28 explicit supersession marker`,
`29 add user phone`, `30 scope webhook event idempotency by workspace`, all `success = 1`.
`flyway.validate-on-migrate` stays on, so an edited applied migration fails startup instead of
silently diverging. Never edit an applied migration — add a new one.

---

## 2. Start with the production profile and schema validation

```bash
export SPRING_PROFILES_ACTIVE=prod
export DB_URL='jdbc:mysql://db-host:3306/aegisx_staging?connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true&characterEncoding=UTF-8&useSSL=true&requireSSL=true'
export DB_USERNAME=aegisx_app
export DB_PASSWORD='<from the secret store>'
export PORT=8080
cd backend && ./mvnw -B spring-boot:run
```

`prod` sets `ddl-auto=validate`: if any entity disagrees with the migrated schema the context fails
to start. That failure is the check — do not "fix" it by switching to `update`.

The same startup was verified locally against a freshly migrated MySQL 8.4 schema: the application
reported `Started AegisXApplication in 13.253 seconds` and the end-to-end harness then passed 30/30
HTTP checks against it.

`ProductionSafetyChecks` refuses to start the `prod` profile when `JWT_SECRET` is missing, is the
placeholder from `.env.example`, or is shorter than 32 bytes, and when `CORS_ALLOWED_ORIGINS` is
empty. Both failures are deliberate.

---

## 3. Inject secrets outside source control

Generate, then store in the platform's secret manager — never in a committed file:

```bash
openssl rand -base64 48      # JWT_SECRET
openssl rand -hex 32         # RAZORPAY_WEBHOOK_SECRET / WHATSAPP_VERIFY_TOKEN
```

| Variable | Staging value | Notes |
| :--- | :--- | :--- |
| `JWT_SECRET` | 48 random bytes, base64 | Rotating it invalidates every issued token. |
| `JWT_EXPIRATION` | `86400000` | Milliseconds; 24 h. |
| `CORS_ALLOWED_ORIGINS` | `https://staging.aegis-x.example` | Exact origin, no wildcard, no trailing slash. |
| `DB_PASSWORD` | generated | Never the DBA/root password. |
| `RAZORPAY_KEY_ID` / `RAZORPAY_KEY_SECRET` | **`rzp_test_…`** | The adapter requires the exact `rzp_test_` prefix. |
| `RAZORPAY_WEBHOOK_SECRET` | generated | Must match the value entered in the Razorpay dashboard. |
| `OPENAI_API_KEY` | optional | Leave empty to run AI-disabled. |
| `TESSERACT_PATH` | optional | Leave empty to run OCR-disabled. |

`.env` and `.env.*` are git-ignored; `.env.example` is the committed template and contains only
placeholders. The application never logs a secret value — only that one is missing or rejected.

Rotation: replace the value in the secret store, restart the instance, verify `/actuator/health`.
Rotate `JWT_SECRET` during a maintenance window because all sessions end when it changes; rotate the
Razorpay webhook secret by creating a new secret, updating the store, then the dashboard, then
verifying a test delivery.

---

## 4. HTTPS, CORS and persistent storage

- Terminate TLS at the proxy and redirect HTTP → HTTPS. HSTS (`max-age=31536000`, `includeSubDomains`,
  `preload`) is sent by the application; only enable `preload` once the domain is permanently HTTPS.
- Forward `X-Forwarded-Proto`/`X-Forwarded-For` and set `aegisx.rate-limit.trust-forwarded-headers=true`
  **only** because the proxy overwrites those headers. With the default `false` the limiter uses the
  socket address, which behind a proxy is the proxy itself.
- Serve the built frontend (`cd frontend && npm run build`, output in `frontend/dist`) from the proxy
  or the Spring static handler. Every page and stylesheet is self-hosted — the CSP is
  `font-src 'self'` with no CDN allowance, so a CDN reference will be blocked in the browser.
- Persistent storage: `STORAGE_TYPE=local` and `APP_STORAGE_ROOT_PATH` on a mounted, backed-up
  volume. **S3/MinIO is not implemented** (`S3StorageService` is a stub); only `local` works.
  Uploads are capped at 20 MB and restricted to PDF/JPEG/PNG, with the MIME type detected from the
  bytes rather than the filename.

Verify CORS actually rejects a foreign origin:

```bash
curl -s -D- -o /dev/null -H 'Origin: https://evil.example' https://staging.aegis-x.example/api/health \
  | grep -i 'access-control-allow-origin'   # expect no header
```

---

## 5. Readiness, logging, backup and restore

**Readiness**

```bash
curl -fsS https://staging.aegis-x.example/actuator/health/readiness   # readinessState + db
curl -fsS https://staging.aegis-x.example/actuator/health/liveness
```

`show-details: never` in `prod`, so neither endpoint discloses component detail. Point the load
balancer's health check at `/actuator/health/readiness` and the restart probe at `/actuator/health/liveness`.

**Logging**

JSON logs are written to `aegisx.log` (configurable with `logging.file.name`), rolled daily and at
10 MB, 30 files retained, 1 GB total cap. Each line carries `correlationId` from MDC, so one request
can be followed across modules. In the `prod` profile the root logger is `WARN` and only the file
appender is attached — **container stdout will look almost silent**. Ship the file (or add a console
appender) if your platform only collects stdout. Retain logs according to your own policy; the
application enforces the file rotation, not a legal retention period.

**Backup and restore** — and actually restore once, to an empty schema:

```bash
mysqldump --single-transaction --routines --triggers --set-gtid-purged=OFF \
  -h db-host -u backup_user -p aegisx_staging > aegisx_staging-$(date +%F).sql
# restore rehearsal into a scratch schema, then confirm row counts and migration history
mysql -h db-host -u backup_user -p aegisx_restore_check < aegisx_staging-$(date +%F).sql
mysql -h db-host -u backup_user -p -e \
  "SELECT COUNT(*) FROM aegisx_restore_check.flyway_schema_history WHERE success=1;"
```

Also back up the storage root: a database row whose file is gone is an unusable evidence record.
Back up database and files on the same schedule so they stay consistent.

---

## 6. Razorpay Test Mode and public webhook delivery

1. In the Razorpay dashboard (Test Mode) create an API key pair (`rzp_test_…`).
2. Register the webhook URL `https://staging.aegis-x.example/api/webhooks/razorpay`, subscribe to
   `payment.captured`, `payment.failed`, `refund.processed`, and set a webhook secret.
3. Put the key id/secret and webhook secret in the secret store, restart, and confirm
   `GET /api/integrity/workspaces/{id}` reports provider connectivity `OK` rather than `NOT_CONFIGURED`.
4. Send a test event from the dashboard and verify:
   - HTTP 200,
   - a row in `webhook_events` with a non-empty `event_id`,
   - the same delivery replayed returns 200 and produces **one** business effect (the unique index on
     `event_id` and the `(workspace_id, provider, provider_event_id)` index on `pi_webhook_events` are
     what enforce this),
   - a delivery with a tampered signature is rejected.

Signature verification is HMAC-SHA256 over the raw body and refuses everything when the secret is
unset. There is no timestamp-tolerance check: replay of a *re-signed, distinct* event id is not
detected by the signature layer — it is detected by event-id idempotency only. Do not enable live
keys in staging.

---

## 7. WhatsApp — only with real credentials and approved templates

Skip this step entirely until a Meta Business account, a phone number id, a permanent system-user
token, and **approved** message templates (`notification`, `document_request`) exist. Without them
the integration reports `NOT_CONFIGURED` and no message is sent, which is the correct staging state.

When they do exist: set `WHATSAPP_PHONE_NUMBER_ID`, `WHATSAPP_ACCESS_TOKEN`, `WHATSAPP_APP_SECRET`,
`WHATSAPP_VERIFY_TOKEN`, then complete the Meta webhook verification handshake against
`https://staging.aegis-x.example/api/webhooks/whatsapp`, send one message to an internal test number
only, and confirm the delivery is recorded in the audit trail.

---

## 8. Degradation: AI disabled, OCR disabled

Both must be verified explicitly, because a graceful-degradation claim is worthless if the disabled
path has never been run.

- **AI disabled** (`OPENAI_API_KEY` empty): the application starts without errors, transaction
  analysis skips the advisory step, and the deterministic policy engine still produces the verdict.
  Create one transaction and confirm it reaches a policy decision, with the AI step recorded as
  skipped rather than failed.
- **OCR disabled** (`TESSERACT_PATH` empty): scanned/image uploads are stored and flagged, and the
  workflow continues; PDFs with a text layer still extract. Upload one text PDF and one scanned
  image and confirm the first extracts and the second reports a clean, non-500 failure.

---

## 9. Keep live financial execution disabled

```bash
INTEGRITY_ALLOW_FINANCIAL_EXECUTION=false   # approving a money-moving recovery action is refused
RAZORPAY_ROUTE_ENABLED=false                # transfer verification is reported as not enabled
```

Both are defaults. Do not flip either in staging: the recovery flow must remain advisory.
`createOrder` creates a provider order and does not capture payment. The checkout adapter exposes no
capture or refund operation. The only implemented outbound money-moving path is the separately
gated RazorpayX payout, and that client also requires an `rzp_test_` key ID.

---

## 10. Sign-off

| Check | Evidence to record |
| :--- | :--- |
| Migrations applied | `flyway_schema_history` output, all `success = 1` |
| `ddl-auto=validate` startup | `Started AegisXApplication` with `SPRING_PROFILES_ACTIVE=prod` |
| Readiness/liveness | HTTP 200 from both probes |
| CORS | no `Access-Control-Allow-Origin` for a foreign origin |
| Uploads | one PDF stored and downloaded; path outside the storage root refused |
| Webhooks | signed delivery accepted, replay ignored, tampered signature rejected |
| Backups | dump restored into a scratch schema with matching counts |
| Degradation | AI-off and OCR-off runs recorded |
| Live execution | `INTEGRITY_ALLOW_FINANCIAL_EXECUTION=false` confirmed in the running config |

A green test suite is one row of this table, not the table.

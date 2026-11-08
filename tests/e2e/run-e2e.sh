#!/usr/bin/env bash
# End-to-end verification runner for Aegis-X.
# Starts the backend against the explicitly disposable aegisx_audit MySQL 8.4 database,
# drives the HTTP API with the in-process test provider, then shuts the server down.
set -u

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
BACKEND="$ROOT/backend"
HARNESS="$ROOT/tests/e2e"
OUTDIR="$HARNESS/out"
mkdir -p "$OUTDIR"

to_native_path() {
  if command -v cygpath >/dev/null 2>&1; then cygpath -w "$1"; else printf '%s\n' "$1"; fi
}
to_config_location() {
  if command -v cygpath >/dev/null 2>&1; then printf 'file:///%s/\n' "$(cygpath -m "$1")"; else printf 'file:%s/\n' "$1"; fi
}

BACKEND_NATIVE="$(to_native_path "$BACKEND")"
OUTDIR_NATIVE="$(to_native_path "$OUTDIR")"
HARNESS_NATIVE="$(to_native_path "$HARNESS")"
if [ -z "${JAVA_HOME:-}" ] && command -v cygpath >/dev/null 2>&1; then
  WINDOWS_JDK="$(cygpath -u 'C:\Program Files\Java\jdk-21.0.12')"
  if [ -x "$WINDOWS_JDK/bin/java.exe" ]; then JAVA_HOME="$WINDOWS_JDK"; export JAVA_HOME; fi
fi
JAVA_BIN="${JAVA_BIN:-${JAVA_HOME:+$JAVA_HOME/bin/java}}"
JAVA_BIN="${JAVA_BIN:-$(command -v java || true)}"
if [ -z "$JAVA_BIN" ]; then echo "Java 21 is required." >&2; exit 1; fi
if [ -n "${JAVA_HOME:-}" ]; then export PATH="$JAVA_HOME/bin:$PATH"; fi
if [ -x "$BACKEND/mvnw" ]; then MVN_BIN="$BACKEND/mvnw"; else MVN_BIN="mvn"; fi
if [ -z "${CURL_BIN:-}" ]; then
  if command -v curl.exe >/dev/null 2>&1; then
    CURL_BIN="curl.exe"
  elif command -v curl >/dev/null 2>&1; then
    CURL_BIN="curl"
  elif command -v cygpath >/dev/null 2>&1; then
    WINDOWS_CURL="$(cygpath -u 'C:\Windows\System32\curl.exe')"
    if [ -x "$WINDOWS_CURL" ]; then CURL_BIN="$WINDOWS_CURL"; fi
  fi
fi
if [ -z "${CURL_BIN:-}" ]; then echo "curl is required for the E2E runner." >&2; exit 1; fi

# Read .env line by line instead of sourcing it: DB_URL contains `&`, which the shell would treat
# as a command separator and leave DB_URL unset. Comments and blank lines are skipped, and a
# trailing CR is trimmed because a Windows-edited .env has CRLF endings.
if [ -f "$ROOT/.env" ]; then
  while IFS= read -r line || [ -n "$line" ]; do
    line="${line%$'\r'}"
    case "$line" in ''|'#'*) continue ;; esac
    export "$line"
  done < "$ROOT/.env"
fi

# The harness talks to MySQL with its own credentials (MYSQL_E2E_USER / MYSQL_E2E_PASSWORD,
# defaulting to a passwordless local root). DB_USERNAME in .env belongs to the application and
# may still be a leftover account name, so it is not reused here.
MYSQL_HOST="${MYSQL_HOST:-localhost}"
MYSQL_PORT="${MYSQL_PORT:-3306}"
MYSQL_E2E_USER="${MYSQL_E2E_USER:-${DB_USERNAME:-root}}"
MYSQL_E2E_PASSWORD="${MYSQL_E2E_PASSWORD:-${DB_PASSWORD:-}}"
case "$MYSQL_HOST" in localhost|127.0.0.1) ;; *) echo "Only local MySQL is permitted." >&2; exit 1 ;; esac
export DB_USERNAME="$MYSQL_E2E_USER"
export DB_PASSWORD="$MYSQL_E2E_PASSWORD"

# The timezone parameters are what make DATETIME(6) DEFAULT CURRENT_TIMESTAMP(6) mean UTC; dropping
# them silently shifts stored instants. allowPublicKeyRetrieval is for a local caching_sha2_password
# account over plain TCP.
MYSQL_PARAMS="?connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true&characterEncoding=UTF-8&useSSL=false&allowPublicKeyRetrieval=true"
export ADMIN_MYSQL_URL="jdbc:mysql://$MYSQL_HOST:$MYSQL_PORT$MYSQL_PARAMS"

# Build both application and test classes: the test-scope SIMULATED provider is deliberately
# loaded into this isolated process for offline E2E verification.
echo "### Compiling application and E2E test classes"
( cd "$BACKEND" && "$MVN_BIN" -B -q -DskipTests test-compile ) || {
  echo "Could not compile the backend and E2E provider fixture."
  exit 1
}

# The test-scope project classpath used by the Java helper tools.
CP_FILE="${CP_FILE:-${TMPDIR:-/tmp}/aegisx-e2e-test-cp.txt}"
if [ ! -s "$CP_FILE" ]; then
  echo "### Resolving project classpath into $CP_FILE"
  ( cd "$BACKEND" && "$MVN_BIN" -B -q dependency:build-classpath -Dmdep.includeScope=test -Dmdep.outputFile="$(to_native_path "$CP_FILE")" ) || exit 1
fi
CP="$(cat "$CP_FILE")"
if command -v cygpath >/dev/null 2>&1; then CP_SEPARATOR=';'; else CP_SEPARATOR=':'; fi
APP_CP="$BACKEND_NATIVE/target/classes${CP_SEPARATOR}$BACKEND_NATIVE/target/test-classes${CP_SEPARATOR}$CP"

DBNAME="${E2E_DB:-aegisx_audit}"
if [ "$DBNAME" != "aegisx_audit" ]; then echo "E2E_DB must be aegisx_audit." >&2; exit 1; fi
PORT="${E2E_PORT:-8080}"
case "$PORT" in ''|*[!0-9]*) echo "E2E_PORT must be numeric." >&2; exit 1 ;; esac
if [ "$PORT" -lt 1 ] || [ "$PORT" -gt 65535 ]; then echo "E2E_PORT is out of range." >&2; exit 1; fi
if (exec 3<>"/dev/tcp/127.0.0.1/$PORT") 2>/dev/null; then
  exec 3>&-
  echo "Port $PORT already has a listener; refusing to reset the E2E database."
  exit 1
fi
echo "### Recreating disposable database $DBNAME"
"$JAVA_BIN" -cp "$CP" "$HARNESS_NATIVE/MySqlTool.java" "$DBNAME" recreate || {
  echo "Could not recreate $DBNAME."
  echo "Point ADMIN_MYSQL_URL at a MySQL server you can create databases on, e.g."
  echo "  ADMIN_MYSQL_URL='jdbc:mysql://localhost:3306?useSSL=false&allowPublicKeyRetrieval=true' \\"
  echo "  MYSQL_E2E_USER=root MYSQL_E2E_PASSWORD=... ./verification/run-e2e.sh"
  exit 1
}

export DB_URL="jdbc:mysql://$MYSQL_HOST:$MYSQL_PORT/$DBNAME$MYSQL_PARAMS"
export SPRING_PROFILES_ACTIVE=prod
# Keep the e2e app fully offline: use its test-scope simulated adapter, random ephemeral signing
# and webhook material, and disable every money-moving or external provider integration.
export SPRING_CONFIG_LOCATION="$(to_config_location "$BACKEND/src/main/resources")"
export JWT_SECRET="$(node -e 'process.stdout.write(require("crypto").randomBytes(48).toString("base64"))')"
export CORS_ALLOWED_ORIGINS="http://localhost:3000"
export E2E_SIMULATED_WEBHOOK_SECRET="$(node -e 'process.stdout.write(require("crypto").randomBytes(32).toString("hex"))')"
export RAZORPAY_WEBHOOK_SECRET="$E2E_SIMULATED_WEBHOOK_SECRET"
unset RAZORPAY_KEY_ID RAZORPAY_KEY_SECRET RAZORPAY_API_BASE_URL
export RAZORPAY_ROUTE_ENABLED=false
export RAZORPAYX_ENABLED=false
export INTEGRITY_ALLOW_FINANCIAL_EXECUTION=false
export OPENAI_API_KEY=aegisx-openai-disabled
export WHATSAPP_SANDBOX_MODE=true
export APP_STORAGE_ROOT_PATH="$BACKEND/target/e2e-storage"
export E2E_SIMULATED_PROVIDER=true
# Pin the API port so the runner can avoid an unrelated local service when needed.
export PORT
export SERVER_PORT="$PORT"

LOG="$OUTDIR/backend-e2e.log"
: > "$LOG"
echo "### Starting backend (profile=prod, db=$DBNAME)"
echo "### E2E API port $PORT using $CURL_BIN"
if command -v setsid >/dev/null 2>&1; then
  ( cd "$BACKEND" && setsid "$JAVA_BIN" -cp "$APP_CP" com.aegisx.AegisXApplication > "$LOG" 2>&1 ) &
else
  ( cd "$BACKEND" && "$JAVA_BIN" -cp "$APP_CP" com.aegisx.AegisXApplication > "$LOG" 2>&1 ) &
fi
APP_PID=$!

# Stop the launched application and clean up any listener it left behind.
cleanup() {
  local status=$?
  echo "### Stopping backend (pid $APP_PID)"
  if command -v taskkill >/dev/null 2>&1; then
    taskkill //PID "$APP_PID" //T //F >/dev/null 2>&1 || true
    local listener
    listener="$(netstat -ano | grep -E "[:.]${PORT} " | grep LISTENING | awk '{print $5}' | head -1)"
    if [ -n "${listener:-}" ]; then taskkill //PID "$listener" //T //F >/dev/null 2>&1 || true; fi
  elif command -v setsid >/dev/null 2>&1; then
    kill -TERM -- "-$APP_PID" >/dev/null 2>&1 || true
    wait "$APP_PID" >/dev/null 2>&1 || true
  else
    kill "$APP_PID" >/dev/null 2>&1 || true
    wait "$APP_PID" >/dev/null 2>&1 || true
  fi
  return $status
}
trap cleanup EXIT

echo "### Waiting for /actuator/health"
READY=0
for i in $(seq 1 60); do
  HEALTH_STATUS=$("$CURL_BIN" -s -m 3 -o /dev/null -w '%{http_code}' \
    "http://127.0.0.1:$PORT/actuator/health" 2>/dev/null)
  if [ -z "$HEALTH_STATUS" ]; then HEALTH_STATUS=000; fi
  if [ "$HEALTH_STATUS" = "200" ] && grep -Fq '"message":"Started AegisXApplication' "$LOG"; then
    READY=1; echo "ready after $((i*5))s"; break
  fi
  sleep 5
done
if [ "$READY" != "1" ]; then
  echo "BACKEND DID NOT BECOME READY"; exit 1
fi

ADMIN_EMAIL="admin-e2e@aegis-x.test"
ADMIN_PASSWORD="${ADMIN_PASSWORD:-AdminTestPass_$(date +%s)_Aa1!}"
echo "### Provisioning admin operator $ADMIN_EMAIL"
ADMIN_REGISTER_STATUS=$("$CURL_BIN" -s -o /dev/null -w '%{http_code}' -X POST "http://127.0.0.1:$PORT/api/auth/register" \
  -H 'Content-Type: application/json' \
  -d "{\"fullName\":\"E2E Admin\",\"email\":\"$ADMIN_EMAIL\",\"password\":\"$ADMIN_PASSWORD\"}")
if [ "$ADMIN_REGISTER_STATUS" != "201" ]; then
  echo "Local E2E operator registration failed (HTTP $ADMIN_REGISTER_STATUS)."
  exit 1
fi
PROMOTE_OUTPUT=$("$JAVA_BIN" -cp "$CP" "$HARNESS_NATIVE/MySqlTool.java" "$DBNAME" sql \
  "UPDATE users SET \`role\`='ADMIN' WHERE email='$ADMIN_EMAIL'" 2>&1)
PROMOTE_EXIT=$?
if [ "$PROMOTE_EXIT" -ne 0 ]; then
  PROMOTE_ERROR=$(printf '%s\n' "$PROMOTE_OUTPUT" | sed -n 's/^Exception in thread "main" \([^:]*\).*/\1/p' | head -n 1)
  echo "Could not grant the local E2E operator role (${PROMOTE_ERROR:-local SQL failure})."
  exit 1
fi
PROMOTE_RESULT="$PROMOTE_OUTPUT"
if [ "$PROMOTE_RESULT" != "rows=1" ]; then
  echo "Expected to promote exactly one local E2E operator."
  exit 1
fi

echo "### Driving E2E HTTP flows"
FLOW_CP="$APP_CP"
B="http://127.0.0.1:$PORT" \
CP="$FLOW_CP" \
OUT_DIR="$OUTDIR_NATIVE" \
HARNESS_DIR="$HARNESS_NATIVE" \
BACKEND_DIR="$BACKEND_NATIVE" \
E2E_DB="$DBNAME" \
ADMIN_EMAIL="$ADMIN_EMAIL" \
ADMIN_PASSWORD="$ADMIN_PASSWORD" \
JAVA_BIN="$(to_native_path "$JAVA_BIN")" \
node "$HARNESS/e2e-flows.mjs"

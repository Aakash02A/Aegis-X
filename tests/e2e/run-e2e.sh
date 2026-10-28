#!/usr/bin/env bash
# End-to-end verification runner for Aegis-X.
# Starts the backend against an isolated MySQL 8.4 database, drives the real HTTP API,
# then shuts the server down. Never touches the application database.
set -u

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
BACKEND="$ROOT/backend"
HARNESS="$ROOT/tests/e2e"
OUTDIR="$HARNESS/out"
mkdir -p "$OUTDIR"

BACKEND_WIN="$(cygpath -w "$BACKEND")"
OUTDIR_WIN="$(cygpath -w "$OUTDIR")"
HARNESS_WIN="$(cygpath -w "$HARNESS")"
JAVA_BIN="${JAVA_BIN:-C:/Program Files/Java/jdk-21.0.12/bin/java.exe}"

# Read .env line by line instead of sourcing it: DB_URL contains `&`, which the shell would treat
# as a command separator and leave DB_URL unset. Comments and blank lines are skipped, and a
# trailing CR is trimmed because a Windows-edited .env has CRLF endings.
while IFS= read -r line || [ -n "$line" ]; do
  line="${line%$'\r'}"
  case "$line" in ''|'#'*) continue ;; esac
  export "$line"
done < "$ROOT/.env"

# The harness talks to MySQL with its own credentials (MYSQL_E2E_USER / MYSQL_E2E_PASSWORD,
# defaulting to a passwordless local root). DB_USERNAME in .env belongs to the application and
# may still be a leftover account name, so it is not reused here.
MYSQL_HOST="${MYSQL_HOST:-localhost}"
MYSQL_PORT="${MYSQL_PORT:-3306}"
MYSQL_E2E_USER="${MYSQL_E2E_USER:-root}"
MYSQL_E2E_PASSWORD="${MYSQL_E2E_PASSWORD:-}"
export DB_USERNAME="$MYSQL_E2E_USER"
export DB_PASSWORD="$MYSQL_E2E_PASSWORD"

# The timezone parameters are what make DATETIME(6) DEFAULT CURRENT_TIMESTAMP(6) mean UTC; dropping
# them silently shifts stored instants. allowPublicKeyRetrieval is for a local caching_sha2_password
# account over plain TCP.
MYSQL_PARAMS="?connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true&characterEncoding=UTF-8&useSSL=false&allowPublicKeyRetrieval=true"
export ADMIN_MYSQL_URL="${ADMIN_MYSQL_URL:-jdbc:mysql://$MYSQL_HOST:$MYSQL_PORT}$MYSQL_PARAMS"

# The project classpath used by the helper tools. `mvn dependency:build-classpath` is cached in
# /tmp so repeated runs stay fast, but it is regenerated when missing instead of being assumed.
CP_FILE="${CP_FILE:-/tmp/aegisx-e2e-cp.txt}"
if [ ! -s "$CP_FILE" ]; then
  echo "### Resolving project classpath into $CP_FILE"
  ( cd "$BACKEND" && mvn -B -q dependency:build-classpath -Dmdep.outputFile="$(cygpath -w "$CP_FILE")" ) || exit 1
fi
CP="$(cat "$CP_FILE")"

DBNAME="${E2E_DB:-aegisx_e2e}"
echo "### Recreating isolated database $DBNAME"
"$JAVA_BIN" -cp "$CP" "$HARNESS_WIN/MySqlTool.java" "$DBNAME" recreate || {
  echo "Could not recreate $DBNAME."
  echo "Point ADMIN_MYSQL_URL at a MySQL server you can create databases on, e.g."
  echo "  ADMIN_MYSQL_URL='jdbc:mysql://localhost:3306?useSSL=false&allowPublicKeyRetrieval=true' \\"
  echo "  MYSQL_E2E_USER=root MYSQL_E2E_PASSWORD=... ./verification/run-e2e.sh"
  exit 1
}

export DB_URL="jdbc:mysql://$MYSQL_HOST:$MYSQL_PORT/$DBNAME$MYSQL_PARAMS"
export SPRING_PROFILES_ACTIVE=prod
# The ambient sandbox environment exports PORT=0, which Spring Boot reads as "pick a random
# port". Pin the documented port so the real frontend origin is exercised.
export PORT=8080

LOG="$OUTDIR/backend-e2e.log"
: > "$LOG"
echo "### Starting backend (profile=prod, db=$DBNAME)"
( cd "$BACKEND" && mvn -B spring-boot:run > "$LOG" 2>&1 ) &
APP_PID=$!

# mvn spring-boot:run forks a JVM, and on Windows the fork outlives the wrapper shell, so killing
# APP_PID alone leaves the server holding the port. Kill the tree and then whatever still listens.
cleanup() {
  local status=$?
  echo "### Stopping backend (pid $APP_PID)"
  taskkill //PID "$APP_PID" //T //F >/dev/null 2>&1
  local listener
  listener="$(netstat -ano | grep -E "[:.]${PORT} " | grep LISTENING | awk '{print $5}' | head -1)"
  if [ -n "${listener:-}" ]; then
    taskkill //PID "$listener" //T //F >/dev/null 2>&1
  fi
  return $status
}
trap cleanup EXIT

echo "### Waiting for /actuator/health"
READY=0
for i in $(seq 1 60); do
  if [ "$(curl -s -m 3 -o /dev/null -w '%{http_code}' http://localhost:8080/actuator/health 2>/dev/null)" = "200" ]; then
    READY=1; echo "ready after $((i*5))s"; break
  fi
  sleep 5
done
if [ "$READY" != "1" ]; then
  echo "BACKEND DID NOT BECOME READY"; tail -n 40 "$LOG"; exit 1
fi

ADMIN_EMAIL="admin-e2e@aegis-x.test"
ADMIN_PASSWORD="${ADMIN_PASSWORD:-AdminTestPass_$(date +%s)_Aa1!}"
echo "### Provisioning admin operator $ADMIN_EMAIL"
curl -s -o /dev/null -X POST http://localhost:8080/api/auth/register \
  -H 'Content-Type: application/json' \
  -d "{\"fullName\":\"E2E Admin\",\"email\":\"$ADMIN_EMAIL\",\"password\":\"$ADMIN_PASSWORD\"}"
"$JAVA_BIN" -cp "$CP" "$HARNESS_WIN/MySqlTool.java" "$DBNAME" sql \
  "UPDATE users SET role='ADMIN' WHERE email='$ADMIN_EMAIL'"

echo "### Driving E2E HTTP flows"
FLOW_CP="$BACKEND_WIN/target/classes;$BACKEND_WIN/target/test-classes;$CP"
B=http://localhost:8080 \
CP="$FLOW_CP" \
OUT_DIR="$OUTDIR_WIN" \
HARNESS_DIR="$HARNESS_WIN" \
BACKEND_DIR="$BACKEND_WIN" \
JAVA_BIN="$JAVA_BIN" \
ADMIN_EMAIL="$ADMIN_EMAIL" \
ADMIN_PASSWORD="$ADMIN_PASSWORD" \
node "$HARNESS/e2e-flows.mjs"

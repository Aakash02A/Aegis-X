#!/usr/bin/env bash
# Probes running Aegis-X services for liveness, readiness, and MySQL connectivity
set -euo pipefail

ENDPOINT="${1:-http://localhost:8080}"

echo "Probing Aegis-X Actuator Health at $ENDPOINT/actuator/health ..."
STATUS=$(curl -s "$ENDPOINT/actuator/health" | grep -o '"status":"[^"]*"' | head -n 1 || echo "DOWN")

echo "Result: $STATUS"
if [[ "$STATUS" == *"UP"* ]]; then
  echo "✅ Aegis-X Backend & Database are HEALTHY."
  exit 0
else
  echo "❌ Aegis-X service is NOT healthy."
  exit 1
fi


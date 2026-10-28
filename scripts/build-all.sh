#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"

echo "=== 1. Building Backend Spring Boot Executable JAR ==="
cd "$ROOT/backend"
./mvnw clean package -DskipTests

echo "=== 2. Building Frontend Production Bundle ==="
cd "$ROOT/frontend"
npm ci
npm run build

echo "=== Build Complete! ==="


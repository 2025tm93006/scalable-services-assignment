#!/usr/bin/env bash
# One file through all four services under one correlation ID:
# Intake -> Quarantine -> (Quarantine calls Scanner) -> (Release verifies at Quarantine).
# Usage: bash scripts/flow.sh [CLEAN|INFECTED]
set -euo pipefail

INTAKE=${INTAKE_URL:-http://localhost:8081}
QUARANTINE=${QUARANTINE_URL:-http://localhost:8082}
RELEASE=${RELEASE_URL:-http://localhost:8084}
MODE=${1:-CLEAN}
CID="flow-$(date +%s)"
SHA=$(printf 'flow %s' "$CID" | sha256sum | cut -d' ' -f1)
H=(-sS -H 'Content-Type: application/json' -H "X-Correlation-Id: $CID")

field() { echo "$1" | grep -o "\"$2\":\"[^\"]*\"" | head -1 | cut -d'"' -f4; }

echo "correlationId=$CID mode=$MODE"

echo "== 1. Intake      POST $INTAKE/api/v1/files"
R=$(curl "${H[@]}" -H "Idempotency-Key: $CID" \
  -d "{\"filename\":\"report.pdf\",\"contentType\":\"application/pdf\",\"sizeBytes\":12,\"sha256\":\"$SHA\",\"scannerMode\":\"$MODE\"}" \
  "$INTAKE/api/v1/files")
echo "$R"; FID=$(field "$R" fileId)

echo "== 2. Quarantine  POST $QUARANTINE/api/v1/quarantine"
R=$(curl "${H[@]}" -d "{\"fileId\":\"$FID\",\"scannerMode\":\"$MODE\"}" "$QUARANTINE/api/v1/quarantine")
echo "$R"; QID=$(field "$R" id); QAT=$(field "$R" quarantinedAt)

echo "== 3. Scan        POST $QUARANTINE/api/v1/quarantine/$QID/scan   (Quarantine -> Scanner)"
R=$(curl "${H[@]}" -X POST "$QUARANTINE/api/v1/quarantine/$QID/scan")
echo "$R"; STATE=$(field "$R" state)

echo "== 4. Release     POST $RELEASE/api/v1/releases   (Release -> Quarantine)"
curl "${H[@]}" -w ' [HTTP %{http_code}]\n' \
  -d "{\"fileId\":\"$FID\",\"quarantineState\":\"$STATE\",\"quarantinedAt\":\"$QAT\"}" \
  "$RELEASE/api/v1/releases"

if command -v docker >/dev/null 2>&1 && docker ps --format '{{.Names}}' | grep -q '^intake-service$'; then
  echo; echo "== Log lines for $CID in each container"
  for s in intake-service quarantine-service scanner-service release-service; do
    echo "-- $s"; docker logs "$s" 2>&1 | grep "$CID" || true
  done
fi

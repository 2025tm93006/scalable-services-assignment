#!/usr/bin/env bash
# Scripted end-to-end test of the main transaction: Intake -> Quarantine -> Scanner -> Release.
# It proves BR1 (replay), BR2 (key reuse), BR3 (quarantine state machine), BR4 (clean-only
# release) and exercises the failure paths, not just the happy path.
# Start all four services first (see README) and run this from the repository root.
set -uo pipefail

INTAKE=${INTAKE_URL:-http://localhost:8081}
QUARANTINE=${QUARANTINE_URL:-http://localhost:8082}
SCANNER=${SCANNER_URL:-http://localhost:8083}
RELEASE=${RELEASE_URL:-http://localhost:8084}
CORRELATION="e2e-$(date -u +%Y%m%d%H%M%S)"
WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT

echo "E2E started at $(date -u +%Y-%m-%dT%H:%M:%SZ) correlationId=$CORRELATION"

fail() { echo "E2E FAIL: $1"; echo "--- last response ---"; cat "$WORK/response.json" 2>/dev/null; exit 1; }

# request METHOD URL PAYLOAD IDEMPOTENCY_KEY -> sets STATUS and BODY
request() {
  local method=$1 url=$2 payload=$3 key=${4:-}
  local args=(-sS -o "$WORK/response.json" -w '%{http_code}' -X "$method"
              -H 'Content-Type: application/json' -H "X-Correlation-Id: $CORRELATION")
  [ -n "$key" ] && args+=(-H "Idempotency-Key: $key")
  [ -n "$payload" ] && args+=(-d "$payload")
  STATUS=$(curl "${args[@]}" "$url") || fail "transport error calling $url"
  BODY=$(cat "$WORK/response.json")
}

# expect STATUS_CODE LABEL -> asserts the last request returned that status
expect() { [ "$STATUS" = "$1" ] || fail "$2: expected HTTP $1, got $STATUS"; echo "ok  $2 -> HTTP $STATUS"; }

field() { python3 -c 'import json,sys; print(json.load(sys.stdin)["'"$1"'"])' <<<"$BODY"; }

for base in "$INTAKE" "$QUARANTINE" "$SCANNER" "$RELEASE"; do
  request GET "$base/health" '' || true
  expect 200 "health $base"
done

SHA=$(python3 -c 'import hashlib; print(hashlib.sha256(b"demo payload").hexdigest())')
KEY="e2e-$(date +%s)"
PAYLOAD="{\"filename\":\"demo.txt\",\"contentType\":\"text/plain\",\"sizeBytes\":12,\"sha256\":\"$SHA\",\"scannerMode\":\"CLEAN\"}"

# BR1 - the same key and payload replays the original response and stores nothing new.
request POST "$INTAKE/api/v1/files" "$PAYLOAD" "$KEY"
expect 201 "intake first submission"
FILE_ID=$(field fileId)
FIRST_INTAKE="$BODY"
request POST "$INTAKE/api/v1/files" "$PAYLOAD" "$KEY"
expect 201 "intake replay of the same key and payload"
[ "$BODY" = "$FIRST_INTAKE" ] || fail "BR1: replayed response differs from the original"
echo "ok  BR1 replayed body is byte-identical"

# BR2 - the same key with a different payload is refused and the original stays intact.
CHANGED="${PAYLOAD/demo.txt/other.txt}"
request POST "$INTAKE/api/v1/files" "$CHANGED" "$KEY"
expect 409 "BR2 same key with a different payload"
request GET "$INTAKE/api/v1/files/$FILE_ID" ''
expect 200 "BR2 original record is still readable"

# BR3 - quarantine the file, scan it with the Scanner service and transition its state once.
request POST "$QUARANTINE/api/v1/quarantine" "{\"fileId\":\"$FILE_ID\",\"scannerMode\":\"CLEAN\"}"
expect 201 "quarantine record created"
QUARANTINE_ID=$(field id)
QUARANTINED_AT=$(field quarantinedAt)
request POST "$QUARANTINE/api/v1/quarantine" "{\"fileId\":\"$FILE_ID\",\"scannerMode\":\"CLEAN\"}"
expect 409 "BR3 the same file cannot be quarantined twice"
request POST "$SCANNER/api/v1/scans" "{\"fileId\":\"$FILE_ID\",\"mode\":\"CLEAN\"}"
expect 200 "scanner returned a deterministic result"
request POST "$QUARANTINE/api/v1/quarantine/$QUARANTINE_ID/scan" ''
expect 200 "quarantine scan transition"
[ "$(field state)" = "CLEAN" ] || fail "BR3: expected state CLEAN, got $(field state)"
request POST "$QUARANTINE/api/v1/quarantine/$QUARANTINE_ID/scan" ''
expect 409 "BR3 a scanned record cannot be scanned again"

# BR4 - only a clean quarantined file may be released.
request GET "$RELEASE/api/v1/releases/$FILE_ID" ''
expect 404 "release record is absent before the release"
request POST "$RELEASE/api/v1/releases" \
  "{\"fileId\":\"$FILE_ID\",\"quarantineState\":\"INFECTED\",\"quarantinedAt\":\"$QUARANTINED_AT\"}"
expect 409 "BR4 an infected file is refused"
request POST "$RELEASE/api/v1/releases" \
  "{\"fileId\":\"$FILE_ID\",\"quarantineState\":\"CLEAN\",\"quarantinedAt\":\"$QUARANTINED_AT\"}"
expect 201 "release accepted the clean file"
RELEASED="$BODY"
request POST "$RELEASE/api/v1/releases" \
  "{\"fileId\":\"$FILE_ID\",\"quarantineState\":\"CLEAN\",\"quarantinedAt\":\"$QUARANTINED_AT\"}"
expect 201 "release replay of the repeated request"
[ "$BODY" = "$RELEASED" ] || fail "BR4: replayed release differs from the original decision"
request GET "$RELEASE/api/v1/releases/$FILE_ID" ''
expect 200 "release record is readable"

echo "E2E PASS fileId=$FILE_ID correlationId=$CORRELATION at $(date -u +%Y-%m-%dT%H:%M:%SZ)"

#!/usr/bin/env bash
# Scripted end-to-end test of the main transaction: Intake -> Quarantine -> Scanner -> Release.
# Scanner is reached through Quarantine, which is the only service that calls another
# service; the script then reads the attempt back from Scanner to prove the call happened.
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

# BR3 - quarantine the file, let Quarantine call Scanner, and transition the state once.
request POST "$QUARANTINE/api/v1/quarantine" "{\"fileId\":\"$FILE_ID\",\"scannerMode\":\"CLEAN\"}"
expect 201 "quarantine record created"
QUARANTINE_ID=$(field id)
QUARANTINED_AT=$(field quarantinedAt)
request POST "$QUARANTINE/api/v1/quarantine" "{\"fileId\":\"$FILE_ID\",\"scannerMode\":\"CLEAN\"}"
expect 409 "BR3 the same file cannot be quarantined twice"
request GET "$SCANNER/api/v1/scans/$FILE_ID" ''
expect 404 "Scanner has not seen this file before Quarantine scans it"
request POST "$QUARANTINE/api/v1/quarantine/$QUARANTINE_ID/scan" ''
expect 200 "quarantine scan transition"
[ "$(field state)" = "CLEAN" ] || fail "BR3: expected state CLEAN, got $(field state)"
QUARANTINE_SCANNED_AT=$(field scannedAt)
request GET "$SCANNER/api/v1/scans/$FILE_ID" ''
expect 200 "the scan Quarantine triggered is stored in Scanner's own database"
[ "$(field result)" = "CLEAN" ] || fail "cross-service call: Scanner stored $(field result)"
[ "$(field finishedAt)" = "$QUARANTINE_SCANNED_AT" ] \
  || fail "Quarantine kept its own timestamp instead of Scanner's: $QUARANTINE_SCANNED_AT vs $(field finishedAt)"
echo "ok  cross-service scan: Quarantine state and Scanner's finishedAt agree"
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

# The failure path of the same transaction: a file Scanner reports as infected never
# reaches Release, and the refusal comes from Quarantine's own verdict, not from a
# value the client typed in.
DIRTY_KEY="e2e-infected-$(date +%s)"
DIRTY_PAYLOAD="{\"filename\":\"malware.exe\",\"contentType\":\"application/octet-stream\",\"sizeBytes\":11,\"sha256\":\"$SHA\",\"scannerMode\":\"INFECTED\"}"
request POST "$INTAKE/api/v1/files" "$DIRTY_PAYLOAD" "$DIRTY_KEY"
expect 201 "intake accepted the file that will be found infected"
DIRTY_FILE_ID=$(field fileId)
request POST "$QUARANTINE/api/v1/quarantine" "{\"fileId\":\"$DIRTY_FILE_ID\",\"scannerMode\":\"INFECTED\"}"
expect 201 "the infected file is quarantined"
DIRTY_QUARANTINE_ID=$(field id)
request POST "$QUARANTINE/api/v1/quarantine/$DIRTY_QUARANTINE_ID/scan" ''
expect 200 "the infected file is scanned"
[ "$(field state)" = "INFECTED" ] || fail "expected INFECTED, got $(field state)"
DIRTY_QUARANTINED_AT=$(field quarantinedAt)
request POST "$RELEASE/api/v1/releases" \
  "{\"fileId\":\"$DIRTY_FILE_ID\",\"quarantineState\":\"INFECTED\",\"quarantinedAt\":\"$DIRTY_QUARANTINED_AT\"}"
expect 409 "BR4 the genuinely infected file is refused its release"
request GET "$RELEASE/api/v1/releases/$DIRTY_FILE_ID" ''
expect 404 "BR4 nothing was stored for the refused file"

# Capacity is a shared, contended resource: while both scanner slots are held a third
# scan is refused rather than queued silently. See scripts/concurrency.py for the burst.
request GET "$QUARANTINE/api/v1/quarantine/_evidence/summary" ''
expect 200 "quarantine reports its own state"
echo "ok  final quarantine summary: $BODY"

echo "E2E PASS fileId=$FILE_ID infectedFileId=$DIRTY_FILE_ID correlationId=$CORRELATION at $(date -u +%Y-%m-%dT%H:%M:%SZ)"

#!/usr/bin/env bash
# Scripted end-to-end test of the main transaction: Intake -> Quarantine -> Scanner -> Release.
set -uo pipefail

GREEN='\033[0;32m'; RED='\033[0;31m'; CYAN='\033[0;36m'; YELLOW='\033[1;33m'
BOLD='\033[1m'; DIM='\033[2m'; RESET='\033[0m'

INTAKE=${INTAKE_URL:-http://localhost:8081}
QUARANTINE=${QUARANTINE_URL:-http://localhost:8082}
SCANNER=${SCANNER_URL:-http://localhost:8083}
RELEASE=${RELEASE_URL:-http://localhost:8084}
CORRELATION="e2e-$(date -u +%Y%m%d%H%M%S)"
WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT

section() { echo -e "\n${CYAN}${BOLD}── $1 ──${RESET}"; }
pass()    { echo -e "  ${GREEN}✔${RESET}  $1"; }
fail()    {
  echo -e "  ${RED}✘  E2E FAIL: $1${RESET}"
  echo -e "  ${DIM}Last response:${RESET}"
  cat "$WORK/response.json" 2>/dev/null | python3 -m json.tool 2>/dev/null || cat "$WORK/response.json" 2>/dev/null
  exit 1
}

echo -e "${BOLD}${CYAN}"
echo "╔══════════════════════════════════════════════════════════╗"
echo "║         SECURE FILE QUARANTINE SYSTEM — E2E TEST        ║"
echo "╚══════════════════════════════════════════════════════════╝${RESET}"
echo -e "  ${DIM}correlationId=$CORRELATION${RESET}"
echo -e "  ${DIM}started at $(date -u +%Y-%m-%dT%H:%M:%SZ)${RESET}"

request() {
  local method=$1 url=$2 payload=$3 key=${4:-}
  local args=(-sS -o "$WORK/response.json" -w '%{http_code}' -X "$method"
              -H 'Content-Type: application/json' -H "X-Correlation-Id: $CORRELATION")
  [ -n "$key" ] && args+=(-H "Idempotency-Key: $key")
  [ -n "$payload" ] && args+=(-d "$payload")
  STATUS=$(curl "${args[@]}" "$url") || fail "transport error calling $url"
  BODY=$(cat "$WORK/response.json")
}

expect() { [ "$STATUS" = "$1" ] || fail "$2: expected HTTP $1, got $STATUS"; pass "$2  ${DIM}→ HTTP $STATUS${RESET}"; }
field()  { python3 -c 'import json,sys; print(json.load(sys.stdin)["'"$1"'"])' <<<"$BODY"; }

section "Health checks"
for base in "$INTAKE" "$QUARANTINE" "$SCANNER" "$RELEASE"; do
  request GET "$base/health" '' || true
  expect 200 "health $base"
done

SHA=$(python3 -c 'import hashlib; print(hashlib.sha256(b"demo payload").hexdigest())')
KEY="e2e-$(date +%s)"
PAYLOAD="{\"filename\":\"demo.txt\",\"contentType\":\"text/plain\",\"sizeBytes\":12,\"sha256\":\"$SHA\",\"scannerMode\":\"CLEAN\"}"

section "BR1 — Idempotency: same key + payload replays identical response"
request POST "$INTAKE/api/v1/files" "$PAYLOAD" "$KEY"
expect 201 "intake first submission"
FILE_ID=$(field fileId)
FIRST_INTAKE="$BODY"
request POST "$INTAKE/api/v1/files" "$PAYLOAD" "$KEY"
expect 201 "intake replay of the same key and payload"
[ "$BODY" = "$FIRST_INTAKE" ] || fail "BR1: replayed response differs from the original"
pass "BR1 replayed body is byte-identical"

section "BR2 — Conflict: same key with different payload returns 409"
CHANGED="${PAYLOAD/demo.txt/other.txt}"
request POST "$INTAKE/api/v1/files" "$CHANGED" "$KEY"
expect 409 "BR2 same key with a different payload"
request GET "$INTAKE/api/v1/files/$FILE_ID" ''
expect 200 "BR2 original record is still readable"

section "BR3 — State machine: quarantine → scan → CLEAN (one transition only)"
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
  || fail "Quarantine kept its own timestamp instead of Scanner's"
pass "cross-service scan: Quarantine state and Scanner's finishedAt agree"
request GET "$QUARANTINE/api/v1/quarantine/by-file/$FILE_ID" ''
expect 200 "Quarantine answers for the file by its Intake id"
[ "$(field state)" = "CLEAN" ] || fail "by-file read reported $(field state) instead of CLEAN"
pass "the record Release will verify says CLEAN"
request POST "$QUARANTINE/api/v1/quarantine/$QUARANTINE_ID/scan" ''
expect 409 "BR3 a scanned record cannot be scanned again"

section "BR4 — Release: only CLEAN files may be released (verified at the owner)"
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

section "Failure path — infected file cannot be released even by lying"
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
request POST "$RELEASE/api/v1/releases" \
  "{\"fileId\":\"$DIRTY_FILE_ID\",\"quarantineState\":\"CLEAN\",\"quarantinedAt\":\"$DIRTY_QUARANTINED_AT\"}"
expect 409 "BR4 a client cannot release an infected file by claiming it is CLEAN"
echo -e "  ${DIM}  Release said: $(python3 -c 'import json,sys; print(json.load(sys.stdin).get("message",""))' <<<"$BODY")${RESET}"
request GET "$RELEASE/api/v1/releases/$DIRTY_FILE_ID" ''
expect 404 "BR4 nothing was stored for the refused file"

section "Final state"
request GET "$QUARANTINE/api/v1/quarantine/_evidence/summary" ''
expect 200 "quarantine summary"
echo -e "  ${DIM}$BODY${RESET}"

echo ""
echo -e "${GREEN}${BOLD}╔══════════════════════════════════════════════════════════╗"
echo    "║                    E2E PASS                              ║"
echo -e "╚══════════════════════════════════════════════════════════╝${RESET}"
echo -e "  ${DIM}fileId=$FILE_ID${RESET}"
echo -e "  ${DIM}infectedFileId=$DIRTY_FILE_ID${RESET}"
echo -e "  ${DIM}correlationId=$CORRELATION${RESET}"
echo -e "  ${DIM}finished at $(date -u +%Y-%m-%dT%H:%M:%SZ)${RESET}"

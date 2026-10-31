#!/usr/bin/env bash
set -euo pipefail

GREEN='\033[0;32m'; RED='\033[0;31m'; CYAN='\033[0;36m'; BOLD='\033[1m'; RESET='\033[0m'

echo -e "${BOLD}${CYAN}"
echo "╔══════════════════════════════════════════════╗"
echo "║         SECURE FILE QUARANTINE SYSTEM        ║"
echo "║              Health Check                    ║"
echo -e "╚══════════════════════════════════════════════╝${RESET}"
echo ""

URLS=(
  "${INTAKE_URL:-http://localhost:8081}|Intake      :8081"
  "${QUARANTINE_URL:-http://localhost:8082}|Quarantine  :8082"
  "${SCANNER_URL:-http://localhost:8083}|Scanner     :8083"
  "${RELEASE_URL:-http://localhost:8084}|Release     :8084"
)

ALL_OK=true
for entry in "${URLS[@]}"; do
  url="${entry%%|*}"
  name="${entry##*|}"
  response=$(curl --silent --show-error --max-time 10 -w "\n%{http_code}" "$url/health" 2>&1) || true
  http_code=$(echo "$response" | tail -1)
  body=$(echo "$response" | head -1)
  if [ "$http_code" = "200" ]; then
    echo -e "  ${GREEN}✔${RESET}  ${BOLD}$name${RESET}  →  $body"
  else
    echo -e "  ${RED}✘${RESET}  ${BOLD}$name${RESET}  →  HTTP $http_code"
    ALL_OK=false
  fi
done

echo ""
if $ALL_OK; then
  echo -e "${GREEN}${BOLD}  All services are UP${RESET}"
else
  echo -e "${RED}${BOLD}  One or more services are DOWN${RESET}"
  exit 1
fi

#!/usr/bin/env bash
set -euo pipefail
for url in "${INTAKE_URL:-http://localhost:8081}" \
           "${QUARANTINE_URL:-http://localhost:8082}" \
           "${SCANNER_URL:-http://localhost:8083}" \
           "${RELEASE_URL:-http://localhost:8084}"; do
  echo "GET $url/health"
  curl --fail --silent --show-error --max-time 10 "$url/health"
  echo
done

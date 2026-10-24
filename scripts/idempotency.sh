#!/usr/bin/env bash
set -euo pipefail

INTAKE_URL=${INTAKE_URL:-http://localhost:8081}
IDEMPOTENCY_CONCURRENT_REQUESTS=${IDEMPOTENCY_CONCURRENT_REQUESTS:-8}

python3 - "$INTAKE_URL" "$IDEMPOTENCY_CONCURRENT_REQUESTS" <<'PY'
import concurrent.futures
import json
import sys
import urllib.error
import urllib.parse
import urllib.request
import uuid
from collections import Counter
from datetime import datetime, timezone
from threading import Barrier

base = sys.argv[1].rstrip('/')
count = int(sys.argv[2])
if count < 2:
    raise SystemExit('IDEMPOTENCY_CONCURRENT_REQUESTS must be at least 2')
url = base + '/api/v1/files'
correlation = 'intake-demo-' + str(uuid.uuid4())
print('TIMESTAMP', datetime.now(timezone.utc).isoformat(), flush=True)
print('COMMAND bash scripts/idempotency.sh', flush=True)
print('INTAKE_URL', base, 'PARALLEL_REQUESTS', count, flush=True)
print('CORRELATION_ID', correlation, flush=True)

def call(method, endpoint, key=None, body=None):
    headers = {'Content-Type': 'application/json', 'X-Correlation-Id': correlation}
    if key is not None:
        headers['Idempotency-Key'] = key
    data = None if body is None else json.dumps(body).encode()
    req = urllib.request.Request(endpoint, data=data, headers=headers, method=method)
    try:
        with urllib.request.urlopen(req, timeout=30) as response:
            assert response.headers['X-Correlation-Id'] == correlation
            return response.status, json.loads(response.read())
    except urllib.error.HTTPError as error:
        assert error.headers['X-Correlation-Id'] == correlation
        return error.code, json.loads(error.read())

body = {'filename': 'demo.txt', 'contentType': 'text/plain', 'sizeBytes': 4,
        'sha256': 'a' * 64, 'scannerMode': 'CLEAN'}
key = 'sequential-' + str(uuid.uuid4())
first_status, first = call('POST', url, key, body)
replay_status, replay = call('POST', url, key, body)
changed_status, changed = call('POST', url, key, dict(body, filename='different.txt'))
print('FIRST', first_status, json.dumps(first, sort_keys=True))
print('REPLAY', replay_status, json.dumps(replay, sort_keys=True))
print('DIFFERENT_PAYLOAD', changed_status, json.dumps(changed, sort_keys=True))
assert first_status == replay_status == 201
assert first == replay, 'The complete response must match, including receivedAt'
assert changed_status == 409
read_status, read = call('GET', url + '/' + first['fileId'])
assert read_status == 200 and read == first
print('SEQUENTIAL_IDEMPOTENCY_PASS')

# Use a new key to race on creation, rather than replaying an existing record.
parallel_key = 'parallel-' + str(uuid.uuid4())
gate = Barrier(count)
def create(_):
    gate.wait(timeout=15)
    return call('POST', url, parallel_key, body)

with concurrent.futures.ThreadPoolExecutor(max_workers=count) as pool:
    responses = list(pool.map(create, range(count)))
statuses = Counter(status for status, _ in responses)
assert statuses == Counter({201: count}), statuses
assert all(response == responses[0][1] for _, response in responses)
evidence_status, evidence = call('GET', url + '/_evidence/idempotency/' +
                               urllib.parse.quote(parallel_key, safe=''))
assert evidence_status == 200 and evidence['databaseRecordCount'] == 1
assert evidence['fileId'] == responses[0][1]['fileId']
print('SIMULTANEOUS_STATUS_COUNTS', dict(statuses))
print('SIMULTANEOUS_UNIQUE_FILE_IDS', len({response['fileId'] for _, response in responses}))
print('DATABASE_EVIDENCE', json.dumps(evidence, sort_keys=True))
print('CHALLENGE_2_IDEMPOTENCY_PASS')
PY

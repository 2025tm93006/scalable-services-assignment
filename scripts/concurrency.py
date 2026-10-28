#!/usr/bin/env python3
"""Challenge 1: fire parallel requests at the two contended resources and prove the
database, not the application, decides who wins.

Part 1 races eight identical quarantine creations for ONE fileId (the contended
resource is the record itself, guarded by the unique fileId index).
Part 2 fires twenty parallel scans against a scanner capacity of two slots (the
contended resource is the slot table, claimed by one atomic conditional update).
Part 3 reads the database back and proves the rejected scans were delayed, not lost.

Quarantine and Scanner are started by this script on their own ports against
temporary file-based H2 databases, so a run is repeatable and leaves no state.
"""

import json
from pathlib import Path
import sys
import tempfile
import threading
import time
import uuid
from datetime import datetime, timezone

import services

CREATION_CONTENDERS = 8
SCAN_CONTENDERS = 20
CAPACITY = 2
SCANNER_SLOW_MILLIS = 2000
CORRELATION = 'challenge1-' + str(uuid.uuid4())


def call(base, method, path, payload=None, timeout=30):
    """Every request of this run carries one correlation ID, so the service logs can
    be followed back to it."""
    return services.call(base, method, path, payload, timeout, CORRELATION)


def fire_parallel(requests):
    """Runs prepared (base, method, path, payload) tuples against a barrier so the
    server receives them as close to the same instant as the client can manage."""
    barrier = threading.Barrier(len(requests))
    results = [None] * len(requests)

    def worker(index, request):
        barrier.wait()
        results[index] = call(*request)

    threads = [threading.Thread(target=worker, args=(index, request), daemon=True)
               for index, request in enumerate(requests)]
    started = time.monotonic()
    for thread in threads:
        thread.start()
    for thread in threads:
        thread.join(timeout=120)
    return results, time.monotonic() - started


def tally(results):
    counts = {}
    for status, body in results:
        message = body.get('message') if isinstance(body, dict) else None
        key = (status, message)
        counts[key] = counts.get(key, 0) + 1
    return counts


def report(results, elapsed, expected):
    print(f'PARALLEL_REQUESTS {len(results)} ELAPSED_MILLIS {int(elapsed * 1000)}', flush=True)
    for (status, message), count in sorted(tally(results).items(), key=lambda item: -item[1]):
        print(f'  RESULT count={count} status={status} message={message}', flush=True)
    for status, message, count in expected:
        actual = tally(results).get((status, message), 0)
        assert actual == count, f'expected {count} responses {status}/{message}, saw {actual}'
    statuses = [status for status, _ in results]
    assert 500 not in statuses, 'a contended write must never surface as a 500'


def main():
    services.require(services.QUARANTINE_JAR)
    services.require(services.SCANNER_JAR)
    java = services.java_executable()

    print('COMMAND python3 scripts/concurrency.py', flush=True)
    print('TIMESTAMP', datetime.now(timezone.utc).isoformat(), flush=True)
    print(f'CORRELATION_ID {CORRELATION}', flush=True)
    print(f'CONFIG capacity={CAPACITY} scannerDelayMillis={SCANNER_SLOW_MILLIS} '
          f'creationContenders={CREATION_CONTENDERS} scanContenders={SCAN_CONTENDERS}', flush=True)

    with tempfile.TemporaryDirectory(prefix='challenge1-') as folder:
        directory = Path(folder)
        scanner_port, quarantine_port = services.free_port(), services.free_port()
        scanner_base = services.base_url(scanner_port)
        quarantine_base = services.base_url(quarantine_port)
        scanner_log = directory / 'scanner.log'
        quarantine_log = directory / 'quarantine.log'
        scanner = quarantine = None
        try:
            scanner = services.start(java, services.SCANNER_JAR, scanner_port, {
                'spring.datasource.url': 'jdbc:h2:file:' + str(directory / 'scanner'),
                'spring.datasource.username': 'challenge1', 'spring.datasource.password': 'test_only',
                'scanner.slow-millis': SCANNER_SLOW_MILLIS}, scanner_log)
            quarantine = services.start(java, services.QUARANTINE_JAR, quarantine_port, {
                'spring.datasource.url': 'jdbc:h2:file:' + str(directory / 'quarantine'),
                'spring.datasource.username': 'challenge1', 'spring.datasource.password': 'test_only',
                'quarantine.max-concurrent-scans': CAPACITY,
                'scanner.base-url': scanner_base}, quarantine_log)

            _, before = call(quarantine_base, 'GET', '/api/v1/quarantine/_evidence/summary')
            print('CAPACITY_BEFORE', json.dumps(before['scannerSlots'], sort_keys=True),
                  f"declaredCapacity={before['capacity']}", flush=True)
            assert before['capacity'] == CAPACITY and before['scannerSlots']['FREE'] == CAPACITY

            # --- Part 1: the unique fileId index decides a creation race ---
            contested_file = str(uuid.uuid4())
            body = {'fileId': contested_file, 'scannerMode': 'SLOW'}
            print(f'\nPART_1_SIMULTANEOUS_CREATIONS fileId={contested_file}', flush=True)
            creations, elapsed = fire_parallel(
                [(quarantine_base, 'POST', '/api/v1/quarantine', body)] * CREATION_CONTENDERS)
            report(creations, elapsed,
                   [(201, None, 1), (409, 'File is already quarantined', CREATION_CONTENDERS - 1)])
            _, records = call(quarantine_base, 'GET', '/api/v1/quarantine/_evidence/summary')
            print('PART_1_DATABASE total={total} QUARANTINED={byState[QUARANTINED]}'.format(**records),
                  flush=True)
            assert records['total'] == 1, 'the race must leave exactly one record'

            # --- Part 2: the slot table decides a scan burst ---
            print(f'\nPART_2_PARALLEL_SCANS capacity={CAPACITY} files={SCAN_CONTENDERS}', flush=True)
            ids = []
            for _ in range(SCAN_CONTENDERS):
                status, created = call(quarantine_base, 'POST', '/api/v1/quarantine',
                                       {'fileId': str(uuid.uuid4()), 'scannerMode': 'SLOW'})
                assert status == 201, (status, created)
                ids.append(created['id'])
            scans, elapsed = fire_parallel(
                [(quarantine_base, 'POST', f'/api/v1/quarantine/{record_id}/scan', None)
                 for record_id in ids])
            report(scans, elapsed,
                   [(200, None, CAPACITY),
                    (409, 'Scanner capacity exhausted', SCAN_CONTENDERS - CAPACITY)])

            # --- Part 3: the rejected scans were delayed, not lost ---
            _, during = call(quarantine_base, 'GET', '/api/v1/quarantine/_evidence/summary')
            print('PART_3_AFTER_BURST', json.dumps(during, sort_keys=True), flush=True)
            # The record left over from Part 1 was never scanned, so it is still
            # QUARANTINED alongside the eighteen the burst refused.
            expected_queued = SCAN_CONTENDERS - CAPACITY + 1
            assert during['byState']['SCANNING'] == 0, during
            assert during['byState']['QUARANTINED'] == expected_queued, during
            assert during['scannerSlots'] == {'FREE': CAPACITY, 'HELD': 0}, 'slots must be back'

            settled = sum(1 for status, _ in scans if status == 200)
            print(f'PART_3_BURST_ACCEPTED {settled} REJECTED {SCAN_CONTENDERS - settled}', flush=True)
            for record_id, (status, _) in zip(ids, scans):
                if status == 200:
                    continue  # already settled by the burst
                retry_status, response = call(quarantine_base,
                                              'POST', f'/api/v1/quarantine/{record_id}/scan')
                assert retry_status == 200, (record_id, retry_status, response)
            _, final = call(quarantine_base, 'GET', '/api/v1/quarantine/_evidence/summary')
            print('PART_3_FINALLY', json.dumps(final, sort_keys=True), flush=True)
            assert final['byState']['CLEAN'] == SCAN_CONTENDERS, final
            assert final['byState']['QUARANTINED'] == 1, final
            assert final['total'] == SCAN_CONTENDERS + 1, final

            print('\nQUARANTINE_LOG_EVIDENCE slot claims and rejections', flush=True)
            for line in services.lines(quarantine_log, 'scanner_slot_acquired',
                                       'scanner_capacity_rejected'):
                print(' ', line, flush=True)
            print('SCANNER_LOG_EVIDENCE one scan_completed per file, no duplicate scans', flush=True)
            completions = services.lines(scanner_log, 'scan_completed')
            for line in completions[:3]:
                print(' ', line, flush=True)
            print(f'  SCANNER_SCAN_COMPLETED_TOTAL {len(completions)} for '
                  f'{SCAN_CONTENDERS} files', flush=True)
            assert len(completions) == SCAN_CONTENDERS, \
                f'Scanner ran {len(completions)} scans for {SCAN_CONTENDERS} files'
        finally:
            services.stop(quarantine)
            services.stop(scanner)
    print('\nCHALLENGE_1_PASS exactly', CAPACITY, 'of', SCAN_CONTENDERS,
          'scans ran at a time and one record existed per file', flush=True)


if __name__ == '__main__':
    try:
        main()
    except AssertionError as failed:
        print('CHALLENGE_1_FAIL', failed, file=sys.stderr, flush=True)
        sys.exit(1)

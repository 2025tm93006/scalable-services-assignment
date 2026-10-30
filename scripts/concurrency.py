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

# ── terminal colors ───────────────────────────────────────────────────────────
GREEN  = '\033[0;32m'; RED    = '\033[0;31m'; CYAN  = '\033[0;36m'
YELLOW = '\033[1;33m'; BOLD   = '\033[1m';    DIM   = '\033[2m'; RESET = '\033[0m'

def banner(title):
    width = 60
    print(f"\n{BOLD}{CYAN}{'═' * width}{RESET}")
    print(f"{BOLD}{CYAN}  {title}{RESET}")
    print(f"{BOLD}{CYAN}{'═' * width}{RESET}\n")

def section(title):
    print(f"\n{CYAN}{BOLD}── {title} ──{RESET}")

def ok(msg):   print(f"  {GREEN}✔{RESET}  {msg}")
def fail_msg(msg): print(f"  {RED}✘{RESET}  {RED}{msg}{RESET}")
def info(msg): print(f"  {DIM}{msg}{RESET}")

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
    print(f"  {BOLD}Parallel requests: {len(results)}  elapsed: {int(elapsed * 1000)} ms{RESET}", flush=True)
    for (status, message), count in sorted(tally(results).items(), key=lambda item: -item[1]):
        color = GREEN if status < 400 else YELLOW if status == 409 else RED
        print(f"    {color}HTTP {status}{RESET}  ×{count}  {DIM}{message or ''}{RESET}", flush=True)
    for status, message, count in expected:
        actual = tally(results).get((status, message), 0)
        assert actual == count, f'expected {count} responses {status}/{message}, saw {actual}'
    statuses = [status for status, _ in results]
    assert 500 not in statuses, 'a contended write must never surface as a 500'


def main():
    services.require(services.QUARANTINE_JAR)
    services.require(services.SCANNER_JAR)
    java = services.java_executable()

    banner("CHALLENGE 1 — Concurrency Control")
    info(f"capacity={CAPACITY}  scanner delay={SCANNER_SLOW_MILLIS} ms")
    info(f"creation contenders={CREATION_CONTENDERS}  scan contenders={SCAN_CONTENDERS}")
    info(f"correlationId={CORRELATION}")
    info(f"timestamp={datetime.now(timezone.utc).isoformat()}")

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
            slots = before['scannerSlots']
            info(f"capacity={before['capacity']}  slots before: FREE={slots['FREE']} HELD={slots.get('HELD',0)}")
            assert before['capacity'] == CAPACITY and before['scannerSlots']['FREE'] == CAPACITY

            # --- Part 1: the unique fileId index decides a creation race ---
            section("Part 1 — Creation race: 8 threads, 1 fileId, database picks the winner")
            contested_file = str(uuid.uuid4())
            body = {'fileId': contested_file, 'scannerMode': 'SLOW'}
            info(f"fileId={contested_file}")
            creations, elapsed = fire_parallel(
                [(quarantine_base, 'POST', '/api/v1/quarantine', body)] * CREATION_CONTENDERS)
            report(creations, elapsed,
                   [(201, None, 1), (409, 'File is already quarantined', CREATION_CONTENDERS - 1)])
            _, records = call(quarantine_base, 'GET', '/api/v1/quarantine/_evidence/summary')
            ok(f"Database has exactly 1 record  (total={records['total']}  QUARANTINED={records['byState']['QUARANTINED']})")
            assert records['total'] == 1, 'the race must leave exactly one record'

            # --- Part 2: the slot table decides a scan burst ---
            section(f"Part 2 — Scan burst: {SCAN_CONTENDERS} parallel scans, capacity={CAPACITY}")
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
            section("Part 3 — Rejected scans are retried, not lost")
            _, during = call(quarantine_base, 'GET', '/api/v1/quarantine/_evidence/summary')
            info(f"After burst: {json.dumps(during['byState'])}  slots: {json.dumps(during['scannerSlots'])}")
            expected_queued = SCAN_CONTENDERS - CAPACITY + 1
            assert during['byState']['SCANNING'] == 0, during
            assert during['byState']['QUARANTINED'] == expected_queued, during
            assert during['scannerSlots'] == {'FREE': CAPACITY, 'HELD': 0}, 'slots must be back'

            settled = sum(1 for status, _ in scans if status == 200)
            info(f"Burst accepted={settled}  rejected={SCAN_CONTENDERS - settled}  (retrying rejected...)")
            for record_id, (status, _) in zip(ids, scans):
                if status == 200:
                    continue
                retry_status, response = call(quarantine_base,
                                              'POST', f'/api/v1/quarantine/{record_id}/scan')
                assert retry_status == 200, (record_id, retry_status, response)
            _, final = call(quarantine_base, 'GET', '/api/v1/quarantine/_evidence/summary')
            ok(f"All {SCAN_CONTENDERS} files eventually scanned CLEAN")
            ok(f"Slots restored: FREE={final['scannerSlots']['FREE']}  HELD={final['scannerSlots'].get('HELD',0)}")
            assert final['byState']['CLEAN'] == SCAN_CONTENDERS, final
            assert final['byState']['QUARANTINED'] == 1, final
            assert final['total'] == SCAN_CONTENDERS + 1, final

            section("Log evidence — slot claims and rejections")
            for line in services.lines(quarantine_log, 'scanner_slot_acquired',
                                       'scanner_capacity_rejected'):
                info(line)
            completions = services.lines(scanner_log, 'scan_completed')
            for line in completions[:3]:
                info(line)
            ok(f"Scanner ran {len(completions)} scans for {SCAN_CONTENDERS} files (no duplicates)")
            assert len(completions) == SCAN_CONTENDERS, \
                f'Scanner ran {len(completions)} scans for {SCAN_CONTENDERS} files'
        finally:
            services.stop(quarantine)
            services.stop(scanner)

    print(f"\n{GREEN}{BOLD}╔{'═'*58}╗{RESET}")
    print(f"{GREEN}{BOLD}║  CHALLENGE_1_PASS{' '*39}║{RESET}")
    print(f"{GREEN}{BOLD}║  {CAPACITY} of {SCAN_CONTENDERS} scans ran at a time, 1 record per file{' '*(58-len(str(CAPACITY))-len(str(SCAN_CONTENDERS))-37)}║{RESET}")
    print(f"{GREEN}{BOLD}╚{'═'*58}╝{RESET}\n", flush=True)


if __name__ == '__main__':
    try:
        main()
    except AssertionError as failed:
        print(f'\n{RED}{BOLD}  CHALLENGE_1_FAIL  {failed}{RESET}', file=sys.stderr, flush=True)
        sys.exit(1)

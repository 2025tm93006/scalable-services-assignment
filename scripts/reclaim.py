#!/usr/bin/env python3
"""Challenge 1, the part that a running system has to survive: a scan whose process is
killed must not leave the capacity table and the state machine inconsistent forever.

Three Quarantine starts on the same database prove it in order. The first one takes a
real SLOW scan and is SIGKILLed mid-call, so nothing gets a chance to clean up. The
second starts with the reclaim sweep switched off and shows the leak sitting in the
database: one slot still HELD, one record still SCANNING. The third starts the sweep and
shows both rows settled without any manual intervention, and that capacity works again.

Scanner runs the whole time; only Quarantine is stopped and started, and every start uses
the same temporary file database so the rows really are read back from disk.
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

CAPACITY = 1
SCANNER_SLOW_MILLIS = 8000
# Long enough that the in-flight scan is still waiting on Scanner when Quarantine dies,
# and still cannot time out afterwards and report a verdict for the abandoned record.
READ_TIMEOUT_MILLIS = 30000
SLOT_AGE_MILLIS = 1500
SWEEP_INTERVAL_MILLIS = 400
CORRELATION = 'reclaim-' + str(uuid.uuid4())


def call(base, method, path, payload=None, timeout=30):
    return services.call(base, method, path, payload, timeout, CORRELATION)


def summary(base):
    status, body = call(base, 'GET', '/api/v1/quarantine/_evidence/summary')
    assert status == 200, (status, body)
    return body


def slots_of(body):
    return body['scannerSlots']


def state_of(body, name):
    return body['byState'][name]


def check(condition, message, detail=None):
    print(f"  {'PASS' if condition else 'FAIL'} {message}"
          + ('' if detail is None else f': {detail}'), flush=True)
    assert condition, message


def fire_scan_in_background(base, record_id, results):
    def worker():
        try:
            results.append(call(base, 'POST', f'/api/v1/quarantine/{record_id}/scan', None, 40))
        except OSError as broken_pipe:
            results.append(('killed', str(broken_pipe)))
    threading.Thread(target=worker, daemon=True).start()


def main():
    services.require(services.QUARANTINE_JAR)
    services.require(services.SCANNER_JAR)
    java = services.java_executable()

    print('COMMAND python3 scripts/reclaim.py', flush=True)
    print('TIMESTAMP', datetime.now(timezone.utc).isoformat(), flush=True)
    print('CORRELATION_ID', CORRELATION, flush=True)
    print(f'CONFIG capacity={CAPACITY} scannerSlowMillis={SCANNER_SLOW_MILLIS} '
          f'readTimeoutMillis={READ_TIMEOUT_MILLIS} maxAttempts=1 '
          f'slotStaleMillis={SLOT_AGE_MILLIS} sweepIntervalMillis={SWEEP_INTERVAL_MILLIS}', flush=True)

    with tempfile.TemporaryDirectory(prefix='reclaim-') as folder:
        directory = Path(folder)
        scanner_port = services.free_port()
        quarantine_port = services.free_port()
        scanner_base = services.base_url(scanner_port)
        quarantine_base = services.base_url(quarantine_port)

        database = {
            'spring.datasource.url': 'jdbc:h2:file:' + str(directory / 'quarantine'),
            'spring.datasource.username': 'reclaim_test', 'spring.datasource.password': 'test_only',
            'spring.datasource.driver-class-name': 'org.h2.Driver',
        }
        shared = dict(database, **{
            'quarantine.max-concurrent-scans': CAPACITY,
            'scanner.base-url': scanner_base,
            'scanner.read-timeout-millis': READ_TIMEOUT_MILLIS,
            'scanner.max-attempts': 1,
        })
        quarantine = scanner = None
        abandoned = None
        try:
            scanner = services.start(java, services.SCANNER_JAR, scanner_port, dict(
                {
                    'spring.datasource.url': 'jdbc:h2:file:' + str(directory / 'scanner'),
                    'spring.datasource.username': 'reclaim_test',
                    'spring.datasource.password': 'test_only',
                    'spring.datasource.driver-class-name': 'org.h2.Driver',
                }, **{'scanner.slow-millis': SCANNER_SLOW_MILLIS}), directory / 'scanner.log')

            # --- Run 1: take a real slow scan and die in the middle of it ---
            quarantine = services.start(java, services.QUARANTINE_JAR, quarantine_port,
                                        shared, directory / 'quarantine-run-1.log')
            status, created = call(quarantine_base, 'POST', '/api/v1/quarantine',
                                  {'fileId': str(uuid.uuid4()), 'scannerMode': 'SLOW'})
            assert status == 201, (status, created)
            abandoned = created['id']
            in_flight = []
            fire_scan_in_background(quarantine_base, abandoned, in_flight)
            time.sleep(2)
            during = summary(quarantine_base)
            print('DURING_THE_SCAN', json.dumps(during, sort_keys=True), flush=True)
            print(f'KILLING_QUARANTINE_MID_SCAN heldSlots={slots_of(during)["HELD"]} '
                  f'scanningRecords={state_of(during, "SCANNING")}', flush=True)
            services.kill_hard(quarantine)
            quarantine = None

            # --- Run 2: sweep switched off, so the leak is shown as it really is ---
            quarantine = services.start(java, services.QUARANTINE_JAR, quarantine_port,
                                         dict(shared, **{'quarantine.reclaim-enabled': 'false'}),
                                         directory / 'quarantine-run-2.log')
            leaked = summary(quarantine_base)
            print('AFTER_RESTART_SWEEP_DISABLED', json.dumps(leaked, sort_keys=True), flush=True)
            print('\nLEAK_CONFIRMED_IN_THE_DATABASE', flush=True)
            check(slots_of(leaked)['HELD'] == 1, 'the killed scan still holds a capacity slot')
            check(state_of(leaked, 'SCANNING') == 1, 'the killed scan still leaves the record unsettled')
            status, still = call(quarantine_base, 'GET', '/api/v1/quarantine/' + abandoned)
            check(status == 200 and still['state'] == 'SCANNING',
                  'the record is stuck in SCANNING', still['state'])
            services.stop(quarantine)
            quarantine = None

            # --- Run 3: same database, sweep switched on with a short age ---
            quarantine = services.start(java, services.QUARANTINE_JAR, quarantine_port,
                                         dict(shared, **{
                                             'quarantine.reclaim-enabled': 'true',
                                             'quarantine.slot-stale-millis': SLOT_AGE_MILLIS,
                                             'quarantine.reclaim-interval-millis': SWEEP_INTERVAL_MILLIS}),
                                         directory / 'quarantine-run-3.log')
            deadline = time.monotonic() + 20
            reclaimed = None
            while time.monotonic() < deadline:
                reclaimed = summary(quarantine_base)
                if slots_of(reclaimed)['HELD'] == 0 and state_of(reclaimed, 'SCANNING') == 0:
                    break
                time.sleep(0.25)
            print('AFTER_RECLAIM_SWEEP', json.dumps(reclaimed, sort_keys=True), flush=True)
            print('\nRECLAIM_CONSISTENCY', flush=True)
            check(slots_of(reclaimed)['HELD'] == 0, 'the abandoned slot was returned to the table')
            check(slots_of(reclaimed)['FREE'] == CAPACITY, 'capacity is back to its declared size')
            check(state_of(reclaimed, 'SCANNING') == 0, 'no record is left in SCANNING')
            check(state_of(reclaimed, 'FAILED') == 1, 'the abandoned scan was settled as FAILED')
            status, settled = call(quarantine_base, 'GET', '/api/v1/quarantine/' + abandoned)
            check(status == 200 and settled['state'] == 'FAILED',
                  'the record reports FAILED without a second transition', settled['state'])
            check(settled['scannedAt'] is not None, 'the settlement is timestamped like any other')

            # A settled record stays settled, and the capacity is usable again.
            status, rescan = call(quarantine_base, 'POST', f'/api/v1/quarantine/{abandoned}/scan')
            check(status == 409 and rescan['message'] == 'File has already been scanned',
                  'the reclaimed record is still final', f'{status} {rescan["message"]}')

            print('\nCAPACITY_USABLE_AFTER_THE_SWEEP', flush=True)
            status, fresh = call(quarantine_base, 'POST', '/api/v1/quarantine',
                                 {'fileId': str(uuid.uuid4()), 'scannerMode': 'CLEAN'})
            assert status == 201, (status, fresh)
            started = time.monotonic()
            scan_status, scanned = call(quarantine_base, 'POST',
                                        f"/api/v1/quarantine/{fresh['id']}/scan")
            elapsed = int((time.monotonic() - started) * 1000)
            check(scan_status == 200 and scanned['state'] == 'CLEAN',
                  'a new scan runs on the reclaimed slot', f'status={scan_status} {elapsed} ms')

            log_lines = services.lines(directory / 'quarantine-run-3.log',
                                      'scan_reclaim', 'scan_reclaim_settled')
            print('\nRECLAIM_LOG', flush=True)
            for line in log_lines:
                print('  ' + line, flush=True)
            check(len(log_lines) >= 2, 'the sweep logged both the settle and the summary')
            warned = services.lines(directory / 'quarantine-run-3.log',
                                   'scan_reclaim_age_below_budget')
            check(len(warned) >= 1,
                  'the job warns that this demo age is below the retry budget',
                  warned[0] if warned else 'no warning')

            abandoned_by_scanner = in_flight[0] if in_flight else None
            print('KILLED_REQUEST_ANSWER', json.dumps(abandoned_by_scanner, sort_keys=True), flush=True)
        finally:
            services.stop(quarantine)
            services.stop(scanner)

    print('\nRECLAIM_PASS a killed scan left a HELD slot and a SCANNING record, and the '
          'sweep returned both without touching the database by hand', flush=True)


if __name__ == '__main__':
    try:
        main()
    except AssertionError as failed:
        print('RECLAIM_FAIL', failed, file=sys.stderr, flush=True)
        sys.exit(1)

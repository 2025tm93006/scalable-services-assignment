#!/usr/bin/env python3
"""Challenge 3A: the downstream Scanner service is slow, then stopped, then back.

Quarantine is configured with a deliberately small resilience budget for the demo:
500 ms read timeout, three attempts, 200 ms backoff base plus up to 150 ms jitter.

Part A  Scanner answers slower than the read timeout. The first attempts time out,
        the retry replays the scan that is already running, and Scanner stores ONE
        attempt for the file - so retrying did not duplicate the work.
Part B  Scanner is stopped. Every attempt fails, the budget runs out and Quarantine
        settles the file as FAILED instead of hanging or returning a 500, then gives
        the capacity slot back.
Part C  Scanner is started again. A new file scans cleanly and the FAILED record
        stays FAILED: a settled record is never transitioned twice.
Part D  The same scan request sent straight to Scanner twice returns the identical
        stored result, which is what makes Part A's retries safe (links to
        Challenge 2's idempotency requirement).

Scanner and Quarantine run on their own ports against temporary file-based H2
databases, so the script is repeatable and leaves nothing behind.
"""

import json
from pathlib import Path
import sys
import tempfile
import time
import uuid
from datetime import datetime, timezone

import services

SLOW_MILLIS = 1500
READ_TIMEOUT_MILLIS = 500
MAX_ATTEMPTS = 3
RETRY_BASE_MILLIS = 200
RETRY_JITTER_MILLIS = 150

failures = []


def check(condition, description):
    print(('  PASS ' if condition else '  FAIL ') + description, flush=True)
    if not condition:
        failures.append(description)


def scan_record(quarantine, mode):
    """Creates a fresh quarantine record and returns its id."""
    status, created = services.call(quarantine, 'POST', '/api/v1/quarantine',
                                    {'fileId': str(uuid.uuid4()), 'scannerMode': mode})
    assert status == 201, (status, created)
    return created['id'], created['fileId']


def timed_scan(quarantine, record_id):
    started = time.monotonic()
    status, body = services.call(quarantine, 'POST', f'/api/v1/quarantine/{record_id}/scan')
    return status, body, int((time.monotonic() - started) * 1000)


def scanner_attempts(scanner):
    status, summary = services.call(scanner, 'GET', '/api/v1/scans/_evidence/summary')
    assert status == 200, (status, summary)
    return summary


def main():
    services.require(services.QUARANTINE_JAR)
    services.require(services.SCANNER_JAR)
    java = services.java_executable()

    print('COMMAND python3 scripts/resilience.py', flush=True)
    print('TIMESTAMP', datetime.now(timezone.utc).isoformat(), flush=True)
    print(f'CONFIG scannerReadTimeoutMillis={READ_TIMEOUT_MILLIS} maxAttempts={MAX_ATTEMPTS} '
          f'retryBaseMillis={RETRY_BASE_MILLIS} retryJitterMillis={RETRY_JITTER_MILLIS} '
          f'scannerSlowMillis={SLOW_MILLIS}', flush=True)

    with tempfile.TemporaryDirectory(prefix='challenge3a-') as folder:
        directory = Path(folder)
        quarantine_log = directory / 'quarantine.log'
        scanner_port = services.free_port()
        quarantine_port = services.free_port()
        scanner = f'http://127.0.0.1:{scanner_port}'
        quarantine = f'http://127.0.0.1:{quarantine_port}'
        quarantine_settings = {
            'spring.datasource.url': 'jdbc:h2:file:' + str(directory / 'quarantine'),
            'spring.datasource.username': 'challenge3a', 'spring.datasource.password': 'test_only',
            'scanner.base-url': scanner,
            'scanner.connect-timeout-millis': 300,
            'scanner.read-timeout-millis': READ_TIMEOUT_MILLIS,
            'scanner.max-attempts': MAX_ATTEMPTS,
            'scanner.retry-base-millis': RETRY_BASE_MILLIS,
            'scanner.retry-jitter-millis': RETRY_JITTER_MILLIS,
            'quarantine.max-concurrent-scans': 2}
        process = None
        scanner_process = None
        try:
            process = services.start(java, services.QUARANTINE_JAR, quarantine_port,
                                     quarantine_settings, quarantine_log)
            scanner_log = directory / 'scanner-1.log'
            scanner_process = services.start(java, services.SCANNER_JAR, scanner_port, {
                'spring.datasource.url': 'jdbc:h2:file:' + str(directory / 'scanner'),
                'spring.datasource.username': 'challenge3a',
                'spring.datasource.password': 'test_only',
                'scanner.slow-millis': SLOW_MILLIS}, scanner_log)

            # --- Part A: a slow call is cut off, retried and replayed ---
            print('\nPART_A_SLOW_DOWNSTREAM timeout_then_retry_no_duplicate_effect', flush=True)
            record_id, file_id = scan_record(quarantine, 'SLOW')
            before = scanner_attempts(scanner)['totalAttempts']
            status, settled, elapsed = timed_scan(quarantine, record_id)
            after = scanner_attempts(scanner)['totalAttempts']
            print(f'  RESPONSE status={status} state={settled["state"]} elapsedMillis={elapsed}',
                  flush=True)
            check(status == 200 and settled['state'] == 'CLEAN',
                  'the retried attempt still produced Scanner\'s verdict')
            check(elapsed > SLOW_MILLIS,
                  f'caller waited past the {READ_TIMEOUT_MILLIS} ms timeout because a retry '
                  f'completed the scan ({elapsed} ms)')
            check(after - before == 1,
                  f'Scanner stored {after - before} attempt for this file, expected exactly 1')
            retry_lines = services.lines(quarantine_log, 'scanner_attempt_failed', 'scanner_retry')
            retry_lines = [line for line in retry_lines if f'fileId={file_id}' in line]
            for line in retry_lines:
                print('  QUARANTINE_LOG ' + line, flush=True)
            delays = [int(line.split('delayMillis=')[1].split()[0])
                      for line in retry_lines if 'scanner_retry' in line]
            check(len(delays) >= 1, 'the failed attempt was retried with a logged delay')
            check(all(delay >= RETRY_BASE_MILLIS for delay in delays),
                  f'backoff delays {delays} are at least the {RETRY_BASE_MILLIS} ms base')
            check(all('extracting response' not in line for line in retry_lines),
                  'a slow call is reported as a timeout, not as a response-parsing error')
            failures_logged = [line for line in retry_lines if 'scanner_attempt_failed' in line]
            check(all('timeout' in line.lower() for line in failures_logged),
                  'every logged failure of Part A is a timeout: ' +
                  ', '.join(sorted({line.split('error=')[1] for line in failures_logged})))

            # --- Part B: the dependency is stopped ---
            print('\nPART_B_STOPPED_DOWNSTREAM graceful_failure', flush=True)
            services.stop(scanner_process)
            deadline = time.monotonic() + 20
            while services.is_listening(scanner_port) and time.monotonic() < deadline:
                time.sleep(0.2)
            check(not services.is_listening(scanner_port),
                  f'Scanner is no longer listening on port {scanner_port}')
            failed_record_id, failed_file_id = scan_record(quarantine, 'CLEAN')
            status, failed, elapsed = timed_scan(quarantine, failed_record_id)
            print(f'  RESPONSE status={status} state={failed["state"]} elapsedMillis={elapsed}',
                  flush=True)
            check(status == 200, 'Quarantine answered instead of failing the request')
            check(failed['state'] == 'FAILED', 'the file settled as FAILED')
            attempts = services.lines(quarantine_log, f'scanner_call fileId={failed_file_id} attempt=')
            check(len(attempts) == MAX_ATTEMPTS,
                  f'{len(attempts)} attempts were logged, expected {MAX_ATTEMPTS}')
            for line in attempts:
                print('  QUARANTINE_LOG ' + line, flush=True)
            refusals = [line for line in services.lines(quarantine_log, 'scanner_attempt_failed')
                        if f'fileId={failed_file_id}' in line]
            for line in refusals:
                print('  QUARANTINE_LOG ' + line, flush=True)
            check(len(refusals) == MAX_ATTEMPTS
                  and all('timeout' not in line.lower() for line in refusals)
                  and all('I/O error' in line for line in refusals),
                  'every attempt failed at the connection, not on the clock')
            outage = services.lines(quarantine_log, 'scan_failed_downstream_unavailable')
            for line in outage[-2:]:
                print('  QUARANTINE_LOG ' + line, flush=True)
            _, evidence = services.call(quarantine, 'GET', '/api/v1/quarantine/_evidence/summary')
            print('  EVIDENCE ' + json.dumps(evidence, sort_keys=True), flush=True)
            check(evidence['scannerSlots'] == {'FREE': 2, 'HELD': 0},
                  'the capacity slot was released even though the scan failed')

            # --- Part C: the dependency is back ---
            print('\nPART_C_DOWNSTREAM_RESTARTED service_kept_working', flush=True)
            restarted_log = directory / 'scanner-2.log'
            scanner_process = services.start(java, services.SCANNER_JAR, scanner_port, {
                'spring.datasource.url': 'jdbc:h2:file:' + str(directory / 'scanner'),
                'spring.datasource.username': 'challenge3a',
                'spring.datasource.password': 'test_only',
                'scanner.slow-millis': SLOW_MILLIS}, restarted_log)
            status, healthy = services.call(quarantine, 'GET', '/health')
            check(status == 200 and healthy['status'] == 'UP', 'Quarantine stayed healthy throughout')
            record_id, _ = scan_record(quarantine, 'CLEAN')
            status, recovered, elapsed = timed_scan(quarantine, record_id)
            print(f'  RESPONSE status={status} state={recovered["state"]} elapsedMillis={elapsed}',
                  flush=True)
            check(status == 200 and recovered['state'] == 'CLEAN',
                  'a new file scans cleanly once Scanner is back')
            repeat_status, repeat = services.call(
                quarantine, 'POST', f'/api/v1/quarantine/{failed_record_id}/scan')
            check(repeat_status == 409,
                  f'the record that failed during the outage stays settled: {repeat_status} '
                  f'{repeat.get("message")}')

            # --- Part D: why the retries in Part A are safe ---
            print('\nPART_D_DOWNSTREAM_IDEMPOTENCY retry_replays_the_stored_result', flush=True)
            replay_file = str(uuid.uuid4())
            first_status, first = services.call(scanner, 'POST', '/api/v1/scans',
                                                {'fileId': replay_file, 'mode': 'CLEAN'})
            before = scanner_attempts(scanner)['totalAttempts']
            second_status, second = services.call(scanner, 'POST', '/api/v1/scans',
                                                  {'fileId': replay_file, 'mode': 'CLEAN'})
            after = scanner_attempts(scanner)['totalAttempts']
            check(first_status == 200 and second_status == 200, 'both calls returned the result')
            check(first['completedAt'] == second['completedAt'],
                  f'same completedAt replayed: {first["completedAt"]} == {second["completedAt"]}')
            check(after == before, f'totalAttempts stayed at {after}; the repeat stored nothing')
            print('  SCANNER ' + json.dumps(second, sort_keys=True), flush=True)

            print('\nQUARANTINE_CLIENT_LOG the configured budget and every attempt', flush=True)
            for line in services.lines(quarantine_log, 'scanner_client_configured')[:1]:
                print('  ' + line, flush=True)
            for line in services.lines(quarantine_log, 'scanner_attempt_failed')[-4:]:
                print('  ' + line, flush=True)
        except BaseException as crashed:
            print('SCRIPT_ERROR', type(crashed).__name__, crashed, file=sys.stderr, flush=True)
            for line in services.lines(quarantine_log, 'scanner_', 'scan_')[-15:]:
                print('  QUARANTINE_LOG ' + line, file=sys.stderr, flush=True)
            raise
        finally:
            services.stop(scanner_process)
            services.stop(process)

    if failures:
        print(f'\nCHALLENGE_3A_FAIL {len(failures)} checks did not hold', file=sys.stderr, flush=True)
        for description in failures:
            print('  ' + description, file=sys.stderr, flush=True)
        return 1
    print('\nCHALLENGE_3A_PASS timeout, retry with backoff and jitter, and graceful '
          'failure were all observed, and no retry produced a duplicate', flush=True)
    return 0


if __name__ == '__main__':
    sys.exit(main())

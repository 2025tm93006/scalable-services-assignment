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
Part E  The Challenge 3A bonus, on a second Quarantine started with the circuit breaker
        enabled: once two calls have each spent their whole retry budget the circuit
        opens, the next scan is refused WITHOUT a single HTTP attempt and is far cheaper,
        and one probe after the cooldown closes it again once Scanner is back.

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

def info(msg): print(f"  {DIM}{msg}{RESET}", flush=True)

SLOW_MILLIS = 1500
READ_TIMEOUT_MILLIS = 500
MAX_ATTEMPTS = 3
RETRY_BASE_MILLIS = 200
RETRY_JITTER_MILLIS = 150
# Part E budget: two failed calls of two attempts each, then a 2.5 s cooldown.
CB_FAILURE_THRESHOLD = 2
CB_ATTEMPTS = 2
CB_OPEN_MILLIS = 2500

failures = []


def check(condition, description):
    color = GREEN if condition else RED
    mark  = '✔' if condition else '✘'
    print(f"  {color}{mark}{RESET}  {description}", flush=True)
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

    banner("CHALLENGE 3A — Resilience: Timeouts, Retries & Circuit Breaker")
    info(f"readTimeout={READ_TIMEOUT_MILLIS} ms  maxAttempts={MAX_ATTEMPTS}  "
         f"retryBase={RETRY_BASE_MILLIS} ms  jitter={RETRY_JITTER_MILLIS} ms  "
         f"scannerSlow={SLOW_MILLIS} ms")
    info(f"timestamp={datetime.now(timezone.utc).isoformat()}")

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
        breaker_process = None
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
            section("Part A — Slow downstream: timeout → retry → no duplicate effect")
            record_id, file_id = scan_record(quarantine, 'SLOW')
            before = scanner_attempts(scanner)['totalAttempts']
            status, settled, elapsed = timed_scan(quarantine, record_id)
            after = scanner_attempts(scanner)['totalAttempts']
            print(f"  {DIM}status={status}  state={settled['state']}  elapsed={elapsed} ms{RESET}", flush=True)
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
                info("QUARANTINE LOG  " + line)
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
            section("Part B — Scanner stopped: budget exhausted → FAILED, slot released")
            services.stop(scanner_process)
            deadline = time.monotonic() + 20
            while services.is_listening(scanner_port) and time.monotonic() < deadline:
                time.sleep(0.2)
            check(not services.is_listening(scanner_port),
                  f'Scanner is no longer listening on port {scanner_port}')
            failed_record_id, failed_file_id = scan_record(quarantine, 'CLEAN')
            status, failed, elapsed = timed_scan(quarantine, failed_record_id)
            print(f"  {DIM}status={status}  state={failed['state']}  elapsed={elapsed} ms{RESET}", flush=True)
            check(status == 200, 'Quarantine answered instead of failing the request')
            check(failed['state'] == 'FAILED', 'the file settled as FAILED')
            attempts = services.lines(quarantine_log, f'scanner_call fileId={failed_file_id} attempt=')
            check(len(attempts) == MAX_ATTEMPTS,
                  f'{len(attempts)} attempts were logged, expected {MAX_ATTEMPTS}')
            for line in attempts:
                info("QUARANTINE LOG  " + line)
            refusals = [line for line in services.lines(quarantine_log, 'scanner_attempt_failed')
                        if f'fileId={failed_file_id}' in line]
            for line in refusals:
                info("QUARANTINE LOG  " + line)
            check(len(refusals) == MAX_ATTEMPTS
                  and all('timeout' not in line.lower() for line in refusals)
                  and all('I/O error' in line for line in refusals),
                  'every attempt failed at the connection, not on the clock')
            outage = services.lines(quarantine_log, 'scan_failed_downstream_unavailable')
            for line in outage[-2:]:
                info("QUARANTINE LOG  " + line)
            _, evidence = services.call(quarantine, 'GET', '/api/v1/quarantine/_evidence/summary')
            info(f"Evidence: {json.dumps(evidence, sort_keys=True)}")
            check(evidence['scannerSlots'] == {'FREE': 2, 'HELD': 0},
                  'the capacity slot was released even though the scan failed')

            # --- Part C: the dependency is back ---
            section("Part C — Scanner restarted: new scans succeed, FAILED stays FAILED")
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
            info(f"status={status}  state={recovered['state']}  elapsed={elapsed} ms")
            check(status == 200 and recovered['state'] == 'CLEAN',
                  'a new file scans cleanly once Scanner is back')
            repeat_status, repeat = services.call(
                quarantine, 'POST', f'/api/v1/quarantine/{failed_record_id}/scan')
            check(repeat_status == 409,
                  f'the record that failed during the outage stays settled: {repeat_status} '
                  f'{repeat.get("message")}')

            # --- Part D: why the retries in Part A are safe ---
            section("Part D — Scanner idempotency: replay returns stored result, no duplicate")
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
            info("SCANNER  " + json.dumps(second, sort_keys=True))

            section("Quarantine client log — configured budget and attempt trail")
            for line in services.lines(quarantine_log, 'scanner_client_configured')[:1]:
                info(line)
            for line in services.lines(quarantine_log, 'scanner_attempt_failed')[-4:]:
                info(line)

            # --- Part E: the bonus - stop calling a Scanner that is known to be down ---
            section("Part E (bonus) — Circuit Breaker: OPEN skips HTTP, HALF_OPEN probes")
            services.stop(scanner_process)
            scanner_process = None
            deadline = time.monotonic() + 20
            while services.is_listening(scanner_port) and time.monotonic() < deadline:
                time.sleep(0.2)
            check(not services.is_listening(scanner_port),
                  f'Scanner is stopped again on port {scanner_port}')

            breaker_port = services.free_port()
            breaker_base = f'http://127.0.0.1:{breaker_port}'
            breaker_log = directory / 'quarantine-breaker.log'
            breaker_process = services.start(java, services.QUARANTINE_JAR, breaker_port, dict(
                quarantine_settings, **{
                    'spring.datasource.url': 'jdbc:h2:file:' + str(directory / 'quarantine-breaker'),
                    'scanner.base-url': f'http://127.0.0.1:{scanner_port}',
                    'scanner.max-attempts': CB_ATTEMPTS,
                    'scanner.retry-jitter-millis': 0,
                    'scanner.circuit-breaker.enabled': 'true',
                    'scanner.circuit-breaker.failure-threshold': CB_FAILURE_THRESHOLD,
                    'scanner.circuit-breaker.open-millis': CB_OPEN_MILLIS}), breaker_log)
            configured = services.lines(breaker_log, 'scanner_client_configured')
            check(len(configured) == 1
                  and 'circuitBreakerEnabled=true' in configured[0]
                  and f'circuitFailureThreshold={CB_FAILURE_THRESHOLD}' in configured[0]
                  and f'circuitOpenMillis={CB_OPEN_MILLIS}' in configured[0],
                  'this Quarantine started with the breaker on: threshold '
                  f'{CB_FAILURE_THRESHOLD}, cooldown {CB_OPEN_MILLIS} ms')
            info("BREAKER LOG  " + (configured[0] if configured else 'no config line'))
            main_config = services.lines(quarantine_log, 'scanner_client_configured')
            check(bool(main_config) and 'circuitBreakerEnabled=false' in main_config[0],
                  'the instance that produced Parts A-D kept the breaker off, so that '
                  'evidence is unchanged')

            durations = []
            refused_id = None
            for step in range(CB_FAILURE_THRESHOLD + 1):
                record_id, file_id = scan_record(breaker_base, 'CLEAN')
                status, settled, elapsed = timed_scan(breaker_base, record_id)
                durations.append(elapsed)
                attempts = services.lines(breaker_log, f'scanner_call fileId={file_id} attempt=')
                info(f"step={step}  status={status}  state={settled['state']}  "
                     f"elapsed={elapsed} ms  httpAttempts={len(attempts)}")
                check(status == 200 and settled['state'] == 'FAILED',
                      f'step {step} settled FAILED either way, so the caller still gets a state')
                if step < CB_FAILURE_THRESHOLD:
                    check(len(attempts) == CB_ATTEMPTS,
                          f'step {step} spent its whole budget of {CB_ATTEMPTS} attempts')
                else:
                    refused_id = file_id
                    check(len(attempts) == 0,
                          'the call after the threshold made no HTTP attempt at all')

            refused_ms = durations[CB_FAILURE_THRESHOLD]
            check(all(refused_ms < duration for duration in durations[:CB_FAILURE_THRESHOLD]),
                  f'the refused call cost {refused_ms} ms against '
                  f'{durations[:CB_FAILURE_THRESHOLD]} ms for the real attempts')
            for line in services.lines(breaker_log, 'scanner_circuit'):
                info("BREAKER LOG  " + line)
            check(any(f'fileId={refused_id}' in line for line in
                      services.lines(breaker_log, 'scanner_circuit_refused')),
                  'the open circuit logged the refusal it made')
            _, breaker_evidence = services.call(breaker_base, 'GET',
                                                '/api/v1/quarantine/_evidence/summary')
            info(f"Evidence: {json.dumps(breaker_evidence, sort_keys=True)}")
            check(breaker_evidence['scannerSlots'] == {'FREE': 2, 'HELD': 0},
                  'refused scans gave their capacity slots back')

            restarted_log = directory / 'scanner-3.log'
            scanner_process = services.start(java, services.SCANNER_JAR, scanner_port, {
                'spring.datasource.url': 'jdbc:h2:file:' + str(directory / 'scanner'),
                'spring.datasource.username': 'challenge3a',
                'spring.datasource.password': 'test_only',
                'scanner.slow-millis': SLOW_MILLIS}, restarted_log)
            time.sleep(CB_OPEN_MILLIS / 1000.0 + 0.3)
            record_id, probe_file = scan_record(breaker_base, 'CLEAN')
            status, probe, elapsed = timed_scan(breaker_base, record_id)
            info(f"PROBE  status={status}  state={probe['state']}  elapsed={elapsed} ms")
            check(status == 200 and probe['state'] == 'CLEAN',
                  'one probe after the cooldown reached Scanner and got a verdict')
            check(len(services.lines(breaker_log, f'scanner_call fileId={probe_file}')) == 1,
                  'the probe was one call, not the whole queue')
            closed = services.lines(breaker_log, 'scanner_circuit_closed')
            check(len(closed) >= 1, 'the circuit closed again after the successful probe')
            for line in services.lines(breaker_log, 'scanner_circuit_tripped',
                                       'scanner_circuit_probe_allowed', 'scanner_circuit_closed'):
                info("BREAKER LIFECYCLE  " + line)

            services.stop(breaker_process)
            breaker_process = None
        except BaseException as crashed:
            print(f'\n{RED}SCRIPT_ERROR  {type(crashed).__name__}: {crashed}{RESET}', file=sys.stderr, flush=True)
            for line in services.lines(quarantine_log, 'scanner_', 'scan_')[-15:]:
                print(f'  {DIM}{line}{RESET}', file=sys.stderr, flush=True)
            raise
        finally:
            services.stop(breaker_process)
            services.stop(scanner_process)
            services.stop(process)

    if failures:
        print(f"\n{RED}{BOLD}╔{'═'*58}╗{RESET}", file=sys.stderr)
        print(f"{RED}{BOLD}║  CHALLENGE_3A_FAIL — {len(failures)} checks did not hold{' '*(35-len(str(len(failures))))}║{RESET}", file=sys.stderr)
        print(f"{RED}{BOLD}╚{'═'*58}╝{RESET}", file=sys.stderr)
        for description in failures:
            print(f"  {RED}✘{RESET}  {description}", file=sys.stderr, flush=True)
        return 1
    print(f"\n{GREEN}{BOLD}╔{'═'*58}╗{RESET}")
    print(f"{GREEN}{BOLD}║  CHALLENGE_3A_PASS{' '*39}║{RESET}")
    print(f"{GREEN}{BOLD}║  Timeout · retry · graceful failure · circuit breaker{' '*5}║{RESET}")
    print(f"{GREEN}{BOLD}╚{'═'*58}╝{RESET}\n", flush=True)
    return 0


if __name__ == '__main__':
    sys.exit(main())

#!/usr/bin/env python3
"""The trust boundary between Release and Quarantine, proved across three processes.

Release used to take the caller's word for a file's quarantine state, which meant any
client could release anything by typing CLEAN. It now asks the service that owns the
record. This script starts Scanner, Quarantine and Release on their own ports against
temporary file databases and shows the four things that change because of it:

  A  a genuinely clean file releases, verified against Quarantine's own record
  B  a client that claims CLEAN for a file Quarantine reports as INFECTED is refused
  C  with Quarantine stopped, a real clean file is NOT released - 503 and nothing stored,
     because being unable to check is not the same as being allowed to release
  D  once Quarantine answers again the same release succeeds, and an already released
     file still replays while Quarantine is down

It exits non-zero if any of that does not hold, so evidence/tests/verification-run.txt is
a test result rather than a transcript.
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

ATTEMPTS = 3
CORRELATION = 'verification-' + str(uuid.uuid4())

failures = []


def check(condition, description, detail=None):
    color = GREEN if condition else RED
    mark  = '✔' if condition else '✘'
    print(f"  {color}{mark}{RESET}  {description}"
          + ('' if detail is None else f'  {DIM}{detail}{RESET}'), flush=True)
    if not condition:
        failures.append(description)


def call(base, method, path, payload=None, timeout=30):
    return services.call(base, method, path, payload, timeout, CORRELATION)


def quarantine_file(base, mode):
    status, created = call(base, 'POST', '/api/v1/quarantine',
                           {'fileId': str(uuid.uuid4()), 'scannerMode': mode})
    assert status == 201, (status, created)
    return created['id'], created['fileId']


def scan(base, record_id):
    status, settled = call(base, 'POST', f'/api/v1/quarantine/{record_id}/scan')
    assert status == 200, (status, settled)
    return settled


def verified_timestamp(quarantine_base, file_id):
    """Ask the owner, exactly as Release does, so the claim is not a rounding artefact."""
    status, record = call(quarantine_base, 'GET', f'/api/v1/quarantine/by-file/{file_id}')
    assert status == 200, (status, record)
    return record['quarantinedAt']


def release(release_base, file_id, claimed_state, quarantined_at):
    return call(release_base, 'POST', '/api/v1/releases',
                {'fileId': file_id, 'quarantineState': claimed_state,
                 'quarantinedAt': quarantined_at})


def main():
    services.require(services.QUARANTINE_JAR)
    services.require(services.SCANNER_JAR)
    services.require(services.RELEASE_JAR)
    java = services.java_executable()

    banner("Release Trust Boundary — Verification Suite")
    info(f"releaseVerificationMaxAttempts={ATTEMPTS}  quarantineReadTimeout=1000 ms")
    info(f"correlationId={CORRELATION}")
    info(f"timestamp={datetime.now(timezone.utc).isoformat()}")

    with tempfile.TemporaryDirectory(prefix='verification-') as folder:
        directory = Path(folder)
        scanner_port = services.free_port()
        quarantine_port = services.free_port()
        release_port = services.free_port()
        scanner = services.base_url(scanner_port)
        quarantine = services.base_url(quarantine_port)
        release_base = services.base_url(release_port)
        release_log = directory / 'release-1.log'   # Release is started once, for every part
        scanner_process = quarantine_process = release_process = None

        def start_quarantine(run):
            return services.start(java, services.QUARANTINE_JAR, quarantine_port, {
                'spring.datasource.url': 'jdbc:h2:file:' + str(directory / 'quarantine'),
                'spring.datasource.username': 'verification',
                'spring.datasource.password': 'test_only',
                'spring.datasource.driver-class-name': 'org.h2.Driver',
                'scanner.base-url': scanner}, directory / f'quarantine-{run}.log')

        def start_release(run):
            return services.start(java, services.RELEASE_JAR, release_port, {
                'spring.datasource.url': 'jdbc:h2:file:' + str(directory / 'release'),
                'spring.datasource.username': 'verification',
                'spring.datasource.password': 'test_only',
                'spring.datasource.driver-class-name': 'org.h2.Driver',
                'quarantine.base-url': quarantine,
                'quarantine.connect-timeout-millis': 300,
                'quarantine.read-timeout-millis': 1000,
                'quarantine.max-attempts': ATTEMPTS,
                'quarantine.retry-base-millis': 100,
                'quarantine.retry-jitter-millis': 50}, directory / f'release-{run}.log')

        try:
            scanner_process = services.start(java, services.SCANNER_JAR, scanner_port, {
                'spring.datasource.url': 'jdbc:h2:file:' + str(directory / 'scanner'),
                'spring.datasource.username': 'verification',
                'spring.datasource.password': 'test_only',
                'spring.datasource.driver-class-name': 'org.h2.Driver',
                'scanner.slow-millis': 200}, directory / 'scanner.log')
            quarantine_process = start_quarantine('1')
            release_process = start_release('1')

            # --- A: the honest case still works, and the check is visible in the log ---
            section("Part A — Verified release: clean file releases against the owner's record")
            record_id, file_id = quarantine_file(quarantine, 'CLEAN')
            settled = scan(quarantine, record_id)
            quarantined_at = verified_timestamp(quarantine, file_id)
            info(f"Quarantine state={settled['state']}")
            status, released = call(release_base, 'POST', '/api/v1/releases',
                                    {'fileId': file_id, 'quarantineState': 'CLEAN',
                                     'quarantinedAt': quarantined_at})
            info(f"status={status}  {json.dumps(released, sort_keys=True)}")
            check(status == 201 and released['state'] == 'RELEASED',
                  'a file Quarantine reports CLEAN is released')
            read_status, read = call(release_base, 'GET', f'/api/v1/releases/{file_id}')
            check(read_status == 200 and read == released, 'the release is readable')
            verified_lines = services.lines(release_log, 'release_verified')
            check(any(file_id in line for line in verified_lines),
                  'Release logged the verification it performed',
                  verified_lines[-1] if verified_lines else 'no line')

            # --- B: the claim is only a claim ---
            section("Part B — Lying client: claiming CLEAN for infected/missing/wrong-time is refused")
            infected_record, infected_file = quarantine_file(quarantine, 'INFECTED')
            infected_state = scan(quarantine, infected_record)['state']
            infected_at = verified_timestamp(quarantine, infected_file)
            info(f"Quarantine state={infected_state}")
            status, refusal = release(release_base, infected_file, 'CLEAN', infected_at)
            info(f"status={status}  message={refusal.get('message')}")
            check(status == 409, 'the release is refused even though the caller said CLEAN')
            check('INFECTED' in str(refusal.get('message')),
                  'and the refusal names what Quarantine actually reported',
                  refusal.get('message'))
            read_status, _ = call(release_base, 'GET', f'/api/v1/releases/{infected_file}')
            check(read_status == 404, 'and nothing was stored for the refused file')
            # The other two ways a claim can fail to be the truth.
            status, never = release(release_base, str(uuid.uuid4()), 'CLEAN',
                                    datetime.now(timezone.utc).isoformat().replace('+00:00', 'Z'))
            check(status == 409 and 'No quarantine record' in str(never.get('message')),
                  'a file that was never quarantined is refused too', never.get('message'))
            wrong_time_record, wrong_time_file = quarantine_file(quarantine, 'CLEAN')
            check(scan(quarantine, wrong_time_record)['state'] == 'CLEAN',
                  'this one is clean as well, so only the timestamp can be the problem')
            status, stale = release(release_base, wrong_time_file, 'CLEAN', '2020-01-01T00:00:00Z')
            check(status == 409 and 'does not match' in str(stale.get('message')),
                  'a real clean file claimed with the wrong quarantine time is refused',
                  stale.get('message'))

            # --- C: cannot check, so cannot release ---
            section("Part C — Quarantine down: no check → no release (503, nothing stored)")
            waiting_record, waiting_file = quarantine_file(quarantine, 'CLEAN')
            check(scan(quarantine, waiting_record)['state'] == 'CLEAN',
                  'this file really is clean and releasable')
            waiting_at = verified_timestamp(quarantine, waiting_file)
            services.stop(quarantine_process)
            quarantine_process = None
            deadline = time.monotonic() + 20
            while services.is_listening(quarantine_port) and time.monotonic() < deadline:
                time.sleep(0.2)
            check(not services.is_listening(quarantine_port),
                  f'Quarantine is no longer listening on port {quarantine_port}')

            started = time.monotonic()
            status, unavailable = release(release_base, waiting_file, 'CLEAN', waiting_at)
            elapsed = int((time.monotonic() - started) * 1000)
            info(f"status={status}  message={unavailable.get('message')}  elapsed={elapsed} ms")
            check(status == 503, 'the release is refused with 503, not granted on the claim')
            check('Quarantine did not answer' in str(unavailable.get('message')),
                  'and the answer says why', unavailable.get('message'))
            read_status, _ = call(release_base, 'GET', f'/api/v1/releases/{waiting_file}')
            check(read_status == 404, 'nothing was stored while the dependency was down')
            attempts = services.lines(release_log, f'quarantine_read fileId={waiting_file}')
            check(len(attempts) == ATTEMPTS,
                  f'the whole budget of {ATTEMPTS} attempts was spent on a read that is '
                  f'safe to repeat', f'{len(attempts)} lines')
            for line in attempts:
                info("RELEASE LOG  " + line)
            refused_writes = [line for line in services.lines(release_log, 'dependency_unavailable')
                              if '/api/v1/releases' in line]
            check(len(refused_writes) >= 1,
                  'the outage is logged as a dependency failure, not as a bug',
                  refused_writes[0] if refused_writes else 'no line')
            check(not any('release_rejected' in line and waiting_file in line
                          for line in services.lines(release_log, 'release_rejected')),
                  'an unchecked release is never reported as a policy rejection')
            for line in refused_writes[:1]:
                info("RELEASE LOG  " + line)

            # --- D: the outage delays a release, it does not lose it ---
            section("Part D — Quarantine back: delayed release proceeds, replay works offline")
            status, health = call(release_base, 'GET', '/health')
            check(status == 200 and health['status'] == 'UP', 'Release stayed up throughout')
            quarantine_process = start_quarantine('2')
            status, recovered = release(release_base, waiting_file, 'CLEAN', waiting_at)
            info(f"status={status}  {json.dumps(recovered, sort_keys=True)}")
            check(status == 201 and recovered['state'] == 'RELEASED',
                  'the same request succeeds once Quarantine answers again')

            services.stop(quarantine_process)
            quarantine_process = None
            deadline = time.monotonic() + 20
            while services.is_listening(quarantine_port) and time.monotonic() < deadline:
                time.sleep(0.2)
            reads_after_the_release = len(
                services.lines(release_log, f'quarantine_read fileId={waiting_file}'))
            status, replay = release(release_base, waiting_file, 'CLEAN', waiting_at)
            check(status == 201 and replay == recovered,
                  'and a finished release still replays while Quarantine is down', status)
            check(len(services.lines(release_log,
                    f'quarantine_read fileId={waiting_file}')) == reads_after_the_release,
                  'the replay asked Quarantine nothing new')

            section("Release log — verification, refusals, outage trail")
            for line in services.lines(release_log, 'release_verified', 'release_rejected',
                                        'release_replay', 'dependency_unavailable')[-8:]:
                info(line)
        finally:
            services.stop(release_process)
            services.stop(quarantine_process)
            services.stop(scanner_process)

    if failures:
        print(f"\n{RED}{BOLD}╔{'═'*58}╗{RESET}", file=sys.stderr)
        print(f"{RED}{BOLD}║  VERIFICATION_FAIL — {len(failures)} checks did not hold{' '*(35-len(str(len(failures))))}║{RESET}", file=sys.stderr)
        print(f"{RED}{BOLD}╚{'═'*58}╝{RESET}", file=sys.stderr)
        for description in failures:
            print(f"  {RED}✘{RESET}  {description}", file=sys.stderr, flush=True)
        return 1
    print(f"\n{GREEN}{BOLD}╔{'═'*58}╗{RESET}")
    print(f"{GREEN}{BOLD}║  VERIFICATION_PASS{' '*39}║{RESET}")
    print(f"{GREEN}{BOLD}║  Release answers to Quarantine, not to the caller{' '*8}║{RESET}")
    print(f"{GREEN}{BOLD}╚{'═'*58}╝{RESET}\n", flush=True)
    return 0


if __name__ == '__main__':
    sys.exit(main())

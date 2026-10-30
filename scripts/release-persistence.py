#!/usr/bin/env python3
"""Verify Release survives a real restart without touching the repository's database.

Release now verifies a file's state at Quarantine, so this script starts all three
services that take part in a release. Only Release is restarted: the decision it stored
has to come back from the file database, and the replay of a finished release has to work
without asking anyone - which is why Quarantine is stopped again before the replay."""

import json
from pathlib import Path
import sys
import tempfile
import uuid
from datetime import datetime, timezone

import services

CORRELATION = 'release-persistence-' + str(uuid.uuid4())


def main():
    services.require(services.RELEASE_JAR)
    services.require(services.QUARANTINE_JAR)
    services.require(services.SCANNER_JAR)
    java = services.java_executable()
    print('TIMESTAMP', datetime.now(timezone.utc).isoformat(), flush=True)
    print('COMMAND python3 scripts/release-persistence.py', flush=True)
    print('CORRELATION_ID', CORRELATION, flush=True)
    print('DATABASE isolated temporary file-based H2; existing application data is untouched', flush=True)

    def call(base, method, path, payload=None):
        return services.call(base, method, path, payload, 30, CORRELATION)

    with tempfile.TemporaryDirectory(prefix='release-persistence-') as folder:
        directory = Path(folder)
        scanner_port, quarantine_port, release_port = (services.free_port(),
                                                       services.free_port(),
                                                       services.free_port())
        scanner = services.base_url(scanner_port)
        quarantine = services.base_url(quarantine_port)
        release = services.base_url(release_port)

        def database(name, owner):
            return {'spring.datasource.url': 'jdbc:h2:file:' + str(directory / name),
                    'spring.datasource.username': owner,
                    'spring.datasource.password': 'test_only',
                    'spring.datasource.driver-class-name': 'org.h2.Driver'}

        scanner_process = quarantine_process = release_process = None
        scanner_settings = dict(database('scanner', 'release_persistence_test'),
                                **{'scanner.slow-millis': 200})
        quarantine_settings = dict(database('quarantine', 'release_persistence_test'),
                                   **{'scanner.base-url': scanner})
        logs = {1: directory / 'release-run-1.log', 2: directory / 'release-run-2.log'}
        try:
            scanner_process = services.start(java, services.SCANNER_JAR, scanner_port,
                                             scanner_settings, directory / 'scanner.log')
            quarantine_process = services.start(java, services.QUARANTINE_JAR, quarantine_port,
                                                quarantine_settings, directory / 'quarantine.log')

            # A real clean file, verified by Quarantine before Release accepts it.
            status, record = call(quarantine, 'POST', '/api/v1/quarantine',
                                 {'fileId': str(uuid.uuid4()), 'scannerMode': 'CLEAN'})
            assert status == 201, (status, record)
            status, scanned = call(quarantine, 'POST',
                                   f"/api/v1/quarantine/{record['id']}/scan")
            assert status == 200 and scanned['state'] == 'CLEAN', (status, scanned)
            _, authoritative = call(quarantine, 'GET',
                                    f"/api/v1/quarantine/by-file/{record['fileId']}")
            body = {'fileId': record['fileId'], 'quarantineState': 'CLEAN',
                    'quarantinedAt': authoritative['quarantinedAt']}

            release_process = services.start(java, services.RELEASE_JAR, release_port,
                                            dict(database('release', 'release_persistence_test'),
                                                 **{'quarantine.base-url': quarantine}),
                                            logs[1])
            status, first = call(release, 'POST', '/api/v1/releases', body)
            assert status == 201 and first['state'] == 'RELEASED', (status, first)
            print('BEFORE_RESTART', status, json.dumps(first, sort_keys=True), flush=True)

            services.stop(release_process)
            release_process = None
            assert (directory / 'release.mv.db').is_file(), 'Database file is missing'
            print('FIRST_PROCESS_STOPPED DATABASE_FILE_EXISTS true', flush=True)

            # Quarantine goes away on purpose: a finished release is Release's own fact.
            services.stop(quarantine_process)
            quarantine_process = None
            release_process = services.start(java, services.RELEASE_JAR, release_port,
                                             dict(database('release', 'release_persistence_test'),
                                                  **{'quarantine.base-url': quarantine}),
                                             logs[2])
            read_status, read = call(release, 'GET', '/api/v1/releases/' + body['fileId'])
            replay_status, replay = call(release, 'POST', '/api/v1/releases', body)
            assert read_status == 200 and read == first, (read_status, read)
            assert replay_status == 201 and replay == first, (replay_status, replay)
            print('AFTER_RESTART_GET_WITH_QUARANTINE_DOWN', read_status,
                  json.dumps(read, sort_keys=True), flush=True)
            print('AFTER_RESTART_REPLAY_WITH_QUARANTINE_DOWN', replay_status,
                  json.dumps(replay, sort_keys=True), flush=True)
        except BaseException:
            for path in (*logs.values(), directory / 'quarantine.log', directory / 'scanner.log'):
                if path.is_file():
                    tail = '\n'.join(path.read_text().splitlines()[-40:])
                    print(f'SERVICE_LOG_TAIL {path.name}\n{tail}', flush=True)
                    break
            raise
        finally:
            services.stop(release_process)
            services.stop(quarantine_process)
            services.stop(scanner_process)
    print('RELEASE_PERSISTENCE_PASS', flush=True)


if __name__ == '__main__':
    try:
        main()
    except AssertionError as failed:
        print('RELEASE_PERSISTENCE_FAIL', failed, file=sys.stderr, flush=True)
        sys.exit(1)

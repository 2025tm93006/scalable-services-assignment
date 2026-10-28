#!/usr/bin/env python3
"""Verify Quarantine keeps its records, and the verdict it got from Scanner, across a
real restart without touching the repository's own database.

Scanner is started alongside Quarantine because the scan outcome now arrives over
REST: a persisted INFECTED state is only worth evidencing if a different service
actually reported it. Only Quarantine is restarted, so the record, its state and the
unique fileId constraint all come back from the file database."""

import json
from pathlib import Path
import sys
import tempfile
import uuid
from datetime import datetime, timezone

import services

CORRELATION = 'quarantine-persistence-' + str(uuid.uuid4())


def main():
    services.require(services.QUARANTINE_JAR)
    services.require(services.SCANNER_JAR)
    java = services.java_executable()
    print('TIMESTAMP', datetime.now(timezone.utc).isoformat(), flush=True)
    print('COMMAND python3 scripts/quarantine-persistence.py', flush=True)
    print('CORRELATION_ID', CORRELATION, flush=True)
    print('DATABASE isolated temporary file-based H2; existing application data is untouched', flush=True)

    with tempfile.TemporaryDirectory(prefix='quarantine-persistence-') as folder:
        directory = Path(folder)
        scanner_port, quarantine_port = services.free_port(), services.free_port()
        scanner_base = services.base_url(scanner_port)
        base = services.base_url(quarantine_port)

        def call(method, path, payload=None):
            return services.call(base, method, path, payload, 30, CORRELATION)

        quarantine_settings = {
            'spring.datasource.url': 'jdbc:h2:file:' + str(directory / 'quarantine'),
            'spring.datasource.username': 'quarantine_persistence_test',
            'spring.datasource.password': 'test_only',
            'spring.datasource.driver-class-name': 'org.h2.Driver',
            'scanner.base-url': scanner_base,
        }
        body = {'fileId': str(uuid.uuid4()), 'scannerMode': 'INFECTED'}
        logs = {1: directory / 'quarantine-run-1.log', 2: directory / 'quarantine-run-2.log'}
        scanner = quarantine = None
        record_id = None
        run = 0
        try:
            scanner = services.start(java, services.SCANNER_JAR, scanner_port, {
                'spring.datasource.url': 'jdbc:h2:file:' + str(directory / 'scanner'),
                'spring.datasource.username': 'scanner_persistence_test',
                'spring.datasource.password': 'test_only',
                'spring.datasource.driver-class-name': 'org.h2.Driver'},
                directory / 'scanner.log')

            for run in (1, 2):
                quarantine = services.start(java, services.QUARANTINE_JAR, quarantine_port,
                                            quarantine_settings, logs[run])
                if run == 1:
                    created_status, created = call('POST', '/api/v1/quarantine', body)
                    assert created_status == 201 and created['state'] == 'QUARANTINED', \
                        (created_status, created)
                    record_id = created['id']
                    scan_status, scanned = call('POST', f'/api/v1/quarantine/{record_id}/scan')
                    assert scan_status == 200 and scanned['state'] == 'INFECTED', \
                        (scan_status, scanned)
                    assert scanned['scannedAt'] is not None, scanned
                    print('BEFORE_RESTART_CREATE', created_status,
                          json.dumps(created, sort_keys=True), flush=True)
                    print('BEFORE_RESTART_SCAN', scan_status,
                          json.dumps(scanned, sort_keys=True), flush=True)

                    # Scanner recorded the scan Quarantine asked it to run, with its own
                    # timestamp; Quarantine stored that same verdict rather than deriving one.
                    stored_status, stored = services.call(
                        scanner_base, 'GET', f"/api/v1/scans/{body['fileId']}", None, 30, CORRELATION)
                    assert stored_status == 200 and stored['result'] == 'INFECTED', \
                        (stored_status, stored)
                    assert stored['finishedAt'] == scanned['scannedAt'], (stored, scanned)
                    print('BEFORE_RESTART_SCANNER_STORED', stored_status,
                          json.dumps(stored, sort_keys=True), flush=True)

                    services.stop(quarantine)
                    quarantine = None
                    assert (directory / 'quarantine.mv.db').is_file(), 'Database file is missing'
                    print('FIRST_PROCESS_STOPPED DATABASE_FILE_EXISTS true', flush=True)
                else:
                    read_status, read = call('GET', '/api/v1/quarantine/' + record_id)
                    duplicate_status, duplicate = call('POST', '/api/v1/quarantine', body)
                    rescan_status, rescan = call('POST', f'/api/v1/quarantine/{record_id}/scan')
                    _, evidence = call('GET', '/api/v1/quarantine/_evidence/summary')
                    assert read_status == 200 and read == scanned, (read_status, read)
                    assert duplicate_status == 409, (duplicate_status, duplicate)
                    assert rescan_status == 409, (rescan_status, rescan)
                    assert evidence['total'] == 1 and evidence['byState']['INFECTED'] == 1, evidence
                    print('AFTER_RESTART_GET', read_status, json.dumps(read, sort_keys=True), flush=True)
                    print('AFTER_RESTART_DUPLICATE_CREATE', duplicate_status,
                          json.dumps(duplicate, sort_keys=True), flush=True)
                    print('AFTER_RESTART_SECOND_SCAN', rescan_status,
                          json.dumps(rescan, sort_keys=True), flush=True)
                    print('AFTER_RESTART_EVIDENCE', json.dumps(evidence, sort_keys=True), flush=True)
        except BaseException:
            for path in logs.values():
                if path.is_file():
                    tail = '\n'.join(path.read_text().splitlines()[-40:])
                    print(f'SERVICE_LOG_TAIL {path.name}\n{tail}', flush=True)
                    break
            raise
        finally:
            services.stop(quarantine)
            services.stop(scanner)
    print('QUARANTINE_PERSISTENCE_PASS', flush=True)


if __name__ == '__main__':
    try:
        main()
    except AssertionError as failed:
        print('QUARANTINE_PERSISTENCE_FAIL', failed, file=sys.stderr, flush=True)
        sys.exit(1)

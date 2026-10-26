#!/usr/bin/env python3
"""Verify Quarantine keeps its records across a real restart without touching the user's database."""

import json
import os
from pathlib import Path
import socket
import subprocess
import tempfile
import time
import urllib.error
import urllib.request
import uuid
from datetime import datetime, timezone


ROOT = Path(__file__).resolve().parents[1]
JAR = ROOT / 'quarantine-service/target/quarantine-service-1.0.0-SNAPSHOT.jar'


def call(base, method, path, payload=None):
    headers = {'Content-Type': 'application/json', 'X-Correlation-Id': 'quarantine-persistence-test'}
    data = None if payload is None else json.dumps(payload).encode()
    request = urllib.request.Request(base + path, data=data, headers=headers, method=method)
    try:
        with urllib.request.urlopen(request, timeout=10) as response:
            return response.status, json.loads(response.read())
    except urllib.error.HTTPError as failure:
        return failure.code, json.loads(failure.read())


def stop(process):
    if process.poll() is None:
        process.terminate()
    try:
        process.wait(timeout=10)
    except subprocess.TimeoutExpired:
        process.kill()
        process.wait()
        raise RuntimeError('Quarantine did not stop gracefully')


def start(java, env, base, log):
    arguments = [java, '-jar', str(JAR), '--spring.config.location=classpath:/application.yml',
                 '--spring.datasource.url=' + env['DB_URL'],
                 '--spring.datasource.username=' + env['DB_USERNAME'],
                 '--spring.datasource.password=' + env['DB_PASSWORD'],
                 '--spring.datasource.driver-class-name=org.h2.Driver',
                 '--server.address=127.0.0.1', '--server.port=' + env['SERVER_PORT']]
    process = subprocess.Popen(arguments, cwd=ROOT, env=env, stdout=log, stderr=subprocess.STDOUT)
    deadline = time.monotonic() + 30
    try:
        while True:
            if process.poll() is not None:
                raise RuntimeError('Quarantine exited before becoming healthy')
            try:
                status, health = call(base, 'GET', '/health')
                if status == 200 and health['status'] == 'UP':
                    return process
            except (OSError, TimeoutError):
                if time.monotonic() >= deadline:
                    raise RuntimeError('Quarantine did not become healthy in 30 seconds')
                time.sleep(0.2)
    except BaseException:
        stop(process)
        raise


def main():
    if not JAR.is_file():
        raise SystemExit('Build Quarantine first: mvn -pl quarantine-service -am package')
    java_home = os.getenv('JAVA_HOME')
    java = str(Path(java_home) / 'bin/java') if java_home else 'java'
    print('TIMESTAMP', datetime.now(timezone.utc).isoformat(), flush=True)
    print('COMMAND python3 scripts/quarantine-persistence.py', flush=True)
    print('DATABASE isolated temporary file-based H2; existing application data is untouched', flush=True)

    with tempfile.TemporaryDirectory(prefix='quarantine-persistence-') as folder:
        directory = Path(folder)
        with socket.socket() as sock:
            sock.bind(('127.0.0.1', 0))
            port = sock.getsockname()[1]
        base = f'http://127.0.0.1:{port}'
        env = {key: value for key, value in os.environ.items() if not key.startswith('SPRING_')}
        env.update(SERVER_PORT=str(port), SERVER_ADDRESS='127.0.0.1',
                   DB_URL='jdbc:h2:file:' + str(directory / 'quarantine'), DB_DRIVER='org.h2.Driver',
                   DB_USERNAME='quarantine_persistence_test', DB_PASSWORD='test_only')
        # Exercise application.yml's default schema policy, not a forced test policy.
        env.pop('DDL_AUTO', None)
        body = {'fileId': str(uuid.uuid4()), 'scannerMode': 'INFECTED'}
        process = None
        log_path = directory / 'service.log'
        with log_path.open('w') as log:
            try:
                process = start(java, env, base, log)
                created_status, created = call(base, 'POST', '/api/v1/quarantine', body)
                assert created_status == 201 and created['state'] == 'QUARANTINED', (created_status, created)
                record_id = created['id']
                scan_status, scanned = call(base, 'POST', f'/api/v1/quarantine/{record_id}/scan')
                assert scan_status == 200 and scanned['state'] == 'INFECTED', (scan_status, scanned)
                assert scanned['scannedAt'] is not None, scanned
                print('BEFORE_RESTART_CREATE', created_status, json.dumps(created, sort_keys=True), flush=True)
                print('BEFORE_RESTART_SCAN', scan_status, json.dumps(scanned, sort_keys=True), flush=True)
                stop(process)
                process = None
                assert (directory / 'quarantine.mv.db').is_file(), 'Database file is missing'
                print('FIRST_PROCESS_STOPPED DATABASE_FILE_EXISTS true', flush=True)

                process = start(java, env, base, log)
                read_status, read = call(base, 'GET', '/api/v1/quarantine/' + record_id)
                duplicate_status, duplicate = call(base, 'POST', '/api/v1/quarantine', body)
                rescan_status, rescan = call(base, 'POST', f'/api/v1/quarantine/{record_id}/scan')
                _, evidence = call(base, 'GET', '/api/v1/quarantine/_evidence/summary')
                assert read_status == 200 and read == scanned, (read_status, read)
                assert duplicate_status == 409, (duplicate_status, duplicate)
                assert rescan_status == 409, (rescan_status, rescan)
                assert evidence['total'] == 1 and evidence['byState']['INFECTED'] == 1, evidence
                print('AFTER_RESTART_GET', read_status, json.dumps(read, sort_keys=True), flush=True)
                print('AFTER_RESTART_DUPLICATE_CREATE', duplicate_status,
                      json.dumps(duplicate, sort_keys=True), flush=True)
                print('AFTER_RESTART_SECOND_SCAN', rescan_status, json.dumps(rescan, sort_keys=True), flush=True)
                print('AFTER_RESTART_EVIDENCE', json.dumps(evidence, sort_keys=True), flush=True)
            except BaseException:
                log.flush()
                print('SERVICE_LOG_TAIL\n' + '\n'.join(log_path.read_text().splitlines()[-40:]), flush=True)
                raise
            finally:
                if process is not None:
                    stop(process)
    print('QUARANTINE_PERSISTENCE_PASS', flush=True)


if __name__ == '__main__':
    main()

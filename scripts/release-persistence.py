#!/usr/bin/env python3
"""Verify Release survives a real restart without touching the user's database."""

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
JAR = ROOT / 'release-service/target/release-service-1.0.0-SNAPSHOT.jar'


def call(base, method, path, payload=None):
    headers = {'Content-Type': 'application/json', 'X-Correlation-Id': 'release-persistence-test'}
    data = None if payload is None else json.dumps(payload).encode()
    request = urllib.request.Request(base + path, data=data, headers=headers, method=method)
    with urllib.request.urlopen(request, timeout=10) as response:
        return response.status, json.loads(response.read())


def stop(process):
    if process.poll() is None:
        process.terminate()
    try:
        process.wait(timeout=10)
    except subprocess.TimeoutExpired:
        process.kill()
        process.wait()
        raise RuntimeError('Release did not stop gracefully')


def start(java, env, base, log):
    arguments = [java, '-jar', str(JAR), '--spring.config.location=classpath:/application.yml',
                 '--spring.datasource.url=' + env['DB_URL'],
                 '--spring.datasource.username=' + env['DB_USERNAME'],
                 '--spring.datasource.password=' + env['DB_PASSWORD'],
                 '--spring.datasource.driver-class-name=org.h2.Driver',
                 '--server.address=127.0.0.1', '--server.port=' + env['SERVER_PORT']]
    process = subprocess.Popen(arguments, cwd=ROOT, env=env,
                               stdout=log, stderr=subprocess.STDOUT)
    deadline = time.monotonic() + 30
    try:
        while True:
            if process.poll() is not None:
                raise RuntimeError('Release exited before becoming healthy')
            try:
                status, health = call(base, 'GET', '/health')
                if status == 200 and health['status'] == 'UP':
                    return process
            except (OSError, TimeoutError):
                if time.monotonic() >= deadline:
                    raise RuntimeError('Release did not become healthy in 30 seconds')
                time.sleep(0.2)
    except BaseException:
        stop(process)
        raise


def main():
    if not JAR.is_file():
        raise SystemExit('Build Release first: mvn -pl release-service -am package')
    java_home = os.getenv('JAVA_HOME')
    java = str(Path(java_home) / 'bin/java') if java_home else 'java'
    print('TIMESTAMP', datetime.now(timezone.utc).isoformat(), flush=True)
    print('COMMAND python3 scripts/release-persistence.py', flush=True)
    print('DATABASE isolated temporary file-based H2; existing application data is untouched', flush=True)

    with tempfile.TemporaryDirectory(prefix='release-persistence-') as folder:
        directory = Path(folder)
        with socket.socket() as sock:
            sock.bind(('127.0.0.1', 0))
            port = sock.getsockname()[1]
        base = f'http://127.0.0.1:{port}'
        env = {key: value for key, value in os.environ.items() if not key.startswith('SPRING_')}
        env.update(SERVER_PORT=str(port), SERVER_ADDRESS='127.0.0.1',
                   DB_URL='jdbc:h2:file:' + str(directory / 'release'), DB_DRIVER='org.h2.Driver',
                   DB_USERNAME='release_persistence_test', DB_PASSWORD='test_only')
        # Exercise application.yml's default schema policy, not a forced test policy.
        env.pop('DDL_AUTO', None)
        body = {'fileId': str(uuid.uuid4()), 'quarantineState': 'CLEAN',
                'quarantinedAt': '2026-10-24T08:00:00Z'}
        process = None
        log_path = directory / 'service.log'
        with log_path.open('w') as log:
            try:
                process = start(java, env, base, log)
                status, first = call(base, 'POST', '/api/v1/releases', body)
                assert status == 201 and first['state'] == 'RELEASED', (status, first)
                print('BEFORE_RESTART', status, json.dumps(first, sort_keys=True), flush=True)
                stop(process)
                process = None
                assert (directory / 'release.mv.db').is_file(), 'Database file is missing'
                print('FIRST_PROCESS_STOPPED DATABASE_FILE_EXISTS true', flush=True)

                process = start(java, env, base, log)
                read_status, read = call(base, 'GET', '/api/v1/releases/' + body['fileId'])
                replay_status, replay = call(base, 'POST', '/api/v1/releases', body)
                assert read_status == 200 and read == first, (read_status, read)
                assert replay_status == 201 and replay == first, (replay_status, replay)
                print('AFTER_RESTART_GET', read_status, json.dumps(read, sort_keys=True), flush=True)
                print('AFTER_RESTART_REPLAY', replay_status, json.dumps(replay, sort_keys=True), flush=True)
            except BaseException:
                log.flush()
                print('SERVICE_LOG_TAIL\n' + '\n'.join(log_path.read_text().splitlines()[-40:]), flush=True)
                raise
            finally:
                if process is not None:
                    stop(process)
    print('RELEASE_PERSISTENCE_PASS', flush=True)


if __name__ == '__main__':
    main()

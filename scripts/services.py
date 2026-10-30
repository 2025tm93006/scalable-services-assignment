#!/usr/bin/env python3
"""Shared harness for the evidence scripts: start a packaged service JAR against a
temporary file-based H2 database, wait for /health, call it, and stop it again.

Keeping it in one place matters because the evidence scripts must run the real JARs
the same way the READMEs tell a reviewer to run them.
"""

import json
import os
from pathlib import Path
import socket
import subprocess
import time
import urllib.error
import urllib.request

ROOT = Path(__file__).resolve().parents[1]
QUARANTINE_JAR = ROOT / 'quarantine-service/target/quarantine-service-1.0.0-SNAPSHOT.jar'
SCANNER_JAR = ROOT / 'scanner-service/target/scanner-service-1.0.0-SNAPSHOT.jar'


def java_executable():
    java_home = os.getenv('JAVA_HOME')
    return str(Path(java_home) / 'bin/java') if java_home else 'java'


def require(jar):
    if not jar.is_file():
        raise SystemExit(f'Build it first: mvn -pl {jar.parent.parent.name} -am package')
    return jar


def call(base, method, path, payload=None, timeout=30, correlation_id=None):
    headers = {'Content-Type': 'application/json'}
    if correlation_id:
        headers['X-Correlation-Id'] = correlation_id
    data = None if payload is None else json.dumps(payload).encode()
    request = urllib.request.Request(base + path, data=data, headers=headers, method=method)
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            body = response.read()
            return response.status, json.loads(body) if body else None
    except urllib.error.HTTPError as failure:
        return failure.code, json.loads(failure.read())


def free_port():
    with socket.socket() as sock:
        sock.bind(('127.0.0.1', 0))
        return sock.getsockname()[1]


def base_url(port):
    return f'http://127.0.0.1:{port}'


def start(java, jar, port, settings, log_path, readiness_timeout=60):
    """Starts one service with command line property overrides, which beat both
    application.yml and any environment variable the caller happens to export."""
    arguments = [java, '-jar', str(jar), '--server.address=127.0.0.1', '--server.port=' + str(port)]
    for key, value in settings.items():
        arguments.append('--' + key + '=' + str(value))
    with Path(log_path).open('w') as log:
        process = subprocess.Popen(arguments, cwd=ROOT, env=os.environ,
                                   stdout=log, stderr=subprocess.STDOUT)
    base = base_url(port)
    deadline = time.monotonic() + readiness_timeout
    while time.monotonic() < deadline:
        if process.poll() is not None:
            raise RuntimeError(f'{jar.name} exited before becoming healthy, see {log_path}')
        try:
            status, health = call(base, 'GET', '/health', timeout=3)
            if status == 200 and health and health['status'] == 'UP':
                return process
        except (OSError, ValueError):
            pass
        time.sleep(0.2)
    stop(process)
    raise RuntimeError(f'{jar.name} did not become healthy in {readiness_timeout} seconds')


def stop(process):
    if process is None or process.poll() is not None:
        return
    process.terminate()
    try:
        process.wait(timeout=15)
    except subprocess.TimeoutExpired:
        process.kill()
        process.wait()


def kill_hard(process):
    """SIGKILL: simulates a process that stopped without a chance to clean up. A graceful
    terminate can let an in-flight request reach its finally block and release its own
    capacity slot, which is exactly the behaviour the reclaim sweep must not be tested on."""
    if process is None or process.poll() is not None:
        return
    process.kill()
    process.wait(timeout=15)


def is_listening(port):
    with socket.socket() as sock:
        sock.settimeout(0.5)
        return sock.connect_ex(('127.0.0.1', port)) == 0


def lines(path, *contains):
    """Reads back a started service's own log, filtered to the interesting events."""
    if not Path(path).is_file():
        return []
    kept = []
    for line in Path(path).read_text().splitlines():
        if any(term in line for term in contains):
            kept.append(line.split('] ', 1)[-1] if '] ' in line else line)
    return kept

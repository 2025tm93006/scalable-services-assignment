# tests

All four services cover their business rules and actual HTTP requests against an
isolated in-memory H2 database, so `mvn clean package` never writes into `data/`.
Intake, Scanner and Release need no other service. Quarantine's scan tests run a stub
Scanner `@RestController` inside the same test application on the same port, so the
timeouts, retries and capacity limits are exercised over real HTTP without starting a
second process.

From the repository root:

```bash
mvn -pl intake-service -am test
mvn -pl quarantine-service -am test
mvn -pl release-service -am test
mvn -pl scanner-service -am test
```

With the separate Maven settings from local setup, add `-s ../maven-settings.xml`.

| Evidence | What it verifies |
|---|---|
| `intake-unit-tests.txt` | Surefire report for business rules, fingerprinting, unique-key race handling and invalid keys |
| `intake-http-tests.txt` | Surefire report for real HTTP/H2 replay, concurrency, validation, errors and correlation |
| `intake-test-environment.txt` | Test command, runtime and where the tested source copy was located |
| `intake-persistence.txt` | A real process restart preserves the original response and a single database row |
| `quarantine-unit-tests.txt` | Surefire reports for BR3 in isolation: one quarantine per file, the create race answered as a conflict, Scanner verdicts, outages, unexpected verdicts, exhausted capacity, and the `ScannerClient` retry curve and failure reporting |
| `quarantine-http-tests.txt` | Surefire reports for real HTTP/H2 quarantine and scan, including timeout, retry, exhaustion, the 20-parallel capacity burst and correlation forwarding to the stub Scanner |
| `quarantine-test-environment.txt` | Quarantine test command, runtime and result summary |
| `quarantine-persistence.txt` | A real process restart keeps the state Scanner reported, the timestamps and the unique `fileId`, so a replayed create is 409 and a replayed scan is 409 |
| `e2e-run.txt` | `scripts/e2e.sh` driving one transaction through Intake, Quarantine, Scanner and Release with its failure cases |
| `release-unit-tests.txt` | Surefire report for BR4, replay, the primary-key race and unrelated-failure handling |
| `release-http-tests.txt` | Surefire report for replay, 8 simultaneous releases, validation, errors and correlation |
| `release-test-environment.txt` | Release test command, runtime and result summary |
| `release-persistence.txt` | A real process restart preserves the release record and the original response |
| `scanner-unit-tests.txt` | Surefire report for each mode, replay, the in-flight wait, the unique-key race, mode conflict and interruption |
| `scanner-http-tests.txt` | Surefire report for replay, a client read timeout then retry with one attempt, 8 simultaneous scans, validation, errors and correlation |
| `scanner-test-environment.txt` | Scanner test command, runtime and result summary |
| `scanner-persistence.txt` | A real process restart replays the stored scan result with a single attempt |

These reports were captured for the current Intake implementation before its
source files were applied to the repository. Challenge 2's live script output
and application log are captured separately in `../challenge-2-idempotency/`.
Regenerate evidence after changing the implementation. Full test reports are
also written under `intake-service/target/surefire-reports/` when tests run here.

After building the Intake JAR, capture the isolated persistence test from the root:

```bash
python3 scripts/persistence.py 2>&1 | tee evidence/tests/intake-persistence.txt
```

It uses a temporary database, not the application's existing `data/` directory.

After building the Release JAR, capture its isolated restart check:

```bash
python3 scripts/release-persistence.py 2>&1 | tee evidence/tests/release-persistence.txt
```

It uses a temporary database, not the application's existing `data/release.mv.db`.

After building the Scanner JAR, capture its isolated restart check:

```bash
python3 scripts/scanner-persistence.py 2>&1 | tee evidence/tests/scanner-persistence.txt
```

It uses a temporary database, not the application's existing `data/scanner.mv.db`.

After building the Quarantine JAR, capture its isolated restart check:

```bash
python3 scripts/quarantine-persistence.py 2>&1 | tee evidence/tests/quarantine-persistence.txt
```

It uses a temporary database, not the application's existing `data/quarantine.mv.db`.

With all four services running, capture the scripted end-to-end transaction:

```bash
bash scripts/e2e.sh 2>&1 | tee evidence/tests/e2e-run.txt
```

`evidence/tests/e2e-run.txt` also carries the Quarantine and Scanner log lines of the
correlated cross-service scan, which is the proof that the scan really left the process.

The two engineering-challenge scripts need no running services; they start and stop
their own JARs:

```bash
python3 scripts/concurrency.py 2>&1 | tee evidence/challenge-1-concurrency/run.txt
python3 scripts/resilience.py 2>&1 | tee evidence/challenge-3a-resilience/run.txt
```

Both exit non-zero if any asserted invariant breaks, so the captured file is a test
result and not a transcript.

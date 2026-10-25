# tests

Intake, Scanner and Release tests cover their business rules and actual HTTP requests
against an isolated H2 database. No other business service is needed.

From the repository root:

```bash
mvn -pl intake-service -am test
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

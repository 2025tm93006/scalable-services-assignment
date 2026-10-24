# tests

Intake tests cover its business rules and actual HTTP requests against an
isolated H2 database. No other business service is needed.

From the repository root:

```bash
mvn -pl intake-service -am test
```

With the separate Maven settings from local setup, add `-s ../maven-settings.xml`.

| Evidence | What it verifies |
|---|---|
| `intake-unit-tests.txt` | Surefire report for business rules, fingerprinting, unique-key race handling and invalid keys |
| `intake-http-tests.txt` | Surefire report for real HTTP/H2 replay, concurrency, validation, errors and correlation |
| `intake-test-environment.txt` | Test command, runtime and where the tested source copy was located |
| `intake-persistence.txt` | A real process restart preserves the original response and a single database row |

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

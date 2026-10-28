# Quarantine Service

Assignment 1, SEZG583. Quarantine owns the quarantine lifecycle of a file: it records a
file for quarantine, holds one scanner capacity slot while it asks the Scanner service
over REST to inspect the file, stores that service's verdict and refuses a second
quarantine or a second scan. It is the owner of business rule **BR3** and the only
service in this system that calls another service; it keeps only Intake's `fileId`,
never a copy of Intake's data.

Spring Boot 3.3.5, Java 21 target, port **8082**. It depends on the shared `common`
module (health endpoint, correlation filter, error model) and on the Scanner service's
HTTP API at runtime.

## Start it

```bash
mvn -pl quarantine-service -am package
java -jar scanner-service/target/scanner-service-1.0.0-SNAPSHOT.jar      # the dependency
java -jar quarantine-service/target/quarantine-service-1.0.0-SNAPSHOT.jar
```

Run from the repository root so the database lands in `data/quarantine.mv.db`.
`GET http://localhost:8082/health` answers `{"status":"UP","service":"quarantine-service"}`.
Quarantine starts happily without Scanner; a scan then uses its retry budget and settles
the file as `FAILED`, which is the graceful-failure behaviour Challenge 3A asks for.

## Configuration

Ports, credentials, the database path and the Scanner dependency come from environment
variables; see [`.env.example`](.env.example). Command-line `--key=value` overrides work
too, which is what the evidence scripts use so a run never touches `data/`.

| Variable | Default | Meaning |
|---|---|---|
| `SERVER_PORT` | 8082 | HTTP port |
| `DB_URL` | `jdbc:h2:file:./data/quarantine` | own file database |
| `MAX_CONCURRENT_SCANS` | 2 | scanner capacity, one `scan_slots` row each |
| `SCANNER_BASE_URL` | `http://localhost:8083` | the service to call |
| `SCANNER_CONNECT_TIMEOUT_MILLIS` | 300 | TCP connect budget per attempt |
| `SCANNER_READ_TIMEOUT_MILLIS` | 5000 | response budget per attempt |
| `SCANNER_MAX_ATTEMPTS` | 3 | tries before the file is given up on |
| `SCANNER_RETRY_BASE_MILLIS` | 100 | `base * 2^(attempt-1)` |
| `SCANNER_RETRY_JITTER_MILLIS` | 100 | random `0..jitter` added to each delay |

## API

Contract: [`docs/openapi/quarantine.yaml`](../docs/openapi/quarantine.yaml).

| Method | Path | Result |
|---|---|---|
| POST | `/api/v1/quarantine` | 201 with `state: QUARANTINED`, or 409 `File is already quarantined` |
| POST | `/api/v1/quarantine/{id}/scan` | 200 with Scanner's verdict, or 409 (see below), or 404 |
| GET | `/api/v1/quarantine/{id}` | 200 with the record, or 404 |
| GET | `/api/v1/quarantine/_evidence/summary` | 200 with `total`, `byState`, `scannerSlots` and `capacity` |
| GET | `/health` | 200 |

A scan answers 200 even when the verdict is `FAILED`: the request itself succeeded, the
scan did not. The three distinct 409s are `File is already being scanned` (the record is
in `SCANNING`), `File has already been scanned` (the record is settled) and `Scanner
capacity exhausted` (every slot is held, the record is untouched and can be scanned
later). `fileId` must be a UUID and `scannerMode` must be one of `CLEAN`, `INFECTED`,
`FAIL` or `SLOW`; anything else is a 400. Wrong methods keep 405 and non-JSON bodies keep
415. Every failure uses the shared `ApiError` shape and echoes `X-Correlation-Id`, which
is also forwarded to Scanner so one client request is readable in both services' logs.

```bash
curl -i -X POST http://localhost:8082/api/v1/quarantine \
  -H 'Content-Type: application/json' -H 'X-Correlation-Id: demo-1' \
  -d '{"fileId":"3f2a4e6c-1b2d-4c5e-8f90-123456789abc","scannerMode":"CLEAN"}'
curl -i -X POST http://localhost:8082/api/v1/quarantine/<id>/scan \
  -H 'X-Correlation-Id: demo-2'
```

## State machine

`QUARANTINED → SCANNING → CLEAN | INFECTED | FAILED`. A record leaves `QUARANTINED` once
and never transitions again; `scannedAt` is null until that transition and is set to
Scanner's own `completedAt`, not to a locally generated timestamp. `SCANNING` exists only
while a capacity slot is held, so a concurrent scan can see it and is refused. The
verdict is whatever Scanner reported; an unreadable or unexpected value is settled as
`FAILED` and logged, never guessed at.

## Calling Scanner

`ScannerClient` posts `{"fileId", "mode"}` to `POST /api/v1/scans` on the JDK HTTP
client, with a connect and a read timeout on every attempt, and retries
`SCANNER_MAX_ATTEMPTS` times with `retry-base * 2^(attempt-1) + random(0..retry-jitter)`
milliseconds of backoff. When the budget runs out it throws
`ScannerUnavailableException`, and `QuarantineService.scan` settles the record as
`FAILED` and releases the slot in a `finally` block — the caller gets a 200 with a state,
not a 500 and not a record stuck in `SCANNING`.

Two details matter and are covered by tests:

* `scan` is deliberately **not** `@Transactional`. A retry budget of a few seconds must
  not hold a database transaction or a capacity row open, and each state change has to
  commit on its own so that a concurrent request can observe `SCANNING`.
* The client uses `JdkClientHttpRequestFactory`, not
  `SimpleClientHttpRequestFactory`. The latter reuses the pooled keep-alive connection
  after a socket read timeout, so the *next* attempt failed while parsing a response that
  Scanner had written correctly, and the log reported a parsing error instead of the
  timeout that had happened. `ScannerClient.reason()` combines the request text with the
  root cause so a line reads `... : Request timed out (TimeoutException)` or
  `... (ConnectException: Connection refused)`.

Retries are safe because Scanner keys its result on `fileId`: a retry after a timeout
replays the scan that is already running instead of starting a second one, which
`scripts/resilience.py` and `scanner-service`'s own tests both assert.

## Assignment traceability

| Requirement | Code | Verification |
|---|---|---|
| BR3: one quarantine record per file | `QuarantineService.quarantine`, unique `fileId` column | `QuarantineServiceTest.alreadyQuarantinedFileCannotBeQuarantinedAgain`; `scripts/quarantine-persistence.py` |
| BR3: the create race is a 409, not a 500 | `QuarantineRecordWriter` (`REQUIRES_NEW`, `saveAndFlush`), `DataIntegrityViolationException` → `ConflictException` | `QuarantineServiceTest.simultaneousQuarantineOfOneFileReportsTheConflictTheSequentialRepeatGets`; `scripts/concurrency.py` Part 1 |
| BR3: one scan transition per record | `QuarantineService.scan` state guard | `QuarantineServiceTest.aScannedRecordCannotBeScannedTwice`, `.aScanAlreadyInFlightIsRefusedRatherThanStartedAgain`; `scripts/concurrency.py` Part 2 |
| BR3: the outcome is Scanner's verdict | `ScannerClient`, `QuarantineService.stateOf` | `QuarantineServiceTest.scanAsksTheScannerOverItsClientAndStoresTheVerdictAndTimestamp`; `scripts/e2e.sh` (Scanner's `finishedAt` equals `scannedAt`) |
| Challenge 1: contended resource enforced by the database | `ScanSlotRepository.claim` / `.release`, `ScanCapacityService` | `QuarantineScanResilienceTest.twentyParallelScansNeverHoldMoreThanTwoScannerSlots`; `evidence/challenge-1-concurrency/` |
| Challenge 3A: timeout, retry with backoff and jitter, graceful failure | `ScannerClient.scan`, `retryDelayMillis`, `QuarantineService.scan` catch | `ScannerClientTest`, `QuarantineScanResilienceTest`; `evidence/challenge-3a-resilience/` |
| Challenge 3A: retries cause no duplicate effect | `fileId` identity in Scanner, slot held for the whole budget | `QuarantineScanResilienceTest.retriesATransientScannerFailureAndScansTheFileExactlyOnce`; `scripts/resilience.py` Part A/D |
| Correlation ID across the call | `MDC` → `X-Correlation-Id` in `ScannerClient` | `QuarantineScanResilienceTest.forwardsTheCallersCorrelationIdToTheScanner`; `evidence/tests/e2e-run.txt` |
| Records survive a restart | `application.yml`, file-backed H2 with `update` | `scripts/quarantine-persistence.py`; `evidence/tests/quarantine-persistence.txt` |

## Data design

Two tables, both owned by this service alone:

`quarantine_records`

| Column | Type | Notes |
|---|---|---|
| `id` | UUID | primary key, generated here |
| `file_id` | UUID | Intake's identifier; unique, no foreign key and no cross-database access |
| `state` | varchar | `QUARANTINED`, `SCANNING`, `CLEAN`, `INFECTED` or `FAILED` |
| `scanner_mode` | varchar | the mode the caller asked Scanner to simulate |
| `quarantined_at` | timestamp | not null |
| `scanned_at` | timestamp | null until the single transition, then Scanner's timestamp |

`scan_slots`

| Column | Type | Notes |
|---|---|---|
| `slot_number` | int | primary key, `1..MAX_CONCURRENT_SCANS`, seeded idempotently at startup |
| `status` | varchar | `FREE` or `HELD` |
| `file_id` | UUID | the current holder, null when free |
| `acquired_at` | timestamp | set by the claim |
| `released_at` | timestamp | set by the release |

The slots are rows rather than `java.util.concurrent` permits so that the allocation is
visible after the process stops, survives a restart, and is decided by the database
instead of by one JVM — see `evidence/challenge-1-concurrency/README.md`. Both releases
and claims are single conditional updates, so a late release from an abandoned attempt
cannot free a slot a different file has already retaken.

Release receives the same `fileId` and `quarantinedAt` in its request body; no service
reads another service's database.

## Persistence

`spring.datasource.url` defaults to `jdbc:h2:file:./data/quarantine` with
`ddl-auto: update`, so records, their unique constraint and the slot ledger survive a
restart. Before this change the service used `jdbc:h2:mem:` with `create-drop`, which
erased the state machine on shutdown and let a released file be quarantined again.
Tests are unaffected: `QuarantineHttpTest`, `QuarantineScanResilienceTest` and
`QuarantineServiceTest` pin an in-memory database, and the scripts build their own
temporary file databases.

`ddl-auto: update` creates a check constraint from the enum, and an update cannot widen
an existing one. Adding a state (`SCANNING` was added after the first runs) therefore
means deleting the local `data/quarantine.mv.db` once so Hibernate can recreate the
column; the service otherwise fails a scan with `Value not permitted for column ...`.
That is a development-database inconvenience, not a design defect, but it is real and it
is why the evidence scripts never reuse `data/`.

## Tests

`mvn -pl quarantine-service -am test` runs 28 tests: 12 unit, 1 HTTP create/scan/read
path, 7 HTTP resilience and capacity tests against a stub Scanner running on the same
port, and 8 `ScannerClientTest` cases for the retry curve and failure reporting. Captured
reports are in [`evidence/tests`](../evidence/tests)
(`quarantine-unit-tests.txt`, `quarantine-http-tests.txt`,
`quarantine-test-environment.txt`, `quarantine-persistence.txt`).

Live, cross-process evidence:

```bash
python3 scripts/concurrency.py   > evidence/challenge-1-concurrency/run.txt
python3 scripts/resilience.py    > evidence/challenge-3a-resilience/run.txt
python3 scripts/quarantine-persistence.py > evidence/tests/quarantine-persistence.txt
```

Each starts its own packaged JARs on ephemeral ports against temporary databases, so a
run is repeatable and leaves nothing behind.

## Limitations

* The scan is synchronous: the caller waits for Scanner's verdict, and the retry backoff
  sleeps on the request thread. That is what makes the Challenge 3A behaviour observable
  in a single log; an async worker with a queue would be the production shape.
* There is no circuit breaker and no fallback verdict. A `FAILED` file stays `FAILED` and
  is not rescanned, because a second scan of the same file would be a second operation;
  the operator's remedy is to quarantine a new intake submission.
* Capacity is a per-service-table limit, so it protects one Quarantine instance's load on
  Scanner. With several Quarantine replicas the same table still arbitrates them, but the
  seed of the slot rows and `MAX_CONCURRENT_SCANS` must be agreed across replicas.
* A slot is released in the same request that claimed it. If the process were killed
  mid-scan, the row would stay `HELD` until the next release of that slot; a reclaim job
  (an age-based sweep of `HELD` rows) is the obvious production addition and is not
  implemented here.

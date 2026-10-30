# Quarantine Service

Assignment 1, SEZG583. Quarantine owns the quarantine lifecycle of a file: it records a
file for quarantine, holds one scanner capacity slot while it asks the Scanner service
over REST to inspect the file, stores that service's verdict and refuses a second
quarantine or a second scan. It is the owner of business rule **BR3**, the only service
that calls out for a decision, and the authority another service can ask: Release reads a
file's record here before it grants a release. It keeps only Intake's `fileId`, never a
copy of Intake's data.

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
| `SCANNER_CB_ENABLED` | false | circuit breaker, the Challenge 3A bonus |
| `SCANNER_CB_FAILURE_THRESHOLD` | 3 | failed *calls* (whole budgets) before the circuit opens |
| `SCANNER_CB_OPEN_MILLIS` | 4000 | how long calls are refused before one probe is allowed |
| `QUARANTINE_RECLAIM_ENABLED` | true | sweep that cleans up after a killed scan |
| `QUARANTINE_SLOT_STALE_MILLIS` | 60000 | age past which a `HELD` slot is considered abandoned |
| `QUARANTINE_RECLAIM_INTERVAL_MILLIS` | 10000 | how often the sweep runs |

## API

Contract: [`docs/openapi/quarantine.yaml`](../docs/openapi/quarantine.yaml).

| Method | Path | Result |
|---|---|---|
| POST | `/api/v1/quarantine` | 201 with `state: QUARANTINED`, or 409 `File is already quarantined` |
| POST | `/api/v1/quarantine/{id}/scan` | 200 with Scanner's verdict, or 409 (see below), or 404 |
| GET | `/api/v1/quarantine/{id}` | 200 with the record, or 404 |
| GET | `/api/v1/quarantine/by-file/{fileId}` | 200 with the record addressed by Intake's id, or 404 |
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
verdict is whatever Scanner reported; an unreadable or absent value is settled as
`FAILED` and logged, never guessed at. `QUARANTINE_RECORDS.scan_started_at` records when a
scan entered `SCANNING`, which is what lets the reclaim sweep tell an abandoned scan from
one that is still inside its budget; the column is not part of any response.

Two routes reach `FAILED` besides a Scanner verdict: the retry budget running out, and the
reclaim sweep settling a record whose process died mid-scan. Neither is a second
transition - both are a record's first and only settle.

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

## Circuit breaker (off by default)

`ScannerCircuitBreaker` counts failed **calls**, not failed attempts: a scan that recovers
on its second try never moves the circuit. After `SCANNER_CB_FAILURE_THRESHOLD` consecutive
failed calls the circuit opens and further scans are refused immediately -
`scanner_circuit_refused ... attemptsMade=0` - which still settles the record `FAILED` and
still releases the slot, because refusal arrives as the same
`ScannerUnavailableException` the graceful-failure path already handles. After
`SCANNER_CB_OPEN_MILLIS` one probe is allowed; success closes the circuit, failure reopens
it, so an outage costs one real call per cooldown instead of one per request.

It is disabled by default on purpose. With it on, a scan may never reach Scanner at all,
which is correct in an outage and misleading for the timeout and backoff evidence, so
everything in `evidence/challenge-3a-resilience/run.txt` parts A to D is produced with it
off and Part E starts a second instance with it on. `scripts/resilience.py` asserts both
configurations appear in the logs.

## Capacity reclaim

A scan releases its slot in a `finally` block, but a `SIGKILL` runs no `finally`. Without
help that leaves one `scan_slots` row `HELD` and one record `SCANNING` forever - capacity
shrinking by one per crash, and a file stranded with no verdict. `ScanReclaimJob` runs on
`QUARANTINE_RECLAIM_INTERVAL_MILLIS` and, in one pass:

1. frees every slot `HELD` since before `now - QUARANTINE_SLOT_STALE_MILLIS` with a single
   conditional update;
2. settles every `SCANNING` record older than the same cutoff as `FAILED`, but only where
   no slot is held for that file - so a scan that is genuinely still running is left alone.

Slots go first, because a crashed scan's own slot has to be free before its record can be
reclaimed. The state is re-tested inside the update, so a scan that finishes between the
sweep reading the row and reclaiming it is not overwritten (`rowsUpdated == 0`). The job
warns at startup if `QUARANTINE_SLOT_STALE_MILLIS` is not above
`SCANNER_READ_TIMEOUT_MILLIS * SCANNER_MAX_ATTEMPTS`, because then it could take a slot
away from a scan still entitled to report a verdict; the default 60 s sits well above the
15 s worst case. `scripts/reclaim.py` kills a scan mid-call and shows both rows restored.

## Answering for a file

`GET /api/v1/quarantine/by-file/{fileId}` returns the same response as `/{id}`, addressed
by the only identifier that crosses a boundary. It exists so Release can verify a state
instead of being told one, and it is read-only, so it grants nothing and changes nothing.

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
| Challenge 3A bonus: circuit breaker | `ScannerCircuitBreaker`, consulted in `ScannerClient.scan` | `ScannerCircuitBreakerTest`, `QuarantineCircuitBreakerTest`; `scripts/resilience.py` Part E |
| Challenge 1: consistent after a crash | `ScanReclaimJob`, `ScanSlotRepository.releaseStale`, `QuarantineRepository.settleStaleScan` | `ScanReclaimJobTest`; `evidence/challenge-1-concurrency/reclaim-run.txt` |
| BR4 support: an authoritative read for Release | `QuarantineService.getByFileId`, `GET /api/v1/quarantine/by-file/{fileId}` | `QuarantineServiceTest.aFileCanBeReadThroughTheQuarantineRecordItOwns`, `QuarantineHttpTest.theSameRecordIsReachableByTheFileIdItBelongsTo`; `scripts/verification.py` |
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
| `scan_started_at` | timestamp | null until a scan is claimed; only the reclaim sweep reads it |

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

`mvn -pl quarantine-service -am test` runs 47 tests: 14 unit for the state machine, the
create race, the verdict handling, capacity and the reclaim rules; 2 HTTP tests for the
create/scan/read paths including the by-file address; 7 HTTP resilience and capacity tests
against a stub Scanner running on the same port; 1 full circuit-breaker lifecycle test on
its own port; 8 `ScannerClientTest` cases for the retry curve and failure reporting; and 8
`ScannerCircuitBreakerTest` cases for the transitions on a clock the test controls. Test
classes use distinct fixed ports (18082, 18093, 18094) because two cached Spring contexts
cannot bind the same one. Captured reports are in
[`evidence/tests`](../evidence/tests)
(`quarantine-unit-tests.txt`, `quarantine-http-tests.txt`,
`quarantine-test-environment.txt`, `quarantine-persistence.txt`).

Live, cross-process evidence:

```bash
python3 scripts/concurrency.py   > evidence/challenge-1-concurrency/run.txt
python3 scripts/reclaim.py       > evidence/challenge-1-concurrency/reclaim-run.txt
python3 scripts/resilience.py    > evidence/challenge-3a-resilience/run.txt
python3 scripts/quarantine-persistence.py > evidence/tests/quarantine-persistence.txt
```

Each starts its own packaged JARs on ephemeral ports against temporary databases, so a
run is repeatable and leaves nothing behind.

## Limitations

* The scan is synchronous: the caller waits for Scanner's verdict, and the retry backoff
  sleeps on the request thread. That is what makes the Challenge 3A behaviour observable
  in a single log; an async worker with a queue would be the production shape.
* There is no fallback verdict. A `FAILED` file stays `FAILED` and is not rescanned,
  because a second scan of the same file would be a second operation; the operator's
  remedy is to quarantine a new intake submission. The circuit breaker is present but
  default-off, so it has to be switched on deliberately rather than being assumed.
* Capacity is a per-service-table limit, so it protects one Quarantine replica's load on
  Scanner. With several replicas the same table still arbitrates them, but the seed of the
  slot rows and `MAX_CONCURRENT_SCANS` must be agreed across replicas, and lowering the
  value leaves the old rows in the table until the database is recreated.
* Reclaim trusts the age, so `QUARANTINE_SLOT_STALE_MILLIS` must stay above the whole
  retry budget. It is enforced as a startup warning rather than a hard failure, because an
  operator who raises the timeout and lowers the age in one change should see the
  consequence in the log rather than be refused at boot.
* A `HELD` slot with a `CLEAN` record means the settle committed and the release of the
  slot did not; reclaim frees it after the cutoff, so the worst case is one wasted slot per
  crash rather than a permanently lost one.

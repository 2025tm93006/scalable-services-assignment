# Quarantine Service

Assignment 1, SEZG583. Quarantine owns the quarantine lifecycle of a file: it
records a file for quarantine, moves it through exactly one scan transition and
keeps the decision in its own H2 database. It is the owner of business rule **BR3**
and it keeps only Intake's `fileId`, never a copy of Intake's data.

Spring Boot 3.3.5, Java 21 target, port **8082**. It depends only on the shared
`common` module (health endpoint, correlation filter, error model).

## Start it

```bash
mvn -pl quarantine-service -am package
java -jar quarantine-service/target/quarantine-service-1.0.0-SNAPSHOT.jar
```

Run from the repository root so the database lands in `data/quarantine.mv.db`.
`GET http://localhost:8082/health` answers `{"status":"UP","service":"quarantine-service"}`.
Ports, credentials and the database path come from environment variables; see
[`.env.example`](.env.example).

## API

Contract: [`docs/openapi/quarantine.yaml`](../docs/openapi/quarantine.yaml).

| Method | Path | Result |
|---|---|---|
| POST | `/api/v1/quarantine` | 201 with `state: QUARANTINED`, or 409 if the file is already quarantined |
| POST | `/api/v1/quarantine/{id}/scan` | 200 with the resulting state, or 409 if the record was already scanned |
| GET | `/api/v1/quarantine/{id}` | 200 with the record, or 404 |
| GET | `/api/v1/quarantine/_evidence/summary` | 200 with `total` and `byState` counts |
| GET | `/health` | 200 |

`fileId` must be a UUID and `scannerMode` must be one of `CLEAN`, `INFECTED`, `FAIL`
or `SLOW`; anything else is a 400. Wrong methods keep 405 and non-JSON bodies keep
415. Every failure uses the shared `ApiError` shape and echoes `X-Correlation-Id`.

```bash
curl -i -X POST http://localhost:8082/api/v1/quarantine \
  -H 'Content-Type: application/json' -H 'X-Correlation-Id: demo-1' \
  -d '{"fileId":"3f2a4e6c-1b2d-4c5e-8f90-123456789abc","scannerMode":"CLEAN"}'
```

## State machine

`QUARANTINED → CLEAN | INFECTED | FAILED`. A record leaves `QUARANTINED` once and
never transitions again; `scannedAt` is null until that transition and is set with it.
`QuarantineService.scan` maps the requested mode deterministically:
`CLEAN → CLEAN`, `INFECTED → INFECTED`, `FAIL → FAILED`, `SLOW → CLEAN`.

## Assignment traceability

| Requirement | Code | Verification |
|---|---|---|
| BR3: one quarantine record per file | `QuarantineService.quarantine`, unique `fileId` column | `QuarantineServiceTest.alreadyQuarantinedFileCannotBeQuarantinedAgain`; `scripts/quarantine-persistence.py` |
| BR3: one scan transition per record | `QuarantineService.scan` state guard | `QuarantineServiceTest.aScannedRecordCannotBeScannedTwice`; `QuarantineHttpTest.createsScansFetchesAndSummarizesQuarantineRecord` |
| BR3: scan outcome follows the declared mapping | `QuarantineService.scan` switch | `QuarantineServiceTest.everyScannerModeResolvesToItsDeclaredState` |
| Consistent error model and correlation ID | shared `common` handler and filter | `scripts/quarantine-persistence.py` (409 bodies carry `correlationId`) |
| Records survive a restart | `application.yml`, file-backed H2 with `update` | `scripts/quarantine-persistence.py`; `evidence/tests/quarantine-persistence.txt` |

## Data design

One table, owned by this service alone:

| Column | Type | Notes |
|---|---|---|
| `id` | UUID | primary key, generated here |
| `file_id` | UUID | Intake's identifier; unique, no foreign key and no cross-database access |
| `state` | varchar | `QUARANTINED`, `CLEAN`, `INFECTED` or `FAILED` |
| `scanner_mode` | varchar | the mode the caller asked Scanner to simulate |
| `quarantined_at` | timestamp | not null |
| `scanned_at` | timestamp | null until the single transition |

Release receives the same `fileId` and `quarantinedAt` in its request body; no
service reads another service's database.

## Persistence

`spring.datasource.url` defaults to `jdbc:h2:file:./data/quarantine` with
`ddl-auto: update`, so records and their unique constraint survive a restart.
Before this change the service used `jdbc:h2:mem:` with `create-drop`, which erased
the state machine on shutdown and let a released file be quarantined again.
Tests are unaffected: `QuarantineHttpTest` pins an in-memory database, and
`scripts/quarantine-persistence.py` builds its own temporary file database.

## Tests

`mvn -pl quarantine-service -am test` runs 9 unit tests and 1 HTTP test against an
isolated in-memory database. Captured reports are in
[`evidence/tests`](../evidence/tests) (`quarantine-unit-tests.txt`,
`quarantine-http-tests.txt`, `quarantine-test-environment.txt`,
`quarantine-persistence.txt`).

## Limitations

* The scan outcome is taken from the caller's `scannerMode` field. Quarantine does not
  yet call Scanner over REST, so the Challenge 3A timeout, retry and backoff behaviour
  belongs to the caller until that client is added.
* A duplicate quarantine is guarded by `existsByFileId` before the insert. Two
  simultaneous requests let the loser hit the unique constraint and the shared
  `Exception` handler answers 500 instead of 409. Catching the violation and
  re-answering as a conflict, as Intake and Release do, is the remaining work.
* The HTTP layer has one test path; validation and not-found cases are currently
  covered at the unit level and by `scripts/e2e.sh`.

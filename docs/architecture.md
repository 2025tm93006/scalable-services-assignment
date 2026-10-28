# Architecture

The diagrams here are drawn from the code in this repository, not from a design
intention. Every node name is a class, table, endpoint or configuration value that
exists in the source, and each diagram says which test or script proves it. Mermaid
renders on GitHub, in the IDE preview and in the report PDF export.

## Component and deployment view

Four independent Spring Boot entry points, one HTTP port and one file-based H2
database each. `common` is a compile-time library, not a running service. The only
edge from one service to another is Quarantine's `ScannerClient`.

```mermaid
flowchart LR
    Client([Client<br/>curl, scripts, e2e])

    subgraph common["common module (library, packaged into each service)"]
        Filter[CorrelationIdFilter<br/>X-Correlation-Id + MDC]
        Handler[GlobalExceptionHandler<br/>ApiError 400/404/409/405/415/500]
        Health[HealthController<br/>GET /health]
    end

    subgraph i["intake-service :8081"]
        Intake[IntakeController<br/>IntakeService<br/>IntakeRecordWriter]
        IDB[("H2 file<br/>data/intake.mv.db<br/>file_record")]
    end

    subgraph q["quarantine-service :8082"]
        Quarantine[QuarantineController<br/>QuarantineService<br/>QuarantineRecordWriter]
        Capacity[ScanCapacityService<br/>ScanSlotRepository]
        QDB[("H2 file<br/>data/quarantine.mv.db<br/>quarantine_records<br/>scan_slots")]
    end

    subgraph s["scanner-service :8083"]
        Scanner[ScannerController<br/>ScannerService<br/>ScanAttemptWriter]
        SDB[("H2 file<br/>data/scanner.mv.db<br/>scan_attempt")]
    end

    subgraph r["release-service :8084"]
        Release[ReleaseController<br/>ReleaseService<br/>ReleaseRecordWriter]
        RDB[("H2 file<br/>data/release.mv.db<br/>release_record")]
    end

    Client -->|POST /api/v1/files<br/>Idempotency-Key| Intake
    Client -->|POST /api/v1/quarantine| Quarantine
    Client -->|POST /api/v1/releases| Release
    Client -.->|GET /api/v1/scans/fileId<br/>read-back proof only| Scanner

    Quarantine --> Capacity
    Capacity -->|claim / release<br/>conditional UPDATE| QDB
    Quarantine -->|save / settle| QDB
    Quarantine -->|POST /api/v1/scans<br/>timeout, retry, backoff + jitter| Scanner
    Scanner -->|insert / finish| SDB
    Intake --> IDB
    Release --> RDB

    Filter -.-> i
    Filter -.-> q
    Filter -.-> s
    Filter -.-> r
```

Reading the edges:

* The `Quarantine -> Scanner` edge is the assignment's service-to-service REST call.
  It is the caller of Challenge 3A: `scanner.connect-timeout-millis`,
  `scanner.read-timeout-millis`, `scanner.max-attempts`,
  `scanner.retry-base-millis`, `scanner.retry-jitter-millis`.
* The `Client -.-> Scanner` dashed edge is not part of the transaction. `scripts/e2e.sh`
  reads `GET /api/v1/scans/{fileId}` afterwards to prove the call really reached
  Scanner's own database.
* Release is called directly by the client with the quarantine evidence
  (`fileId`, `quarantineState`, `quarantinedAt`) in the body. No service opens another
  service's database, so there is no shared-schema edge anywhere in this diagram.
* `data/quarantine.mv.db` holds two tables because Quarantine owns two contended
  resources: the record (unique `fileId`) and the scanner capacity slot table.

Proven by `scripts/e2e.sh` (28 checks) and `bash scripts/health.sh`.

## Happy-path sequence

One file, one quarantine record, one scan, one release. The scan transition claims a
capacity slot before the call and releases it in a `finally` block, so a failure still
returns the slot.

```mermaid
sequenceDiagram
    autonumber
    participant C as Client
    participant I as IntakeService
    participant Q as QuarantineService
    participant SC as ScanCapacityService
    participant CL as ScannerClient
    participant A as ScannerService
    participant R as ReleaseService

    C->>I: POST /api/v1/files (Idempotency-Key: k1)
    I->>I: save file_record, unique key decides any race
    I-->>C: 201 fileId

    C->>Q: POST /api/v1/quarantine {fileId, scannerMode}
    Q->>Q: existsByFileId false, then saveAndFlush in REQUIRES_NEW
    Q-->>C: 201 state=QUARANTINED

    C->>Q: POST /api/v1/quarantine/{id}/scan
    Q->>SC: claim(fileId)
    SC->>SC: UPDATE scan_slots SET status='HELD' WHERE slot=i AND status='FREE'
    SC-->>Q: slot 1
    Q->>Q: state=SCANNING, committed before the call
    Q->>CL: scan(fileId, mode)
    CL->>A: POST /api/v1/scans (X-Correlation-Id forwarded)
    A-->>CL: 200 result=CLEAN, completedAt
    CL-->>Q: ScanResult
    Q->>Q: state=CLEAN, scannedAt=Scanner's completedAt
    Q->>SC: release(slot 1, fileId) in finally
    Q-->>C: 200 state=CLEAN

    C->>R: POST /api/v1/releases {fileId, CLEAN, quarantinedAt}
    R->>R: requireReleasable then save
    R-->>C: 201 state=RELEASED
```

Proven by `QuarantineHttpTest`, `scripts/e2e.sh` (which asserts Quarantine's `scannedAt`
equals Scanner's stored `finishedAt`) and
`evidence/tests/e2e-run.txt`.

## Failure and contention sequence

Three ways the scan does not complete cleanly, all of which the client is told about
in the caller's own language rather than as a 500.

```mermaid
sequenceDiagram
    autonumber
    participant C as Client
    participant Q as QuarantineService
    participant SC as ScanCapacityService
    participant CL as ScannerClient
    participant A as ScannerService

    rect rgb(60, 60, 30)
        Note over C,A: Timeout, then a retry that succeeds (Challenge 3A)
        C->>Q: POST /api/v1/quarantine/{id}/scan
        Q->>SC: claim(fileId) -> slot 1
        Q->>CL: scan(fileId, SLOW)
        CL->>A: attempt 1 POST /api/v1/scans
        A--xCL: no response within read-timeout-millis
        CL->>CL: log scanner_attempt_failed (TimeoutException)
        CL->>CL: sleep base * 2^(attempt-1) + jitter
        CL->>A: attempt 2 POST /api/v1/scans
        A-->>CL: 200 result=CLEAN (same fileId, one stored attempt)
        CL-->>Q: ScanResult
        Q->>SC: release(slot 1) in finally
        Q-->>C: 200 state=CLEAN
        Note over A: scan_attempt still holds exactly one row for the fileId
    end

    rect rgb(30, 60, 40)
        Note over C,A: Scanner unreachable for the whole budget
        C->>Q: POST /api/v1/quarantine/{id}/scan
        Q->>SC: claim(fileId) -> slot 1
        Q->>CL: scan(fileId, CLEAN)
        loop attempts 1..max-attempts, backoff between them
            CL->>A: POST /api/v1/scans
            A--xCL: ConnectException / ClosedChannelException
        end
        CL-->>Q: ScannerUnavailableException
        Q->>Q: state=FAILED, settled once
        Q->>SC: release(slot 1) in finally
        Q-->>C: 200 state=FAILED (record is not left in SCANNING)
    end

    rect rgb(60, 30, 30)
        Note over C,SC: Capacity exhausted (Challenge 1)
        C->>Q: POST /api/v1/quarantine/{id}/scan (20th parallel request)
        Q->>SC: claim(fileId)
        SC->>SC: every slot UPDATE matched 0 rows
        SC-->>Q: -1
        Q-->>C: 409 Scanner capacity exhausted
        Note over Q: the record stays QUARANTINED, so the scan can be retried later
    end

    rect rgb(40, 40, 70)
        Note over C,Q: Concurrent create for the same fileId
        C->>Q: POST /api/v1/quarantine {fileId} (8 at once)
        Q->>Q: one saveAndFlush passes the unique fileId index
        Q->>Q: seven raise DataIntegrityViolationException
        Q-->>C: 1 x 201 and 7 x 409 File is already quarantined
    end
```

Proven by `QuarantineScanResilienceTest` (in-JVM, stub Scanner on the same port),
`scripts/resilience.py` (`evidence/challenge-3a-resilience/run.txt`) and
`scripts/concurrency.py` (`evidence/challenge-1-concurrency/run.txt`).

## Quarantine state machine

`QUARANTINED` leaves once. `SCANNING` exists only while a capacity slot is held, so a
concurrent scan request can see it and is refused, and no record can end in
`SCANNING` when a request finishes.

```mermaid
stateDiagram-v2
    [*] --> QUARANTINED: POST /api/v1/quarantine<br/>(unique fileId wins a race)
    QUARANTINED --> SCANNING: slot claimed<br/>committed before the Scanner call
    QUARANTINED --> QUARANTINED: 409 Scanner capacity exhausted<br/>(no slot, nothing changed)
    SCANNING --> CLEAN: Scanner result=CLEAN
    SCANNING --> INFECTED: Scanner result=INFECTED
    SCANNING --> FAILED: Scanner result=FAIL, an unexpected<br/>result, or Scanner unreachable
    CLEAN --> [*]: Release accepts it (BR4)
    INFECTED --> [*]: Release refuses it (BR4)
    FAILED --> [*]: Release refuses it (BR4)
```

Settled states are final: a second scan of any record is 409 `File has already been
scanned`, including of a `FAILED` one, because a retry would be a second operation on
the same file.

Proven by `QuarantineServiceTest`, `QuarantineScanResilienceTest` and the state
counts in `GET /api/v1/quarantine/_evidence/summary`.

## Data ownership

Four separate H2 databases. The `fileId` value is the only identifier that crosses a
boundary, and it crosses as data in a request body, never as a join. Entity names below
are the JPA table names in capitals; each block is the columns of that table exactly as
the `@Column` declarations define them, with the constraint that arbitrates a race
marked `PK` or `UK`.

```mermaid
erDiagram
    FILE_RECORD {
        uuid id PK "the fileId the caller receives"
        varchar idempotency_key UK "255, unique - BR1 and BR2"
        varchar fingerprint "64, SHA-256 of the canonical body"
        varchar filename "255"
        varchar content_type "255"
        bigint size_bytes
        varchar sha256 "64"
        varchar scanner_mode "16, CLEAN/INFECTED/FAIL/SLOW"
        varchar status "16"
        timestamp created_at
        text response_body "LOB, replayed verbatim"
    }
```

| Table | Owner | Contended resource | Rule |
|---|---|---|---|
| `file_record` | Intake | `uk_file_idempotency_key` | BR1, BR2 |

```mermaid
erDiagram
    QUARANTINE_RECORDS {
        uuid id PK
        uuid file_id UK "unique, Intake's fileId, no foreign key"
        varchar state "QUARANTINED/SCANNING/CLEAN/INFECTED/FAILED"
        varchar scanner_mode "not null"
        timestamp quarantined_at "not null"
        timestamp scanned_at "null until the single transition"
    }
    SCAN_SLOTS {
        int slot_number PK "1..max-concurrent-scans"
        varchar status "FREE or HELD"
        uuid file_id "holder, null when free"
        timestamp acquired_at
        timestamp released_at
    }
    QUARANTINE_RECORDS ||--o| SCAN_SLOTS : "a running scan holds one slot"
```

| Table | Owner | Contended resource | Rule |
|---|---|---|---|
| `quarantine_records` | Quarantine | unique `file_id` | BR3 |
| `scan_slots` | Quarantine | `status='FREE'` in the claim predicate | Challenge 1 |

The relationship above is the one invariant that is not a key: a `HELD` slot row names
a file whose record is in `SCANNING`, and both are set back before the response is
written. `ScanSlotRepository.release` matches on `slotNumber`, `status='HELD'` *and*
`fileId`, so a late release cannot free a slot a different file has already retaken.

```mermaid
erDiagram
    SCAN_ATTEMPT {
        bigint id PK "generated"
        uuid file_id UK "unique - one scan per file"
        varchar mode "16, CLEAN/INFECTED/FAIL/SLOW"
        varchar result "16, null while in flight"
        timestamp started_at
        timestamp finished_at "null while in flight"
    }
```

| Table | Owner | Contended resource | Rule |
|---|---|---|---|
| `scan_attempt` | Scanner | `uk_scan_attempt_file_id` | makes Quarantine's retries duplicate-free |

```mermaid
erDiagram
    RELEASE_RECORD {
        uuid file_id PK "Intake's fileId is the identity"
        varchar state "16, always RELEASED"
        timestamp quarantined_at "evidence accepted from the caller"
        timestamp released_at
    }
```

| Table | Owner | Contended resource | Rule |
|---|---|---|---|
| `release_record` | Release | primary key `file_id` | BR4 |

## Cross-service contract

| Caller | Callee | Endpoint | Body | Failure the caller must handle |
|---|---|---|---|---|
| Quarantine `ScannerClient` | Scanner | `POST /api/v1/scans` | `{fileId, mode}` | connect timeout, read timeout, 409 in-flight, no response at all |

Contract: [`openapi/scanner.yaml`](openapi/scanner.yaml), consumed as
`ScanResult{fileId, result, message, completedAt}`. The retry budget is
`scanner.*` in
[`quarantine-service/src/main/resources/application.yml`](../quarantine-service/src/main/resources/application.yml);
`scanner-service`'s `SCANNER_SLOW_MILLIS` (default 2000 ms) is below the default read
timeout (5000 ms), so the shipped defaults never time out. The evidence scripts pass
both values on the command line to put a slow scan deliberately over the budget without
changing any code or packaged artifact.

`X-Correlation-Id` is forwarded from the original client request through
`MDC("correlationId")`, so one request is traceable across both service logs; the
evidence files show the same correlation ID in the Quarantine and Scanner lines.

## What is deliberately not here

* No message broker, no async worker and no database sharing. The scan is
  synchronous: the caller waits for Scanner's verdict, which is what makes the
  timeout and retry behaviour observable.
* No circuit breaker or bulkhead. Challenge 3A asks for a timeout, retry with
  exponential backoff and jitter, and graceful failure; a breaker would hide the
  retry pattern the evidence has to show.
* No in-memory semaphore for capacity. The slot table is the contended resource so
  that the database, not one JVM, decides the winner and the allocation survives a
  restart — see `evidence/challenge-1-concurrency/README.md`.
* Retries sleep on the request thread. With Tomcat's default 200 worker threads this
  is acceptable for the demonstration load; a production caller would move the scan
  off the request thread.

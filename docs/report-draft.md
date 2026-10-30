# Report draft — Secure File Quarantine and Release System

Draft text for the design report, structured on the ten sections the assignment
specifies so the team can lay it out in the PDF (maximum 15 main pages excluding
appendices). Everything here is generated from this repository: every claim names a
file, a test or an evidence artifact, and every number is from the recorded runs.
Replace `<...>` placeholders, export the diagrams from
[architecture.md](architecture.md), and keep claim and evidence side by side in the
layout so a marker can check them.

---

## 1. Problem and scope

A company receives files from an intake portal and must decide, on evidence rather
than on a claim, whether each file may be released back. One service cannot answer
that alone: the metadata of a file, its malware verdict, its quarantine lifecycle and
the terminal release decision are four different responsibilities with four different
failure modes, and the interesting failures all happen at the seams between them -
two requests quarantining the same file at once, a client resending a release, the
malware scanner being slow or down.

The application therefore splits into four services, each owning one business
capability and one private database:

| Service | One-sentence responsibility | Owns |
|---|---|---|
| Intake | Accept a file's metadata exactly once per submitted operation | `file_record` |
| Quarantine | Hold a file for exactly one scan and record the outcome | `quarantine_records`, `scan_slots` |
| Scanner | Simulate a malware scanner that can be slow, wrong or absent, and answer a retried file with the same result | `scan_attempt` |
| Release | Grant the terminal release of a file, but only if the owner of the state confirms it | `release_record` |

**Assumptions.** Files are already stored elsewhere; only metadata travels through these
APIs. The scanner is a mock built by this team, as the assignment requires, and its mode
is chosen by the caller so a demonstration can select a verdict - which is exactly why
the release decision is verified at Quarantine rather than accepted from a client.
Authentication, an authorisation model, a user interface and real malware analysis are
out of scope; so is a real payment or notification provider, which this domain does not
need. Correlation IDs, structured logs and environment-based configuration are in scope
because Assignment 2 deploys the same services on Docker and Minikube.

**Business rules.**

| Rule | Statement | Owner |
|---|---|---|
| BR1 | A repeated intake with the same `Idempotency-Key` and the same payload returns the original response and stores no second record | Intake |
| BR2 | The same `Idempotency-Key` with a different payload is rejected with 409 and leaves the original record untouched | Intake |
| BR3 | A file is quarantined once, scanned once, and its outcome follows `QUARANTINED → SCANNING → CLEAN \| INFECTED \| FAILED`, with the verdict coming from Scanner | Quarantine |
| BR4 | A file is released only when Quarantine's own record reports `CLEAN` with the timestamp the caller supplied; a claim that does not match is 409 and stores nothing, and a state that cannot be checked is 503 | Release |

Each rule is traced to its enforcing method and its tests in
[business-rules.md](business-rules.md); that table is the source for section 7.

**How the assignment's five conditions are met.** (a) four services with distinct
responsibilities. (b) one transaction spanning all four: intake, quarantine, scan at
Scanner, release. (c) scanner capacity: `MAX_CONCURRENT_SCANS` slots that twenty
parallel requests can over-allocate. (d) intake submission and release are both unsafe
to repeat. (e) Scanner is the dependency that can be slow or fail, and it is our own
mock.

## 2. Service decomposition

Boundaries were drawn by business capability, not by technical layer, and each service
can be explained in one sentence (table above). What each owns:

* **Intake** owns the idempotency key, a SHA-256 fingerprint of the canonical request
  body, and the response body it replays. It owns no opinion about malware.
* **Quarantine** owns the state machine, the unique `fileId` index that arbitrates a
  duplicate create, and the slot ledger that arbitrates capacity. It does not decide a
  verdict; it asks for one.
* **Scanner** owns the attempt row, keyed by `fileId`, so a file is scanned at most once
  even when its caller retries.
* **Release** owns the terminal decision and stores only `fileId`, `RELEASED` and two
  timestamps.

**Alternatives rejected.**

* *One "quarantine-and-release" service.* Both enforce a rule, but the failure modes
  differ: a scan depends on a flaky downstream, a release depends on being able to
  check a state. Merging them would make a Scanner outage block releases.
* *Quarantine calling Release on a clean verdict* (see section 8, decision 3).
* *A shared database with a `files` table joined across services*, which would break
  data ownership and turn each service into a layer rather than a capability.
* *A "scanner-client" or "database" service*: a technical layer, not a business one.

## 3. Architecture

Insert the component/deployment diagram and both sequence diagrams from
[architecture.md](architecture.md). Two edges cross service boundaries and both are
REST over HTTP with a documented budget:

```
Client      -> Intake       POST /api/v1/files            (Idempotency-Key header)
Client      -> Quarantine   POST /api/v1/quarantine
Client      -> Release      POST /api/v1/releases
Quarantine  -> Scanner      POST /api/v1/scans            timeout, retry, backoff+jitter, breaker
Release     -> Quarantine   GET  /api/v1/quarantine/by-file/{fileId}   timeout, retry, backoff+jitter
Client     -.-> Scanner     GET  /api/v1/scans/{fileId}   read-back proof in tests only
```

The longest chain a request walks is two hops in each case, which keeps the design
inside the rule about avoiding long synchronous chains; nothing calls itself in a loop
and no service calls a third on every request.

State machine: `QUARANTINED → SCANNING → CLEAN | INFECTED | FAILED`, settled exactly
once. `SCANNING` exists only while a capacity slot is held. A killed process that leaves
a record there is settled `FAILED` by the reclaim sweep, so no record ends in a
transient state.

## 4. Data design

Four file-based H2 databases, four credential sets, no shared tables, no cross-service
foreign keys or joins, and `fileId` as the only identifier that crosses a boundary. The
ER diagrams come from [architecture.md](architecture.md); the ownership table:

| Table | Service | Contended resource that arbitrates a race |
|---|---|---|
| `file_record` | Intake | unique `idempotency_key` |
| `quarantine_records` | Quarantine | unique `file_id` |
| `scan_slots` | Quarantine | `status='FREE'` in the claim predicate |
| `scan_attempt` | Scanner | unique `file_id` |
| `release_record` | Release | primary key `file_id` |

Cross-service references are handled by passing ids and, where a decision depends on
another service's data, by asking that service over REST instead of reading its tables.
Release is the case in point: it keeps `quarantined_at` for audit but confirms the
state by calling Quarantine.

## 5. API design

Versioned paths, `Location`-free but status-correct responses, bean validation on every
request body, and one error format for all four services:

```json
{"timestamp":"2026-10-30T04:26:39Z","status":409,"error":"Conflict",
 "message":"Quarantine reports this file as INFECTED, not CLEAN",
 "path":"/api/v1/releases","correlationId":"verification-2f0c...","fieldErrors":{}}
```

| Status | Meaning used here |
|---|---|
| 200 / 201 | read, state transition / created or replayed idempotent operation |
| 400 | validation, malformed JSON, non-UUID path |
| 404 | no record for that identifier |
| 405 / 415 | wrong method / body is not JSON |
| 409 | a business conflict: duplicate, settled record, capacity exhausted, claim that does not match |
| 500 | an unexpected failure, details only in the log |
| 503 | a dependency needed to decide did not answer within its budget |

Endpoint tables and sample calls live in the four contracts:
[`openapi/intake.yaml`](openapi/intake.yaml),
[`openapi/quarantine.yaml`](openapi/quarantine.yaml),
[`openapi/scanner.yaml`](openapi/scanner.yaml),
[`openapi/release.yaml`](openapi/release.yaml). Every service also exposes
`GET /health` and echoes `X-Correlation-Id`, which is propagated across service calls
so one request appears in every log it touches.

## 6. Engineering challenges

### Challenge 1 — concurrency on a contended resource (mandatory)

**Problem.** Twenty scans arrive at once for a capacity of two. Any
read-then-write in application code can let more than two through.
**Design choice.** Scanner capacity is a table, `scan_slots`, one row per slot. A claim
is a single conditional update - `UPDATE scan_slots SET status='HELD', file_id=?, ...
WHERE slot_number=? AND status='FREE'` - and the code branches on `rowsUpdated == 1`,
which is the database's answer rather than an application's guess. A duplicate
quarantine is handled the same way: the unique `file_id` index decides, and the loser's
constraint violation is answered 409.
**Alternatives considered.** A `Semaphore` or an `AtomicInteger` in front of the table
(rejected: only one JVM sees it, and the allocation disappears on restart); `SELECT FOR
UPDATE` then update (rejected: two statements, and an easy window to get wrong);
optimistic version columns (kept as a fallback: correct, but it turns expected
contention into retries and exceptions).
**Evidence.** `evidence/challenge-1-concurrency/run.txt`: eight simultaneous creations
of one file give 1x201 + 7x409 and `total=1`; twenty parallel scans at capacity 2 give
exactly 2x200 and 18x409 `Scanner capacity exhausted`, no 500 anywhere, the ledger back
to `FREE 2 / HELD 0`, and exactly 20 `scan_completed` lines in Scanner for 20 files.
`reclaim-run.txt` adds the crash case: a `SIGKILL`ed scan leaves `HELD 1 / SCANNING 1`
readable after a restart, and one sweep restores both.
**Limitations.** Capacity is one table in one service's database: correct across
replicas of Quarantine that share it, but the row seed and the configured maximum have
to agree. Reclaim trusts an age cutoff, which must stay above the retry budget; it warns
rather than refuses when it does not.

### Challenge 2 — idempotency (mandatory)

**Problem.** An unsafe operation - accepting a file, granting a release - must be
repeatable without a second effect, including when two copies arrive simultaneously.
**Design choice.** Intake stores key, fingerprint and the response body together;
`idempotency_key` has a unique index, so the winner of a race is the row that
committed and the loser replays it instead of failing. Release is idempotent on
`fileId` as its primary key.
**Alternatives considered.** A separate "processed keys" cache (rejected: two stores,
one of them authoritative, and the race moves rather than disappears); a distributed
lock (rejected: a second system to run, for no gain over a unique index).
**Evidence.** `evidence/challenge-2-idempotency/run.txt` - byte-identical replay
including `receivedAt`, 409 for a different payload, eight simultaneous requests giving
eight 201s with one `fileId` and `databaseRecordCount: 1`. Release's replay, its
primary-key race and its "an unrelated integrity failure is not a replay" case are in
`ReleaseServiceTest` and `evidence/tests/release-*.txt`.
**Limitations.** A replay is keyed on a caller-chosen string, so key hygiene matters;
the fingerprint comparison is over the canonical JSON, so whitespace and property order
do not change it but a changed value does.

### Challenge 3A — resilience, chosen from 3A to 3D

**Problem.** Scanner is the dependency that can be slow or fail. The caller must not
hang, must not duplicate work, and must not end up with a half-run scan.
**Design choice.** A budget per call - connect and read timeouts, at most
`max-attempts` tries, delay `retry-base * 2^(attempt-1) + random(0..jitter)` - on the
JDK HTTP client, with `X-Correlation-Id` forwarded. Exhausting it settles the file
`FAILED` and releases the slot in a `finally` block, so the caller receives a state,
never a 500 and never a record left in `SCANNING`.
**Alternatives considered.** Fail-fast with no retries (rejected: a single transient
timeout would strand a file); retries without jitter (rejected: replicas that fail
together retry on the same tick); a queue and a worker (rejected for this assignment -
see section 8); the same call through `SimpleClientHttpRequestFactory` (rejected after
measurement: its keep-alive reuse turns a timeout into a parsing error on the *next*
attempt, so the log misreports what happened).
**Evidence.** `evidence/challenge-3a-resilience/run.txt`, thirty-five checks: Part A a
slow Scanner timed out twice, retried at doubled delays, one stored attempt; Part B a
stopped Scanner answered `200 state=FAILED` after exactly three attempts with the slot
returned; Part C recovery without intervention; Part D a replay returning the identical
`completedAt` with `totalAttempts` unchanged; Part E the bonus - after two exhausted
budgets the circuit opens, the third scan is refused at 13 ms with `httpAttemptsMade=0`,
and one probe after the cooldown closes it.
**Limitations.** The scan is synchronous and backoff sleeps on the request thread, which
is what makes the behaviour observable in one log at this scale. `FAILED` is final by
design, so a file is not silently rescanned. The breaker is off by default, because with
it on a scan may never reach Scanner at all.

## 7. Testing strategy and results

Three layers, all runnable from the repository root:

| Layer | What it proves | Result |
|---|---|---|
| Unit | each business rule in isolation, with persistence and the HTTP clients mocked | `IntakeServiceTest` 13, `QuarantineServiceTest` 14, `ScannerServiceTest` 14, `ReleaseServiceTest` 19 |
| HTTP/database | the real application over a socket against an isolated in-memory H2, including concurrency and the stubbed dependency | `IntakeHttpTest` 23, `QuarantineHttpTest` 2, `QuarantineScanResilienceTest` 7, `QuarantineCircuitBreakerTest` 1, `ScannerHttpTest` 20, `ReleaseHttpTest` 27 |
| Client and mechanism | retry curves, jitter bounds, failure reporting, breaker transitions, reclaim rules | `ScannerClientTest` 8, `ScannerCircuitBreakerTest` 8, `ScanReclaimJobTest` 7, `QuarantineStateVerifierTest` 7, `QuarantineClientTest` 8 |
| Scripted, cross-process | the transaction and the three challenges against packaged JARs in separate processes | `e2e.sh` 32 checks, `concurrency.py`, `reclaim.py`, `resilience.py`, `verification.py` 22 checks, four persistence scripts |

`mvn clean package` runs 178 tests (Intake 36, Quarantine 47, Scanner 34, Release 61)
with zero failures, on Temurin 21 and SapMachine 25. Failure paths are deliberate:
400/404/405/409/415/500/503, the killed process, the stopped dependency, the lying
client. Reports and raw output are in `evidence/tests/`, screenshots in the appendix
show command, output and timestamp together, as the marking guide asks.

## 8. Design decisions and trade-offs

1. **Synchronous REST over a broker for the scan.** Chosen: the caller waits for the
   verdict, so timeout, retry and graceful failure are visible in one log and provable
   in one script. Rejected: a message queue, which needs a broker, hides the retry
   behaviour the evidence has to show, and would make "what happened to this request"
   a multi-process question. Accepted cost: a slow dependency occupies a request thread.
2. **Database-enforced concurrency over in-process locks.** Chosen: a conditional
   update returns `rowsUpdated`, which is an authoritative answer that survives a
   restart and is shared by every replica. Rejected: `Semaphore` (one JVM), and
   Redis or a `SELECT FOR UPDATE` dance (a second system for a problem a row already
   solves). Accepted cost: the slot rows are real data and can leak, which is why the
   reclaim sweep exists.
3. **Release verifies, rather than Quarantine orchestrating.** The question came from
   inside the team: if Scanner says CLEAN, should Quarantine call Release? Rejected on
   three grounds - ownership (the service that produced the verdict would also be
   enforcing the release rule, reducing Release to a store), side effects on retries (a
   retried scan would re-trigger a release whose whole difficulty is that the first
   attempt may already have taken effect), and semantics (release is an authorisation
   decision, not a consequence of a verdict; the design rule against long synchronous
   chains also warns against services that call each other on every request). Chosen
   instead: the client still calls Release, and Release asks Quarantine to confirm.
   Rejected alternative for the same problem: a signed release token issued by
   Quarantine - cleaner separation, but signing and verification are authentication,
   explicitly out of scope here.
4. **Circuit breaker in-house, and off by default.** One guard did not justify adding a
   resilience library to a build four students share, and a mechanism the team can walk
   through line by line is worth more in the viva than a dependency. Off by default so
   the timeout evidence stays exactly reproducible; counts failed *calls*, so
   enabling it never changes one scan's budget. Accepted cost: state lives in one
   process, so each replica trips on its own.
5. **Verification is a read with retries, and never falls back to the claim.** A 404 is
   an answer (never quarantined → 409), silence after the budget is an outage (503) and
   nothing is stored. Rejected: trusting the caller when the check fails, which would
   reintroduce the exact weakness the check exists to close; and rejecting a release
   because the timestamp differs in the last microseconds, which would tie a legitimate
   release to the host clock - hence the millisecond tolerance.
6. **Database-per-service with ids only, and one shared library that stays small.**
   `common` holds the health endpoint, the correlation filter and the error model - no
   HTTP client and no domain code, so each service owns its outbound calls and can be
   built alone. The visible cost is that `ScannerClient` and `QuarantineClient`
   duplicate the same delay formula, which was accepted deliberately: two small copies
   beat a shared resilience abstraction that couples four independently deployable
   services at build time.

## 9. Limitations and future work

* Scan and release decisions are synchronous and the retry sleeps on the request
  thread; a queue plus worker and a pending-release state are the production shape.
* A `FAILED` file is never rescanned and a `RELEASED` file can never be revoked. Both
  are deliberate state-machine decisions, and both need an operator workflow the
  current API does not offer.
* Quarantine is trusted as the owner of a state, and Scanner is trusted for the mode it
  is told, because authentication is out of scope: no request is signed, no caller is
  identified, and audit is a correlation ID rather than a log service.
* H2 in file mode is embedded, so each service supports one process; Assignment 2's
  containers need either one instance per database or a server-backed database, and the
  slot seed and `MAX_CONCURRENT_SCANS` must be agreed across replicas.
* If Scanner's process dies mid-scan its attempt row stays in progress and later
  requests for that file get 409 until it is cleaned up; Release and Quarantine both
  have a sweep for that case, Scanner does not.
* The malware verdict is simulated and selected by the caller; no file content is read
  anywhere in the system.
* No metrics, tracing or dashboards. The structured logs carry a correlation ID across
  both edges, which is the hook Micrometer and OpenTelemetry would attach to.
* Tests are per-service plus scripted end-to-end; there is no contract test between the
  two API-consuming edges, and no chaos or load harness.

## 10. Video and documents

* Demo video (8-10 minutes, every member speaks): `<LINK>`
* Slides and screenshot appendix: `<LINK>`
* Repository: `<GITHUB URL>`, submitted branch `main`
* The video follows [demo-runbook.md](demo-runbook.md): architecture 2 min, live main
  flow with one failure 3 min, challenge scripts 3 min, of which the lying-client
  refusal, Part E's zero-attempt circuit refusal and reclaim.py's restored ledger are the
  moments to look for. Every member owns one service's part of the script and one
  decision from section 8.

---

## Appendix A — contribution table

One row per member, filled in by the team. Commit history is the cross-check:
`git shortlog -sne` and `git log --format='%h %an %s'`.

| Member | Service owned | Also contributed | Contribution factor |
|---|---|---|---|
| `<name>` | `<service>` | `<scripts, docs, fixes>` | `<0-1>` |
| `<name>` | `<service>` | `<...>` | `<...>` |
| `<name>` | `<service>` | `<...>` | `<...>` |
| `<name>` | `<service>` | `<...>` | `<...>` |

## Appendix B — individual reflections

Half a page each, in the member's own words: what you built, the hardest problem you
solved, and what you would change. Written by the person, not generated - a marker can
ask about it in the demo.

## Appendix C — AI-use declaration

Tools used and for what, per the course rule that AI may be used for learning and
boilerplate but must be declared, and that submitted code must be explainable in the
demo. Fill in and keep accurate:

| Tool | Used for | Not used for |
|---|---|---|
| `<assistant>` | `<e.g. drafting this report's structure, generating script scaffolding, summarising the assignment's requirements>` | `<e.g. deciding the service boundaries, choosing the concurrency mechanism>` |

Every design decision in section 8 was chosen and is explained by a team member, and
every artifact named here was executed by the team on a machine running the repository's
own README.

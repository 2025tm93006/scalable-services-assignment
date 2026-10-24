# Assignment 1 submission review and next steps

Reviewed on 24 October 2026 against `Assignment1_Microservices_Design_and_Development.docx`
from Downloads. The implementation reviewed is `/Users/amrurama/Desktop/assignment`.
The scaffold in this repository is a separate, intentionally minimal initial commit.
Statuses below describe the existing implementation, not the new scaffold.

Completed means the requested artifact or implementation is present with supporting
source/evidence. Partial means something exists but required content, correctness
or proof is missing. Not completed means the required final artifact was not found.
No assignment instructions embedded in the source document were treated as a
request to implement more features during this review.

## Submission package required by the guideline

| Item to submit | Status | Evidence and remaining work |
|---|---|---|
| Faculty-accessible GitHub repository link | Partial | Local scaffold prepared; remote publication and access verification tracked below. Existing implementation has no `.git` directory. Scaffold publication is not publication of the completed implementation. |
| Source code, tests and runnable README | Partial | Four Maven service modules, common utilities, README and scripts exist. Fresh-clone execution has not been demonstrated; implementation issues below remain. |
| Design and technical report PDF, max 15 pages excluding appendices | Partial | `docs/design-report.pdf` exists and has 6 pages. Required diagrams are not embedded; team material and submission links are placeholders. |
| Demo video link, 8 to 10 minutes, terminal/API execution, every member speaking | Not completed | A demo runbook exists, but no final video or usable link was found. |
| Repository `/evidence` with one subfolder per chosen challenge and claim-to-file READMEs | Partial | All three challenge folders have README, raw output and logs dated 17 October 2026. Fresh test and main-flow evidence and retry-no-duplicate proof remain missing. Some README file references are incomplete. |
| Contribution table in report appendix | Not completed | Placeholder only. Must reflect actual service ownership, contributions and commits per member; maximum team size is 4. |
| Half-page individual reflection per student | Not completed | Placeholder only. Cover own work, hardest problem solved and what to change. |
| AI-use declaration in report appendix | Not completed | Placeholder only. Include Codex scaffold/review assistance and all other tools actually used, with purposes and team review. |
| Google Drive link to video and other documents in report section 10 | Not completed | Placeholder only; test access from an account other than the owner's. |

## The ten report sections to submit

| Required section | Status | What exists and what is still required |
|---|---|---|
| 1. Problem and scope | Partial | Overview, assumptions, scope and exclusions exist. BR1 to BR4 are in `docs/business-rules.md`; incorporate the required BR list into the final PDF so the section is self-contained. |
| 2. Service decomposition | Partial | Responsibility/ownership/port table and rationale exist. Explicitly describe alternatives rejected for the service boundaries, and justify Release's distinct business responsibility. |
| 3. Architecture | Partial | Component, happy/failure sequence and lifecycle Mermaid sources are in `docs/architecture.md`. PDF contains text sequences and a link to the Markdown, but lacks the actual component diagram. Export readable diagrams with direction/protocol labels into the PDF. |
| 4. Data design | Partial | Per-service ER sources and ownership discussion exist. PDF has an ER text summary, not the four ER diagrams. Add diagrams and an explicit ownership table, and correct the claim that references are validated via APIs: Release currently trusts the client. |
| 5. API design | Partial | Endpoint table, common error example and four OpenAPI files exist. Add representative request/response/error examples and correct contract mismatches described below. Ensure final PDF links are usable. |
| 6. Engineering challenges | Partial | All three challenges have designs, scripts and captured outputs. Add alternatives and limitations per challenge; resolve concurrency compliance and add slow-timeout/retry deduplication evidence. |
| 7. Testing strategy and results | Partial | Four test classes, 7 recorded passing tests and scripts exist. Save fresh execution results, main-flow output, API negative tests and an honest environment description. |
| 8. Design decisions and trade-offs, at least 3 | Completed | Four decisions are documented: REST, client orchestration, H2, local semaphore. Update rationale when the implementation changes. |
| 9. Limitations and future work | Completed | Mock scanning, persistence, capacity coordination, workflow and operational limitations are documented. |
| 10. Google Drive link containing video and documents | Not completed | No usable link; also replace the report's stale absolute repository path with the GitHub URL. |

The PDF renderer strips Markdown link targets and leaves tables as pipe-delimited
text. Replace or improve this export so references and tables are useful in the
submitted PDF. The existing 6-page count is within the limit; recheck after adding
diagrams and final material. Do not claim the current PDF contains the Mermaid
diagrams merely because it references `architecture.md`.

## Technical requirements compared with code

| Guideline requirement | Status | Finding |
|---|---|---|
| At least four independently runnable services, own folders/dependencies | Completed | Intake, Quarantine, Scanner and Release each have a POM and Spring Boot application. `common` is a library, not a fifth service. |
| A meaningful transaction spans at least three services | Completed | `scripts/e2e.sh` drives Intake, Quarantine, Scanner and Release. Quarantine calls Scanner over REST; client orchestration is explicit. This is not a distributed atomic transaction, which is not a mandatory requirement for chosen option 3A. |
| A contended resource, repeat-unsafe operation and slow/failing mock dependency | Completed | Scanner capacity, intake creation and mock Scanner modes provide these cases. |
| Database per service and separate credentials, no direct cross-database access | Completed | Four distinct H2 configurations and users/passwords exist. References use UUIDs; no cross-database access was found. In-memory H2 is permitted but loses data on restart. |
| REST-only service communication | Completed | `QuarantineService` uses HTTP `RestClient` for Scanner; no broker or cross-service SQL was found. |
| Versioned paths and input validation | Completed | Business controllers use `/api/v1`; request records use Jakarta validation. See malformed-input error handling gap below. |
| Correct HTTP status codes and one consistent JSON error model | Partial | Common `ApiError` handler exists. Missing explicit handling for malformed JSON/type conversion means generic `Exception` handling can turn bad client input into 500. Add HTTP-level tests for 400/404/409 and correct the handlers. |
| OpenAPI spec for each service | Partial | Four files exist. Scanner declares `message` but actual response has `detail`. Release spec omits required `quarantinedAt` and makes it nullable although the request uses `@NotNull`. Several responses lack success/error schemas. |
| Every service exposes GET /health | Completed | Shared `HealthController` is component-scanned by all four applications. |
| Environment-based configuration | Completed | Ports, DB connection and Scanner URL/timeouts/retry values use environment-backed placeholders. `.env.example` is documentation; Java does not automatically load it. Scripts still hard-code some ports. |
| Structured logs with propagated correlation ID | Completed | Timestamp/level/MDC pattern plus key-value events; filter accepts/returns `X-Correlation-Id` and Quarantine propagates it to Scanner. A full client-orchestrated flow should reuse one ID across all calls; current e2e script does not. |
| At least four BRs, each mapped to implementation and test | Completed | BR1/2 intake replay/conflict, BR3 transitions and BR4 release gate are mapped in `docs/business-rules.md`. BR4 has incomplete branch and integrity coverage; see below. |
| Unit test for every core rule and one scripted main-flow test | Completed | Test classes exist for all four BRs; `scripts/e2e.sh` exists. Fresh run and captured evidence remain outstanding. |
| Maintainable API/business/data separation | Partial | Controllers/services/repositories are separate, but many classes are compressed onto one line. Format code and add focused tests for failure handling. |
| Fresh clone runnable using README alone | Not completed | No fresh-clone run is recorded. Challenge 1 README uses `mvn -pl ... -am spring-boot:run`, which can try to run non-application reactor modules; use packaged service JARs or install dependencies then run only the selected service. |

## Engineering challenge assessment

| Challenge | Status | What is proved and what remains |
|---|---|---|
| 1. Concurrency control, mandatory | Partial | Script issues 20 simultaneous requests; saved output shows exactly 2 HTTP 200 and 18 HTTP 409, 2 CLEAN / 18 QUARANTINED and 0 ACTIVE / 2 RELEASED slots. However admission uses an in-memory semaphore. The guideline asks for a proper contention mechanism such as row locks, optimistic versioning, atomic conditional update or unique constraint. Use database-backed admission for the shared capacity and prove it across replicas; the current scan-slot rows only record admission. |
| 2. Idempotency, mandatory | Completed with limits | Stored key/fingerprint and stable response fields, unique constraint and per-key lock exist. Saved output proves first call, repeat, changed-payload 409, 8 simultaneous same-key requests, one ID and one database row; replay logs exist. Response is reconstructed from persisted fields rather than stored as a serialized response. Current evidence demonstrates one running service instance. |
| 3A. Resilience, chosen option | Partial | Configurable connect/read timeouts, bounded retries, exponential backoff and jitter exist. Stopped-scanner evidence shows 3 attempts, 2 delays, FAILED and zero active slots. It does not exercise an accepted slow scan whose response times out, followed by retries, with one final Scanner row/effect. Add that proof. Circuit breaker is optional bonus. |
| 3B, 3C, 3D | Not required | Choose one option only; the implementation selected 3A. Do not add broker, saga or timed holds solely to satisfy this assignment. |

## Correctness issues to resolve before submission

1. **Release must verify authoritative state.** `release-service/.../ReleaseService.java`
   checks the request's `quarantineState` and timestamp without calling Quarantine.
   A fabricated UUID plus CLEAN and any timestamp can be released even if it was
   never quarantined. Query Quarantine by ID through REST and use its persisted
   state/timestamp. Reject missing, infected, scanning and failed files. Add tests
   for forged CLEAN, missing record, failed dependency and absent timestamp.
   Authentication is out of scope; business integrity is still required.
2. **Make capacity and file transitions atomic in the database.**
   `quarantine-service/.../QuarantineService.java` has a semaphore but no row lock
   or version on `QuarantineRecord`. Concurrent scans of the same file can read
   QUARANTINED together and both transition. Multiple service replicas each have
   independent permits. Persist capacity admission with appropriate locking or
   atomic updates and make per-file admission conditional on its current state.
3. **Prevent leaked admission on storage failure.** `slots.save(slot)` is before
   the `try/finally`, and the final `slots.save(slot)` precedes `capacity.release()`.
   Either persistence exception can skip releasing the semaphore. Restructure
   cleanup and test database-failure paths; consider the transaction boundary
   around slow external calls and retry sleeps.
4. **Exercise retry safety through HTTP.** Scanner's per-file lock and unique row
   help on a single instance, but the supplied test only mocks an already
   completed attempt. Force a real accepted scan to exceed the client read
   timeout, retry the same file identity, and inspect one final persisted effect.
   Clarify multi-instance behavior and resource usage while abandoned requests
   are still executing.
5. **Align contracts, error handling and documentation.** Fix the OpenAPI and
   bad-input issues above; add real request/response examples, portable script
   URLs and end-to-end correlation. Add a missing-timestamp BR4 unit test and
   broader legal/illegal transition coverage.

## Evidence and validation observed during this review

The supplied Surefire text reports list 7 tests, 0 failures, 0 errors and 0 skipped:
Intake 3, Quarantine 1, Scanner 1 and Release 2. A fresh run on a temporary source
copy during this review also passed all 7 tests after resolving the Maven mirror
problem described below. Challenge outputs/logs are dated 17 October 2026;
the live challenge scripts were not rerun during this review.

A new test attempt on a temporary copy failed before compilation because the
machine's Maven configuration redirects Central to
`artifactory.oci.oraclecorp.com`, which did not resolve even on a network-enabled
retry. A temporary settings file pointing to public Maven Central then allowed
the fresh test run to pass. Global Maven settings were not changed.
The interactive `java` command reports Java 25; `mvn -version` reports
Maven running Java 23. Use `mvn -version` when recording the actual build runtime.
Further validation results are recorded in `validation-results.md`.

No source implementation or old evidence was modified. Build products, IDE
files, caches and old evidence were not imported into the scaffold.

## Ordered next steps

1. **Publish the initial scaffold.** Use the GitHub repository
   `https://github.com/2025tm93006/scalable-services-assignment.git`. Verify faculty
   access and use real member identities for future commits. The scaffold is
   intentionally health-only and cannot yet satisfy the assignment.
2. **Agree ownership and define the final integrity rules.** Assign each member
   a service, record BR1 to BR4 and the release verification/capacity invariants.
   Keep a maximum of four students. Review APIs before importing implementation.
3. **Bring in the existing work in real, reviewable commits.** Port intake with
   replay/conflict tests, then quarantine states and database-backed admission,
   Scanner with deduplication, and Release with an authoritative API check.
   Import the existing working code as such; do not fabricate a historical
   development timeline or retroactively assign other people's contributions.
4. **Resolve build reproducibility.** Ensure a clean environment can download the
   pinned dependencies; use the intended JDK 21 (or document tested later JDK).
   Fix any Maven runner commands. Run `mvn clean test` and `mvn clean package` and
   keep test counts and command/runtime details in `evidence/tests/`.
5. **Run and record the main transaction.** Start all four packaged applications,
   check `/health`, run e2e, then demonstrate an infected/failed case and a forged
   release rejection. Save command, UTC timestamp, output and correlation logs.
6. **Recapture Challenge 1 on fresh state.** Set capacity 2, use at least 20
   concurrent requests and a Scanner delay long enough that requests overlap;
   set the read timeout above that delay. Prove exactly 2 successes / 18 capacity
   rejections and consistent database state. Also test simultaneous same-file
   scans and shared capacity across service replicas using the same database.
7. **Recapture Challenge 2.** Show first/repeated identical response, changed
   payload 409, simultaneous same-key requests and a single final row. Save
   replay logs and counts. Keep a separate key/run identifier for every run.
8. **Complete Challenge 3A evidence.** Record stopped-scanner graceful failure and
   a slow-response timeout with successful retry replay/no duplicate effect.
   Show attempts, backoff/jitter, final state and released capacity. Restart
   Scanner before the final happy-path demo.
9. **Finish the report PDF.** Complete the ten sections above, embed component,
   happy/failure sequences and all four ER diagrams, correct outdated claims,
   add readable API examples, test results and at least three justified
   trade-offs. Recheck the 15-page main-report limit.
10. **Finish individual material.** Fill the contribution table with actual
    commits, add every half-page reflection and a specific AI-use declaration.
    Include this scaffold/review and any AI-assisted implementation work.
11. **Record the 8 to 10 minute video.** Use roughly 1 minute for team/ownership,
    2 for architecture, 3 for main flow including failure and 3 for live challenge
    scripts. Every member speaks and explains their work. Show real terminal/API
    execution with readable commands, outputs and timestamps.
12. **Final submission check.** Clone the pushed repository into a clean location
    or another member's machine and follow only the README. Verify the PDF,
    evidence and video correspond to the submitted commit. Test faculty access
    to GitHub and Google Drive; submit the repository URL, PDF and video link.

Docker/Minikube deployment belongs to Assignment 2. Keep configuration ready for
it, but it is not an extra prerequisite for completing Assignment 1.

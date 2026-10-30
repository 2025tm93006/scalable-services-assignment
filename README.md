# Secure File Quarantine and Release System

Assignment 1 for Scalable Services SEZG583. The project has four independent
Spring Boot entry points, isolated H2 configuration, correlation IDs and GET /health.
Intake accepts metadata and deduplicates repeated intake requests. Quarantine
records a file, holds one scanner capacity slot while it asks Scanner over REST to
inspect the file, and moves it through its scan states; it refuses a second quarantine
or a second scan. Scanner returns deterministic mock results and replays a stored
result for a repeated or retried file. Release grants the terminal release of a file,
and verifies the state at Quarantine instead of accepting the caller's claim.

Two edges go between services, each with a connect and read timeout, a bounded number
of attempts and exponential backoff with jitter. Quarantine calling Scanner is the one
Challenge 3A is about: a slow or stopped Scanner settles the file `FAILED` gracefully,
and an optional circuit breaker stops calling a dependency that is known to be down.
Release calling Quarantine is a read-only verification, and a state that cannot be
checked is answered 503 rather than released on trust. See
[docs/architecture.md](docs/architecture.md) for the diagrams and
[docs/business-rules.md](docs/business-rules.md) for where each rule is enforced.

## Prerequisites

JDK 17 or later and Maven 3.9 or later. Maven must be able to resolve public
dependencies. The build compiles at Java 17 bytecode and the test suite is verified
on SapMachine 17 and SapMachine 25; Surefire passes `-Dnet.bytebuddy.experimental=true`
so Mockito can mock service classes on JDKs newer than the Byte Buddy version
Spring Boot 3.3.5 ships. All database credentials here are local demonstration values.

## Build and run

From this folder, run `mvn clean package`. Every service includes unit and
HTTP/database tests (184 tests in total: Intake 42, Quarantine 47, Scanner 34,
Release 61).
Run each command in a separate terminal after packaging:

```bash
java -jar intake-service/target/intake-service-1.0.0-SNAPSHOT.jar
java -jar scanner-service/target/scanner-service-1.0.0-SNAPSHOT.jar
java -jar quarantine-service/target/quarantine-service-1.0.0-SNAPSHOT.jar
java -jar release-service/target/release-service-1.0.0-SNAPSHOT.jar
```

| Service | Responsibility planned | Default port |
|---|---|---|
| Intake | Accept metadata and deduplicate intake requests | 8081 |
| Quarantine | Own quarantine states and scanner capacity, call Scanner, answer for a file | 8082 |
| Scanner | Simulate clean, infected, slow and failed scans | 8083 |
| Release | Verify the state at its owner and permit a clean release | 8084 |

The table is in port order; the commands above start each service after the one it
calls. Quarantine starts fine without Scanner and settles a scan `FAILED` once its retry
budget runs out; Release starts fine without Quarantine and answers 503 until it can
verify - except for a file that is already released, which replays from Release's own
table with no call out at all.

With all services running, run `bash scripts/health.sh` and then
`bash scripts/e2e.sh`, which drives one transaction through all four services and
asserts the failure paths. To demonstrate the system to someone, including for the
assignment video, follow
[docs/demo-runbook.md](docs/demo-runbook.md): terminals, exact commands, the output
lines worth pointing at, and the failure demos. Ctrl-C stops each service.
All four services store their H2 databases in `data/intake.mv.db`,
`data/quarantine.mv.db`, `data/scanner.mv.db` and `data/release.mv.db` and preserve
records across restarts. Start each service from
the repository root so it uses the same relative database path every time.
`scripts/persistence.py`, `scripts/quarantine-persistence.py`,
`scripts/scanner-persistence.py` and `scripts/release-persistence.py` prove that
durability by restarting each service against a temporary file database.
`scripts/concurrency.py`, `scripts/reclaim.py` and `scripts/resilience.py` are the
Challenge 1 and Challenge 3A checks, and `scripts/verification.py` drives the
release-verification boundary: each starts its own packaged JARs on ephemeral ports,
fires the parallel, failing or lying requests, asserts the invariants and exits non-zero
on any broken one, with the captured output in `evidence/challenge-1-concurrency/`,
`evidence/challenge-3a-resilience/` and `evidence/tests/`.
Each service runs its own tests against a separate in-memory database, so the
test suite never writes into `data/`.
Each service has its own database URL and credentials. For overrides, export SERVER_PORT, DB_URL,
DB_USERNAME, DB_PASSWORD, DB_DRIVER and DDL_AUTO in that service's terminal;
see its `.env.example`. Quarantine additionally reads `MAX_CONCURRENT_SCANS`, the
`SCANNER_*` timeout, retry, circuit-breaker and base-URL variables, and the
`QUARANTINE_RECLAIM_*` sweep settings; Release reads `QUARANTINE_BASE_URL` and the
`QUARANTINE_*` verification budget. These files are examples and are not
loaded automatically.

## Run with Docker

Each service has its own multi-stage `Dockerfile`, built from the repository root
because the module needs the parent POM and `common/`:

```bash
docker build -f intake-service/Dockerfile -t intake-service:1.0.0 .
```

Each image runs as a non-root user on a JRE 17 Alpine base, keeps its H2 database
under the `/app/data` volume and reports health to Docker through `GET /health`.

`docker-compose.yml` deploys all four as the Compose project `scalable-assignment`
(Compose requires lowercase project names) on one bridge network:

| Container | Host port | Depends on | Volume |
|---|---|---|---|
| `intake-service` | 8081 | - | `scalable-assignment_intake-data` |
| `scanner-service` | 8083 | - | `scalable-assignment_scanner-data` |
| `quarantine-service` | 8082 | Scanner healthy | `scalable-assignment_quarantine-data` |
| `release-service` | 8084 | Quarantine healthy | `scalable-assignment_release-data` |

Quarantine reaches Scanner at `http://scanner-service:8083` and Release reaches
Quarantine at `http://quarantine-service:8082`; `localhost` inside a container is that
container itself. Stop any services started with `java -jar` first, since they hold the
same host ports.

```bash
docker compose up -d --build --wait
docker compose ps
bash scripts/health.sh && bash scripts/e2e.sh
docker compose down          # add -v to also delete the database volumes
```

`bash scripts/flow.sh` sends one file through all four services under one correlation
ID - Intake, Quarantine, the scan Quarantine asks Scanner for, and the release Release
verifies at Quarantine - and then prints that ID's log lines from each container, which
shows the containers calling each other by service name.
`bash scripts/flow.sh INFECTED` shows the same flow ending in a 409 refused release. To
trace any request by hand, run `docker logs quarantine-service 2>&1 | grep <correlationId>`.

Host ports default to 8081-8084. If those are in use, set `INTAKE_PORT`,
`QUARANTINE_PORT`, `SCANNER_PORT` and `RELEASE_PORT`, and point the scripts at them
with `INTAKE_URL`, `QUARANTINE_URL`, `SCANNER_URL` and `RELEASE_URL`.

## Layout

`common/` holds HTTP health and correlation utilities. The four `*-service/`
modules each contain sources, test locations, a dependency file and configuration.
`docs/` holds the architecture diagrams, the business-rule traceability and the OpenAPI
contracts; `scripts/` holds repeatable checks; `evidence/` reserves a folder per
engineering challenge and tests.

See [Intake service](intake-service/README.md) for its API, business rules, database
design, tests and Challenge 2 evidence instructions. See
[Quarantine service](quarantine-service/README.md) for the state machine, the capacity
slots, the Scanner client and its retry budget. See
[Scanner service](scanner-service/README.md) for its scan modes, retry-safe
replay, persistence and tests. See
[Release service](release-service/README.md) for its API, BR4 enforcement,
persistence and tests.

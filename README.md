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

For host builds and tests: JDK 17 or later and Maven 3.9 or later. Maven must be able to
resolve public dependencies. The build compiles at Java 17 bytecode and the test suite is
verified on SapMachine 17 and SapMachine 25; Surefire passes
`-Dnet.bytebuddy.experimental=true` so Mockito can mock service classes on JDKs newer than
the Byte Buddy version Spring Boot 3.3.5 ships.

For container deployment: Docker Engine 24 or newer with Docker Compose v2, plus Git,
curl, and Python 3 for the verification scripts. The Dockerfiles install their own build
toolchain inside the image, so a reviewer does not need Java or Maven on the host. The
deployment is intended to run on Linux AMD64 and ARM64, macOS Intel and Apple silicon,
and Windows using Docker Desktop with a WSL2 Linux engine.

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

The Maven build runs inside the image; the container runtime is the non-root
Eclipse Temurin 17 JRE image. That runtime base is available for both AMD64 and
Apple-silicon ARM64, so the application itself does not have to be rebuilt for a
different CPU architecture. Each service keeps its H2 database under the `/app/data`
volume and reports health to Docker through `GET /health`.

`docker-compose.yml` deploys all four as the Compose project `scalable-assignment`
(Compose requires lowercase project names) on one bridge network:

| Container | Default host port | Depends on | Volume |
|---|---:|---|---|
| `intake-service` | 8081 | - | `scalable-assignment_intake-data` |
| `scanner-service` | 8083 | - | `scalable-assignment_scanner-data` |
| `quarantine-service` | 8082 | Scanner healthy | `scalable-assignment_quarantine-data` |
| `release-service` | 8084 | Quarantine healthy | `scalable-assignment_release-data` |

Configuration is supplied through environment variables. Compose uses the internal
service URLs by default, while `SCANNER_BASE_URL` and `QUARANTINE_BASE_URL` remain
overridable. Database URLs, usernames, optional passwords, capacity, and demo behavior
are also environment-configured. Copy `.env.example` to `.env` only if you want local
overrides; never commit `.env` or real credentials.

```bash
cp .env.example .env   # optional; .env is gitignored
docker compose up -d --build --wait
docker compose ps
bash scripts/health.sh && bash scripts/e2e.sh
bash scripts/flow.sh
bash scripts/flow.sh INFECTED
docker compose stop
docker compose up -d --wait
bash scripts/health.sh && bash scripts/e2e.sh
docker compose down    # preserves named volumes
docker compose down -v # removes the demo database volumes
docker compose up -d --build --wait
```

Quarantine reaches Scanner at `http://scanner-service:8083` and Release reaches
Quarantine at `http://quarantine-service:8082`; `localhost` inside a container is that
container itself. Stop any services started with `java -jar` first, since they hold the
same host ports. To run alongside local JARs, override `INTAKE_PORT`,
`QUARANTINE_PORT`, `SCANNER_PORT` and `RELEASE_PORT`, then point `INTAKE_URL`,
`QUARANTINE_URL`, `SCANNER_URL` and `RELEASE_URL` at those alternate host ports.
The integration tests use fixed host ports 18082, 18084, 18093 and 18094; do not expose
Docker on those ports while running `mvn test`. Ports such as 28081-28084 are a safer
parallel-testing choice.

The deployment deliberately has no bind mounts from the repository into `/app`, so its
image builds do not depend on a developer’s absolute path or host filesystem layout.

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

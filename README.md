# Secure File Quarantine and Release System

Assignment 1 for Scalable Services SEZG583. The project has four independent
Spring Boot entry points, isolated H2 configuration, correlation IDs and GET /health.
Intake accepts metadata and deduplicates repeated intake requests. Quarantine
records a file, moves it through its scan states and refuses a second quarantine
or a second scan. Scanner returns deterministic mock results and replays a stored
result for a repeated or retried file. Release verifies clean quarantine state and
records the release decision.

## Prerequisites

JDK 21 or later and Maven 3.9 or later. Maven must be able to resolve public
dependencies. The build compiles at Java 21 bytecode and the test suite is verified
on Temurin 21 and SapMachine 25; Surefire passes `-Dnet.bytebuddy.experimental=true`
so Mockito can mock service classes on JDKs newer than the Byte Buddy version
Spring Boot 3.3.5 ships. All database credentials here are local demonstration values.

## Build and run

From this folder, run `mvn clean package`. Every service includes unit and
HTTP/database tests (113 tests in total).
Run each command in a separate terminal after packaging:

```bash
java -jar intake-service/target/intake-service-1.0.0-SNAPSHOT.jar
java -jar quarantine-service/target/quarantine-service-1.0.0-SNAPSHOT.jar
java -jar scanner-service/target/scanner-service-1.0.0-SNAPSHOT.jar
java -jar release-service/target/release-service-1.0.0-SNAPSHOT.jar
```

| Service | Responsibility planned | Default port |
|---|---|---|
| Intake | Accept metadata and deduplicate intake requests | 8081 |
| Quarantine | Own quarantine states and scanner capacity | 8082 |
| Scanner | Simulate clean, infected, slow and failed scans | 8083 |
| Release | Verify quarantine state and permit clean release | 8084 |

With all services running, run `bash scripts/health.sh`. Ctrl-C stops each service.
All four services store their H2 databases in `data/intake.mv.db`,
`data/quarantine.mv.db`, `data/scanner.mv.db` and `data/release.mv.db` and preserve
records across restarts. Start each service from
the repository root so it uses the same relative database path every time.
`scripts/persistence.py`, `scripts/quarantine-persistence.py`,
`scripts/scanner-persistence.py` and `scripts/release-persistence.py` prove that
durability by restarting each service against a temporary file database.
Each service runs its own tests against a separate in-memory database, so the
test suite never writes into `data/`.
Each service has its own database URL and credentials. For overrides, export SERVER_PORT, DB_URL,
DB_USERNAME, DB_PASSWORD, DB_DRIVER and DDL_AUTO in that service's terminal;
see its `.env.example`. These files are examples and are not loaded automatically.

## Layout

`common/` holds HTTP health and correlation utilities. The four `*-service/`
modules each contain sources, test locations, a dependency file and configuration.
`docs/` holds the review, report work and OpenAPI contracts; `scripts/` holds
repeatable checks; `evidence/` reserves a folder per engineering challenge and tests.

See [Intake service](intake-service/README.md) for its API, business rules, database
design, tests and Challenge 2 evidence instructions. See
[Scanner service](scanner-service/README.md) for its scan modes, retry-safe
replay, persistence and tests. See
[Release service](release-service/README.md) for its API, BR4 enforcement,
persistence and tests.

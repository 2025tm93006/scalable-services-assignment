# Secure File Quarantine and Release System

Assignment 1 for Scalable Services SEZG583. The project has four independent
Spring Boot entry points, isolated H2 configuration, correlation IDs and GET /health.
Intake includes metadata acceptance and idempotency. Quarantine, Scanner and
Release currently contain their service entry points and health endpoints.

## Prerequisites

JDK 21 or later and Maven 3.9 or later. Maven must be able to resolve public
dependencies. All database credentials here are local demonstration values.

## Build and run

From this folder, run `mvn clean package`. Intake includes unit and HTTP/database tests.
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
Intake stores its H2 database in `data/intake.mv.db` and preserves records across
restarts. Start Intake from the repository root so it uses the same relative
database path each time. The other services currently use in-memory databases.
Each service has its own database URL and credentials. For overrides, export SERVER_PORT, DB_URL,
DB_USERNAME, DB_PASSWORD, DB_DRIVER and DDL_AUTO in that service's terminal;
see its `.env.example`. These files are examples and are not loaded automatically.

## Layout

`common/` holds HTTP health and correlation utilities. The four `*-service/`
modules each contain sources, test locations, a dependency file and configuration.
`docs/` holds the review, report work and OpenAPI contracts; `scripts/` holds
repeatable checks; `evidence/` reserves a folder per engineering challenge and tests.

See [Intake service](intake-service/README.md) for its API, business rules, database
design, tests and Challenge 2 evidence instructions.

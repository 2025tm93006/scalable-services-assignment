# Secure File Quarantine and Release System

Assignment 1 for Scalable Services SEZG583. This initial commit contains the
project : four independent Spring Boot entry points, isolated H2
configuration, correlation IDs, GET /health, and health API contracts.

## Prerequisites

JDK 21 or later and Maven 3.9 or later. Maven must be able to resolve public
dependencies. All database credentials here are local demonstration values.

## Build and run

From this folder, run `mvn clean package`. This scaffold has no business tests yet.
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
H2 state is in memory and disappears on shutdown. Each service has its own
database URL and credentials. For overrides, export SERVER_PORT, DB_URL,
DB_USERNAME, DB_PASSWORD, DB_DRIVER and DDL_AUTO in that service's terminal;
see its `.env.example`. These files are examples and are not loaded automatically.

## Layout

`common/` holds HTTP health and correlation utilities. The four `*-service/`
modules each contain sources, test locations, a dependency file and configuration.
`docs/` holds the review, report work and OpenAPI contracts; `scripts/` holds
repeatable checks; `evidence/` reserves a folder per engineering challenge and tests.

# challenge-2-idempotency

Intake must replay the same response for the same key and payload, reject changed
payloads with 409, and prevent duplicates when requests arrive simultaneously.

From the repository root, build and start Intake while recording its log:

```bash
mvn -pl intake-service -am clean package
java -jar intake-service/target/intake-service-1.0.0-SNAPSHOT.jar 2>&1 | tee evidence/challenge-2-idempotency/intake-service-log.txt
```

On the machine using separate Maven settings from setup, add
`-s ../maven-settings.xml` to the Maven command. Wait for `/health` to return UP.
In a second terminal, from the repository root:

```bash
bash scripts/idempotency.sh 2>&1 | tee evidence/challenge-2-idempotency/run.txt
```

`INTAKE_URL` defaults to `http://localhost:8081` and
`IDEMPOTENCY_CONCURRENT_REQUESTS` defaults to 8. Each run uses new keys and prints
the command, UTC timestamp and correlation ID. Use the ID to locate matching log
events. Stop Intake before sharing the completed log file.

| Claim | Artifact and observation |
|---|---|
| Original result replays | `run.txt`: FIRST/REPLAY are 201 with identical full JSON, including receivedAt |
| Changed payload is rejected | `run.txt`: DIFFERENT_PAYLOAD is 409 in the common error format |
| Concurrent creation is safe | `run.txt`: 8 responses are 201 with identical bodies and one file ID |
| One database effect | `run.txt`: DATABASE_EVIDENCE reports databaseRecordCount 1 for the concurrent key |
| Replay path was executed | `intake-service-log.txt`: idempotency_created/idempotency_replay with the run's correlation ID |

`IntakeHttpTest` additionally covers actual H2 response snapshots, validation and
simultaneous different-payload conflicts. Capture current test output under
`evidence/tests/` for the report. The database unique key arbitrates concurrent
inserts; the losing insert rolls back before its request reads the winner.

Capture current output and logs from the submitted code. Screenshots used for the
report/video should show the command, output and timestamp. Intake now uses
file-based H2, so restarts preserve keys and records. The script generates fresh
keys for every run; no database reset is needed. For a separate restart check,
run `python3 scripts/persistence.py` as described in the Intake README.

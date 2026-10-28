# Demo runbook — starting the system and demonstrating it

A presenter's script, not a reference. It assumes you are in the repository root on
macOS or Linux with JDK 21+ and Maven 3.9+, and it uses the packaged JARs exactly the
way `README.md` tells a reviewer to run them, so what the video shows is what a marker
gets by following the README.

Total runtime: about 18 minutes at a talking pace. Section 9 gives the 5-minute cut.

## 0. Before you record

Five terminals. Only the first four run long-lived processes; the fifth is where the
scripts go, so a script's output never scrolls the service logs away.

| Terminal | Contents |
|---|---|
| `T1` | Intake :8081 |
| `T2` | Scanner :8083 |
| `T3` | Quarantine :8082 |
| `T4` | Release :8084 |
| `T5` | `curl` calls and the evidence scripts |

Build once, from the repository root:

```bash
export JAVA_HOME=$(/usr/libexec/java_home)   # macOS; on Linux use your JDK path
mvn -B -ntp clean package
```

Expect `BUILD SUCCESS` for all five reactor modules and 131 tests
(Intake 36, Quarantine 28, Scanner 34, Release 33).

Start with a clean slate so the counts on screen are only from this run:

```bash
rm -rf data            # the four local demo databases; safe, they are git-ignored
```

## 1. Start and show health (1 min)

Start Scanner **before** Quarantine — Quarantine calls it, and a scan against a stopped
Scanner is a `FAILED` verdict rather than an error.

```bash
# T1
java -jar intake-service/target/intake-service-1.0.0-SNAPSHOT.jar
# T2
java -jar scanner-service/target/scanner-service-1.0.0-SNAPSHOT.jar
# T3
java -jar quarantine-service/target/quarantine-service-1.0.0-SNAPSHOT.jar
# T4
java -jar release-service/target/release-service-1.0.0-SNAPSHOT.jar
```

Then, in `T5`:

```bash
bash scripts/health.sh
```

Four `{"status":"UP",...}` lines. Point out that each service keeps its own file
database under `data/` and that Quarantine's startup log contains
`scanner_client_configured baseUrl=... readTimeoutMillis=5000 maxAttempts=3 ...` —
that line is the whole Challenge 3A budget in one place.

## 2. The happy path, one request at a time (5 min)

`T5`. The `sha256` is arbitrary demo metadata; the modes are `CLEAN`, `INFECTED`,
`FAIL` and `SLOW`.

```bash
SHA=$(python3 -c 'import hashlib; print(hashlib.sha256(b"demo payload").hexdigest())')
KEY=demo-key-1
BODY="{\"filename\":\"invoice.pdf\",\"contentType\":\"application/pdf\",\"sizeBytes\":12,\"sha256\":\"$SHA\",\"scannerMode\":\"CLEAN\"}"
```

**Intake, first submission** — `201`; keep the `fileId`, every later step uses it:

```bash
curl -si -X POST localhost:8081/api/v1/files \
  -H 'Content-Type: application/json' -H "Idempotency-Key: $KEY" \
  -H 'X-Correlation-Id: demo-1' -d "$BODY"

FILE=$(curl -s -X POST localhost:8081/api/v1/files \
  -H 'Content-Type: application/json' -H "Idempotency-Key: $KEY" -d "$BODY" \
  | python3 -c 'import json,sys;print(json.load(sys.stdin)["fileId"])')
```

The second call is a replay: same key, same payload, `201`, and the body is identical
including `receivedAt`. `scripts/idempotency.sh` proves at the database level that only
one row exists (BR1).

**Challenge 2 in one line** — same key, different payload:

```bash
curl -si -X POST localhost:8081/api/v1/files -H 'Content-Type: application/json' \
  -H "Idempotency-Key: $KEY" -d '{"filename":"other.pdf","contentType":"application/pdf","sizeBytes":12,"sha256":"'"$SHA"'","scannerMode":"CLEAN"}'
```

`409` with the shared `ApiError` body and the echoed `X-Correlation-Id: demo-1` (BR2).

**Quarantine the file.** Capture the record `id` (the scan needs it) and `quarantinedAt`
(the release needs it) from the `201` body:

```bash
REC=$(curl -s -X POST localhost:8082/api/v1/quarantine -H 'Content-Type: application/json' \
  -H 'X-Correlation-Id: demo-3' -d '{"fileId":"'"$FILE"'","scannerMode":"CLEAN"'} \
  | python3 -c 'import json,sys; r=json.load(sys.stdin); print(r["id"], r["quarantinedAt"])')
QUAR_ID=${REC% *}; QUARANTINED_AT=${REC#* }
echo "record=$QUAR_ID quarantinedAt=$QUARANTINED_AT"
```

Then repeat the same call on screen, and it is a `409 File is already quarantined`:

```bash
curl -si -X POST localhost:8082/api/v1/quarantine -H 'Content-Type: application/json' \
  -d '{"fileId":"'"$FILE"'","scannerMode":"CLEAN"'}
```

One record per file, and the second request is refused rather than quietly replayed -
which is exactly what makes the create race in Challenge 1 worth racing.

**Show that Scanner has never heard of this file — this is the line that makes the next
step a real cross-service call:**

```bash
curl -si localhost:8083/api/v1/scans/$FILE     # 404
```

**Scan it:**

```bash
curl -si -X POST localhost:8082/api/v1/quarantine/$QUAR_ID/scan
```

`200` with `"state":"CLEAN"` and a `scannedAt`. Look at `T3`: four lines in one second —
`scanner_slot_acquired slot=1`, `scanner_call attempt=1 maxAttempts=3`,
`scan_settled state=CLEAN`, `scanner_slot_released rowsUpdated=1`. Now ask Scanner
directly:

```bash
curl -s localhost:8083/api/v1/scans/$FILE      # 200, result CLEAN
```

`finishedAt` equals Quarantine's `scannedAt`. That equality is the proof that the verdict
came from the other service and was not invented locally. A second scan is `409`
`File has already been scanned`:

```bash
curl -si -X POST localhost:8082/api/v1/quarantine/$QUAR_ID/scan
```

**Release it — and show the refusal first:**

```bash
curl -si -X POST localhost:8084/api/v1/releases -H 'Content-Type: application/json' \
  -d '{"fileId":"'"$FILE"'","quarantineState":"INFECTED","quarantinedAt":"'"$QUARANTINED_AT"'"}'
curl -si -X POST localhost:8084/api/v1/releases -H 'Content-Type: application/json' \
  -d '{"fileId":"'"$FILE"'","quarantineState":"CLEAN","quarantinedAt":"'"$QUARANTINED_AT"'"}'
curl -si localhost:8084/api/v1/releases/$FILE
```

The first call is `409` and stores nothing, which is why the `GET` between them would be
`404`; after the `CLEAN` call it is `201` and the `GET` is `200` (BR4). A repeat of the
`CLEAN` call replays the same record.

## 3. All of section 2 in one command, when you do not want to type

```bash
bash scripts/e2e.sh
```

28 `ok` lines ending in `E2E PASS ... correlationId=e2e-<timestamp>`. Say the word
"scripted" and move on; the same assertions as section 2, including the cross-service
timestamp equality and the genuinely infected file that Release refuses.

## 4. Challenge 1 — concurrency on a contended resource (3 min)

```bash
python3 scripts/concurrency.py
```

It starts its own Scanner and Quarantine on random ports against temporary databases, so
nothing above is disturbed. Read the output in this order:

1. `PART_1_SIMULTANEOUS_CREATIONS` — eight identical creations of one `fileId`:
   `count=1 status=201`, `count=7 status=409`, then `PART_1_DATABASE total=1`. The unique
   index decided it, not a check in application code.
2. `PART_2_PARALLEL_SCANS capacity=2 files=20` — `count=2 status=200` and
   `count=18 status=409 message=Scanner capacity exhausted`.
3. `QUARANTINE_LOG_EVIDENCE` — exactly two `scanner_slot_acquired` lines before the first
   `scanner_capacity_rejected`.
4. `PART_3_FINALLY` — `"CLEAN": 20`, `"QUARANTINED": 1`, `"SCANNING": 0`,
   `scannerSlots {"FREE": 2, "HELD": 0}`: the rejected scans were delayed, not lost, and
   the ledger is back where it started.
5. `SCANNER_SCAN_COMPLETED_TOTAL 20 for 20 files` — no rejection or retry caused a second
   scan.

The sentence to say: "the check and the update are one statement, so the database's
`rowsUpdated` is the answer rather than an application's guess."

**Optional live version, if you want concurrency visible in the running services** —
restart Quarantine in `T3` with one slot and a slow Scanner, then fire two scans:

```bash
# T2 first: make every scan take 4 seconds
# (stop T2 and restart with:)
java -jar scanner-service/target/scanner-service-1.0.0-SNAPSHOT.jar --scanner.slow-millis=4000
# T3: one slot
java -jar quarantine-service/target/quarantine-service-1.0.0-SNAPSHOT.jar --quarantine.max-concurrent-scans=1

# T5: queue two SLOW scans and watch one of them get 409
curl -s -X POST localhost:8082/api/v1/quarantine -H 'Content-Type: application/json' -d '{"fileId":"11111111-1111-1111-1111-111111111111","scannerMode":"SLOW"}'
curl -s -X POST localhost:8082/api/v1/quarantine -H 'Content-Type: application/json' -d '{"fileId":"22222222-2222-2222-2222-222222222222","scannerMode":"SLOW"}'
curl -s -X POST localhost:8082/api/v1/quarantine/<first-id>/scan & sleep 0.2; curl -si -X POST localhost:8082/api/v1/quarantine/<second-id>/scan
```

One `200`, one `409 Scanner capacity exhausted`, and `T3` logs
`scanner_capacity_rejected ... capacity=1`.

Verified output, with the two scans fired half a second apart:

```
{"fileId":"11111111-...","state":"CLEAN","scannerMode":"SLOW","scannedAt":"..."}   first=200
{"status":409,"message":"Scanner capacity exhausted", ...}                          second=409
```

Do this on a database from section 0. If you lower `MAX_CONCURRENT_SCANS` on a `data/`
that was seeded with a larger value, the extra slot rows stay in the table, so
`_evidence/summary` reports `scannerSlots.FREE` from the ledger while `capacity` reports
the new smaller number. Raising the value is fine — the missing rows are seeded at
startup.

## 5. Challenge 3A — resilience of the service-to-service call (3 min)

```bash
python3 scripts/resilience.py
```

Say the budget out loud before the output lands (`500 ms read timeout, 3 attempts,
200 ms base, 150 ms jitter`, Scanner held at 1500 ms), then walk the four parts:

* `PART_A_SLOW_DOWNSTREAM` — `RESPONSE status=200 state=CLEAN`, two
  `scanner_attempt_failed ... Request timed out`, and two `scanner_retry` lines whose
  `delayMillis` roughly double: exponential backoff with jitter. `Scanner stored 1 attempt
  for this file` is the "no duplicate effect" claim.
* `PART_B_STOPPED_DOWNSTREAM` — Scanner is killed mid-run; the caller still gets
  `status=200 state=FAILED` after exactly three attempts, and
  `scannerSlots {"FREE": 2, "HELD": 0}` shows the slot came back even though the scan
  failed.
* `PART_C_DOWNSTREAM_RESTARTED` — Scanner starts again, a new file settles `CLEAN` in
  about 120 ms, and the file that failed stays `FAILED` (409 on a retry).
* `PART_D_DOWNSTREAM_IDEMPOTENCY` — two identical requests to Scanner return the same
  `completedAt` with `totalAttempts` unchanged, which is what makes the retries in Part A
  safe.

**Optional live version, no script:** in `T2` press Ctrl-C to stop Scanner, then in `T5`

```bash
curl -s -X POST localhost:8082/api/v1/quarantine -H 'Content-Type: application/json' \
  -d '{"fileId":"33333333-3333-3333-3333-333333333333","scannerMode":"CLEAN"'}
curl -si -X POST localhost:8082/api/v1/quarantine/<id>/scan
```

and restart Scanner in `T2`. The scan answers `200` with `"state":"FAILED"` in about half a
second, because a refused connection fails immediately rather than waiting out the read
timeout; a Scanner that is up but slow is the case that costs the full budget, and that is
what `scripts/resilience.py` Part A shows. `T3` shows `scanner_attempt_failed ...
(ClosedChannelException)` three times, then `scan_failed_downstream_unavailable`, then
`scanner_slot_released rowsUpdated=1`. The caller is never left with a 500 or a hung
request, and the record is never left in `SCANNING`.

## 6. Persistence across a real restart (2 min)

```bash
python3 scripts/persistence.py            # Intake
python3 scripts/quarantine-persistence.py # Quarantine + Scanner
python3 scripts/scanner-persistence.py    # Scanner
python3 scripts/release-persistence.py    # Release
```

For the live version, stop `T3` with Ctrl-C and start it again, then:

```bash
curl -s localhost:8082/api/v1/quarantine/<an-id-from-section-2>       # still there
curl -si -X POST localhost:8082/api/v1/quarantine -H 'Content-Type: application/json' \
  -d '{"fileId":"'"$FILE"'","scannerMode":"CLEAN"}'                   # still 409
```

`quarantine-persistence.py` is the strongest of the four to show: it asserts that the
state which survives the restart is the verdict **Scanner** reported, by comparing the
record's `scannedAt` with `GET /api/v1/scans/{fileId}`'s `finishedAt` both before and
after the restart.

## 7. Close on the tests and the evidence folder (2 min)

```bash
mvn -B -ntp test          # or show the captured reports instead of waiting
open evidence             # in Finder, or: ls -R evidence
```

Show, in this order: `evidence/tests/e2e-run.txt` (the correlated Quarantine and Scanner
log lines of one cross-service scan), `evidence/challenge-1-concurrency/run.txt`,
`evidence/challenge-2-idempotency/run.txt`,
`evidence/challenge-3a-resilience/run.txt`, then
[architecture.md](architecture.md) for the diagrams and
[business-rules.md](business-rules.md) for BR1–BR4 with the test names behind each.

## 8. What to say when something goes wrong on camera

| You see | Say |
|---|---|
| `409 Scanner capacity exhausted` on a hand-run scan | "Capacity is deliberately 2 — that is Challenge 1 working, not a failure. The record stays `QUARANTINED`, so the scan is delayed, not lost." |
| `200` with `"state":"FAILED"` | "The call failed, the request did not. Scanner was unreachable for the whole retry budget, so the file is settled `FAILED` and the slot was returned." |
| Two requests with the same key both `201` | "Same response, one row — the replay is the second request's answer; `DATABASE_EVIDENCE` shows `databaseRecordCount: 1`." |
| `Value not permitted for column ... "SCANNING"` | A stale `data/quarantine.mv.db` from before the state was added. `rm -f data/quarantine.mv.db data/quarantine.trace.db` and restart Quarantine. |

## 9. The 5-minute cut

1. `bash scripts/health.sh` — four services, four own databases. (30 s)
2. Quarantine a file, `curl -si localhost:8083/api/v1/scans/$FILE` → 404, scan it, same
   call → 200 with the same timestamp Quarantine stored. This is the cross-service proof
   and the most important 60 seconds of the video. (90 s)
3. `python3 scripts/concurrency.py` — talk over `2x200 / 18x409`, `total=1`,
   `FREE 2 / HELD 0`, `20 scans for 20 files`. (90 s)
4. `python3 scripts/resilience.py` — talk over the two timeouts, the doubled delays and
   the `FAILED` settle after the kill. (60 s)
5. `evidence/` folder tour and `docs/architecture.md`. (30 s)

## 10. Notes that keep the demo honest

* The scripts in sections 4, 5 and 6 start their own JARs on ephemeral ports with
  temporary databases, so they can be run while the four services are up and they never
  write into `data/`.
* `mvn test` never touches `data/` either: every test uses an in-memory database.
* `scripts/idempotency.sh` needs Intake running; the two challenge scripts and the four
  persistence scripts need nothing running.
* Stop the services with Ctrl-C in each of `T1`–`T4`. Files under `data/` are local demo
  state, git-ignored, and deleting them is always safe.

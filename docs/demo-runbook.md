# Demo Runbook — Step-by-Step Presenter Guide

**Full demo:** 8–10 minutes | **Short cut:** 5 minutes (see end of this file)  
**What you need:** 5 terminal windows, all opened in the repository root

---

## Before You Start

Build everything once:

```bash
mvn -B -ntp clean package
```

You should see `BUILD SUCCESS` and `178 tests` passing.

Clear old database files so counts on screen are only from this run:

```bash
rm -rf data
```

---

## Step 1 — Start the Four Services (1 min)

Open 4 terminals. In each one, `cd` into the repository root, then run one command:

| Terminal | Command |
|---|---|
| T1 — Intake | `java -jar intake-service/target/intake-service-1.0.0-SNAPSHOT.jar` |
| T2 — Scanner | `java -jar scanner-service/target/scanner-service-1.0.0-SNAPSHOT.jar` |
| T3 — Quarantine | `java -jar quarantine-service/target/quarantine-service-1.0.0-SNAPSHOT.jar` |
| T4 — Release | `java -jar release-service/target/release-service-1.0.0-SNAPSHOT.jar` |

> **Order matters:** start Scanner (T2) before Quarantine (T3), and Quarantine (T3) before Release (T4).
> Quarantine calls Scanner; Release calls Quarantine.

Open a **5th terminal (T5)** for all commands below.

---

## Step 2 — Verify All Services Are Running (30 sec)

```bash
bash scripts/health.sh
```

You should see four green checkmarks with `{"status":"UP"}`.

While you are here, look at the **T3 (Quarantine) startup log** and point out this line:

```
scanner_client_configured baseUrl=http://localhost:8083 connectTimeoutMillis=300
  readTimeoutMillis=5000 maxAttempts=3 retryBaseMillis=100 retryJitterMillis=100
```

**Say:** *"That one line is the entire Challenge 3A resilience budget — timeout, retries, and jitter all configured in one place."*

---

## Step 3 — Manual Happy Path (optional, 3 min)

> Skip this section and go straight to Step 4 if you prefer to use the automated script.
> This section shows the same thing one curl at a time — useful if you want to narrate each step.

In T5, set up variables:

```bash
SHA=$(python3 -c 'import hashlib; print(hashlib.sha256(b"demo payload").hexdigest())')
KEY=demo-key-1
BODY="{\"filename\":\"invoice.pdf\",\"contentType\":\"application/pdf\",\"sizeBytes\":12,\"sha256\":\"$SHA\",\"scannerMode\":\"CLEAN\"}"
```

**Intake — first submission (BR1):**

```bash
# First call → 201
curl -si -X POST localhost:8081/api/v1/files \
  -H 'Content-Type: application/json' -H "Idempotency-Key: $KEY" \
  -H 'X-Correlation-Id: demo-1' -d "$BODY"

# Capture the fileId, then replay — same 201, identical body including receivedAt
FILE=$(curl -s -X POST localhost:8081/api/v1/files \
  -H 'Content-Type: application/json' -H "Idempotency-Key: $KEY" -d "$BODY" \
  | python3 -c 'import json,sys;print(json.load(sys.stdin)["fileId"])')
```

Prove one DB row at the database level:

```bash
bash scripts/idempotency.sh
```

**Key conflict — same key, different payload (BR2):**

```bash
curl -si -X POST localhost:8081/api/v1/files -H 'Content-Type: application/json' \
  -H "Idempotency-Key: $KEY" \
  -d "{\"filename\":\"other.pdf\",\"contentType\":\"application/pdf\",\"sizeBytes\":12,\"sha256\":\"$SHA\",\"scannerMode\":\"CLEAN\"}"
```

`409` — the original record is unchanged.

**Quarantine the file (BR3):**

```bash
REC=$(curl -s -X POST localhost:8082/api/v1/quarantine \
  -H 'Content-Type: application/json' -H 'X-Correlation-Id: demo-3' \
  -d '{"fileId":"'"$FILE"'","scannerMode":"CLEAN"}' \
  | python3 -c 'import json,sys; r=json.load(sys.stdin); print(r["id"], r["quarantinedAt"])')
QUAR_ID=${REC% *}; QUARANTINED_AT=${REC#* }
echo "record=$QUAR_ID  quarantinedAt=$QUARANTINED_AT"
```

Repeat the same quarantine call — `409 File is already quarantined`.

**Prove cross-service scan — Scanner has never seen this file yet:**

```bash
curl -si localhost:8083/api/v1/scans/$FILE     # 404 — not yet
```

**Scan it:**

```bash
curl -si -X POST localhost:8082/api/v1/quarantine/$QUAR_ID/scan
```

`200` with `"state":"CLEAN"`. Look at **T3** — you'll see four log lines:
`scanner_slot_acquired` → `scanner_call attempt=1` → `scan_settled state=CLEAN` → `scanner_slot_released`.

Now ask Scanner directly:

```bash
curl -s localhost:8083/api/v1/scans/$FILE      # 200, result CLEAN
```

`finishedAt` in Scanner equals `scannedAt` in Quarantine — the verdict came from Scanner, not invented locally. A second scan attempt returns `409 File has already been scanned`.

**Release — show the refusal first (BR4):**

```bash
# Wrong state → 409
curl -si -X POST localhost:8084/api/v1/releases -H 'Content-Type: application/json' \
  -d '{"fileId":"'"$FILE"'","quarantineState":"INFECTED","quarantinedAt":"'"$QUARANTINED_AT"'"}'

# Correct state → 201
curl -si -X POST localhost:8084/api/v1/releases -H 'Content-Type: application/json' \
  -d '{"fileId":"'"$FILE"'","quarantineState":"CLEAN","quarantinedAt":"'"$QUARANTINED_AT"'"}'

# Read it back → 200
curl -si localhost:8084/api/v1/releases/$FILE
```

**Prove Release verifies the claim — stop Quarantine (Ctrl-C in T3), then:**

```bash
# A new release attempt → 503 Quarantine did not answer after 3 attempts
curl -si -X POST localhost:8084/api/v1/releases -H 'Content-Type: application/json' \
  -d '{"fileId":"'"$FILE"'","quarantineState":"CLEAN","quarantinedAt":"'"$QUARANTINED_AT"'"}'

# The release you already made still replays → 201
curl -si -X POST localhost:8084/api/v1/releases -H 'Content-Type: application/json' \
  -d '{"fileId":"'"$FILE"'","quarantineState":"CLEAN","quarantinedAt":"'"$QUARANTINED_AT"'"}'
```

**Say:** *"A decision Release already made is its own fact — it replays without calling Quarantine. But a new release cannot proceed without a check."*

Restart Quarantine in T3 before the next step.

---

## Step 4 — Automated End-to-End (2 min)

If you skipped Step 3, run this now. If you did Step 3, run this anyway to show the scripted version:

```bash
bash scripts/e2e.sh
```

Every assertion in Step 3 is in here, plus the cross-service timestamp equality check and the genuinely infected file that Release refuses. Ends with `E2E PASS`.

**What it proves:**

| What you see | Business rule |
|---|---|
| First POST → `201`, repeat → `201` with identical body | BR1: idempotent intake |
| Same key, different filename → `409` | BR2: key conflict detected |
| Second quarantine attempt → `409` | BR3: one record per file |
| Scanner returns `404` before scan, `200` after | Real cross-service call happened |
| `finishedAt` in Scanner equals `scannedAt` in Quarantine | Verdict came from Scanner, not invented locally |
| Infected file claimed as CLEAN → `409` | BR4: Release asks Quarantine, not the caller |
| Release repeated → same body | BR4: idempotent release |

---

## Step 5 — Concurrency Control (2 min)

```bash
python3 scripts/concurrency.py
```

Starts its own temporary services — your running services are not disturbed.

**Walk through the output in this order:**

1. **Creation race** — 8 threads, same `fileId`, at the same instant.
   `HTTP 201 ×1` and `HTTP 409 ×7`. Database has exactly 1 record.
   *"The unique index picks the winner, not application code."*

2. **Scan burst** — 20 parallel scans, capacity = 2.
   `HTTP 200 ×2` and `HTTP 409 ×18 (Scanner capacity exhausted)`.

3. **Log evidence** — point out exactly 2 `scanner_slot_acquired` lines before the first `scanner_capacity_rejected` in the Quarantine log output.

4. **Retry** — the 18 rejected scans are retried. All 20 reach `CLEAN`.
   Slots return to `FREE: 2  HELD: 0`.

5. **No duplicates** — `Scanner ran 20 scans for 20 files`. No rejection or retry caused a second scan.

Ends with `CHALLENGE_1_PASS`.

**Say:** *"The database decides which requests win the race. Rejected scans stay QUARANTINED — delayed, not lost."*

### Optional: live capacity demo (no script)

Stop T2 and T3, restart them with reduced settings:

```bash
# T2 — make every scan take 4 seconds
java -jar scanner-service/target/scanner-service-1.0.0-SNAPSHOT.jar --scanner.slow-millis=4000

# T3 — one slot only
java -jar quarantine-service/target/quarantine-service-1.0.0-SNAPSHOT.jar --quarantine.max-concurrent-scans=1
```

Quarantine two files, then fire both scans at the same time:

```bash
ID1=$(curl -s -X POST localhost:8082/api/v1/quarantine -H 'Content-Type: application/json' \
  -d '{"fileId":"11111111-1111-1111-1111-111111111111","scannerMode":"SLOW"}' \
  | python3 -c 'import json,sys;print(json.load(sys.stdin)["id"])')
ID2=$(curl -s -X POST localhost:8082/api/v1/quarantine -H 'Content-Type: application/json' \
  -d '{"fileId":"22222222-2222-2222-2222-222222222222","scannerMode":"SLOW"}' \
  | python3 -c 'import json,sys;print(json.load(sys.stdin)["id"])')

curl -s -X POST localhost:8082/api/v1/quarantine/$ID1/scan & \
sleep 0.2; curl -si -X POST localhost:8082/api/v1/quarantine/$ID2/scan
```

One `200`, one `409 Scanner capacity exhausted`. T3 logs `scanner_capacity_rejected capacity=1`.

Restart T2 and T3 normally (`rm -rf data` first) before continuing.

### Crash recovery demo

```bash
python3 scripts/reclaim.py
```

Shows what happens when a process is killed mid-scan (a `finally` block cannot save you). Walk the output:

1. `DURING_THE_SCAN` — `SCANNING: 1`, `HELD: 1`: a real slot held by a real scan.
2. `AFTER_RESTART_SWEEP_DISABLED` — same numbers after restart: the leak is in the database, not in memory.
3. `AFTER_RECLAIM_SWEEP` — `HELD: 0`, `FAILED: 1`, `SCANNING: 0`: one sweep freed the slot and settled the abandoned record.
4. `CAPACITY_USABLE_AFTER_THE_SWEEP` — a new scan runs on the reclaimed slot immediately.

**Say:** *"A process kill cannot leave capacity locked forever. The reclaim job runs on a schedule and cleans up anything the finally block missed."*

---

## Step 6 — Resilience and Retries (2 min)

```bash
python3 scripts/resilience.py
```

Say the budget out loud before it runs: *"500 ms read timeout, 3 attempts, 200 ms base delay, 150 ms jitter, Scanner held at 1500 ms."*

| Part | What happens | What to say |
|---|---|---|
| **A — Slow Scanner** | Timeout → retry → success. Scanner stored exactly 1 attempt. | *"Retrying a slow call is safe because Scanner is idempotent."* |
| **B — Scanner stopped** | All 3 attempts fail. File settles as `FAILED`. Slots return to `FREE: 2 HELD: 0`. | *"Downstream failure becomes a FAILED state, not a 500 error. Slot always released."* |
| **C — Scanner restarted** | New file scans cleanly. FAILED record stays FAILED. | *"A settled state is final — it cannot transition twice."* |
| **D — Idempotency** | Same scan sent twice: identical `completedAt`, `totalAttempts` unchanged. | *"This is why Part A retries are safe — replaying stores nothing new."* |
| **E — Circuit breaker** | After 2 failed calls, circuit opens. Next call: `httpAttemptsMade=0`. After cooldown, one probe closes it. | *"The circuit stops burning the retry budget on a known-down service."* |

Ends with `CHALLENGE_3A_PASS`.

### Optional: live resilience demo (no script)

Stop Scanner (Ctrl-C in T2), then in T5:

```bash
curl -s -X POST localhost:8082/api/v1/quarantine -H 'Content-Type: application/json' \
  -d '{"fileId":"33333333-3333-3333-3333-333333333333","scannerMode":"CLEAN"}'
# capture the id, then:
curl -si -X POST localhost:8082/api/v1/quarantine/<id>/scan
```

`200` with `"state":"FAILED"` in ~500 ms. T3 logs three `scanner_attempt_failed (I/O error)` lines, then `scan_failed_downstream_unavailable`, then `scanner_slot_released`. Restart Scanner in T2 when done.

---

## Step 7 — Release Trust Boundary (1 min)

```bash
python3 scripts/verification.py
```

| Part | Scenario | Result |
|---|---|---|
| **A** | Genuine CLEAN file, correct timestamp | `201 RELEASED` |
| **B** | Infected claimed as CLEAN / missing record / wrong timestamp | `409` all refused |
| **C** | Quarantine stopped, real clean file | `503` — nothing stored |
| **D** | Quarantine back, same request retried | `201 RELEASED`; replay still works offline |

Ends with `VERIFICATION_PASS`.

**Say:** *"Release treats the caller's state as a claim, not as authority. Cannot verify = cannot release."*

---

## Step 8 — Persistence (optional, 1 min)

Shows that state survives a real service restart:

```bash
python3 scripts/quarantine-persistence.py   # strongest: verifies scannedAt == Scanner's finishedAt after restart
python3 scripts/release-persistence.py      # release replays 201 even while Quarantine is down
```

Or live: stop T3 with Ctrl-C, restart it, then:

```bash
curl -s localhost:8082/api/v1/quarantine/$QUAR_ID    # record still there
curl -si -X POST localhost:8082/api/v1/quarantine \
  -H 'Content-Type: application/json' \
  -d '{"fileId":"'"$FILE"'","scannerMode":"CLEAN"}'   # still 409
```

---

## Step 9 — Evidence and Tests (1 min)

```bash
ls -R evidence
```

Open these in order:

| File | What to point out |
|---|---|
| `docs/architecture.md` | System diagrams and sequence flows |
| `docs/business-rules.md` | BR1–BR4 mapped to code and tests |
| `evidence/tests/e2e-run.txt` | Correlated Quarantine + Scanner log lines of one cross-service scan |
| `evidence/tests/verification-run.txt` | Trust boundary proof |
| `evidence/challenge-1-concurrency/run.txt` | Slot claims, rejections, final counts |
| `evidence/challenge-1-concurrency/reclaim-run.txt` | Crash recovery |
| `evidence/challenge-2-idempotency/run.txt` | One DB row from 8 simultaneous requests |
| `evidence/challenge-3a-resilience/run.txt` | Timeouts, retries, FAILED settle, circuit breaker |

Show 178 tests passing live (optional):

```bash
mvn -B -ntp test
```

---

## 5-Minute Cut

If you only have 5 minutes, do these 6 steps:

1. `bash scripts/health.sh` — four services, four own databases. *(20 sec)*
2. Quarantine a file, show Scanner returns `404`, scan it, show Scanner returns `200` with the same `finishedAt` that Quarantine stored as `scannedAt`. This is the cross-service proof. *(75 sec)*
3. Infect a second file, scan it, then ask Release for it claiming `CLEAN` → `409 Quarantine reports this file as INFECTED, not CLEAN`. *(45 sec)*
4. `python3 scripts/concurrency.py` — point out `201 ×1 / 409 ×7`, then `200 ×2 / 409 ×18`, then `FREE: 2 HELD: 0`. *(60 sec)*
5. `python3 scripts/resilience.py` — point out the two timeouts, doubled delays, `FAILED` settle, and Part E's `httpAttemptsMade=0`. *(60 sec)*
6. `evidence/` folder and `docs/architecture.md`. *(40 sec)*

---

## Quick Recovery — If Something Goes Wrong

| What you see | Fix |
|---|---|
| `409 Scanner capacity exhausted` on a hand-run scan | Normal — capacity is 2. Record stays QUARANTINED. Retry the scan. |
| `200` with `"state":"FAILED"` | Normal — Scanner was unreachable for the full budget. Slot was returned. |
| `503 Quarantine did not answer` | Quarantine is stopped. Restart T3, wait ~5 sec, retry. |
| `409 Quarantine reports this file as INFECTED, not CLEAN` | Design working correctly — Release checked and refused the false claim. |
| `409 File is already quarantined` | Same `fileId` quarantined before. Use a new `fileId`. |
| `scanner_circuit_refused ... attemptsMade=0` | Circuit breaker is open (Part E). Normal — it closes after the cooldown probe. |
| H2 database lock error on startup | Another instance is still running. Run `pkill -f "SNAPSHOT.jar"` then restart. |
| `release version 21 not supported` | Maven picked Java 17. Run `export JAVA_HOME=$(/usr/libexec/java_home -v 25)` first. |
| Stale schema error (`Value not permitted for column SCANNING`) | Old `data/` from a previous build. Run `rm -rf data` and restart all services. |

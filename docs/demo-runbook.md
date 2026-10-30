# Demo Runbook — Step-by-Step Presenter Guide

**Total time:** 8–10 minutes  
**What you need:** 5 terminal windows, all opened in the repository root

---

## Before You Start

Run this once to build everything:

```bash
mvn -B -ntp clean package
```

You should see `BUILD SUCCESS` and `178 tests` passing.

Then clear old database files so counts on screen are fresh:

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
> This is because Quarantine calls Scanner, and Release calls Quarantine.

Open a **5th terminal** for all commands below.

---

## Step 2 — Verify All Services Are Running (30 sec)

```bash
bash scripts/health.sh
```

You should see four green checkmarks with `{"status":"UP"}`.  
Each service has its own database file under `data/`.

**Say:** *"Four independent services, each with its own database. They share nothing at the data layer."*

---

## Step 3 — End-to-End Business Flow (2 min)

This single command drives a file through all four services automatically:

```bash
bash scripts/e2e.sh
```

Watch the output — each line is a numbered assertion. It ends with:

```
╔══════════════════════════════════════════════════════════╗
║                    E2E PASS                              ║
╚══════════════════════════════════════════════════════════╝
```

**What it proves (point these out as they scroll):**

| What you see | Business rule |
|---|---|
| First POST → `201`, repeat → `201` with identical body | BR1: idempotent intake |
| Same key, different filename → `409` | BR2: key conflict detected |
| Second quarantine attempt → `409` | BR3: one record per file |
| Scanner returns `404` before scan, `200` after | Real cross-service call happened |
| `finishedAt` in Scanner equals `scannedAt` in Quarantine | Verdict came from Scanner, not invented locally |
| Infected file claimed as CLEAN → `409` | BR4: Release asks Quarantine, not the caller |
| Release repeated → same body | BR4: idempotent release |

**Say:** *"The client drives the transaction. Quarantine gets the verdict from Scanner. Release independently verifies that verdict with Quarantine — it does not trust what the client claims."*

---

## Step 4 — Concurrency Control (2 min)

```bash
python3 scripts/concurrency.py
```

This script starts its own temporary services, so your running services are not disturbed.

**Walk through the output in this order:**

1. **Creation race** — 8 threads send the same `fileId` at the same instant.  
   You see: `HTTP 201 ×1` and `HTTP 409 ×7`.  
   Database has exactly 1 record.  
   *"The database unique index picks the winner — not application code."*

2. **Scan burst** — 20 parallel scans with capacity set to 2.  
   You see: `HTTP 200 ×2` and `HTTP 409 ×18 (Scanner capacity exhausted)`.

3. **Retry** — the 18 rejected scans are retried one by one.  
   All 20 eventually reach `CLEAN`.  
   Slots return to `FREE: 2  HELD: 0`.

Ends with:
```
╔══════════════════════════════════════════════╗
║  CHALLENGE_1_PASS                            ║
╚══════════════════════════════════════════════╝
```

**Say:** *"The database decides which requests win the race. Rejected scans stay QUARANTINED so they can be retried — they are delayed, not lost."*

---

## Step 5 — Resilience and Retries (2 min)

```bash
python3 scripts/resilience.py
```

Again uses its own temporary services. Walk the five parts:

| Part | What happens | What to say |
|---|---|---|
| **A — Slow Scanner** | Scanner takes 1500 ms, timeout is 500 ms. First attempts time out, retry succeeds. Scanner stored exactly 1 attempt. | *"Retrying a slow call is safe because Scanner is idempotent."* |
| **B — Scanner stopped** | All 3 attempts fail. File settles as `FAILED`. Slots return to `FREE: 2 HELD: 0`. | *"Downstream failure becomes a FAILED state, not a 500 error."* |
| **C — Scanner restarted** | New file scans cleanly. FAILED record stays FAILED. | *"A settled state is final — it cannot transition twice."* |
| **D — Idempotency** | Same scan request sent twice returns identical `completedAt`. No new DB row. | *"This is why retries in Part A are safe — replaying doesn't duplicate."* |
| **E — Circuit breaker** | After 2 failed calls, circuit opens. Next call costs 0 HTTP attempts. After cooldown, one probe closes it. | *"The circuit stops wasting the retry budget on a known-down service."* |

Ends with:
```
╔══════════════════════════════════════════════╗
║  CHALLENGE_3A_PASS                           ║
╚══════════════════════════════════════════════╝
```

**Say:** *"A downstream failure does not become an unhandled error. Quarantine uses a bounded retry budget, releases its capacity slot, and settles the record safely."*

---

## Step 6 — Release Trust Boundary (1 min)

```bash
python3 scripts/verification.py
```

Four scenarios proving Release does not trust the caller:

| Part | Scenario | Result |
|---|---|---|
| **A** | Genuine CLEAN file with correct timestamp | `201 RELEASED` |
| **B** | Infected file claimed as CLEAN / missing record / wrong timestamp | `409` (all refused) |
| **C** | Quarantine stopped, real clean file released | `503` — nothing stored |
| **D** | Quarantine comes back, same request retried | `201 RELEASED` — and replay still works while Quarantine is down |

Ends with:
```
╔══════════════════════════════════════════════╗
║  VERIFICATION_PASS                           ║
╚══════════════════════════════════════════════╝
```

**Say:** *"Release treats the caller's state as a claim, not as authority. If the claim cannot be verified, Release refuses."*

---

## Step 7 — Evidence and Tests (1 min)

Show the evidence folder:

```bash
ls -R evidence
```

Open these files briefly:

- `docs/architecture.md` — system diagrams and sequence flows
- `docs/business-rules.md` — BR1–BR4 mapped to code and tests
- `evidence/tests/e2e-run.txt` — recorded end-to-end run
- `evidence/challenge-1-concurrency/run.txt` — concurrency proof
- `evidence/challenge-3a-resilience/run.txt` — resilience proof

To show all 178 tests passing live:

```bash
mvn -B -ntp test
```

---

## Quick Recovery — If Something Goes Wrong

| What you see | Fix |
|---|---|
| `409 Scanner capacity exhausted` on a hand-run scan | Normal — capacity is 2. Record stays QUARANTINED. Retry the scan. |
| `200` with `"state":"FAILED"` | Normal — Scanner was unreachable for the full retry budget. Slot was returned. |
| `503 Quarantine did not answer` | Quarantine is stopped. Restart T3, wait ~5 sec, retry. |
| `409 Quarantine reports this file as INFECTED, not CLEAN` | This is the design working — Release checked and refused the false claim. |
| H2 database lock error on startup | Another instance is running. Kill it: `pkill -f "SNAPSHOT.jar"` then restart. |
| `release version 21 not supported` | Maven is using Java 17. Run: `export JAVA_HOME=$(/usr/libexec/java_home -v 25)` |

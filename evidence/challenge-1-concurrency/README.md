# challenge-1-concurrency

**Claim:** simultaneous requests for a contended resource never over-allocate it, and
the database decides the winner, not application code.

Two contended resources are exercised in one run:

1. **The quarantine record for a file.** `quarantine_records.file_id` has a unique
   index. Eight identical `POST /api/v1/quarantine` requests for the same fileId are
   released from a client-side barrier at the same instant.
2. **Scanner capacity.** `scan_slots` holds one row per slot (`MAX_CONCURRENT_SCANS`,
   here 2). A scan may only run while it owns a row, and the claim is a single
   conditional `UPDATE scan_slots SET status='HELD' ... WHERE slot_number=? AND
   status='FREE'`. Twenty files are scanned in parallel while Scanner holds each call
   for 2000 ms, so the requests genuinely overlap.

## How to repeat

```bash
mvn -pl quarantine-service,scanner-service -am package
python3 scripts/concurrency.py 2>&1 | tee evidence/challenge-1-concurrency/run.txt
```

The script starts Scanner and Quarantine itself on free ports against temporary
file-based H2 databases, so no reset is needed and nothing of the developer's data is
touched. It ends with `CHALLENGE_1_PASS` or exits non-zero with `CHALLENGE_1_FAIL`.

## Evidence

`run.txt` is one captured run, so the elapsed times, ports and file IDs in it move from
run to run. The script's assertions are exact counts (`2x200`, `18x409`, `total=1`,
`one scan_completed per file`), so a fresh run upholds every claim below with different
timings.

| Claim | Artifact and observation |
|---|---|
| Exactly N of the parallel requests succeed | `run.txt`: `PART_2_PARALLEL_SCANS` reports `count=2 status=200` and `count=18 status=409 message=Scanner capacity exhausted` against `capacity=2`, all 20 fired within 2198 ms |
| The losers get a meaningful error, not a crash | `run.txt`: the only statuses are 200/201/409; the script asserts `500 not in statuses` |
| A creation race leaves one row | `run.txt`: `PART_1_SIMULTANEOUS_CREATIONS` gives 1x201 + 7x409 and `PART_1_DATABASE total=1 QUARANTINED=1` |
| The contended resource is never over-allocated | `run.txt`: `QUARANTINE_LOG_EVIDENCE` shows exactly two `scanner_slot_acquired` lines (`slot=1`, `slot=2`) before any `scanner_capacity_rejected` |
| Capacity is returned, not leaked | `run.txt`: `PART_3_AFTER_BURST` and `PART_3_FINALLY` both show `scannerSlots {"FREE": 2, "HELD": 0}` and `SCANNING: 0` |
| Rejections delay work instead of losing it | `run.txt`: the 18 refused files are scanned afterwards, one by one, and `PART_3_FINALLY` shows `CLEAN: 20` |
| Retries and rejections do not create duplicate scans | `run.txt`: `SCANNER_SCAN_COMPLETED_TOTAL 20 for 20 files` in the Scanner log |
| State is consistent afterwards | `run.txt`: final summary `total=21` = 20 scanned files + 1 never-scanned record from Part 1 |

## Why a plain check-then-update in application code would fail

`if (repository.isSlotFree()) { repository.takeSlot(); }` reads a value, decides on it,
and writes it back as three steps. Two requests can both read `FREE` in the same
millisecond, both decide they may proceed, and both write — the capacity is exceeded
and neither caller is wrong about what it saw. The window is tiny, so the bug survives
testing and appears under load.

This implementation removes the window instead of narrowing it. The condition lives in
the write itself: `UPDATE ... WHERE status='FREE'` is evaluated by the database under a
row lock, so exactly one of the racing statements changes a row. The code branches on
`rowsUpdated == 1`, which is the database's answer, not an application guess. The same
argument covers the creation race, where the unique index makes one insert succeed and
the loser receives a `DataIntegrityViolationException` that Quarantine maps to the same
409 a sequential repeat gets. The `existsByFileId` check in front of it is only a fast
path for the common case; correctness comes from the constraint.

Keeping the ledger in a table rather than in a `java.util.concurrent.Semaphore` also
means the allocation is enforced by the shared database, so two Quarantine replicas
pointed at the same database share one capacity, and the state survives a restart.

## Automated coverage of the same claim

`QuarantineScanResilienceTest.twentyParallelScansNeverHoldMoreThanTwoScannerSlots`
fires the same burst against an embedded application with capacity 2 and asserts that
the stub Scanner service never observed more than two calls at the same instant, and
that every slot was released afterwards.

`evidence/tests/quarantine-http-tests.txt` and
`evidence/tests/quarantine-unit-tests.txt` hold the current test output.

# challenge-3a-resilience

**Claim:** when the downstream Scanner service is slow or stopped, Quarantine times the
call out, retries with exponential backoff plus jitter, fails gracefully, and no retry
causes a duplicate effect.

Quarantine calls Scanner over REST in `ScannerClient`. Every call carries a connect
timeout and a read timeout; a failed attempt is retried after
`retry-base * 2^(attempt-1) + random(0..jitter)` up to `max-attempts` times; when the
whole budget is used up the file settles as `FAILED` and the scanner capacity slot is
released. Scanner keys its store on the `fileId`, so a retry that arrives while a scan
is still running waits for that scan and replays its result instead of starting a
second one — that is what makes the retries safe.

The demo budget is deliberately small (500 ms read timeout, 3 attempts, 200 ms base,
150 ms jitter, Scanner holds a SLOW scan for 1500 ms) so an outage costs about a second.
Production defaults in `quarantine-service/src/main/resources/application.yml` are
5000 ms, 3 attempts, 100 ms base and 100 ms jitter.

## How to repeat

```bash
mvn -pl quarantine-service,scanner-service -am package
python3 scripts/resilience.py 2>&1 | tee evidence/challenge-3a-resilience/run.txt
```

The script starts Scanner and Quarantine on its own ports against temporary file-based
H2 databases, stops Scanner mid-run and starts it again, and prints `PASS`/`FAIL` per
claim. It exits non-zero with `CHALLENGE_3A_FAIL` if any check does not hold.

## Evidence

`run.txt` is a single captured run, so the jittered delays, elapsed times, ports and
file IDs in it differ from run to run. The claims below quote that file; the assertions
the script makes are range assertions (`delays are at least the base`, `exactly 3
attempts`, `exactly 1 stored scan`), so a fresh run upholds them with different numbers.

| Claim | Artifact and observation |
|---|---|
| A slow call is cut off by a timeout | `run.txt` PART_A: `scanner_attempt_failed ... attempt=1 error=I/O error on POST request for "http://127.0.0.1:.../api/v1/scans": request timed out (HttpTimeoutException: request timed out)`, and `PASS every logged failure of Part A is a timeout` |
| Retries use exponential backoff | `run.txt` PART_A: `scanner_retry ... nextAttempt=2 delayMillis=320` then `nextAttempt=3 delayMillis=459`, i.e. a 200 ms base doubled to 400 ms before jitter |
| Jitter is actually applied | `run.txt` PART_A: the two delays are `200 + j1` and `400 + j2` with different `j`, so replicas that fail together do not retry on the same tick; `ScannerClientTest.delayGrowsExponentiallyAndIsBoundedByTheJitter` pins the bounds of each attempt's interval and `withoutJitterTheDelayIsExactlyTheExponentialTerm` shows the term jitter is added to |
| A retry still delivers the verdict | `run.txt` PART_A: `RESPONSE status=200 state=CLEAN elapsedMillis=1851` — a later attempt got Scanner's result after the slow scan finished, and the caller paid the whole budget rather than being cut off |
| Retries cause no duplicate effect | `run.txt` PART_A: `Scanner stored 1 attempt for this file, expected exactly 1` from Scanner's own `_evidence/summary` count; PART_D: a repeat straight to Scanner returns the identical `completedAt` and `totalAttempts stayed at 3` |
| Graceful failure when the dependency is stopped | `run.txt` PART_B: `RESPONSE status=200 state=FAILED` with `every attempt failed at the connection, not on the clock`; the caller is never left with a 500 or a hung request |
| The whole budget is spent before giving up | `run.txt` PART_B: exactly 3 `scanner_call ... attempt=1/2/3` lines and `scan_failed_downstream_unavailable ... message=Scanner did not answer after 3 attempts` |
| Capacity is not leaked by a failure | `run.txt` PART_B: `EVIDENCE ... "scannerSlots": {"FREE": 2, "HELD": 0}` after the failed scan |
| The service recovers on its own | `run.txt` PART_C: with Scanner started again, a new file settles `CLEAN` in 119 ms while Quarantine reported `status=200 state=UP` at `/health` throughout |
| A settled record is never re-decided | `run.txt` PART_C: re-scanning the file that failed during the outage returns 409 `File has already been scanned` |
| The correlation is carried across the boundary | The outbound call forwards `X-Correlation-Id`; `QuarantineScanResilienceTest.forwardsTheCallersCorrelationIdToTheScanner` asserts the downstream received the caller's value |

## Honest limitations

- The `FAILED` verdict is final by design (BR3): once the outage settles a record, the
  file is not rescanned automatically when Scanner comes back. A real system would
  requeue it or run a reconciliation job; the same-file requeue is deliberately absent
  because the assignment's state machine forbids a second transition.
- Circuit breaker is a bonus and is not implemented; the retry budget and the capacity
  slots bound the blast radius instead. See `docs/business-rules.md`.
- Backoff delays are measured with `Thread.sleep` on the request thread, so a caller
  waiting through an outage waits about the whole budget (roughly 0.9-1.9 s here).
- The JDK HTTP client reports a read timeout as `HttpTimeoutException` or as
  `TimeoutException` depending on where the socket gave up; both are asserted to be
  timeouts, and neither is ever a response-parsing error — which is the reason the
  client is not `SimpleClientHttpRequestFactory`. See `quarantine-service/README.md`.

## Automated coverage of the same claims

`QuarantineScanResilienceTest` (stub Scanner on a real socket): transient 503 retried
into a success with exactly three calls, a scanner slower than the read timeout failing
inside the budget, the retry budget exhausted into `FAILED`, capacity refused while
held, and the correlation forwarded. `ScannerClientTest` covers the backoff curve and
the reason reported for a failed attempt.

Current test output is in `evidence/tests/quarantine-unit-tests.txt` and
`evidence/tests/quarantine-http-tests.txt`.

# Business rules

Four rules govern the secure file quarantine and release flow. Each is stated as a
testable invariant, points at the one place it is enforced and lists the unit and
HTTP tests that prove it. The rule text matches the wording used in the design
report so claims stay traceable in both documents.

| Rule | Statement | Owner | Enforced in |
|---|---|---|---|
| BR1 | A repeated intake with the same `Idempotency-Key` and the same payload returns the original response and stores no second record | Intake | `IntakeService.replay` |
| BR2 | The same `Idempotency-Key` with a different payload is rejected with 409 and leaves the original record untouched | Intake | `IntakeService.replay` fingerprint comparison |
| BR3 | A file is quarantined once, scanned once, and its scan outcome follows the state machine `QUARANTINED → SCANNING → CLEAN \| INFECTED \| FAILED`, where the verdict comes from the Scanner service | Quarantine | `QuarantineService.quarantine`, `QuarantineService.scan`, `ScanSlotRepository.claim`, `ScanReclaimJob` |
| BR4 | A file is released only when Quarantine's own record reports it `CLEAN` with the timestamp the caller supplied; a claim that does not match is 409 and stores nothing, and a state that cannot be checked is 503 | Release | `ReleaseService.requireClaimedReleasable`, `QuarantineStateVerifier.requireReleasable`, `QuarantineClient.findForFile` |

## Traceability

### BR1 — intake replay is not a second operation

* Unit: `IntakeServiceTest.firstRequestStoresKeyFingerprintAndResponseTogether`,
  `IntakeServiceTest.sameKeyAndPayloadReplaysTheEntireOriginalResponse`,
  `IntakeServiceTest.uniqueKeyRaceReplaysCommittedWinnerAfterWriterRollback`
* HTTP: `IntakeHttpTest.createReplayReadAndEvidenceAgreeWithTheStoredResponse`,
  `IntakeHttpTest.eightSimultaneousSameKeyRequestsCreateOneDatabaseRow`,
  `IntakeHttpTest.jsonPropertyOrderAndWhitespaceDoNotChangeTheFingerprint`
* Restart: `scripts/persistence.py` (one row survives a real process restart)

### BR2 — one key cannot carry two different operations

* Unit: `IntakeServiceTest.sameKeyWithDifferentPayloadIsConflict`,
  `IntakeServiceTest.uniqueKeyRaceWithDifferentPayloadIsConflict`,
  `IntakeServiceTest.unrelatedDatabaseFailureIsNotTreatedAsSuccessfulReplay`
* HTTP: `IntakeHttpTest.differentPayloadReturns409AndDoesNotChangeTheOriginal`,
  `IntakeHttpTest.simultaneousDifferentPayloadsHaveOneWinnerAndOneConflict`,
  `IntakeHttpTest.missingBlankOrOversizedKeyReturns400`

### BR3 — quarantine records and scan each file once

* Unit: `QuarantineServiceTest.newFileIsQuarantinedAndWaitsForAScan`,
  `QuarantineServiceTest.alreadyQuarantinedFileCannotBeQuarantinedAgain`,
  `QuarantineServiceTest.simultaneousQuarantineOfOneFileReportsTheConflictTheSequentialRepeatGets`,
  `QuarantineServiceTest.scanAsksTheScannerOverItsClientAndStoresTheVerdictAndTimestamp`,
  `QuarantineServiceTest.scannerOutageSettlesTheFileAsFailedAndStillGivesTheSlotBack`,
  `QuarantineServiceTest.unexpectedScannerResultIsTreatedAsAFailedScan`,
  `QuarantineServiceTest.aResponseWithoutAVerdictIsTreatedAsAFailedScan`,
  `QuarantineServiceTest.exhaustedCapacityRefusesTheScanAndLeavesItQuarantined`,
  `QuarantineServiceTest.aScannedRecordCannotBeScannedTwice`,
  `QuarantineServiceTest.aScanAlreadyInFlightIsRefusedRatherThanStartedAgain`,
  `QuarantineServiceTest.unknownQuarantineRecordIsNotFound`,
  `QuarantineServiceTest.summaryAddsTheStatesItCountsAndReportsTheCapacity`
* HTTP, against a stub Scanner on the same port: `QuarantineHttpTest.createsScansFetchesAndSummarizesQuarantineRecord`,
  `QuarantineScanResilienceTest.twentyParallelScansNeverHoldMoreThanTwoScannerSlots`,
  `QuarantineScanResilienceTest.refusesTheThirdScanWhileBothCapacitySlotsAreHeld`,
  `QuarantineScanResilienceTest.aFailedFileIsNotScannedAgainByASecondRequest`
* Client: `ScannerClientTest` (retry curve, jitter bounds, exponent cap, failure reason,
  budget validation) and
  `QuarantineScanResilienceTest.retriesATransientScannerFailureAndScansTheFileExactlyOnce`,
  `QuarantineScanResilienceTest.settlesTheFileAsFailedOnceTheRetryBudgetIsUsedUp`,
  `QuarantineScanResilienceTest.treatsAScannerSlowerThanTheReadTimeoutAsAFailedAttemptAndGivesUpInTheBudget`
* Concurrency: `scripts/concurrency.py` → `evidence/challenge-1-concurrency/run.txt`
  (eight simultaneous creations leave one record; twenty parallel scans at a capacity of
  two give exactly two 200s and eighteen 409s, and no 500 anywhere)
* Crash consistency: `ScanReclaimJobTest.freesTheSlotOfAScanThatNeverFinished`,
  `.settlesTheAbandonedRecordAsFailedExactlyOnce`, `.leavesARecentScanAloneBecauseItMayStillBeRunning`,
  `.doesNotSettleARecordWhoseSlotIsStillHeld`, `.neverReopensARecordThatAlreadySettled`;
  `scripts/reclaim.py` → `evidence/challenge-1-concurrency/reclaim-run.txt` kills a scan
  mid-call and shows the sweep restoring `FREE 2 / HELD 0` and `SCANNING 0`
* Resilience: `scripts/resilience.py` → `evidence/challenge-3a-resilience/run.txt`,
  including Part E with the circuit breaker enabled
* Circuit breaker: `ScannerCircuitBreakerTest`,
  `QuarantineCircuitBreakerTest.aDownScannerTripsTheCircuitAndRefusesCallsUntilTheServiceRecovers`
* Restart: `scripts/quarantine-persistence.py` (the state, the timestamps, Scanner's
  stored verdict and the unique `fileId` all survive a restart, so a replayed create is
  still 409 and a replayed scan is still 409)

Two mechanisms make the rule hold under concurrency, and neither is a check-then-act
inside the request transaction:

* **One record per file.** `existsByFileId` is only a fast path. The write goes through
  `QuarantineRecordWriter` in its own `REQUIRES_NEW` transaction with `saveAndFlush`, so
  the unique `fileId` index decides a race immediately; the loser catches
  `DataIntegrityViolationException` and answers 409 `File is already quarantined`, the
  same conflict a sequential repeat gets. This replaced a 500 from the shared `Exception`
  handler, which was the defect Challenge 1 asks about.
* **One scan at a time per slot.** A scan claims a row of `scan_slots` with one atomic
  conditional update (`WHERE slot_number = ? AND status = 'FREE'`) before it moves the
  record to `SCANNING`. `rowsUpdated == 1` is the database's answer, not an application
  guess. A request that finds every slot held is refused with 409 `Scanner capacity
  exhausted` and the record is left in `QUARANTINED`, so its scan is delayed rather than
  lost. The slot is released in a `finally` block, so even a `FAILED` settle returns it.

### BR4 — only a clean quarantined file is released, verified at the owner

* Unit: `ReleaseServiceTest.infectedCannotRelease`,
  `ReleaseServiceTest.cleanAfterQuarantineReleases`,
  `ReleaseServiceTest.cleanFileWithoutQuarantineTimestampCannotBeReleased`,
  `ReleaseServiceTest.onlyTheCleanStateCanBeReleased`,
  `ReleaseServiceTest.alreadyReleasedFileReplaysTheOriginalRecordWithoutWriting`,
  `ReleaseServiceTest.simultaneousReleaseRaceReplaysTheCommittedWinner`,
  `ReleaseServiceTest.aClientCannotReleaseAFileQuarantineReportsAsInfected`,
  `ReleaseServiceTest.aFileThatWasNeverQuarantinedCannotBeReleased`,
  `ReleaseServiceTest.aScanStillInFlightIsNotAReleaseableCleanFile`,
  `ReleaseServiceTest.aStaleQuarantineTimestampDoesNotMatchTheRecord`,
  `ReleaseServiceTest.aTimestampThatDiffersOnlyByStoredPrecisionIsStillTheSameInstant`,
  `ReleaseServiceTest.aTimestampOneMillisecondApartIsNotTheSameQuarantine`,
  `ReleaseServiceTest.anUnreachableQuarantineRefusesTheReleaseInsteadOfTrustingTheCaller`
* HTTP, against a stub Quarantine answering on the application's own port:
  `ReleaseHttpTest.unreleasableRequestsReturn409WithoutStoringAnything`,
  `ReleaseHttpTest.releaseReadAndRepeatAgreeWithOneStoredRecord`,
  `ReleaseHttpTest.eightSimultaneousRequestsReleaseOnce`,
  `ReleaseHttpTest.aClientCannotReleaseAFileQuarantineReportsAsInfected`,
  `ReleaseHttpTest.anUnreachableQuarantineRefusesTheReleaseWith503AndStoresNothing`,
  `ReleaseHttpTest.replayingAFinishedReleaseDoesNotAskQuarantineAgain`,
  `ReleaseHttpTest.aQuarantineThatIsDownCannotUnmakeAFileThatWasAlreadyReleased`
* Client: `QuarantineClientTest` for the verification budget's curve and reporting
* Cross-process: `scripts/verification.py` → `evidence/tests/verification-run.txt`
  (twenty-two checks: verified release, false claim refused, never-quarantined refused,
  wrong timestamp refused, outage answered 503 with nothing stored, recovery releases the
  same file, replay works with the dependency down)
* End to end: `scripts/e2e.sh` reads `GET /api/v1/quarantine/by-file/{fileId}` and then
  refuses a `CLEAN` claim for the file Quarantine reports as INFECTED
* Restart: `scripts/release-persistence.py`

The caller still submits `quarantineState` and `quarantinedAt`, because Release owns its
own table and must not read Quarantine's; what changed is that the submission is treated
as a claim and checked against the service that owns the state. The two-stage order keeps
the cost low: a finished release replays with no outbound call at all, so a Quarantine
outage can never unmake a file that is already out, and a request that fails the local
checks never reaches the network either.

## Scanner invariants

Scanner has no numbered rule of its own; its behaviour is what makes BR3 and BR4
safe to repeat. `ScannerServiceTest` and `ScannerHttpTest` cover deterministic mode
results, replay of a stored attempt for a retried `fileId`, the in-flight wait, the
unique-key race and the interruption path. See
[scanner-service/README.md](../scanner-service/README.md) for its traceability table.

Scanner is the only service that is called for a decision. Quarantine's `ScannerClient`
owns the Challenge 3A budget — connect and read timeouts, at most `max-attempts` tries, a
delay of `retry-base * 2^(attempt-1) + random(0..retry-jitter)` — and because Scanner
keys the result on `fileId`, a retry after a timeout replays the scan that is already
running instead of starting a second one. `X-Correlation-Id` is forwarded, so one
click of the client is readable in both services' logs. An optional circuit breaker sits
in front of that budget, off by default so the retry evidence stays reproducible.

The second service-to-service edge is Release asking Quarantine whether a file really is
`CLEAN` (`GET /api/v1/quarantine/by-file/{fileId}`), with its own identical budget
configuration under `quarantine.*`. It is a read, so repeating it is safe, and it answers
only a request that has not already been granted: a released file replays from Release's
own table.

`scripts/e2e.sh` exercises BR1 → BR3 → BR4 in one scripted run across all four
services, 32 asserted steps, and fails loudly if any step returns an unexpected status.
For BR3 it asserts that Scanner had never seen the file before the scan, that Scanner's
own database holds the attempt afterwards, and that Quarantine stored Scanner's
`finishedAt` rather than a timestamp of its own. For BR4 it reads the record Release will
verify against, and refuses a `CLEAN` claim for the file that is genuinely infected.

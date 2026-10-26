# Business rules

Four rules govern the secure file quarantine and release flow. Each is stated as a
testable invariant, points at the one place it is enforced and lists the unit and
HTTP tests that prove it. The rule text matches the wording used in the design
report so claims stay traceable in both documents.

| Rule | Statement | Owner | Enforced in |
|---|---|---|---|
| BR1 | A repeated intake with the same `Idempotency-Key` and the same payload returns the original response and stores no second record | Intake | `IntakeService.replay` |
| BR2 | The same `Idempotency-Key` with a different payload is rejected with 409 and leaves the original record untouched | Intake | `IntakeService.replay` fingerprint comparison |
| BR3 | A file is quarantined once, scanned once, and its scan outcome follows the state machine `QUARANTINED → CLEAN \| INFECTED \| FAILED` | Quarantine | `QuarantineService.quarantine`, `QuarantineService.scan` |
| BR4 | Only a submitted `CLEAN` quarantine state with a quarantine timestamp may be released; anything else is 409 and stores nothing | Release | `ReleaseService.requireReleasable` |

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
  `QuarantineServiceTest.aScannedRecordCannotBeScannedTwice`,
  `QuarantineServiceTest.everyScannerModeResolvesToItsDeclaredState`,
  `QuarantineServiceTest.unknownQuarantineRecordIsNotFound`,
  `QuarantineServiceTest.summaryAddsTheStatesItCounts`
* HTTP: `QuarantineHttpTest.createsScansFetchesAndSummarizesQuarantineRecord`
* Restart: `scripts/quarantine-persistence.py` (state, timestamps and the unique
  `fileId` all survive a restart, so a replayed create is still 409)
* Known limitation: `QuarantineService.quarantine` checks `existsByFileId` and then
  saves. The unique `fileId` column is the real guard, but the losing concurrent
  request surfaces the constraint violation as a 500 through the shared `Exception`
  handler instead of the 409 a client expects. Intake and Release catch the
  violation and replay or conflict instead; Quarantine should do the same before
  Challenge 1 evidence is recorded here.

### BR4 — only a clean quarantined file is released

* Unit: `ReleaseServiceTest.infectedCannotRelease`,
  `ReleaseServiceTest.cleanAfterQuarantineReleases`,
  `ReleaseServiceTest.cleanFileWithoutQuarantineTimestampCannotBeReleased`,
  `ReleaseServiceTest.onlyTheCleanStateCanBeReleased`,
  `ReleaseServiceTest.alreadyReleasedFileReplaysTheOriginalRecordWithoutWriting`,
  `ReleaseServiceTest.simultaneousReleaseRaceReplaysTheCommittedWinner`
* HTTP: `ReleaseHttpTest.unreleasableRequestsReturn409WithoutStoringAnything`,
  `ReleaseHttpTest.releaseReadAndRepeatAgreeWithOneStoredRecord`,
  `ReleaseHttpTest.eightSimultaneousRequestsReleaseOnce`
* Restart: `scripts/release-persistence.py`

## Scanner invariants

Scanner has no numbered rule of its own; its behaviour is what makes BR3 and BR4
safe to repeat. `ScannerServiceTest` and `ScannerHttpTest` cover deterministic mode
results, replay of a stored attempt for a retried `fileId`, the in-flight wait, the
unique-key race and the interruption path. See
[scanner-service/README.md](../scanner-service/README.md) for its traceability table.

`scripts/e2e.sh` exercises BR1 → BR3 → BR4 in one scripted run across all four
services and fails loudly if any step returns an unexpected status.

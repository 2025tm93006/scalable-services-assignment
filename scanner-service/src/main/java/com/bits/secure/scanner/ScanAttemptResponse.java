package com.bits.secure.scanner;

import java.time.Instant;
import java.util.UUID;

/** Stored attempt for one file; result and finishedAt are null while the scan runs. */
public record ScanAttemptResponse(UUID fileId, String mode, String result, Instant startedAt, Instant finishedAt) {

    static ScanAttemptResponse from(ScanAttempt attempt) {
        return new ScanAttemptResponse(attempt.getFileId(), attempt.getMode(), attempt.getResult(),
                attempt.getStartedAt(), attempt.getFinishedAt());
    }
}

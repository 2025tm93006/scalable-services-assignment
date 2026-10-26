package com.bits.secure.scanner;

import java.time.Instant;
import java.util.UUID;

// Field names match the ScannerResult record that Quarantine deserializes.
public record ScanResponse(UUID fileId, String result, String message, Instant completedAt) {

    static ScanResponse from(ScanAttempt attempt) {
        String message = "FAILED".equals(attempt.getResult())
                ? "Deterministic scanner failure" : "Deterministic mock result";
        return new ScanResponse(attempt.getFileId(), attempt.getResult(), message, attempt.getFinishedAt());
    }
}

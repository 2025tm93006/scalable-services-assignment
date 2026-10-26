package com.bits.secure.scanner;

import java.util.Map;

/** Read-only attempt counts used to show that retries did not create duplicate scans. */
public record ScannerEvidence(long totalAttempts, Map<String, Long> attemptsByResult) {
}

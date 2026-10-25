package com.bits.secure.quarantine;

import java.time.Instant;
import java.util.UUID;

public record QuarantineResponse(
        UUID id,
        UUID fileId,
        String state,
        String scannerMode,
        Instant quarantinedAt,
        Instant scannedAt) {
}
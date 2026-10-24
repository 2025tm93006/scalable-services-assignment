package com.bits.secure.intake;

import java.time.Instant;
import java.util.UUID;

public record IntakeResponse(UUID fileId, String status, String filename,
                             String scannerMode, Instant receivedAt) {
}

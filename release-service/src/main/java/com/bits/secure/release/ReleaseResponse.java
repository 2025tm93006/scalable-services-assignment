package com.bits.secure.release;

import java.time.Instant;
import java.util.UUID;

public record ReleaseResponse(UUID fileId, String state, Instant quarantinedAt, Instant releasedAt) {

    static ReleaseResponse from(ReleaseRecord record) {
        return new ReleaseResponse(record.getFileId(), record.getState(),
                record.getQuarantinedAt(), record.getReleasedAt());
    }
}

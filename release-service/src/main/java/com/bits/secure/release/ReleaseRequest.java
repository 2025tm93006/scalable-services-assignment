package com.bits.secure.release;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.UUID;

public record ReleaseRequest(
        @NotNull UUID fileId,
        @NotBlank @Size(max = 32) String quarantineState,
        // Optional so a quarantined file without a timestamp is a business
        // conflict (409) rather than a malformed request (400).
        Instant quarantinedAt) {
}

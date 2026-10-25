package com.bits.secure.quarantine;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import java.util.UUID;

public record QuarantineRequest(
        @NotNull UUID fileId,
        @NotBlank @Pattern(regexp = "CLEAN|INFECTED|FAIL|SLOW") String scannerMode) {
}
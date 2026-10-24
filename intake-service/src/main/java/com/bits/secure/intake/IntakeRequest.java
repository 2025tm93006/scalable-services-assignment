package com.bits.secure.intake;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

public record IntakeRequest(
        @NotBlank @Size(max = 255) String filename,
        @NotBlank @Size(max = 255) String contentType,
        @NotNull @PositiveOrZero Long sizeBytes,
        @NotBlank @Pattern(regexp = "[a-fA-F0-9]{64}") String sha256,
        @NotBlank @Pattern(regexp = "CLEAN|INFECTED|FAIL|SLOW") String scannerMode) {
}

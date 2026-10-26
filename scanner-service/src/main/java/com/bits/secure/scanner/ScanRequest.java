package com.bits.secure.scanner;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import java.util.UUID;

public record ScanRequest(
        @NotNull UUID fileId,
        @NotBlank @Pattern(regexp = "CLEAN|INFECTED|FAIL|SLOW",
                message = "must be one of CLEAN, INFECTED, FAIL, SLOW") String mode) {
}

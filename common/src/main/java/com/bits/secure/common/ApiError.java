package com.bits.secure.common;

import java.time.Instant;
import java.util.Map;

public record ApiError(Instant timestamp, int status, String error, String message,
                       String path, String correlationId, Map<String, String> fieldErrors) {
}

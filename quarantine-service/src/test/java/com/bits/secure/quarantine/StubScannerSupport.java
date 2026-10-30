package com.bits.secure.quarantine;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Stands in for the Scanner service so the HTTP tests exercise a real REST call,
 * real timeouts and real retries without starting a second Spring Boot application.
 * The nested controller is picked up by the test application's component scan, which
 * is why a scan posted to this application's own port is answered here. It returns
 * the same JSON contract Scanner publishes, and it records how many times each file
 * was called, which is how the tests show that a retry does not silently scan twice.
 */
public final class StubScannerSupport {
    private static final Map<String, AtomicInteger> CALLS = new ConcurrentHashMap<>();
    private static final AtomicInteger IN_FLIGHT = new AtomicInteger();
    private static final AtomicInteger MAX_IN_FLIGHT = new AtomicInteger();
    // Monotonic for the whole test run and deliberately not cleared by reset(), so a test
    // can assert that a call reached the stub at all - a circuit-refused scan must not.
    private static final AtomicInteger TOTAL_CALLS = new AtomicInteger();
    private static volatile long delayMillis = 0;
    private static volatile int failingAttempts = 0;
    private static volatile String lastCorrelationId = null;

    private StubScannerSupport() {
    }

    static void reset(long sleepMillis, int failedAttemptsAhead) {
        CALLS.clear();
        IN_FLIGHT.set(0);
        MAX_IN_FLIGHT.set(0);
        delayMillis = sleepMillis;
        failingAttempts = failedAttemptsAhead;
        lastCorrelationId = null;
    }

    static String lastCorrelationId() {
        return lastCorrelationId;
    }

    /**
     * The most calls this stub ever handled at the same instant. Quarantine is only
     * allowed to reach this number, because that is what its capacity slots enforce.
     */
    static int maxConcurrentCalls() {
        return MAX_IN_FLIGHT.get();
    }

    static int callsFor(UUID fileId) {
        AtomicInteger seen = CALLS.get(String.valueOf(fileId));
        return seen == null ? 0 : seen.get();
    }

    /** Every call this stub has ever handled in this JVM run, across all files. */
    static int totalCalls() {
        return TOTAL_CALLS.get();
    }

    @RestController
    static class StubScanner {
        @PostMapping("/api/v1/scans")
        ResponseEntity<Map<String, Object>> scan(@RequestBody Map<String, Object> request,
                                                 @RequestHeader(name = "X-Correlation-Id", required = false)
                                                 String correlationId) {
            lastCorrelationId = correlationId;
            TOTAL_CALLS.incrementAndGet();
            int running = IN_FLIGHT.incrementAndGet();
            MAX_IN_FLIGHT.accumulateAndGet(running, Math::max);
            try {
                return answer(request);
            } finally {
                IN_FLIGHT.decrementAndGet();
            }
        }

        private ResponseEntity<Map<String, Object>> answer(Map<String, Object> request) {
            UUID fileId = UUID.fromString((String) request.get("fileId"));
            String mode = (String) request.get("mode");
            int attempt = CALLS.computeIfAbsent(String.valueOf(fileId), key -> new AtomicInteger())
                    .incrementAndGet();
            if (delayMillis > 0) {
                try {
                    Thread.sleep(delayMillis);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
            if (attempt <= failingAttempts) {
                return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                        .body(Map.of("status", 503, "message", "scanner is down"));
            }
            String result = switch (mode) {
                case "INFECTED" -> "INFECTED";
                case "FAIL" -> "FAILED";
                default -> "CLEAN";
            };
            return ResponseEntity.ok(Map.of(
                    "fileId", fileId,
                    "result", result,
                    "message", "Deterministic stub result",
                    // Truncated to milliseconds, exactly like the real Scanner service.
                    "completedAt", Instant.now().truncatedTo(ChronoUnit.MILLIS)));
        }
    }
}

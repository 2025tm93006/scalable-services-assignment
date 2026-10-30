package com.bits.secure.release;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Stands in for the Quarantine service so the HTTP tests exercise Release's verification
 * call over a real socket: the endpoint, the address-by-fileId contract, a 404, a server
 * error that has to be retried, and the state Release is told.
 *
 * Like the Scanner stub in the quarantine module, the nested controller is found by the
 * test application's own component scan and answers on the application's port, and it must
 * not also be registered as a @Bean - doing both gives an ambiguous mapping that stops the
 * context from starting.
 */
public final class StubQuarantineSupport {
    private static final AtomicInteger CALLS = new AtomicInteger();
    private static volatile String state = "CLEAN";
    private static volatile String quarantinedAt = "2026-10-24T08:00:00Z";
    private static volatile Integer failureStatus = null;
    private static volatile boolean recordAbsent = false;

    private StubQuarantineSupport() {
    }

    /** The ordinary case: this file was quarantined and Scanner reported the state. */
    static void answer(String answerState, String answerQuarantinedAt) {
        recordAbsent = false;
        failureStatus = null;
        state = answerState;
        quarantinedAt = answerQuarantinedAt;
    }

    static void recordAbsent() {
        recordAbsent = true;
        failureStatus = null;
    }

    /** Every read answers this status, so the retry budget and the 503 can be observed. */
    static void failingWith(int status) {
        recordAbsent = false;
        failureStatus = status;
    }

    static int calls() {
        return CALLS.get();
    }

    static void reset() {
        CALLS.set(0);
        answer("CLEAN", "2026-10-24T08:00:00Z");
    }

    @RestController
    static class StubQuarantine {
        @GetMapping("/api/v1/quarantine/by-file/{fileId}")
        ResponseEntity<Map<String, Object>> byFile(@PathVariable String fileId) {
            CALLS.incrementAndGet();
            if (failureStatus != null) {
                return ResponseEntity.status(HttpStatus.valueOf(failureStatus))
                        .body(Map.of("status", failureStatus, "message", "stub quarantine failure"));
            }
            if (recordAbsent) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(Map.of("status", 404, "message", "No quarantine record for file " + fileId));
            }
            Map<String, Object> record = new LinkedHashMap<>();
            record.put("id", "0f0f0f0f-0f0f-0f0f-0f0f-0f0f0f0f0f0f");
            record.put("fileId", fileId);
            record.put("state", state);
            record.put("scannerMode", "CLEAN");
            record.put("quarantinedAt", quarantinedAt);
            // Null until the file has actually been scanned, exactly like the real response.
            record.put("scannedAt", "QUARANTINED".equals(state) || "SCANNING".equals(state) ? null : quarantinedAt);
            return ResponseEntity.ok(record);
        }
    }
}

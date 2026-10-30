package com.bits.secure.quarantine;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The circuit breaker where it actually lives: inside a real scan request, against the
 * stub Scanner on this application's own port. One scenario, in order, because the
 * breaker is state on a singleton bean and a second test method would inherit the state
 * the first one left - which is precisely the behaviour under test.
 *
 * Budget: 300 ms read timeout, 2 attempts, 100 ms backoff, so a call that fails spends
 * roughly 700 ms. Cooldown 1200 ms, threshold 2 failed calls.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT, properties = {
        "spring.datasource.url=jdbc:h2:mem:quarantine-breaker-test;DB_CLOSE_DELAY=-1",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "server.port=18094",
        "scanner.base-url=http://localhost:18094",
        "scanner.connect-timeout-millis=200",
        "scanner.read-timeout-millis=300",
        "scanner.max-attempts=2",
        "scanner.retry-base-millis=100",
        "scanner.retry-jitter-millis=0",
        "scanner.circuit-breaker.enabled=true",
        "scanner.circuit-breaker.failure-threshold=2",
        "scanner.circuit-breaker.open-millis=1200",
        "quarantine.max-concurrent-scans=2"
})
class QuarantineCircuitBreakerTest {
    @Autowired
    private TestRestTemplate http;

    @Autowired
    private QuarantineRepository repository;

    @Autowired
    private ScanCapacityService capacity;

    @Test
    void aDownScannerTripsTheCircuitAndRefusesCallsUntilTheServiceRecovers() throws Exception {
        StubScannerSupport.reset(0, 99);     // every call answers 503
        repository.deleteAll();
        int callsBeforeTheScenario = StubScannerSupport.totalCalls();

        // Two failed calls, each paying its whole retry budget, reach the threshold.
        UUID firstFile = quarantine();
        assertThat(stateOf(scan(firstFile))).isEqualTo("FAILED");
        assertThat(StubScannerSupport.callsFor(firstFile))
                .as("a failing call still attempts every try")
                .isEqualTo(2);

        UUID secondFile = quarantine();
        assertThat(stateOf(scan(secondFile))).isEqualTo("FAILED");
        assertThat(StubScannerSupport.callsFor(secondFile)).isEqualTo(2);

        // The third is refused by the open circuit: no HTTP request at all.
        UUID refusedFile = quarantine();
        long started = System.nanoTime();
        ResponseEntity<QuarantineResponse> refused = scan(refusedFile);
        long refusedIn = Duration.ofNanos(System.nanoTime() - started).toMillis();

        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(stateOf(refused))
                .as("a refused call is still a graceful failure, not a 500")
                .isEqualTo("FAILED");
        assertThat(StubScannerSupport.callsFor(refusedFile))
                .as("the circuit was open, so Scanner was never contacted")
                .isZero();
        assertThat(StubScannerSupport.totalCalls())
                .as("no extra calls reached the stub while the circuit was open")
                .isEqualTo(callsBeforeTheScenario + 4);
        assertThat(refusedIn)
                .as("a refusal costs far less than the 700 ms a full budget costs")
                .isLessThan(250);
        assertThat(capacity.countHeld())
                .as("the refused scan still gave its slot back")
                .isZero();

        // Cooldown over and Scanner healthy again: one probe is allowed through.
        Thread.sleep(1300);
        StubScannerSupport.reset(0, 0);
        UUID probeFile = quarantine();

        assertThat(stateOf(scan(probeFile))).isEqualTo("CLEAN");
        assertThat(StubScannerSupport.callsFor(probeFile))
                .as("the probe is one call, not a whole queue")
                .isEqualTo(1);

        // Closed again, so ordinary traffic flows and only real failures cost a budget.
        UUID recoveredFile = quarantine();
        assertThat(stateOf(scan(recoveredFile))).isEqualTo("CLEAN");
        assertThat(StubScannerSupport.callsFor(recoveredFile)).isEqualTo(1);
        assertThat(capacity.countFree()).isEqualTo(2);
    }

    private UUID quarantine() {
        ResponseEntity<QuarantineResponse> created = http.postForEntity("/api/v1/quarantine",
                new QuarantineRequest(UUID.randomUUID(), "CLEAN"), QuarantineResponse.class);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return created.getBody().fileId();
    }

    private ResponseEntity<QuarantineResponse> scan(UUID fileId) {
        UUID recordId = repository.findByFileId(fileId).orElseThrow().getId();
        return http.postForEntity("/api/v1/quarantine/" + recordId + "/scan", null,
                QuarantineResponse.class);
    }

    private String stateOf(ResponseEntity<QuarantineResponse> response) {
        assertThat(response.getBody()).isNotNull();
        return response.getBody().state();
    }
}

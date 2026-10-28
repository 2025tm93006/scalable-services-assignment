package com.bits.secure.quarantine;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The resilience and capacity behaviour, proved against a stub Scanner service over a
 * real HTTP socket on this application's own port. The budget here is deliberately
 * tiny (300 ms read timeout, three attempts, ~50 ms backoff) so that an outage costs
 * about a second instead of the fifteen a client would wait in production.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT, properties = {
        "spring.datasource.url=jdbc:h2:mem:quarantine-resilience-test;DB_CLOSE_DELAY=-1",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "server.port=18093",
        "scanner.base-url=http://localhost:18093",
        "scanner.connect-timeout-millis=200",
        "scanner.read-timeout-millis=300",
        "scanner.max-attempts=3",
        "scanner.retry-base-millis=50",
        "scanner.retry-jitter-millis=20",
        "quarantine.max-concurrent-scans=2"
})
class QuarantineScanResilienceTest {
    @Autowired
    private TestRestTemplate http;

    @Autowired
    private QuarantineRepository repository;

    @Autowired
    private ScanCapacityService capacity;

    @BeforeEach
    void clearRecords() {
        StubScannerSupport.reset(0, 0);
        repository.deleteAll();
    }

    @Test
    void retriesATransientScannerFailureAndScansTheFileExactlyOnce() {
        StubScannerSupport.reset(0, 2);
        UUID fileId = quarantine("CLEAN");

        QuarantineResponse settled = scan(createdId(fileId));

        assertThat(settled.state()).isEqualTo("CLEAN");
        assertThat(StubScannerSupport.callsFor(fileId)).isEqualTo(3);
        assertThat(repository.countByState(QuarantineState.CLEAN)).isEqualTo(1);
    }

    @Test
    void settlesTheFileAsFailedOnceTheRetryBudgetIsUsedUp() {
        StubScannerSupport.reset(0, 5);
        UUID fileId = quarantine("CLEAN");

        QuarantineResponse settled = scan(createdId(fileId));

        assertThat(settled.state()).isEqualTo("FAILED");
        assertThat(settled.scannedAt()).isNotNull();
        assertThat(StubScannerSupport.callsFor(fileId)).isEqualTo(3);
        assertThat(capacity.countFree()).isEqualTo(2);
    }

    @Test
    void treatsAScannerSlowerThanTheReadTimeoutAsAFailedAttemptAndGivesUpInTheBudget() {
        StubScannerSupport.reset(1500, 0);
        UUID fileId = quarantine("CLEAN");
        Instant before = Instant.now();

        QuarantineResponse settled = scan(createdId(fileId));

        Duration elapsed = Duration.between(before, Instant.now());
        assertThat(settled.state()).isEqualTo("FAILED");
        // Three attempts that were cut off after 300 ms plus the backoff between them.
        // Without the timeout the same call would have taken at least 4.5 seconds.
        assertThat(elapsed).isLessThan(Duration.ofSeconds(4));
        assertThat(capacity.countFree()).isEqualTo(2);
    }

    @Test
    void refusesTheThirdScanWhileBothCapacitySlotsAreHeld() {
        UUID heldOne = UUID.randomUUID();
        UUID heldTwo = UUID.randomUUID();
        assertThat(capacity.claim(heldOne)).isEqualTo(1);
        assertThat(capacity.claim(heldTwo)).isEqualTo(2);
        UUID fileId = quarantine("CLEAN");

        ResponseEntity<ApiErrorBody> refused = http.postForEntity(
                "/api/v1/quarantine/" + createdId(fileId) + "/scan", null, ApiErrorBody.class);

        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(refused.getBody().message()).isEqualTo("Scanner capacity exhausted");
        assertThat(repository.findByFileId(fileId).orElseThrow().getState())
                .isEqualTo(QuarantineState.QUARANTINED);

        capacity.release(1, heldOne);
        capacity.release(2, heldTwo);
        assertThat(scan(createdId(fileId)).state()).isEqualTo("CLEAN");
    }

    /**
     * Challenge 1 as an HTTP test: twenty files are scanned at the same instant against
     * a capacity of two. The invariant is not how many were accepted but that the
     * scanner was never addressed by more than two scans at a time.
     */
    @Test
    void twentyParallelScansNeverHoldMoreThanTwoScannerSlots() throws Exception {
        StubScannerSupport.reset(80, 0);
        List<UUID> ids = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            ids.add(createdId(quarantine("CLEAN")));
        }

        List<HttpStatus> statuses = runInParallel(ids);

        assertThat(statuses).hasSize(20);
        assertThat(statuses).allSatisfy(status ->
                assertThat(status).isIn(HttpStatus.OK, HttpStatus.CONFLICT));
        long accepted = statuses.stream().filter(HttpStatus.OK::equals).count();
        long rejected = statuses.stream().filter(HttpStatus.CONFLICT::equals).count();
        assertThat(accepted).isGreaterThanOrEqualTo(1);
        assertThat(rejected).isGreaterThanOrEqualTo(1);
        assertThat(StubScannerSupport.maxConcurrentCalls()).isLessThanOrEqualTo(2);
        assertThat(capacity.countHeld()).isZero();
        assertThat(capacity.countFree()).isEqualTo(2);
        assertThat(repository.countByState(QuarantineState.SCANNING)).isZero();
    }

    @Test
    void forwardsTheCallersCorrelationIdToTheScanner() {
        UUID fileId = quarantine("CLEAN");
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Correlation-Id", "cid-resilience-" + fileId);

        ResponseEntity<QuarantineResponse> scanned = http.exchange(
                "/api/v1/quarantine/" + createdId(fileId) + "/scan", HttpMethod.POST,
                new HttpEntity<>(headers), QuarantineResponse.class);

        assertThat(scanned.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(StubScannerSupport.lastCorrelationId()).isEqualTo("cid-resilience-" + fileId);
    }

    @Test
    void aFailedFileIsNotScannedAgainByASecondRequest() {
        StubScannerSupport.reset(0, 5);
        UUID id = createdId(quarantine("CLEAN"));

        assertThat(scan(id).state()).isEqualTo("FAILED");

        ResponseEntity<ApiErrorBody> repeat = http.postForEntity(
                "/api/v1/quarantine/" + id + "/scan", null, ApiErrorBody.class);
        assertThat(repeat.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(repeat.getBody().message()).isEqualTo("File has already been scanned");
    }

    private List<HttpStatus> runInParallel(List<UUID> ids) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(ids.size());
        try {
            List<Callable<HttpStatus>> calls = new ArrayList<>();
            for (UUID id : ids) {
                calls.add(() -> HttpStatus.valueOf(http.postForEntity(
                        "/api/v1/quarantine/" + id + "/scan", null, ApiErrorBody.class)
                        .getStatusCode().value()));
            }
            List<HttpStatus> statuses = new ArrayList<>();
            for (Future<HttpStatus> future : pool.invokeAll(calls)) {
                statuses.add(future.get(30, TimeUnit.SECONDS));
            }
            return statuses;
        } finally {
            pool.shutdownNow();
        }
    }

    private UUID quarantine(String scannerMode) {
        ResponseEntity<QuarantineResponse> created = http.postForEntity(
                "/api/v1/quarantine", new QuarantineRequest(UUID.randomUUID(), scannerMode),
                QuarantineResponse.class);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return created.getBody().fileId();
    }

    private UUID createdId(UUID fileId) {
        return repository.findByFileId(fileId).orElseThrow().getId();
    }

    private QuarantineResponse scan(UUID id) {
        ResponseEntity<QuarantineResponse> scanned = http.postForEntity(
                "/api/v1/quarantine/" + id + "/scan", null, QuarantineResponse.class);
        assertThat(scanned.getStatusCode()).isEqualTo(HttpStatus.OK);
        return scanned.getBody();
    }

    /** Only the message is needed from the shared error body. */
    record ApiErrorBody(int status, String message, String correlationId) {
    }
}

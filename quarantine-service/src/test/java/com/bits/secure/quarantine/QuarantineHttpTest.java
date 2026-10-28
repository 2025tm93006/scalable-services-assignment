package com.bits.secure.quarantine;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT, properties = {
        "spring.datasource.url=jdbc:h2:mem:quarantine-http-test;DB_CLOSE_DELAY=-1",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "server.port=18082",
        // The scan now calls Scanner over REST, so it is pointed at the stub controller.
        "scanner.base-url=http://localhost:18082",
        "scanner.max-attempts=1",
        "quarantine.max-concurrent-scans=2"
})
class QuarantineHttpTest {
    @Autowired
    private TestRestTemplate http;

    @Autowired
    private QuarantineRepository repository;

    @BeforeEach
    void clearRecords() {
        StubScannerSupport.reset(0, 0);
        repository.deleteAll();
    }

    @Test
    void createsScansFetchesAndSummarizesQuarantineRecord() {
        QuarantineRequest request = new QuarantineRequest(UUID.randomUUID(), "CLEAN");
        ResponseEntity<QuarantineResponse> created = http.postForEntity(
                "/api/v1/quarantine", request, QuarantineResponse.class);

        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(created.getBody()).isNotNull();
        assertThat(created.getBody().state()).isEqualTo("QUARANTINED");
        UUID id = created.getBody().id();

        ResponseEntity<QuarantineResponse> scanned = http.postForEntity(
                "/api/v1/quarantine/{id}/scan", null, QuarantineResponse.class, id);
        assertThat(scanned.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(scanned.getBody()).isNotNull();
        assertThat(scanned.getBody().state()).isEqualTo("CLEAN");
        assertThat(scanned.getBody().scannedAt()).isNotNull();

        ResponseEntity<QuarantineResponse> fetched = http.getForEntity(
                "/api/v1/quarantine/{id}", QuarantineResponse.class, id);
        assertThat(fetched.getBody()).isNotNull();
        assertThat(fetched.getBody().state()).isEqualTo("CLEAN");

        ResponseEntity<Map> evidence = http.getForEntity(
                "/api/v1/quarantine/_evidence/summary", Map.class);
        assertThat(evidence.getBody()).containsEntry("total", 1);
    }
}
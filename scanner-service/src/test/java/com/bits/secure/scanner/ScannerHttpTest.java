package com.bits.secure.scanner;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.ResourceAccessException;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:h2:mem:scanner-http-test;DB_CLOSE_DELAY=-1",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "scanner.slow-millis=1500",
        "scanner.in-flight-wait-millis=4000"
})
class ScannerHttpTest {
    private static final String ID = "7c1d2e3f-4a5b-4c6d-8e9f-0a1b2c3d4e5f";
    @Autowired private TestRestTemplate http;
    @Autowired private ObjectMapper mapper;
    @Autowired private ScanAttemptRepository repository;
    @LocalServerPort private int port;

    @BeforeEach
    void clearDatabase() {
        repository.deleteAll();
    }

    @Test
    void scanReplayAndReadAgreeWithOneStoredAttempt() throws Exception {
        ResponseEntity<String> first = post(payload("CLEAN"));
        ResponseEntity<String> replay = post(payload("CLEAN"));

        assertEquals(200, first.getStatusCode().value());
        assertEquals(200, replay.getStatusCode().value());
        assertEquals(first.getBody(), replay.getBody());
        assertEquals("scanner-test", first.getHeaders().getFirst("X-Correlation-Id"));

        JsonNode body = mapper.readTree(first.getBody());
        assertEquals(UUID.fromString(ID), UUID.fromString(body.get("fileId").asText()));
        assertEquals("CLEAN", body.get("result").asText());
        assertEquals("Deterministic mock result", body.get("message").asText());
        assertFalse(body.get("completedAt").asText().isBlank());

        ResponseEntity<String> read = http.getForEntity("/api/v1/scans/" + ID, String.class);
        assertEquals(200, read.getStatusCode().value());
        JsonNode attempt = mapper.readTree(read.getBody());
        assertEquals("CLEAN", attempt.get("mode").asText());
        assertEquals("CLEAN", attempt.get("result").asText());
        assertEquals(body.get("completedAt").asText(), attempt.get("finishedAt").asText());
        assertEquals(1, repository.count());
    }

    @ParameterizedTest
    @CsvSource({"INFECTED,INFECTED", "FAIL,FAILED"})
    void deterministicModesReturn200WithTheirResult(String mode, String expected) throws Exception {
        ResponseEntity<String> response = post(payload(mode));

        assertEquals(200, response.getStatusCode().value());
        assertEquals(expected, mapper.readTree(response.getBody()).get("result").asText());
        assertEquals(expected, repository.findByFileId(UUID.fromString(ID)).orElseThrow().getResult());
    }

    @Test
    void retryAfterClientReadTimeoutReplaysTheSingleInFlightScan() throws Exception {
        // The client gives up before the SLOW scan finishes, as Quarantine does when
        // its read timeout is shorter than the scan. The scan keeps running here.
        var impatient = new RestTemplateBuilder().rootUri("http://localhost:" + port)
                .setReadTimeout(Duration.ofMillis(300)).build();
        assertThrows(ResourceAccessException.class,
                () -> impatient.postForEntity("/api/v1/scans", json(payload("SLOW")), String.class));

        ResponseEntity<String> inFlight = http.getForEntity("/api/v1/scans/" + ID, String.class);
        assertTrue(mapper.readTree(inFlight.getBody()).get("result").isNull(), inFlight.getBody());

        ResponseEntity<String> retry = post(payload("SLOW"));
        assertEquals(200, retry.getStatusCode().value(), retry.getBody());
        assertEquals("CLEAN", mapper.readTree(retry.getBody()).get("result").asText());
        assertEquals(1, repository.count());

        JsonNode evidence = mapper.readTree(http.getForEntity("/api/v1/scans/_evidence/summary", String.class).getBody());
        assertEquals(1, evidence.get("totalAttempts").asLong());
        assertEquals(1, evidence.get("attemptsByResult").get("CLEAN").asLong());
        assertEquals(0, evidence.get("attemptsByResult").get("IN_PROGRESS").asLong());
    }

    @Test
    void eightSimultaneousSlowScansOfOneFileRunOnce() throws Exception {
        int count = 8;
        CyclicBarrier start = new CyclicBarrier(count);
        var pool = Executors.newFixedThreadPool(count);
        try {
            List<Future<ResponseEntity<String>>> calls = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                calls.add(pool.submit(() -> {
                    start.await(10, TimeUnit.SECONDS);
                    return post(payload("SLOW"));
                }));
            }
            List<ResponseEntity<String>> results = new ArrayList<>();
            for (var call : calls) results.add(call.get(20, TimeUnit.SECONDS));
            for (ResponseEntity<String> response : results) {
                assertEquals(200, response.getStatusCode().value(), response.getBody());
                assertEquals(results.get(0).getBody(), response.getBody());
            }
        } finally {
            pool.shutdown();
        }
        assertEquals(1, repository.count());
    }

    @Test
    void sameFileWithDifferentModeReturns409WithoutChangingTheResult() throws Exception {
        assertEquals(200, post(payload("CLEAN")).getStatusCode().value());

        assertError(post(payload("INFECTED")), 409);
        assertEquals("CLEAN", repository.findByFileId(UUID.fromString(ID)).orElseThrow().getResult());
        assertEquals(1, repository.count());
    }

    @ParameterizedTest
    @MethodSource("invalidBodies")
    void invalidInputReturnsConsistent400WithoutStoringAnything(String body) throws Exception {
        assertError(post(body), 400);
        assertEquals(0, repository.count());
    }

    static Stream<String> invalidBodies() {
        return Stream.of("{", "null", "",
                "{\"mode\":\"CLEAN\"}",
                payload("CLEAN").replace("\"" + ID + "\"", "null"),
                payload("CLEAN").replace(ID, "not-a-uuid"),
                "{\"fileId\":\"" + ID + "\"}",
                payload(" "), payload("clean"), payload("FAILED"), payload("RELEASED"));
    }

    @Test
    void unknownAttemptReturns404AndInvalidUuidReturns400() throws Exception {
        assertError(http.getForEntity("/api/v1/scans/" + UUID.randomUUID(), String.class), 404);
        assertError(http.getForEntity("/api/v1/scans/not-a-uuid", String.class), 400);
    }

    @Test
    void wrongMethodAndContentTypeKeepTheirHttpStatuses() throws Exception {
        assertError(http.exchange("/api/v1/scans", HttpMethod.PUT, json(payload("CLEAN")), String.class), 405);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.TEXT_PLAIN);
        assertError(http.postForEntity("/api/v1/scans", new HttpEntity<>(payload("CLEAN"), headers), String.class), 415);
    }

    @Test
    void healthStillWorks() throws Exception {
        ResponseEntity<String> response = http.getForEntity("/health", String.class);
        assertEquals(200, response.getStatusCode().value());
        assertEquals("UP", mapper.readTree(response.getBody()).get("status").asText());
        assertNotNull(response.getHeaders().getFirst("X-Correlation-Id"));
    }

    private ResponseEntity<String> post(String body) {
        return http.postForEntity("/api/v1/scans", json(body), String.class);
    }

    private static HttpEntity<String> json(String body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Correlation-Id", "scanner-test");
        return new HttpEntity<>(body, headers);
    }

    private void assertError(ResponseEntity<String> response, int expectedStatus) throws Exception {
        assertEquals(expectedStatus, response.getStatusCode().value(), response.getBody());
        JsonNode error = mapper.readTree(response.getBody());
        assertEquals(expectedStatus, error.get("status").asInt());
        for (String name : List.of("timestamp", "error", "message", "path", "correlationId", "fieldErrors")) {
            assertTrue(error.hasNonNull(name), name + " missing from " + error);
        }
        assertEquals(response.getHeaders().getFirst("X-Correlation-Id"), error.get("correlationId").asText());
    }

    private static String payload(String mode) {
        return "{\"fileId\":\"" + ID + "\",\"mode\":\"" + mode + "\"}";
    }
}

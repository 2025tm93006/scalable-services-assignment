package com.bits.secure.release;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

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
        "spring.datasource.url=jdbc:h2:mem:release-http-test;DB_CLOSE_DELAY=-1",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
class ReleaseHttpTest {
    private static final String ID = "3f2a4e6c-1b2d-4c5e-8f90-123456789abc";
    private static final String QUARANTINED_AT = "2026-10-24T08:00:00Z";
    @Autowired private TestRestTemplate http;
    @Autowired private ObjectMapper mapper;
    @Autowired private ReleaseRepository repository;

    @BeforeEach
    void clearDatabase() {
        repository.deleteAll();
    }

    @Test
    void releaseReadAndRepeatAgreeWithOneStoredRecord() throws Exception {
        ResponseEntity<String> first = post(payload("CLEAN"));
        ResponseEntity<String> replay = post(payload("CLEAN"));

        assertEquals(201, first.getStatusCode().value());
        assertEquals(201, replay.getStatusCode().value());
        assertEquals(first.getBody(), replay.getBody());
        assertEquals("release-test", first.getHeaders().getFirst("X-Correlation-Id"));

        JsonNode body = mapper.readTree(first.getBody());
        assertEquals(UUID.fromString(ID), UUID.fromString(body.get("fileId").asText()));
        assertEquals("RELEASED", body.get("state").asText());
        assertEquals(QUARANTINED_AT, body.get("quarantinedAt").asText());
        assertFalse(body.get("releasedAt").asText().isBlank());

        ResponseEntity<String> read = http.getForEntity("/api/v1/releases/" + ID, String.class);
        assertEquals(200, read.getStatusCode().value());
        assertEquals(first.getBody(), read.getBody());
        assertEquals(1, repository.count());
    }

    @ParameterizedTest
    @MethodSource("unreleasableBodies")
    void unreleasableRequestsReturn409WithoutStoringAnything(String body) throws Exception {
        assertError(post(body), 409);
        assertEquals(0, repository.count());
    }

    static Stream<String> unreleasableBodies() {
        return Stream.of(payload("INFECTED"), payload("FAILED"), payload("QUARANTINED"), payload("clean"),
                payload("CLEAN").replace("\"" + QUARANTINED_AT + "\"", "null"),
                payloadWithoutTimestamp("CLEAN"));
    }

    @ParameterizedTest
    @MethodSource("invalidBodies")
    void invalidInputReturnsConsistent400WithoutStoringAnything(String body) throws Exception {
        assertError(post(body), 400);
        assertEquals(0, repository.count());
    }

    static Stream<String> invalidBodies() {
        return Stream.of("{", "null", "",
                payload("CLEAN").replace("\"fileId\":\"" + ID + "\",", ""),
                payload("CLEAN").replace("\"fileId\":\"" + ID + "\"", "\"fileId\":null"),
                payload("CLEAN").replace("\"fileId\":\"" + ID + "\"", "\"fileId\":\"not-a-uuid\""),
                payload("CLEAN").replace("\"CLEAN\"", "\" \""),
                payload("CLEAN").replace("\"CLEAN\"", "\"" + "C".repeat(33) + "\""),
                payload("CLEAN").replace(QUARANTINED_AT, "not-a-timestamp"),
                payloadWithoutState());
    }

    @Test
    void unknownReleaseReturns404AndInvalidUuidReturns400() throws Exception {
        assertError(http.getForEntity("/api/v1/releases/" + UUID.randomUUID(), String.class), 404);
        assertError(http.getForEntity("/api/v1/releases/not-a-uuid", String.class), 400);
    }

    @Test
    void wrongMethodAndContentTypeKeepTheirHttpStatuses() throws Exception {
        assertError(http.exchange("/api/v1/releases", HttpMethod.PUT,
                new HttpEntity<>(payload("CLEAN")), String.class), 405);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.TEXT_PLAIN);
        assertError(http.postForEntity("/api/v1/releases",
                new HttpEntity<>(payload("CLEAN"), headers), String.class), 415);
    }

    @Test
    void eightSimultaneousRequestsReleaseOnce() throws Exception {
        int count = 8;
        CyclicBarrier start = new CyclicBarrier(count);
        try (var pool = Executors.newFixedThreadPool(count)) {
            List<Future<ResponseEntity<String>>> calls = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                calls.add(pool.submit(() -> {
                    start.await(10, TimeUnit.SECONDS);
                    return post(payload("CLEAN"));
                }));
            }
            List<ResponseEntity<String>> results = new ArrayList<>();
            for (var call : calls) results.add(call.get(20, TimeUnit.SECONDS));
            for (ResponseEntity<String> response : results) {
                assertEquals(201, response.getStatusCode().value(), response.getBody());
                assertEquals(results.getFirst().getBody(), response.getBody());
            }
        }
        assertEquals(1, repository.count());
    }

    @Test
    void healthStillWorks() throws Exception {
        ResponseEntity<String> response = http.getForEntity("/health", String.class);
        assertEquals(200, response.getStatusCode().value());
        assertEquals("UP", mapper.readTree(response.getBody()).get("status").asText());
        assertNotNull(response.getHeaders().getFirst("X-Correlation-Id"));
    }

    private ResponseEntity<String> post(String body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Correlation-Id", "release-test");
        return http.postForEntity("/api/v1/releases", new HttpEntity<>(body, headers), String.class);
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

    private static String payload(String quarantineState) {
        return "{\"fileId\":\"" + ID + "\",\"quarantineState\":\"" + quarantineState
                + "\",\"quarantinedAt\":\"" + QUARANTINED_AT + "\"}";
    }

    private static String payloadWithoutTimestamp(String quarantineState) {
        return "{\"fileId\":\"" + ID + "\",\"quarantineState\":\"" + quarantineState + "\"}";
    }

    private static String payloadWithoutState() {
        return "{\"fileId\":\"" + ID + "\",\"quarantinedAt\":\"" + QUARANTINED_AT + "\"}";
    }
}

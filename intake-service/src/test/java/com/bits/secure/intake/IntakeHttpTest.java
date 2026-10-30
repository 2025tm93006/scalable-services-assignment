package com.bits.secure.intake;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
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
        "spring.datasource.url=jdbc:h2:mem:intake-http-test;DB_CLOSE_DELAY=-1",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
class IntakeHttpTest {
    @Autowired private TestRestTemplate http;
    @Autowired private ObjectMapper mapper;
    @Autowired private FileRepository repository;

    @BeforeEach
    void clearDatabase() {
        repository.deleteAll();
    }

    @Test
    void createReplayReadAndEvidenceAgreeWithTheStoredResponse() throws Exception {
        ResponseEntity<String> first = post("same-key", payload());
        ResponseEntity<String> replay = post("same-key", payload());
        assertEquals(201, first.getStatusCode().value());
        assertEquals(201, replay.getStatusCode().value());
        assertEquals(first.getBody(), replay.getBody());
        assertEquals("intake-test", first.getHeaders().getFirst("X-Correlation-Id"));
        JsonNode body = mapper.readTree(first.getBody());
        assertEquals("RECEIVED", body.get("status").asText());
        assertEquals(first.getBody(), http.getForEntity("/api/v1/files/" + body.get("fileId").asText(), String.class).getBody());
        FileRecord stored = repository.findByIdempotencyKey("same-key").orElseThrow();
        assertEquals(mapper.readTree(stored.getResponseBody()), body);
        assertEquals(64, stored.getFingerprint().length());
        JsonNode evidence = mapper.readTree(http.getForEntity(
                "/api/v1/files/_evidence/idempotency/same-key", String.class).getBody());
        assertEquals(1, evidence.get("databaseRecordCount").asInt());
        assertEquals(body.get("fileId"), evidence.get("fileId"));
    }

    @Test
    void jsonPropertyOrderAndWhitespaceDoNotChangeTheFingerprint() throws Exception {
        String original = payload();
        JsonNode p = mapper.readTree(original);
        String reordered = "{\n\"scannerMode\":\"CLEAN\",\"sha256\":\"" + p.get("sha256").asText()
                + "\",\"sizeBytes\":4,\"contentType\":\"text/plain\",\"filename\":\"demo.txt\"}";
        assertEquals(post("order-key", original).getBody(), post("order-key", reordered).getBody());
        assertEquals(1, repository.count());
    }

    @Test
    void differentKeysCreateDifferentOperationsEvenForTheSameMetadata() throws Exception {
        JsonNode first = mapper.readTree(post("operation-one", payload()).getBody());
        JsonNode second = mapper.readTree(post("operation-two", payload()).getBody());
        assertNotEquals(first.get("fileId"), second.get("fileId"));
        assertEquals(2, repository.count());
    }

    @Test
    void differentPayloadReturns409AndDoesNotChangeTheOriginal() throws Exception {
        ResponseEntity<String> original = post("conflict-key", payload());
        assertError(post("conflict-key", payload().replace("demo.txt", "changed.txt")), 409);
        assertEquals(original.getBody(), post("conflict-key", payload()).getBody());
        assertEquals(1, repository.count());
    }

    @ParameterizedTest(name = "changing {0} conflicts without changing the original")
    @MethodSource("metadataChanges")
    void changingAnyMetadataFieldReturns409AndPreservesTheOriginal(String field, String changedValue)
            throws Exception {
        String key = "field-conflict-key";
        ResponseEntity<String> original = post(key, payload());
        assertEquals(201, original.getStatusCode().value());
        FileRecord stored = repository.findByIdempotencyKey(key).orElseThrow();
        UUID originalId = stored.getId();
        String originalFingerprint = stored.getFingerprint();
        String originalResponse = stored.getResponseBody();

        ObjectNode changed = (ObjectNode) mapper.readTree(payload());
        changed.set(field, mapper.readTree(changedValue));
        ResponseEntity<String> conflict = post(key, changed.toString());
        assertError(conflict, 409);
        assertEquals("Idempotency-Key was used with a different payload",
                mapper.readTree(conflict.getBody()).get("message").asText());

        FileRecord afterConflict = repository.findByIdempotencyKey(key).orElseThrow();
        assertEquals(originalId, afterConflict.getId());
        assertEquals(originalFingerprint, afterConflict.getFingerprint());
        assertEquals(originalResponse, afterConflict.getResponseBody());
        ResponseEntity<String> replay = post(key, payload());
        assertEquals(201, replay.getStatusCode().value());
        assertEquals(original.getBody(), replay.getBody());
        assertEquals(1, repository.countByIdempotencyKey(key));
        assertEquals(1, repository.count());
    }

    static Stream<Arguments> metadataChanges() {
        return Stream.of(
                Arguments.of("filename", "\"changed.txt\""),
                Arguments.of("contentType", "\"application/octet-stream\""),
                Arguments.of("sizeBytes", "5"),
                Arguments.of("sha256", "\"" + "b".repeat(64) + "\""),
                Arguments.of("scannerMode", "\"INFECTED\""));
    }

    @Test
    void invalidRequestDoesNotConsumeKeyAndCorrectedRequestCanBeReplayed() throws Exception {
        String key = "corrected-request-key";
        assertError(post(key, payload().replace("\"sizeBytes\":4", "\"sizeBytes\":-1")), 400);
        assertTrue(repository.findByIdempotencyKey(key).isEmpty());
        assertEquals(0, repository.count());

        ResponseEntity<String> corrected = post(key, payload());
        assertEquals(201, corrected.getStatusCode().value());
        JsonNode response = mapper.readTree(corrected.getBody());
        assertEquals("RECEIVED", response.get("status").asText());
        FileRecord stored = repository.findByIdempotencyKey(key).orElseThrow();
        assertEquals(stored.getId().toString(), response.get("fileId").asText());
        assertEquals(response, mapper.readTree(stored.getResponseBody()));

        ResponseEntity<String> replay = post(key, payload());
        assertEquals(201, replay.getStatusCode().value());
        assertEquals(corrected.getBody(), replay.getBody());
        assertEquals(1, repository.countByIdempotencyKey(key));
        assertEquals(1, repository.count());
    }

    @Test
    void eightSimultaneousSameKeyRequestsCreateOneDatabaseRow() throws Exception {
        List<ResponseEntity<String>> results = parallelRequests(false);
        for (ResponseEntity<String> response : results) {
            assertEquals(201, response.getStatusCode().value());
            assertEquals(results.get(0).getBody(), response.getBody());
        }
        assertEquals(1, repository.countByIdempotencyKey("parallel-key"));
    }

    @Test
    void simultaneousDifferentPayloadsHaveOneWinnerAndOneConflict() throws Exception {
        List<ResponseEntity<String>> results = parallelRequests(true);
        assertEquals(List.of(201, 409), results.stream().map(r -> r.getStatusCode().value()).sorted().toList());
        assertEquals(1, repository.countByIdempotencyKey("parallel-key"));
    }

    @ParameterizedTest
    @MethodSource("invalidBodies")
    void invalidInputReturnsConsistent400WithoutCreatingARecord(String body) throws Exception {
        assertError(post("invalid-key", body), 400);
        assertEquals(0, repository.count());
    }

    static Stream<String> invalidBodies() {
        String body = payload();
        return Stream.of("{", "null", body.replace("demo.txt", " "),
                body.replace("demo.txt", "x".repeat(256)),
                body.replace("text/plain", "x".repeat(256)),
                body.replace("\"sizeBytes\":4", "\"sizeBytes\":-1"),
                body.replace("\"sizeBytes\":4", "\"sizeBytes\":1.5"),
                body.replace("\"sizeBytes\":4,", ""),
                body.replace("\"sizeBytes\":4", "\"sizeBytes\":null"),
                body.replace("\"sizeBytes\":4", "\"sizeBytes\":\"wrong\""),
                body.replace("a".repeat(64), "bad-hash"),
                body.replace("CLEAN", "UNKNOWN"));
    }

    @Test
    void missingBlankOrOversizedKeyReturns400() throws Exception {
        assertError(post(null, payload()), 400);
        assertError(post(" ", payload()), 400);
        assertError(post("k".repeat(256), payload()), 400);
        assertEquals(0, repository.count());
    }

    @Test
    void zeroByteFileIsValid() {
        assertEquals(201, post("empty-file", payload().replace("\"sizeBytes\":4", "\"sizeBytes\":0")).getStatusCode().value());
    }

    @Test
    void unknownFileReturns404AndInvalidUuidReturns400() throws Exception {
        assertError(http.getForEntity("/api/v1/files/" + UUID.randomUUID(), String.class), 404);
        assertError(http.getForEntity("/api/v1/files/not-a-uuid", String.class), 400);
    }

    @Test
    void wrongMethodAndContentTypeKeepTheirHttpStatuses() throws Exception {
        assertError(http.exchange("/api/v1/files", HttpMethod.PUT, new HttpEntity<>(payload()), String.class), 405);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.TEXT_PLAIN);
        headers.set("Idempotency-Key", "wrong-media-type");
        assertError(http.postForEntity("/api/v1/files", new HttpEntity<>(payload(), headers), String.class), 415);
    }

    @Test
    void healthStillWorks() throws Exception {
        ResponseEntity<String> response = http.getForEntity("/health", String.class);
        assertEquals(200, response.getStatusCode().value());
        assertEquals("UP", mapper.readTree(response.getBody()).get("status").asText());
        assertNotNull(response.getHeaders().getFirst("X-Correlation-Id"));
    }

    private List<ResponseEntity<String>> parallelRequests(boolean differentPayloads) throws Exception {
        int count = differentPayloads ? 2 : 8;
        CyclicBarrier start = new CyclicBarrier(count);
        var pool = Executors.newFixedThreadPool(count);
        try {
            List<Future<ResponseEntity<String>>> calls = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                String body = differentPayloads && i == 1 ? payload().replace("demo.txt", "other.txt") : payload();
                calls.add(pool.submit(() -> {
                    start.await(10, TimeUnit.SECONDS);
                    return post("parallel-key", body);
                }));
            }
            List<ResponseEntity<String>> responses = new ArrayList<>();
            for (var call : calls) responses.add(call.get(20, TimeUnit.SECONDS));
            return responses;
        } finally {
            pool.shutdown();
        }
    }

    private ResponseEntity<String> post(String key, String body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Correlation-Id", "intake-test");
        if (key != null) headers.set("Idempotency-Key", key);
        return http.postForEntity("/api/v1/files", new HttpEntity<>(body, headers), String.class);
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

    private static String payload() {
        return "{\"filename\":\"demo.txt\",\"contentType\":\"text/plain\",\"sizeBytes\":4,\"sha256\":\""
                + "a".repeat(64) + "\",\"scannerMode\":\"CLEAN\"}";
    }
}

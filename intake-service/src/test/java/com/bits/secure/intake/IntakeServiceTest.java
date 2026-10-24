package com.bits.secure.intake;

import com.bits.secure.common.ConflictException;
import com.bits.secure.common.NotFoundException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class IntakeServiceTest {
    @Mock private FileRepository repository;
    @Mock private IntakeRecordWriter writer;
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final IntakeRequest request = new IntakeRequest("demo.txt", "text/plain", 4L, "a".repeat(64), "CLEAN");
    private IntakeService service;

    @BeforeEach
    void setUp() {
        service = new IntakeService(repository, writer, mapper);
    }

    @Test
    void firstRequestStoresKeyFingerprintAndResponseTogether() throws Exception {
        when(repository.findByIdempotencyKey("key")).thenReturn(Optional.empty());
        when(writer.create(any())).thenAnswer(invocation -> invocation.getArgument(0));

        IntakeResponse response = service.receive("key", request);

        assertEquals("RECEIVED", response.status());
        assertNotNull(response.fileId());
        ArgumentCaptor<FileRecord> saved = ArgumentCaptor.forClass(FileRecord.class);
        verify(writer).create(saved.capture());
        assertEquals(service.fingerprint(request), saved.getValue().getFingerprint());
        assertEquals(response, mapper.readValue(saved.getValue().getResponseBody(), IntakeResponse.class));
    }

    @Test
    void sameKeyAndPayloadReplaysTheEntireOriginalResponse() throws Exception {
        FileRecord original = record(request);
        when(repository.findByIdempotencyKey("key")).thenReturn(Optional.of(original));

        assertEquals(mapper.readValue(original.getResponseBody(), IntakeResponse.class), service.receive("key", request));
        verifyNoInteractions(writer);
    }

    @Test
    void sameKeyWithDifferentPayloadIsConflict() throws Exception {
        when(repository.findByIdempotencyKey("key")).thenReturn(Optional.of(record(request)));
        IntakeRequest changed = new IntakeRequest("different.txt", "text/plain", 4L, "a".repeat(64), "CLEAN");

        assertThrows(ConflictException.class, () -> service.receive("key", changed));
        verifyNoInteractions(writer);
    }

    @Test
    void uniqueKeyRaceReplaysCommittedWinnerAfterWriterRollback() throws Exception {
        FileRecord winner = record(request);
        when(repository.findByIdempotencyKey("key")).thenReturn(Optional.empty(), Optional.of(winner));
        when(writer.create(any())).thenThrow(new DataIntegrityViolationException("unique key"));

        assertEquals(winner.getId(), service.receive("key", request).fileId());
    }

    @Test
    void uniqueKeyRaceWithDifferentPayloadIsConflict() throws Exception {
        IntakeRequest changed = new IntakeRequest("different.txt", "text/plain", 4L, "a".repeat(64), "CLEAN");
        when(repository.findByIdempotencyKey("key")).thenReturn(Optional.empty(), Optional.of(record(changed)));
        when(writer.create(any())).thenThrow(new DataIntegrityViolationException("unique key"));

        assertThrows(ConflictException.class, () -> service.receive("key", request));
    }

    @Test
    void unrelatedDatabaseFailureIsNotTreatedAsSuccessfulReplay() {
        when(repository.findByIdempotencyKey("key")).thenReturn(Optional.empty());
        DataIntegrityViolationException failure = new DataIntegrityViolationException("other constraint");
        when(writer.create(any())).thenThrow(failure);

        assertSame(failure, assertThrows(DataIntegrityViolationException.class, () -> service.receive("key", request)));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t"})
    void blankKeyIsRejected(String key) {
        assertThrows(IllegalArgumentException.class, () -> service.receive(key, request));
        verifyNoInteractions(repository, writer);
    }

    @Test
    void keyLongerThanDatabaseColumnIsRejectedBeforeWriting() {
        assertThrows(IllegalArgumentException.class, () -> service.receive("k".repeat(256), request));
        verifyNoInteractions(repository, writer);
    }

    @Test
    void fingerprintCannotConfuseFieldsContainingTheOldDelimiter() {
        var first = new IntakeRequest("a|b", "c", 4L, "a".repeat(64), "CLEAN");
        var second = new IntakeRequest("a", "b|c", 4L, "a".repeat(64), "CLEAN");
        assertNotEquals(service.fingerprint(first), service.fingerprint(second));
    }

    @Test
    void unknownFileIsNotFound() {
        UUID id = UUID.randomUUID();
        when(repository.findById(id)).thenReturn(Optional.empty());
        assertThrows(NotFoundException.class, () -> service.find(id));
    }

    private FileRecord record(IntakeRequest body) throws Exception {
        var response = new IntakeResponse(UUID.randomUUID(), "RECEIVED", body.filename(), body.scannerMode(),
                Instant.parse("2026-10-24T00:00:00.123456789Z"));
        return new FileRecord("key", service.fingerprint(body), body, response, mapper.writeValueAsString(response));
    }
}

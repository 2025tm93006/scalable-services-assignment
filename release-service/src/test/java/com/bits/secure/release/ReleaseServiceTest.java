package com.bits.secure.release;

import com.bits.secure.common.ConflictException;
import com.bits.secure.common.NotFoundException;
import com.bits.secure.common.ServiceUnavailableException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
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
class ReleaseServiceTest {
    private static final Instant QUARANTINED_AT = Instant.parse("2026-10-24T08:00:00Z");
    private static final UUID QUARANTINE_RECORD_ID = UUID.randomUUID();
    @Mock private ReleaseRepository repository;
    @Mock private QuarantineClient quarantine;
    private final UUID fileId = UUID.randomUUID();
    private ReleaseService service;

    @BeforeEach
    void setUp() {
        // The real verifier over a mocked client: these tests are about the decision order,
        // and the verifier's own verdicts are covered in QuarantineStateVerifierTest.
        service = new ReleaseService(repository, new ReleaseRecordWriter(repository),
                new QuarantineStateVerifier(quarantine));
        // Lenient because most of these cases are refused before Release asks Quarantine;
        // the tests that do ask either override this or assert that they never get here.
        lenient().when(quarantine.findForFile(fileId))
                .thenReturn(Optional.of(quarantineRecord("CLEAN", QUARANTINED_AT)));
    }

    @Test
    void cleanAfterQuarantineReleases() {
        when(repository.findById(fileId)).thenReturn(Optional.empty());
        when(repository.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0, ReleaseRecord.class));

        ReleaseResponse response = service.release(clean());

        assertEquals(fileId, response.fileId());
        assertEquals("RELEASED", response.state());
        assertEquals(QUARANTINED_AT, response.quarantinedAt());
        assertNotNull(response.releasedAt());
        ArgumentCaptor<ReleaseRecord> saved = ArgumentCaptor.forClass(ReleaseRecord.class);
        verify(repository).saveAndFlush(saved.capture());
        assertEquals(ReleaseRecord.RELEASED, saved.getValue().getState());
        assertEquals(QUARANTINED_AT, saved.getValue().getQuarantinedAt());
        verify(quarantine).findForFile(fileId);
    }

    @Test
    void alreadyReleasedFileReplaysTheOriginalRecordWithoutWriting() {
        ReleaseRecord original = new ReleaseRecord(fileId, QUARANTINED_AT, Instant.parse("2026-10-24T09:00:00Z"));
        when(repository.findById(fileId)).thenReturn(Optional.of(original));

        ReleaseResponse response = service.release(clean());

        assertEquals(original.getReleasedAt(), response.releasedAt());
        assertEquals(QUARANTINED_AT, response.quarantinedAt());
        verify(repository, never()).saveAndFlush(any());
        // A finished release is answered from Release's own table: no outbound call, so a
        // Quarantine outage can never make an already released file un-releasable.
        verify(quarantine, never()).findForFile(any());
    }

    @Test
    void infectedCannotRelease() {
        when(repository.findById(fileId)).thenReturn(Optional.empty());

        assertThrows(ConflictException.class,
                () -> service.release(new ReleaseRequest(fileId, "INFECTED", QUARANTINED_AT)));
        verify(repository, never()).saveAndFlush(any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"FAILED", "QUARANTINED", "SCANNING", "clean", "CLEAN "})
    void onlyTheCleanStateCanBeReleased(String state) {
        when(repository.findById(fileId)).thenReturn(Optional.empty());

        assertThrows(ConflictException.class,
                () -> service.release(new ReleaseRequest(fileId, state, QUARANTINED_AT)));
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void cleanFileWithoutQuarantineTimestampCannotBeReleased() {
        when(repository.findById(fileId)).thenReturn(Optional.empty());

        ConflictException failure = assertThrows(ConflictException.class,
                () -> service.release(new ReleaseRequest(fileId, "CLEAN", null)));

        assertTrue(failure.getMessage().contains("quarantined"));
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void simultaneousReleaseRaceReplaysTheCommittedWinner() {
        ReleaseRecord winner = new ReleaseRecord(fileId, QUARANTINED_AT, Instant.parse("2026-10-24T09:00:00Z"));
        when(repository.findById(fileId)).thenReturn(Optional.empty(), Optional.of(winner));
        when(repository.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException("duplicate primary key"));

        assertEquals(winner.getReleasedAt(), service.release(clean()).releasedAt());
    }

    @Test
    void unrelatedDatabaseFailureIsNotTreatedAsSuccessfulReplay() {
        when(repository.findById(fileId)).thenReturn(Optional.empty());
        DataIntegrityViolationException failure = new DataIntegrityViolationException("other constraint");
        when(repository.saveAndFlush(any())).thenThrow(failure);

        assertSame(failure, assertThrows(DataIntegrityViolationException.class, () -> service.release(clean())));
    }

    @Test
    void unknownFileIsNotFound() {
        UUID unknown = UUID.randomUUID();
        when(repository.findById(unknown)).thenReturn(Optional.empty());

        assertThrows(NotFoundException.class, () -> service.find(unknown));
    }

    @Test
    void aClientCannotReleaseAFileQuarantineReportsAsInfected() {
        when(repository.findById(fileId)).thenReturn(Optional.empty());
        when(quarantine.findForFile(fileId))
                .thenReturn(Optional.of(quarantineRecord("INFECTED", QUARANTINED_AT)));

        ConflictException refusal = assertThrows(ConflictException.class, () -> service.release(clean()));

        assertTrue(refusal.getMessage().contains("INFECTED"), refusal.getMessage());
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void aFileThatWasNeverQuarantinedCannotBeReleased() {
        when(repository.findById(fileId)).thenReturn(Optional.empty());
        when(quarantine.findForFile(fileId)).thenReturn(Optional.empty());

        ConflictException refusal = assertThrows(ConflictException.class, () -> service.release(clean()));

        assertEquals("No quarantine record for this file", refusal.getMessage());
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void aStaleQuarantineTimestampDoesNotMatchTheRecord() {
        when(repository.findById(fileId)).thenReturn(Optional.empty());
        when(quarantine.findForFile(fileId)).thenReturn(Optional.of(
                quarantineRecord("CLEAN", Instant.parse("2026-10-25T08:00:00Z"))));

        ConflictException refusal = assertThrows(ConflictException.class, () -> service.release(clean()));

        assertTrue(refusal.getMessage().contains("does not match"), refusal.getMessage());
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void aTimestampThatDiffersOnlyByStoredPrecisionIsStillTheSameInstant() {
        when(repository.findById(fileId)).thenReturn(Optional.empty());
        when(repository.saveAndFlush(any())).thenAnswer(call -> call.getArgument(0, ReleaseRecord.class));
        // A database column keeps microseconds; a client that echoes the create response
        // can therefore be a few hundred nanoseconds off a fresh read. That is rounding.
        when(quarantine.findForFile(fileId)).thenReturn(Optional.of(
                quarantineRecord("CLEAN", QUARANTINED_AT.plusNanos(700))));

        assertEquals("RELEASED", service.release(clean()).state());
    }

    @Test
    void aTimestampOneMillisecondApartIsNotTheSameQuarantine() {
        when(repository.findById(fileId)).thenReturn(Optional.empty());
        when(quarantine.findForFile(fileId)).thenReturn(Optional.of(
                quarantineRecord("CLEAN", QUARANTINED_AT.plusMillis(1))));

        assertThrows(ConflictException.class, () -> service.release(clean()));
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void anUnreachableQuarantineRefusesTheReleaseInsteadOfTrustingTheCaller() {
        when(repository.findById(fileId)).thenReturn(Optional.empty());
        when(quarantine.findForFile(fileId)).thenThrow(
                new QuarantineClient.QuarantineUnreachableException(
                        "Quarantine did not answer after 3 attempts", null));

        assertThrows(ServiceUnavailableException.class, () -> service.release(clean()));
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void aScanStillInFlightIsNotAReleaseableCleanFile() {
        when(repository.findById(fileId)).thenReturn(Optional.empty());
        when(quarantine.findForFile(fileId))
                .thenReturn(Optional.of(quarantineRecord("SCANNING", QUARANTINED_AT)));

        assertThrows(ConflictException.class, () -> service.release(clean()));
        verify(repository, never()).saveAndFlush(any());
    }

    private ReleaseRequest clean() {
        return new ReleaseRequest(fileId, "CLEAN", QUARANTINED_AT);
    }

    private QuarantineClient.QuarantineView quarantineRecord(String state, Instant quarantinedAt) {
        return new QuarantineClient.QuarantineView(QUARANTINE_RECORD_ID, fileId, state, "CLEAN",
                quarantinedAt, quarantinedAt == null ? null : quarantinedAt.plusSeconds(30));
    }
}

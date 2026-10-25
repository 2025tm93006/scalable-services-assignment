package com.bits.secure.release;

import com.bits.secure.common.ConflictException;
import com.bits.secure.common.NotFoundException;
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
    @Mock private ReleaseRepository repository;
    private final UUID fileId = UUID.randomUUID();
    private ReleaseService service;

    @BeforeEach
    void setUp() {
        service = new ReleaseService(repository, new ReleaseRecordWriter(repository));
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
    }

    @Test
    void alreadyReleasedFileReplaysTheOriginalRecordWithoutWriting() {
        ReleaseRecord original = new ReleaseRecord(fileId, QUARANTINED_AT, Instant.parse("2026-10-24T09:00:00Z"));
        when(repository.findById(fileId)).thenReturn(Optional.of(original));

        ReleaseResponse response = service.release(clean());

        assertEquals(original.getReleasedAt(), response.releasedAt());
        assertEquals(QUARANTINED_AT, response.quarantinedAt());
        verify(repository, never()).saveAndFlush(any());
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

    private ReleaseRequest clean() {
        return new ReleaseRequest(fileId, "CLEAN", QUARANTINED_AT);
    }
}

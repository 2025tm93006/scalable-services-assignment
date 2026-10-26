package com.bits.secure.scanner;

import com.bits.secure.common.ConflictException;
import com.bits.secure.common.NotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
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
class ScannerServiceTest {
    private static final Instant STARTED_AT = Instant.parse("2026-10-25T08:00:00Z");
    private static final Instant FINISHED_AT = Instant.parse("2026-10-25T08:00:02Z");
    @Mock private ScanAttemptRepository repository;
    private final UUID fileId = UUID.randomUUID();
    private ScannerService service;

    @BeforeEach
    void setUp() {
        service = service(1000);
    }

    @ParameterizedTest
    @CsvSource({"CLEAN,CLEAN", "INFECTED,INFECTED", "FAIL,FAILED", "SLOW,CLEAN"})
    void firstScanStoresOneDeterministicResult(String mode, String expected) {
        when(repository.findByFileId(fileId)).thenReturn(Optional.empty());
        when(repository.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0, ScanAttempt.class));

        ScanResponse response = service.scan(new ScanRequest(fileId, mode));

        assertEquals(fileId, response.fileId());
        assertEquals(expected, response.result());
        assertNotNull(response.completedAt());
        ArgumentCaptor<ScanAttempt> saved = ArgumentCaptor.forClass(ScanAttempt.class);
        // One claim of the in-progress row, then one write of its result.
        verify(repository, times(2)).saveAndFlush(saved.capture());
        assertSame(saved.getAllValues().get(0), saved.getAllValues().get(1));
        assertEquals(mode, saved.getValue().getMode());
        assertEquals(expected, saved.getValue().getResult());
    }

    @Test
    void failModeExplainsTheDeterministicFailure() {
        when(repository.findByFileId(fileId)).thenReturn(Optional.empty());
        when(repository.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0, ScanAttempt.class));

        assertEquals("Deterministic scanner failure", service.scan(new ScanRequest(fileId, "FAIL")).message());
    }

    @Test
    void repeatedFileIdReplaysCompletedAttemptWithoutSavingAnotherRow() {
        when(repository.findByFileId(fileId)).thenReturn(Optional.of(finished("CLEAN", "CLEAN")));

        ScanResponse response = service.scan(new ScanRequest(fileId, "CLEAN"));

        assertEquals("CLEAN", response.result());
        assertEquals(FINISHED_AT, response.completedAt());
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void sameFileWithDifferentModeIsConflict() {
        when(repository.findByFileId(fileId)).thenReturn(Optional.of(finished("CLEAN", "CLEAN")));

        assertThrows(ConflictException.class, () -> service.scan(new ScanRequest(fileId, "INFECTED")));
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void retryDuringAnInProgressScanWaitsAndReplaysItsResult() {
        when(repository.findByFileId(fileId)).thenReturn(
                Optional.of(inProgress("SLOW")), Optional.of(inProgress("SLOW")),
                Optional.of(finished("SLOW", "CLEAN")));

        ScanResponse response = service.scan(new ScanRequest(fileId, "SLOW"));

        assertEquals("CLEAN", response.result());
        assertEquals(FINISHED_AT, response.completedAt());
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void scanStillInProgressAfterTheWaitIsConflictWithoutStartingAnotherScan() {
        service = service(0);
        when(repository.findByFileId(fileId)).thenReturn(Optional.of(inProgress("SLOW")));

        ConflictException failure = assertThrows(ConflictException.class,
                () -> service.scan(new ScanRequest(fileId, "SLOW")));

        assertTrue(failure.getMessage().contains("in progress"));
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void simultaneousFirstScanRaceFollowsTheCommittedWinner() {
        when(repository.findByFileId(fileId)).thenReturn(Optional.empty(), Optional.of(finished("CLEAN", "CLEAN")));
        when(repository.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException("duplicate file_id"));

        ScanResponse response = service.scan(new ScanRequest(fileId, "CLEAN"));

        assertEquals("CLEAN", response.result());
        assertEquals(FINISHED_AT, response.completedAt());
        verify(repository, times(1)).saveAndFlush(any());
    }

    @Test
    void unrelatedDatabaseFailureIsNotTreatedAsReplay() {
        when(repository.findByFileId(fileId)).thenReturn(Optional.empty());
        DataIntegrityViolationException failure = new DataIntegrityViolationException("other constraint");
        when(repository.saveAndFlush(any())).thenThrow(failure);

        assertSame(failure, assertThrows(DataIntegrityViolationException.class,
                () -> service.scan(new ScanRequest(fileId, "CLEAN"))));
    }

    @Test
    void interruptedSlowScanIsStoredAsFailedAndKeepsTheInterrupt() {
        service = new ScannerService(repository, new ScanAttemptWriter(repository), 60_000, 1000, 1);
        when(repository.findByFileId(fileId)).thenReturn(Optional.empty());
        when(repository.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0, ScanAttempt.class));

        Thread.currentThread().interrupt();
        try {
            assertEquals("FAILED", service.scan(new ScanRequest(fileId, "SLOW")).result());
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void finishedAttemptCannotBeOverwritten() {
        ScanAttempt attempt = finished("CLEAN", "CLEAN");

        assertThrows(IllegalStateException.class, () -> attempt.finish("INFECTED", FINISHED_AT));
        assertEquals("CLEAN", attempt.getResult());
    }

    @Test
    void unknownFileIsNotFound() {
        when(repository.findByFileId(fileId)).thenReturn(Optional.empty());

        assertThrows(NotFoundException.class, () -> service.find(fileId));
    }

    private ScannerService service(long inFlightWaitMillis) {
        return new ScannerService(repository, new ScanAttemptWriter(repository), 0, inFlightWaitMillis, 1);
    }

    private ScanAttempt inProgress(String mode) {
        return new ScanAttempt(fileId, mode, STARTED_AT);
    }

    private ScanAttempt finished(String mode, String result) {
        ScanAttempt attempt = inProgress(mode);
        attempt.finish(result, FINISHED_AT);
        return attempt;
    }
}

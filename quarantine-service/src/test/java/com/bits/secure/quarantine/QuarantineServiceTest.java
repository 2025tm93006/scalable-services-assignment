package com.bits.secure.quarantine;

import com.bits.secure.common.ConflictException;
import com.bits.secure.common.NotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * The state machine in isolation. The Scanner service is represented by its client
 * bean, so these tests decide what Quarantine does with a verdict, an outage, a lost
 * race and an exhausted capacity - the branches the HTTP layer cannot force on its own.
 */
@ExtendWith(MockitoExtension.class)
class QuarantineServiceTest {
    private static final Instant QUARANTINED_AT = Instant.parse("2026-10-26T08:00:00Z");
    private static final Instant SCANNED_AT = Instant.parse("2026-10-26T08:00:05Z");
    @Mock private QuarantineRepository repository;
    @Mock private ScanCapacityService capacity;
    @Mock private ScannerClient scanner;
    private final UUID fileId = UUID.randomUUID();
    private QuarantineService service;

    @BeforeEach
    void setUp() {
        service = new QuarantineService(repository, new QuarantineRecordWriter(repository), capacity, scanner);
    }

    @Test
    void newFileIsQuarantinedAndWaitsForAScan() {
        when(repository.existsByFileId(fileId)).thenReturn(false);
        when(repository.saveAndFlush(any())).thenAnswer(written -> written.getArgument(0, QuarantineRecord.class));

        QuarantineResponse response = service.quarantine(new QuarantineRequest(fileId, "CLEAN"));

        assertEquals(fileId, response.fileId());
        assertEquals("QUARANTINED", response.state());
        assertNotNull(response.quarantinedAt());
        assertNull(response.scannedAt());
        ArgumentCaptor<QuarantineRecord> saved = ArgumentCaptor.forClass(QuarantineRecord.class);
        verify(repository).saveAndFlush(saved.capture());
        assertEquals(QuarantineState.QUARANTINED, saved.getValue().getState());
    }

    @Test
    void alreadyQuarantinedFileCannotBeQuarantinedAgain() {
        when(repository.existsByFileId(fileId)).thenReturn(true);

        assertThrows(ConflictException.class,
                () -> service.quarantine(new QuarantineRequest(fileId, "CLEAN")));
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void simultaneousQuarantineOfOneFileReportsTheConflictTheSequentialRepeatGets() {
        when(repository.existsByFileId(fileId)).thenReturn(false);
        when(repository.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException("unique fileId"));

        ConflictException conflict = assertThrows(ConflictException.class,
                () -> service.quarantine(new QuarantineRequest(fileId, "CLEAN")));

        assertEquals("File is already quarantined", conflict.getMessage());
    }

    @Test
    void scanAsksTheScannerOverItsClientAndStoresTheVerdictAndTimestamp() {
        QuarantineRecord record = quarantined();
        when(repository.findById(record.getId())).thenReturn(Optional.of(record));
        when(repository.saveAndFlush(any())).thenAnswer(written -> written.getArgument(0, QuarantineRecord.class));
        when(capacity.claim(fileId)).thenReturn(1);
        when(scanner.scan(fileId, "CLEAN")).thenReturn(
                new ScannerClient.ScanResult(fileId, "CLEAN", "Deterministic mock result", SCANNED_AT));

        QuarantineResponse response = service.scan(record.getId());

        assertEquals("CLEAN", response.state());
        assertEquals(SCANNED_AT, response.scannedAt(), "the scan stamp comes from Scanner, not from this JVM");
        verify(scanner).scan(fileId, "CLEAN");
        verify(capacity).release(1, fileId);
    }

    @Test
    void scannerOutageSettlesTheFileAsFailedAndStillGivesTheSlotBack() {
        QuarantineRecord record = quarantined();
        when(repository.findById(record.getId())).thenReturn(Optional.of(record));
        when(repository.saveAndFlush(any())).thenAnswer(written -> written.getArgument(0, QuarantineRecord.class));
        when(capacity.claim(fileId)).thenReturn(1);
        when(scanner.scan(fileId, "CLEAN")).thenThrow(
                new ScannerClient.ScannerUnavailableException("Scanner did not answer after 3 attempts", null));

        QuarantineResponse response = service.scan(record.getId());

        assertEquals("FAILED", response.state());
        assertNotNull(response.scannedAt());
        verify(capacity).release(1, fileId);
    }

    @Test
    void unexpectedScannerResultIsTreatedAsAFailedScan() {
        QuarantineRecord record = quarantined();
        when(repository.findById(record.getId())).thenReturn(Optional.of(record));
        when(repository.saveAndFlush(any())).thenAnswer(written -> written.getArgument(0, QuarantineRecord.class));
        when(capacity.claim(fileId)).thenReturn(1);
        when(scanner.scan(fileId, "CLEAN")).thenReturn(
                new ScannerClient.ScanResult(fileId, "MAYBE", "unexpected", SCANNED_AT));

        assertEquals("FAILED", service.scan(record.getId()).state());
    }

    @Test
    void aResponseWithoutAVerdictIsTreatedAsAFailedScan() {
        QuarantineRecord record = quarantined();
        when(repository.findById(record.getId())).thenReturn(Optional.of(record));
        when(repository.saveAndFlush(any())).thenAnswer(written -> written.getArgument(0, QuarantineRecord.class));
        when(capacity.claim(fileId)).thenReturn(1);
        // An empty body deserialises to null and a body with no result field to a null
        // verdict; neither may be read as CLEAN.
        when(scanner.scan(fileId, "CLEAN")).thenReturn(
                new ScannerClient.ScanResult(fileId, null, "", null));

        QuarantineResponse response = service.scan(record.getId());

        assertEquals("FAILED", response.state());
        assertNotNull(response.scannedAt(), "a failed scan is still a settled transition");
        verify(capacity).release(1, fileId);
    }

    @Test
    void exhaustedCapacityRefusesTheScanAndLeavesItQuarantined() {
        QuarantineRecord record = quarantined();
        when(repository.findById(record.getId())).thenReturn(Optional.of(record));
        when(capacity.claim(fileId)).thenReturn(-1);

        ConflictException conflict = assertThrows(ConflictException.class, () -> service.scan(record.getId()));

        assertEquals("Scanner capacity exhausted", conflict.getMessage());
        assertEquals(QuarantineState.QUARANTINED, record.getState());
        verify(scanner, never()).scan(any(), anyString());
        verify(capacity, never()).release(anyInt(), any());
    }

    @Test
    void aScannedRecordCannotBeScannedTwice() {
        QuarantineRecord record = quarantined();
        record.completeScan(QuarantineState.CLEAN, SCANNED_AT);
        when(repository.findById(record.getId())).thenReturn(Optional.of(record));

        ConflictException conflict = assertThrows(ConflictException.class, () -> service.scan(record.getId()));

        assertEquals("File has already been scanned", conflict.getMessage());
        verify(capacity, never()).claim(any());
    }

    @Test
    void aScanAlreadyInFlightIsRefusedRatherThanStartedAgain() {
        QuarantineRecord record = quarantined();
        record.startScan(SCANNED_AT);
        when(repository.findById(record.getId())).thenReturn(Optional.of(record));

        ConflictException conflict = assertThrows(ConflictException.class, () -> service.scan(record.getId()));

        assertEquals("File is already being scanned", conflict.getMessage());
        verify(capacity, never()).claim(any());
    }

    @Test
    void unknownQuarantineRecordIsNotFound() {
        UUID id = UUID.randomUUID();
        when(repository.findById(id)).thenReturn(Optional.empty());

        assertThrows(NotFoundException.class, () -> service.scan(id));
        assertThrows(NotFoundException.class, () -> service.get(id));
    }

    @Test
    void aFileCanBeReadThroughTheQuarantineRecordItOwns() {
        QuarantineRecord record = quarantined();
        when(repository.findByFileId(record.getFileId())).thenReturn(Optional.of(record));

        QuarantineResponse view = service.getByFileId(record.getFileId());

        // This is the response Release verifies against: state and quarantinedAt, by fileId.
        assertEquals(record.getId(), view.id());
        assertEquals("QUARANTINED", view.state());
        assertEquals(record.getQuarantinedAt(), view.quarantinedAt());
    }

    @Test
    void aFileThatWasNeverQuarantinedIsNotFoundThroughEitherAddress() {
        UUID unknown = UUID.randomUUID();
        when(repository.findByFileId(unknown)).thenReturn(Optional.empty());

        NotFoundException failure = assertThrows(NotFoundException.class,
                () -> service.getByFileId(unknown));

        assertTrue(failure.getMessage().contains(String.valueOf(unknown)), failure.getMessage());
    }

    @Test
    void summaryAddsTheStatesItCountsAndReportsTheCapacity() {
        when(repository.countByState(QuarantineState.QUARANTINED)).thenReturn(2L);
        when(repository.countByState(QuarantineState.SCANNING)).thenReturn(1L);
        when(repository.countByState(QuarantineState.CLEAN)).thenReturn(5L);
        when(repository.countByState(QuarantineState.INFECTED)).thenReturn(1L);
        when(repository.countByState(QuarantineState.FAILED)).thenReturn(0L);
        when(capacity.countFree()).thenReturn(1L);
        when(capacity.countHeld()).thenReturn(1L);
        when(capacity.capacity()).thenReturn(2);

        QuarantineEvidence evidence = service.evidence();

        assertEquals(9L, evidence.total());
        assertEquals(5L, evidence.byState().get("CLEAN"));
        assertEquals(1L, evidence.scannerSlots().get("HELD"));
        assertEquals(2, evidence.capacity());
    }

    private QuarantineRecord quarantined() {
        return new QuarantineRecord(UUID.randomUUID(), fileId, "CLEAN", QUARANTINED_AT);
    }
}

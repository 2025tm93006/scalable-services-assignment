package com.bits.secure.quarantine;

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

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class QuarantineServiceTest {
    private static final Instant QUARANTINED_AT = Instant.parse("2026-10-26T08:00:00Z");
    @Mock private QuarantineRepository repository;
    private final UUID fileId = UUID.randomUUID();
    private QuarantineService service;

    @BeforeEach
    void setUp() {
        service = new QuarantineService(repository);
    }

    @Test
    void newFileIsQuarantinedAndWaitsForAScan() {
        when(repository.existsByFileId(fileId)).thenReturn(false);
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0, QuarantineRecord.class));

        QuarantineResponse response = service.quarantine(new QuarantineRequest(fileId, "CLEAN"));

        assertEquals(fileId, response.fileId());
        assertEquals("QUARANTINED", response.state());
        assertNotNull(response.quarantinedAt());
        assertNull(response.scannedAt());
        ArgumentCaptor<QuarantineRecord> saved = ArgumentCaptor.forClass(QuarantineRecord.class);
        verify(repository).save(saved.capture());
        assertEquals(QuarantineState.QUARANTINED, saved.getValue().getState());
        assertNull(saved.getValue().getScannedAt());
    }

    @Test
    void alreadyQuarantinedFileCannotBeQuarantinedAgain() {
        when(repository.existsByFileId(fileId)).thenReturn(true);

        assertThrows(ConflictException.class,
                () -> service.quarantine(new QuarantineRequest(fileId, "CLEAN")));
        verify(repository, never()).save(any());
    }

    @ParameterizedTest
    @CsvSource({"CLEAN,CLEAN", "INFECTED,INFECTED", "FAIL,FAILED", "SLOW,CLEAN"})
    void everyScannerModeResolvesToItsDeclaredState(String scannerMode, QuarantineState expected) {
        QuarantineRecord record = quarantined(scannerMode);
        when(repository.findById(record.getId())).thenReturn(Optional.of(record));
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0, QuarantineRecord.class));

        QuarantineResponse response = service.scan(record.getId());

        assertEquals(expected, QuarantineState.valueOf(response.state()));
        assertNotNull(response.scannedAt());
    }

    @Test
    void aScannedRecordCannotBeScannedTwice() {
        QuarantineRecord record = quarantined("CLEAN");
        record.completeScan(QuarantineState.CLEAN, QUARANTINED_AT);
        when(repository.findById(record.getId())).thenReturn(Optional.of(record));

        assertThrows(ConflictException.class, () -> service.scan(record.getId()));
        verify(repository, never()).save(any());
    }

    @Test
    void unknownQuarantineRecordIsNotFound() {
        UUID id = UUID.randomUUID();
        when(repository.findById(id)).thenReturn(Optional.empty());

        assertThrows(NotFoundException.class, () -> service.scan(id));
        assertThrows(NotFoundException.class, () -> service.get(id));
    }

    @Test
    void summaryAddsTheStatesItCounts() {
        when(repository.countByState(QuarantineState.QUARANTINED)).thenReturn(2L);
        when(repository.countByState(QuarantineState.CLEAN)).thenReturn(5L);
        when(repository.countByState(QuarantineState.INFECTED)).thenReturn(1L);
        when(repository.countByState(QuarantineState.FAILED)).thenReturn(0L);

        QuarantineEvidence evidence = service.evidence();

        assertEquals(8L, evidence.total());
        assertEquals(5L, evidence.byState().get("CLEAN"));
        assertEquals(0L, evidence.byState().get("FAILED"));
    }

    private QuarantineRecord quarantined(String scannerMode) {
        return new QuarantineRecord(UUID.randomUUID(), fileId, scannerMode, QUARANTINED_AT);
    }
}

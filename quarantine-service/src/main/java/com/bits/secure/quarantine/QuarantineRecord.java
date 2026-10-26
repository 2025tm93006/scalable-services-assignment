package com.bits.secure.quarantine;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "quarantine_records")
public class QuarantineRecord {
    @Id
    private UUID id;

    @Column(nullable = false, unique = true)
    private UUID fileId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private QuarantineState state;

    @Column(nullable = false)
    private String scannerMode;

    @Column(nullable = false)
    private Instant quarantinedAt;

    private Instant scannedAt;

    protected QuarantineRecord() {
    }

    QuarantineRecord(UUID id, UUID fileId, String scannerMode, Instant quarantinedAt) {
        this.id = id;
        this.fileId = fileId;
        this.scannerMode = scannerMode;
        this.quarantinedAt = quarantinedAt;
        this.state = QuarantineState.QUARANTINED;
    }

    void startScan() {
        this.state = QuarantineState.SCANNING;
    }

    void completeScan(QuarantineState result, Instant timestamp) {
        this.state = result;
        this.scannedAt = timestamp;
    }

    UUID getId() {
        return id;
    }

    UUID getFileId() {
        return fileId;
    }

    QuarantineState getState() {
        return state;
    }

    String getScannerMode() {
        return scannerMode;
    }

    Instant getQuarantinedAt() {
        return quarantinedAt;
    }

    Instant getScannedAt() {
        return scannedAt;
    }
}
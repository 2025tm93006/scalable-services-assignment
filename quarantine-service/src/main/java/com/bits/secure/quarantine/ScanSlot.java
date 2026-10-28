package com.bits.secure.quarantine;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * One row per scanner capacity slot. The rows are the contended resource: a scan
 * may only run while it owns a row in the FREE state, and the claim is a single
 * conditional update, so simultaneous requests cannot both take the same slot.
 * Holding it in the table rather than in a java.util.concurrent permit also means
 * the allocation is visible after the process stops and is enforced by the
 * database rather than by one JVM.
 */
@Entity
@Table(name = "scan_slots")
public class ScanSlot {
    public static final String FREE = "FREE";
    public static final String HELD = "HELD";

    @Id
    private int slotNumber;

    private UUID fileId;

    @Column(nullable = false)
    private String status;

    private Instant acquiredAt;

    private Instant releasedAt;

    protected ScanSlot() {
    }

    ScanSlot(int slotNumber) {
        this.slotNumber = slotNumber;
        this.status = FREE;
    }

    int getSlotNumber() {
        return slotNumber;
    }

    UUID getFileId() {
        return fileId;
    }

    String getStatus() {
        return status;
    }

    Instant getAcquiredAt() {
        return acquiredAt;
    }

    Instant getReleasedAt() {
        return releasedAt;
    }
}

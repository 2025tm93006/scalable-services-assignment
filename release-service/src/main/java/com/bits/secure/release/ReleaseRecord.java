package com.bits.secure.release;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "release_record")
public class ReleaseRecord {
    public static final String RELEASED = "RELEASED";

    @Id
    @Column(name = "file_id", nullable = false)
    private UUID fileId;

    @Column(nullable = false, length = 16)
    private String state;

    // Quarantine evidence accepted when the release was granted, kept for audit.
    @Column(name = "quarantined_at", nullable = false)
    private Instant quarantinedAt;

    @Column(name = "released_at", nullable = false)
    private Instant releasedAt;

    protected ReleaseRecord() {
    }

    public ReleaseRecord(UUID fileId, Instant quarantinedAt, Instant releasedAt) {
        this.fileId = fileId;
        this.state = RELEASED;
        this.quarantinedAt = quarantinedAt;
        this.releasedAt = releasedAt;
    }

    public UUID getFileId() {
        return fileId;
    }

    public String getState() {
        return state;
    }

    public Instant getQuarantinedAt() {
        return quarantinedAt;
    }

    public Instant getReleasedAt() {
        return releasedAt;
    }
}

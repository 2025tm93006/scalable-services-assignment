package com.bits.secure.scanner;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "scan_attempt", uniqueConstraints =
        @UniqueConstraint(name = "uk_scan_attempt_file_id", columnNames = "file_id"))
public class ScanAttempt {
    @Id
    @GeneratedValue
    private Long id;

    // fileId is the operation identity: one attempt, and therefore one result, per file.
    @Column(name = "file_id", nullable = false)
    private UUID fileId;

    @Column(nullable = false, length = 16)
    private String mode;

    // Null while the scan is in progress; set once and never changed afterwards.
    @Column(length = 16)
    private String result;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    protected ScanAttempt() {
    }

    public ScanAttempt(UUID fileId, String mode, Instant startedAt) {
        this.fileId = fileId;
        this.mode = mode;
        this.startedAt = startedAt;
    }

    void finish(String result, Instant finishedAt) {
        if (this.result != null) {
            throw new IllegalStateException("Scan attempt already finished for file " + fileId);
        }
        this.result = result;
        this.finishedAt = finishedAt;
    }

    public boolean isFinished() {
        return result != null;
    }

    public Long getId() { return id; }
    public UUID getFileId() { return fileId; }
    public String getMode() { return mode; }
    public String getResult() { return result; }
    public Instant getStartedAt() { return startedAt; }
    public Instant getFinishedAt() { return finishedAt; }
}

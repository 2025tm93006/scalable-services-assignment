package com.bits.secure.intake;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "file_record", uniqueConstraints =
        @UniqueConstraint(name = "uk_file_idempotency_key", columnNames = "idempotency_key"))
public class FileRecord {
    @Id
    private UUID id;

    @Column(name = "idempotency_key", nullable = false, length = 255)
    private String idempotencyKey;

    @Column(nullable = false, length = 64)
    private String fingerprint;

    @Column(nullable = false, length = 255)
    private String filename;

    @Column(nullable = false, length = 255)
    private String contentType;

    @Column(nullable = false)
    private long sizeBytes;

    @Column(nullable = false, length = 64)
    private String sha256;

    @Column(nullable = false, length = 16)
    private String scannerMode;

    @Column(nullable = false, length = 16)
    private String status;

    @Column(nullable = false)
    private Instant createdAt;

    @Lob
    @Column(nullable = false)
    private String responseBody;

    protected FileRecord() {
    }

    FileRecord(String key, String fingerprint, IntakeRequest request,
               IntakeResponse response, String responseBody) {
        this.id = response.fileId();
        this.idempotencyKey = key;
        this.fingerprint = fingerprint;
        this.filename = request.filename();
        this.contentType = request.contentType();
        this.sizeBytes = request.sizeBytes();
        this.sha256 = request.sha256();
        this.scannerMode = request.scannerMode();
        this.status = response.status();
        this.createdAt = response.receivedAt();
        this.responseBody = responseBody;
    }

    public UUID getId() { return id; }
    public String getFingerprint() { return fingerprint; }
    public String getResponseBody() { return responseBody; }
}

package com.bits.secure.intake;

import com.bits.secure.common.ConflictException;
import com.bits.secure.common.NotFoundException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;

@Service
public class IntakeService {
    private static final Logger log = LoggerFactory.getLogger(IntakeService.class);
    private final FileRepository repository;
    private final IntakeRecordWriter writer;
    private final ObjectMapper mapper;

    public IntakeService(FileRepository repository, IntakeRecordWriter writer, ObjectMapper mapper) {
        this.repository = repository;
        this.writer = writer;
        this.mapper = mapper;
    }

    public IntakeResponse receive(String key, IntakeRequest request) {
        validateKey(key);
        String fingerprint = fingerprint(request);
        var prior = repository.findByIdempotencyKey(key);
        if (prior.isPresent()) {
            return replay(prior.get(), fingerprint);
        }

        IntakeResponse response = new IntakeResponse(UUID.randomUUID(), "RECEIVED",
                request.filename(), request.scannerMode(), Instant.now());
        FileRecord candidate = new FileRecord(key, fingerprint, request, response, json(response));
        try {
            FileRecord created = writer.create(candidate);
            log.info("idempotency_created fileId={}", created.getId());
            return storedResponse(created);
        } catch (DataIntegrityViolationException failure) {
            // No process-local lock: the unique database constraint chooses the winner.
            // Do not turn an unrelated integrity failure into a replay or conflict.
            FileRecord winner = repository.findByIdempotencyKey(key).orElseThrow(() -> failure);
            return replay(winner, fingerprint);
        }
    }

    public IntakeResponse find(UUID id) {
        return repository.findById(id).map(this::storedResponse)
                .orElseThrow(() -> new NotFoundException("File not found: " + id));
    }

    public IdempotencyEvidence evidence(String key) {
        validateKey(key);
        return new IdempotencyEvidence(key, repository.countByIdempotencyKey(key),
                repository.findByIdempotencyKey(key).map(FileRecord::getId).orElse(null));
    }

    private IntakeResponse replay(FileRecord record, String fingerprint) {
        if (!record.getFingerprint().equals(fingerprint)) {
            log.warn("idempotency_conflict fileId={}", record.getId());
            throw new ConflictException("Idempotency-Key was used with a different payload");
        }
        log.info("idempotency_replay fileId={}", record.getId());
        return storedResponse(record);
    }

    String fingerprint(IntakeRequest request) {
        try {
            // Serialize the typed fields, so JSON whitespace/order do not change identity
            // and embedded delimiters cannot make different payloads collide.
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(mapper.writeValueAsBytes(request)));
        } catch (JsonProcessingException | NoSuchAlgorithmException ex) {
            throw new IllegalStateException("Could not fingerprint intake request", ex);
        }
    }

    private String json(IntakeResponse response) {
        try {
            return mapper.writeValueAsString(response);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Could not store intake response", ex);
        }
    }

    private IntakeResponse storedResponse(FileRecord record) {
        try {
            return mapper.readValue(record.getResponseBody(), IntakeResponse.class);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Could not read stored intake response", ex);
        }
    }

    private void validateKey(String key) {
        if (key == null || key.isBlank() || key.length() > 255) {
            throw new IllegalArgumentException("Idempotency-Key must contain 1 to 255 characters and not be blank");
        }
    }

    public record IdempotencyEvidence(String idempotencyKey, long databaseRecordCount, UUID fileId) {
    }
}

package com.bits.secure.quarantine;

import com.bits.secure.common.ConflictException;
import com.bits.secure.common.NotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@Service
public class QuarantineService {
    private final QuarantineRepository repository;

    public QuarantineService(QuarantineRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public QuarantineResponse quarantine(QuarantineRequest request) {
        if (repository.existsByFileId(request.fileId())) {
            throw new ConflictException("File is already quarantined");
        }

        QuarantineRecord record = new QuarantineRecord(
                UUID.randomUUID(), request.fileId(), request.scannerMode(), Instant.now());
        return response(repository.save(record));
    }

    @Transactional
    public QuarantineResponse scan(UUID id) {
        QuarantineRecord record = findRecord(id);
        if (record.getState() != QuarantineState.QUARANTINED) {
            throw new ConflictException("File has already been scanned");
        }

        QuarantineState result = switch (record.getScannerMode()) {
            case "INFECTED" -> QuarantineState.INFECTED;
            case "FAIL" -> QuarantineState.FAILED;
            case "CLEAN", "SLOW" -> QuarantineState.CLEAN;
            default -> throw new IllegalStateException("Unsupported scanner mode");
        };
        record.completeScan(result, Instant.now());
        return response(repository.save(record));
    }

    @Transactional(readOnly = true)
    public QuarantineResponse get(UUID id) {
        return response(findRecord(id));
    }

    @Transactional(readOnly = true)
    public QuarantineEvidence evidence() {
        Map<String, Long> byState = new LinkedHashMap<>();
        long total = 0;
        for (QuarantineState state : QuarantineState.values()) {
            long count = repository.countByState(state);
            byState.put(state.name(), count);
            total += count;
        }
        return new QuarantineEvidence(total, byState);
    }

    private QuarantineRecord findRecord(UUID id) {
        return repository.findById(id)
                .orElseThrow(() -> new NotFoundException("Quarantine record not found"));
    }

    private QuarantineResponse response(QuarantineRecord record) {
        return new QuarantineResponse(record.getId(), record.getFileId(), record.getState().name(),
                record.getScannerMode(), record.getQuarantinedAt(), record.getScannedAt());
    }
}
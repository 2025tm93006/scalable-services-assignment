package com.bits.secure.quarantine;

import com.bits.secure.common.ConflictException;
import com.bits.secure.common.NotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Owns the quarantine record and its state machine. A scan is not decided here: the
 * record claims one scanner capacity slot, asks the Scanner service over REST and
 * stores that service's verdict, settling once and never again.
 */
@Service
public class QuarantineService {
    private static final Logger log = LoggerFactory.getLogger(QuarantineService.class);

    private final QuarantineRepository repository;
    private final QuarantineRecordWriter writer;
    private final ScanCapacityService capacity;
    private final ScannerClient scanner;

    public QuarantineService(QuarantineRepository repository,
                             QuarantineRecordWriter writer,
                             ScanCapacityService capacity,
                             ScannerClient scanner) {
        this.repository = repository;
        this.writer = writer;
        this.capacity = capacity;
        this.scanner = scanner;
    }

    public QuarantineResponse quarantine(QuarantineRequest request) {
        if (repository.existsByFileId(request.fileId())) {
            throw new ConflictException("File is already quarantined");
        }
        Instant now = Instant.now();
        QuarantineRecord record = new QuarantineRecord(
                UUID.randomUUID(), request.fileId(), request.scannerMode(), now);
        try {
            return response(writer.save(record));
        } catch (DataIntegrityViolationException raced) {
            // Two requests passed the check above. The unique fileId index chose the
            // winner, so the loser reports the same conflict a sequential repeat gets.
            log.info("quarantine_race fileId={} loserId={}", request.fileId(), record.getId());
            throw new ConflictException("File is already quarantined");
        }
    }

    /**
     * Not transactional on purpose: the Scanner call and its retries must not hold a
     * database transaction or a capacity row open. Each state change is committed on
     * its own, which is also what lets a concurrent request see SCANNING.
     */
    public QuarantineResponse scan(UUID id) {
        QuarantineRecord record = findRecord(id);
        if (record.getState() == QuarantineState.SCANNING) {
            throw new ConflictException("File is already being scanned");
        }
        if (record.getState() != QuarantineState.QUARANTINED) {
            throw new ConflictException("File has already been scanned");
        }

        int slot = capacity.claim(record.getFileId());
        if (slot < 0) {
            throw new ConflictException("Scanner capacity exhausted");
        }

        record.startScan();
        writer.save(record);
        log.info("scan_started fileId={} recordId={} slot={} mode={}",
                record.getFileId(), record.getId(), slot, record.getScannerMode());
        try {
            ScannerClient.ScanResult result = scanner.scan(record.getFileId(), record.getScannerMode());
            QuarantineState outcome = stateOf(result);
            record.completeScan(outcome, result == null || result.completedAt() == null
                    ? Instant.now() : result.completedAt());
            writer.save(record);
            log.info("scan_settled fileId={} state={} slot={}", record.getFileId(), outcome, slot);
        } catch (ScannerClient.ScannerUnavailableException outage) {
            // Graceful failure: the file is marked FAILED rather than leaving the caller
            // with a half-run scan, and the reason is logged with the attempt count.
            record.completeScan(QuarantineState.FAILED, Instant.now());
            writer.save(record);
            log.error("scan_failed_downstream_unavailable fileId={} message={}",
                    record.getFileId(), outage.getMessage());
        } finally {
            capacity.release(slot, record.getFileId());
        }
        return response(findRecord(id));
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
        Map<String, Long> slots = new LinkedHashMap<>();
        slots.put(ScanSlot.FREE, capacity.countFree());
        slots.put(ScanSlot.HELD, capacity.countHeld());
        return new QuarantineEvidence(total, byState, slots, capacity.capacity());
    }

    private QuarantineState stateOf(ScannerClient.ScanResult result) {
        String reported = result == null ? null : result.result();
        if (reported == null) {
            // An empty or verdict-less body is not a scan result, and guessing a state
            // from it would invent a security decision, so the file is marked FAILED.
            log.warn("scan_result_missing treatedAs=FAILED");
            return QuarantineState.FAILED;
        }
        try {
            return QuarantineState.valueOf(reported);
        } catch (IllegalArgumentException unexpected) {
            log.warn("scan_result_unexpected value={} treatedAs=FAILED", reported);
            return QuarantineState.FAILED;
        }
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

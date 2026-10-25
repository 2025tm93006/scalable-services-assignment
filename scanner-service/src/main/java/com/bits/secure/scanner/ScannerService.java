package com.bits.secure.scanner;

import com.bits.secure.common.ConflictException;
import com.bits.secure.common.NotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Service
public class ScannerService {
    static final String CLEAN = "CLEAN";
    static final String INFECTED = "INFECTED";
    static final String FAILED = "FAILED";
    static final String IN_PROGRESS = "IN_PROGRESS";
    private static final Logger log = LoggerFactory.getLogger(ScannerService.class);

    private final ScanAttemptRepository repository;
    private final ScanAttemptWriter writer;
    private final long slowMillis;
    private final long inFlightWaitMillis;
    private final long inFlightPollMillis;

    public ScannerService(
            ScanAttemptRepository repository,
            ScanAttemptWriter writer,
            @Value("${scanner.slow-millis:2000}") long slowMillis,
            @Value("${scanner.in-flight-wait-millis:4000}") long inFlightWaitMillis,
            @Value("${scanner.in-flight-poll-millis:50}") long inFlightPollMillis) {
        if (slowMillis < 0) throw new IllegalArgumentException("SCANNER_SLOW_MILLIS must not be negative");
        if (inFlightWaitMillis < 0) throw new IllegalArgumentException("SCANNER_IN_FLIGHT_WAIT_MILLIS must not be negative");
        if (inFlightPollMillis < 1) throw new IllegalArgumentException("SCANNER_IN_FLIGHT_POLL_MILLIS must be at least 1");
        this.repository = repository;
        this.writer = writer;
        this.slowMillis = slowMillis;
        this.inFlightWaitMillis = inFlightWaitMillis;
        this.inFlightPollMillis = inFlightPollMillis;
    }

    /**
     * fileId is the operation identity. The first request commits an in-progress
     * attempt and runs the scan; a repeat, including a client retry after a read
     * timeout, waits for that attempt and replays its stored result instead of
     * scanning again. The unique fileId column, not a process-local lock, decides
     * which request owns the scan, so the rule does not depend on one JVM. Replicas
     * would still need a shared database server; embedded H2 has one owner process.
     */
    public ScanResponse scan(ScanRequest request) {
        var prior = repository.findByFileId(request.fileId());
        if (prior.isPresent()) {
            return existing(prior.get(), request);
        }

        ScanAttempt attempt;
        try {
            attempt = writer.claim(new ScanAttempt(request.fileId(), request.mode(), now()));
        } catch (DataIntegrityViolationException failure) {
            // Two requests both saw no attempt. The unique key chose the owner; the
            // loser follows that attempt. Unrelated integrity failures stay errors.
            ScanAttempt winner = repository.findByFileId(request.fileId()).orElseThrow(() -> failure);
            log.info("scan_race_follow fileId={}", winner.getFileId());
            return existing(winner, request);
        }

        log.info("scan_started fileId={} mode={}", attempt.getFileId(), attempt.getMode());
        boolean interrupted = false;
        String result;
        try {
            result = execute(attempt.getMode());
        } catch (InterruptedException e) {
            // The interrupt flag is clear here, so the database write below is not
            // disturbed; it is restored once the result is stored.
            interrupted = true;
            result = FAILED;
            log.warn("scan_interrupted fileId={}", attempt.getFileId());
        }
        ScanAttempt finished = writer.finish(attempt, result, now());
        if (interrupted) Thread.currentThread().interrupt();
        log.info("scan_completed fileId={} mode={} result={} durationMillis={}", finished.getFileId(),
                finished.getMode(), finished.getResult(),
                finished.getFinishedAt().toEpochMilli() - finished.getStartedAt().toEpochMilli());
        return ScanResponse.from(finished);
    }

    public ScanAttemptResponse find(UUID fileId) {
        return repository.findByFileId(fileId).map(ScanAttemptResponse::from)
                .orElseThrow(() -> new NotFoundException("No scan attempt for file: " + fileId));
    }

    public ScannerEvidence evidence() {
        Map<String, Long> results = new LinkedHashMap<>();
        results.put(CLEAN, repository.countByResult(CLEAN));
        results.put(INFECTED, repository.countByResult(INFECTED));
        results.put(FAILED, repository.countByResult(FAILED));
        results.put(IN_PROGRESS, repository.countByResultIsNull());
        return new ScannerEvidence(repository.count(), results);
    }

    private ScanResponse existing(ScanAttempt attempt, ScanRequest request) {
        if (!attempt.getMode().equals(request.mode())) {
            log.warn("scan_conflict fileId={} storedMode={} requestedMode={}",
                    attempt.getFileId(), attempt.getMode(), request.mode());
            throw new ConflictException("File was already submitted for scanning with a different mode");
        }
        ScanAttempt finished = attempt.isFinished() ? attempt : awaitCompletion(attempt.getFileId());
        log.info("scan_replay fileId={} result={}", finished.getFileId(), finished.getResult());
        return ScanResponse.from(finished);
    }

    private ScanAttempt awaitCompletion(UUID fileId) {
        log.info("scan_in_progress_wait fileId={} maxWaitMillis={}", fileId, inFlightWaitMillis);
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(inFlightWaitMillis);
        while (true) {
            ScanAttempt current = repository.findByFileId(fileId)
                    .orElseThrow(() -> new IllegalStateException("Scan attempt disappeared for file " + fileId));
            if (current.isFinished()) return current;
            if (System.nanoTime() >= deadline) break;
            try {
                Thread.sleep(inFlightPollMillis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        log.warn("scan_still_in_progress fileId={}", fileId);
        throw new ConflictException("Scan is still in progress for this file; retry later");
    }

    private String execute(String mode) throws InterruptedException {
        return switch (mode) {
            case "CLEAN" -> CLEAN;
            case "INFECTED" -> INFECTED;
            case "FAIL" -> FAILED;
            case "SLOW" -> {
                Thread.sleep(slowMillis);
                yield CLEAN;
            }
            default -> throw new IllegalArgumentException("Unsupported scanner mode: " + mode);
        };
    }

    // Millisecond precision keeps a replayed response identical to the first one.
    private static Instant now() {
        return Instant.now().truncatedTo(ChronoUnit.MILLIS);
    }
}

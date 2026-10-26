package com.bits.secure.scanner;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

@Service
public class ScanAttemptWriter {
    private final ScanAttemptRepository repository;

    public ScanAttemptWriter(ScanAttemptRepository repository) {
        this.repository = repository;
    }

    // Committed before the scan runs, so a concurrent or retried request sees the
    // in-progress row. A losing unique-key race rolls back here before ScannerService
    // rereads the winner.
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public ScanAttempt claim(ScanAttempt attempt) {
        return repository.saveAndFlush(attempt);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public ScanAttempt finish(ScanAttempt attempt, String result, Instant finishedAt) {
        attempt.finish(result, finishedAt);
        return repository.saveAndFlush(attempt);
    }
}

package com.bits.secure.quarantine;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

/**
 * Reclaims the two rows a killed process leaves behind: the scanner capacity slot it
 * was holding and the record it had moved to SCANNING. Without this sweep a crash
 * permanently reduces the capacity and permanently strands a file in an unsettled
 * state, which would contradict the Challenge 1 claim that the database is consistent
 * afterwards.
 *
 * Slots are freed first, then a stale SCANNING record is settled as FAILED only if no
 * slot is still held for it. That order matters: the crashed scan's own slot has just
 * been freed, so its record becomes reclaimable in the same sweep, while a scan that is
 * genuinely still running keeps its slot and is therefore left alone.
 *
 * Settling an abandoned scan is not a second transition. BR3 forbids re-deciding a
 * record that has already settled, and this sweep only touches records still in
 * SCANNING, which by definition have no verdict.
 */
@Component
public class ScanReclaimJob {
    private static final Logger log = LoggerFactory.getLogger(ScanReclaimJob.class);

    private final QuarantineRepository records;
    private final ScanCapacityService capacity;
    private final boolean enabled;
    private final long staleMillis;
    private final long readTimeoutMillis;
    private final int maxAttempts;

    public ScanReclaimJob(QuarantineRepository records,
                          ScanCapacityService capacity,
                          @Value("${quarantine.reclaim-enabled:true}") boolean enabled,
                          @Value("${quarantine.slot-stale-millis:60000}") long staleMillis,
                          @Value("${scanner.read-timeout-millis:5000}") long readTimeoutMillis,
                          @Value("${scanner.max-attempts:3}") int maxAttempts) {
        this.records = records;
        this.capacity = capacity;
        this.enabled = enabled;
        this.staleMillis = staleMillis;
        this.readTimeoutMillis = readTimeoutMillis;
        this.maxAttempts = maxAttempts;
    }

    /**
     * A slot must only be freed once no scan can still report a verdict for it. The
     * worst case for one scan is maxAttempts timeouts plus the backoff between them, so
     * an age below that would let the sweep hand a busy slot to somebody else and then
     * have the original scan overwrite a FAILED state with CLEAN.
     */
    @PostConstruct
    void warnIfAgeIsBelowTheBudget() {
        long budget = readTimeoutMillis * maxAttempts;
        if (enabled && staleMillis <= budget) {
            log.warn("scan_reclaim_age_below_budget staleMillis={} retryBudgetMillis={} "
                    + "a scan that is still running could have its slot taken away", staleMillis, budget);
        }
    }

    @Scheduled(initialDelayString = "${quarantine.reclaim-interval-millis:10000}",
            fixedDelayString = "${quarantine.reclaim-interval-millis:10000}")
    public void scheduledSweep() {
        if (enabled) sweep();
    }

    /** Runs one sweep and returns the row counts, so a test can invoke it directly. */
    public ReclaimResult sweep() {
        Instant now = Instant.now();
        Instant cutoff = now.minusMillis(staleMillis);

        int slotsFreed = capacity.releaseStaleSlots(cutoff);

        int recordsFailed = 0;
        List<QuarantineRecord> stale = records.findStale(QuarantineState.SCANNING, cutoff);
        for (QuarantineRecord record : stale) {
            if (capacity.slotHeldBy(record.getFileId()).isPresent()) {
                continue;   // a scan is still running for this file with its slot held
            }
            if (records.settleStaleScan(record.getId(), QuarantineState.SCANNING,
                    QuarantineState.FAILED, now, cutoff) == 1) {
                recordsFailed++;
                log.warn("scan_reclaim_settled fileId={} recordId={} from=SCANNING to=FAILED",
                        record.getFileId(), record.getId());
            }
        }

        if (slotsFreed > 0 || recordsFailed > 0) {
            log.warn("scan_reclaim slotsFreed={} recordsFailed={} staleMillis={}",
                    slotsFreed, recordsFailed, staleMillis);
        }
        return new ReclaimResult(slotsFreed, recordsFailed);
    }

    public record ReclaimResult(int slotsFreed, int recordsFailed) {
    }
}

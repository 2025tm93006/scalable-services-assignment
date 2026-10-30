package com.bits.secure.quarantine;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The sweep that cleans up after a scan whose process stopped. Everything here is driven
 * by calling {@link ScanReclaimJob#sweep()} directly and by writing deliberately old
 * timestamps, so the test is about the age and slot rules rather than about waiting for
 * a scheduler. Scheduling itself is switched off for the context.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
        "spring.datasource.url=jdbc:h2:mem:quarantine-reclaim-test;DB_CLOSE_DELAY=-1",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "quarantine.max-concurrent-scans=2",
        "quarantine.reclaim-enabled=false",
        "quarantine.slot-stale-millis=60000"
})
class ScanReclaimJobTest {
    @Autowired private ScanReclaimJob job;
    @Autowired private QuarantineRepository records;
    @Autowired private ScanCapacityService capacity;
    @Autowired private JdbcTemplate jdbc;

    private final UUID fileId = UUID.randomUUID();

    @BeforeEach
    void clear() {
        records.deleteAll();
        jdbc.update("update scan_slots set status = 'FREE', file_id = null, "
                + "acquired_at = null, released_at = null");
    }

    @Test
    void freesTheSlotOfAScanThatNeverFinished() {
        scanStartedAndAbandoned(fileId, Instant.now().minusSeconds(120));

        ScanReclaimJob.ReclaimResult result = job.sweep();

        assertThat(result.slotsFreed()).isEqualTo(1);
        assertThat(capacity.countHeld()).isZero();
        assertThat(capacity.countFree()).isEqualTo(2);
    }

    @Test
    void settlesTheAbandonedRecordAsFailedExactlyOnce() {
        QuarantineRecord record = scanStartedAndAbandoned(fileId, Instant.now().minusSeconds(120));

        ScanReclaimJob.ReclaimResult result = job.sweep();

        assertThat(result.recordsFailed()).isEqualTo(1);
        QuarantineRecord settled = records.findById(record.getId()).orElseThrow();
        assertThat(settled.getState()).isEqualTo(QuarantineState.FAILED);
        assertThat(settled.getScannedAt()).isNotNull();
        // The same sweep run again must not re-decide it, and must not free anything.
        assertThat(job.sweep()).isEqualTo(new ScanReclaimJob.ReclaimResult(0, 0));
        assertThat(records.findById(record.getId()).orElseThrow().getState())
                .isEqualTo(QuarantineState.FAILED);
        assertThat(records.countByState(QuarantineState.SCANNING)).isZero();
    }

    @Test
    void leavesARecentScanAloneBecauseItMayStillBeRunning() {
        QuarantineRecord record = scanStartedAndAbandoned(fileId, Instant.now().minusSeconds(5));

        assertThat(job.sweep()).isEqualTo(new ScanReclaimJob.ReclaimResult(0, 0));
        assertThat(records.findById(record.getId()).orElseThrow().getState())
                .isEqualTo(QuarantineState.SCANNING);
    }

    @Test
    void doesNotSettleARecordWhoseSlotIsStillHeld() {
        // Record stamped as scanning long ago but its slot was claimed now: only the
        // scan holding the slot may finish this file, so the sweep must not reach past it.
        QuarantineRecord record = newRecord(fileId);
        record.startScan(Instant.now().minusSeconds(120));
        records.saveAndFlush(record);
        assertThat(capacity.claim(fileId)).isPositive();

        ScanReclaimJob.ReclaimResult result = job.sweep();

        assertThat(result.slotsFreed()).isZero();
        assertThat(result.recordsFailed()).isZero();
        assertThat(records.findById(record.getId()).orElseThrow().getState())
                .isEqualTo(QuarantineState.SCANNING);
        assertThat(capacity.slotHeldBy(fileId)).isPresent();
    }

    @Test
    void neverReopensARecordThatAlreadySettled() {
        Instant longAgo = Instant.now().minusSeconds(600);
        QuarantineRecord record = newRecord(fileId);
        record.startScan(longAgo);
        record.completeScan(QuarantineState.CLEAN, longAgo.plusSeconds(1));
        records.saveAndFlush(record);

        assertThat(job.sweep()).isEqualTo(new ScanReclaimJob.ReclaimResult(0, 0));
        assertThat(records.findById(record.getId()).orElseThrow().getState())
                .isEqualTo(QuarantineState.CLEAN);
    }

    @Test
    void warnsAtStartupWhenTheAgeIsShorterThanTheRetryBudget() {
        List<ILoggingEvent> events = captureLogsFor(ScanReclaimJob.class);
        // 2000 ms of age against a 5000 ms x 3 attempt budget: a scan can still be
        // running when the sweep would take its slot away.
        new ScanReclaimJob(records, capacity, true, 2000, 5000, 3).warnIfAgeIsBelowTheBudget();

        assertThat(events).anySatisfy(event -> assertThat(event.getFormattedMessage())
                .contains("scan_reclaim_age_below_budget")
                .contains("retryBudgetMillis=15000"));
    }

    @Test
    void staysQuietWhenTheAgeIsComfortablyAboveTheRetryBudget() {
        List<ILoggingEvent> events = captureLogsFor(ScanReclaimJob.class);
        new ScanReclaimJob(records, capacity, true, 60000, 5000, 3).warnIfAgeIsBelowTheBudget();

        assertThat(events).noneSatisfy(event -> assertThat(event.getFormattedMessage())
                .contains("scan_reclaim_age_below_budget"));
    }

    private QuarantineRecord scanStartedAndAbandoned(UUID fileId, Instant startedAt) {
        QuarantineRecord record = newRecord(fileId);
        record.startScan(startedAt);
        records.saveAndFlush(record);
        assertThat(capacity.claim(fileId)).isPositive();
        // A scan that stopped took its slot's clock with it: the row is still HELD and
        // the record is still SCANNING, both as old as the instant it died at.
        ageTheSlotRow(fileId, startedAt);
        return record;
    }

    private QuarantineRecord newRecord(UUID fileId) {
        return records.saveAndFlush(new QuarantineRecord(UUID.randomUUID(), fileId, "CLEAN", Instant.now()));
    }

    /** The slot rows keep their own clock, so the fixture moves it back explicitly. */
    private void ageTheSlotRow(UUID fileId, Instant when) {
        jdbc.update("update scan_slots set acquired_at = ? where file_id = ?",
                Timestamp.from(when), fileId);
    }

    private ListAppender<ILoggingEvent> appender;

    private List<ILoggingEvent> captureLogsFor(Class<?> target) {
        Logger logger = (Logger) LoggerFactory.getLogger(target);
        appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        return appender.list;
    }

    @AfterEach
    void detachAppender() {
        if (appender != null) {
            ((Logger) LoggerFactory.getLogger(ScanReclaimJob.class)).detachAppender(appender);
        }
    }
}

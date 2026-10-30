package com.bits.secure.release;

import com.bits.secure.common.ConflictException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * The trust boundary of BR4, kept in one place: what the caller claims about a file is
 * compared with what the service that owns the state actually says.
 *
 * Release used to read the request's own quarantineState and stop there, which meant any
 * client could release anything by typing CLEAN. Quarantine owns the verdict, so this
 * component asks it - through QuarantineClient, with that call's own timeout, retry and
 * backoff budget - and refuses a claim that does not match.
 *
 * Three outcomes are deliberately distinguished:
 *   no record        -> 409, the file was never quarantined
 *   record not CLEAN -> 409, naming the state Quarantine reported
 *   dependency mute  -> 503 from the client, and nothing is stored. Not being able to
 *                        check is never read as permission to release.
 */
@Component
public class QuarantineStateVerifier {
    static final String CLEAN = "CLEAN";
    private static final Logger log = LoggerFactory.getLogger(QuarantineStateVerifier.class);
    private final QuarantineClient quarantine;

    public QuarantineStateVerifier(QuarantineClient quarantine) {
        this.quarantine = quarantine;
    }

    /**
     * @throws ConflictException                            when the claim contradicts the record
     * @throws QuarantineClient.QuarantineUnreachableException when the record could not be read
     */
    public void requireReleasable(ReleaseRequest claim) {
        QuarantineClient.QuarantineView record = quarantine.findForFile(claim.fileId())
                .orElseThrow(() -> {
                    log.warn("release_rejected fileId={} reason=no_quarantine_record", claim.fileId());
                    return new ConflictException("No quarantine record for this file");
                });
        if (!CLEAN.equals(record.state())) {
            log.warn("release_rejected fileId={} claimed={} verified={}",
                    claim.fileId(), claim.quarantineState(), record.state());
            throw new ConflictException(
                    "Quarantine reports this file as " + record.state() + ", not " + CLEAN);
        }
        if (record.quarantinedAt() == null
                || !sameInstantToTheMillisecond(record.quarantinedAt(), claim.quarantinedAt())) {
            log.warn("release_rejected fileId={} claimedQuarantinedAt={} verifiedQuarantinedAt={}",
                    claim.fileId(), claim.quarantinedAt(), record.quarantinedAt());
            throw new ConflictException("quarantinedAt does not match the quarantine record");
        }
        log.info("release_verified fileId={} quarantineState={} quarantineRecordId={}",
                claim.fileId(), record.state(), record.id());
    }

    /**
     * Compared to the millisecond rather than exactly. A database column stores this stamp
     * with limited fractional precision, so a client that faithfully echoes what the create
     * response returned can differ from a fresh read in the last microseconds; that is
     * rounding, not a forged timestamp, and refusing it would make a legitimate release
     * depend on the host clock. Anything a millisecond apart is still refused.
     */
    private static boolean sameInstantToTheMillisecond(Instant verified, Instant claimed) {
        return verified.truncatedTo(ChronoUnit.MILLIS).equals(claimed.truncatedTo(ChronoUnit.MILLIS));
    }
}

package com.bits.secure.release;

import com.bits.secure.common.ConflictException;
import com.bits.secure.common.NotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

@Service
public class ReleaseService {
    static final String CLEAN = "CLEAN";
    private static final Logger log = LoggerFactory.getLogger(ReleaseService.class);
    private final ReleaseRepository repository;
    private final ReleaseRecordWriter writer;
    private final QuarantineClient quarantine;

    public ReleaseService(ReleaseRepository repository, ReleaseRecordWriter writer,
                          QuarantineClient quarantine) {
        this.repository = repository;
        this.writer = writer;
        this.quarantine = quarantine;
    }

    public ReleaseResponse release(ReleaseRequest request) {
        var prior = repository.findById(request.fileId());
        if (prior.isPresent()) {
            // Replaying a finished release needs no outbound call: the file is already
            // out, and asking Quarantine again could only turn a working dependency's
            // outage into an error for a request that is already answered.
            log.info("release_replay fileId={}", prior.get().getFileId());
            return ReleaseResponse.from(prior.get());
        }
        requireClaimedReleasable(request);
        requireVerifiedReleasable(request);

        // Millisecond precision keeps the stored record byte-identical to the
        // response that created it, so a replay does not differ by rounding.
        ReleaseRecord candidate = new ReleaseRecord(request.fileId(), request.quarantinedAt(),
                Instant.now().truncatedTo(ChronoUnit.MILLIS));
        try {
            ReleaseRecord created = writer.create(candidate);
            log.info("release_created fileId={}", created.getFileId());
            return ReleaseResponse.from(created);
        } catch (DataIntegrityViolationException failure) {
            // Two callers can both observe an unreleased file. The primary key
            // decides the winner; the loser returns that committed record.
            // Unrelated integrity failures are not turned into a replay.
            ReleaseRecord winner = repository.findById(request.fileId()).orElseThrow(() -> failure);
            log.info("release_race_replay fileId={}", winner.getFileId());
            return ReleaseResponse.from(winner);
        }
    }

    public ReleaseResponse find(UUID fileId) {
        return repository.findById(fileId).map(ReleaseResponse::from)
                .orElseThrow(() -> new NotFoundException("File has not been released: " + fileId));
    }

    private void requireClaimedReleasable(ReleaseRequest request) {
        if (!CLEAN.equals(request.quarantineState())) {
            log.warn("release_rejected fileId={} quarantineState={}",
                    request.fileId(), request.quarantineState());
            throw new ConflictException("Only CLEAN quarantined files can be released");
        }
        if (request.quarantinedAt() == null) {
            log.warn("release_rejected fileId={} quarantinedAt=null", request.fileId());
            throw new ConflictException("File must be quarantined before release");
        }
    }

    /**
     * The caller's claim is only a claim. Quarantine owns the state, so the release is
     * granted against what Quarantine's own record says - which is what stops a client from
     * releasing a file by typing CLEAN. A Quarantine that cannot be reached at all is a 503
     * rather than a fall back to trusting the caller: not being able to check is not the
     * same as being allowed to release.
     */
    private void requireVerifiedReleasable(ReleaseRequest request) {
        QuarantineClient.QuarantineView record = quarantine.findForFile(request.fileId())
                .orElseThrow(() -> {
                    log.warn("release_rejected fileId={} reason=no_quarantine_record", request.fileId());
                    return new ConflictException("No quarantine record for this file");
                });
        if (!CLEAN.equals(record.state())) {
            log.warn("release_rejected fileId={} claimed={} verified={}",
                    request.fileId(), request.quarantineState(), record.state());
            throw new ConflictException(
                    "Quarantine reports this file as " + record.state() + ", not " + CLEAN);
        }
        if (record.quarantinedAt() == null
                || !sameInstantToTheMillisecond(record.quarantinedAt(), request.quarantinedAt())) {
            log.warn("release_rejected fileId={} claimedQuarantinedAt={} verifiedQuarantinedAt={}",
                    request.fileId(), request.quarantinedAt(), record.quarantinedAt());
            throw new ConflictException("quarantinedAt does not match the quarantine record");
        }
        log.info("release_verified fileId={} quarantineState={} quarantineRecordId={}",
                request.fileId(), record.state(), record.id());
    }

    /**
     * Compared to the millisecond rather than exactly. A database column stores this stamp
     * with limited fractional precision, so a client that faithfully echoes what the create
     * response returned can differ from a fresh read in the last microseconds; that is
     * rounding, not a forged timestamp, and refusing it would depend on the host clock.
     * Anything a millisecond apart is still refused.
     */
    private static boolean sameInstantToTheMillisecond(Instant verified, Instant claimed) {
        return verified.truncatedTo(ChronoUnit.MILLIS).equals(claimed.truncatedTo(ChronoUnit.MILLIS));
    }
}

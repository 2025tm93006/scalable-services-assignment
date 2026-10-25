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

    public ReleaseService(ReleaseRepository repository, ReleaseRecordWriter writer) {
        this.repository = repository;
        this.writer = writer;
    }

    public ReleaseResponse release(ReleaseRequest request) {
        var prior = repository.findById(request.fileId());
        if (prior.isPresent()) {
            log.info("release_replay fileId={}", prior.get().getFileId());
            return ReleaseResponse.from(prior.get());
        }
        requireReleasable(request);

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

    private void requireReleasable(ReleaseRequest request) {
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
}

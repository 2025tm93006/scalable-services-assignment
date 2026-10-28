package com.bits.secure.quarantine;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Writes quarantine records in their own transaction. The scan flow must not hold a
 * database transaction open across the Scanner call, and the insert needs to reach
 * the database immediately so that the unique fileId index can decide a race between
 * two simultaneous requests for the same file. A rolled-back write here therefore
 * costs nothing but the one statement.
 */
@Component
public class QuarantineRecordWriter {
    private final QuarantineRepository repository;

    public QuarantineRecordWriter(QuarantineRepository repository) {
        this.repository = repository;
    }

    /**
     * @throws DataIntegrityViolationException when another request inserted the same
     * fileId first; the caller turns that into the conflict a client expects.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public QuarantineRecord save(QuarantineRecord record) {
        return repository.saveAndFlush(record);
    }
}

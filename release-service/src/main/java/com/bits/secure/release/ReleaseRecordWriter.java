package com.bits.secure.release;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ReleaseRecordWriter {
    private final ReleaseRepository repository;

    public ReleaseRecordWriter(ReleaseRepository repository) {
        this.repository = repository;
    }

    // A losing primary-key race rolls back here before ReleaseService rereads the winner.
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public ReleaseRecord create(ReleaseRecord record) {
        return repository.saveAndFlush(record);
    }
}

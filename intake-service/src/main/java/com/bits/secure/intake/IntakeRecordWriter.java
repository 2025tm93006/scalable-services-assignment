package com.bits.secure.intake;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class IntakeRecordWriter {
    private final FileRepository repository;

    public IntakeRecordWriter(FileRepository repository) {
        this.repository = repository;
    }

    // A losing unique-key race rolls back here before IntakeService rereads the winner.
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public FileRecord create(FileRecord record) {
        return repository.saveAndFlush(record);
    }
}

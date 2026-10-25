package com.bits.secure.quarantine;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface QuarantineRepository extends JpaRepository<QuarantineRecord, UUID> {
    boolean existsByFileId(UUID fileId);

    long countByState(QuarantineState state);
}
package com.bits.secure.intake;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface FileRepository extends JpaRepository<FileRecord, UUID> {
    Optional<FileRecord> findByIdempotencyKey(String key);

    long countByIdempotencyKey(String key);
}

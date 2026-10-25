package com.bits.secure.scanner;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface ScanAttemptRepository extends JpaRepository<ScanAttempt, Long> {
    Optional<ScanAttempt> findByFileId(UUID fileId);

    long countByResult(String result);

    long countByResultIsNull();
}

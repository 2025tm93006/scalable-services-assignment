package com.bits.secure.quarantine;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface QuarantineRepository extends JpaRepository<QuarantineRecord, UUID> {
    boolean existsByFileId(UUID fileId);

    Optional<QuarantineRecord> findByFileId(UUID fileId);

    long countByState(QuarantineState state);

    /** Records that entered SCANNING before the cutoff, for the reclaim sweep. */
    @Transactional(readOnly = true)
    @Query("select r from QuarantineRecord r where r.state = :state and r.scanStartedAt < :cutoff")
    List<QuarantineRecord> findStale(@Param("state") QuarantineState state, @Param("cutoff") Instant cutoff);

    /**
     * Settles an abandoned scan as FAILED. The state is re-tested inside the update, so
     * a scan that finished between the sweep reading the row and reclaiming it is not
     * overwritten: zero updated rows means the live scan won, which is the answer.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update QuarantineRecord r set r.state = :to, r.scannedAt = :now "
            + "where r.id = :id and r.state = :from and r.scanStartedAt < :cutoff")
    int settleStaleScan(@Param("id") UUID id, @Param("from") QuarantineState from,
                        @Param("to") QuarantineState to, @Param("now") Instant now,
                        @Param("cutoff") Instant cutoff);
}
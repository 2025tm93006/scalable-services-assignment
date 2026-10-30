package com.bits.secure.quarantine;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface ScanSlotRepository extends JpaRepository<ScanSlot, Integer> {

    /**
     * Takes one free slot if and only if it is still free. H2 evaluates the
     * predicate under row-level locking, so of several concurrent callers exactly
     * one sees one updated row and the others see zero and move to the next slot.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update ScanSlot s set s.fileId = :fileId, s.status = 'HELD', s.acquiredAt = :when "
            + "where s.slotNumber = :slotNumber and s.status = 'FREE'")
    int claim(@Param("slotNumber") int slotNumber, @Param("fileId") UUID fileId, @Param("when") Instant when);

    /**
     * Releases the slot only for the file that holds it, so a late release from an
     * abandoned attempt cannot free a slot somebody else has already taken, and a
     * release that runs twice changes nothing the second time.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update ScanSlot s set s.status = 'FREE', s.fileId = null, s.releasedAt = :when "
            + "where s.slotNumber = :slotNumber and s.status = 'HELD' and s.fileId = :fileId")
    int release(@Param("slotNumber") int slotNumber, @Param("fileId") UUID fileId, @Param("when") Instant when);

    /**
     * Frees every slot that has been held since before the cutoff, which is how a scan
     * whose process died mid-call stops blocking the capacity forever. Safe to run
     * twice: the second sweep finds those rows already FREE. The cutoff must be beyond
     * the whole Scanner retry budget, otherwise it would free a slot whose scan can
     * still finish and report its verdict.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update ScanSlot s set s.status = 'FREE', s.fileId = null, s.releasedAt = :when "
            + "where s.status = 'HELD' and s.acquiredAt < :cutoff")
    int releaseStale(@Param("cutoff") Instant cutoff, @Param("when") Instant when);

    long countByStatus(String status);

    Optional<ScanSlot> findByFileId(UUID fileId);
}

package com.bits.secure.quarantine;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Owns the scanner capacity table: the rows are created on startup, one slot is
 * claimed per scan and released when the scan settles. Claiming is one atomic
 * conditional update per slot, so with capacity N a burst of simultaneous scans
 * can never end with more than N running.
 */
@Service
public class ScanCapacityService {
    private static final Logger log = LoggerFactory.getLogger(ScanCapacityService.class);

    private final ScanSlotRepository slots;
    private final int capacity;

    public ScanCapacityService(ScanSlotRepository slots,
                               @Value("${quarantine.max-concurrent-scans:2}") int capacity) {
        if (capacity < 1) throw new IllegalArgumentException("MAX_CONCURRENT_SCANS must be at least 1");
        this.slots = slots;
        this.capacity = capacity;
    }

    /** Idempotent, because the slots now live in a file database that survives a restart. */
    @PostConstruct
    void seedSlots() {
        for (int slotNumber = 1; slotNumber <= capacity; slotNumber++) {
            final int slot = slotNumber;
            slots.findById(slot).orElseGet(() -> slots.save(new ScanSlot(slot)));
        }
        log.info("scanner_capacity_seeded capacity={}", capacity);
    }

    /**
     * Returns the slot this file now owns, or -1 when every slot is taken. Slots are
     * tried in order and each attempt commits on its own, so a rejected request never
     * waits behind a holder.
     */
    public int claim(UUID fileId) {
        for (int slotNumber = 1; slotNumber <= capacity; slotNumber++) {
            if (slots.claim(slotNumber, fileId, Instant.now()) == 1) {
                log.info("scanner_slot_acquired fileId={} slot={} capacity={}", fileId, slotNumber, capacity);
                return slotNumber;
            }
        }
        log.warn("scanner_capacity_rejected fileId={} capacity={}", fileId, capacity);
        return -1;
    }

    public void release(int slotNumber, UUID fileId) {
        int updated = slots.release(slotNumber, fileId, Instant.now());
        log.info("scanner_slot_released fileId={} slot={} rowsUpdated={}", fileId, slotNumber, updated);
    }

    public Optional<ScanSlot> slotHeldBy(UUID fileId) {
        return slots.findByFileId(fileId);
    }

    public long countFree() {
        return slots.countByStatus(ScanSlot.FREE);
    }

    public long countHeld() {
        return slots.countByStatus(ScanSlot.HELD);
    }

    public int capacity() {
        return capacity;
    }
}

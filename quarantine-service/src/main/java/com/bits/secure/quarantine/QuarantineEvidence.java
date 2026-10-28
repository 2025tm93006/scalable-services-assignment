package com.bits.secure.quarantine;

import java.util.Map;

/**
 * Read-only counts for the challenge evidence: how many records sit in each state and
 * how the scanner capacity is allocated. The slot counts are what proves a burst of
 * simultaneous scans never ran more than the configured capacity at a time.
 */
public record QuarantineEvidence(long total, Map<String, Long> byState,
                                 Map<String, Long> scannerSlots, int capacity) {
}

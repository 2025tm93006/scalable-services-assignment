package com.bits.secure.quarantine;

import java.util.Map;

public record QuarantineEvidence(long total, Map<String, Long> byState) {
}
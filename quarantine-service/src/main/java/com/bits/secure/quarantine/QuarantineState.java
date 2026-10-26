package com.bits.secure.quarantine;

/**
 * The quarantine lifecycle. A record leaves QUARANTINED for SCANNING while the
 * Scanner call is in flight and then settles once; a settled state is never
 * transitioned again, which is what makes a repeated scan request refuse to do
 * anything.
 */
public enum QuarantineState {
    QUARANTINED,
    SCANNING,
    CLEAN,
    INFECTED,
    FAILED
}

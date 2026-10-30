package com.bits.secure.quarantine;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.function.LongSupplier;

/**
 * The Challenge 3A bonus: stop hammering a dependency that is known to be down.
 *
 * It counts failed <em>calls</em>, not failed attempts - a call only counts as failed once
 * its whole retry budget was spent, so a Scanner that answers on the second try never
 * moves the breaker. Once open it refuses calls outright, which is what protects both
 * sides: the caller stops paying attempts x read-timeout for a service that is not
 * answering, and Scanner is not woken up by a fresh burst the moment it comes back.
 *
 * After the cooldown one probe is allowed through. Success closes the circuit; failure
 * opens it again for another cooldown, so an outage costs one real call per period rather
 * than one per request.
 *
 * Kept in-house and disabled by default: a library breaker would pull a dependency into a
 * reactor four students build, and the retry evidence is easier to read while every scan
 * really does attempt its calls.
 */
class ScannerCircuitBreaker {
    private static final Logger log = LoggerFactory.getLogger(ScannerCircuitBreaker.class);

    enum State { CLOSED, OPEN, HALF_OPEN }

    private final boolean enabled;
    private final int failureThreshold;
    private final long openMillis;
    private final LongSupplier clock;

    private State state = State.CLOSED;
    private int consecutiveFailedCalls;
    private long openedAt;
    private boolean probeInFlight;

    ScannerCircuitBreaker(boolean enabled, int failureThreshold, long openMillis) {
        this(enabled, failureThreshold, openMillis, System::currentTimeMillis);
    }

    ScannerCircuitBreaker(boolean enabled, int failureThreshold, long openMillis, LongSupplier clock) {
        if (enabled && failureThreshold < 1) {
            throw new IllegalArgumentException("SCANNER_CB_FAILURE_THRESHOLD must be at least 1");
        }
        if (openMillis < 0) {
            throw new IllegalArgumentException("SCANNER_CB_OPEN_MILLIS must not be negative");
        }
        this.enabled = enabled;
        this.failureThreshold = failureThreshold;
        this.openMillis = openMillis;
        this.clock = clock;
    }

    /**
     * Whether this call may reach Scanner. In HALF_OPEN only one call at a time is a
     * probe, so a burst waiting on the cooldown does not all stampede through together.
     */
    synchronized boolean allowCall() {
        if (!enabled) {
            return true;
        }
        if (state == State.OPEN) {
            if (clock.getAsLong() - openedAt < openMillis) {
                return false;
            }
            state = State.HALF_OPEN;
            probeInFlight = false;
            log.info("scanner_circuit_probe_allowed openForMillis={}", clock.getAsLong() - openedAt);
        }
        if (state == State.HALF_OPEN) {
            if (probeInFlight) {
                return false;
            }
            probeInFlight = true;
        }
        return true;
    }

    synchronized void recordSuccess() {
        if (!enabled) {
            return;
        }
        if (state != State.CLOSED) {
            log.info("scanner_circuit_closed afterFailedCalls={}", consecutiveFailedCalls);
        }
        state = State.CLOSED;
        consecutiveFailedCalls = 0;
        probeInFlight = false;
    }

    synchronized void recordFailure() {
        if (!enabled) {
            return;
        }
        if (state == State.HALF_OPEN) {
            log.info("scanner_circuit_probe_failed reopening for {} ms", openMillis);
            open();
            return;
        }
        consecutiveFailedCalls++;
        if (consecutiveFailedCalls >= failureThreshold) {
            open();
        }
    }

    private void open() {
        state = State.OPEN;
        openedAt = clock.getAsLong();
        probeInFlight = false;
        consecutiveFailedCalls = Math.max(consecutiveFailedCalls, failureThreshold);
        log.warn("scanner_circuit_tripped failedCalls={} threshold={} openMillis={}",
                consecutiveFailedCalls, failureThreshold, openMillis);
    }

    synchronized State state() {
        return state;
    }

    synchronized int consecutiveFailedCalls() {
        return consecutiveFailedCalls;
    }

    boolean enabled() {
        return enabled;
    }

    int failureThreshold() {
        return failureThreshold;
    }

    long openMillis() {
        return openMillis;
    }
}

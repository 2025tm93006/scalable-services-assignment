package com.bits.secure.quarantine;

import org.junit.jupiter.api.Test;

import java.util.function.LongSupplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The breaker on its own clock, so the transitions can be tested without waiting for a
 * cooldown. What matters here is that it counts calls rather than attempts, and that an
 * open circuit lets exactly one probe through instead of the whole queue.
 */
class ScannerCircuitBreakerTest {
    private long now = 1_000_000L;
    private final LongSupplier clock = () -> now;

    private ScannerCircuitBreaker breaker(int threshold, long openMillis) {
        return new ScannerCircuitBreaker(true, threshold, openMillis, clock);
    }

    @Test
    void staysClosedWhileFailedCallsAreBelowTheThreshold() {
        ScannerCircuitBreaker breaker = breaker(3, 4000);

        breaker.recordFailure();
        breaker.recordFailure();

        assertEquals(ScannerCircuitBreaker.State.CLOSED, breaker.state());
        assertTrue(breaker.allowCall());
    }

    @Test
    void opensOnceTheThresholdOfConsecutiveFailedCallsIsReached() {
        ScannerCircuitBreaker breaker = breaker(2, 4000);

        breaker.recordFailure();
        breaker.recordFailure();

        assertEquals(ScannerCircuitBreaker.State.OPEN, breaker.state());
        assertFalse(breaker.allowCall(), "an open circuit must refuse the call");
    }

    @Test
    void aSuccessfulCallClearsTheFailureStreak() {
        ScannerCircuitBreaker breaker = breaker(3, 4000);

        breaker.recordFailure();
        breaker.recordFailure();
        breaker.recordSuccess();
        breaker.recordFailure();
        breaker.recordFailure();

        assertEquals(ScannerCircuitBreaker.State.CLOSED, breaker.state(),
                "an intermittent Scanner must not trip the circuit");
    }

    @Test
    void allowsExactlyOneProbeOnceTheCooldownHasPassed() {
        ScannerCircuitBreaker breaker = breaker(1, 1000);
        breaker.recordFailure();
        assertFalse(breaker.allowCall());

        now += 999;
        assertFalse(breaker.allowCall(), "the cooldown is not over yet");

        now += 1;
        assertTrue(breaker.allowCall(), "the first call after the cooldown is the probe");
        assertFalse(breaker.allowCall(), "one probe at a time, so a queue does not stampede");

        assertEquals(ScannerCircuitBreaker.State.HALF_OPEN, breaker.state());
    }

    @Test
    void aFailedProbeReopensTheCircuitWithoutWaitingForTheThreshold() {
        ScannerCircuitBreaker breaker = breaker(3, 1000);
        breaker.recordFailure();
        breaker.recordFailure();
        breaker.recordFailure();

        now += 1000;
        assertTrue(breaker.allowCall());
        breaker.recordFailure();

        assertEquals(ScannerCircuitBreaker.State.OPEN, breaker.state());
        now += 999;
        assertFalse(breaker.allowCall(), "the cooldown starts again from the failed probe");
    }

    @Test
    void aSuccessfulProbeClosesTheCircuitAndLetsEverythingThrough() {
        ScannerCircuitBreaker breaker = breaker(1, 1000);
        breaker.recordFailure();

        now += 1000;
        assertTrue(breaker.allowCall());
        breaker.recordSuccess();

        assertEquals(ScannerCircuitBreaker.State.CLOSED, breaker.state());
        assertEquals(0, breaker.consecutiveFailedCalls());
        assertTrue(breaker.allowCall());
        assertTrue(breaker.allowCall());
    }

    @Test
    void aDisabledBreakerNeverRefusesACall() {
        ScannerCircuitBreaker breaker = new ScannerCircuitBreaker(false, 1, 0, clock);

        for (int i = 0; i < 10; i++) {
            breaker.recordFailure();
            assertTrue(breaker.allowCall(), "with the breaker off every scan must attempt its calls");
        }
        assertEquals(ScannerCircuitBreaker.State.CLOSED, breaker.state());
    }

    @Test
    void anEnabledBreakerWithoutAThresholdIsRejectedAtStartup() {
        assertThrows(IllegalArgumentException.class, () -> new ScannerCircuitBreaker(true, 0, 1000, clock));
        assertThrows(IllegalArgumentException.class, () -> new ScannerCircuitBreaker(true, 3, -1, clock));
    }
}

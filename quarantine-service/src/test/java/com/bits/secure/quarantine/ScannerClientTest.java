package com.bits.secure.quarantine;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.web.client.ResourceAccessException;

import java.net.ConnectException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The retry curve on its own, without waiting for it. Exponential growth is what keeps
 * a slow Scanner from being hammered, and the jitter term is what stops several
 * Quarantine replicas from retrying on the same tick.
 */
class ScannerClientTest {

    @ParameterizedTest(name = "attempt {0} waits at least {1} ms, at most {2} ms")
    @CsvSource({
            // failed attempt, base, jitter bound, expected range
            "1, 100, 50, 100, 150",
            "2, 100, 50, 200, 250",
            "3, 100, 50, 400, 450",
            "4, 100, 50, 800, 850"
    })
    void delayGrowsExponentiallyAndIsBoundedByTheJitter(int failedAttempt, long base,
                                                        long jitterUnit, long min, long max) {
        // Jitter is drawn as 0..jitterUnit, so with jitterUnit = 50 the delay sits in
        // [base * 2^(attempt-1), base * 2^(attempt-1) + 50].
        for (int run = 0; run < 200; run++) {
            long delay = ScannerClient.retryDelayMillis(failedAttempt, base, jitterUnit);
            assertTrue(delay >= min && delay <= max,
                    "attempt " + failedAttempt + " waited " + delay + " ms, expected " + min + ".." + max);
        }
    }

    @Test
    void withoutJitterTheDelayIsExactlyTheExponentialTerm() {
        assertEquals(100, ScannerClient.retryDelayMillis(1, 100, 0));
        assertEquals(1600, ScannerClient.retryDelayMillis(5, 100, 0));
    }

    @Test
    void theExponentIsCappedSoALongOutageCannotOverflowIntoANegativeWait() {
        long delay = ScannerClient.retryDelayMillis(200, 100, 0);
        assertEquals(100L * (1L << 20), delay);
        assertTrue(delay > 0);
    }

    @Test
    void aFailedAttemptIsLoggedWithTheReasonTheEvidenceNeeds() {
        // RestClient's own message repeats the URL and ends in ": null", because the
        // JDK client keeps the reason on the wrapped cause. A log line that says
        // "null" proves nothing, so the root cause is what gets reported.
        ResourceAccessException refused = new ResourceAccessException(
                "I/O error on POST request for \"http://localhost:1/api/v1/scans\": null",
                new ConnectException("Connection refused"));
        assertEquals("I/O error on POST request for \"http://localhost:1/api/v1/scans\" "
                + "(ConnectException: Connection refused)", ScannerClient.reason(refused));

        ResourceAccessException mute = new ResourceAccessException(
                "I/O error on POST request for \"http://localhost:1/api/v1/scans\": null",
                new java.nio.channels.ClosedChannelException());
        assertEquals("I/O error on POST request for \"http://localhost:1/api/v1/scans\" "
                + "(ClosedChannelException)", ScannerClient.reason(mute));

        ResourceAccessException timedOut = new ResourceAccessException(
                "I/O error on POST request for \"http://localhost:1/api/v1/scans\": null",
                new java.net.http.HttpTimeoutException("request timed out"));
        assertEquals("I/O error on POST request for \"http://localhost:1/api/v1/scans\" "
                + "(HttpTimeoutException: request timed out)", ScannerClient.reason(timedOut));

        ResourceAccessException alone = new ResourceAccessException("request timed out");
        assertEquals("ResourceAccessException: request timed out", ScannerClient.reason(alone));
    }

    @Test
    void aRetryBudgetBelowOneAttemptIsRejectedAtStartup() {
        assertThrows(IllegalArgumentException.class, () -> new ScannerClient(
                "http://localhost:1", 10, 10, 0, 10, 10));
        assertThrows(IllegalArgumentException.class, () -> new ScannerClient(
                "http://localhost:1", 10, 10, 3, -1, 10));
        assertThrows(IllegalArgumentException.class, () -> new ScannerClient(
                "http://localhost:1", 10, 10, 3, 10, -1));
    }
}

package com.bits.secure.release;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import com.bits.secure.common.ServiceUnavailableException;
import org.springframework.web.client.ResourceAccessException;

import java.net.ConnectException;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The verification call's retry curve and failure reporting, on their own and without
 * waiting for them. Verification is a read, so repeating it is what keeps a slow Quarantine
 * from turning into a refused release; the bounds are what make that repetition predictable.
 */
class QuarantineClientTest {

    @ParameterizedTest(name = "attempt {0} waits at least {1} ms, at most {2} ms")
    @CsvSource({
            "1, 100, 50, 100, 150",
            "2, 100, 50, 200, 250",
            "3, 100, 50, 400, 450"
    })
    void delayGrowsExponentiallyAndIsBoundedByTheJitter(int failedAttempt, long base,
                                                        long jitterBound, long min, long max) {
        for (int run = 0; run < 200; run++) {
            long delay = QuarantineClient.retryDelayMillis(failedAttempt, base, jitterBound);
            assertTrue(delay >= min && delay <= max,
                    "attempt " + failedAttempt + " waited " + delay + " ms, expected " + min + ".." + max);
        }
    }

    @Test
    void withoutJitterTheDelayIsExactlyTheExponentialTerm() {
        assertEquals(100, QuarantineClient.retryDelayMillis(1, 100, 0));
        assertEquals(800, QuarantineClient.retryDelayMillis(4, 100, 0));
    }

    @Test
    void theExponentIsCappedSoALongOutageCannotOverflowIntoANegativeWait() {
        long delay = QuarantineClient.retryDelayMillis(200, 100, 0);
        assertEquals(100L * (1L << 20), delay);
        assertTrue(delay > 0);
    }

    @Test
    void aFailedReadIsLoggedWithTheCauseAndNotAnEmptyMessage() {
        ResourceAccessException refused = new ResourceAccessException(
                "I/O error on GET request for \"http://localhost:1/api/v1/quarantine/by-file/x\": null",
                new ConnectException("Connection refused"));

        assertEquals("I/O error on GET request for \"http://localhost:1/api/v1/quarantine/by-file/x\" "
                + "(ConnectException: Connection refused)", QuarantineClient.reason(refused));
    }

    @Test
    void anUnreachableQuarantineIsADependencyOutageNotAServerBug() {
        QuarantineClient.QuarantineUnreachableException outage =
                new QuarantineClient.QuarantineUnreachableException(
                        "Quarantine did not answer after 2 attempts", null);

        assertInstanceOf(ServiceUnavailableException.class, outage,
                "so the shared error handler answers 503");
    }

    @Test
    void anUnusableVerificationBudgetIsRejectedAtStartup() {
        assertThrows(IllegalArgumentException.class,
                () -> new QuarantineClient("http://localhost:1", 10, 10, 0, 10, 10));
        assertThrows(IllegalArgumentException.class,
                () -> new QuarantineClient("http://localhost:1", 10, 10, 3, -1, 10));
        assertThrows(IllegalArgumentException.class,
                () -> new QuarantineClient("http://localhost:1", 10, 10, 3, 10, -1));
    }
}

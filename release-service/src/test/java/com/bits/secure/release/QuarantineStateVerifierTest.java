package com.bits.secure.release;

import com.bits.secure.common.ConflictException;
import com.bits.secure.common.ServiceUnavailableException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * The verifier on its own: what makes a claim acceptable, and what each kind of answer
 * from Quarantine means. Release's own tests cover when the verifier gets asked at all;
 * these cover what it decides once it is asked.
 */
@ExtendWith(MockitoExtension.class)
class QuarantineStateVerifierTest {
    private static final Instant QUARANTINED_AT = Instant.parse("2026-10-24T08:00:00Z");
    @Mock private QuarantineClient quarantine;
    private final UUID fileId = UUID.randomUUID();
    private QuarantineStateVerifier verifier;

    @BeforeEach
    void setUp() {
        verifier = new QuarantineStateVerifier(quarantine);
    }

    @Test
    void aRecordReportingCleanWithTheSameTimestampIsAccepted() {
        when(quarantine.findForFile(fileId)).thenReturn(Optional.of(record("CLEAN", QUARANTINED_AT)));

        verifier.requireReleasable(claim("CLEAN", QUARANTINED_AT));

        verify(quarantine).findForFile(fileId);
    }

    @Test
    void aMissingRecordIsABusinessConflictNotAnError() {
        when(quarantine.findForFile(fileId)).thenReturn(Optional.empty());

        ConflictException refusal = assertThrows(ConflictException.class,
                () -> verifier.requireReleasable(claim("CLEAN", QUARANTINED_AT)));

        assertEquals("No quarantine record for this file", refusal.getMessage());
    }

    @Test
    void everyNonCleanStateIsRefusedAndTheMessageNamesWhatWasFound() {
        for (String state : new String[] {"QUARANTINED", "SCANNING", "INFECTED", "FAILED"}) {
            reset(quarantine);
            when(quarantine.findForFile(fileId)).thenReturn(Optional.of(record(state, QUARANTINED_AT)));

            ConflictException refusal = assertThrows(ConflictException.class,
                    () -> verifier.requireReleasable(claim("CLEAN", QUARANTINED_AT)),
                    state + " must not be releasable");

            assertTrue(refusal.getMessage().contains(state), refusal.getMessage());
        }
    }

    @Test
    void aClaimOneMillisecondOffIsRefused() {
        when(quarantine.findForFile(fileId)).thenReturn(Optional.of(record("CLEAN", QUARANTINED_AT)));

        ConflictException refusal = assertThrows(ConflictException.class,
                () -> verifier.requireReleasable(claim("CLEAN", QUARANTINED_AT.plusMillis(1))));

        assertEquals("quarantinedAt does not match the quarantine record", refusal.getMessage());
    }

    @Test
    void subMicrosecondRoundingBetweenStoreAndReadIsStillTheSameInstant() {
        // The record came out of a column with limited fractional precision; the claim came
        // from the create response. Refusing that would make a release depend on the host clock.
        when(quarantine.findForFile(fileId))
                .thenReturn(Optional.of(record("CLEAN", QUARANTINED_AT.plusNanos(900))));

        verifier.requireReleasable(claim("CLEAN", QUARANTINED_AT));
    }

    @Test
    void aRecordWithoutATimestampCannotProveAnything() {
        when(quarantine.findForFile(fileId)).thenReturn(Optional.of(record("CLEAN", null)));

        assertThrows(ConflictException.class, () -> verifier.requireReleasable(claim("CLEAN", QUARANTINED_AT)));
    }

    @Test
    void anUnreachableQuarantineStaysADependencyOutage() {
        when(quarantine.findForFile(fileId)).thenThrow(
                new QuarantineClient.QuarantineUnreachableException(
                        "Quarantine did not answer after 3 attempts", null));

        // Not turned into a 409: the claim was never tested, so nothing was decided about it.
        assertThrows(ServiceUnavailableException.class,
                () -> verifier.requireReleasable(claim("CLEAN", QUARANTINED_AT)));
    }

    private ReleaseRequest claim(String state, Instant quarantinedAt) {
        return new ReleaseRequest(fileId, state, quarantinedAt);
    }

    private QuarantineClient.QuarantineView record(String state, Instant quarantinedAt) {
        return new QuarantineClient.QuarantineView(UUID.randomUUID(), fileId, state, "CLEAN",
                quarantinedAt, quarantinedAt == null ? null : quarantinedAt.plusSeconds(30));
    }
}

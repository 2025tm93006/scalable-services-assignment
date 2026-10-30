package com.bits.secure.release;

import com.bits.secure.common.CorrelationIdFilter;
import com.bits.secure.common.ServiceUnavailableException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.net.http.HttpClient;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Reads a file's quarantine record from the service that owns it.
 *
 * Release used to take the caller's word for the state, which meant any client could
 * release anything by typing CLEAN. Quarantine is now the authority, and this is the only
 * way Release finds out what it said. It is a read, so repeating it is safe, and it gets
 * the same treatment the Scanner call has: connect and read timeouts, a bounded number of
 * attempts and exponential backoff with jitter.
 *
 * A 404 is an answer rather than a failure - the file was never quarantined - so it comes
 * back as an empty Optional. Only a dependency that never answers at all is an outage.
 */
@Component
public class QuarantineClient {
    private static final Logger log = LoggerFactory.getLogger(QuarantineClient.class);

    private final RestClient restClient;
    private final int maxAttempts;
    private final long retryBaseMillis;
    private final long retryJitterMillis;

    public QuarantineClient(
            @Value("${quarantine.base-url:http://localhost:8082}") String baseUrl,
            @Value("${quarantine.connect-timeout-millis:300}") int connectTimeoutMillis,
            @Value("${quarantine.read-timeout-millis:2000}") int readTimeoutMillis,
            @Value("${quarantine.max-attempts:3}") int maxAttempts,
            @Value("${quarantine.retry-base-millis:100}") long retryBaseMillis,
            @Value("${quarantine.retry-jitter-millis:100}") long retryJitterMillis) {
        if (maxAttempts < 1) throw new IllegalArgumentException("QUARANTINE_MAX_ATTEMPTS must be at least 1");
        if (retryBaseMillis < 0) throw new IllegalArgumentException("QUARANTINE_RETRY_BASE_MILLIS must not be negative");
        if (retryJitterMillis < 0) throw new IllegalArgumentException("QUARANTINE_RETRY_JITTER_MILLIS must not be negative");
        var httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofMillis(connectTimeoutMillis))
                .build();
        var requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(Duration.ofMillis(readTimeoutMillis));
        this.restClient = RestClient.builder().baseUrl(baseUrl).requestFactory(requestFactory).build();
        this.maxAttempts = maxAttempts;
        this.retryBaseMillis = retryBaseMillis;
        this.retryJitterMillis = retryJitterMillis;
        log.info("quarantine_client_configured baseUrl={} connectTimeoutMillis={} readTimeoutMillis={} "
                        + "maxAttempts={} retryBaseMillis={} retryJitterMillis={}",
                baseUrl, connectTimeoutMillis, readTimeoutMillis, maxAttempts, retryBaseMillis, retryJitterMillis);
    }

    /**
     * @return the record Quarantine holds for this file, or empty when it never quarantined it
     * @throws QuarantineUnreachableException when the whole retry budget was spent
     */
    public Optional<QuarantineView> findForFile(UUID fileId) {
        RestClientException lastFailure = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                log.info("quarantine_read fileId={} attempt={} maxAttempts={}", fileId, attempt, maxAttempts);
                QuarantineView view = restClient.get()
                        .uri("/api/v1/quarantine/by-file/{fileId}", fileId)
                        .headers(headers -> {
                            String correlationId = MDC.get("correlationId");
                            if (correlationId != null) headers.set(CorrelationIdFilter.HEADER, correlationId);
                        })
                        .retrieve()
                        .body(QuarantineView.class);
                return Optional.ofNullable(view);
            } catch (HttpClientErrorException.NotFound neverQuarantined) {
                log.info("quarantine_record_absent fileId={}", fileId);
                return Optional.empty();
            } catch (RestClientException failure) {
                lastFailure = failure;
                log.warn("quarantine_read_failed fileId={} attempt={} error={}", fileId, attempt, reason(failure));
                if (attempt < maxAttempts) {
                    long delay = retryDelayMillis(attempt, retryBaseMillis, retryJitterMillis);
                    log.info("quarantine_retry fileId={} nextAttempt={} delayMillis={}", fileId, attempt + 1, delay);
                    sleep(delay, fileId);
                }
            }
        }
        throw new QuarantineUnreachableException(
                "Quarantine did not answer after " + maxAttempts + " attempts", lastFailure);
    }

    /** Visible for the unit test that has to assert the curve without waiting for it. */
    static long retryDelayMillis(int failedAttempt, long baseMillis, long jitterMillis) {
        long exponential = baseMillis * (1L << Math.min(failedAttempt - 1, 20));
        long jitter = jitterMillis == 0 ? 0 : ThreadLocalRandom.current().nextLong(jitterMillis + 1L);
        return exponential + jitter;
    }

    /** Same reporting rule the Scanner client follows: name the cause, never print "null". */
    static String reason(RestClientException failure) {
        String request = trim(failure.getMessage());
        Throwable root = failure;
        while (root.getCause() != null) {
            root = root.getCause();
        }
        String detail = trim(root.getMessage());
        String cause = detail == null
                ? root.getClass().getSimpleName()
                : root.getClass().getSimpleName() + ": " + detail;
        if (root == failure) {
            return cause;
        }
        return request == null || request.isBlank() ? cause : request + " (" + cause + ")";
    }

    private static String trim(String message) {
        String text = message;
        while (text != null && text.endsWith(": null")) {
            text = text.substring(0, text.length() - 6);
        }
        return text;
    }

    private static void sleep(long millis, UUID fileId) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new QuarantineUnreachableException("Quarantine verification interrupted", interrupted);
        }
    }

    /** The fields of Quarantine's own response, read as data and never stored as ownership. */
    public record QuarantineView(UUID id, UUID fileId, String state, String scannerMode,
                                 Instant quarantinedAt, Instant scannedAt) {
    }

    /** Thrown when Quarantine stayed unreachable for the whole budget: the caller gets 503. */
    public static class QuarantineUnreachableException extends ServiceUnavailableException {
        public QuarantineUnreachableException(String message) {
            super(message);
        }

        public QuarantineUnreachableException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}

package com.bits.secure.quarantine;

import com.bits.secure.common.CorrelationIdFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.net.http.HttpClient;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * The only place Quarantine talks to another service. Scanner is a dependency that
 * can be slow or stopped, so every call has a connect and read timeout and is
 * retried with exponential backoff plus jitter. Scanner keys its result on the
 * fileId, so a retry after a timeout replays the scan that is already running
 * instead of starting a second one - which is what makes these retries safe.
 *
 * The timeouts are enforced by the JDK HTTP client rather than by
 * SimpleClientHttpRequestFactory: a read timeout on that implementation leaves the
 * pooled keep-alive connection half-read, and the next attempt then fails while
 * parsing a response that the downstream service actually wrote correctly. A timeout
 * must look like a timeout, otherwise the retry log lies about what happened.
 */
@Component
public class ScannerClient {
    private static final Logger log = LoggerFactory.getLogger(ScannerClient.class);

    private final RestClient restClient;
    private final int maxAttempts;
    private final long retryBaseMillis;
    private final long retryJitterMillis;

    public ScannerClient(
            @Value("${scanner.base-url:http://localhost:8083}") String baseUrl,
            @Value("${scanner.connect-timeout-millis:300}") int connectTimeoutMillis,
            @Value("${scanner.read-timeout-millis:5000}") int readTimeoutMillis,
            @Value("${scanner.max-attempts:3}") int maxAttempts,
            @Value("${scanner.retry-base-millis:100}") long retryBaseMillis,
            @Value("${scanner.retry-jitter-millis:100}") long retryJitterMillis) {
        if (maxAttempts < 1) throw new IllegalArgumentException("SCANNER_MAX_ATTEMPTS must be at least 1");
        if (retryBaseMillis < 0) throw new IllegalArgumentException("SCANNER_RETRY_BASE_MILLIS must not be negative");
        if (retryJitterMillis < 0) throw new IllegalArgumentException("SCANNER_RETRY_JITTER_MILLIS must not be negative");
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
        log.info("scanner_client_configured baseUrl={} connectTimeoutMillis={} readTimeoutMillis={} "
                        + "maxAttempts={} retryBaseMillis={} retryJitterMillis={}",
                baseUrl, connectTimeoutMillis, readTimeoutMillis, maxAttempts, retryBaseMillis, retryJitterMillis);
    }

    /**
     * Posts the scan request and returns Scanner's verdict. Any failed attempt is
     * retried, including Scanner's own 409 for a scan that is still in flight.
     *
     * @throws ScannerUnavailableException when all attempts failed
     */
    public ScanResult scan(UUID fileId, String scannerMode) {
        RestClientException lastFailure = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                log.info("scanner_call fileId={} attempt={} maxAttempts={} mode={}",
                        fileId, attempt, maxAttempts, scannerMode);
                return restClient.post()
                        .uri("/api/v1/scans")
                        .headers(headers -> {
                            String correlationId = MDC.get("correlationId");
                            if (correlationId != null) headers.set(CorrelationIdFilter.HEADER, correlationId);
                        })
                        .body(new ScanRequest(fileId, scannerMode))
                        .retrieve()
                        .body(ScanResult.class);
            } catch (RestClientException failure) {
                lastFailure = failure;
                log.warn("scanner_attempt_failed fileId={} attempt={} error={}",
                        fileId, attempt, reason(failure));
                if (attempt < maxAttempts) {
                    long delay = retryDelayMillis(attempt, retryBaseMillis, retryJitterMillis);
                    log.info("scanner_retry fileId={} nextAttempt={} delayMillis={}", fileId, attempt + 1, delay);
                    sleep(delay, fileId);
                }
            }
        }
        throw new ScannerUnavailableException(
                "Scanner did not answer after " + maxAttempts + " attempts", lastFailure);
    }

    /**
     * Exponential growth on the failed attempt number plus jitter, so replicas that
     * failed at the same instant do not all retry on the same tick. Visible for the
     * unit test that has to assert the curve without waiting for it.
     */
    static long retryDelayMillis(int failedAttempt, long baseMillis, long jitterMillis) {
        long exponential = baseMillis * (1L << Math.min(failedAttempt - 1, 20));
        long jitter = jitterMillis == 0 ? 0 : ThreadLocalRandom.current().nextLong(jitterMillis + 1L);
        return exponential + jitter;
    }

    /**
     * A failed attempt has to be explainable from the log alone, and neither part of
     * RestClient's report is enough on its own: its own message ends in ": null"
     * because the JDK client keeps the reason on the cause, while causes such as a
     * refused connection are reported as a bare exception class. So the line carries
     * the request and the root cause, with any ": null" tail dropped.
     * Visible for the unit test.
     */
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
            throw new ScannerUnavailableException("Scanner retry interrupted", interrupted);
        }
    }

    /** Thrown when the downstream Scanner service stayed unreachable for the whole budget. */
    public static class ScannerUnavailableException extends RuntimeException {
        public ScannerUnavailableException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    record ScanRequest(UUID fileId, String mode) {
    }

    /** Field names match the Scanner service response contract. */
    public record ScanResult(UUID fileId, String result, String message, Instant completedAt) {
    }
}

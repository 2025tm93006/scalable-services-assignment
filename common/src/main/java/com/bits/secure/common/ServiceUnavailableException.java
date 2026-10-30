package com.bits.secure.common;

/**
 * The service itself is working, but a dependency it needs in order to answer is not, and
 * it refuses to guess. Returned as 503 rather than 500 because nothing broke here: the
 * answer is simply unavailable until the dependency answers again, and the same request
 * may well succeed later.
 */
public class ServiceUnavailableException extends RuntimeException {
    public ServiceUnavailableException(String message) {
        super(message);
    }

    public ServiceUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}

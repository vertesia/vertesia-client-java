package io.vertesia;

/**
 * Raised when the Vertesia SDK facade cannot be configured or authenticated.
 */
public class VertesiaClientException extends RuntimeException {
    public VertesiaClientException(String message) {
        super(message);
    }

    public VertesiaClientException(String message, Throwable cause) {
        super(message, cause);
    }
}

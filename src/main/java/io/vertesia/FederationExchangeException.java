package io.vertesia;

/** Sanitized token-server failure with programmatically inspectable OAuth metadata. */
public class FederationExchangeException extends VertesiaClientException {
    private final int statusCode;
    private final String errorCode;

    FederationExchangeException(int statusCode, String errorCode) {
        super(
                "federation exchange failed: HTTP "
                        + statusCode
                        + (errorCode == null ? "" : " (" + errorCode + ")"));
        this.statusCode = statusCode;
        this.errorCode = errorCode;
    }

    public int getStatusCode() {
        return statusCode;
    }

    public String getErrorCode() {
        return errorCode;
    }
}

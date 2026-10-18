package io.vertesia;

/** One-shot exchange result with conservative remaining expiresIn seconds. No refresh token is issued. */
public record FederationToken(String accessToken, int expiresIn, String scope) {
    @Override
    public String toString() {
        return "FederationToken[accessToken redacted, expiresIn=" + expiresIn + "]";
    }
}

#!/usr/bin/env python3
"""Patch generated Java client code for repository security policy.

OpenAPI Generator's Java ApiClient can emit support for disabling TLS
verification. This client intentionally does not support that insecure mode;
use setSslCaCert for private CAs instead.

The client is generated for bearer authentication only, which leaves
setAccessToken throwing; it is patched to keep the access token as a fallback
bearer token, which a token from setBearerToken overrides.
"""

from __future__ import annotations

import pathlib
import re


API_CLIENT = pathlib.Path("src/main/java/io/vertesia/ApiClient.java")

CERTIFICATE_EXCEPTION_IMPORT = "import java.security.cert.CertificateException;\n"

SET_VERIFYING_SSL_OLD = re.compile(
    r"""    /\*\*
     \* Configure whether to verify certificate and hostname when making https requests\.
     \* Default to true\.
     \* NOTE: Do NOT set to false in production code, otherwise you would face multiple types of cryptographic attacks\.
     \*
     \* @param verifyingSsl True to verify TLS/SSL connection
     \* @return ApiClient
     \*/
    public ApiClient setVerifyingSsl\(boolean verifyingSsl\) \{
        this\.verifyingSsl = verifyingSsl;
        applySslSettings\(\);
        return this;
    \}
"""
)

SET_VERIFYING_SSL_NEW = """    /**
     * Configure whether to verify certificate and hostname when making https requests. Default to true.
     * Disabling TLS verification is not supported. To trust a private certificate authority, use
     * {@link #setSslCaCert(InputStream)}.
     *
     * @param verifyingSsl must be true to verify TLS/SSL connections
     * @return ApiClient
     * @throws IllegalArgumentException if verifyingSsl is false
     */
    public ApiClient setVerifyingSsl(boolean verifyingSsl) {
        if (!verifyingSsl) {
            throw new IllegalArgumentException(
                    "Disabling TLS/SSL verification is not supported. Use setSslCaCert(...) to trust a custom CA certificate.");
        }
        this.verifyingSsl = true;
        applySslSettings();
        return this;
    }
"""

APPLY_SSL_SETTINGS_START = re.compile(
    r"""    protected void applySslSettings\(\) \{
        try \{
(?P<body>.*?)

            SSLContext sslContext = SSLContext\.getInstance\("TLS"\);
""",
    re.DOTALL,
)

APPLY_SSL_SETTINGS_SAFE_START = """    protected void applySslSettings() {
        try {
            if (!verifyingSsl) {
                throw new IllegalStateException("Disabling TLS/SSL verification is not supported.");
            }

            TrustManager[] trustManagers;
            HostnameVerifier hostnameVerifier;

            TrustManagerFactory trustManagerFactory =
                    TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());

            if (sslCaCert == null) {
                trustManagerFactory.init((KeyStore) null);
            } else {
                char[] password = null; // Any password will work.
                CertificateFactory certificateFactory = CertificateFactory.getInstance("X.509");
                Collection<? extends Certificate> certificates =
                        certificateFactory.generateCertificates(sslCaCert);
                if (certificates.isEmpty()) {
                    throw new IllegalArgumentException(
                            "expected non-empty set of trusted certificates");
                }
                KeyStore caKeyStore = newEmptyKeyStore(password);
                int index = 0;
                for (Certificate certificate : certificates) {
                    String certificateAlias = "ca" + (index++);
                    caKeyStore.setCertificateEntry(certificateAlias, certificate);
                }
                trustManagerFactory.init(caKeyStore);
            }
            trustManagers = trustManagerFactory.getTrustManagers();
            if (tlsServerName != null && !tlsServerName.isEmpty()) {
                hostnameVerifier =
                        new HostnameVerifier() {
                            @Override
                            public boolean verify(String hostname, SSLSession session) {
                                // Verify the certificate against tlsServerName instead of the
                                // actual hostname
                                return OkHostnameVerifier.INSTANCE.verify(tlsServerName, session);
                            }
                        };
            } else {
                hostnameVerifier = OkHostnameVerifier.INSTANCE;
            }

            SSLContext sslContext = SSLContext.getInstance("TLS");
"""


def patch_set_verifying_ssl(text: str) -> tuple[str, bool]:
    if SET_VERIFYING_SSL_NEW in text:
        return text, False

    patched, count = SET_VERIFYING_SSL_OLD.subn(SET_VERIFYING_SSL_NEW, text, count=1)
    if count != 1:
        raise RuntimeError("Could not patch ApiClient.setVerifyingSsl")
    return patched, True


def patch_apply_ssl_settings(text: str) -> tuple[str, bool]:
    if APPLY_SSL_SETTINGS_SAFE_START in text:
        return text, False

    match = APPLY_SSL_SETTINGS_START.search(text)
    if not match:
        raise RuntimeError("Could not locate ApiClient.applySslSettings")

    body = match.group("body")
    if "new TrustManager[]" not in body or "return true;" not in body:
        raise RuntimeError("ApiClient.applySslSettings did not match expected insecure TLS shape")

    patched = text[: match.start()] + APPLY_SSL_SETTINGS_SAFE_START + text[match.end() :]
    return patched, True


SET_ACCESS_TOKEN_WITHOUT_OAUTH = re.compile(
    r"""    /\*\*
     \* Helper method to set access token for the first OAuth2 authentication\.
     \*
     \* @param accessToken Access token
     \*/
    public void setAccessToken\(String accessToken\) \{
        throw new RuntimeException\("No OAuth2 authentication configured!"\);
    \}
"""
)

SET_ACCESS_TOKEN_FALLBACK = """    /**
     * Access token set by {@link #setAccessToken(String)}, sent when no bearer token is configured.
     */
    private String fallbackAccessToken;

    /**
     * Helper method to set the access token sent as {@code Authorization: Bearer <token>} on authenticated
     * requests. A token from {@link #setBearerToken(String)} or {@link #setBearerToken(Supplier)} takes
     * precedence: the access token is sent only when no bearer token is set or the supplier returns null.
     *
     * @param accessToken Access token, or null to clear it
     */
    public void setAccessToken(String accessToken) {
        this.fallbackAccessToken = accessToken;
    }
"""

UPDATE_PARAMS_FOR_AUTH_LOOP = re.compile(
    r"(    public void updateParamsForAuth\([^)]*\) throws ApiException \{\n)"
    r"(        for \(String authName : authNames\) \{\n)"
)

UPDATE_PARAMS_FOR_AUTH_FALLBACK = r"""\1        if (fallbackAccessToken != null && authNames.length > 0) {
            // Applied before the security schemes, so a bearer token from setBearerToken overrides it.
            headerParams.put("Authorization", "Bearer " + fallbackAccessToken);
        }
\2"""

FALLBACK_MARKERS = (
    "private String fallbackAccessToken;",
    "if (fallbackAccessToken != null && authNames.length > 0) {",
)


def patch_set_access_token(text: str) -> tuple[str, bool]:
    """Keep ApiClient.setAccessToken working when the client is generated without OAuth.

    The generator emits setAccessToken either way; without an OAuth security scheme its body only throws.
    Callers used it to send a bearer token through the OAuth2 scheme, which every operation applied before
    bearerAuth: a token from setBearerToken (or a supplier returning non-null) won, and the access token was
    the fallback. Keep that order by storing the access token and applying it before the security schemes.
    """
    present = [marker in text for marker in FALLBACK_MARKERS]
    if all(present):
        return text, False
    if any(present):
        raise RuntimeError("ApiClient.setAccessToken is partly patched")
    patched, count = SET_ACCESS_TOKEN_WITHOUT_OAUTH.subn(SET_ACCESS_TOKEN_FALLBACK, text)
    if count == 1:
        patched, count = UPDATE_PARAMS_FOR_AUTH_LOOP.subn(UPDATE_PARAMS_FOR_AUTH_FALLBACK, patched)
        if count != 1:
            raise RuntimeError("ApiClient.updateParamsForAuth did not match the expected shape")
        return patched, True
    if "((OAuth) auth).setAccessToken(accessToken);" in text:
        # Generated with an OAuth scheme: the method already sends the token, with the same precedence.
        return text, False
    raise RuntimeError("ApiClient.setAccessToken did not match an expected shape")


def main() -> None:
    text = API_CLIENT.read_text()
    patched = text.replace(CERTIFICATE_EXCEPTION_IMPORT, "")
    changed = patched != text

    patched, set_changed = patch_set_verifying_ssl(patched)
    patched, apply_changed = patch_apply_ssl_settings(patched)
    patched, token_changed = patch_set_access_token(patched)
    changed = changed or set_changed or apply_changed or token_changed

    if changed:
        API_CLIENT.write_text(patched)

    state = "changed" if changed else "already current"
    print(f"Patched generated ApiClient security settings: {state}.")


if __name__ == "__main__":
    main()

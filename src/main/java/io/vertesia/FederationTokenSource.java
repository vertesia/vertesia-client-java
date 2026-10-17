package io.vertesia;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.net.URI;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import okhttp3.FormBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

final class FederationTokenSource implements TokenSource {
    private final FederationOptions options;
    private final String endpoint;
    private final OkHttpClient client;
    private final LongSupplier clock;
    private String token = "";
    private long renewAt;

    FederationTokenSource(
            FederationOptions options, String endpoint, OkHttpClient client, LongSupplier clock) {
        if (options.subjectTokenProvider() == null)
            throw new VertesiaClientException("federation requires subjectTokenProvider");
        this.options = options;
        this.endpoint = endpoint;
        this.client = client;
        this.clock = clock;
    }

    static String endpoint(FederationOptions f, String base, boolean safeBase) {
        if (empty(f.connectionId()) || empty(f.projectId()) || empty(f.resource()))
            throw new VertesiaClientException(
                    "federation requires connectionId, projectId and resource");
        if ((f.clientSecretBasic() && empty(f.clientSecret()))
                || (!empty(f.clientSecret()) || f.clientAssertionProvider() != null)
                        != !empty(f.clientId())
                || (!empty(f.clientSecret()) && f.clientAssertionProvider() != null))
            throw new VertesiaClientException(
                    "client authentication requires clientId and exactly one credential method");
        if (empty(f.tokenEndpoint()) && !safeBase)
            throw new VertesiaClientException(
                    "explicit federation tokenServerUrl or tokenEndpoint required for custom URLs");
        String endpoint = empty(f.tokenEndpoint()) ? base + "/oauth/token" : f.tokenEndpoint();
        try {
            URI uri = URI.create(endpoint);
            String host = uri.getHost();
            boolean local =
                    "localhost".equals(host) || "127.0.0.1".equals(host) || "[::1]".equals(host);
            if (host == null
                    || uri.getUserInfo() != null
                    || uri.getRawQuery() != null
                    || uri.getRawFragment() != null
                    || !("https".equals(uri.getScheme())
                            || ("http".equals(uri.getScheme()) && local)))
                throw new IllegalArgumentException();
        } catch (IllegalArgumentException e) {
            throw new VertesiaClientException(
                    "federation endpoint must be HTTPS (or loopback HTTP) without credentials/query/fragment");
        }
        return endpoint;
    }

    static FederationToken exchange(
            FederationOptions f, String endpoint, OkHttpClient base, String assertion) {
        if (empty(assertion)) throw new VertesiaClientException("empty IdP assertion");
        FormBody.Builder form =
                new FormBody.Builder()
                        .add("grant_type", "urn:ietf:params:oauth:grant-type:token-exchange")
                        .add("subject_token", assertion)
                        .add(
                                "subject_token_type",
                                empty(f.subjectTokenType())
                                        ? "urn:ietf:params:oauth:token-type:jwt"
                                        : f.subjectTokenType())
                        .add("federation_connection_id", f.connectionId())
                        .add("project_id", f.projectId())
                        .add("resource", f.resource());
        if (!empty(f.scope())) form.add("scope", f.scope());
        if (!empty(f.clientId()) && !f.clientSecretBasic()) form.add("client_id", f.clientId());
        if (!empty(f.clientSecret()) && !f.clientSecretBasic())
            form.add("client_secret", f.clientSecret());
        if (f.clientAssertionProvider() != null) {
            String proof;
            try {
                proof = f.clientAssertionProvider().get();
            } catch (RuntimeException e) {
                throw new VertesiaClientException("client assertion provider failed");
            }
            if (empty(proof)) throw new VertesiaClientException("empty client assertion");
            form.add("client_assertion", proof)
                    .add(
                            "client_assertion_type",
                            "urn:ietf:params:oauth:client-assertion-type:jwt-bearer");
        }
        Request.Builder requestBuilder =
                new Request.Builder()
                        .url(endpoint)
                        .post(form.build())
                        .header("Accept", "application/json");
        if (f.clientSecretBasic()) {
            String credential =
                    java.net.URLEncoder.encode(
                                    f.clientId(), java.nio.charset.StandardCharsets.UTF_8)
                            + ":"
                            + java.net.URLEncoder.encode(
                                    f.clientSecret(), java.nio.charset.StandardCharsets.UTF_8);
            requestBuilder.header(
                    "Authorization",
                    "Basic "
                            + java.util.Base64.getEncoder()
                                    .encodeToString(
                                            credential.getBytes(
                                                    java.nio.charset.StandardCharsets.UTF_8)));
        }
        Request request = requestBuilder.build();
        OkHttpClient client =
                base.newBuilder()
                        .followRedirects(false)
                        .followSslRedirects(false)
                        .callTimeout(60, TimeUnit.SECONDS)
                        .build();
        try (Response response = client.newCall(request).execute()) {
            if (response.code() != 200)
                throw new VertesiaClientException(
                        "federation exchange failed: HTTP " + response.code());
            if (response.body() == null)
                throw new VertesiaClientException("invalid federation response");
            byte[] bytes = response.body().byteStream().readNBytes(65537);
            if (bytes.length > 65536)
                throw new VertesiaClientException("invalid federation response");
            try {
                JsonObject json =
                        JsonParser.parseString(
                                        new String(bytes, java.nio.charset.StandardCharsets.UTF_8))
                                .getAsJsonObject();
                if (!json.getAsJsonPrimitive("access_token").isString()
                        || empty(json.get("access_token").getAsString())
                        || !json.getAsJsonPrimitive("expires_in").isNumber()
                        || !"Bearer".equals(json.get("token_type").getAsString())
                        || !"urn:ietf:params:oauth:token-type:access_token"
                                .equals(json.get("issued_token_type").getAsString()))
                    throw new IllegalArgumentException();
                int ttl = json.get("expires_in").getAsBigDecimal().intValueExact();
                if (ttl < 1 || ttl > 300) throw new IllegalArgumentException();
                return new FederationToken(
                        json.get("access_token").getAsString(),
                        ttl,
                        json.has("scope") ? json.get("scope").getAsString() : null);
            } catch (RuntimeException e) {
                throw new VertesiaClientException("invalid federation token response");
            }
        } catch (IOException e) {
            throw new VertesiaClientException("federation exchange request failed");
        }
    }

    @Override
    public synchronized String get() {
        long now = clock.getAsLong();
        if (!token.isEmpty() && now < renewAt) return token;
        String assertion;
        try {
            assertion = options.subjectTokenProvider().get();
        } catch (RuntimeException e) {
            throw new VertesiaClientException("IdP token provider failed");
        }
        FederationToken issued = exchange(options, endpoint, client, assertion);
        long ttl = TimeUnit.SECONDS.toNanos(issued.expiresIn());
        long next = now + ttl - ttl / 10;
        if (clock.getAsLong() >= next)
            throw new VertesiaClientException("federation token expired during exchange");
        token = issued.accessToken();
        renewAt = next;
        return token;
    }

    private static boolean empty(String value) {
        return value == null || value.isBlank();
    }
}

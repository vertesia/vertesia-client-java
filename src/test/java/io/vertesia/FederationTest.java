package io.vertesia;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import okhttp3.OkHttpClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class FederationTest {
    private HttpServer server;
    private String base;
    private volatile String response;
    private volatile int status;
    private volatile int apiStatus = 200;
    private final List<Integer> ports = new ArrayList<>();
    private final List<String> forms = java.util.Collections.synchronizedList(new ArrayList<>());
    private final List<String> headers = java.util.Collections.synchronizedList(new ArrayList<>());
    private final List<String> stsHeaders =
            java.util.Collections.synchronizedList(new ArrayList<>());
    private final AtomicInteger providers = new AtomicInteger();
    private FederationOptions federation;
    private ClientOptions options;
    private static final String VALID =
            """
        {"access_token":"delegated","token_type":"Bearer","expires_in":30,
         "issued_token_type":"urn:ietf:params:oauth:token-type:access_token"}
        """;

    @BeforeEach
    void setup() throws Exception {
        response = VALID;
        status = 200;
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/",
                exchange -> {
                    byte[] data;
                    if (exchange.getRequestMethod().equals("POST")) {
                        stsHeaders.add(exchange.getRequestHeaders().getFirst("Authorization"));
                        ports.add(exchange.getRemoteAddress().getPort());
                        forms.add(
                                URLDecoder.decode(
                                        new String(
                                                exchange.getRequestBody().readAllBytes(),
                                                StandardCharsets.UTF_8),
                                        StandardCharsets.UTF_8));
                        data = response.getBytes(StandardCharsets.UTF_8);
                        if (status == 307)
                            exchange.getResponseHeaders().set("Location", base + "/attacker");
                        exchange.sendResponseHeaders(status, data.length);
                    } else {
                        headers.add(exchange.getRequestHeaders().getFirst("Authorization"));
                        data = "[]".getBytes(StandardCharsets.UTF_8);
                        exchange.getResponseHeaders().set("Content-Type", "application/json");
                        exchange.sendResponseHeaders(apiStatus, data.length);
                    }
                    exchange.getResponseBody().write(data);
                    exchange.close();
                });
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
        federation =
                new FederationOptions(
                                "connection",
                                "project",
                                base,
                                () -> "fresh-idp-" + providers.incrementAndGet())
                        .withScope("content:read");
        options = new ClientOptions().setServerUrl(base).setStoreUrl(base).setTokenServerUrl(base);
    }

    @AfterEach
    void cleanup() {
        server.stop(0);
    }

    @Test
    void concurrentCacheRenewalAndFailure() throws Exception {
        AtomicLong clock = new AtomicLong();
        FederationTokenSource source =
                new FederationTokenSource(
                        federation, base + "/oauth/token", new OkHttpClient(), clock::get);
        var pool = Executors.newFixedThreadPool(10);
        try {
            var tasks = new ArrayList<java.util.concurrent.Callable<String>>();
            for (int i = 0; i < 20; i++) tasks.add(source::get);
            for (var result : pool.invokeAll(tasks)) assertEquals("delegated", result.get());
        } finally {
            pool.shutdownNow();
        }
        assertEquals(1, forms.size());
        assertEquals(1, providers.get());
        clock.addAndGet(TimeUnit.SECONDS.toNanos(28));
        assertEquals("delegated", source.get());
        assertTrue(forms.get(1).contains("subject_token=fresh-idp-2"));
        clock.addAndGet(TimeUnit.SECONDS.toNanos(31));
        status = 401;
        response = "sensitive-idp-secret";
        var error = assertThrows(VertesiaClientException.class, source::get);
        assertFalse(error.getMessage().contains("sensitive"));
    }

    @Test
    void facadeUsesSharedDelegatedBearer() throws Exception {
        var client = new VertesiaClient(options.setFederation(federation));
        client.projects.listProjects(null);
        client.store.projects.listProjects(null);
        assertEquals(List.of("Bearer delegated", "Bearer delegated"), headers);
        assertEquals(1, forms.size());
        assertTrue(forms.get(0).contains("project_id=project"));
        assertTrue(forms.get(0).contains("resource=" + base));
        assertTrue(forms.get(0).contains("scope=content:read"));
    }

    @Test
    void oneShotAndFreshSignedClientProof() {
        var f =
                federation
                        .withTokenEndpoint(base + "/tenant/token")
                        .withClientSecret("m", "a+b%secret");
        var result = VertesiaClient.exchangeFederationToken(options, f, "idp+token");
        assertEquals("delegated", result.accessToken());
        assertTrue(result.expiresIn() >= 28 && result.expiresIn() <= 30);
        assertEquals(0, providers.get());
        assertTrue(forms.get(0).contains("client_secret=a+b%secret"));
        assertFalse(f.toString().contains("a+b%secret"));
        assertFalse(result.toString().contains("delegated"));
        AtomicInteger proof = new AtomicInteger();
        f = f.withClientAssertion("m", () -> "proof-" + proof.incrementAndGet());
        VertesiaClient.exchangeFederationToken(options, f, "idp");
        VertesiaClient.exchangeFederationToken(options, f, "idp");
        assertTrue(forms.get(1).contains("client_assertion=proof-1"));
        assertTrue(forms.get(2).contains("client_assertion=proof-2"));
        assertFalse(forms.get(1).contains("client_secret"));
    }

    @Test
    void malformedResponsesAndRedirectsFailClosed() {
        for (String invalid :
                List.of(
                        "[]",
                        "{}",
                        "not-json",
                        VALID.replace("30", "0"),
                        VALID.replace("30", "86401"),
                        VALID.replace("30", "\"30\""),
                        VALID.replace("Bearer", "Other"),
                        VALID.replace("delegated", ""),
                        "x".repeat(65537))) {
            response = invalid;
            assertThrows(
                    VertesiaClientException.class,
                    () -> VertesiaClient.exchangeFederationToken(options, federation, "idp"));
        }
        forms.clear();
        status = 307;
        assertThrows(
                VertesiaClientException.class,
                () -> VertesiaClient.exchangeFederationToken(options, federation, "idp"));
        assertEquals(1, forms.size());
        assertEquals(0, headers.size());
    }

    @Test
    void configurationAndProviderErrorsAreRejectedWithoutLeakingCredentials() {
        assertThrows(
                VertesiaClientException.class,
                () -> new VertesiaClient(options.setFederation(federation).setToken("direct")));
        var insecure = federation.withTokenEndpoint("http://remote.example/token");
        assertThrows(
                VertesiaClientException.class,
                () -> VertesiaClient.exchangeFederationToken(options, insecure, "idp"));
        var missing = new FederationOptions("c", "p", "r", null);
        assertThrows(
                VertesiaClientException.class,
                () -> new VertesiaClient(options.setToken(null).setFederation(missing)));
        var failing =
                new FederationOptions(
                        "c",
                        "p",
                        "r",
                        () -> {
                            throw new IllegalStateException("sensitive-secret");
                        });
        var source =
                new FederationTokenSource(
                        failing, base + "/oauth/token", new OkHttpClient(), System::nanoTime);
        assertFalse(
                assertThrows(VertesiaClientException.class, source::get)
                        .getMessage()
                        .contains("sensitive-secret"));
        assertEquals(0, forms.size());
    }

    @Test
    void basicEncodingAndExpiredExchange() {
        var f = federation.withClientSecretBasic("client+id", "a+b%:secret");
        VertesiaClient.exchangeFederationToken(options.setToken("must-not-forward"), f, "idp");
        String decoded =
                new String(
                        java.util.Base64.getDecoder().decode(stsHeaders.get(0).substring(6)),
                        StandardCharsets.UTF_8);
        assertEquals("client%2Bid:a%2Bb%25%3Asecret", decoded);
        assertFalse(forms.get(0).contains("client_secret"));
        assertFalse(forms.get(0).contains("client_id"));
        AtomicLong clock = new AtomicLong();
        var slow =
                new FederationOptions(
                        "c",
                        "p",
                        "r",
                        () -> {
                            clock.addAndGet(TimeUnit.MINUTES.toNanos(1));
                            return "idp";
                        });
        var source =
                new FederationTokenSource(
                        slow, base + "/oauth/token", new OkHttpClient(), clock::get);
        assertEquals("delegated", source.get());
    }

    @Test
    void customStsMustBeExplicit() {
        for (String host :
                List.of(
                        "api.example.com",
                        "api.vertesia.io.attacker.test",
                        "api.evilvertesia.io")) {
            var opts =
                    new ClientOptions()
                            .setServerUrl("https://" + host)
                            .setStoreUrl("https://store.example.com")
                            .setFederation(federation);
            assertThrows(VertesiaClientException.class, () -> new VertesiaClient(opts));
            assertThrows(
                    VertesiaClientException.class,
                    () -> VertesiaClient.exchangeFederationToken(opts, federation, "idp"));
            assertDoesNotThrow(() -> new VertesiaClient(opts.setTokenServerUrl(base)));
            opts.setTokenServerUrl(null)
                    .setFederation(federation.withTokenEndpoint(base + "/tenant/token"));
            assertDoesNotThrow(() -> new VertesiaClient(opts));
        }
        assertDoesNotThrow(() -> new VertesiaClient(new ClientOptions().setFederation(federation)));
        assertDoesNotThrow(
                () ->
                        new VertesiaClient(
                                new ClientOptions()
                                        .setRegion("us1")
                                        .setPreview(true)
                                        .setFederation(federation)));
        assertDoesNotThrow(
                () ->
                        new VertesiaClient(
                                new ClientOptions()
                                        .setSite("api.us1.harness.vertesia.io")
                                        .setFederation(federation)));
        assertEquals(0, forms.size());
        assertEquals(0, providers.get());
    }

    @Test
    void sharedExchangeExcludesProvidersAndAccountsForTransit() {
        AtomicLong clock = new AtomicLong();
        AtomicLong delay = new AtomicLong(TimeUnit.SECONDS.toNanos(2));
        var http =
                new OkHttpClient.Builder()
                        .addInterceptor(
                                chain -> {
                                    var response = chain.proceed(chain.request());
                                    clock.addAndGet(delay.get());
                                    return response;
                                })
                        .build();
        var f =
                federation.withClientAssertion(
                        "middleware",
                        () -> {
                            clock.addAndGet(TimeUnit.MINUTES.toNanos(1));
                            return "proof";
                        });
        response = VALID.replace("Bearer", "bearer");
        var issued =
                FederationTokenSource.exchange(f, base + "/oauth/token", http, "idp", clock::get);
        assertEquals(28, issued.expiresIn());
        delay.set(TimeUnit.SECONDS.toNanos(31));
        assertThrows(
                VertesiaClientException.class,
                () ->
                        FederationTokenSource.exchange(
                                f, base + "/oauth/token", http, "idp", clock::get));
    }

    @Test
    void rejectsInvalidBearerSyntaxBeforeReturningOrCaching() {
        for (String token :
                List.of("bad\\ntoken", "contains space", "bad:token", "middle=padding")) {
            response = VALID.replace("delegated", token);
            assertThrows(
                    VertesiaClientException.class,
                    () -> VertesiaClient.exchangeFederationToken(options, federation, "idp"));
            var source =
                    new FederationTokenSource(
                            federation,
                            base + "/oauth/token",
                            new OkHttpClient(),
                            System::nanoTime);
            assertThrows(VertesiaClientException.class, source::get);
        }
    }

    @Test
    void oauthErrorsExposeOnlyAllowlistedMetadata() {
        status = 400;
        for (String code :
                List.of(
                        "invalid_grant",
                        "invalid_client",
                        "invalid_scope",
                        "unauthorized_client",
                        "invalid_target",
                        "invalid_request",
                        "temporarily_unavailable")) {
            response = "{\"error\":\"" + code + "\",\"error_description\":\"secret-idp-token\"}";
            var error =
                    assertThrows(
                            FederationExchangeException.class,
                            () ->
                                    VertesiaClient.exchangeFederationToken(
                                            options, federation, "idp"));
            assertEquals(400, error.getStatusCode());
            assertEquals(code, error.getErrorCode());
            assertFalse(error.getMessage().contains("secret-idp-token"));
        }
        status = 503;
        response = "{\"error\":\"secret-idp-token\"}";
        var error =
                assertThrows(
                        FederationExchangeException.class,
                        () -> VertesiaClient.exchangeFederationToken(options, federation, "idp"));
        assertEquals(503, error.getStatusCode());
        assertNull(error.getErrorCode());
        assertFalse(error.getMessage().contains("secret-idp-token"));
    }

    @Test
    void rejectedTokenIsInvalidatedWithoutReplayAndFailuresBackOff() throws Exception {
        var client = new VertesiaClient(options.setFederation(federation));
        apiStatus = 401;
        assertThrows(ApiException.class, () -> client.projects.listProjects(null));
        assertEquals(1, forms.size());
        assertEquals(1, headers.size());
        apiStatus = 200;
        client.store.projects.listProjects(null);
        assertEquals(2, forms.size());
        assertEquals(2, headers.size());
        AtomicLong clock = new AtomicLong();
        var source =
                new FederationTokenSource(
                        federation, base + "/oauth/token", new OkHttpClient(), clock::get);
        status = 429;
        int initial = forms.size();
        for (int i = 0; i < 10; i++) assertThrows(FederationExchangeException.class, source::get);
        assertEquals(initial + 1, forms.size());
        clock.addAndGet(TimeUnit.SECONDS.toNanos(2));
        assertThrows(FederationExchangeException.class, source::get);
        assertEquals(initial + 2, forms.size());
        clock.addAndGet(TimeUnit.SECONDS.toNanos(2));
        status = 200;
        response = VALID.replace("delegated", "new-token");
        assertEquals("new-token", source.get());
        int exchanges = forms.size();
        source.invalidate("old-token");
        assertEquals("new-token", source.get());
        assertEquals(exchanges, forms.size());
    }

    @Test
    void raisedFractionalLifetimesAndSharedOneShotConnections() {
        response = VALID.replace("30", "600.75");
        var token =
                FederationTokenSource.exchange(
                        federation, base + "/oauth/token", new OkHttpClient(), "idp", () -> 0L);
        assertEquals(600, token.expiresIn());
        ports.clear();
        VertesiaClient.exchangeFederationToken(options, federation, "idp");
        VertesiaClient.exchangeFederationToken(options, federation, "idp");
        assertEquals(2, ports.size());
        assertEquals(ports.get(0), ports.get(1));
    }
}

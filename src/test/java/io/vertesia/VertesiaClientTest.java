package io.vertesia;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class VertesiaClientTest {
    private final List<TestServer> servers = new ArrayList<TestServer>();

    @AfterEach
    void stopServers() {
        for (TestServer server : servers) {
            server.close();
        }
        servers.clear();
    }

    @Test
    void resolvesDefaultEndpoints() {
        VertesiaClient client = new VertesiaClient(new ClientOptions().setToken("token"));

        assertEquals("https://api.vertesia.io/api/v1", client.getStudioUrl());
        assertEquals("https://api.vertesia.io/api/v1", client.getStoreUrl());
        assertEquals("https://sts.vertesia.io", client.getTokenServerUrl());
        assertEquals(VertesiaClient.DEFAULT_API_VERSION, client.getApiVersion());
    }

    @Test
    void resolvesRegionalPreviewEndpoints() {
        VertesiaClient client =
                new VertesiaClient(
                        new ClientOptions().setRegion("us1").setPreview(true).setToken("token"));

        assertEquals("https://api-preview.us1.vertesia.io/api/v1", client.getStudioUrl());
        assertEquals("https://api-preview.us1.vertesia.io/api/v1", client.getStoreUrl());
        assertEquals("https://sts.us1.vertesia.io", client.getTokenServerUrl());
    }

    @Test
    void resolvesExplicitSplitEndpoints() {
        VertesiaClient client =
                new VertesiaClient(
                        new ClientOptions()
                                .setServerUrl("http://localhost:8091")
                                .setStoreUrl("http://localhost:8092/")
                                .setTokenServerUrl("http://localhost:8093/")
                                .setToken("token")
                                .setApiVersion("20260101"));

        assertEquals("http://localhost:8091/api/v1", client.getStudioUrl());
        assertEquals("http://localhost:8092/api/v1", client.getStoreUrl());
        assertEquals("http://localhost:8093", client.getTokenServerUrl());
        assertEquals("20260101", client.getApiVersion());
    }

    @Test
    void rejectsAmbiguousEndpointOptions() {
        assertThrows(
                VertesiaClientException.class,
                () ->
                        new VertesiaClient(
                                new ClientOptions()
                                        .setSite("api.us1.vertesia.io")
                                        .setRegion("us1")));
    }

    @Test
    void rejectsInvalidAuthOptions() {
        assertThrows(
                VertesiaClientException.class,
                () -> new VertesiaClient(new ClientOptions().setApiKey("not-secret")));
        assertThrows(
                VertesiaClientException.class,
                () ->
                        new VertesiaClient(
                                new ClientOptions().setApiKey("sk-test").setToken("token")));
    }

    @Test
    void requiresTokenServerForSecretKeyWithCustomHosts() {
        VertesiaClientException error =
                assertThrows(
                        VertesiaClientException.class,
                        () ->
                                new VertesiaClient(
                                        new ClientOptions()
                                                .setServerUrl("https://studio.example.com")
                                                .setStoreUrl("https://store.example.com")
                                                .setApiKey("sk-test")));

        assertEquals(
                "tokenServerUrl is required when using apiKey with custom endpoints",
                error.getMessage());
    }

    @Test
    void exposesGeneratedClientsAndAliases() {
        VertesiaClient client = new VertesiaClient(new ClientOptions().setToken("token"));

        assertNotNull(client.studio);
        assertNotNull(client.store);
        assertSame(client.studio.projects, client.projects);
        assertSame(client.store.objects, client.objects);
    }

    @Test
    void usesDirectBearerTokenWithoutCallingSts() throws Exception {
        TestServer api =
                server(
                        new TestHandler() {
                            @Override
                            public void handle(HttpExchange exchange) throws IOException {
                                assertEquals(
                                        "/api/v1/projects", exchange.getRequestURI().getPath());
                                assertEquals(
                                        "Bearer direct-token",
                                        exchange.getRequestHeaders().getFirst("Authorization"));
                                writeJson(exchange, 200, "[]");
                            }
                        });
        TestServer sts =
                server(
                        new TestHandler() {
                            @Override
                            public void handle(HttpExchange exchange) throws IOException {
                                writeJson(
                                        exchange, 500, "{\"error\":\"STS should not be called\"}");
                            }
                        });

        VertesiaClient client =
                new VertesiaClient(
                        new ClientOptions()
                                .setServerUrl(api.url())
                                .setStoreUrl(api.url())
                                .setTokenServerUrl(sts.url())
                                .setToken("direct-token"));

        client.projects.listProjects(null);
        assertEquals(0, sts.requestCount());
    }

    @Test
    void exchangesSecretKeyForGeneratedClients() throws Exception {
        final String issuedToken = jwt(System.currentTimeMillis() / 1000L + 3600L);
        TestServer sts =
                server(
                        new TestHandler() {
                            @Override
                            public void handle(HttpExchange exchange) throws IOException {
                                assertEquals("/token/issue", exchange.getRequestURI().getPath());
                                assertEquals("POST", exchange.getRequestMethod());
                                assertEquals(
                                        "Bearer sk-test",
                                        exchange.getRequestHeaders().getFirst("Authorization"));
                                assertEquals(
                                        "20260101",
                                        exchange.getRequestHeaders().getFirst("x-api-version"));
                                String body = readString(exchange.getRequestBody());
                                assertEquals("{\"type\":\"apikey\",\"key\":\"sk-test\"}", body);
                                writeToken(exchange, issuedToken);
                            }
                        });
        TestServer api =
                server(
                        new TestHandler() {
                            @Override
                            public void handle(HttpExchange exchange) throws IOException {
                                assertEquals(
                                        "Bearer " + issuedToken,
                                        exchange.getRequestHeaders().getFirst("Authorization"));
                                writeJson(exchange, 200, "[]");
                            }
                        });

        VertesiaClient client =
                new VertesiaClient(
                        new ClientOptions()
                                .setServerUrl(api.url())
                                .setStoreUrl(api.url())
                                .setTokenServerUrl(sts.url())
                                .setApiKey("sk-test")
                                .setApiVersion("20260101"));

        client.projects.listProjects(null);
        client.store.projects.listProjects(null);
        assertEquals(1, sts.requestCount());
    }

    @Test
    void refreshesNearExpiredSecretKeyToken() throws Exception {
        final List<String> tokens = new ArrayList<String>();
        tokens.add(jwt(System.currentTimeMillis() / 1000L + 30L));
        tokens.add(jwt(System.currentTimeMillis() / 1000L + 3600L));
        final AtomicInteger tokenIndex = new AtomicInteger();
        TestServer sts =
                server(
                        new TestHandler() {
                            @Override
                            public void handle(HttpExchange exchange) throws IOException {
                                int index =
                                        Math.min(tokenIndex.getAndIncrement(), tokens.size() - 1);
                                writeToken(exchange, tokens.get(index));
                            }
                        });
        TestServer api =
                server(
                        new TestHandler() {
                            @Override
                            public void handle(HttpExchange exchange) throws IOException {
                                writeJson(exchange, 200, "[]");
                            }
                        });

        VertesiaClient client =
                new VertesiaClient(
                        new ClientOptions()
                                .setServerUrl(api.url())
                                .setStoreUrl(api.url())
                                .setTokenServerUrl(sts.url())
                                .setApiKey("sk-test"));

        client.projects.listProjects(null);
        client.projects.listProjects(null);

        assertEquals(2, sts.requestCount());
    }

    private TestServer server(TestHandler handler) throws IOException {
        TestServer server = new TestServer(handler);
        servers.add(server);
        return server;
    }

    private static String jwt(long exp) {
        String header = encodeJson("{\"alg\":\"none\",\"typ\":\"JWT\"}");
        String payload = encodeJson("{\"exp\":" + exp + "}");
        return header + "." + payload + ".signature";
    }

    private static String encodeJson(String json) {
        return Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(json.getBytes(StandardCharsets.UTF_8));
    }

    private static void writeToken(HttpExchange exchange, String token) throws IOException {
        writeJson(
                exchange,
                200,
                "{\"token\":\"" + token + "\",\"token_type\":\"Bearer\",\"expires_in\":3600}");
    }

    private static void writeJson(HttpExchange exchange, int status, String body)
            throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders()
                .put("Content-Type", Collections.singletonList("application/json"));
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }

    private static String readString(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[1024];
        int read;
        while ((read = input.read(buffer)) != -1) {
            output.write(buffer, 0, read);
        }
        return new String(output.toByteArray(), StandardCharsets.UTF_8);
    }

    private abstract static class TestHandler {
        abstract void handle(HttpExchange exchange) throws IOException;
    }

    private static class TestServer implements AutoCloseable {
        private final HttpServer server;
        private final AtomicInteger requests = new AtomicInteger();

        private TestServer(final TestHandler handler) throws IOException {
            this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            this.server.createContext(
                    "/",
                    exchange -> {
                        requests.incrementAndGet();
                        handler.handle(exchange);
                    });
            this.server.start();
        }

        private String url() {
            return "http://127.0.0.1:" + server.getAddress().getPort();
        }

        private int requestCount() {
            return requests.get();
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }
}

package io.vertesia;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.sun.net.httpserver.HttpServer;
import io.vertesia.api.ProjectsApi;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

/**
 * Callers of the low-level {@link ApiClient} authenticate with {@code setAccessToken}, {@code setBearerToken}
 * or both. The access token is sent as a bearer token, and a token from {@code setBearerToken} takes precedence
 * over it, whether or not the client is generated with an OAuth security scheme.
 */
class ApiClientAccessTokenTest {
    @Test
    void setAccessTokenSendsABearerAuthorizationHeader() throws Exception {
        assertEquals(
                "Bearer access-token",
                authorizationSentWith(client -> client.setAccessToken("access-token")));
    }

    @Test
    void aBearerTokenSupplierTakesPrecedenceOverTheAccessToken() throws Exception {
        assertEquals(
                "Bearer supplied-token",
                authorizationSentWith(
                        client -> {
                            client.setBearerToken(() -> "supplied-token");
                            client.setAccessToken("fallback-token");
                        }));
    }

    @Test
    void theAccessTokenIsSentWhenTheSupplierReturnsNull() throws Exception {
        assertEquals(
                "Bearer fallback-token",
                authorizationSentWith(
                        client -> {
                            client.setBearerToken(() -> null);
                            client.setAccessToken("fallback-token");
                        }));
    }

    @Test
    void clearingTheAccessTokenKeepsTheBearerTokenSupplier() throws Exception {
        assertEquals(
                "Bearer supplied-token",
                authorizationSentWith(
                        client -> {
                            client.setBearerToken(() -> "supplied-token");
                            client.setAccessToken(null);
                        }));
    }

    @Test
    void clearingTheAccessTokenAloneSendsNoAuthorizationHeader() throws Exception {
        assertNull(
                authorizationSentWith(
                        client -> {
                            client.setAccessToken("access-token");
                            client.setAccessToken(null);
                        }));
    }

    private static String authorizationSentWith(Consumer<ApiClient> configure) throws Exception {
        AtomicReference<String> authorization = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/",
                exchange -> {
                    authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
                    byte[] body = "[]".getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(200, body.length);
                    try (OutputStream output = exchange.getResponseBody()) {
                        output.write(body);
                    }
                });
        server.start();
        try {
            ApiClient client = new ApiClient();
            client.setBasePath("http://127.0.0.1:" + server.getAddress().getPort() + "/api/v1");
            configure.accept(client);

            new ProjectsApi(client).listProjects().execute();

            return authorization.get();
        } finally {
            server.stop(0);
        }
    }
}

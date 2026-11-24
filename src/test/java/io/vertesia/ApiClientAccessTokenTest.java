package io.vertesia;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.sun.net.httpserver.HttpServer;
import io.vertesia.api.ProjectsApi;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * Callers of the low-level {@link ApiClient} authenticate with {@code setAccessToken}. It must keep sending
 * the token as a bearer token whether or not the client is generated with an OAuth security scheme.
 */
class ApiClientAccessTokenTest {
    @Test
    void setAccessTokenSendsABearerAuthorizationHeader() throws Exception {
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
            client.setAccessToken("access-token");

            new ProjectsApi(client).listProjects().execute();

            assertEquals("Bearer access-token", authorization.get());
        } finally {
            server.stop(0);
        }
    }
}

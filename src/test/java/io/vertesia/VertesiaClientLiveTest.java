package io.vertesia;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.github.cdimascio.dotenv.Dotenv;
import io.github.cdimascio.dotenv.DotenvEntry;
import io.vertesia.model.ComplexSearchPayload;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class VertesiaClientLiveTest {
    private static final Map<String, String> DOTENV = new HashMap<String, String>();

    @BeforeAll
    static void loadDotEnv() {
        Dotenv dotenv = Dotenv.configure().ignoreIfMissing().ignoreIfMalformed().load();
        for (DotenvEntry entry : dotenv.entries()) {
            DOTENV.put(entry.getKey(), entry.getValue());
        }
    }

    @Test
    void liveApiSmokeTest() throws Exception {
        assumeTrue(
                "1".equals(env("VERTESIA_LIVE_TESTS", "0")),
                "live SDK tests are disabled; set VERTESIA_LIVE_TESTS=1 to run them");

        String apiKey = env("VERTESIA_API_KEY", "");
        assertTrue(apiKey.startsWith("sk-"), "VERTESIA_API_KEY must be an sk- secret key");

        VertesiaClient client =
                new VertesiaClient(
                        new ClientOptions()
                                .setServerUrl(
                                        env("VERTESIA_STUDIO_URL", "http://localhost:8091/api/v1"))
                                .setStoreUrl(
                                        env("VERTESIA_ZENO_URL", "http://localhost:8092/api/v1"))
                                .setTokenServerUrl(env("VERTESIA_STS_URL", "http://localhost:8093"))
                                .setApiKey(apiKey));

        assertNotNull(client.accounts.getCurrentAccount());
        assertFalse(
                client.projects.listProjects(null).isEmpty(),
                "expected at least one visible project");
        assertNotNull(client.store.data.listDataStores());
        assertNotNull(client.store.workflowDefinitions.listWorkflowDefinitions());
        assertNotNull(
                client.store.objects.searchObjects(
                        new ComplexSearchPayload().limit(BigDecimal.ONE)));
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        if (value == null || value.trim().isEmpty()) {
            value = DOTENV.get(name);
        }
        return value == null || value.trim().isEmpty() ? fallback : value.trim();
    }
}

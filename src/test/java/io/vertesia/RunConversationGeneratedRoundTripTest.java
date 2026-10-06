package io.vertesia;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/**
 * Verifies canonical models after the latest API document has been generated.
 *
 * <p>The committed generated source may predate that API addition between synchronization runs, so the test skips
 * only while the generated response class is absent. The SDK synchronization gate regenerates the class before
 * running this suite.
 */
class RunConversationGeneratedRoundTripTest {
    private static String fixture() {
        try (InputStream input =
                RunConversationGeneratedRoundTripTest.class.getResourceAsStream(
                        "/fixtures/run-conversation-available.json")) {
            if (input == null) {
                throw new IllegalStateException("Missing canonical conversation fixture");
            }
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Class<?> generatedResponseClass() {
        try {
            return Class.forName("io.vertesia.model.RunConversationResponse");
        } catch (ClassNotFoundException e) {
            assumeTrue(
                    false,
                    "RunConversationResponse has not been synchronized into generated source yet");
            throw new AssertionError(e);
        }
    }

    @Test
    void roundTripsFullCanonicalConversationExactly() {
        JsonElement expected = JsonParser.parseString(fixture());
        Object response = JSON.getGson().fromJson(expected, generatedResponseClass());

        JsonObject serialized = JSON.getGson().toJsonTree(response).getAsJsonObject();
        JsonObject firstContextEntry =
                serialized
                        .getAsJsonObject("conversation")
                        .getAsJsonObject("context")
                        .getAsJsonArray("entries")
                        .get(0)
                        .getAsJsonObject();
        assertFalse(firstContextEntry.has("block_ids"));
        JsonObject jsonValue =
                serialized
                        .getAsJsonObject("conversation")
                        .getAsJsonArray("turns")
                        .get(1)
                        .getAsJsonObject()
                        .getAsJsonArray("blocks")
                        .get(1)
                        .getAsJsonObject()
                        .getAsJsonObject("value");
        assertTrue(jsonValue.has("note"));
        assertTrue(jsonValue.get("note").isJsonNull());
        assertEquals(expected, serialized);
    }

    @Test
    void roundTripsBooleanToolInputSchemas() {
        for (boolean inputSchema : new boolean[] {true, false}) {
            JsonObject expected = JsonParser.parseString(fixture()).getAsJsonObject();
            expected.getAsJsonObject("conversation")
                    .getAsJsonObject("tool_definitions")
                    .getAsJsonObject("tool-inspect-image-v1")
                    .addProperty("input_schema", inputSchema);

            Object response = JSON.getGson().fromJson(expected, generatedResponseClass());

            assertEquals(expected, JSON.getGson().toJsonTree(response));
        }
    }
}

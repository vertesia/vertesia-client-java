package io.vertesia;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.google.gson.annotations.JsonAdapter;
import io.vertesia.gson.NullPreservingJsonElementMapTypeAdapter;
import io.vertesia.gson.NullPreservingJsonElementTypeAdapter;
import java.util.Map;
import org.junit.jupiter.api.Test;

class NullPreservingJsonElementTypeAdapterTest {
    private static final Gson GSON = new GsonBuilder().create();

    static final class JsonElementHolder {
        @JsonAdapter(value = NullPreservingJsonElementTypeAdapter.class, nullSafe = false)
        JsonElement value;

        String optional;
    }

    static final class JsonElementMapHolder {
        @JsonAdapter(value = NullPreservingJsonElementMapTypeAdapter.class, nullSafe = false)
        Map<String, JsonElement> metadata;

        String optional;
    }

    @Test
    void preservesNestedNullWithoutEmittingAbsentOptionalFields() {
        JsonElementHolder holder = new JsonElementHolder();
        holder.value = JsonParser.parseString("{\"note\":null,\"items\":[true,null,false]}");

        JsonElement serialized = GSON.toJsonTree(holder);

        assertEquals(holder.value, serialized.getAsJsonObject().get("value"));
        assertTrue(serialized.getAsJsonObject().getAsJsonObject("value").has("note"));
        assertTrue(serialized.getAsJsonObject().getAsJsonObject("value").get("note").isJsonNull());
        assertFalse(serialized.getAsJsonObject().has("optional"));
    }

    @Test
    void roundTripsBooleanJsonElements() {
        for (boolean value : new boolean[] {true, false}) {
            JsonElementHolder holder =
                    GSON.fromJson("{\"value\":" + value + "}", JsonElementHolder.class);

            assertEquals(value, holder.value.getAsBoolean());
            assertEquals(
                    JsonParser.parseString("{\"value\":" + value + "}"), GSON.toJsonTree(holder));
        }
    }

    @Test
    void preservesNullsNestedInJsonElementMaps() {
        JsonElementMapHolder holder =
                GSON.fromJson(
                        "{\"metadata\":{\"direct\":null,\"nested\":{\"value\":null},\"items\":[null,{\"value\":null}]}}",
                        JsonElementMapHolder.class);

        JsonElement serialized = GSON.toJsonTree(holder);

        assertEquals(
                JsonParser.parseString(
                        "{\"metadata\":{\"direct\":null,\"nested\":{\"value\":null},\"items\":[null,{\"value\":null}]}}"),
                serialized);
        assertFalse(serialized.getAsJsonObject().has("optional"));
    }
}

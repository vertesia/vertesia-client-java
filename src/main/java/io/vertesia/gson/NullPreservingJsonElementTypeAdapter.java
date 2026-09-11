package io.vertesia.gson;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.google.gson.TypeAdapter;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonWriter;
import java.io.IOException;
import java.util.Map;

/**
 * Preserves explicit null members inside arbitrary JSON values without enabling global null
 * serialization.
 */
public final class NullPreservingJsonElementTypeAdapter extends TypeAdapter<JsonElement> {
    @Override
    public void write(JsonWriter out, JsonElement value) throws IOException {
        if (value == null) {
            out.nullValue();
            return;
        }
        boolean previousSerializeNulls = out.getSerializeNulls();
        out.setSerializeNulls(true);
        try {
            writeElement(out, value);
        } finally {
            out.setSerializeNulls(previousSerializeNulls);
        }
    }

    @Override
    public JsonElement read(JsonReader in) {
        return JsonParser.parseReader(in);
    }

    static void writeElement(JsonWriter out, JsonElement value) throws IOException {
        if (value == null || value.isJsonNull()) {
            out.nullValue();
            return;
        }
        if (value.isJsonPrimitive()) {
            JsonPrimitive primitive = value.getAsJsonPrimitive();
            if (primitive.isBoolean()) {
                out.value(primitive.getAsBoolean());
            } else if (primitive.isNumber()) {
                out.value(primitive.getAsNumber());
            } else {
                out.value(primitive.getAsString());
            }
            return;
        }
        if (value.isJsonArray()) {
            out.beginArray();
            JsonArray array = value.getAsJsonArray();
            for (JsonElement item : array) {
                writeElement(out, item);
            }
            out.endArray();
            return;
        }
        out.beginObject();
        JsonObject object = value.getAsJsonObject();
        for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
            out.name(entry.getKey());
            writeElement(out, entry.getValue());
        }
        out.endObject();
    }
}

package io.vertesia.gson;

import com.google.gson.JsonElement;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.google.gson.TypeAdapter;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonWriter;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/** Preserves explicit null members in string-keyed maps of arbitrary JSON values. */
public final class NullPreservingJsonElementMapTypeAdapter
        extends TypeAdapter<Map<String, JsonElement>> {
    @Override
    public void write(JsonWriter out, Map<String, JsonElement> value) throws IOException {
        if (value == null) {
            out.nullValue();
            return;
        }
        boolean previousSerializeNulls = out.getSerializeNulls();
        out.setSerializeNulls(true);
        try {
            out.beginObject();
            for (Map.Entry<String, JsonElement> entry : value.entrySet()) {
                out.name(entry.getKey());
                NullPreservingJsonElementTypeAdapter.writeElement(out, entry.getValue());
            }
            out.endObject();
        } finally {
            out.setSerializeNulls(previousSerializeNulls);
        }
    }

    @Override
    public Map<String, JsonElement> read(JsonReader in) {
        JsonElement value = JsonParser.parseReader(in);
        if (value.isJsonNull()) {
            return null;
        }
        if (!value.isJsonObject()) {
            throw new JsonParseException("Expected an object containing arbitrary JSON values");
        }
        Map<String, JsonElement> result = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> entry : value.getAsJsonObject().entrySet()) {
            result.put(entry.getKey(), entry.getValue());
        }
        return result;
    }
}

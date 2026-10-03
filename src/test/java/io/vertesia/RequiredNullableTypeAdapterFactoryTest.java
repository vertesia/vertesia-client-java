package io.vertesia;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.google.gson.Gson;
import com.google.gson.JsonParser;
import com.google.gson.annotations.JsonAdapter;
import io.vertesia.gson.RequiredNullableTypeAdapterFactory;
import org.junit.jupiter.api.Test;

class RequiredNullableTypeAdapterFactoryTest {
    private static final Gson GSON = new Gson();

    static final class Holder {
        @JsonAdapter(value = RequiredNullableTypeAdapterFactory.class, nullSafe = false)
        String required;

        String optional;
    }

    static final class NestedHolder {
        @JsonAdapter(value = RequiredNullableTypeAdapterFactory.class, nullSafe = false)
        Holder nested;

        String optional;
    }

    @Test
    void preservesRequiredNullAndRestoresOptionalFieldPolicy() {
        Holder holder = GSON.fromJson("{\"required\":null}", Holder.class);
        assertEquals("{\"required\":null}", GSON.toJson(holder));
        assertFalse(GSON.toJsonTree(holder).getAsJsonObject().has("optional"));
    }

    @Test
    void delegatesNonNullValuesWithoutChangingNestedOptionalFields() {
        String body = "{\"nested\":{\"required\":\"recorded-model\"}}";
        NestedHolder holder = GSON.fromJson(body, NestedHolder.class);
        assertEquals(JsonParser.parseString(body), GSON.toJsonTree(holder));
        holder.nested.required = null;
        assertEquals("{\"nested\":{\"required\":null}}", GSON.toJson(holder));
    }

    @Test
    void preservesNullObjectFieldWithoutEnablingGlobalNulls() {
        assertEquals("{\"nested\":null}", GSON.toJson(new NestedHolder()));
    }
}

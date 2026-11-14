package io.vertesia;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.TypeAdapter;
import com.google.gson.annotations.SerializedName;
import com.google.gson.stream.JsonWriter;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Writes the JSON trees of the generated models.
 *
 * <p>A model's tree holds a {@code null} member for each property the model leaves unset, and the
 * client's writer drops every {@code null} member to leave them out. That writer would also drop
 * the {@code null} members of a union variant kept as the JSON it was received as, and those of
 * free-form values ({@code Object}, {@code Map<String, Object>}). So each model leaves out its own
 * unset properties and the {@code null} entries of its typed maps, such as {@code Map<String,
 * String>}, and the rest of the tree is written with its {@code null} members.
 * scripts/patch_forward_compat_validation.py routes the generated writes through this class.
 */
public final class JsonOutput {
    private static final String MODELS = "io.vertesia.model.";
    private static final Map<Class<?>, Map<String, Type>> TYPED_MAPS = new ConcurrentHashMap<>();

    private JsonOutput() {}

    /**
     * Writes a model's tree without the properties it leaves unset or the {@code null} entries of
     * its typed maps.
     */
    public static void writeModel(
            JsonWriter out, TypeAdapter<JsonElement> adapter, JsonElement tree, Object model)
            throws IOException {
        if (tree.isJsonObject()) {
            JsonObject object = tree.getAsJsonObject();
            object.entrySet().removeIf(member -> member.getValue().isJsonNull());
            if (model != null && model.getClass().getName().startsWith(MODELS)) {
                for (Map.Entry<String, Type> map : typedMaps(model.getClass()).entrySet()) {
                    dropNullEntries(object.get(map.getKey()), map.getValue());
                }
            }
        }
        writeKeepingNulls(out, adapter, tree);
    }

    /** Writes a tree as it is, {@code null} members included. */
    public static void writeKeepingNulls(
            JsonWriter out, TypeAdapter<JsonElement> adapter, JsonElement tree) throws IOException {
        boolean serializeNulls = out.getSerializeNulls();
        out.setSerializeNulls(true);
        try {
            adapter.write(out, tree);
        } finally {
            out.setSerializeNulls(serializeNulls);
        }
    }

    /** Returns the JSON names of a model's typed map properties, with the type of their values. */
    private static Map<String, Type> typedMaps(Class<?> type) {
        return TYPED_MAPS.computeIfAbsent(
                type,
                model -> {
                    Map<String, Type> maps = new LinkedHashMap<>();
                    for (Class<?> c = model;
                            c != null && c != Object.class;
                            c = c.getSuperclass()) {
                        for (Field field : c.getDeclaredFields()) {
                            Type values = typedMapValues(field.getGenericType());
                            if (values != null) {
                                SerializedName name = field.getAnnotation(SerializedName.class);
                                maps.put(name != null ? name.value() : field.getName(), values);
                            }
                        }
                    }
                    return maps;
                });
    }

    /** Returns the value type of a map whose values are not free-form, or null. */
    private static Type typedMapValues(Type type) {
        if (!(type instanceof ParameterizedType)) {
            return null;
        }
        ParameterizedType map = (ParameterizedType) type;
        if (!(map.getRawType() instanceof Class)
                || !Map.class.isAssignableFrom((Class<?>) map.getRawType())) {
            return null;
        }
        Type values = map.getActualTypeArguments()[1];
        return values == Object.class || values == JsonElement.class ? null : values;
    }

    private static void dropNullEntries(JsonElement map, Type values) {
        if (map == null || !map.isJsonObject()) {
            return;
        }
        map.getAsJsonObject().entrySet().removeIf(entry -> entry.getValue().isJsonNull());
        Type nested = typedMapValues(values);
        if (nested != null) {
            for (Map.Entry<String, JsonElement> entry : map.getAsJsonObject().entrySet()) {
                dropNullEntries(entry.getValue(), nested);
            }
        }
    }
}

package io.vertesia;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.google.gson.annotations.SerializedName;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.GenericArrayType;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.WildcardType;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Keeps the enum values a newer server sends that this client version does not know.
 *
 * <p>A generated enum decodes a value it does not declare as {@code UNKNOWN_DEFAULT_OPEN_API}, and
 * a Java enum constant cannot hold the value it was decoded from. So a model keeps the JSON it
 * received for each enum property that decoded to that placeholder, in a field named {@value
 * #FIELD}, and writing the model back writes that JSON again instead of {@code
 * unknown_default_open_api}. A property the caller changed since it was decoded is written as it
 * now is. scripts/patch_forward_compat_validation.py adds the field and routes the generated
 * adapters through this class; {@link JsonOutput#writeModel} restores the values.
 */
public final class UnknownEnumValues {
    /** The transient field in which a generated model keeps the JSON of its unknown enum values. */
    public static final String FIELD = "receivedEnumValues";

    /** What enum placeholders serialize to: string enums and numeric enums. */
    private static final String PLACEHOLDER = "unknown_default_open_api";

    private static final BigDecimal NUMERIC_PLACEHOLDER = new BigDecimal("11184809");

    private static final Map<Class<?>, Optional<Model>> MODELS = new ConcurrentHashMap<>();

    private UnknownEnumValues() {}

    /** The field that keeps a model's unknown enum values, and its enum properties. */
    private static final class Model {
        final Field store;
        final List<Field> properties;

        Model(Field store, List<Field> properties) {
            this.store = store;
            this.properties = properties;
        }
    }

    /**
     * Records, in a decoded model, the JSON received for each enum property that decoded to the
     * placeholder. Returns the model.
     */
    public static <T> T keep(JsonElement received, T model) {
        Model type = model(model);
        if (type == null || received == null || !received.isJsonObject()) {
            return model;
        }
        JsonObject kept = null;
        for (Field property : type.properties) {
            JsonElement value = received.getAsJsonObject().get(name(property));
            if (value != null && holdsPlaceholder(get(property, model))) {
                if (kept == null) {
                    kept = new JsonObject();
                }
                kept.add(name(property), value.deepCopy());
            }
        }
        set(type.store, model, kept);
        return model;
    }

    /**
     * Writes back, into a model's JSON tree, the enum values it was decoded from, for each
     * property whose value has not changed since.
     */
    public static void restore(JsonElement tree, Object model) {
        Model type = model(model);
        if (type == null || tree == null || !tree.isJsonObject()) {
            return;
        }
        JsonObject kept = (JsonObject) get(type.store, model);
        if (kept == null) {
            return;
        }
        JsonObject written = tree.getAsJsonObject();
        for (Map.Entry<String, JsonElement> property : kept.entrySet()) {
            JsonElement value = written.get(property.getKey());
            if (value != null && decodesTo(property.getValue(), value)) {
                written.add(property.getKey(), property.getValue().deepCopy());
            }
        }
    }

    /**
     * Fails for a value the given enum does not declare. A union whose branch is an enum calls it,
     * so that a value the enum does not know goes to the next branch, such as a plain string.
     */
    public static void requireKnown(JsonElement received, Class<? extends Enum<?>> type)
            throws IOException {
        if (isPlaceholder(JSON.getGson().fromJson(received, type))) {
            throw new IOException(
                    "The value " + received + " is not a known " + type.getSimpleName());
        }
    }

    /** Returns whether a decoded value is, or contains, an enum placeholder. */
    static boolean holdsPlaceholder(Object value) {
        if (value instanceof Enum) {
            return isPlaceholder(value);
        }
        if (value instanceof Map) {
            value = ((Map<?, ?>) value).values();
        }
        if (value instanceof Collection) {
            for (Object item : (Collection<?>) value) {
                if (holdsPlaceholder(item)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean isPlaceholder(Object value) {
        // String enums name it UNKNOWN_DEFAULT_OPEN_API, numeric ones
        // NUMBER_unknown_default_open_api.
        return value instanceof Enum
                && ((Enum<?>) value)
                        .name()
                        .toLowerCase(java.util.Locale.ROOT)
                        .endsWith(PLACEHOLDER);
    }

    /**
     * Returns whether a received value decodes to the written one: the same JSON, except where the
     * written value is the placeholder an unknown received value decodes to.
     */
    static boolean decodesTo(JsonElement received, JsonElement written) {
        if (received.equals(written)) {
            return true;
        }
        if (written.isJsonPrimitive() && received.isJsonPrimitive()) {
            JsonPrimitive placeholder = written.getAsJsonPrimitive();
            return placeholder.isString()
                    ? PLACEHOLDER.equals(placeholder.getAsString())
                    : placeholder.isNumber()
                            && NUMERIC_PLACEHOLDER.compareTo(placeholder.getAsBigDecimal()) == 0;
        }
        if (written.isJsonArray() && received.isJsonArray()) {
            JsonArray items = received.getAsJsonArray();
            if (items.size() != written.getAsJsonArray().size()) {
                return false;
            }
            Iterator<JsonElement> writtenItems = written.getAsJsonArray().iterator();
            for (JsonElement item : items) {
                if (!decodesTo(item, writtenItems.next())) {
                    return false;
                }
            }
            return true;
        }
        if (written.isJsonObject() && received.isJsonObject()) {
            JsonObject entries = received.getAsJsonObject();
            if (!entries.keySet().equals(written.getAsJsonObject().keySet())) {
                return false;
            }
            for (Map.Entry<String, JsonElement> entry : entries.entrySet()) {
                JsonElement value = written.getAsJsonObject().get(entry.getKey());
                if (!decodesTo(entry.getValue(), value)) {
                    return false;
                }
            }
            return true;
        }
        return false;
    }

    /** Returns the class declaring the field that keeps a model's unknown enum values, or null. */
    private static Model model(Object model) {
        if (model == null) {
            return null;
        }
        return MODELS.computeIfAbsent(model.getClass(), UnknownEnumValues::describe).orElse(null);
    }

    private static Optional<Model> describe(Class<?> type) {
        // A caller's subclass of a generated model inherits the field.
        for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
            Field store;
            try {
                store = c.getDeclaredField(FIELD);
            } catch (NoSuchFieldException e) {
                continue;
            }
            List<Field> properties = new ArrayList<>();
            for (Field field : c.getDeclaredFields()) {
                if (!Modifier.isStatic(field.getModifiers())
                        && !Modifier.isTransient(field.getModifiers())
                        && holdsEnums(field.getGenericType())) {
                    field.setAccessible(true);
                    properties.add(field);
                }
            }
            store.setAccessible(true);
            return Optional.of(new Model(store, List.copyOf(properties)));
        }
        return Optional.empty();
    }

    /** Returns whether a property type is an enum, or a collection or map that can hold one. */
    static boolean holdsEnums(Type type) {
        if (type instanceof Class) {
            Class<?> c = (Class<?>) type;
            return c.isEnum() || (c.isArray() && holdsEnums(c.getComponentType()));
        }
        if (type instanceof ParameterizedType) {
            for (Type argument : ((ParameterizedType) type).getActualTypeArguments()) {
                if (holdsEnums(argument)) {
                    return true;
                }
            }
            return false;
        }
        if (type instanceof GenericArrayType) {
            return holdsEnums(((GenericArrayType) type).getGenericComponentType());
        }
        if (type instanceof WildcardType) {
            for (Type bound : ((WildcardType) type).getUpperBounds()) {
                if (holdsEnums(bound)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static String name(Field property) {
        SerializedName name = property.getAnnotation(SerializedName.class);
        return name != null ? name.value() : property.getName();
    }

    private static Object get(Field field, Object model) {
        try {
            return field.get(model);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void set(Field field, Object model, Object value) {
        try {
            field.set(model, value);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(e);
        }
    }
}

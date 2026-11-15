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
import java.util.LinkedHashMap;
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
 * #FIELD}, and writing the model back writes each unknown value again in place of {@code
 * unknown_default_open_api}. The values belong to the lists and maps they were decoded into: a
 * value the caller sets, or a list or map the caller puts in place of a decoded one, is written as
 * it now is, and a decoded list or map the caller edits keeps its unknown values, see {@link
 * #restored}. scripts/patch_forward_compat_validation.py adds the field and routes the generated
 * adapters through this class; {@link JsonOutput#writeModel} restores the values.
 */
public final class UnknownEnumValues {
    /** The transient field in which a generated model keeps its unknown enum values. */
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
     * What a model keeps for each enum property that decoded to a placeholder: the JSON received,
     * and what it decoded to.
     */
    private static final class Kept {
        final Map<String, JsonElement> received = new LinkedHashMap<>();
        final Map<String, Object> decoded = new LinkedHashMap<>();
    }

    /** A list or a map as it was decoded: the instance, and what it held then. */
    private static final class Decoded {
        final Object container;
        final List<Object> items;
        final Map<String, Object> entries;

        Decoded(Object container, List<Object> items, Map<String, Object> entries) {
            this.container = container;
            this.items = items;
            this.entries = entries;
        }
    }

    /**
     * Records, in a decoded model, the JSON received for each enum property that decoded to the
     * placeholder, and what it decoded to. Returns the model.
     */
    public static <T> T keep(JsonElement received, T model) {
        Model type = model(model);
        if (type == null || received == null || !received.isJsonObject()) {
            return model;
        }
        Kept kept = null;
        for (Field property : type.properties) {
            JsonElement value = received.getAsJsonObject().get(name(property));
            Object decoded = get(property, model);
            if (value != null && holdsPlaceholder(decoded)) {
                if (kept == null) {
                    kept = new Kept();
                }
                kept.received.put(name(property), value.deepCopy());
                kept.decoded.put(name(property), snapshot(decoded));
            }
        }
        set(type.store, model, kept);
        return model;
    }

    /** Writes back, into a model's JSON tree, the unknown enum values it was decoded from. */
    public static void restore(JsonElement tree, Object model) {
        Model type = model(model);
        if (type == null || tree == null || !tree.isJsonObject()) {
            return;
        }
        Object stored = get(type.store, model);
        if (!(stored instanceof Kept)) {
            return;
        }
        Kept kept = (Kept) stored;
        JsonObject written = tree.getAsJsonObject();
        for (Field property : type.properties) {
            JsonElement received = kept.received.get(name(property));
            JsonElement value = written.get(name(property));
            if (received != null && value != null) {
                JsonElement restored =
                        restored(
                                received,
                                kept.decoded.get(name(property)),
                                value,
                                get(property, model));
                written.add(name(property), restored.deepCopy());
            }
        }
    }

    /** Returns a decoded value with, for a list or a map, the instance and what it holds now. */
    private static Object snapshot(Object value) {
        if (value instanceof Map) {
            Map<String, Object> entries = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
                entries.put(String.valueOf(entry.getKey()), snapshot(entry.getValue()));
            }
            return new Decoded(value, null, entries);
        }
        if (value instanceof Collection) {
            List<Object> items = new ArrayList<>();
            for (Object item : (Collection<?>) value) {
                items.add(snapshot(item));
            }
            return new Decoded(value, items, null);
        }
        return value;
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
     * Returns what to write for a property: its written value, with the placeholders that stand
     * for a received unknown value replaced by that value.
     *
     * <p>A placeholder written where an unknown value was received is that value. A list or a map
     * the caller put in place of the decoded one is written as it now is. A decoded list of values
     * gets its unknown values back in their order, whatever known values the caller replaced,
     * added, removed or moved, while it holds as many placeholders as it received unknown values;
     * otherwise, such as after the caller removed an unknown value or added a placeholder, the
     * placeholders cannot be told apart and are written as such. A decoded map keeps its entries
     * by key, and a decoded list of lists or maps its elements by position.
     *
     * @param received the JSON the property was decoded from
     * @param decoded what {@code received} decoded to, from {@link #snapshot}
     * @param written the property's value now, written as JSON
     * @param current the property's value now
     */
    static JsonElement restored(
            JsonElement received, Object decoded, JsonElement written, Object current) {
        if (!(decoded instanceof Decoded)) {
            return writesPlaceholder(written) && isPlaceholder(decoded) ? received : written;
        }
        Decoded container = (Decoded) decoded;
        if (current != container.container) {
            return written;
        }
        if (container.items != null && received.isJsonArray() && written.isJsonArray()) {
            return restoredList(
                    received.getAsJsonArray(), container, written.getAsJsonArray(), current);
        }
        if (container.entries != null && received.isJsonObject() && written.isJsonObject()) {
            Map<?, ?> map = (Map<?, ?>) current;
            JsonObject entries = new JsonObject();
            for (Map.Entry<String, JsonElement> entry : written.getAsJsonObject().entrySet()) {
                JsonElement value = received.getAsJsonObject().get(entry.getKey());
                entries.add(
                        entry.getKey(),
                        value == null || !container.entries.containsKey(entry.getKey())
                                ? entry.getValue()
                                : restored(
                                        value,
                                        container.entries.get(entry.getKey()),
                                        entry.getValue(),
                                        map.get(entry.getKey())));
            }
            return entries;
        }
        return written;
    }

    private static JsonElement restoredList(
            JsonArray received, Decoded decoded, JsonArray written, Object current) {
        if (received.size() != decoded.items.size()) {
            return written;
        }
        List<JsonElement> unknown = new ArrayList<>();
        boolean flat = true;
        for (int i = 0; i < decoded.items.size(); i++) {
            Object item = decoded.items.get(i);
            if (isPlaceholder(item)) {
                unknown.add(received.get(i));
            } else if (item instanceof Decoded) {
                flat = false;
            }
        }
        JsonArray items = new JsonArray(written.size());
        if (flat) {
            int placeholders = 0;
            for (JsonElement item : written) {
                placeholders += writesPlaceholder(item) ? 1 : 0;
            }
            if (placeholders != unknown.size()) {
                return written;
            }
            Iterator<JsonElement> next = unknown.iterator();
            for (JsonElement item : written) {
                items.add(writesPlaceholder(item) ? next.next() : item);
            }
            return items;
        }
        List<?> now = new ArrayList<>((Collection<?>) current);
        if (written.size() != received.size() || now.size() != written.size()) {
            return written;
        }
        for (int i = 0; i < written.size(); i++) {
            items.add(restored(received.get(i), decoded.items.get(i), written.get(i), now.get(i)));
        }
        return items;
    }

    /** Returns whether a JSON value is what an enum placeholder serializes to. */
    private static boolean writesPlaceholder(JsonElement value) {
        if (value == null || !value.isJsonPrimitive()) {
            return false;
        }
        JsonPrimitive primitive = value.getAsJsonPrimitive();
        return primitive.isString()
                ? PLACEHOLDER.equals(primitive.getAsString())
                : primitive.isNumber()
                        && NUMERIC_PLACEHOLDER.compareTo(primitive.getAsBigDecimal()) == 0;
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

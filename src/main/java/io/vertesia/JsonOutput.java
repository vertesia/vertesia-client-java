package io.vertesia;

import com.google.gson.JsonElement;
import com.google.gson.TypeAdapter;
import com.google.gson.stream.JsonWriter;
import java.io.IOException;

/**
 * Writes the JSON trees of the generated models.
 *
 * <p>A model's tree holds a {@code null} member for each property the model leaves unset, and the
 * client's writer drops every {@code null} member to leave them out. That writer would also drop
 * the {@code null} members of a union variant kept as the JSON it was received as. So each model
 * leaves out its own unset properties, and the tree is then written with its {@code null} members.
 * scripts/patch_forward_compat_validation.py routes the generated writes through this class.
 */
public final class JsonOutput {
    private JsonOutput() {}

    /** Writes a model's tree without the properties it leaves unset. */
    public static void writeModel(
            JsonWriter out, TypeAdapter<JsonElement> adapter, JsonElement tree) throws IOException {
        if (tree.isJsonObject()) {
            tree.getAsJsonObject().entrySet().removeIf(member -> member.getValue().isJsonNull());
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
}

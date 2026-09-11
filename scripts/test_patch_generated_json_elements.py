#!/usr/bin/env python3
"""Tests the generated-model JsonElement annotation patch."""

from __future__ import annotations

import pathlib
import tempfile
import unittest

from patch_generated_json_elements import (
    ADAPTER_ANNOTATION,
    MAP_ADAPTER_ANNOTATION,
    patch_nullable_container_defaults,
    patch_model,
    patch_streaming_writers,
    referenced_schemas,
)


class PatchGeneratedJsonElementsTest(unittest.TestCase):
    def test_annotates_direct_json_element_fields_idempotently(self) -> None:
        source = """\
package io.vertesia.model;

import com.google.gson.JsonElement;

public class Example {
    private JsonElement value = new HashMap<>();
    private java.util.Map<String, JsonElement> metadata;
    private Map<String, JsonElement> options = new HashMap<>();
}
"""
        with tempfile.TemporaryDirectory() as directory:
            path = pathlib.Path(directory) / "Example.java"
            path.write_text(source)

            self.assertEqual(2, patch_model(path))
            self.assertEqual(0, patch_model(path))
            patched = path.read_text()

        self.assertEqual(1, patched.count(ADAPTER_ANNOTATION))
        self.assertEqual(1, patched.count(MAP_ADAPTER_ANNOTATION))
        self.assertIn(f"    {ADAPTER_ANNOTATION}\n    private JsonElement value;", patched)
        self.assertNotIn("JsonElement value = new HashMap", patched)
        self.assertIn(f"    {MAP_ADAPTER_ANNOTATION}\n    private Map<String, JsonElement> options;", patched)
        self.assertNotIn("JsonElement> options = new HashMap", patched)
        self.assertNotIn(f"{ADAPTER_ANNOTATION}\n    private java.util.Map", patched)

    def test_recognizes_spotless_formatted_adapter_annotation(self) -> None:
        source = """\
@com.google.gson.annotations.JsonAdapter(
        value = io.vertesia.gson.NullPreservingJsonElementTypeAdapter.class,
        nullSafe = false)
private JsonElement value;
"""
        with tempfile.TemporaryDirectory() as directory:
            path = pathlib.Path(directory) / "Example.java"
            path.write_text(source)

            self.assertEqual(0, patch_model(path))
            patched = path.read_text()

        self.assertEqual(1, patched.count("NullPreservingJsonElementTypeAdapter.class"))

    def test_streams_generated_tree_writers_idempotently(self) -> None:
        source = """\
JsonObject obj = thisAdapter.toJsonTree(value).getAsJsonObject();
elementAdapter.write(out, obj);
JsonElement element = adapterBranch.toJsonTree((Branch) value.getActualInstance());
elementAdapter.write(out, element);
"""
        with tempfile.TemporaryDirectory() as directory:
            path = pathlib.Path(directory) / "Example.java"
            path.write_text(source)

            self.assertEqual((1, 1), patch_streaming_writers(path))
            self.assertEqual((0, 0), patch_streaming_writers(path))
            patched = path.read_text()

        self.assertIn("thisAdapter.write(out, value);", patched)
        self.assertIn("adapterBranch.write(out, (Branch) value.getActualInstance());", patched)

    def test_finds_transitive_schema_closure(self) -> None:
        document = {
            "components": {
                "schemas": {
                    "Root": {"oneOf": [{"$ref": "#/components/schemas/Branch"}]},
                    "Branch": {"properties": {"value": {"$ref": "#/components/schemas/Leaf"}}},
                    "Leaf": {"type": "object"},
                    "Unrelated": {"type": "object"},
                }
            }
        }

        self.assertEqual({"Root", "Branch", "Leaf"}, referenced_schemas(document, "Root"))

    def test_clears_only_nullable_container_defaults(self) -> None:
        source = """\
@jakarta.annotation.Nullable private List<String> optional = new ArrayList<>();
@jakarta.annotation.Nonnull
private List<String> required = new ArrayList<>();
@jakarta.annotation.Nullable private Map<String, JsonElement> metadata = new HashMap<>();
"""
        with tempfile.TemporaryDirectory() as directory:
            path = pathlib.Path(directory) / "Example.java"
            path.write_text(source)

            self.assertEqual(2, patch_nullable_container_defaults(path))
            self.assertEqual(0, patch_nullable_container_defaults(path))
            patched = path.read_text()

        self.assertIn("@jakarta.annotation.Nullable private List<String> optional;", patched)
        self.assertIn("private List<String> required = new ArrayList<>();", patched)
        self.assertIn("@jakarta.annotation.Nullable private Map<String, JsonElement> metadata;", patched)


if __name__ == "__main__":
    unittest.main()

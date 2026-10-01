#!/usr/bin/env python3
"""Tests the generated-model JsonElement annotation patch."""

from __future__ import annotations

import pathlib
import tempfile
import unittest

from patch_generated_json_elements import (
    ADAPTER_ANNOTATION,
    CANONICAL_ROOT_SCHEMAS,
    MAP_ADAPTER_ANNOTATION,
    generated_inline_models,
    mapped_object_fields,
    patch_nullable_container_defaults,
    patch_mapped_object_validation,
    patch_model,
    patch_required_nullable_fields,
    patch_streaming_writers,
    referenced_schemas,
    required_nullable_fields,
)


class PatchGeneratedJsonElementsTest(unittest.TestCase):
    def test_required_nullable_fields_are_schema_derived(self) -> None:
        self.assertEqual({"model", "version"}, required_nullable_fields({
            "properties": {
                "model": {"anyOf": [{"type": "string"}, {"type": "null"}]},
                "version": {"type": ["string", "null"]},
                "optional": {"type": ["string", "null"]},
                "name": {"type": "string"},
            },
            "required": ["model", "version", "name"],
        }))

    def test_required_nullable_field_patch_is_scoped_and_idempotent(self) -> None:
        source = '\n'.join([
            'public static final String SERIALIZED_NAME_MODEL = "model";',
            '@SerializedName(SERIALIZED_NAME_MODEL)',
            '@jakarta.annotation.Nullable',
            'private String model;',
            'public static final String SERIALIZED_NAME_OPTIONAL = "optional";',
            '@SerializedName(SERIALIZED_NAME_OPTIONAL)',
            'private String optional;',
        ])
        with tempfile.TemporaryDirectory() as directory:
            path = pathlib.Path(directory) / "Example.java"
            path.write_text(source)
            self.assertEqual(1, patch_required_nullable_fields(path, {"model"}))
            self.assertEqual(0, patch_required_nullable_fields(path, {"model"}))
            formatted = path.read_text().replace(
                'SERIALIZED_NAME_MODEL = "model";',
                'SERIALIZED_NAME_MODEL =\n        "model";',
            ).replace(
                '@com.google.gson.annotations.JsonAdapter(value = ',
                '@com.google.gson.annotations.JsonAdapter(\n        value = ',
            ).replace(', nullSafe = false)', ',\n        nullSafe = false)')
            path.write_text(formatted)
            self.assertEqual(0, patch_required_nullable_fields(path, {"model"}))
            self.assertEqual(1, path.read_text().count("RequiredNullableTypeAdapterFactory.class"))
            with self.assertRaisesRegex(ValueError, "missing"):
                patch_required_nullable_fields(path, {"unknown"})

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

    def test_additional_properties_tree_retains_explicit_null_without_optional_nulls(self) -> None:
        source = """\
JsonObject obj = thisAdapter.toJsonTree(value).getAsJsonObject();
obj.remove("additionalProperties");
if (value.getAdditionalProperties() != null) { /* generated flattening */ }
elementAdapter.write(out, obj);
"""
        with tempfile.TemporaryDirectory() as directory:
            path = pathlib.Path(directory) / "Resume.java"
            path.write_text(source)
            self.assertEqual((1, 0), patch_streaming_writers(path))
            self.assertEqual((0, 0), patch_streaming_writers(path))
            patched = path.read_text()
        self.assertIn("canonicalTreeWriter.setSerializeNulls(false)", patched)
        self.assertIn("thisAdapter.write(canonicalTreeWriter, value)", patched)
        self.assertIn('obj.remove("additionalProperties")', patched)
        self.assertIn("out.setSerializeNulls(true)", patched)
        self.assertIn("finally {", patched)
        self.assertIn("out.setSerializeNulls(canonicalSerializeNulls)", patched)

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

    def test_derives_nested_inline_union_model_names(self) -> None:
        document = {
            "components": {
                "schemas": {
                    "AppendRunConversationProgramTurnPayload": {
                        "oneOf": [
                            {"type": "object", "properties": {"purpose": {"const": "controller"}}},
                            {
                                "type": "object",
                                "properties": {
                                    "purpose": {"const": "terminal"},
                                    "result": {
                                        "oneOf": [
                                            {"properties": {"type": {"const": "text"}}},
                                            {
                                                "properties": {
                                                    "type": {"const": "json"},
                                                    "value": {
                                                        "$ref": "#/components/schemas/ConversationJsonValue"
                                                    },
                                                }
                                            },
                                        ]
                                    },
                                },
                            },
                        ]
                    },
                    "ConversationJsonValue": {},
                }
            }
        }

        self.assertEqual(
            {
                "AppendRunConversationProgramTurnPayload",
                "AppendRunConversationProgramTurnPayloadOneOf",
                "AppendRunConversationProgramTurnPayloadOneOf1",
                "AppendRunConversationProgramTurnPayloadOneOf1Result",
                "AppendRunConversationProgramTurnPayloadOneOf1ResultOneOf",
                "AppendRunConversationProgramTurnPayloadOneOf1ResultOneOf1",
                "ConversationJsonValue",
            },
            set(generated_inline_models(document, "AppendRunConversationProgramTurnPayload")),
        )

    def test_native_resume_closure_reaches_inline_objects_and_array_items(self) -> None:
        for root in ("ExperimentalCanonicalUserMessagePayload", "ExperimentalCanonicalToolResultsPayload"):
            self.assertIn(root, CANONICAL_ROOT_SCHEMAS)
            document = {"components": {"schemas": {
                root: {"properties": {
                    "result_schema": {"$ref": "#/components/schemas/ExperimentalCanonicalInteractionResultSchemaInput"},
                    "input_append": {"type": "object", "properties": {
                        "records": {"type": "object", "properties": {
                            "turns": {"type": "array", "items": {"type": "object", "properties": {
                                "kind": {"const": "user"},
                                "blocks": {"type": "array", "items": {"$ref": "#/components/schemas/ConversationJsonBlock"}},
                            }}},
                        }},
                    }},
                }},
                "ExperimentalCanonicalInteractionResultSchemaInput": {},
                "ConversationJsonBlock": {"properties": {"value": {"$ref": "#/components/schemas/ConversationJsonValue"}}},
                "ConversationJsonValue": {},
            }}}
            closure = generated_inline_models(document, root)
            self.assertIn(root + "InputAppendRecordsTurnsInner", closure)
            self.assertIn("ConversationJsonBlock", closure)
            self.assertIn("ConversationJsonValue", closure)
            self.assertEqual({"result_schema": True}, mapped_object_fields(document, root))

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

    def test_replaces_impossible_json_element_validation_with_object_guards(self) -> None:
        source = """\
JsonObject jsonObj = jsonElement.getAsJsonObject();
if (jsonObj.get("result_schema") != null && !jsonObj.get("result_schema").isJsonNull()) {
  JsonElement.validateJsonElement(jsonObj.get("result_schema"));
}
"""
        with tempfile.TemporaryDirectory() as directory:
            path = pathlib.Path(directory) / "Example.java"
            path.write_text(source)

            fields = {"data": False, "result_schema": True}
            self.assertEqual(2, patch_mapped_object_validation(path, fields))
            self.assertEqual(0, patch_mapped_object_validation(path, fields))
            patched = path.read_text()

        self.assertNotIn("JsonElement.validateJsonElement", patched)
        self.assertIn(
            'jsonObj.has("data") && !jsonObj.get("data").isJsonObject()', patched
        )
        self.assertIn(
            'jsonObj.has("result_schema") && !jsonObj.get("result_schema").isJsonNull()', patched
        )

    def test_object_guards_do_not_modify_additional_properties_reader(self) -> None:
        source = """\
public static void validateJsonElement(JsonElement jsonElement) throws IOException {
    JsonObject jsonObj = jsonElement.getAsJsonObject();
    JsonElement.validateJsonElement(jsonObj.get("result_schema"));
}
public static class CustomTypeAdapterFactory implements TypeAdapterFactory {
    public Object read(JsonElement jsonElement) {
        JsonObject jsonObj = jsonElement.getAsJsonObject();
    }
}
"""
        with tempfile.TemporaryDirectory() as directory:
            path = pathlib.Path(directory) / "Resume.java"
            path.write_text(source)
            self.assertEqual(1, patch_mapped_object_validation(path, {"result_schema": True}))
            self.assertEqual(0, patch_mapped_object_validation(path, {"result_schema": True}))
            patched = path.read_text()
        validator, reader = patched.split("public static class CustomTypeAdapterFactory", 1)
        self.assertIn('if (jsonObj.has("result_schema")', validator)
        self.assertNotIn('if (jsonObj.has("result_schema")', reader)
        self.assertNotIn("JsonElement.validateJsonElement", patched)

    def test_finds_only_named_object_mappings_and_nullability(self) -> None:
        document = {
            "components": {
                "schemas": {
                    "Request": {
                        "properties": {
                            "data": {"$ref": "#/components/schemas/ConversationJsonObject"},
                            "result_schema": {
                                "$ref": "#/components/schemas/ExperimentalCanonicalInteractionResultSchemaInput"
                            },
                            "value": {"$ref": "#/components/schemas/ConversationJsonValue"},
                        }
                    }
                }
            }
        }

        self.assertEqual(
            {"data": False, "result_schema": True}, mapped_object_fields(document, "Request")
        )


if __name__ == "__main__":
    unittest.main()

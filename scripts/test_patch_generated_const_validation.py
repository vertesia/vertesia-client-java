#!/usr/bin/env python3
"""Tests the generated-model OpenAPI const validation patch."""

from __future__ import annotations

import pathlib
import tempfile
import unittest

from patch_generated_const_validation import (
    MARKER,
    patch_canonical_shape,
    patch_nested_union_adapters,
    patch_union_selectors,
    const_model_schemas,
    patch_model,
    scalar_constants,
    union_discriminator_values,
)


class PatchGeneratedConstValidationTest(unittest.TestCase):
    def test_discovers_only_scalar_constants(self) -> None:
        schema = {
            "properties": {
                "kind": {"type": "string", "const": "user"},
                "count": {"type": "number", "const": 1},
                "status": {"type": "string"},
            }
        }

        self.assertEqual([("kind", "user", False), ("count", 1, False)], scalar_constants(schema))

    def test_discovers_constants_in_nested_terminal_inline_models(self) -> None:
        document = {
            "components": {
                "schemas": {
                    "AppendRunConversationProgramTurnPayload": {
                        "oneOf": [
                            {
                                "properties": {"purpose": {"const": "controller_corrective"}},
                                "required": ["purpose"],
                            },
                            {
                                "properties": {
                                    "purpose": {"const": "terminal_result"},
                                    "result": {
                                        "oneOf": [
                                            {
                                                "properties": {"type": {"const": "text"}},
                                                "required": ["type"],
                                            },
                                            {
                                                "properties": {"type": {"const": "json"}},
                                                "required": ["type"],
                                            },
                                        ]
                                    },
                                },
                                "required": ["purpose", "result"],
                            },
                        ]
                    }
                }
            }
        }

        model_schemas = const_model_schemas(document)
        self.assertEqual(
            [("purpose", "terminal_result", True)],
            scalar_constants(model_schemas["AppendRunConversationProgramTurnPayloadOneOf1"]),
        )
        self.assertEqual(
            [("type", "json", True)],
            scalar_constants(
                model_schemas["AppendRunConversationProgramTurnPayloadOneOf1ResultOneOf1"]
            ),
        )

    def test_native_resume_inline_constants_are_strict(self) -> None:
        user_root = "ExperimentalCanonicalUserMessagePayload"
        tool_root = "ExperimentalCanonicalToolResultsPayload"
        document = {"components": {"schemas": {
            user_root: {"properties": {"input_append": {"type": "object", "properties": {
                "records": {"type": "object", "properties": {
                    "turns": {"type": "array", "items": {"type": "object", "properties": {
                        "kind": {"type": "string", "const": "user"},
                        "authority": {"type": "string", "const": "ordinary"},
                    }, "required": ["kind", "authority"]}},
                }},
            }}}},
            tool_root: {"properties": {"asyncCompletion": {"type": "object", "properties": {
                "canonical_output_reference": {"type": "string", "const": "conversation_output_authority_v1"},
            }, "required": ["canonical_output_reference"]}}},
        }}}
        models = const_model_schemas(document)
        self.assertEqual(
            [("kind", "user", True), ("authority", "ordinary", True)],
            scalar_constants(models[user_root + "InputAppendRecordsTurnsInner"]),
        )
        self.assertEqual(
            [("canonical_output_reference", "conversation_output_authority_v1", True)],
            scalar_constants(models[tool_root + "AsyncCompletion"]),
        )

    def test_discovers_edit_and_processing_union_constants(self) -> None:
        branches = {
            "ConversationEditAnchor": ("before_entry", "after_entry", "head", "tail"),
            "ConversationEditOperation": ("protect", "insert", "replace"),
            "ConversationAcceptedToolSelection": ("unchanged", "replace"),
            "ConversationContextChangeProposal": ("exclude", "replace_with_compaction"),
            "ConversationProcessingJobSelection": ("entries", "predecessor_output"),
            "ConversationProcessingOutputReceipt": ("proposal", "no_op", "failed", "unknown_outcome"),
        }
        schemas = {
            name: {"oneOf": [
                {"properties": {"kind": {"type": "string", "const": kind}}, "required": ["kind"]}
                for kind in kinds
            ]}
            for name, kinds in branches.items()
        }
        schemas["ConversationDocument"] = {"properties": {name: {"$ref": "#/components/schemas/" + name} for name in schemas}}
        models = const_model_schemas({"components": {"schemas": schemas}})
        for name, kinds in branches.items():
            for index, kind in enumerate(kinds):
                with self.subTest(component=name, kind=kind):
                    generated_name = name + "OneOf" + (str(index) if index else "")
                    self.assertEqual([("kind", kind, True)], scalar_constants(models[generated_name]))

    def test_union_discriminator_enum_is_exact_but_ordinary_enum_is_not(self) -> None:
        document = {"components": {"schemas": {
            "ConversationProcessingOutputReceipt": {
                "discriminator": {"propertyName": "kind"},
                "oneOf": [{"properties": {
                    "kind": {"type": "string", "enum": ["failed", "unknown_outcome"]},
                    "phase": {"type": "string", "enum": ["policy", "output"]},
                }, "required": ["kind"]}],
            },
            "ConversationProcessingOperation": {"properties": {
                "phase": {"type": "string", "enum": ["policy", "output"]},
            }},
        }}}
        document['components']['schemas']['ConversationDocument'] = {'properties': {'output': {'$ref': '#/components/schemas/ConversationProcessingOutputReceipt'}}}
        values = union_discriminator_values(document)
        self.assertEqual({"ConversationProcessingOutputReceiptOneOf": [
            ("kind", ["failed", "unknown_outcome"], True)
        ]}, values)
        source = (
            "public static void validateJsonElement(JsonElement jsonElement) {\n"
            "        JsonObject jsonObj = jsonElement.getAsJsonObject();\n}\n"
        )
        with tempfile.TemporaryDirectory() as directory:
            path = pathlib.Path(directory) / "Example.java"
            path.write_text(source)
            self.assertTrue(patch_model(path, [], values["ConversationProcessingOutputReceiptOneOf"]))
            self.assertFalse(patch_model(path, [], values["ConversationProcessingOutputReceiptOneOf"]))
            patched = path.read_text()
        self.assertIn('!"failed".equals(jsonObj.get("kind").getAsString())', patched)
        self.assertIn('!"unknown_outcome".equals(jsonObj.get("kind").getAsString())', patched)
        self.assertNotIn('jsonObj.get("phase")', patched)

    def test_referenced_union_branch_reuses_exact_shared_values(self) -> None:
        union = {
            "discriminator": {"propertyName": "kind"},
            "oneOf": [{"$ref": "#/components/schemas/SharedOutput"}],
        }
        document = {"components": {"schemas": {
            "ConversationProcessingOutputReceipt": union,
            "ConversationContextChangeProposal": union,
            "SharedOutput": {"properties": {
                "kind": {"type": "string", "enum": ["failed", "unknown_outcome"]},
            }, "required": ["kind"]},
        }}}
        document['components']['schemas']['ConversationDocument'] = {'properties': {'output': {'$ref': '#/components/schemas/ConversationProcessingOutputReceipt'}}}
        self.assertEqual({"SharedOutput": [("kind", ["failed", "unknown_outcome"], True)]},
                         union_discriminator_values(document))

    def test_referenced_shared_model_conflict_fails_closed(self) -> None:
        document = {"components": {"schemas": {
            "ConversationProcessingOutputReceipt": {
                "discriminator": {"propertyName": "kind"},
                "oneOf": [{"$ref": "#/components/schemas/SharedOutput"}],
            },
            "ConversationContextChangeProposal": {
                "discriminator": {"propertyName": "status"},
                "oneOf": [{"$ref": "#/components/schemas/SharedOutput"}],
            },
            "SharedOutput": {"properties": {
                "kind": {"type": "string", "enum": ["failed", "unknown_outcome"]},
                "status": {"type": "string", "enum": ["exclude", "replace_with_compaction"]},
            }, "required": ["kind", "status"]},
        }}}
        document['components']['schemas']['ConversationDocument'] = {'properties': {'output': {'$ref': '#/components/schemas/ConversationProcessingOutputReceipt'}}}
        with self.assertRaisesRegex(ValueError, "Conflicting discriminator enum in shared model"):
            union_discriminator_values(document)

    def test_rejects_optional_discriminator_provenance(self) -> None:
        document = {"components": {"schemas": {
            "ConversationProcessingOutputReceipt": {
                "discriminator": {"propertyName": "kind"},
                "oneOf": [{"properties": {"kind": {"enum": ["failed", "unknown_outcome"]}}}],
            },
        }}}
        document['components']['schemas']['ConversationDocument'] = {'properties': {'output': {'$ref': '#/components/schemas/ConversationProcessingOutputReceipt'}}}
        with self.assertRaisesRegex(ValueError, "discriminator must be required"):
            union_discriminator_values(document)

    def test_injects_exact_check_idempotently(self) -> None:
        source = """\
public class Example {
    public static void validateJsonElement(JsonElement jsonElement) {
        JsonObject jsonObj = jsonElement.getAsJsonObject();
    }
}
"""
        with tempfile.TemporaryDirectory() as directory:
            path = pathlib.Path(directory) / "Example.java"
            path.write_text(source)

            self.assertTrue(patch_model(path, [("kind", "user", True)]))
            self.assertFalse(patch_model(path, [("kind", "user", True)]))
            patched = path.read_text()

        self.assertEqual(1, patched.count(MARKER))
        self.assertIn('jsonObj.get("kind") == null', patched)
        self.assertIn('getAsJsonPrimitive().isString()', patched)
        self.assertIn('!"user".equals(jsonObj.get("kind").getAsString())', patched)

    def test_optional_const_is_checked_only_when_present_and_escapes_message(self) -> None:
        source = """\
public class Example {
    public static void validateJsonElement(JsonElement jsonElement) {
        JsonObject jsonObj = jsonElement.getAsJsonObject();
    }
}
"""
        with tempfile.TemporaryDirectory() as directory:
            path = pathlib.Path(directory) / "Example.java"
            path.write_text(source)

            patch_model(path, [("quoted", '100% say "hello"', False)])
            patched = path.read_text()

        self.assertIn('jsonObj.get("quoted") != null', patched)
        self.assertIn('!"100% say \\"hello\\"".equals', patched)
        self.assertIn('equal `100% say \\"hello\\"`', patched)
        self.assertNotIn("String.format", patched)

    def test_numeric_and_boolean_constants_use_exact_primitive_types(self):
        with tempfile.TemporaryDirectory() as directory:
            path = pathlib.Path(directory) / "Example.java"
            path.write_text("        JsonObject jsonObj = jsonElement.getAsJsonObject();\n")
            patch_model(path, [("version", 2, True), ("enabled", False, True)])
            source = path.read_text()
            self.assertIn("isNumber()", source)
            self.assertIn('new java.math.BigDecimal("2").compareTo', source)
            self.assertIn("isBoolean()", source)
            self.assertNotIn("getAsDouble", source)

    def test_nested_union_adapter_uses_exact_child_factory_and_is_idempotent(self):
        schemas = {"Child": {"oneOf": [{"type": "string"}, {"type": "number"}]},
                   "Second": {"anyOf": [{"type": "string"}, {"type": "boolean"}]},
                   "Plain": {"type": "object"}}
        parent = {"anyOf": [{"$ref": "#/components/schemas/Child"},
                             {"$ref": "#/components/schemas/Second"},
                             {"$ref": "#/components/schemas/Plain"}]}
        source = "\n".join(f"gson.getDelegateAdapter(this, TypeToken.get({name}.class));"
                           for name in schemas)
        with tempfile.TemporaryDirectory() as directory:
            path = pathlib.Path(directory) / "Parent.java"
            path.write_text(source)
            self.assertTrue(patch_nested_union_adapters(path, parent, schemas))
            self.assertFalse(patch_nested_union_adapters(path, parent, schemas))
            text = path.read_text()
            for name in ("Child", "Second"):
                self.assertIn(f"new {name}.CustomTypeAdapterFactory().create", text)
            self.assertIn("gson.getDelegateAdapter(this, TypeToken.get(Plain.class))", text)
            self.assertNotIn("setActualInstance", text)

    def test_closed_object_and_primitive_reference_patterns_are_schema_derived(self):
        schema = {"type": "object", "additionalProperties": False,
                  "properties": {"pointer": {"$ref": "#/components/schemas/Pointer"}}}
        with tempfile.TemporaryDirectory() as directory:
            path = pathlib.Path(directory) / "Example.java"
            path.write_text("        JsonObject jsonObj = jsonElement.getAsJsonObject();\n")
            self.assertTrue(patch_canonical_shape(path, schema, {"Pointer": {"type": "string", "pattern": "^/$"}}))
            self.assertFalse(patch_canonical_shape(path, schema, {}))
            self.assertIn('java.util.Arrays.asList("pointer")', path.read_text())
            self.assertIn('Pattern.compile("^/$")', path.read_text())

    def test_ordinary_string_enums_reject_json_type_coercion_without_closing_values(self):
        schema = {"type": "object", "properties": {
            "policy": {"$ref": "#/components/schemas/Policy"},
            "nullable_policy": {"type": ["string", "null"], "enum": ["exact", None]},
            "mixed": {"type": ["string", "integer"]},
        }}
        with tempfile.TemporaryDirectory() as directory:
            path = pathlib.Path(directory) / "Example.java"
            path.write_text("        JsonObject jsonObj = jsonElement.getAsJsonObject();\n")
            self.assertTrue(patch_canonical_shape(path, schema, {
                "Policy": {"type": "string", "enum": ["exact", "identified_estimate"]},
            }))
            patched = path.read_text()
            self.assertIn('Invalid canonical string type: policy', patched)
            self.assertIn('jsonObj.get("policy").getAsJsonPrimitive().isString()', patched)
            self.assertIn('jsonObj.get("nullable_policy").getAsJsonPrimitive().isString()', patched)
            self.assertIn('!jsonObj.get("nullable_policy").isJsonNull()', patched)
            self.assertNotIn('Invalid canonical string type: mixed', patched)
            # Type checking must not change ordinary unknown-string enum compatibility.
            self.assertNotIn('"exact"', patched)
            self.assertNotIn('"identified_estimate"', patched)
            self.assertFalse(patch_canonical_shape(path, schema, {}))
            self.assertEqual(patched, path.read_text())

    def test_inline_selector_literal_mappings_are_exact_and_idempotent(self):
        schema = {"oneOf": [{"properties": {"kind": {"const": "a"}}, "required": ["kind"]},
                            {"properties": {"kind": {"enum": ["b", "c"]}}, "required": ["kind"]}],
                  "discriminator": {"propertyName": "kind"}}
        document = {"components": {"schemas": {"ConversationContextChangeProposal": schema}}}
        source = (".registerTypeSelector(io.vertesia.model.ConversationContextChangeProposal.class,\n"
                  "return getClassByDiscriminator();")
        with tempfile.TemporaryDirectory() as directory:
            path = pathlib.Path(directory) / "JSON.java"
            path.write_text(source)
            self.assertEqual(1, patch_union_selectors(path, document))
            self.assertEqual(0, patch_union_selectors(path, document))
            self.assertIn('put("a", io.vertesia.model.ConversationContextChangeProposalOneOf.class)', path.read_text())
            self.assertIn('put("c", io.vertesia.model.ConversationContextChangeProposalOneOf1.class)', path.read_text())
            schema['oneOf'][1]['properties']['kind']['enum'].append('a')
            with self.assertRaisesRegex(ValueError, "Ambiguous"):
                patch_union_selectors(path, document)


if __name__ == "__main__":
    unittest.main()

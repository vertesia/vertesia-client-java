#!/usr/bin/env python3
"""Tests the generated-model OpenAPI const validation patch."""

from __future__ import annotations

import pathlib
import tempfile
import unittest

from patch_generated_const_validation import (
    MARKER,
    const_model_schemas,
    patch_model,
    string_constants,
)


class PatchGeneratedConstValidationTest(unittest.TestCase):
    def test_discovers_only_string_constants(self) -> None:
        schema = {
            "properties": {
                "kind": {"type": "string", "const": "user"},
                "count": {"type": "number", "const": 1},
                "status": {"type": "string"},
            }
        }

        self.assertEqual([("kind", "user", False)], string_constants(schema))

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
            string_constants(model_schemas["AppendRunConversationProgramTurnPayloadOneOf1"]),
        )
        self.assertEqual(
            [("type", "json", True)],
            string_constants(
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
            string_constants(models[user_root + "InputAppendRecordsTurnsInner"]),
        )
        self.assertEqual(
            [("canonical_output_reference", "conversation_output_authority_v1", True)],
            string_constants(models[tool_root + "AsyncCompletion"]),
        )

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


if __name__ == "__main__":
    unittest.main()

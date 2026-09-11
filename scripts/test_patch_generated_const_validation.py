#!/usr/bin/env python3
"""Tests the generated-model OpenAPI const validation patch."""

from __future__ import annotations

import pathlib
import tempfile
import unittest

from patch_generated_const_validation import MARKER, patch_model, string_constants


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

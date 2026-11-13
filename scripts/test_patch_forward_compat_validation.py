#!/usr/bin/env python3
"""Regression tests for patch_forward_compat_validation.py.

Run with: python3 -m unittest discover -s scripts -p 'test_*.py'
"""

from __future__ import annotations

import pathlib
import sys
import unittest

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))

import json  # noqa: E402

from patch_forward_compat_validation import (  # noqa: E402
    patch_schema_constants,
    patch_union_fallback,
    union_discriminators,
)

SCHEMA = {"properties": {"type": {"type": "string", "const": "thoughts"}}}

# Shape of openapi-generator v7 okhttp-gson output before Spotless runs (modelInnerEnum.mustache and
# pojo.mustache): the enum's validateJsonElement closes at 4 spaces, and the model's own
# validateJsonElement below it has braces at 8 spaces.
RAW = """public class ThoughtsResult {
  @JsonAdapter(TypeEnum.Adapter.class)
  public enum TypeEnum {
    THOUGHTS("thoughts"),

    UNKNOWN_DEFAULT_OPEN_API("unknown_default_open_api");

    public static void validateJsonElement(JsonElement jsonElement) throws IOException {
      String value = jsonElement.getAsString();
      TypeEnum.fromValue(value);
    }
  }

  public static final String SERIALIZED_NAME_TYPE = "type";
  @SerializedName(SERIALIZED_NAME_TYPE)
  private TypeEnum type;

  public static void validateJsonElement(JsonElement jsonElement) throws IOException {
      if (jsonElement == null) {
        if (!ThoughtsResult.openapiRequiredFields.isEmpty()) {
          throw new IllegalArgumentException("required fields missing");
        }
      }
      TypeEnum.validateJsonElement(jsonObj.get("type"));
  }
}
"""

# The same class after Spotless (google-java-format, AOSP style), as committed in this repository.
FORMATTED = """public class ThoughtsResult {
    @JsonAdapter(TypeEnum.Adapter.class)
    public enum TypeEnum {
        THOUGHTS("thoughts"),

        UNKNOWN_DEFAULT_OPEN_API("unknown_default_open_api");

        public static void validateJsonElement(JsonElement jsonElement) throws IOException {
            String value = jsonElement.getAsString();
            TypeEnum.fromValue(value);
        }
    }

    public static final String SERIALIZED_NAME_TYPE = "type";

    @SerializedName(SERIALIZED_NAME_TYPE)
    private TypeEnum type;

    public static void validateJsonElement(JsonElement jsonElement) throws IOException {
        if (jsonElement == null) {
            if (!ThoughtsResult.openapiRequiredFields.isEmpty()) {
                throw new IllegalArgumentException("required fields missing");
            }
        }
        TypeEnum.validateJsonElement(jsonObj.get("type"));
    }
}
"""


class PatchSchemaConstantsTest(unittest.TestCase):
    def assert_patched(self, source: str) -> None:
        patched = patch_schema_constants(source, SCHEMA)

        self.assertIn('if (!"thoughts".equals(jsonElement.getAsString())) {', patched)
        self.assertIn('throw new IOException("Expected type to be thoughts");', patched)
        self.assertNotIn("TypeEnum.fromValue(value);", patched)
        # Only the enum's method body changes: everything after it in the class must survive.
        self.assertIn(source[source.index("  public static final String SERIALIZED_NAME_TYPE") :], patched)
        self.assertEqual(patched.count("{"), patched.count("}"))
        # The Test workflow re-runs the script on already patched sources.
        self.assertEqual(patch_schema_constants(patched, SCHEMA), patched)

    def test_raw_generator_output(self) -> None:
        self.assert_patched(RAW)

    def test_spotless_formatted_source(self) -> None:
        self.assert_patched(FORMATTED)

    def test_enum_without_const_is_left_unchanged(self) -> None:
        self.assertEqual(patch_schema_constants(RAW, {"properties": {"type": {"type": "string"}}}), RAW)


class PatchUnionFallbackTest(unittest.TestCase):
    ROOT = pathlib.Path(__file__).resolve().parent.parent

    def patch(self, name: str) -> tuple[str, str]:
        source = (self.ROOT / "src/main/java/io/vertesia/model" / f"{name}.java").read_text()
        spec = json.loads((self.ROOT / "spec/vertesia-openapi.json").read_text())
        discriminator = union_discriminators(spec["components"]["schemas"]).get(name)
        patched = patch_union_fallback(source, name, discriminator)
        # The Test workflow re-runs the script on already patched sources.
        self.assertEqual(patch_union_fallback(patched, name, discriminator), patched)
        self.assertEqual(patched.count("{"), patched.count("}"))
        return source, patched

    def test_discriminated_union_dispatches_and_keeps_unknown_values(self) -> None:
        _, patched = self.patch("CompletionResult")
        self.assertIn('case "text":', patched)
        self.assertEqual(patched.count('checkDiscriminator(jsonElement, "text");'), 2)
        self.assertIn("public JsonElement getRaw() {", patched)

    def test_undiscriminated_union_falls_back_after_trial(self) -> None:
        _, patched = self.patch("NodeDefinitionBranchesInner")
        self.assertIn("if (validCount != 1 && isUnknownVariant(jsonElement)) {", patched)
        self.assertNotIn("checkDiscriminator", patched)

    def test_other_models_are_left_unchanged(self) -> None:
        source = (self.ROOT / "src/main/java/io/vertesia/model/TextResult.java").read_text()
        self.assertEqual(patch_union_fallback(source, "TextResult", None), source)


if __name__ == "__main__":
    unittest.main()

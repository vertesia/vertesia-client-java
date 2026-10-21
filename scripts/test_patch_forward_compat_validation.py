#!/usr/bin/env python3
"""Regression tests for patch_forward_compat_validation.py.

Run with: python3 -m unittest discover -s scripts -p 'test_*.py'
"""

from __future__ import annotations

import pathlib
import sys
import unittest

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))

from patch_forward_compat_validation import patch_schema_constants  # noqa: E402

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


if __name__ == "__main__":
    unittest.main()

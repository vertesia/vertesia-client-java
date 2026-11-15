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
    enum_properties,
    patch_model_writes,
    patch_schema_constants,
    patch_union_enum_values,
    patch_union_fallback,
    patch_unknown_enum_values,
    standalone_enums,
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



class PatchModelWritesTest(unittest.TestCase):
    SOURCE = (
        "import io.vertesia.JSON;\n"
        "class TimerTask {\n"
        "    JsonObject obj = thisAdapter.toJsonTree(value).getAsJsonObject();\n"
        "    elementAdapter.write(out, obj);\n"
        "    elementAdapter.write(out, null);\n"
        "                                JsonElement element = adapterTimerTask.toJsonTree(value);\n"
        "                                elementAdapter.write(out, element);\n"
        "}\n"
    )

    def test_model_writes_go_through_json_output(self) -> None:
        patched = patch_model_writes(self.SOURCE)
        self.assertIn("import io.vertesia.JSON;\nimport io.vertesia.JsonOutput;\n", patched)
        self.assertIn("    JsonOutput.writeModel(out, elementAdapter, obj, value);\n", patched)
        # Too long for one formatted line, so wrapped as google-java-format wraps it.
        self.assertIn(
            "                                JsonOutput.writeModel(\n"
            "                                        out, elementAdapter, element, value.getActualInstance());\n",
            patched,
        )
        self.assertIn("    elementAdapter.write(out, null);\n", patched)
        self.assertEqual(patch_model_writes(patched), patched)

    def test_source_without_json_writes_is_left_unchanged(self) -> None:
        source = "package io.vertesia.model;\nenum AgentRunStatus {}\n"
        self.assertEqual(patch_model_writes(source), source)


# A closed model's adapter (pojo.mustache) as raw generator output and as committed after Spotless.
CLOSED_RAW = """import io.vertesia.JSON;
import java.io.IOException;

public class AgentRunSummary {
  public static final String SERIALIZED_NAME_STATUS = "status";
  @SerializedName(SERIALIZED_NAME_STATUS)
  @javax.annotation.Nonnull
  private AgentRunStatus status;

  public static final String SERIALIZED_NAME_TAGS = "tags";
  @SerializedName(SERIALIZED_NAME_TAGS)
  private List<String> tags = new ArrayList<>();


  public static HashSet<String> openapiFields;

           @Override
           public AgentRunSummary read(JsonReader in) throws IOException {
             JsonElement jsonElement = elementAdapter.read(in);
             validateJsonElement(jsonElement);
             return thisAdapter.fromJsonTree(jsonElement);
           }
}
"""

CLOSED_FORMATTED = """import io.vertesia.JSON;
import io.vertesia.JsonOutput;
import java.io.IOException;

public class AgentRunSummary {
    public static final String SERIALIZED_NAME_STATUS = "status";

    @SerializedName(SERIALIZED_NAME_STATUS)
    @jakarta.annotation.Nonnull
    private AgentRunStatus status;

    public static final String SERIALIZED_NAME_TAGS = "tags";

    @SerializedName(SERIALIZED_NAME_TAGS)
    @jakarta.annotation.Nullable private List<String> tags = new ArrayList<>();

    public static HashSet<String> openapiFields;

                        @Override
                        public AgentRunSummary read(JsonReader in) throws IOException {
                            JsonElement jsonElement = elementAdapter.read(in);
                            validateJsonElement(jsonElement);
                            return thisAdapter.fromJsonTree(jsonElement);
                        }
}
"""

# An open model with an inline enum array, whose long name google-java-format puts on its own line.
OPEN_FORMATTED = """import io.vertesia.JSON;
import io.vertesia.JsonOutput;

public class AskUserWebhookConfigurationWithAVeryLongGeneratedNameThatDoesNotFitOnALine {
    public enum EventsEnum {
        REQUESTED("requested"),

        UNKNOWN_DEFAULT_OPEN_API("unknown_default_open_api");
    }

    @SerializedName(SERIALIZED_NAME_EVENTS)
    @jakarta.annotation.Nullable private List<EventsEnum> events = new ArrayList<>();

    private Map<String, Object> additionalProperties;

    public static HashSet<String> openapiFields;

                            // store additional fields in the deserialized instance
                            AskUserWebhookConfigurationWithAVeryLongGeneratedNameThatDoesNotFitOnALine
                                    instance = thisAdapter.fromJsonTree(jsonObj);
                            for (Map.Entry<String, JsonElement> entry : jsonObj.entrySet()) {
                            }
                            return instance;
}
"""


class PatchUnknownEnumValuesTest(unittest.TestCase):
    ROOT = pathlib.Path(__file__).resolve().parent.parent
    ENUMS = {"AgentRunStatus"}

    def patch(self, source: str, name: str) -> str:
        patched = patch_unknown_enum_values(source, name, self.ENUMS)
        # The Test workflow re-runs the script on already patched sources.
        self.assertEqual(patch_unknown_enum_values(patched, name, self.ENUMS), patched)
        self.assertEqual(patched.count("{"), patched.count("}"))
        return patched

    def test_enum_properties(self) -> None:
        self.assertEqual(enum_properties(CLOSED_FORMATTED, self.ENUMS), ["status"])
        self.assertEqual(enum_properties(CLOSED_RAW, self.ENUMS), ["status"])
        self.assertEqual(enum_properties(OPEN_FORMATTED, set()), ["events"])
        self.assertEqual(enum_properties(CLOSED_FORMATTED, set()), [])

    def test_standalone_enums(self) -> None:
        enums = standalone_enums(sorted((self.ROOT / "src/main/java/io/vertesia/model").glob("*.java")))
        self.assertIn("AgentRunStatus", enums)
        self.assertIn("KnownAuditAction", enums)
        self.assertNotIn("AuditAction", enums)
        self.assertNotIn("AccountApiKey", enums)

    def test_raw_generator_output(self) -> None:
        patched = self.patch(CLOSED_RAW, "AgentRunSummary")
        self.assertIn(
            "  // The JSON received for the enum properties whose value this client version does not know.\n"
            "  private transient JsonObject receivedEnumValues;\n\n"
            "  public static HashSet<String> openapiFields;\n",
            patched,
        )
        self.assertIn(
            "             return UnknownEnumValues.keep(jsonElement, thisAdapter.fromJsonTree(jsonElement));\n",
            patched,
        )
        self.assertIn("import io.vertesia.JSON;\nimport io.vertesia.UnknownEnumValues;\n", patched)

    def test_spotless_formatted_source(self) -> None:
        patched = self.patch(CLOSED_FORMATTED, "AgentRunSummary")
        self.assertIn(
            "    // The JSON received for the enum properties whose value this client version does not know.\n"
            "    private transient JsonObject receivedEnumValues;\n\n"
            "    public static HashSet<String> openapiFields;\n",
            patched,
        )
        # Too long for one formatted line, so wrapped as google-java-format wraps it.
        self.assertIn(
            "                            return UnknownEnumValues.keep(\n"
            "                                    jsonElement, thisAdapter.fromJsonTree(jsonElement));\n",
            patched,
        )
        self.assertIn("import io.vertesia.JsonOutput;\nimport io.vertesia.UnknownEnumValues;\n", patched)

    def test_open_model_keeps_values_before_reading_additional_properties(self) -> None:
        name = "AskUserWebhookConfigurationWithAVeryLongGeneratedNameThatDoesNotFitOnALine"
        patched = self.patch(OPEN_FORMATTED, name)
        self.assertIn(
            "                                    instance = thisAdapter.fromJsonTree(jsonObj);\n"
            "                            UnknownEnumValues.keep(jsonObj, instance);\n"
            "                            for (Map.Entry",
            patched,
        )

    def test_model_without_enums_is_left_unchanged(self) -> None:
        source = CLOSED_FORMATTED.replace("AgentRunStatus status", "String status")
        self.assertEqual(patch_unknown_enum_values(source, "AgentRunSummary", self.ENUMS), source)

    def test_union_with_an_enum_branch(self) -> None:
        source = (self.ROOT / "src/main/java/io/vertesia/model/AuditAction.java").read_text()
        source = patch_union_fallback(source, "AuditAction", None)
        patched = patch_union_enum_values(source, {"KnownAuditAction"})
        self.assertEqual(patch_union_enum_values(patched, {"KnownAuditAction"}), patched)
        # In read() and in validateJsonElement().
        self.assertEqual(
            patched.count(
                "KnownAuditAction.validateJsonElement(jsonElement);\n"
                + " " * 32
                + "UnknownEnumValues.requireKnown(jsonElement, KnownAuditAction.class);\n"
            ),
            1,
        )
        self.assertEqual(patched.count("UnknownEnumValues.requireKnown("), 2)
        self.assertEqual(
            patched.count(
                "ret.setActualInstance(actualAdapter.fromJsonTree(jsonElement));\n"
                + " " * 32
                + "UnknownEnumValues.keep(jsonElement, ret.getActualInstance());\n"
            ),
            2,
        )

    def test_union_variants_are_kept_before_the_lossless_check(self) -> None:
        source = (self.ROOT / "src/main/java/io/vertesia/model/ModelOptions.java").read_text()
        source = patch_union_fallback(source, "ModelOptions", None, lossless=True)
        patched = patch_union_enum_values(source, set())
        self.assertNotIn("requireKnown", patched)
        self.assertIn(
            "UnknownEnumValues.keep(jsonElement, ret.getActualInstance());\n"
            + " " * 32
            + "checkLossless(jsonElement, ret.getActualInstance());\n",
            patched,
        )

    def test_other_models_are_left_unchanged_by_the_union_patch(self) -> None:
        self.assertEqual(patch_union_enum_values(CLOSED_FORMATTED, {"AgentRunStatus"}), CLOSED_FORMATTED)


if __name__ == "__main__":
    unittest.main()

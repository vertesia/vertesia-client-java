#!/usr/bin/env python3
"""Restore strict OpenAPI ``const`` validation with forward-compatible enums.

``enumUnknownDefaultCase`` is useful for ordinary response enums, but the Java
generator also represents string ``const`` properties as enums. Its generated
validator accepts the synthetic unknown value for those properties, which can
make several discriminated-union branches match the same payload. Inject an
exact check into object-model validators and parent-union discriminator enums,
while retaining unknown-enum handling for ordinary enum fields.
"""

from __future__ import annotations

import json
import pathlib
from collections.abc import Mapping

from patch_generated_json_elements import generated_inline_models


SPEC_PATH = pathlib.Path("spec/vertesia-openapi.json")
MODEL_ROOT = pathlib.Path("src/main/java/io/vertesia/model")
JSON_OBJECT_DECLARATION = "        JsonObject jsonObj = jsonElement.getAsJsonObject();\n"
MARKER = "        // Enforce OpenAPI const values independently of enum unknown-default handling.\n"
INLINE_CONST_ROOT_SCHEMAS = (
    "ExperimentalCanonicalUserMessagePayload",
    "ExperimentalCanonicalToolResultsPayload",
    "AppendRunConversationProgramTurnPayload",
    "ImportAgentRunConversationArchivePayload",
    "ImportAgentRunConversationArchiveResponse",
    "ConversationEditAnchor",
    "ConversationEditOperation",
    "ConversationAcceptedToolSelection",
    "ConversationContextChangeProposal",
    "ConversationProcessingJobSelection",
    "ConversationProcessingOutputReceipt",
)


def string_constants(schema: Mapping[str, object]) -> list[tuple[str, str, bool]]:
    properties = schema.get("properties")
    if not isinstance(properties, Mapping):
        return []
    required_value = schema.get("required")
    required = set(required_value) if isinstance(required_value, list) else set()
    constants: list[tuple[str, str, bool]] = []
    for property_name, value in properties.items():
        if isinstance(property_name, str) and isinstance(value, Mapping):
            constant = value.get("const")
            if isinstance(constant, str):
                constants.append((property_name, constant, property_name in required))
    return constants


def const_model_schemas(document: Mapping[str, object]) -> dict[str, Mapping[str, object]]:
    schemas = document.get("components", {}).get("schemas", {})
    if not isinstance(schemas, Mapping):
        return {}
    model_schemas = {
        name: schema
        for name, schema in schemas.items()
        if isinstance(name, str) and isinstance(schema, Mapping)
    }
    for root in INLINE_CONST_ROOT_SCHEMAS:
        model_schemas.update(generated_inline_models(document, root))
    return model_schemas


def union_discriminator_values(document: Mapping[str, object]) -> dict[str, list[tuple[str, list[str], bool]]]:
    """Only union branch discriminator enums are exact; ordinary enums stay forward compatible."""
    schemas = document.get("components", {}).get("schemas", {})
    result: dict[str, list[tuple[str, list[str], bool]]] = {}
    for root in INLINE_CONST_ROOT_SCHEMAS:
        schema = schemas.get(root)
        if not isinstance(schema, Mapping):
            continue
        discriminator = schema.get("discriminator")
        if not isinstance(discriminator, Mapping):
            continue
        field = discriminator.get("propertyName")
        branches = schema.get("oneOf")
        if not isinstance(field, str) or not isinstance(branches, list):
            continue
        for index, branch in enumerate(branches):
            if not isinstance(branch, Mapping):
                raise ValueError(f"Invalid discriminator branch {root} {index}")
            reference = branch.get("$ref")
            if isinstance(reference, str):
                name = reference.rsplit("/", 1)[-1]
                branch = schemas.get(name)
            else:
                name = root + "OneOf" + (str(index) if index else "")
            if not isinstance(branch, Mapping):
                raise ValueError(f"Missing discriminator branch {root} {index}")
            property_schema = branch.get("properties", {}).get(field)
            if not isinstance(property_schema, Mapping) or "enum" not in property_schema:
                continue
            allowed = property_schema["enum"]
            if not isinstance(allowed, list) or not allowed or not all(isinstance(value, str) for value in allowed):
                raise ValueError(f"Invalid discriminator enum {name}.{field}")
            if field not in branch.get("required", []):
                raise ValueError(f"Union discriminator must be required: {name}.{field}")
            values = [(field, allowed, True)]
            if name in result and result[name] != values:
                raise ValueError(f"Conflicting discriminator enum in shared model {name}")
            result[name] = values
    return result


def patch_model(
    path: pathlib.Path,
    constants: list[tuple[str, str, bool]],
    discriminator_values: list[tuple[str, list[str], bool]] | None = None,
) -> bool:
    source = path.read_text()
    if MARKER in source:
        return False
    if JSON_OBJECT_DECLARATION not in source:
        raise RuntimeError(f"Cannot find generated validator in {path}")

    checks = [MARKER]
    for property_name, expected, required in constants:
        property_literal = json.dumps(property_name)
        expected_literal = json.dumps(expected)
        error_prefix = json.dumps(
            f"Expected the field `{property_name}` to equal `{expected}` in the JSON string but got `"
        )
        if required:
            condition = [
                f"        if (jsonObj.get({property_literal}) == null\n",
                f"                || !jsonObj.get({property_literal}).isJsonPrimitive()\n",
                f"                || !jsonObj.get({property_literal}).getAsJsonPrimitive().isString()\n",
                f"                || !{expected_literal}.equals(jsonObj.get({property_literal}).getAsString())) {{\n",
            ]
        else:
            condition = [
                f"        if (jsonObj.get({property_literal}) != null\n",
                f"                && (!jsonObj.get({property_literal}).isJsonPrimitive()\n",
                f"                        || !jsonObj.get({property_literal}).getAsJsonPrimitive().isString()\n",
                f"                        || !{expected_literal}.equals(jsonObj.get({property_literal}).getAsString()))) {{\n",
            ]
        checks.extend(
            condition
            + [
                "            throw new IllegalArgumentException(\n",
                f"                    {error_prefix} + jsonObj.get({property_literal}) + \"`\");\n",
                "        }\n",
            ]
        )
    for field, allowed, required in discriminator_values or []:
        literal = json.dumps(field)
        alternatives = " && ".join(
            f"!{json.dumps(value)}.equals(jsonObj.get({literal}).getAsString())"
            for value in allowed
        )
        invalid = (
            f"(!jsonObj.get({literal}).isJsonPrimitive() || "
            f"!jsonObj.get({literal}).getAsJsonPrimitive().isString() || ({alternatives}))"
        )
        condition = (
            f"jsonObj.get({literal}) == null || {invalid}"
            if required
            else f"jsonObj.get({literal}) != null && {invalid}"
        )
        checks.extend([
            f"        if ({condition}) {{\n",
            f"            throw new IllegalArgumentException({json.dumps('Unknown union discriminator ' + field)});\n",
            "        }\n",
        ])
    source = source.replace(JSON_OBJECT_DECLARATION, JSON_OBJECT_DECLARATION + "".join(checks), 1)
    path.write_text(source)
    return True


def main() -> None:
    document = json.loads(SPEC_PATH.read_text())
    changed = 0
    discriminator_models = union_discriminator_values(document)
    for schema_name, schema in const_model_schemas(document).items():
        constants = string_constants(schema)
        path = MODEL_ROOT / f"{schema_name}.java"
        discriminators = discriminator_models.get(schema_name, [])
        if (constants or discriminators) and path.is_file() and patch_model(path, constants, discriminators):
            changed += 1
    print(f"Patched exact const validation in {changed} generated Java model files.")


if __name__ == "__main__":
    main()

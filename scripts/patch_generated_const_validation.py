#!/usr/bin/env python3
"""Restore strict OpenAPI ``const`` validation with forward-compatible enums.

``enumUnknownDefaultCase`` is useful for ordinary response enums, but the Java
generator also represents string ``const`` properties as enums. Its generated
validator accepts the synthetic unknown value for those properties, which can
make several discriminated-union branches match the same payload. Inject an
exact check into object-model validators while retaining unknown-enum handling
for fields that are not constants.
"""

from __future__ import annotations

import json
import pathlib
from collections.abc import Mapping


SPEC_PATH = pathlib.Path("spec/vertesia-openapi.json")
MODEL_ROOT = pathlib.Path("src/main/java/io/vertesia/model")
JSON_OBJECT_DECLARATION = "        JsonObject jsonObj = jsonElement.getAsJsonObject();\n"
MARKER = "        // Enforce OpenAPI const values independently of enum unknown-default handling.\n"


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


def patch_model(path: pathlib.Path, constants: list[tuple[str, str, bool]]) -> bool:
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
    source = source.replace(JSON_OBJECT_DECLARATION, JSON_OBJECT_DECLARATION + "".join(checks), 1)
    path.write_text(source)
    return True


def main() -> None:
    document = json.loads(SPEC_PATH.read_text())
    schemas = document.get("components", {}).get("schemas", {})
    changed = 0
    for schema_name, schema in schemas.items():
        if not isinstance(schema_name, str) or not isinstance(schema, Mapping):
            continue
        constants = string_constants(schema)
        path = MODEL_ROOT / f"{schema_name}.java"
        if constants and path.is_file() and patch_model(path, constants):
            changed += 1
    print(f"Patched exact const validation in {changed} generated Java model files.")


if __name__ == "__main__":
    main()

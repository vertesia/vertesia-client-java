#!/usr/bin/env python3
"""Restore strict OpenAPI ``const`` validation with forward-compatible enums.

``enumUnknownDefaultCase`` is useful for ordinary response enums, but the Java
generator also represents scalar ``const`` properties as enums. Its generated
validator accepts the synthetic unknown value for those properties, which can
make several discriminated-union branches match the same payload. Inject an
exact check into object-model validators and parent-union discriminator enums,
while retaining unknown-enum handling for ordinary enum fields.
"""

from __future__ import annotations

import json
import math
import pathlib
import re
from collections.abc import Mapping

from patch_generated_json_elements import canonical_model_schemas


SPEC_PATH = pathlib.Path("spec/vertesia-openapi.json")
MODEL_ROOT = pathlib.Path("src/main/java/io/vertesia/model")
JSON_OBJECT_DECLARATION = "        JsonObject jsonObj = jsonElement.getAsJsonObject();\n"
MARKER = "        // Enforce OpenAPI const values independently of enum unknown-default handling.\n"


def scalar_constants(schema: Mapping[str, object]) -> list[tuple[str, str | int | float | bool, bool]]:
    properties = schema.get("properties")
    if not isinstance(properties, Mapping):
        return []
    required_value = schema.get("required")
    required = set(required_value) if isinstance(required_value, list) else set()
    constants: list[tuple[str, str | int | float | bool, bool]] = []
    for property_name, value in properties.items():
        if isinstance(property_name, str) and isinstance(value, Mapping):
            constant = value.get("const")
            if isinstance(constant, (str, bool, int)) or (isinstance(constant, float) and math.isfinite(constant)):
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
    model_schemas.update(canonical_model_schemas(document))
    return model_schemas


def union_discriminator_values(document: Mapping[str, object]) -> dict[str, list[tuple[str, list[str], bool]]]:
    """Only union branch discriminator enums are exact; ordinary enums stay forward compatible."""
    schemas = document.get("components", {}).get("schemas", {})
    result: dict[str, list[tuple[str, list[str], bool]]] = {}
    models = canonical_model_schemas(document)
    for root, schema in models.items():
        if not isinstance(schema, Mapping):
            continue
        discriminator = schema.get("discriminator")
        if not isinstance(discriminator, Mapping):
            continue
        field = discriminator.get("propertyName")
        keyword = "oneOf" if "oneOf" in schema else "anyOf"
        branches = schema.get(keyword)
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
                name = root + ("OneOf" if keyword == "oneOf" else "AnyOf") + (str(index) if index else "")
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
    constants: list[tuple[str, str | int | float | bool, bool]],
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
        if isinstance(expected, str):
            primitive_kind = "isString()"
            mismatch = f"!{expected_literal}.equals(jsonObj.get({property_literal}).getAsString())"
        elif isinstance(expected, bool):
            primitive_kind = "isBoolean()"
            mismatch = f"jsonObj.get({property_literal}).getAsBoolean() != {expected_literal}"
        else:
            primitive_kind = "isNumber()"
            mismatch = (f"new java.math.BigDecimal({json.dumps(str(expected))})"
                        f".compareTo(jsonObj.get({property_literal}).getAsBigDecimal()) != 0")
        error_prefix = json.dumps(
            f"Expected the field `{property_name}` to equal `{expected}` in the JSON string but got `"
        )
        if required:
            condition = [
                f"        if (jsonObj.get({property_literal}) == null\n",
                f"                || !jsonObj.get({property_literal}).isJsonPrimitive()\n",
                f"                || !jsonObj.get({property_literal}).getAsJsonPrimitive().{primitive_kind}\n",
                f"                || {mismatch}) {{\n",
            ]
        else:
            condition = [
                f"        if (jsonObj.get({property_literal}) != null\n",
                f"                && (!jsonObj.get({property_literal}).isJsonPrimitive()\n",
                f"                        || !jsonObj.get({property_literal}).getAsJsonPrimitive().{primitive_kind}\n",
                f"                        || {mismatch})) {{\n",
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


def patch_union_selectors(path: pathlib.Path, document: Mapping[str, object]) -> int:
    """Bind actual discriminator literals to schema-derived generated branch classes."""
    if not path.is_file():
        return 0
    source = path.read_text()
    schemas = document.get("components", {}).get("schemas", {})
    changed = 0
    for model_name, schema in canonical_model_schemas(document).items():
        discriminator = schema.get("discriminator")
        branches = schema.get("oneOf", schema.get("anyOf"))
        if not isinstance(discriminator, Mapping) or not isinstance(branches, list):
            continue
        field = discriminator.get("propertyName")
        if not isinstance(field, str):
            raise ValueError(f"Missing discriminator field for {model_name}")
        mappings: dict[str, str] = {}
        for index, branch in enumerate(branches):
            if not isinstance(branch, Mapping):
                raise ValueError(f"Invalid discriminator branch {model_name}")
            reference = branch.get("$ref")
            if isinstance(reference, str):
                name = reference.rsplit("/", 1)[-1]
                branch = schemas.get(name)
            else:
                suffix = "OneOf" if "oneOf" in schema else "AnyOf"
                name = model_name + suffix + (str(index) if index else "")
            if not isinstance(branch, Mapping):
                raise ValueError(f"Missing discriminator branch {name}")
            literal_schema = branch.get("properties", {}).get(field)
            if not isinstance(literal_schema, Mapping):
                # Existing discriminator mappings can target a nested union (AgentTurn).
                continue
            values = [literal_schema["const"]] if "const" in literal_schema else literal_schema.get("enum", [])
            if field not in branch.get("required", []) or not values or any(not isinstance(v, str) for v in values):
                raise ValueError(f"Invalid required string discriminator in {name}")
            for value in values:
                if value in mappings and mappings[value] != name:
                    raise ValueError(f"Ambiguous discriminator {model_name}.{value}")
                mappings[value] = name
        prefix = f".registerTypeSelector(io.vertesia.model.{model_name}.class,"
        start = source.find(prefix)
        if start < 0:
            continue
        end = source.find("return getClassByDiscriminator", start)
        if end < 0:
            raise ValueError(f"Missing generated selector body for {model_name}")
        body = source[start:end]
        additions = []
        for value, name in mappings.items():
            line = f"classByDiscriminatorValue.put({json.dumps(value)}, io.vertesia.model.{name}.class);"
            if line not in body:
                additions.append("                        " + line + "\n")
        if additions:
            source = source[:end] + "".join(additions) + source[end:]
            changed += 1
    if changed:
        path.write_text(source)
    return changed


def patch_canonical_shape(path: pathlib.Path, schema: Mapping[str, object], schemas: Mapping[str, object]) -> bool:
    """Enforce closed-object and primitive-reference constraints omitted by the generator."""
    marker = "        // Enforce canonical object closure and referenced string patterns.\n"
    source = path.read_text()
    properties = schema.get("properties")
    if not isinstance(properties, Mapping) or marker in source:
        return False
    checks = []
    if schema.get("additionalProperties") is False:
        allowed = ", ".join(json.dumps(key) for key in properties)
        checks.extend([
            "        for (String canonicalKey : jsonObj.keySet()) {\n",
            f"            if (!java.util.Arrays.asList({allowed}).contains(canonicalKey)) {{\n",
            '                throw new IllegalArgumentException("Unknown canonical field: " + canonicalKey);\n',
            "            }\n        }\n",
        ])
    for field, value in properties.items():
        if not isinstance(value, Mapping):
            continue
        reference = value.get("$ref")
        if isinstance(reference, str):
            value = schemas.get(reference.rsplit("/", 1)[-1], {})
        field_type = value.get("type")
        string_type = field_type == "string" or (
            isinstance(field_type, list)
            and "string" in field_type
            and set(field_type).issubset({"string", "null"})
        )
        if string_type:
            literal = json.dumps(field)
            checks.extend([
                f"        if (jsonObj.get({literal}) != null && !jsonObj.get({literal}).isJsonNull()\n",
                f"                && (!jsonObj.get({literal}).isJsonPrimitive()\n",
                f"                    || !jsonObj.get({literal}).getAsJsonPrimitive().isString())) {{\n",
                f"            throw new IllegalArgumentException({json.dumps('Invalid canonical string type: ' + field)});\n",
                "        }\n",
            ])
        if string_type:
            for keyword, comparison in (("minLength", "<"), ("maxLength", ">")):
                bound = value.get(keyword)
                if bound is None:
                    continue
                if isinstance(bound, bool) or not isinstance(bound, int) or bound < 0:
                    raise ValueError(f"Invalid {keyword} for {path.name}.{field}")
                checks.extend([
                    f"        if (jsonObj.get({literal}) != null && !jsonObj.get({literal}).isJsonNull()) {{\n",
                    f"            String canonicalString = jsonObj.get({literal}).getAsString();\n",
                    f"            if (canonicalString.codePointCount(0, canonicalString.length()) {comparison} {bound}) {{\n",
                    f"                throw new IllegalArgumentException({json.dumps('Invalid canonical string length: ' + field)});\n",
                    "            }\n        }\n",
                ])
        numeric_type = field_type if field_type in ("integer", "number") else None
        if isinstance(field_type, list) and "null" in field_type:
            nonnull_types = set(field_type) - {"null"}
            if nonnull_types in ({"integer"}, {"number"}):
                numeric_type = next(iter(nonnull_types))
        if numeric_type is not None:
            literal = json.dumps(field)
            checks.extend([
                f"        if (jsonObj.get({literal}) != null && !jsonObj.get({literal}).isJsonNull()) {{\n",
                f"            if (!jsonObj.get({literal}).isJsonPrimitive()\n",
                f"                    || !jsonObj.get({literal}).getAsJsonPrimitive().isNumber()) {{\n",
                f"                throw new IllegalArgumentException({json.dumps('Invalid canonical numeric type: ' + field)});\n",
                "            }\n",
                f"            java.math.BigDecimal canonicalNumber = jsonObj.get({literal}).getAsBigDecimal();\n",
            ])
            if numeric_type == "integer":
                checks.extend([
                    "            if (canonicalNumber.stripTrailingZeros().scale() > 0) {\n",
                    f"                throw new IllegalArgumentException({json.dumps('Invalid canonical integer: ' + field)});\n",
                    "            }\n",
                ])
            for keyword, comparison in (("minimum", "<"), ("maximum", ">")):
                bound = value.get(keyword)
                if bound is None:
                    continue
                if isinstance(bound, bool) or not isinstance(bound, (int, float)) or not math.isfinite(bound):
                    raise ValueError(f"Invalid {keyword} for {path.name}.{field}")
                checks.extend([
                    f"            if (canonicalNumber.compareTo(new java.math.BigDecimal({json.dumps(str(bound))})) {comparison} 0) {{\n",
                    f"                throw new IllegalArgumentException({json.dumps('Invalid canonical numeric bound: ' + field)});\n",
                    "            }\n",
                ])
            checks.append("        }\n")
        pattern = value.get("pattern")
        if value.get("type") == "string" and isinstance(pattern, str):
            literal = json.dumps(field)
            checks.extend([
                f"        if (jsonObj.get({literal}) != null && !jsonObj.get({literal}).isJsonNull()\n",
                f"                && (!jsonObj.get({literal}).isJsonPrimitive()\n",
                f"                    || !jsonObj.get({literal}).getAsJsonPrimitive().isString()\n",
                f"                    || !java.util.regex.Pattern.compile({json.dumps(pattern)}).matcher(jsonObj.get({literal}).getAsString()).find())) {{\n",
                f"            throw new IllegalArgumentException({json.dumps('Invalid canonical string pattern: ' + field)});\n",
                "        }\n",
            ])
    if not checks:
        return False
    if JSON_OBJECT_DECLARATION not in source:
        raise ValueError(f"Missing object validator in {path}")
    path.write_text(source.replace(JSON_OBJECT_DECLARATION, JSON_OBJECT_DECLARATION + marker + "".join(checks), 1))
    return True


def patch_nested_union_adapters(path: pathlib.Path, schema: Mapping[str, object], schemas: Mapping[str, object]) -> bool:
    """Use the referenced union's own adapter rather than Gson's reflective/leaf delegate.

    Delegate lookup is ordered relative to the parent's registered factory. It can
    skip a later child's union factory, losing its wrapper during both read/write.
    The exact child factory handles discriminated and non-discriminated unions.
    """
    source = path.read_text()
    original = source
    for branch in schema.get("oneOf", schema.get("anyOf", [])):
        reference = branch.get("$ref") if isinstance(branch, Mapping) else None
        if not isinstance(reference, str):
            continue
        name = reference.rsplit("/", 1)[-1]
        child = schemas.get(name, {})
        if not isinstance(child.get("oneOf", child.get("anyOf")), list):
            continue
        old = f"gson.getDelegateAdapter(this, TypeToken.get({name}.class))"
        new = f"new {name}.CustomTypeAdapterFactory().create(gson, TypeToken.get({name}.class))"
        source = source.replace(old, new)
    if source != original:
        path.write_text(source)
        return True
    return False


def main() -> None:
    document = json.loads(SPEC_PATH.read_text())
    schemas = document["components"]["schemas"]
    for name, schema in canonical_model_schemas(document).items():
        path = MODEL_ROOT / f"{name}.java"
        if path.is_file():
            patch_canonical_shape(path, schema, schemas)
            patch_nested_union_adapters(path, schema, schemas)
    selectors = patch_union_selectors(MODEL_ROOT.parent / "JSON.java", document)
    changed = 0
    discriminator_models = union_discriminator_values(document)
    for schema_name, schema in const_model_schemas(document).items():
        constants = scalar_constants(schema)
        path = MODEL_ROOT / f"{schema_name}.java"
        discriminators = discriminator_models.get(schema_name, [])
        if (constants or discriminators) and path.is_file() and patch_model(path, constants, discriminators):
            changed += 1
    print(f"Patched exact const validation in {changed} generated Java model files and {selectors} selectors.")


if __name__ == "__main__":
    main()

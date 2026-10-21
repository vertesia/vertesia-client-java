#!/usr/bin/env python3
"""Patch generated Java model validation and schema serialization.

OpenAPI Generator's Gson Java client validates response payloads before
deserialization. Some generated validators reject any future response field,
which is too strict for a public client talking to a newer Vertesia server.

This patch keeps required-field and type validation, but removes the generated
block that throws only because a JSON object contains fields unknown to this
client version. Literal union tags stay strict, and JSON Schema wire properties
remain separate from the generated catch-all map.
"""

from __future__ import annotations

import json
import pathlib
import re


ROOT = pathlib.Path("src/main/java/io/vertesia/model")

UNKNOWN_FIELD_BLOCK = re.compile(
    r'\n\s*Set<Map\.Entry<String,\s*JsonElement>>\s+entries\s*=\s*jsonElement\.getAsJsonObject\(\)\.entrySet\(\);'
    r'\n\s*// check to see if the JSON string contains additional fields'
    r'\n\s*for \(Map\.Entry<String,\s*JsonElement>\s+entry\s*:\s*entries\) \{'
    r'\n\s*if \(![A-Za-z0-9_]+\.openapiFields\.contains\(entry\.getKey\(\)\)\) \{'
    r'\n\s*throw new IllegalArgumentException\(String\.format\(java\.util\.Locale\.ROOT, "The field `%s` in the JSON string is not defined in the `[A-Za-z0-9_]+` properties\. JSON: %s", entry\.getKey\(\), jsonElement\.toString\(\)\)\);'
    r'\n\s*\}'
    r'\n\s*\}'
    r'\n',
    re.MULTILINE,
)


def patch_schema_constants(text: str, schema: dict) -> str:
    """Validate literal tags strictly while other enums remain forward compatible."""
    for field, definition in schema.get("properties", {}).items():
        if "const" not in definition or not isinstance(definition["const"], str):
            continue
        # Generated inline enums use the property name in their Java class name.
        enum_name = "".join(part[:1].upper() + part[1:] for part in field.split("_")) + "Enum"
        enum_start = text.find("public enum " + enum_name + " {")
        if enum_start == -1:
            continue
        start = text.find("public static void validateJsonElement", enum_start)
        if start == -1:
            continue
        # The script runs on raw generator output (before Spotless) and on formatted sources, so
        # find the method's closing brace from the indentation of its own declaration line.
        indent = text[text.rfind("\n", 0, start) + 1 : start]
        end = text.find("\n" + indent + "}", start)
        if indent.strip() or end == -1:
            continue
        value = json.dumps(definition["const"])
        message = json.dumps(f"Expected {field} to be {definition['const']}")
        method = (
            "public static void validateJsonElement(JsonElement jsonElement) throws IOException {\n"
            f"{indent}    if (!{value}.equals(jsonElement.getAsString())) {{\n"
            f"{indent}        throw new IOException({message});\n"
            f"{indent}    }}"
        )
        text = text[:start] + method + text[end:]
    return text

def patch_schema_property_collision(text: str) -> str:
    # JSONSchema has a real wire property named additionalProperties as well as the generator's
    # catch-all map. Gson must only reflect the wire property; the custom adapter handles the map.
    if not re.search(r'SERIALIZED_NAME_\w+\s*=\s*"additionalProperties"', text):
        return text
    text = re.sub(
        r"private (?:transient )?Map<String, Object> additionalProperties;",
        "private transient Map<String, Object> additionalProperties;",
        text,
    )
    return re.sub(r'(?m)^ *obj\.remove\("additionalProperties"\);\n', "", text)


def union_literal_schemas(schemas: dict) -> dict:
    """Find required, distinct literal tags that discriminate object union branches."""
    tagged = {}
    for union in schemas.values():
        variants = union.get("oneOf", union.get("anyOf", []))
        names = [item["$ref"].rsplit("/", 1)[-1] for item in variants if "$ref" in item]
        if len(names) < 2 or len(names) != len(variants):
            continue
        branches = [schemas.get(name, {}) for name in names]
        shared = set.intersection(*(set(branch.get("required", [])) for branch in branches))
        for field in shared:
            definitions = [branch.get("properties", {}).get(field, {}) for branch in branches]
            values = [definition.get("const") for definition in definitions]
            if not all(isinstance(value, str) for value in values) or len(set(values)) != len(values):
                continue
            for name, definition in zip(names, definitions):
                tagged.setdefault(name, {"properties": {}})["properties"][field] = definition
    return tagged

def main() -> None:
    schemas = json.loads(pathlib.Path("spec/vertesia-openapi.json").read_text())["components"]["schemas"]
    schemas = union_literal_schemas(schemas)
    changed = 0
    for path in ROOT.glob("*.java"):
        text = path.read_text()
        patched = UNKNOWN_FIELD_BLOCK.sub("\n", text)
        patched = patch_schema_constants(patched, schemas.get(path.stem, {}))
        patched = patch_schema_property_collision(patched)
        if patched != text:
            path.write_text(patched)
            changed += 1

    print(f"Patched compatibility validation in {changed} generated Java model files.")


if __name__ == "__main__":
    main()

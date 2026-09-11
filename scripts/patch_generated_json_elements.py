#!/usr/bin/env python3
"""Preserve canonical arbitrary JSON values in generated Gson models.

OpenAPI Generator maps the canonical JSON value and boolean-or-object JSON Schema
components to Gson ``JsonElement`` through ``openapi-generator-config.yaml``.
Gson's reflective model adapter otherwise selects the runtime ``JsonObject``
adapter and omits nested explicit null members. Generated union adapters then
write intermediate JSON trees through the same null-dropping adapter. The two
patches keep declared JSON-tree semantics through both layers while leaving
absent optional POJO fields omitted.
"""

from __future__ import annotations

import json
import pathlib
import re
from collections.abc import Mapping


PACKAGE_ROOT = pathlib.Path("src/main/java/io/vertesia")
MODEL_ROOT = PACKAGE_ROOT / "model"
SPEC_PATH = pathlib.Path("spec/vertesia-openapi.json")
CANONICAL_ROOT_SCHEMA = "RunConversationResponse"
JSON_ELEMENT_FIELD = re.compile(
    r"^(?P<indent>\s*)private JsonElement (?P<name>[A-Za-z_$][\w$]*)(?P<suffix>[^;]*);\s*$"
)
JSON_ELEMENT_MAP_FIELD = re.compile(
    r"^(?P<indent>\s*)private Map<String, JsonElement> (?P<name>[A-Za-z_$][\w$]*)(?P<suffix>[^;]*);\s*$"
)
INVALID_JSON_ELEMENT_CONTAINER_DEFAULT = re.compile(
    r"\s*=\s*new\s+(?:ArrayList|HashMap|HashSet)(?:<[^;\n]*>)?\(\)\s*$"
)
ADAPTER_ANNOTATION = (
    "@com.google.gson.annotations.JsonAdapter("
    "value = io.vertesia.gson.NullPreservingJsonElementTypeAdapter.class, nullSafe = false)"
)
ADAPTER_CLASS = "io.vertesia.gson.NullPreservingJsonElementTypeAdapter.class"
MAP_ADAPTER_ANNOTATION = (
    "@com.google.gson.annotations.JsonAdapter("
    "value = io.vertesia.gson.NullPreservingJsonElementMapTypeAdapter.class, nullSafe = false)"
)
MAP_ADAPTER_CLASS = "io.vertesia.gson.NullPreservingJsonElementMapTypeAdapter.class"
SIMPLE_OBJECT_TREE_WRITER = re.compile(
    r"JsonObject\s+obj\s*=\s*thisAdapter\.toJsonTree\(value\)\.getAsJsonObject\(\);\s*"
    r"elementAdapter\.write\(out,\s*obj\);"
)
UNION_TREE_WRITER = re.compile(
    r"JsonElement\s+element\s*=\s*(?P<adapter>[A-Za-z_$][\w$]*)\.toJsonTree\(\s*"
    r"(?P<argument>\([A-Za-z_$][\w.$]*\)\s*value\.getActualInstance\(\))\s*\);\s*"
    r"elementAdapter\.write\(out,\s*element\);"
)
NULLABLE_CONTAINER_DEFAULT = re.compile(
    r"(@jakarta\.annotation\.Nullable\s+private\s+[^;=\n]+)\s*=\s*"
    r"new\s+(?:ArrayList|HashMap|HashSet)(?:<[^;\n]*>)?\(\);"
)


def has_recent_adapter(lines: list[str], adapter_class: str) -> bool:
    return adapter_class in "".join(lines[-5:])


def patch_model(path: pathlib.Path) -> int:
    lines = path.read_text().splitlines(keepends=True)
    patched: list[str] = []
    annotated = 0
    modified = False
    for line in lines:
        original = line
        match = JSON_ELEMENT_FIELD.match(line)
        if match is not None:
            annotation = f"{match.group('indent')}{ADAPTER_ANNOTATION}"
            if not has_recent_adapter(patched, ADAPTER_CLASS):
                patched.append(f"{annotation}\n")
                annotated += 1
                modified = True
            suffix = INVALID_JSON_ELEMENT_CONTAINER_DEFAULT.sub("", match.group("suffix"))
            line = f"{match.group('indent')}private JsonElement {match.group('name')}{suffix};\n"
        map_match = JSON_ELEMENT_MAP_FIELD.match(line)
        if map_match is not None:
            if not has_recent_adapter(patched, MAP_ADAPTER_CLASS):
                patched.append(f"{map_match.group('indent')}{MAP_ADAPTER_ANNOTATION}\n")
                annotated += 1
                modified = True
            suffix = INVALID_JSON_ELEMENT_CONTAINER_DEFAULT.sub("", map_match.group("suffix"))
            line = (
                f"{map_match.group('indent')}private Map<String, JsonElement> "
                f"{map_match.group('name')}{suffix};\n"
            )
        patched.append(line)
        modified = modified or line != original
    if modified:
        path.write_text("".join(patched))
    return annotated


def referenced_schemas(document: Mapping[str, object], root: str) -> set[str]:
    schemas = document.get("components", {}).get("schemas", {})
    if not isinstance(schemas, Mapping):
        return set()
    pending = [root]
    found: set[str] = set()
    while pending:
        name = pending.pop()
        if name in found:
            continue
        found.add(name)
        schema = schemas.get(name)
        if schema is None:
            continue
        stack = [schema]
        while stack:
            value = stack.pop()
            if isinstance(value, Mapping):
                reference = value.get("$ref")
                if isinstance(reference, str) and reference.startswith("#/components/schemas/"):
                    pending.append(reference.rsplit("/", 1)[-1])
                stack.extend(value.values())
            elif isinstance(value, list):
                stack.extend(value)
    return found


def patch_streaming_writers(path: pathlib.Path) -> tuple[int, int]:
    source = path.read_text()
    patched, simple_writers = SIMPLE_OBJECT_TREE_WRITER.subn("thisAdapter.write(out, value);", source)

    def stream_union(match: re.Match[str]) -> str:
        return f"{match.group('adapter')}.write(out, {match.group('argument')});"

    patched, union_writers = UNION_TREE_WRITER.subn(stream_union, patched)
    if simple_writers or union_writers:
        path.write_text(patched)
    return simple_writers, union_writers


def patch_nullable_container_defaults(path: pathlib.Path) -> int:
    source = path.read_text()
    patched, changed = NULLABLE_CONTAINER_DEFAULT.subn(
        lambda match: f"{match.group(1).rstrip()};", source
    )
    if changed:
        path.write_text(patched)
    return changed


def main() -> None:
    paths = list(MODEL_ROOT.glob("*.java"))
    changed_fields = sum(patch_model(path) for path in paths)
    document = json.loads(SPEC_PATH.read_text())
    canonical_schemas = referenced_schemas(document, CANONICAL_ROOT_SCHEMA)
    simple_writers = 0
    union_writers = 0
    nullable_containers = 0
    for schema_name in canonical_schemas:
        path = MODEL_ROOT / f"{schema_name}.java"
        if path.is_file():
            nullable_containers += patch_nullable_container_defaults(path)
            simple, union = patch_streaming_writers(path)
            simple_writers += simple
            union_writers += union
    print(
        "Configured null-preserving adapters on "
        f"{changed_fields} generated JsonElement fields/maps; cleared {nullable_containers} optional container defaults "
        f"and streamed {simple_writers} object and {union_writers} union writers in the canonical response closure."
    )


if __name__ == "__main__":
    main()

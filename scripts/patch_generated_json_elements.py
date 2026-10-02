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
CANONICAL_ROOT_SCHEMAS = (
    "ConversationDocument",
    "ConversationChange",
    "ConversationContextChangeRequest",
    "ConversationContextChangeProposal",
    "RunConversationResponse",
    "ExperimentalCanonicalUserMessagePayload",
    "ExperimentalCanonicalToolResultsPayload",
    "ExperimentalPublishAgentAssetPayload",
    "ExperimentalAgentAssetPublication",
    "ExperimentalExtractAgentAssetPayload",
    "ExperimentalAgentAssetExtraction",
    "ExperimentalCanonicalInteractionExecutionRequest",
    "ExperimentalCanonicalNamedInteractionExecutionRequest",
    "AppendRunConversationProgramTurnPayload",
    "ImportAgentRunConversationArchivePayload",
    "ImportAgentRunConversationArchiveResponse",
)
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
MAPPED_OBJECT_SCHEMAS = {
    "ConversationJsonObject": False,
    "ExperimentalCanonicalInteractionResultSchemaInput": True,
}
INVALID_JSON_ELEMENT_VALIDATION = re.compile(
    r'^\s*JsonElement\.validateJsonElement\(jsonObj\.get\("[^"]+"\)\);\s*$', re.MULTILINE
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


def generated_inline_models(document: Mapping[str, object], root: str) -> dict[str, Mapping[str, object]]:
    """Return the Java model names OpenAPI Generator derives for inline object/union schemas."""

    schemas = document.get("components", {}).get("schemas", {})
    if not isinstance(schemas, Mapping):
        return {}
    root_schema = schemas.get(root)
    if not isinstance(root_schema, Mapping):
        return {}

    found: dict[str, Mapping[str, object]] = {}

    def property_suffix(wire_name: str) -> str:
        parts = re.split(r"[^A-Za-z0-9]+", wire_name)
        suffix = "".join(part[:1].upper() + part[1:] for part in parts if part)
        if not suffix:
            raise ValueError(f"Cannot derive generated Java property model name from {wire_name!r}")
        return suffix

    def visit(model_name: str, schema: Mapping[str, object]) -> None:
        prior = found.get(model_name)
        if prior is not None:
            if prior != schema:
                raise ValueError(f"Conflicting generated inline schema name {model_name}")
            return
        found[model_name] = schema

        for keyword, generated_suffix in (("oneOf", "OneOf"), ("anyOf", "AnyOf")):
            branches = schema.get(keyword)
            if not isinstance(branches, list):
                continue
            for index, branch in enumerate(branches):
                if not isinstance(branch, Mapping):
                    raise ValueError(f"{model_name} {keyword} branch {index} is not an object")
                reference = branch.get("$ref")
                if isinstance(reference, str) and reference.startswith("#/components/schemas/"):
                    referenced_name = reference.rsplit("/", 1)[-1]
                    referenced_schema = schemas.get(referenced_name)
                    if isinstance(referenced_schema, Mapping):
                        visit(referenced_name, referenced_schema)
                else:
                    suffix = str(index) if index else ""
                    visit(f"{model_name}{generated_suffix}{suffix}", branch)

        additional = schema.get("additionalProperties")
        if isinstance(additional, Mapping):
            visit_property(f"{model_name}Value", additional)

        properties = schema.get("properties")
        if isinstance(properties, Mapping):
            for wire_name, property_schema in properties.items():
                if not isinstance(wire_name, str) or not isinstance(property_schema, Mapping):
                    continue
                visit_property(f"{model_name}{property_suffix(wire_name)}", property_schema)

    def visit_property(model_name: str, schema: Mapping[str, object]) -> None:
        reference = schema.get("$ref")
        if isinstance(reference, str) and reference.startswith("#/components/schemas/"):
            referenced_name = reference.rsplit("/", 1)[-1]
            referenced_schema = schemas.get(referenced_name)
            if isinstance(referenced_schema, Mapping):
                visit(referenced_name, referenced_schema)
        elif isinstance(schema.get("additionalProperties"), Mapping):
            visit_property(f"{model_name}Value", schema["additionalProperties"])
        elif schema.get("type") == "array":
            items = schema.get("items")
            if isinstance(items, Mapping):
                visit_property(f"{model_name}Inner", items)
        elif (isinstance(schema.get("properties"), Mapping)
              or isinstance(schema.get("oneOf"), list) or isinstance(schema.get("anyOf"), list)):
            visit(model_name, schema)

    visit(root, root_schema)
    return found


def canonical_model_schemas(document: Mapping[str, object]) -> dict[str, Mapping[str, object]]:
    """Named and inline generated models in the explicit canonical reference closure."""
    schemas = document.get("components", {}).get("schemas", {})
    if not isinstance(schemas, Mapping):
        return {}
    roots = set(CANONICAL_ROOT_SCHEMAS)
    result: dict[str, Mapping[str, object]] = {}
    for root in sorted(roots):
        for name, schema in generated_inline_models(document, root).items():
            if name in result and result[name] != schema:
                raise ValueError(f"Conflicting canonical generated model {name}")
            result[name] = schema
    return result


def required_nullable_fields(schema: Mapping[str, object]) -> set[str]:
    properties = schema.get("properties", {})
    required = schema.get("required", [])
    if not isinstance(properties, Mapping) or not isinstance(required, list):
        return set()
    fields: set[str] = set()
    for name in required:
        value = properties.get(name)
        if not isinstance(name, str) or not isinstance(value, Mapping):
            continue
        types = value.get("type")
        alternatives = value.get("anyOf", value.get("oneOf", []))
        if (
            value.get("nullable") is True
            or (isinstance(types, list) and "null" in types)
            or (
                isinstance(alternatives, list)
                and any(
                    isinstance(branch, Mapping) and branch.get("type") == "null"
                    for branch in alternatives
                )
            )
        ):
            fields.add(name)
    return fields


def schema_accepts_null(
    schema: object,
    components: Mapping[str, object],
    visiting: frozenset[str] = frozenset(),
) -> bool:
    """Evaluate the literal JSON null against schema constraints; unresolved cycles stay permissive."""
    if isinstance(schema, bool):
        return schema
    if not isinstance(schema, Mapping):
        raise ValueError("Nullability requires a schema object or boolean")
    if schema.get("nullable") is True:
        return True
    types = schema.get("type")
    if isinstance(types, str) and types != "null":
        return False
    if isinstance(types, list) and "null" not in types:
        return False
    if "const" in schema and schema["const"] is not None:
        return False
    values = schema.get("enum")
    if isinstance(values, list) and None not in values:
        return False
    reference = schema.get("$ref")
    if isinstance(reference, str) and reference.startswith("#/components/schemas/"):
        name = reference.rsplit("/", 1)[-1]
        if name not in components:
            raise ValueError(f"Missing nullable schema reference {reference}")
        if name not in visiting and not schema_accepts_null(components[name], components, visiting | {name}):
            return False
    for keyword in ("allOf", "anyOf", "oneOf"):
        branches = schema.get(keyword)
        if not isinstance(branches, list):
            continue
        accepted = [schema_accepts_null(branch, components, visiting) for branch in branches]
        if keyword == "allOf" and not all(accepted):
            return False
        if keyword == "anyOf" and not any(accepted):
            return False
        if keyword == "oneOf" and sum(accepted) != 1:
            return False
    return True


def optional_nonnullable_fields(schema: Mapping[str, object], document: Mapping[str, object]) -> set[str]:
    properties = schema.get("properties", {})
    required = schema.get("required", [])
    components = document.get("components", {}).get("schemas", {})
    if not isinstance(properties, Mapping) or not isinstance(required, list) or not isinstance(components, Mapping):
        return set()
    return {
        name for name, value in properties.items()
        if isinstance(name, str) and name not in required and not schema_accepts_null(value, components)
    }


def patch_optional_nonnullable_fields(path: pathlib.Path, fields: set[str]) -> int:
    """Reject present null without changing optional absence or nullable decoding."""
    if not fields:
        return 0
    source = path.read_text()
    start = source.find("public static void validateJsonElement(")
    if start < 0:
        raise ValueError(f"Missing canonical static validator in {path}")
    adapter = re.search(r"\n\s*public static class CustomTypeAdapterFactory", source[start:])
    if adapter is None:
        raise ValueError(f"Missing canonical adapter boundary in {path}")
    end = start + adapter.start()
    validator = source[start:end]
    anchor = "JsonObject jsonObj = jsonElement.getAsJsonObject();"
    if validator.count(anchor) != 1:
        raise ValueError(f"Expected one canonical optional-null anchor in {path}")
    marker = "// Reject explicit null on schema-optional nonnullable canonical fields."
    guards = []
    for field in sorted(fields):
        literal = json.dumps(field)
        message = json.dumps(f"Field `{field}` must be omitted or non-null")
        guards.append(
            f"\n      if (jsonObj.has({literal}) && jsonObj.get({literal}).isJsonNull()) {{\n"
            f"        throw new IllegalArgumentException({message});\n      }}"
        )
    if marker in validator:
        normalized = re.sub(r"\s+", "", validator)
        if any(re.sub(r"\s+", "", guard) not in normalized for guard in guards):
            raise ValueError(f"Conflicting canonical optional-null guards in {path}")
        return 0
    injected = "\n      " + marker + "".join(guards)
    path.write_text(source[:start] + validator.replace(anchor, anchor + injected) + source[end:])
    return len(fields)


def patch_required_nullable_fields(path: pathlib.Path, fields: set[str]) -> int:
    """Preserve explicit null only for schema-required nullable properties."""
    if not fields:
        return 0
    source = path.read_text()
    constants = dict(
        re.findall(r'public static final String (SERIALIZED_NAME_\w+)\s*=\s*"([^"]+)";', source)
    )
    annotation = (
        "@com.google.gson.annotations.JsonAdapter("
        "value = io.vertesia.gson.RequiredNullableTypeAdapterFactory.class, nullSafe = false)"
    )
    lines: list[str] = []
    current_field = None
    found: set[str] = set()
    changed = 0
    for line in source.splitlines(keepends=True):
        serialized = re.search(r'@SerializedName\((SERIALIZED_NAME_\w+)\)', line)
        if serialized:
            current_field = constants.get(serialized.group(1))
        if re.match(r"\s*private\s+", line):
            if current_field in fields:
                found.add(current_field)
                if "RequiredNullableTypeAdapterFactory.class" not in "".join(lines[-5:]):
                    indent = re.match(r"\s*", line).group(0)
                    lines.append(f"{indent}{annotation}\n")
                    changed += 1
            current_field = None
        lines.append(line)
    if found != fields:
        raise ValueError(f"Required nullable generated fields missing in {path}: {fields - found}")
    if changed:
        path.write_text("".join(lines))
    return changed


def patch_streaming_writers(path: pathlib.Path) -> tuple[int, int]:
    source = path.read_text()
    patched, simple_writers = SIMPLE_OBJECT_TREE_WRITER.subn("thisAdapter.write(out, value);", source)

    def stream_union(match: re.Match[str]) -> str:
        return f"{match.group('adapter')}.write(out, {match.group('argument')});"

    patched, union_writers = UNION_TREE_WRITER.subn(stream_union, patched)
    # Generated additional-properties writers must flatten their map through a tree. Build that
    # tree with optional Java nulls omitted, then preserve the explicit JSON nulls retained by the
    # field adapters when writing it. TypeAdapter.toJsonTree uses an unconfigured tree writer and
    # the final default elementAdapter write otherwise drops nested required JSON null values.
    tree_source = "JsonObject obj = thisAdapter.toJsonTree(value).getAsJsonObject();"
    if tree_source in patched and 'obj.remove("additionalProperties")' in patched:
        if patched.count(tree_source) != 1 or patched.count("elementAdapter.write(out, obj);") != 1:
            raise ValueError(f"additional-properties generated writer changed in {path}")
        patched = patched.replace(tree_source, """com.google.gson.internal.bind.JsonTreeWriter canonicalTreeWriter =
                new com.google.gson.internal.bind.JsonTreeWriter();
            canonicalTreeWriter.setSerializeNulls(false);
            thisAdapter.write(canonicalTreeWriter, value);
            JsonObject obj = canonicalTreeWriter.get().getAsJsonObject();""")
        patched = patched.replace("elementAdapter.write(out, obj);", """boolean canonicalSerializeNulls = out.getSerializeNulls();
            out.setSerializeNulls(true);
            try {
                elementAdapter.write(out, obj);
            } finally {
                out.setSerializeNulls(canonicalSerializeNulls);
            }""")
        simple_writers += 1
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


def mapped_object_fields(document: Mapping[str, object], schema_name: str) -> dict[str, bool]:
    schemas = document.get("components", {}).get("schemas", {})
    if not isinstance(schemas, Mapping):
        return {}
    schema = schemas.get(schema_name)
    if not isinstance(schema, Mapping):
        return {}
    properties = schema.get("properties")
    if not isinstance(properties, Mapping):
        return {}
    fields: dict[str, bool] = {}
    for wire_name, property_schema in properties.items():
        if not isinstance(wire_name, str) or not isinstance(property_schema, Mapping):
            continue
        reference = property_schema.get("$ref")
        if not isinstance(reference, str) or not reference.startswith("#/components/schemas/"):
            continue
        referenced_name = reference.rsplit("/", 1)[-1]
        nullable = MAPPED_OBJECT_SCHEMAS.get(referenced_name)
        if nullable is not None:
            fields[wire_name] = nullable
    return fields


def patch_mapped_object_validation(path: pathlib.Path, fields: Mapping[str, bool]) -> int:
    """Restore object-root validation lost when named free-form schemas map to JsonElement."""

    if not fields:
        return 0
    source = path.read_text()
    source, invalid_calls = INVALID_JSON_ELEMENT_VALIDATION.subn("", source)
    anchor = "JsonObject jsonObj = jsonElement.getAsJsonObject();"
    # Some inline resume objects retain additional-properties adapters with a second jsonObj
    # declaration in read(). Restore validation only in the static validator, not that decoder.
    start = source.find("public static void validateJsonElement(")
    if start < 0:
        start = 0
        end = len(source)
    else:
        adapter = re.search(r"\n\s*public static class CustomTypeAdapterFactory", source[start:])
        if adapter is None:
            raise ValueError(f"expected generated adapter boundary in {path}")
        end = start + adapter.start()
    validator = source[start:end]
    if validator.count(anchor) != 1:
        raise ValueError(f"expected one generated validation anchor in {path}")

    guards: list[str] = []
    for wire_name, nullable in sorted(fields.items()):
        non_null = f' && !jsonObj.get("{wire_name}").isJsonNull()' if nullable else ""
        guards.append(
            f'''\n      if (jsonObj.has("{wire_name}"){non_null} && !jsonObj.get("{wire_name}").isJsonObject()) {{
        throw new IllegalArgumentException(String.format(java.util.Locale.ROOT, "Expected the field `{wire_name}` to be an object in the JSON string but got `%s`", jsonObj.get("{wire_name}").toString()));
      }}'''
        )
    guard_source = "".join(guards)
    first_guard = f'if (jsonObj.has("{sorted(fields)[0]}")'
    added = 0
    if first_guard not in validator:
        source = source[:start] + validator.replace(anchor, anchor + guard_source) + source[end:]
        added = len(fields)
    if invalid_calls or added:
        path.write_text(source)
    return added


def main() -> None:
    paths = list(MODEL_ROOT.glob("*.java"))
    changed_fields = sum(patch_model(path) for path in paths)
    document = json.loads(SPEC_PATH.read_text())
    model_schemas = canonical_model_schemas(document)
    canonical_schemas = set(model_schemas)
    simple_writers = 0
    union_writers = 0
    nullable_containers = 0
    object_guards = 0
    required_nulls = 0
    optional_null_guards = 0
    for schema_name in canonical_schemas:
        path = MODEL_ROOT / f"{schema_name}.java"
        if path.is_file():
            required_nulls += patch_required_nullable_fields(
                path, required_nullable_fields(model_schemas.get(schema_name, {}))
            )
            optional_null_guards += patch_optional_nonnullable_fields(
                path, optional_nonnullable_fields(model_schemas[schema_name], document)
            )
            nullable_containers += patch_nullable_container_defaults(path)
            object_guards += patch_mapped_object_validation(path, mapped_object_fields(document, schema_name))
            simple, union = patch_streaming_writers(path)
            simple_writers += simple
            union_writers += union
    print(
        "Configured null-preserving adapters on "
        f"{changed_fields} generated JsonElement fields/maps; cleared {nullable_containers} optional container defaults "
        f"and preserved {required_nulls} required nullable fields; rejected null on {optional_null_guards} "
        f"optional nonnullable fields; added {object_guards} mapped-object root guards; "
        f"streamed {simple_writers} object and "
        f"{union_writers} union writers in the canonical contract closure."
    )


if __name__ == "__main__":
    main()

#!/usr/bin/env python3
"""Preserve absent versus explicit-null routing effort in generated Gson models.

The routing contract permits clearing effort with JSON null. Gson's nullable enum field
otherwise drops that null during serialization, including after a response round trip.
This patch is scoped to the experimental routing closure and derives generated anyOf
branch names from the pinned OpenAPI document.
"""

from __future__ import annotations

import json
import pathlib
import re
from collections.abc import Mapping

from patch_generated_json_elements import (
    patch_required_nullable_fields,
    patch_streaming_writers,
    referenced_schemas,
)


SPEC = pathlib.Path("spec/vertesia-openapi.json")
MODELS = pathlib.Path("src/main/java/io/vertesia/model")
ROOTS = ("ExperimentalUpdateAgentRoutingControlPayload", "ExperimentalAgentRoutingControlResponse")
UNIONS = ("ExperimentalAgentRoutingControlChange", "ExperimentalAgentRoutingIntent")
MARKER = "private transient boolean effortPresent;"


def routing_branches(schemas: Mapping[str, object]) -> dict[str, bool]:
    branches: dict[str, bool] = {}
    for union in UNIONS:
        schema = schemas.get(union)
        if not isinstance(schema, Mapping):
            raise ValueError(f"Missing routing union {union}")
        alternatives = schema.get("anyOf")
        if not isinstance(alternatives, list) or not alternatives:
            raise ValueError(f"Routing union {union} has no anyOf branches")
        for index, alternative in enumerate(alternatives):
            if not isinstance(alternative, Mapping):
                raise ValueError(f"Invalid routing union {union} branch {index}")
            reference = alternative.get("$ref")
            branch = schemas.get(reference.rsplit("/", 1)[-1]) if isinstance(reference, str) else alternative
            if not isinstance(branch, Mapping):
                raise ValueError(f"Missing routing branch {union} {index}")
            properties = branch.get("properties")
            if not isinstance(properties, Mapping) or "effort" not in properties:
                raise ValueError(f"Routing branch {union} {index} has no effort field")
        source = (MODELS / f"{union}.java").read_text()
        for name in re.findall(r"TypeAdapter<([A-Za-z0-9_]+)> adapter[A-Za-z0-9_]+", source):
            path = MODELS / f"{name}.java"
            if not path.is_file():
                raise ValueError(f"Generated routing effort model missing: {name}")
            model_source = path.read_text()
            if "private ReasoningEffort effort;" not in model_source:
                raise ValueError(f"Generated routing branch {name} has no nullable effort field")
            required = re.search(
                r"openapiRequiredFields = new HashSet<String>\(Arrays.asList\((.*?)\)\);",
                model_source,
                re.DOTALL,
            )
            if required is None:
                if "openapiRequiredFields = new HashSet<String>(0);" not in model_source:
                    raise ValueError(f"Generated routing branch {name} has no required field list")
                branches[name] = False
            else:
                branches[name] = '"effort"' in required.group(1)
    return branches


def patch_optional_effort(path: pathlib.Path) -> None:
    source = path.read_text()
    if MARKER in source:
        return
    declaration = "  private ReasoningEffort effort;"
    if source.count(declaration) != 1:
        raise ValueError(f"Generated effort declaration changed in {path}")
    source = source.replace(declaration, declaration + "\n\n  " + MARKER, 1)
    assignment = "    this.effort = effort;"
    if source.count(assignment) != 2:
        raise ValueError(f"Generated effort setters changed in {path}")
    source = source.replace(assignment, assignment + "\n    this.effortPresent = true;")
    writer = """             JsonObject obj = thisAdapter.toJsonTree(value).getAsJsonObject();
             elementAdapter.write(out, obj);"""
    replacement = """             JsonObject obj = thisAdapter.toJsonTree(value).getAsJsonObject();
             if (!value.effortPresent) {
               obj.remove("effort");
             } else if (value.effort == null) {
               obj.add("effort", com.google.gson.JsonNull.INSTANCE);
             }
             boolean previousSerializeNulls = out.getSerializeNulls();
             out.setSerializeNulls(true);
             try {
               elementAdapter.write(out, obj);
             } finally {
               out.setSerializeNulls(previousSerializeNulls);
             }"""
    if source.count(writer) != 1:
        raise ValueError(f"Generated effort writer changed in {path}")
    source = source.replace(writer, replacement, 1)
    reader = "             return thisAdapter.fromJsonTree(jsonElement);"
    if source.count(reader) != 1:
        raise ValueError(f"Generated effort reader changed in {path}")
    source = source.replace(
        reader,
        """             ExperimentalRoutingEffortModel result = thisAdapter.fromJsonTree(jsonElement);
             result.effortPresent = jsonElement.getAsJsonObject().has("effort");
             return result;""".replace("ExperimentalRoutingEffortModel", path.stem),
        1,
    )
    getter = re.search(r"  public ReasoningEffort getEffort\(\) \{\n.*?\n  \}", source, re.DOTALL)
    if getter is None:
        raise ValueError(f"Generated effort getter changed in {path}")
    source = source[: getter.end()] + "\n\n  public boolean hasEffort() {\n    return effortPresent;\n  }" + source[getter.end() :]
    path.write_text(source)


def patch_routing_union_adapters(path: pathlib.Path) -> None:
    source = path.read_text()
    original = "gson.getDelegateAdapter(this, TypeToken.get("
    if original not in source:
        if re.search(r"gson\s*\.\s*getAdapter\s*\(\s*TypeToken\s*\.\s*get\s*\(", source) and "// Select routing anyOf by its route key." in source:
            return
        raise ValueError(f"Generated routing union leaf adapters changed in {path}")
    # The generated union's delegate lookup bypasses the leaf adapter that remembers whether
    # nullable effort was present. The leaf is a different type, so normal adapter lookup is safe.
    source = source.replace(original, "gson.getAdapter(TypeToken.get(")
    branches = re.findall(r"TypeAdapter<([A-Za-z0-9_]+)> adapter[A-Za-z0-9_]+", source)
    selected: dict[str, str] = {}
    for name in branches:
        branch_source = (MODELS / f"{name}.java").read_text()
        for key in ("model", "inference_profile"):
            if f'SERIALIZED_NAME_{key.upper()} = "{key}"' in branch_source:
                selected[key] = name
    if set(selected) != {"model", "inference_profile"}:
        raise ValueError(f"Generated routing selector branches are incomplete in {path}")
    model = selected["model"]
    profile = selected["inference_profile"]
    union = path.stem
    anchor = "                    JsonElement jsonElement = elementAdapter.read(in);"
    if source.count(anchor) != 1:
        raise ValueError(f"Generated routing union reader changed in {path}")
    dispatch = f'''

                    // Select routing anyOf by its route key. Permissive future-field validation
                    // must not let an effort-only branch discard model or inference_profile.
                    if (jsonElement == null || !jsonElement.isJsonObject()) {{
                        throw new IOException("Routing selection must be a JSON object");
                    }}
                    JsonObject routeFields = jsonElement.getAsJsonObject();
                    if (routeFields.has("model") && routeFields.has("inference_profile")) {{
                        throw new IOException("Routing model and inference_profile are mutually exclusive");
                    }}
                    if (routeFields.has("model")) {{
                        {model}.validateJsonElement(jsonElement);
                        {union} result = new {union}();
                        result.setActualInstance(adapter{model}.fromJsonTree(jsonElement));
                        return result;
                    }}
                    if (routeFields.has("inference_profile")) {{
                        {profile}.validateJsonElement(jsonElement);
                        {union} result = new {union}();
                        result.setActualInstance(adapter{profile}.fromJsonTree(jsonElement));
                        return result;
                    }}'''
    source = source.replace(anchor, anchor + dispatch, 1)
    path.write_text(source)


def main() -> None:
    document = json.loads(SPEC.read_text())
    schemas = document.get("components", {}).get("schemas", {})
    if not isinstance(schemas, Mapping):
        raise ValueError("OpenAPI components.schemas missing")
    if not any(root in schemas for root in ROOTS):
        print("No experimental routing schemas in this spec; nullable effort patch skipped.")
        return
    if not all(root in schemas for root in ROOTS):
        raise ValueError("Incomplete experimental routing contract")
    branches = routing_branches(schemas)
    for name, required in branches.items():
        path = MODELS / f"{name}.java"
        if not path.is_file():
            raise ValueError(f"Generated routing effort model missing: {name}")
        if required:
            patched = patch_required_nullable_fields(path, {"effort"})
            if patched != 1 and "RequiredNullableTypeAdapterFactory.class" not in path.read_text():
                raise ValueError(f"Required nullable routing effort was not patched: {name}")
            patch_streaming_writers(path)
        else:
            patch_optional_effort(path)
    closure = set(UNIONS)
    for root in ROOTS:
        closure.update(referenced_schemas(document, root))
    for name in sorted(closure - branches.keys()):
        path = MODELS / f"{name}.java"
        if path.is_file():
            patch_streaming_writers(path)
    for name in UNIONS:
        patch_routing_union_adapters(MODELS / f"{name}.java")
    print(f"Patched nullable routing effort in {len(branches)} generated branch models.")


if __name__ == "__main__":
    main()

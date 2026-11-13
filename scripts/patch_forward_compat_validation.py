#!/usr/bin/env python3
"""Patch generated Java model validation and schema serialization.

OpenAPI Generator's Gson Java client validates response payloads before
deserialization. Some generated validators reject any future response field,
which is too strict for a public client talking to a newer Vertesia server.

This patch keeps required-field and type validation, but removes the generated
block that throws only because a JSON object contains fields unknown to this
client version. Literal union tags stay strict, and JSON Schema wire properties
remain separate from the generated catch-all map.

A newer server can also return a union variant this client does not know. The
generated oneOf/anyOf classes throw unless their branches match as expected, and
a parent's validation fails with them, so one unknown element fails the whole
response. The patch keeps such a variant as the JsonElement it was received as:
getActualInstance() and getRaw() return it, and serialization writes it back.
A union with a published discriminator does this only for an unknown
discriminator value; a known value still has to match its branch.
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


def union_literal_tags(schemas: dict) -> dict:
    """Map each object union to the properties every branch declares as a distinct literal.

    The tag may be optional, as `_option_id` is in ModelOptions: when present, it still names the branch.
    """
    tags = {}
    for union_name, union in schemas.items():
        variants = union.get("oneOf", union.get("anyOf", []))
        names = [item["$ref"].rsplit("/", 1)[-1] for item in variants if "$ref" in item]
        if len(names) < 2 or len(names) != len(variants):
            continue
        branches = [schemas.get(name, {}) for name in names]
        shared = set.intersection(*(set(branch.get("properties", {})) for branch in branches))
        for field in sorted(shared):
            definitions = [branch["properties"][field] for branch in branches]
            values = [definition.get("const") for definition in definitions]
            if all(isinstance(value, str) for value in values) and len(set(values)) == len(values):
                tags.setdefault(union_name, {})[field] = dict(zip(names, definitions))
    return tags


def union_literal_schemas(schemas: dict) -> dict:
    """Find distinct literal tags that discriminate object union branches."""
    tagged = {}
    for fields in union_literal_tags(schemas).values():
        for field, branches in fields.items():
            for name, definition in branches.items():
                tagged.setdefault(name, {"properties": {}})["properties"][field] = definition
    return tagged

UNION_HELPER = "isUnknownVariant("


def _line_start(text: str, index: int) -> int:
    return text.rfind("\n", 0, index) + 1


def _indent(text: str, index: int) -> str:
    start = _line_start(text, index)
    return text[start : start + len(text[start:]) - len(text[start:].lstrip(" "))]


def _block(lines: list[str], indent: str, unit: str) -> str:
    """Render lines whose leading tabs count indentation units, as google-java-format would."""
    rendered = []
    for line in lines:
        depth = len(line) - len(line.lstrip("\t"))
        prefix = indent + unit * depth
        line = line.lstrip("\t")
        if len(prefix + line) > 100 and " = " in line:
            # google-java-format breaks an overlong declaration after "=", with a double indent.
            left, right = line.split(" = ", 1)
            line = left + " =\n" + prefix + unit * 2 + right
        rendered.append(prefix + line if line else "")
    return "\n".join(rendered) + "\n"


def _insert_before_line(text: str, index: int, lines: list[str], unit: str) -> str:
    start = _line_start(text, index)
    return text[:start] + _block(lines, _indent(text, index), unit) + text[start:]


def _insert_after_line(text: str, index: int, lines: list[str], unit: str, deeper: bool) -> str:
    end = text.index("\n", index) + 1
    indent = _indent(text, index) + (unit if deeper else "")
    return text[:end] + _block(lines, indent, unit) + text[end:]


def _throw_before(text: str, message: str, start: int = 0) -> int:
    position = text.index(message, start)
    return text.rindex("throw new IOException(", 0, position)


def union_discriminators(schemas: dict) -> dict:
    """Map each discriminated union to its discriminator property and {value: branch class}."""
    return {
        name: (
            schema["discriminator"]["propertyName"],
            {value: ref.rsplit("/", 1)[-1] for value, ref in sorted(schema["discriminator"].get("mapping", {}).items())},
        )
        for name, schema in schemas.items()
        if "discriminator" in schema and ("oneOf" in schema or "anyOf" in schema)
    }


def patch_union_fallback(
    text: str, name: str, discriminator: tuple[str, dict[str, str]] | None, lossless: bool = False
) -> str:
    """Keep a union variant this client does not know instead of failing the response."""
    if not re.search(r"\bclass " + re.escape(name) + r" extends AbstractOpenApiSchema\b", text):
        return text
    if UNION_HELPER in text:
        return text
    # Raw generator output indents with 2 spaces; Spotless (AOSP) with 4.
    unit = "    " if re.search(r"\n    private static final Logger log\b", text) else "  "
    keep = ["if (isUnknownVariant(jsonElement)) {", f"\t{name} ret = new {name}();",
            "\tret.setActualInstance(jsonElement);", "\treturn ret;", "}"]
    accept = ["if (isUnknownVariant(jsonElement)) {", "\treturn;", "}"]
    any_of = f'"The JSON string is invalid for {name} with anyOf schemas' in text

    # Members, before the Javadoc of fromJson(String).
    from_json = text.index(f"public static {name} fromJson(String jsonString)")
    javadoc = text.rindex("/**", 0, from_json)
    if discriminator:
        property_name, mapping = discriminator
        values = list(mapping)
        helper = [
            "// A variant added by a newer server: a discriminator value this client does not know.",
            "private static boolean isUnknownVariant(JsonElement jsonElement) {",
            "\tif (!jsonElement.isJsonObject()) {",
            "\t\treturn false;",
            "\t}",
            f"\tJsonElement tag = jsonElement.getAsJsonObject().get({json.dumps(property_name)});",
            "\tif (tag == null || !tag.isJsonPrimitive() || !tag.getAsJsonPrimitive().isString()) {",
            "\t\treturn false;",
            "\t}",
            "\tswitch (tag.getAsString()) {",
            '\t\tcase "":',
            *[f"\t\tcase {json.dumps(value)}:" for value in values],
            "\t\t\treturn false;",
            "\t\tdefault:",
            "\t\t\treturn true;",
            "\t}",
            "}",
            "",
            "// A branch matches only the discriminator value the spec maps to it. Branch validation alone",
            "// accepts any value when the tag is an enum with an unknown-value default.",
            "private static void checkDiscriminator(JsonElement jsonElement, String value)",
            "\t\tthrows IOException {",
            f"\tJsonElement tag = jsonElement.getAsJsonObject().get({json.dumps(property_name)});",
            "\tif (tag == null || !tag.isJsonPrimitive() || !value.equals(tag.getAsString())) {",
            f"\t\tthrow new IOException(\"Expected {property_name} to be \" + value);",
            "\t}",
            "}",
        ]
    else:
        helper = [
            "// A variant added by a newer server: no known variant decodes it.",
            "private static boolean isUnknownVariant(JsonElement jsonElement) {",
            "\treturn !jsonElement.isJsonNull();",
            "}",
        ]
    if lossless:
        helper += [
            "",
            "// The first branch that validates can drop or rewrite received properties, for example options",
            "// merged from several families: then the next branch is tried, and the payload is kept raw if",
            "// no branch writes back every received property unchanged.",
            "private static void checkLossless(JsonElement received, Object variant) throws IOException {",
            "\tJsonElement written = JSON.getGson().toJsonTree(variant);",
            "\tfor (Map.Entry<String, JsonElement> entry : received.getAsJsonObject().entrySet()) {",
            "\t\tif (!entry.getValue().equals(written.getAsJsonObject().get(entry.getKey()))) {",
            '\t\t\tthrow new IOException("The variant would not keep " + entry.getKey());',
            "\t\t}",
            "\t}",
            "}",
        ]
    members = [
        "/**",
        " * Returns a variant added by a newer server, as it was received, or null for a variant this",
        " * client version knows.",
        " */",
        "public JsonElement getRaw() {",
        "\tObject instance = getActualInstance();",
        "\treturn instance instanceof JsonElement ? (JsonElement) instance : null;",
        "}",
        "",
        *helper,
        "",
    ]
    text = _insert_before_line(text, javadoc, members, unit)

    if discriminator:
        # Each branch is tried in read() and in validateJsonElement().
        for value, branch in discriminator[1].items():
            call = f"{branch}.validateJsonElement(jsonElement);"
            position = 0
            while (position := text.find(call, position)) != -1:
                text = _insert_before_line(text, position, [f"checkDiscriminator(jsonElement, {json.dumps(value)});"], unit)
                position = text.index(call, position) + len(call)

    # Validation, which parent models run on their union properties.
    validate = text.index("public static void validateJsonElement(JsonElement jsonElement) throws IOException {")
    if discriminator:
        text = _insert_after_line(text, validate, accept, unit, deeper=True)
    elif any_of:
        text = _insert_before_line(text, _throw_before(text, f'"The JSON string is invalid for {name}', validate), accept, unit)
    else:
        count = text.index("if (validCount != 1) {", validate)
        text = _insert_before_line(text, count, ["if (validCount != 1 && isUnknownVariant(jsonElement)) {", "\treturn;", "}"], unit)

    # Deserialization.
    read = text.index("JsonElement jsonElement = elementAdapter.read(in);")
    if discriminator:
        text = _insert_after_line(text, read, keep, unit, deeper=False)
    else:
        text = _insert_before_line(text, _throw_before(text, f'"Failed deserialization for {name}:', read), keep, unit)

    if lossless and any_of:
        call = "ret.setActualInstance(actualAdapter.fromJsonTree(jsonElement));"
        position = text.index("JsonElement jsonElement = elementAdapter.read(in);")
        while (position := text.find(call, position)) != -1:
            text = _insert_after_line(text, position, ["checkLossless(jsonElement, ret.getActualInstance());"], unit, False)
            position = text.index(call, position) + len(call)

    # Serialization.
    write = _throw_before(text, "\"Failed to serialize as the type doesn't match")
    text = _insert_before_line(text, write, [
        "if (value.getActualInstance() instanceof JsonElement) {",
        "\telementAdapter.write(out, (JsonElement) value.getActualInstance());",
        "\treturn;",
        "}",
    ], unit)

    setter = text.index("public void setActualInstance(Object instance) {")
    return _insert_after_line(text, setter, [
        "if (instance instanceof JsonElement) {",
        "\tsuper.setActualInstance(instance);",
        "\treturn;",
        "}",
        "",
    ], unit, deeper=True)


def main() -> None:
    spec_schemas = json.loads(pathlib.Path("spec/vertesia-openapi.json").read_text())["components"]["schemas"]
    schemas = union_literal_schemas(spec_schemas)
    discriminators = union_discriminators(spec_schemas)
    # anyOf unions whose branches carry a literal tag: a payload no single branch keeps whole is kept raw.
    lossless = {name for name in union_literal_tags(spec_schemas) if "anyOf" in spec_schemas[name]}
    changed = 0
    for path in ROOT.glob("*.java"):
        text = path.read_text()
        patched = UNKNOWN_FIELD_BLOCK.sub("\n", text)
        patched = patch_schema_constants(patched, schemas.get(path.stem, {}))
        patched = patch_schema_property_collision(patched)
        patched = patch_union_fallback(
            patched, path.stem, discriminators.get(path.stem), lossless=path.stem in lossless
        )
        if patched != text:
            path.write_text(patched)
            changed += 1

    print(f"Patched compatibility validation in {changed} generated Java model files.")


if __name__ == "__main__":
    main()

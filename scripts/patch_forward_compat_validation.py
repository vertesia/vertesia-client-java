#!/usr/bin/env python3
"""Remove generated Java unknown-field validation from OpenAPI models.

OpenAPI Generator's Gson Java client validates response payloads before
deserialization. Some generated validators reject any future response field,
which is too strict for a public client talking to a newer Vertesia server.

This patch keeps required-field and type validation, but removes the generated
block that throws only because a JSON object contains fields unknown to this
client version.
"""

from __future__ import annotations

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


def main() -> None:
    changed = 0
    for path in ROOT.glob("*.java"):
        text = path.read_text()
        patched = UNKNOWN_FIELD_BLOCK.sub("\n", text)
        if patched != text:
            path.write_text(patched)
            changed += 1

    print(f"Patched unknown-field validation in {changed} generated Java model files.")


if __name__ == "__main__":
    main()

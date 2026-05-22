#!/usr/bin/env python3
"""Patch generated Java annotations to Jakarta namespace.

OpenAPI Generator is configured with useJakartaEe=true, but this patch keeps
the checked-in generated source aligned when generator output changes or older
generator versions leave javax.annotation references behind.
"""

from __future__ import annotations

import pathlib


ROOT = pathlib.Path("src/main/java/io/vertesia")


def main() -> None:
    changed = 0
    for path in ROOT.rglob("*.java"):
        text = path.read_text()
        patched = text.replace("javax.annotation.", "jakarta.annotation.")
        if patched != text:
            path.write_text(patched)
            changed += 1

    print(f"Patched Jakarta annotation namespace in {changed} generated Java files.")


if __name__ == "__main__":
    main()

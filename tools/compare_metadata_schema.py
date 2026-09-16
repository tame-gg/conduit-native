#!/usr/bin/env python3
"""Compare what Conduit hands a 1.14 client against what a real 1.14 server sends.

Both inputs are MetadataSchemaProbe output captured as protocol 477: one from
the real 1.14 server directly, one from the same probe talking to a 1.13.2
server through Conduit. For every entity, every (index, serializer) pair the
proxied run produced must be one the native run also produced.

That is the exact property the arrow crash violated. A field at an index 1.14
uses for something else - or at the right index with the wrong serializer - is
a field the client will read as the wrong type and cast, which is how an
ItemStack ended up being cast to a Byte. A field that is simply missing is not
an error here: fields with no 1.13.2 counterpart are meant to be dropped.

Usage:
  python tools/compare_metadata_schema.py <native-477.txt> <through-conduit.txt>
"""

from __future__ import annotations

import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
RES = ROOT / "src/main/resources/gg/tame/conduit/protocol/entity"


def registry_names() -> list[str]:
    return [line.strip() for line in
            (RES / "entitytypes_477_names.txt").read_text(encoding="utf-8").split("\n")]


def parse(path: Path) -> dict[str, set[str]]:
    """entity identifier -> the (index:serializer) pairs observed for it."""
    names = registry_names()
    observed: dict[str, set[str]] = {}
    for line in path.read_text(encoding="utf-8").split("\n"):
        if not line.startswith("SCHEMA "):
            continue
        _, key, fields = line.split(" ", 2)
        if key.startswith(("mob#", "object#")):
            index = int(key.split("#")[1])
            name = names[index] if index < len(names) else key
        else:
            name = key
        pairs = {p for p in fields.strip().split(",") if ":" in p and "UNWALKABLE" not in p}
        observed.setdefault(name, set()).update(pairs)
    return observed


def main() -> None:
    if len(sys.argv) != 3:
        raise SystemExit(__doc__)
    native = parse(Path(sys.argv[1]))
    proxied = parse(Path(sys.argv[2]))

    checked = 0
    bad: list[str] = []
    missing_native: list[str] = []
    for entity, pairs in sorted(proxied.items()):
        if entity.startswith("unknown"):
            continue
        if entity not in native:
            missing_native.append(entity)
            continue
        checked += 1
        wrong = sorted(pairs - native[entity], key=lambda p: int(p.split(":")[0]))
        if wrong:
            bad.append(f"  {entity}: {','.join(wrong)} "
                       f"(1.14 sends {','.join(sorted(native[entity], key=lambda p: int(p.split(':')[0])))})")

    print(f"entities compared: {checked}")
    if missing_native:
        print(f"no native 1.14 capture for (not checked): {len(missing_native)}")
    if bad:
        print(f"FAIL: {len(bad)} entities carry a field 1.14 does not have there")
        for line in bad:
            print(line)
        raise SystemExit(1)
    print("OK: every field Conduit sends lands where 1.14 reads that field")


if __name__ == "__main__":
    main()

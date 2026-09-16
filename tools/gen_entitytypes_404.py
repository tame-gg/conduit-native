#!/usr/bin/env python3
"""Emit the protocol 404 (1.13.2) entity-type registry as index -> identifier.

The 1.13.x server's `--reports` predates the registry dump (it emits only
blocks.json, items.json and commands.json), so unlike 477 the 404 entity table
cannot be read straight out of a report. It is instead recovered from data
already in the tree and already verified: `entitytypes_mob_393_to_765.bin` maps
each 1.13 registry index to a 1.20.4 index, and `entitytypes_765_names.txt`
names those. Composing the two recovers the 1.13 registry by name.

1.13.1 and 1.13.2 added no entity types, so the 1.13 registry is also the 1.13.2
registry - which is exactly why 404 needs a table at all: 1.14 inserted `cat` at
index 6, shifting 88 of the 95 ids.

One identifier has to be undone: the 393<->765 table resolves index 53 through
the 1.16 rename `zombie_pigman` -> `zombified_piglin`. On 1.13.2 and 1.14 the
entity is still `zombie_pigman`, so the modern name is mapped back.
"""

from __future__ import annotations

import struct
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
RES = ROOT / "src/main/resources/gg/tame/conduit/protocol/entity"

# Renames applied between 1.13.2 and 1.20.4 that must be undone to recover the
# 1.13.2-era identifier. Only entity types that actually existed in 1.13.2.
MODERN_TO_404 = {
    "minecraft:zombified_piglin": "minecraft:zombie_pigman",
}


def read_table(path: Path) -> list[int]:
    data = path.read_bytes()
    count = struct.unpack("<i", data[:4])[0]
    return list(struct.unpack("<" + "i" * count, data[4:4 + 4 * count]))


def main() -> None:
    mapping = read_table(RES / "entitytypes_mob_393_to_765.bin")
    modern = (RES / "entitytypes_765_names.txt").read_text(encoding="utf-8").split("\n")

    names: list[str] = []
    for index, target in enumerate(mapping):
        if target < 0 or target >= len(modern) or not modern[target]:
            raise SystemExit(f"1.13 entity index {index} does not resolve to a 1.20.4 name")
        names.append(MODERN_TO_404.get(modern[target], modern[target]))

    out = RES / "entitytypes_404_names.txt"
    out.write_text("\n".join(names) + "\n", encoding="utf-8")
    print(f"  {out.relative_to(ROOT)}: {len(names)} names")

    known = (RES / "entitytypes_477_names.txt").read_text(encoding="utf-8").split("\n")
    index_477 = {name: i for i, name in enumerate(known) if name}
    missing = [n for n in names if n not in index_477]
    shifted = [n for n in names if n in index_477 and index_477[n] != names.index(n)]
    print(f"  404 entity types absent from 477: {missing}")
    print(f"  404 entity ids that shift in 477: {len(shifted)} of {len(names)}")


if __name__ == "__main__":
    main()

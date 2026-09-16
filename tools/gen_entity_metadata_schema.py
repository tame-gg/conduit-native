#!/usr/bin/env python3
"""Turn MetadataSchemaProbe output into Conduit's entity metadata schema tables.

The probe connects straight to a real 1.13.2 or 1.14 server, summons every
entity type in the 1.13.2 registry, and writes down the (metadataIndex,
serializer) pairs each entity actually sends. This converts those raw
observations into one resource per protocol:

    <entity identifier> <index>:<serializer>,<index>:<serializer>,...

Entity types are keyed by identifier, so the two protocols' files line up by
name rather than by the numeric ids, which 1.14 reshuffled. Spawn Object types
are resolved through the legacy object enumeration on 1.13.2 and through the
entity registry on 1.14 - which is itself the difference that caused the arrow
crash, and the reason this tool resolves each side with its own namespace.

Usage:
  python tools/gen_entity_metadata_schema.py <schema-404.txt> <schema-477.txt>
"""

from __future__ import annotations

import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
RES = ROOT / "src/main/resources/gg/tame/conduit/protocol/entity"


def legacy_object_names() -> dict[int, str]:
    """The 1.13 Spawn Object enumeration, read from the class that defines it."""
    source = (ROOT / "src/main/java/gg/tame/conduit/protocol/entity/LegacyObjectTypes.java").read_text(
        encoding="utf-8")
    return {int(k): v for k, v in re.findall(r'NAMES\.put\((\d+),\s*"([^"]+)"', source)}


def registry_names(protocol: int) -> list[str]:
    path = RES / f"entitytypes_{protocol}_names.txt"
    return [line.strip() for line in path.read_text(encoding="utf-8").split("\n")]


def parse(path: Path) -> dict[str, str]:
    observed: dict[str, str] = {}
    for line in path.read_text(encoding="utf-8").split("\n"):
        if not line.startswith("SCHEMA "):
            continue
        _, key, fields = line.split(" ", 2)
        observed[key] = fields.strip()
    return observed


def resolve(observed: dict[str, str], protocol: int) -> dict[str, str]:
    """Raw probe keys (mob#N / object#N) -> entity identifiers."""
    registry = registry_names(protocol)
    legacy = legacy_object_names()
    resolved: dict[str, str] = {}
    for key, fields in observed.items():
        if "UNWALKABLE" in fields:
            # A serializer the probe could not step over: the rest of that block
            # is unreliable, so the entity is left out rather than recorded half
            # right. Callers then fall back to the base-class mapping.
            fields = fields.split(",UNWALKABLE")[0]
        if key.startswith("mob#"):
            index = int(key[4:])
            name = registry[index] if index < len(registry) else None
        elif key.startswith("object#"):
            index = int(key[7:])
            # 1.13.2 Spawn Object carries the legacy enumeration; 1.14 carries
            # the entity registry id. This is the delta the translator has to
            # honour, so each side is resolved in its own namespace.
            name = legacy.get(index) if protocol < 477 else (
                registry[index] if index < len(registry) else None)
        elif key.startswith("minecraft:"):
            name = key
        else:
            name = None
        if not name:
            continue
        # Keep the longest observation: a mob seen mid-tick may only report the
        # fields that changed, while its spawn reports the full set.
        if name not in resolved or len(fields) > len(resolved[name]):
            resolved[name] = fields
    return resolved


def write(path: Path, schema: dict[str, str]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    lines = [f"{name} {fields}" for name, fields in sorted(schema.items())]
    path.write_text("\n".join(lines) + "\n", encoding="utf-8")
    print(f"  {path.relative_to(ROOT)}: {len(lines)} entity types")


def main() -> None:
    if len(sys.argv) != 3:
        raise SystemExit(__doc__)
    for protocol, source in ((404, Path(sys.argv[1])), (477, Path(sys.argv[2]))):
        schema = resolve(parse(source), protocol)
        write(RES / f"entity_metadata_{protocol}.txt", schema)


if __name__ == "__main__":
    main()

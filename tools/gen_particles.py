#!/usr/bin/env python3
"""Generate Conduit's 393 <-> 765 particle id tables from OFFICIAL Mojang data.

A particle travels as a registry index, and the registries do not agree: 1.13 has
50 particle types and 1.20.4 has 101, with the additions spread through the list
rather than appended. So an index means a different particle on each side.

Input, per side, is Mojang's own registry, exactly as for sounds:

  1.20.4  `registries.json` from the server's own report:
            java -DbundlerMainClass=net.minecraft.data.Main -jar server.jar --reports

  1.13    read out of its jar, because its reports predate the registry dump:
            javac -d out tools/DumpRegistry.java
            java -cp out DumpRegistry mc113/server.jar minecraft:explosion particles_393.txt
            java -cp out DumpRegistry mc113/server.jar minecraft:diamond_sword items_393.txt

**The dumper is calibrated, not trusted.** It reads ids out of an obfuscated jar,
so the second dump above points it at the one 1.13 registry whose ids are already
known -- items.json, which the 1.13 server does report -- and its output must
reproduce that report exactly. Only then is the particle dump used.

Do not check a dump by comparing it against a later version's registry: a
registry's order is not stable. 1.14 reordered the sound registry wholesale, and
an earlier version of the sound generator accepted a table in which two ids out
of three were wrong because it assumed otherwise.

Usage:
  python tools/gen_particles.py <particles_393.txt> <items_393.txt> <reports-1.13-dir> <reports-1.20.4-dir>

Writes the binary tables under src/main/resources/gg/tame/conduit/protocol/particle/.
Table format: little-endian i32 length, then that many little-endian i32 entries.
A -1 entry means "no mapping" and callers must fail closed.
"""

from __future__ import annotations

import json
import struct
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
RES = ROOT / "src/main/resources/gg/tame/conduit/protocol/particle"

# Real Mojang renames between 1.13 and 1.20.4. None so far: every 1.13 particle
# that still exists kept its name. Kept for the same reason the sound table keeps
# its own -- so that a future rename is recorded here rather than silently lost.
PARTICLE_RENAMES_393_TO_765: dict[str, str] = {}

# 1.13 particles with no 1.20.4 counterpart. `barrier` is the whole list: 1.18
# replaced it with `block_marker`, which is a different particle taking a block
# state as its payload rather than a standalone one, so it is not a rename that
# this table could make. It maps to -1 and that one packet is dropped.
PARTICLES_REMOVED_AFTER_393 = {
    "minecraft:barrier",
}


def read_dump(path: Path) -> list[str]:
    """`<id> <identifier>` lines as a list indexed by id."""
    names: list[str] = []
    for line in path.read_text(encoding="utf-8").splitlines():
        if not line.strip() or line.startswith("#"):
            continue
        index, identifier = line.split(" ", 1)
        index = int(index)
        if index != len(names):
            raise SystemExit(f"{path}: id {index} is out of order; expected {len(names)}")
        names.append(identifier.strip())
    return names


def registry(reports: Path, key: str) -> list[str]:
    """A registry from a `registries.json` report as a list indexed by protocol id."""
    entries = json.loads((reports / "registries.json").read_text(encoding="utf-8"))[key]["entries"]
    by_id = {value["protocol_id"]: name for name, value in entries.items()}
    highest = max(by_id)
    if len(by_id) != highest + 1:
        raise SystemExit(f"{key} in {reports} has gaps; cannot be indexed by id")
    return [by_id[index] for index in range(highest + 1)]


def calibrate(dump: Path, reports: Path) -> None:
    """Fails unless the dumper reproduces a registry whose ids are already known.

    The dumper reads ids out of an obfuscated jar, so the question is not whether
    its answer looks plausible but whether its answer is the registry's. The 1.13
    server does report one registry with protocol ids -- items.json -- so the same
    tool is pointed at the item registry of the same jar, and its output must equal
    that report exactly, all 785 entries in the same order.

    This replaced an earlier check that compared the dump against a later version's
    registry on the assumption that a registry's order never changes. It does: 1.14
    reordered the sound registry wholesale, and the old check passed a sound table
    in which two ids out of three were wrong. A calibration against a known answer
    for the same jar cannot be fooled that way.
    """
    dumped = read_dump(dump)
    official = json.loads((reports / "items.json").read_text(encoding="utf-8"))
    by_id = {value["protocol_id"]: name for name, value in official.items()}
    truth = [by_id[index] for index in range(max(by_id) + 1)]
    if dumped != truth:
        divergence = next((index for index, name in enumerate(dumped)
                           if index >= len(truth) or name != truth[index]), len(dumped))
        raise SystemExit(
            f"FATAL: the dumper does not reproduce {reports / 'items.json'}: first divergence at "
            f"id {divergence}. It is not reading the registry's own ids, so every table it "
            f"produces would be wrong. Do not use it."
        )
    print(f"  dumper calibrated: {len(truth)} item ids reproduced exactly from {dump.name}")


def write_table(path: Path, values: list[int]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("wb") as handle:
        handle.write(struct.pack("<i", len(values)))
        for value in values:
            handle.write(struct.pack("<i", value))
    mapped = sum(1 for value in values if value >= 0)
    print(f"  {path.relative_to(ROOT)}: {len(values)} entries, {mapped} mapped")


def write_names(path: Path, names: list[str]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text("\n".join(names) + "\n", encoding="utf-8")
    print(f"  {path.relative_to(ROOT)}: {len(names)} names")


def particle_map(source: list[str], target: list[str], renames: dict[str, str]) -> list[int]:
    index = {name: position for position, name in enumerate(target)}
    return [index.get(renames.get(name, name), -1) for name in source]


def invert(renames: dict[str, str]) -> dict[str, str]:
    return {value: key for key, value in renames.items()}


def main() -> None:
    if len(sys.argv) != 5:
        raise SystemExit(__doc__)
    dump = Path(sys.argv[1])
    calibration = Path(sys.argv[2])
    reports393 = Path(sys.argv[3])
    reports765 = Path(sys.argv[4])

    calibrate(calibration, reports393)
    particles393 = read_dump(dump)
    particles765 = registry(reports765, "minecraft:particle_type")
    print(f"1.13 particles: {len(particles393)}; 1.20.4 particles: {len(particles765)}")

    unmapped = [name for name in particles393
                if PARTICLE_RENAMES_393_TO_765.get(name, name) not in set(particles765)]
    unexpected = [name for name in unmapped if name not in PARTICLES_REMOVED_AFTER_393]
    if unexpected:
        raise SystemExit(
            "FATAL: 1.13 particles with no 1.20.4 counterpart that this script does not know "
            f"about: {unexpected}. Add a rename to PARTICLE_RENAMES_393_TO_765 or record the "
            "removal in PARTICLES_REMOVED_AFTER_393; do not let one go unmapped unexplained."
        )

    print("particles:")
    write_table(RES / "particles_393_to_765.bin",
                particle_map(particles393, particles765, PARTICLE_RENAMES_393_TO_765))
    write_table(RES / "particles_765_to_393.bin",
                particle_map(particles765, particles393, invert(PARTICLE_RENAMES_393_TO_765)))
    write_names(RES / "particles_393_names.txt", particles393)
    write_names(RES / "particles_765_names.txt", particles765)
    print(f"1.13 particles with no 1.20.4 counterpart (dropped): {sorted(unmapped)}")


if __name__ == "__main__":
    main()

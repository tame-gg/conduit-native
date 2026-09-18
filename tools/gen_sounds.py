#!/usr/bin/env python3
"""Generate Conduit's 393 <-> 765 sound id tables from OFFICIAL Mojang data.

A sound is sent on the wire as a registry index, and the two registries do not
agree: 1.13 has 662 sound events, 1.20.4 has 1539, and the additions are spread
through the list rather than appended, so passing an index through plays an
unrelated sound. That is why the translator dropped every sound until now.

Input, per side, is Mojang's own registry:

  1.20.4  `registries.json` from the server's own report:
            java -DbundlerMainClass=net.minecraft.data.Main -jar server.jar --reports

  1.13    the 1.13 server's `--reports` predates the registry dump, so its
          registry is read out of its jar instead (see tools/DumpRegistry.java):
            javac -d out tools/DumpRegistry.java
            java -cp out DumpRegistry mc113/server.jar minecraft:ambient.cave sounds_393.txt

Nothing here is copied from another proxy and nothing is guessed from id
arithmetic: every mapping is resolved by name.

**The 1.13 dump is checked, not trusted.** Its ids come from the declaration
order of the fields of the jar's sound-event holder class, which is an inference
about how the registry was filled. So this script verifies it against a third,
independent official registry -- a later version that still contains every 1.13
name. If the 1.13 order is real, those names appear there in the same relative
order. 1.14 contains all 662 in exactly this order. The check is fatal: a dump
that fails it is a wrong table, and a wrong table is worse than no sounds.

Usage:
  python tools/gen_sounds.py <sounds_393.txt> <reports-1.20.4-dir> <reports-1.14-dir>

Writes the binary tables under src/main/resources/gg/tame/conduit/protocol/sound/.
Table format: little-endian i32 length, then that many little-endian i32 entries.
A -1 entry means "no mapping" and callers must fail closed.
"""

from __future__ import annotations

import json
import struct
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
RES = ROOT / "src/main/resources/gg/tame/conduit/protocol/sound"

# Real Mojang renames between 1.13 and 1.20.4, each verified to exist on both
# sides by the `missing` report this script prints. Not similarity guesses.
SOUND_RENAMES_393_TO_765 = {
    # 1.16 renamed the mob and every sound it owns.
    "minecraft:entity.zombie_pigman.ambient": "minecraft:entity.zombified_piglin.ambient",
    "minecraft:entity.zombie_pigman.angry": "minecraft:entity.zombified_piglin.angry",
    "minecraft:entity.zombie_pigman.death": "minecraft:entity.zombified_piglin.death",
    "minecraft:entity.zombie_pigman.hurt": "minecraft:entity.zombified_piglin.hurt",
    # 1.16 split the single Nether track per biome; nether_wastes is the biome
    # the old track played in.
    "minecraft:music.nether": "minecraft:music.nether.nether_wastes",
}

# 1.13 sounds with no 1.20.4 counterpart at all. Listed so that a name turning up
# unmapped is a decision recorded here rather than an unexplained hole; each maps
# to -1 and is dropped, which is what happened to every sound before this table.
SOUNDS_REMOVED_AFTER_393 = {
    # Parrots stopped imitating these: 1.14 removed the first three, and the
    # zombie pigman imitation did not come back under the piglin name.
    "minecraft:entity.parrot.imitate.enderman",
    "minecraft:entity.parrot.imitate.polar_bear",
    "minecraft:entity.parrot.imitate.wolf",
    "minecraft:entity.parrot.imitate.zombie_pigman",
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


def verify(dump: list[str], later: list[str], label: str) -> None:
    """Fails unless `dump`'s order survives in `later`, which decides if the dump is real.

    A registry is filled in one order and ids follow it, so a name added later
    shifts nothing that came before it. If the dump really is that order, then
    reading `later` and keeping only the names the dump has must give the dump's
    own order back. Any disagreement means the dump's ids are not the registry's.
    """
    rank = {name: index for index, name in enumerate(later)}
    shared = [name for name in dump if name in rank]
    missing = [name for name in dump if name not in rank]
    order = [rank[name] for name in shared]
    if order != sorted(order):
        raise SystemExit(
            f"FATAL: the dumped order disagrees with {label}. The dump's ids are not the "
            f"registry's order and the tables would play wrong sounds. Do not use it."
        )
    print(f"  verified against {label}: {len(shared)} of {len(dump)} names present, order intact"
          + (f", {len(missing)} absent" if missing else ""))


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


def sound_map(source: list[str], target: list[str], renames: dict[str, str]) -> list[int]:
    index = {name: position for position, name in enumerate(target)}
    return [index.get(renames.get(name, name), -1) for name in source]


def invert(renames: dict[str, str]) -> dict[str, str]:
    return {value: key for key, value in renames.items()}


def main() -> None:
    if len(sys.argv) != 4:
        raise SystemExit(__doc__)
    dump = Path(sys.argv[1])
    reports765 = Path(sys.argv[2])
    verification = Path(sys.argv[3])

    sounds393 = read_dump(dump)
    sounds765 = registry(reports765, "minecraft:sound_event")

    print(f"1.13 sounds: {len(sounds393)}; 1.20.4 sounds: {len(sounds765)}")
    verify(sounds393, registry(verification, "minecraft:sound_event"), str(verification))

    unmapped = [name for name in sounds393
                if SOUND_RENAMES_393_TO_765.get(name, name) not in set(sounds765)]
    unexpected = [name for name in unmapped if name not in SOUNDS_REMOVED_AFTER_393]
    if unexpected:
        raise SystemExit(
            "FATAL: 1.13 sounds with no 1.20.4 counterpart that this script does not know about: "
            f"{unexpected}. Add a rename to SOUND_RENAMES_393_TO_765 or record the removal in "
            "SOUNDS_REMOVED_AFTER_393; do not let a sound go unmapped unexplained."
        )

    print("sounds:")
    write_table(RES / "sounds_393_to_765.bin",
                sound_map(sounds393, sounds765, SOUND_RENAMES_393_TO_765))
    write_table(RES / "sounds_765_to_393.bin",
                sound_map(sounds765, sounds393, invert(SOUND_RENAMES_393_TO_765)))
    write_names(RES / "sounds_393_names.txt", sounds393)
    write_names(RES / "sounds_765_names.txt", sounds765)
    print(f"1.13 sounds with no 1.20.4 counterpart (dropped): {sorted(unmapped)}")


if __name__ == "__main__":
    main()

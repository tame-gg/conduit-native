#!/usr/bin/env python3
"""Generate Conduit's 393 <-> 765 semantic id tables from OFFICIAL Mojang data.

Input is what the vanilla server jars themselves emit:

    java -cp mc113/server.jar net.minecraft.data.Main --reports
    java -DbundlerMainClass=net.minecraft.data.Main -jar mc1204/server.jar --reports

That gives, per version, the real block-state table (name + property map + the
exact network id) and the real item registry (name -> protocol id). Nothing here
is copied from another proxy and nothing is guessed from id arithmetic: every
mapping is resolved by *name*, and for blocks additionally by *property set*.

Usage:
  python tools/gen_mappings.py <reports-1.13-dir> <reports-1.20.4-dir>

Writes the binary tables under src/main/resources/gg/tame/conduit/protocol/.
Table format: little-endian i32 length, then that many little-endian i32 entries.
A -1 entry means "no mapping" and callers must fail closed.
"""

from __future__ import annotations

import json
import struct
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
RES = ROOT / "src/main/resources/gg/tame/conduit/protocol"

# The only identifier renames between 1.13 and 1.20.4 that affect blocks or
# items, computed by diffing the two registries (see the `missing` report this
# script prints). Each is a real Mojang rename, not a similarity guess.
BLOCK_RENAMES_393_TO_765 = {
    "minecraft:grass": "minecraft:short_grass",
    "minecraft:grass_path": "minecraft:dirt_path",
    "minecraft:sign": "minecraft:oak_sign",
    "minecraft:wall_sign": "minecraft:oak_wall_sign",
}

ITEM_RENAMES_393_TO_765 = {
    "minecraft:grass": "minecraft:short_grass",
    "minecraft:grass_path": "minecraft:dirt_path",
    "minecraft:sign": "minecraft:oak_sign",
    "minecraft:rose_red": "minecraft:red_dye",
    "minecraft:cactus_green": "minecraft:green_dye",
    "minecraft:dandelion_yellow": "minecraft:yellow_dye",
    "minecraft:zombie_pigman_spawn_egg": "minecraft:zombified_piglin_spawn_egg",
}


# Property VALUE domains that changed even though the property name did not.
# 1.16 turned wall connection sides from a boolean into none/low/tall; matching
# on the raw strings would send every connected wall to its default state.
WALL_SIDES = ("north", "south", "east", "west")
WALL_BLOCKS = ("_wall",)   # only walls; fences kept the boolean form


def alias_to_765(block: str, key: str, value: str) -> str:
    """1.13 wall side boolean -> 1.16+ none/low/tall."""
    if not block.endswith(WALL_BLOCKS) or key not in WALL_SIDES:
        return value
    return "low" if value == "true" else "none"


def alias_to_393(block: str, key: str, value: str) -> str:
    """1.16+ wall side none/low/tall -> 1.13 boolean."""
    if not block.endswith(WALL_BLOCKS) or key not in WALL_SIDES:
        return value
    return "false" if value == "none" else "true"


def alias_none(block: str, key: str, value: str) -> str:
    return value


# Blocks that split by property value rather than being renamed outright: 1.19
# moved a filled cauldron into its own block, so 1.13's `cauldron[level=1..3]`
# corresponds to 1.20.4's `water_cauldron[level=1..3]`.
def conditional_rename_393_to_765(block: str, props: dict) -> str | None:
    if block == "minecraft:cauldron" and props.get("level", "0") != "0":
        return "minecraft:water_cauldron"
    return None


def conditional_rename_765_to_393(block: str, props: dict) -> str | None:
    if block == "minecraft:water_cauldron":
        return "minecraft:cauldron"
    return None


def invert(renames: dict[str, str]) -> dict[str, str]:
    return {v: k for k, v in renames.items()}


def write_table(path: Path, values: list[int]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("wb") as handle:
        handle.write(struct.pack("<i", len(values)))
        for value in values:
            handle.write(struct.pack("<i", value))
    mapped = sum(1 for v in values if v >= 0)
    print(f"  {path.relative_to(ROOT)}: {len(values)} entries, {mapped} mapped")


# ---------------------------------------------------------------- block states


def block_states(report: dict):
    """id -> (block, props), block -> [(id, props)], block -> (default id, default props)."""
    by_id: dict[int, tuple[str, dict]] = {}
    by_block: dict[str, list[tuple[int, dict]]] = {}
    default: dict[str, tuple[int, dict]] = {}
    for block, entry in report.items():
        states = entry["states"]
        by_block[block] = []
        for state in states:
            props = state.get("properties", {})
            by_id[state["id"]] = (block, props)
            by_block[block].append((state["id"], props))
            if state.get("default"):
                default[block] = (state["id"], props)
        default.setdefault(block, (states[0]["id"], states[0].get("properties", {})))
    return by_id, by_block, default


def best_state(candidates: list[tuple[int, dict]], want: dict, default_id: int,
               default_props: dict) -> int:
    """Pick the target state agreeing with the source on the most shared properties.

    Shared properties are matched by name AND value, so `facing=north` on a
    stair picks the north stair rather than the block's default. Properties that
    exist on only one side (e.g. `waterlogged`, added per-block over time) simply
    do not contribute to the score instead of disqualifying the candidate.

    Ties are broken toward the target's own default state. That matters for
    properties the source version does not have at all: 1.13 leaves carry no
    `waterlogged`, and without this tie-break every 1.13 leaf block would arrive
    waterlogged simply because that state happens to be enumerated first.
    """
    best_id, best_key = default_id, None
    for state_id, props in candidates:
        shared = set(props) & set(want)
        score = sum(1 if props[key] == want[key] else -1 for key in shared)
        # Secondary: properties the SOURCE version does not have at all should
        # take the target's default value (an unwaterlogged leaf, a non-lit
        # block), not whichever variant happens to be enumerated first.
        extra = sum(1 for key in set(props) - set(want) if props[key] == default_props.get(key))
        key = (score, extra)
        if best_key is None or key > best_key:
            best_id, best_key = state_id, key
    return best_id


def block_map(src_by_id, dst_by_block, dst_default, renames, conditional, alias) -> list[int]:
    size = max(src_by_id) + 1
    table = [-1] * size
    for state_id, (block, raw_props) in src_by_id.items():
        target = conditional(block, raw_props) or renames.get(block, block)
        # Normalise the source's property VALUES into the target's domain before
        # matching, so a 1.13 wall with north=true finds the 1.16+ north=low.
        props = {key: alias(target, key, value) for key, value in raw_props.items()}
        candidates = dst_by_block.get(target)
        if not candidates:
            continue
        default_id, default_props = dst_default[target]
        table[state_id] = best_state(candidates, props, default_id, default_props) if props else default_id
    return table


# ---------------------------------------------------------------------- items


def write_names(path: Path, registry: dict[str, int]) -> None:
    """Index -> identifier, one per line. This is what makes the item layer
    semantic at runtime: a stack is carried as `minecraft:diamond_sword`, not as
    a number that happens to mean something different on the other side."""
    path.parent.mkdir(parents=True, exist_ok=True)
    names = [""] * (max(registry.values()) + 1)
    for name, protocol_id in registry.items():
        names[protocol_id] = name
    path.write_text("\n".join(names) + "\n", encoding="utf-8")
    print(f"  {path.relative_to(ROOT)}: {len(names)} names")


def item_map(src: dict[str, int], dst: dict[str, int], renames) -> list[int]:
    size = max(src.values()) + 1
    table = [-1] * size
    for name, src_id in src.items():
        target = renames.get(name, name)
        if target in dst:
            table[src_id] = dst[target]
    return table


def main() -> None:
    if len(sys.argv) != 3:
        raise SystemExit(__doc__)
    r393 = Path(sys.argv[1])
    r765 = Path(sys.argv[2])

    b393 = json.loads((r393 / "blocks.json").read_text(encoding="utf-8"))
    b765 = json.loads((r765 / "blocks.json").read_text(encoding="utf-8"))
    i393 = {k: v["protocol_id"] for k, v in json.loads((r393 / "items.json").read_text(encoding="utf-8")).items()}
    reg765 = json.loads((r765 / "registries.json").read_text(encoding="utf-8"))
    i765 = {k: v["protocol_id"] for k, v in reg765["minecraft:item"]["entries"].items()}

    by_id_393, by_block_393, default_393 = block_states(b393)
    by_id_765, by_block_765, default_765 = block_states(b765)

    print("block states:")
    write_table(RES / "chunk/blockstates_393_to_765.bin",
                block_map(by_id_393, by_block_765, default_765, BLOCK_RENAMES_393_TO_765,
                          conditional_rename_393_to_765, alias_to_765))
    write_table(RES / "chunk/blockstates_765_to_393.bin",
                block_map(by_id_765, by_block_393, default_393, invert(BLOCK_RENAMES_393_TO_765),
                          conditional_rename_765_to_393, alias_to_393))

    print("items:")
    write_table(RES / "item/items_393_to_765.bin", item_map(i393, i765, ITEM_RENAMES_393_TO_765))
    write_table(RES / "item/items_765_to_393.bin", item_map(i765, i393, invert(ITEM_RENAMES_393_TO_765)))
    write_names(RES / "item/items_393_names.txt", i393)
    write_names(RES / "item/items_765_names.txt", i765)
    write_names(RES / "entity/entitytypes_765_names.txt",
                {k: v["protocol_id"] for k, v in reg765["minecraft:entity_type"]["entries"].items()})

    missing_blocks = [b for b in b393 if BLOCK_RENAMES_393_TO_765.get(b, b) not in b765]
    missing_items = [i for i in i393 if ITEM_RENAMES_393_TO_765.get(i, i) not in i765]
    print(f"unmapped 1.13 blocks: {missing_blocks}")
    print(f"unmapped 1.13 items: {missing_items}")


if __name__ == "__main__":
    main()

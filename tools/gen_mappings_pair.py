#!/usr/bin/env python3
"""Generate Conduit's semantic id tables for a pre-1.16 protocol pair.

Input is what the vanilla server jars emit about themselves:

    java -cp minecraft-server-<v>.jar net.minecraft.data.Main --reports

1.13.x emits `blocks.json` + `items.json`; 1.14+ emits `blocks.json` +
`registries.json`. Both shapes are handled. Block and item resolution is reused
verbatim from `gen_mappings.py`, so these tables are produced by exactly the
algorithm that produced the verified 393<->765 tables: match by identifier,
then by property name AND value, ties broken toward the target's own default
state. Nothing here is id arithmetic.

Why dedicated tables are required rather than numeric passthrough:

    393 -> 404   only 1126 of 8582 block states keep their meaning
                 (1.13.1 inserted `tnt[unstable]`); 347 item ids shift
                 (1.13.1 inserted the dead-coral items)
    404 -> 477   only  748 of 8599 block states keep their meaning
                 (1.14 expanded the note-block instruments); 678 item ids shift

Usage:
  python tools/gen_mappings_pair.py <low-proto> <reports-low> <high-proto> <reports-high>

Example:
  python tools/gen_mappings_pair.py 404 /tmp/gen1132/generated/reports \\
                                    477 /tmp/gen114/generated/reports
"""

from __future__ import annotations

import json
import sys
from pathlib import Path

import gen_mappings as base

RES = base.RES

# Real Mojang identifier renames, keyed by (low, high) protocol pair. Each entry
# was confirmed by diffing the two registries: every low-version name absent
# from the high version appears here and nowhere else. An empty dict means the
# pair genuinely renamed nothing.
BLOCK_RENAMES = {
    (393, 404): {},
    (404, 477): {
        "minecraft:sign": "minecraft:oak_sign",
        "minecraft:wall_sign": "minecraft:oak_wall_sign",
    },
}

ITEM_RENAMES = {
    (393, 404): {},
    (404, 477): {
        "minecraft:sign": "minecraft:oak_sign",
        "minecraft:rose_red": "minecraft:red_dye",
        "minecraft:cactus_green": "minecraft:green_dye",
        "minecraft:dandelion_yellow": "minecraft:yellow_dye",
    },
}


def no_conditional(block: str, props: dict):
    """No pre-1.16 pair splits a block by property value.

    The cauldron split is 1.19 and the wall none/low/tall reform is 1.16, so for
    these pairs the identity is the honest answer - inventing a transformation
    that did not happen would be as wrong as omitting one that did.
    """
    return None


def read_registries(reports: Path):
    """(items, entity_types) as identifier -> protocol id, for either report shape."""
    registries = reports / "registries.json"
    if registries.exists():
        data = json.loads(registries.read_text(encoding="utf-8"))
        items = {k: v["protocol_id"] for k, v in data["minecraft:item"]["entries"].items()}
        entities = {k: v["protocol_id"]
                    for k, v in data["minecraft:entity_type"]["entries"].items()}
        return items, entities
    items = {k: v["protocol_id"]
             for k, v in json.loads((reports / "items.json").read_text(encoding="utf-8")).items()}
    return items, None


def main() -> None:
    if len(sys.argv) != 5:
        raise SystemExit(__doc__)
    low, low_dir, high, high_dir = (int(sys.argv[1]), Path(sys.argv[2]),
                                   int(sys.argv[3]), Path(sys.argv[4]))
    pair = (low, high)
    if pair not in BLOCK_RENAMES:
        raise SystemExit(f"no rename table recorded for pair {pair}; add one after "
                         f"diffing the registries rather than assuming there are none")

    block_renames = BLOCK_RENAMES[pair]
    item_renames = ITEM_RENAMES[pair]

    blocks_low = json.loads((low_dir / "blocks.json").read_text(encoding="utf-8"))
    blocks_high = json.loads((high_dir / "blocks.json").read_text(encoding="utf-8"))
    items_low, entities_low = read_registries(low_dir)
    items_high, entities_high = read_registries(high_dir)

    by_id_low, _, default_low_unused = base.block_states(blocks_low)
    by_id_high, by_block_high, default_high = base.block_states(blocks_high)
    _, by_block_low, default_low = base.block_states(blocks_low)

    print(f"block states {low} <-> {high}:")
    base.write_table(RES / f"chunk/blockstates_{low}_to_{high}.bin",
                     base.block_map(by_id_low, by_block_high, default_high,
                                    block_renames, no_conditional, base.alias_none))
    base.write_table(RES / f"chunk/blockstates_{high}_to_{low}.bin",
                     base.block_map(by_id_high, by_block_low, default_low,
                                    base.invert(block_renames), no_conditional, base.alias_none))

    print(f"items {low} <-> {high}:")
    base.write_table(RES / f"item/items_{low}_to_{high}.bin",
                     base.item_map(items_low, items_high, item_renames))
    base.write_table(RES / f"item/items_{high}_to_{low}.bin",
                     base.item_map(items_high, items_low, base.invert(item_renames)))
    base.write_names(RES / f"item/items_{low}_names.txt", items_low)
    base.write_names(RES / f"item/items_{high}_names.txt", items_high)

    if entities_high:
        print("entities:")
        base.write_names(RES / f"entity/entitytypes_{high}_names.txt", entities_high)
    if entities_low:
        base.write_names(RES / f"entity/entitytypes_{low}_names.txt", entities_low)

    missing_blocks = [b for b in blocks_low if block_renames.get(b, b) not in blocks_high]
    missing_items = [i for i in items_low if item_renames.get(i, i) not in items_high]
    print(f"unmapped {low} blocks: {missing_blocks}")
    print(f"unmapped {low} items: {missing_items}")


if __name__ == "__main__":
    main()

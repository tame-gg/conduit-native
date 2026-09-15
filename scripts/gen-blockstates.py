#!/usr/bin/env python3
"""Generate 393↔765 block-state ID maps from PrismarineJS minecraft-data."""
import json
import os
import struct
from pathlib import Path


def load(path: str):
    blocks = json.load(open(path, encoding="utf-8"))
    by_name = {}
    max_state = 0
    for block in blocks:
        name = block["name"]
        mn, mx = block["minStateId"], block["maxStateId"]
        by_name[name] = (mn, mx, block.get("defaultState", mn))
        max_state = max(max_state, mx)
    return by_name, max_state


def write_map(path: Path, arr: list[int]) -> None:
    data = struct.pack("<" + "i" * (len(arr) + 1), len(arr), *arr)
    path.write_bytes(data)
    print(path, "entries", len(arr), "bytes", len(data))


def main() -> None:
    temp = os.environ.get("TEMP", "/tmp")
    src113, max113 = load(os.path.join(temp, "blocks_113.json"))
    src765, max765 = load(os.path.join(temp, "blocks_1204.json"))
    stone113 = src113["stone"][2]
    stone765 = src765["stone"][2]

    to393 = [stone113] * (max765 + 1)
    to765 = [stone765] * (max113 + 1)
    to393[0] = 0
    to765[0] = 0

    for name, (mn765, mx765, _def765) in src765.items():
        if name not in src113:
            continue
        mn113, mx113, _def113 = src113[name]
        span765 = mx765 - mn765
        span113 = mx113 - mn113
        for i in range(span765 + 1):
            if span113 == 0:
                mapped = mn113
            else:
                mapped = mn113 + min(i, span113)
            to393[mn765 + i] = mapped

    for name, (mn113, mx113, _def113) in src113.items():
        if name not in src765:
            continue
        mn765, mx765, _def765 = src765[name]
        span113 = mx113 - mn113
        span765 = mx765 - mn765
        for i in range(span113 + 1):
            if span765 == 0:
                mapped = mn765
            else:
                mapped = mn765 + min(i, span765)
            to765[mn113 + i] = mapped

    out = Path("src/main/resources/gg/tame/conduit/protocol/chunk")
    out.mkdir(parents=True, exist_ok=True)
    write_map(out / "blockstates_765_to_393.bin", to393)
    write_map(out / "blockstates_393_to_765.bin", to765)
    print("air", to393[0], to765[0])
    print("stone765->", to393[stone765], "stone113->", to765[stone113])


if __name__ == "__main__":
    main()

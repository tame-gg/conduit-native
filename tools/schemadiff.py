#!/usr/bin/env python3
"""Compare a packet's field layout between two Minecraft releases.

Identical packet names do not imply identical schemas, so every translation
decision in Conduit has to be made against the real field list rather than the
name. This prints both layouts side by side.

  python tools/schemadiff.py 1.13 1.20.4 play toClient rel_entity_move
  python tools/schemadiff.py 1.13 1.20.4 play toClient --all-common
"""

from __future__ import annotations

import sys
import pathlib

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
import packetids


def section(release, state, direction):
    data = packetids.protocol_json(release)
    if data is None:
        raise SystemExit(f"no published protocol.json for {release}")
    return data[state][direction]


def names(release, state, direction):
    mappings = section(release, state, direction)["types"]["packet"][1][0]["type"][1]["mappings"]
    return {name: int(code, 16) for code, name in mappings.items()}


def fields(release, state, direction, packet):
    """Flatten a packet's declared field list to (name, type) pairs."""
    types = packetids.protocol_json(release)[state][direction]["types"]
    container = types.get(f"packet_{packet}")
    if container is None:
        return None
    out = []

    def walk(node, prefix=""):
        if isinstance(node, list) and node and node[0] == "container":
            for entry in node[1]:
                walk(entry.get("type"), prefix + str(entry.get("name", "?")) + ".")
            return
        if isinstance(node, list) and node and node[0] == "switch":
            out.append((prefix.rstrip("."), "switch"))
            return
        if isinstance(node, dict):
            walk(node.get("type"), prefix)
            return
        label = node[0] if isinstance(node, list) else node
        out.append((prefix.rstrip("."), str(label)))

    walk(container)
    return out


def show(a, b, state, direction, packet):
    fa = fields(a, state, direction, packet)
    fb = fields(b, state, direction, packet)
    ia, ib = names(a, state, direction).get(packet), names(b, state, direction).get(packet)
    print(f"\n=== {packet} ===")
    print(f"  {a}: id={'0x%02X' % ia if ia is not None else 'ABSENT'}  "
          f"{b}: id={'0x%02X' % ib if ib is not None else 'ABSENT'}")
    if fa is None or fb is None:
        print(f"  {a}: {'missing' if fa is None else len(fa)} fields, "
              f"{b}: {'missing' if fb is None else len(fb)} fields")
        return
    width = max((len(f"{n}:{t}") for n, t in fa), default=10) + 2
    same = [f"{n}:{t}" for n, t in fa] == [f"{n}:{t}" for n, t in fb]
    print(f"  layout {'IDENTICAL' if same else 'DIFFERS'}")
    for index in range(max(len(fa), len(fb))):
        left = f"{fa[index][0]}:{fa[index][1]}" if index < len(fa) else ""
        right = f"{fb[index][0]}:{fb[index][1]}" if index < len(fb) else ""
        mark = " " if left == right else "*"
        print(f"  {mark} {left:<{width}} | {right}")


def main() -> None:
    a, b, state, direction = sys.argv[1:5]
    rest = sys.argv[5:]
    if rest and rest[0] == "--all-common":
        common = sorted(set(names(a, state, direction)) & set(names(b, state, direction)))
        identical, differs = [], []
        for packet in common:
            fa, fb = fields(a, state, direction, packet), fields(b, state, direction, packet)
            if fa is None or fb is None:
                continue
            (identical if fa == fb else differs).append(packet)
        print(f"common packets: {len(common)}")
        print(f"\nIDENTICAL layout ({len(identical)}):\n  " + "\n  ".join(identical))
        print(f"\nDIFFERING layout ({len(differs)}):\n  " + "\n  ".join(differs))
        return
    for packet in rest:
        show(a, b, state, direction, packet)


if __name__ == "__main__":
    main()

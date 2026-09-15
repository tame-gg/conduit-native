#!/usr/bin/env python3
"""Export Conduit's release->protocol catalog to tools/protocols.json.

The authoritative list lives in ProtocolVersion.RELEASES (Java). This exporter
reads those `rel("<name>", <protocol>, <modern>)` entries so the artifact
tooling and the Java proxy can never disagree about which protocol number
belongs to which release.
"""

from __future__ import annotations

import json
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SOURCE = ROOT / "src/main/java/gg/tame/conduit/protocol/ProtocolVersion.java"
OUT = Path(__file__).resolve().parent / "protocols.json"

PATTERN = re.compile(r'rel\("([^"]+)",\s*(\d+),\s*(true|false)\)')


def main() -> None:
    text = SOURCE.read_text(encoding="utf-8")
    entries = [
        {"release": name, "protocol": int(protocol), "modern_program": modern == "true"}
        for name, protocol, modern in PATTERN.findall(text)
    ]
    if not entries:
        raise SystemExit(f"no rel(...) entries found in {SOURCE}")
    OUT.write_text(json.dumps(entries, indent=2) + "\n", encoding="utf-8")
    modern = sum(1 for e in entries if e["modern_program"])
    protocols = len({e["protocol"] for e in entries if e["modern_program"]})
    print(f"wrote {OUT}: {len(entries)} releases, {modern} in modern program, "
          f"{protocols} distinct modern protocol numbers")


if __name__ == "__main__":
    main()

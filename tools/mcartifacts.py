#!/usr/bin/env python3
"""Acquire and record official Minecraft client/server artifacts for the Conduit
multi-version compatibility program (1.13 -> 26.2).

Everything here is driven by Mojang's official version manifest. No protocol
number, release name, hash, or Java requirement is invented: releases and
download metadata come from the manifest, and protocol numbers come from
conduit's own catalog export (tools/protocols.json), which is generated from
ProtocolVersion.RELEASES.

Usage:
  python tools/mcartifacts.py plan                 # show what would be fetched
  python tools/mcartifacts.py fetch [--kind server|client|both] [--only 1.13,1.14]
  python tools/mcartifacts.py verify               # re-hash what is on disk
  python tools/mcartifacts.py report               # write artifacts/ARTIFACTS.md
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import sys
import urllib.request
from pathlib import Path

MANIFEST_URL = "https://launchermeta.mojang.com/mc/game/version_manifest_v2.json"
# Requests name Conduit's development tools and nothing else: no user, machine or path.
USER_AGENT = {"User-Agent": "Conduit-Development/0.9.0-SNAPSHOT"}

ROOT = Path(__file__).resolve().parent.parent
ARTIFACTS = ROOT / "artifacts"
BIN = ARTIFACTS / "bin"
CATALOG = ARTIFACTS / "catalog.json"
PROTOCOLS = Path(__file__).resolve().parent / "protocols.json"

# One representative release per distinct protocol number across the program
# range. Where several releases share a protocol number (e.g. 1.20.3/1.20.4 ->
# 765) a single representative is enough to exercise the wire format; the
# catalog records the alias set so the choice stays visible.
REPRESENTATIVES = [
    "1.13", "1.13.1", "1.13.2",
    "1.14", "1.14.1", "1.14.2", "1.14.3", "1.14.4",
    "1.15", "1.15.1", "1.15.2",
    "1.16", "1.16.1", "1.16.2", "1.16.3", "1.16.5",
    "1.17", "1.17.1",
    "1.18", "1.18.2",
    "1.19", "1.19.2", "1.19.3", "1.19.4",
    "1.20.1", "1.20.2", "1.20.4", "1.20.6",
    "1.21", "1.21.3", "1.21.4", "1.21.5", "1.21.6", "1.21.8", "1.21.10", "1.21.11",
    "26.1", "26.2",
]

# Releases Conduit actively drives in end-to-end tests. Fetched first so useful
# testing can start before the whole range is mirrored.
PRIORITY = ["1.13", "1.20.4", "26.2", "1.20.1", "1.20.6"]


def http_json(url: str):
    with urllib.request.urlopen(urllib.request.Request(url, headers=USER_AGENT), timeout=120) as response:
        return json.load(response)


def load_manifest(cache: Path) -> dict:
    if cache.exists():
        return json.loads(cache.read_text(encoding="utf-8"))
    data = http_json(MANIFEST_URL)
    cache.parent.mkdir(parents=True, exist_ok=True)
    cache.write_text(json.dumps(data), encoding="utf-8")
    return data


def load_protocols() -> dict[str, int]:
    if not PROTOCOLS.exists():
        sys.exit(f"missing {PROTOCOLS}; run the Conduit catalog export first")
    return {entry["release"]: entry["protocol"] for entry in json.loads(PROTOCOLS.read_text(encoding="utf-8"))}


def sha256_of(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1 << 20), b""):
            digest.update(chunk)
    return digest.hexdigest()


def sha1_of(path: Path) -> str:
    digest = hashlib.sha1()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1 << 20), b""):
            digest.update(chunk)
    return digest.hexdigest()


def read_catalog() -> dict:
    if CATALOG.exists():
        return json.loads(CATALOG.read_text(encoding="utf-8"))
    return {"artifacts": {}}


def write_catalog(catalog: dict) -> None:
    ARTIFACTS.mkdir(parents=True, exist_ok=True)
    CATALOG.write_text(json.dumps(catalog, indent=2, sort_keys=True) + "\n", encoding="utf-8")


def version_index(manifest: dict) -> dict[str, dict]:
    return {version["id"]: version for version in manifest["versions"]}


def targets(only: str | None) -> list[str]:
    if not only:
        ordered = [r for r in PRIORITY if r in REPRESENTATIVES]
        ordered += [r for r in REPRESENTATIVES if r not in ordered]
        return ordered
    return [name.strip() for name in only.split(",") if name.strip()]


def describe(release: str, kind: str, meta: dict, protocol: int) -> dict:
    downloads = meta.get("downloads", {})
    entry = downloads.get(kind)
    java_version = meta.get("javaVersion", {}).get("majorVersion")
    return {
        "release": release,
        "protocol": protocol,
        "kind": kind,
        "url": entry.get("url") if entry else None,
        "mojang_sha1": entry.get("sha1") if entry else None,
        "size": entry.get("size") if entry else None,
        # Pre-1.17 version JSONs carry no javaVersion block. Mojang's own
        # launcher defaults those to Java 8, which is what the jars require.
        "java_major": java_version if java_version is not None else 8,
        "java_declared_by_mojang": java_version is not None,
        "availability": "available" if entry else "not-published-by-mojang",
        "sha256": None,
        "validation": "not-downloaded",
        "path": None,
    }


def cmd_plan(args) -> None:
    scratch = ARTIFACTS / "_manifest.json"
    manifest = load_manifest(scratch)
    index = version_index(manifest)
    protocols = load_protocols()
    print(f"{'release':<10} {'proto':>5}  {'java':>4}  client  server")
    for release in targets(args.only):
        if release not in index:
            print(f"{release:<10} {'?':>5}  MISSING FROM MOJANG MANIFEST")
            continue
        meta = http_json(index[release]["url"])
        downloads = meta.get("downloads", {})
        java_major = meta.get("javaVersion", {}).get("majorVersion", 8)
        print(f"{release:<10} {protocols.get(release, -1):>5}  {java_major:>4}  "
              f"{'yes' if 'client' in downloads else 'NO':<6}  {'yes' if 'server' in downloads else 'NO'}")


def cmd_fetch(args) -> None:
    manifest = load_manifest(ARTIFACTS / "_manifest.json")
    index = version_index(manifest)
    protocols = load_protocols()
    catalog = read_catalog()
    kinds = ["client", "server"] if args.kind == "both" else [args.kind]
    BIN.mkdir(parents=True, exist_ok=True)

    for release in targets(args.only):
        if release not in index:
            print(f"[skip] {release}: not in Mojang manifest")
            continue
        protocol = protocols.get(release)
        if protocol is None:
            print(f"[skip] {release}: not in Conduit protocol catalog")
            continue
        meta = http_json(index[release]["url"])
        for kind in kinds:
            key = f"{release}/{kind}"
            record = describe(release, kind, meta, protocol)
            if record["url"] is None:
                print(f"[none] {key}: {record['availability']}")
                catalog["artifacts"][key] = record
                continue
            destination = BIN / f"minecraft-{kind}-{release}.jar"
            record["path"] = str(destination.relative_to(ROOT)).replace("\\", "/")
            existing = catalog["artifacts"].get(key)
            if destination.exists() and existing and existing.get("validation") == "sha1-verified":
                print(f"[have] {key}")
                catalog["artifacts"][key] = {**record, **{
                    "sha256": existing["sha256"], "validation": existing["validation"]}}
                continue
            print(f"[get ] {key} ({record['size'] / 1e6:.1f} MB)")
            urllib.request.urlretrieve(record["url"], destination)
            actual_sha1 = sha1_of(destination)
            if actual_sha1 != record["mojang_sha1"]:
                record["validation"] = "sha1-MISMATCH"
                print(f"[FAIL] {key}: sha1 {actual_sha1} != manifest {record['mojang_sha1']}")
            else:
                record["validation"] = "sha1-verified"
            record["sha256"] = sha256_of(destination)
            catalog["artifacts"][key] = record
            write_catalog(catalog)
    write_catalog(catalog)
    print(f"catalog -> {CATALOG}")


def cmd_verify(args) -> None:
    catalog = read_catalog()
    bad = 0
    for key, record in sorted(catalog["artifacts"].items()):
        if not record.get("path"):
            continue
        path = ROOT / record["path"]
        if not path.exists():
            print(f"[gone] {key}")
            record["validation"] = "missing-on-disk"
            bad += 1
            continue
        digest = sha256_of(path)
        if digest != record.get("sha256"):
            print(f"[FAIL] {key}: sha256 drift")
            record["validation"] = "sha256-MISMATCH"
            bad += 1
        else:
            print(f"[ok  ] {key}")
    write_catalog(catalog)
    sys.exit(1 if bad else 0)


def cmd_report(args) -> None:
    catalog = read_catalog()
    rows = sorted(catalog["artifacts"].values(), key=lambda r: (r["protocol"], r["kind"]))
    lines = [
        "# Minecraft test artifacts",
        "",
        "Generated by `tools/mcartifacts.py report`. Every row comes from Mojang's",
        "official version manifest; SHA-1 is Mojang's published hash (verified on",
        "download) and SHA-256 is computed locally. Jars live under `artifacts/bin/`",
        "and are excluded from Git.",
        "",
        "| Release | Protocol | Kind | Java | Availability | Validation | SHA-256 |",
        "| --- | ---: | --- | ---: | --- | --- | --- |",
    ]
    for row in rows:
        digest = (row.get("sha256") or "")[:16]
        java = f"{row['java_major']}" + ("" if row["java_declared_by_mojang"] else " (impl.)")
        lines.append(
            f"| {row['release']} | {row['protocol']} | {row['kind']} | {java} | "
            f"{row['availability']} | {row['validation']} | `{digest}` |")
    lines.append("")
    (ARTIFACTS / "ARTIFACTS.md").write_text("\n".join(lines), encoding="utf-8")
    print(f"wrote {ARTIFACTS / 'ARTIFACTS.md'} ({len(rows)} rows)")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="command", required=True)

    plan = sub.add_parser("plan")
    plan.add_argument("--only")
    plan.set_defaults(func=cmd_plan)

    fetch = sub.add_parser("fetch")
    fetch.add_argument("--kind", choices=["client", "server", "both"], default="both")
    fetch.add_argument("--only")
    fetch.set_defaults(func=cmd_fetch)

    sub.add_parser("verify").set_defaults(func=cmd_verify, only=None)
    sub.add_parser("report").set_defaults(func=cmd_report, only=None)

    args = parser.parse_args()
    args.func(args)


if __name__ == "__main__":
    main()

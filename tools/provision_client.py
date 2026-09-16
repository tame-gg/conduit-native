#!/usr/bin/env python3
"""Provision a real vanilla Minecraft client for interoperability validation.

Downloads the version manifest, the libraries and the asset objects Mojang
publishes for a given version, extracts the native libraries, and writes a
classpath file. The client jar itself is taken from `artifacts/bin` so the
binary under test is the same artifact the repository already pins.

Usage:
  python tools/provision_client.py 1.14 <target-dir>
"""

from __future__ import annotations

import json
import os
import subprocess
import sys
import urllib.request
import zipfile
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

MANIFEST = "https://launchermeta.mojang.com/mc/game/version_manifest_v2.json"
RESOURCES = "https://resources.download.minecraft.net"
ROOT = Path(__file__).resolve().parent.parent


def fetch(url: str) -> bytes:
    with urllib.request.urlopen(url, timeout=120) as response:
        return response.read()


def fetch_to(url: str, path: Path) -> None:
    if path.exists() and path.stat().st_size > 0:
        return
    path.parent.mkdir(parents=True, exist_ok=True)
    data = fetch(url)
    path.write_bytes(data)


def rules_allow(entry: dict) -> bool:
    """Mojang gates libraries per OS. Only Windows entries are wanted here."""
    rules = entry.get("rules")
    if not rules:
        return True
    allowed = False
    for rule in rules:
        os_rule = rule.get("os", {})
        name = os_rule.get("name")
        matches = name is None or name == "windows"
        if matches:
            allowed = rule["action"] == "allow"
    return allowed


def main() -> None:
    if len(sys.argv) != 3:
        raise SystemExit(__doc__)
    version, target = sys.argv[1], Path(sys.argv[2])
    target.mkdir(parents=True, exist_ok=True)

    manifest = json.loads(fetch(MANIFEST))
    entry = next((v for v in manifest["versions"] if v["id"] == version), None)
    if entry is None:
        raise SystemExit(f"version {version} not in the manifest")
    meta = json.loads(fetch(entry["url"]))
    (target / "version.json").write_text(json.dumps(meta, indent=2), encoding="utf-8")

    # The client jar comes from the repository's pinned artifacts, not the CDN.
    pinned = ROOT / "artifacts/bin" / f"minecraft-client-{version}.jar"
    client_jar = target / f"{version}-client.jar"
    if not client_jar.exists():
        if not pinned.exists():
            raise SystemExit(f"no pinned client jar at {pinned}")
        client_jar.write_bytes(pinned.read_bytes())

    libraries = target / "libraries"
    natives = target / "natives"
    natives.mkdir(parents=True, exist_ok=True)
    classpath = [str(client_jar)]
    native_jars: list[Path] = []

    downloads: list[tuple[str, Path]] = []
    for library in meta["libraries"]:
        if not rules_allow(library):
            continue
        artifact = library.get("downloads", {}).get("artifact")
        if artifact:
            path = libraries / artifact["path"]
            downloads.append((artifact["url"], path))
            classpath.append(str(path))
        classifiers = library.get("downloads", {}).get("classifiers", {})
        native_key = library.get("natives", {}).get("windows", "").replace("${arch}", "64")
        if native_key and native_key in classifiers:
            native = classifiers[native_key]
            path = libraries / native["path"]
            downloads.append((native["url"], path))
            native_jars.append(path)

    print(f"libraries: {len(downloads)}")
    with ThreadPoolExecutor(max_workers=16) as pool:
        list(pool.map(lambda job: fetch_to(job[0], job[1]), downloads))

    for jar in native_jars:
        with zipfile.ZipFile(jar) as archive:
            for member in archive.namelist():
                if member.endswith((".dll", ".so", ".dylib")) and "META-INF" not in member:
                    archive.extract(member, natives)

    # Assets: an index plus the hashed objects it names.
    index = meta["assetIndex"]
    assets = target / "assets"
    index_path = assets / "indexes" / f"{index['id']}.json"
    fetch_to(index["url"], index_path)
    objects = json.loads(index_path.read_text(encoding="utf-8"))["objects"]
    jobs = []
    for obj in objects.values():
        digest = obj["hash"]
        jobs.append((f"{RESOURCES}/{digest[:2]}/{digest}",
                     assets / "objects" / digest[:2] / digest))
    print(f"asset objects: {len(jobs)}")
    with ThreadPoolExecutor(max_workers=32) as pool:
        list(pool.map(lambda job: fetch_to(job[0], job[1]), jobs))

    (target / "classpath.txt").write_text(";".join(classpath), encoding="utf-8")
    (target / "game").mkdir(parents=True, exist_ok=True)
    print(f"main class: {meta['mainClass']}")
    print(f"asset index: {index['id']}")
    print(f"provisioned {version} at {target}")


if __name__ == "__main__":
    main()

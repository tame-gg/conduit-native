#!/usr/bin/env python3
"""Build per-protocol packet-id tables for Conduit from published packet data.

Conduit addresses packets by semantic PacketKind, never by numeric id. Each
protocol version owns its own kind -> id mapping, and this tool produces those
mappings for the whole 1.13-26.2 range from PrismarineJS minecraft-data, the
same public data the hand-authored tables in ProtocolDefinition already cite.

Three jobs:

  check    Cross-check Conduit's hand-authored tables (393/763/765/766) against
           the published data. Disagreements are real bugs in one or the other
           and must be resolved by hand, not by overwriting.
  deltas   Emit, for a target protocol and a chosen base protocol, only the
           mappings that differ -- the input to a ProtocolRevision.
  declare  Emit a whole table for one release, for a version too far from any
           registered base to derive from.
  coverage Report which PacketKinds are unmapped for which versions.

This tool never writes Java. It prints data for a human to review and paste,
because a wrong packet id is a silently corrupted stream, not a test failure.
"""

from __future__ import annotations

import argparse
import json
import re
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
CACHE = ROOT / "artifacts" / "_mcdata"
BASE_URL = "https://raw.githubusercontent.com/PrismarineJS/minecraft-data/master/data/"
PATHS_URL = BASE_URL + "dataPaths.json"

DEFINITION = ROOT / "src/main/java/gg/tame/conduit/protocol/ProtocolDefinition.java"

# PacketKind -> (state, direction, [candidate minecraft-data names]).
# Candidates are tried in order; minecraft-data renamed a number of packets over
# the range, so a kind legitimately maps to different names in different
# versions. A kind with no matching candidate in a version is reported as
# absent rather than guessed at.
STATE_HANDSHAKE, STATE_STATUS, STATE_LOGIN, STATE_CONFIG, STATE_PLAY = (
    "handshaking", "status", "login", "configuration", "play")
TO_CLIENT, TO_SERVER = "toClient", "toServer"

KINDS: dict[tuple[str, str, str], list[str]] = {
    (STATE_HANDSHAKE, TO_SERVER, "HANDSHAKE"): ["set_protocol"],

    (STATE_STATUS, TO_SERVER, "STATUS_REQUEST"): ["ping_start"],
    (STATE_STATUS, TO_SERVER, "STATUS_PING"): ["ping"],
    (STATE_STATUS, TO_CLIENT, "STATUS_REQUEST"): ["server_info"],
    (STATE_STATUS, TO_CLIENT, "STATUS_PING"): ["ping"],

    (STATE_LOGIN, TO_SERVER, "LOGIN_START"): ["login_start"],
    (STATE_LOGIN, TO_SERVER, "LOGIN_ENCRYPTION_RESPONSE"): ["encryption_begin"],
    (STATE_LOGIN, TO_SERVER, "LOGIN_PLUGIN_RESPONSE"): ["login_plugin_response"],
    (STATE_LOGIN, TO_SERVER, "LOGIN_ACKNOWLEDGED"): ["login_acknowledged"],
    (STATE_LOGIN, TO_CLIENT, "LOGIN_DISCONNECT"): ["disconnect"],
    (STATE_LOGIN, TO_CLIENT, "LOGIN_ENCRYPTION_REQUEST"): ["encryption_begin"],
    (STATE_LOGIN, TO_CLIENT, "LOGIN_SUCCESS"): ["success"],
    (STATE_LOGIN, TO_CLIENT, "LOGIN_SET_COMPRESSION"): ["compress"],
    (STATE_LOGIN, TO_CLIENT, "LOGIN_PLUGIN_REQUEST"): ["login_plugin_request"],

    (STATE_CONFIG, TO_SERVER, "CONFIGURATION_CLIENT_INFORMATION"): ["settings"],
    (STATE_CONFIG, TO_SERVER, "CONFIGURATION_PLUGIN_MESSAGE"): ["custom_payload"],
    (STATE_CONFIG, TO_SERVER, "CONFIGURATION_FINISH"): ["finish_configuration"],
    (STATE_CONFIG, TO_SERVER, "CONFIGURATION_KEEP_ALIVE"): ["keep_alive"],
    (STATE_CONFIG, TO_SERVER, "CONFIGURATION_KNOWN_PACKS"): ["select_known_packs"],
    (STATE_CONFIG, TO_CLIENT, "CONFIGURATION_DISCONNECT"): ["disconnect"],
    (STATE_CONFIG, TO_CLIENT, "CONFIGURATION_REGISTRY"): ["registry_data"],
    (STATE_CONFIG, TO_CLIENT, "CONFIGURATION_RESET_CHAT"): ["reset_chat"],

    (STATE_PLAY, TO_CLIENT, "PLAY_LOGIN"): ["login"],
    (STATE_PLAY, TO_CLIENT, "PLAY_PLUGIN_MESSAGE"): ["custom_payload"],
    (STATE_PLAY, TO_CLIENT, "PLAY_START_CONFIGURATION"): ["start_configuration"],
    (STATE_PLAY, TO_CLIENT, "PLAY_SYSTEM_CHAT"): ["system_chat", "chat"],
    (STATE_PLAY, TO_CLIENT, "PLAY_CHAT"): ["chat"],
    (STATE_PLAY, TO_CLIENT, "PLAY_TAB_COMPLETE"): ["tab_complete"],
    (STATE_PLAY, TO_CLIENT, "PLAY_DISCONNECT"): ["kick_disconnect"],
    (STATE_PLAY, TO_CLIENT, "PLAY_DECLARE_COMMANDS"): ["declare_commands"],
    (STATE_PLAY, TO_CLIENT, "PLAY_PLAYER_INFO_UPDATE"): ["player_info"],
    (STATE_PLAY, TO_CLIENT, "PLAY_PLAYER_INFO_REMOVE"): ["player_remove"],
    (STATE_PLAY, TO_CLIENT, "PLAY_KEEP_ALIVE"): ["keep_alive"],
    (STATE_PLAY, TO_CLIENT, "PLAY_PLAYER_POSITION"): ["position"],
    (STATE_PLAY, TO_CLIENT, "PLAY_RESOURCE_PACK_SEND"): ["resource_pack_send", "add_resource_pack"],
    (STATE_PLAY, TO_CLIENT, "PLAY_CHUNK_DATA"): ["map_chunk"],
    (STATE_PLAY, TO_CLIENT, "PLAY_UNLOAD_CHUNK"): ["unload_chunk"],
    (STATE_PLAY, TO_CLIENT, "PLAY_UPDATE_LIGHT"): ["update_light"],
    (STATE_PLAY, TO_CLIENT, "PLAY_SPAWN_POSITION"): ["spawn_position"],
    (STATE_PLAY, TO_CLIENT, "PLAY_DIFFICULTY"): ["difficulty"],
    (STATE_PLAY, TO_CLIENT, "PLAY_GAME_EVENT"): ["game_state_change"],
    (STATE_PLAY, TO_CLIENT, "PLAY_ABILITIES"): ["abilities"],
    (STATE_PLAY, TO_CLIENT, "PLAY_HELD_ITEM"): ["held_item_slot"],
    (STATE_PLAY, TO_CLIENT, "PLAY_DECLARE_RECIPES"): ["declare_recipes"],
    (STATE_PLAY, TO_CLIENT, "PLAY_TAGS"): ["tags"],
    (STATE_PLAY, TO_CLIENT, "PLAY_UPDATE_VIEW_POSITION"): ["update_view_position"],
    (STATE_PLAY, TO_CLIENT, "PLAY_UPDATE_VIEW_DISTANCE"): ["update_view_distance"],
    (STATE_PLAY, TO_CLIENT, "PLAY_SIMULATION_DISTANCE"): ["simulation_distance"],
    (STATE_PLAY, TO_CLIENT, "PLAY_CHUNK_BATCH_START"): ["chunk_batch_start"],
    (STATE_PLAY, TO_CLIENT, "PLAY_CHUNK_BATCH_FINISHED"): ["chunk_batch_finished"],
    (STATE_PLAY, TO_CLIENT, "PLAY_UNLOCK_RECIPES"): ["unlock_recipes"],
    (STATE_PLAY, TO_CLIENT, "PLAY_ENTITY_STATUS"): ["entity_status"],
    (STATE_PLAY, TO_CLIENT, "PLAY_SERVER_DATA"): ["server_data"],
    (STATE_PLAY, TO_CLIENT, "PLAY_WORLD_BORDER_INIT"): ["initialize_world_border", "world_border"],
    (STATE_PLAY, TO_CLIENT, "PLAY_UPDATE_TIME"): ["update_time"],
    (STATE_PLAY, TO_CLIENT, "PLAY_SET_TICKING_STATE"): ["set_ticking_state", "tick_state"],
    (STATE_PLAY, TO_CLIENT, "PLAY_STEP_TICK"): ["step_tick"],
    (STATE_PLAY, TO_CLIENT, "PLAY_SET_CONTAINER_CONTENT"): ["window_items"],
    (STATE_PLAY, TO_CLIENT, "PLAY_SET_CONTAINER_SLOT"): ["set_slot"],
    (STATE_PLAY, TO_CLIENT, "PLAY_SET_ENTITY_METADATA"): ["entity_metadata"],
    (STATE_PLAY, TO_CLIENT, "PLAY_UPDATE_ATTRIBUTES"): ["entity_update_attributes", "update_attributes"],
    (STATE_PLAY, TO_CLIENT, "PLAY_UPDATE_ADVANCEMENTS"): ["advancements"],
    (STATE_PLAY, TO_CLIENT, "PLAY_UPDATE_HEALTH"): ["update_health"],
    (STATE_PLAY, TO_CLIENT, "PLAY_SET_EXPERIENCE"): ["experience"],
    (STATE_PLAY, TO_CLIENT, "PLAY_BLOCK_UPDATE"): ["block_change"],
    (STATE_PLAY, TO_CLIENT, "PLAY_MULTI_BLOCK_CHANGE"): ["multi_block_change"],
    (STATE_PLAY, TO_CLIENT, "PLAY_ENTITY_DESTROY"): ["entity_destroy"],

    (STATE_PLAY, TO_SERVER, "PLAY_CLIENT_INFORMATION"): ["settings"],
    (STATE_PLAY, TO_SERVER, "PLAY_CHAT_COMMAND"): ["chat_command", "chat"],
    (STATE_PLAY, TO_SERVER, "PLAY_TAB_COMPLETE_REQUEST"): ["tab_complete"],
    (STATE_PLAY, TO_SERVER, "PLAY_CONFIGURATION_ACKNOWLEDGED"): ["configuration_acknowledged"],
    (STATE_PLAY, TO_SERVER, "PLAY_TELEPORT_CONFIRM"): ["teleport_confirm"],
    (STATE_PLAY, TO_SERVER, "PLAY_POSITION"): ["position"],
    (STATE_PLAY, TO_SERVER, "PLAY_POSITION_LOOK"): ["position_look"],
    (STATE_PLAY, TO_SERVER, "PLAY_LOOK"): ["look"],
    (STATE_PLAY, TO_SERVER, "PLAY_FLYING"): ["flying"],
    (STATE_PLAY, TO_SERVER, "PLAY_RESOURCE_PACK_STATUS"): ["resource_pack_receive"],
    (STATE_PLAY, TO_SERVER, "PLAY_PLAYER_DIGGING"): ["block_dig"],
    (STATE_PLAY, TO_SERVER, "PLAY_SWING_ARM"): ["arm_animation"],
    # Kinds that exist in both directions with different ids.
    (STATE_PLAY, TO_SERVER, "PLAY_KEEP_ALIVE"): ["keep_alive"],
    (STATE_PLAY, TO_SERVER, "PLAY_PLUGIN_MESSAGE"): ["custom_payload"],
    # 1.19 split the serverbound chat packet into signed chat_message and
    # chat_command; older versions carry both through plain "chat".
    (STATE_PLAY, TO_SERVER, "PLAY_CHAT"): ["chat_message", "chat"],
    (STATE_CONFIG, TO_CLIENT, "CONFIGURATION_PLUGIN_MESSAGE"): ["custom_payload"],
    (STATE_CONFIG, TO_CLIENT, "CONFIGURATION_FINISH"): ["finish_configuration"],
    (STATE_CONFIG, TO_CLIENT, "CONFIGURATION_KEEP_ALIVE"): ["keep_alive"],
    (STATE_CONFIG, TO_CLIENT, "CONFIGURATION_KNOWN_PACKS"): ["select_known_packs"],
}

# Conduit ConnectionState / PacketDirection names for the Java output.
JAVA_STATE = {
    STATE_HANDSHAKE: "AWAITING_HANDSHAKE", STATE_STATUS: "STATUS", STATE_LOGIN: "LOGIN",
    STATE_CONFIG: "CONFIGURATION", STATE_PLAY: "PLAY",
}
JAVA_DIRECTION = {TO_CLIENT: "SERVER_TO_CLIENT", TO_SERVER: "CLIENT_TO_SERVER"}

# PLAY_CHAT is the pre-1.19 clientbound chat packet and PLAY_SYSTEM_CHAT is its
# modern replacement; both resolve to "chat" on old versions, which would make
# them collide. Keep PLAY_CHAT only where there is no system_chat.
def resolve(protocol_json: dict, key: tuple[str, str, str]) -> int | None:
    state, direction, kind = key
    candidates = KINDS[key]
    section = protocol_json.get(state, {}).get(direction)
    if not section:
        return None
    mappings = section["types"]["packet"][1][0]["type"][1]["mappings"]
    by_name = {name: int(code, 16) for code, name in mappings.items()}
    if kind == "PLAY_CHAT" and direction == TO_CLIENT and "system_chat" in by_name:
        return None  # modern versions carry server chat as PLAY_SYSTEM_CHAT instead
    for name in candidates:
        if name in by_name:
            return by_name[name]
    return None


def paths() -> dict:
    CACHE.mkdir(parents=True, exist_ok=True)
    cached = CACHE / "dataPaths.json"
    if not cached.exists():
        with urllib.request.urlopen(PATHS_URL, timeout=120) as response:
            cached.write_bytes(response.read())
    return json.loads(cached.read_text(encoding="utf-8"))["pc"]


def protocol_json(release: str) -> dict | None:
    index = paths()
    if release not in index or "protocol" not in index[release]:
        return None
    cached = CACHE / f"{release}.protocol.json"
    if not cached.exists():
        url = BASE_URL + index[release]["protocol"] + "/protocol.json"
        with urllib.request.urlopen(url, timeout=180) as response:
            cached.write_bytes(response.read())
    return json.loads(cached.read_text(encoding="utf-8"))


def table_for(release: str) -> dict[tuple[str, str, str], int]:
    data = protocol_json(release)
    if data is None:
        raise SystemExit(f"minecraft-data has no protocol.json for {release}")
    result = {}
    for key in KINDS:
        value = resolve(data, key)
        if value is not None:
            result[key] = value
    return result


ENTRY = re.compile(
    r"ConnectionState\.(\w+),\s*PacketDirection\.(\w+),\s*PacketKind\.(\w+),\s*((?:0x)?[0-9A-Fa-f]+)")
DEFN = re.compile(r"private static final ProtocolDefinition (\w+) = define\(")


def declared_tables() -> dict[str, dict[tuple[str, str, str], int]]:
    """Parse the hand-authored tables straight out of ProtocolDefinition.java.

    Keyed by (state, direction, kind): several kinds -- keep-alive, plugin
    message, chat -- exist in both directions with different ids, so a
    kind-only key would silently drop one of each pair.
    """
    text = DEFINITION.read_text(encoding="utf-8")
    starts = [(m.group(1), m.start()) for m in DEFN.finditer(text)]
    tables: dict[str, dict[tuple[str, str, str], int]] = {}
    for index, (name, start) in enumerate(starts):
        end = starts[index + 1][1] if index + 1 < len(starts) else len(text)
        table = {}
        for state, direction, kind, value in ENTRY.findall(text[start:end]):
            table[(state, direction, kind)] = int(value, 16) if value.lower().startswith("0x") else int(value)
        tables[name] = table
    return tables


def to_java_key(key: tuple[str, str, str]) -> tuple[str, str, str]:
    state, direction, kind = key
    return JAVA_STATE[state], JAVA_DIRECTION[direction], kind


def cmd_check(args) -> None:
    pairs = [("V1_13", "1.13"), ("V1_20_1", "1.20.1"), ("V1_20_4", "1.20.4"), ("V1_20_5", "1.20.5")]
    tables = declared_tables()
    total_mismatch = 0
    for java_name, release in pairs:
        declared = tables.get(java_name)
        if declared is None:
            print(f"[skip] {java_name}: not found in ProtocolDefinition.java")
            continue
        published = {to_java_key(k): v for k, v in table_for(release).items()}
        mismatches = {k: (v, published[k]) for k, v in declared.items()
                      if k in published and published[k] != v}
        unknown = sorted(k for k in declared if k not in published)
        print(f"\n== {java_name} ({release}): {len(declared)} declared, "
              f"{len(mismatches)} disagree, {len(unknown)} not resolvable from published data")
        for (state, direction, kind), (mine, theirs) in sorted(mismatches.items()):
            print(f"   MISMATCH {state}/{direction}/{kind}: "
                  f"Conduit 0x{mine:02X} vs published 0x{theirs:02X}")
        for state, direction, kind in unknown:
            print(f"   unresolved {state}/{direction}/{kind}")
        total_mismatch += len(mismatches)
    print(f"\ntotal mismatches: {total_mismatch}")
    raise SystemExit(1 if total_mismatch else 0)


def cmd_deltas(args) -> None:
    base = table_for(args.base_release)
    target = table_for(args.release)
    keys = sorted(set(base) | set(target))
    lines = []
    for key in keys:
        old, new = base.get(key), target.get(key)
        if old == new:
            continue
        state, direction, kind = key
        js, jd = JAVA_STATE[state], JAVA_DIRECTION[direction]
        if new is None:
            lines.append(f"      PacketMapping.removed(ConnectionState.{js}, PacketDirection.{jd}, "
                         f"PacketKind.{kind}),")
        else:
            lines.append(f"      PacketMapping.of(ConnectionState.{js}, PacketDirection.{jd}, "
                         f"PacketKind.{kind}, 0x{new:02X}),")
    print(f"// {args.release} derived from {args.base_release}: {len(lines)} changed mappings "
          f"of {len(keys)} known packets")
    print("\n".join(lines) if lines else "      // no differences")


def cmd_declare(args) -> None:
    """Print a full declared table for a release, for a version with no suitable base.

    `deltas` covers the common case: a point release that moves a handful of ids off
    a neighbour. A version from a different era has no neighbour in the registry to
    derive from, and deriving one anyway would inherit every id the delta did not
    happen to override -- which is a silently corrupted stream rather than a missing
    entry. So this emits the whole table, and only the kinds the published data
    actually resolves: a kind absent here reads as "this version has no such packet",
    which is what the lookup returns for it and what the proxy guards on.
    """
    table = table_for(args.release)
    print(f"// {args.release}: {len(table)} mappings resolved from published data, "
          f"{len(KINDS) - len(table)} kinds absent")
    for key in sorted(table, key=lambda k: (JAVA_STATE[k[0]], JAVA_DIRECTION[k[1]], k[2])):
        state, direction, kind = key
        print(f"      ConnectionState.{JAVA_STATE[state]}, PacketDirection.{JAVA_DIRECTION[direction]}, "
              f"PacketKind.{kind}, 0x{table[key]:02X},")


def cmd_coverage(args) -> None:
    releases = args.releases.split(",") if args.releases else sorted(paths())
    print(f"{'release':<10} {'mapped':>6} {'absent':>7}")
    for release in releases:
        if protocol_json(release) is None:
            print(f"{release:<10} {'--':>6} no published protocol.json")
            continue
        table = table_for(release)
        print(f"{release:<10} {len(table):>6} {len(KINDS) - len(table):>7}")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="command", required=True)
    sub.add_parser("check").set_defaults(func=cmd_check)
    deltas = sub.add_parser("deltas")
    deltas.add_argument("release")
    deltas.add_argument("base_release")
    deltas.set_defaults(func=cmd_deltas)
    declare = sub.add_parser("declare")
    declare.add_argument("release")
    declare.set_defaults(func=cmd_declare)
    coverage = sub.add_parser("coverage")
    coverage.add_argument("--releases")
    coverage.set_defaults(func=cmd_coverage)
    args = parser.parse_args()
    args.func(args)


if __name__ == "__main__":
    main()

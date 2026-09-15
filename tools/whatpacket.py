#!/usr/bin/env python3
"""Resolve a numeric packet id to its published name: whatpacket.py <release> <state> <toClient|toServer> <0xNN ...>"""
import sys, pathlib
sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
import packetids

release, state, direction = sys.argv[1], sys.argv[2], sys.argv[3]
j = packetids.protocol_json(release)
m = j[state][direction]['types']['packet'][1][0]['type'][1]['mappings']
by_id = {int(k, 16): v for k, v in m.items()}
for arg in sys.argv[4:]:
    n = int(arg, 16) if arg.lower().startswith('0x') else int(arg)
    print(f"{release} {state}/{direction} 0x{n:02X} = {by_id.get(n, '<not defined>')}")

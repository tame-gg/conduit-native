# Conduit compatibility matrix (Minecraft 1.13 - 26.2)

Generated from the live registries, not hand-maintained: every row is what
`CompatibilityRegistry.resolve(client, backend)` would tell a real session.
Regenerate after any protocol change. 1.12.2 and older are deliberately out of
scope for this program's *native* codecs.

## ViaVersion ecosystem (optional)

When `[translation] enabled = true`, Conduit prefers the ViaVersion graph for
TRANSLATED pairs (see `docs/VIAVERSION.md`). Native translators remain as
fallback/experimental.

| Client → Backend | Mode | Verification |
|---|---|---|
| same protocol (codec present) | DIRECT | VERIFIED / codec-dependent |
| 765 → 393 (Via enabled) | TRANSLATED | **VERIFIED** — real 1.20.4 client, real 1.13 server, gameplay through Via, 0 translation failures |
| 765 → 404 (Via enabled, direct, no switch) | TRANSLATED | **VERIFIED** — real 1.20.4 client, real 1.13.2 server, joins and plays, 0 translation failures |
| 765 → 404 (Via enabled, after `/server`) | TRANSLATED | VERIFIED — real 1.20.4 client switched between a real 1.13 and a real 1.13.2 backend five times, each time through a full Configuration phase (feature flags, registry data, tags, finish) and back into Play; see RESULTS-VIA-393-765.md |
| 393 → 765 (Via enabled) | TRANSLATED | **VERIFIED** — real 1.13 client, real 1.20.4 server, 7 min of gameplay through Via, 0 translation failures |
| 5 (1.7.6) → 765, and after `/server` 765 → 393 → 765 (Via+Rewind) | TRANSLATED | **REAL-CLIENT VERIFIED** — see the real-client matrix below |
| 47 (1.8.9) → 765, and after `/server` 765 → 393 → 765 (Via+Rewind) | TRANSLATED | **REAL-CLIENT VERIFIED** — see the real-client matrix below |
| modern → 1.7.6 (Via+Legacy) | TRANSLATED | UNTESTED — ViaLegacy not loaded; not shown to be required |
| * → 26.3 / 26.3 → * on Via 5.11.0 | UNSUPPORTED | Via does not register 26.3; out of scope for this phase |

## Real-client matrix (Via engine, target 1.7.6 → 26.2)

Evidence levels, lowest to highest. A row is only ever at the level its evidence
reaches.

- `UNTESTED` - nothing has been run.
- `PROBED` - the compatibility probe / Via path says the pair can be carried.
- `OFFLINE/SCRIPTED` - unit tests or a scripted harness, no real client in the loop.
- `REAL-CLIENT PARTIAL` - a real client and a real server through Conduit, but
  only part of the gameplay checklist was observed.
- `REAL-CLIENT VERIFIED` - a real client and a real server through Conduit, with
  login, world, movement, block interaction, chat and Keep Alive each observed,
  and the session still connected at least 60 s after the last switch.
- `FAILED` - a real run that did not hold, with the failure recorded.

| Client | Backend path | Level | Evidence |
|---|---|---|---|
| 1.7.6 (5) | 1.20.4 | REAL-CLIENT VERIFIED | run `20260916-222350`: joined, world; connected 78 s; 5/5 Keep Alives answered; 101 position packets and 3 digs forwarded; chat reached the server; 0 translation failures |
| 1.7.6 (5) | 1.20.4 → `/server` 1.13 → `/server` 1.20.4 | REAL-CLIENT VERIFIED | run `20260916-224844`: both switches completed; legacy world reload put the client in the 1.20.4 world; connected 158 s after returning; 6/6 Keep Alives answered; chat reached the server and its echo rendered on the client; 140 position packets forwarded; a block broken and answered with a Block Update; 0 translation failures. Needed `630d7eb` (Conduit's own chat carried the 1.8 position byte; the client disconnected with "found 1 bytes extra whilst reading packet 2") |
| 1.8.9 (47) | 1.20.4 | REAL-CLIENT VERIFIED | run `20260916-221648`: joined, world; connected 125 s; 8/8 Keep Alives answered; 121 position packets and 3 digs forwarded; chat reached the server; 0 translation failures |
| 1.8.9 (47) | 1.20.4 → `/server` 1.13 → `/server` 1.20.4 | REAL-CLIENT VERIFIED | runs `20260916-221148` and `20260916-223226`: connected 105 s after returning; 7 Keep Alives answered; chat reached the server at +37 s and +93 s; movement and digging forwarded; 0 translation failures. Needed `ac59183` (the post-switch hold never ended when Via delivered Join Game as an extra; the server timed the player out after 30 s) |
| 1.12.2 (340) | 1.20.4 | REAL-CLIENT VERIFIED | run `20260916-230702`: joined 23:07:32, client-initiated Disconnect 23:12:08, clean on both ends; server-confirmed over RCON: block broken, block placed, pig hit (10 → 9 HP), five diamonds moved from a chest into hotbar slot 9 (2 Click Window, 1 Close Window), player walked; 18/18 Keep Alives answered; 218 chunks and 487 entity spawns delivered; chat reached the server; 0 translation failures; client log clean |
| 1.12.2 (340) | 1.20.4 → `/server` 1.13 → `/server` 1.20.4 | REAL-CLIENT VERIFIED | run `20260916-231228`: back on 1.20.4 at 23:14:25, clean Disconnect 23:16:40 (135 s); the same server-confirmed break, place, entity, container and walk checks all passed after the return; 8/8 Keep Alives; two chat lines reached the server; 0 translation failures in either session |
| 1.12.2 (340) | 1.20.4 → 1.13 → 1.20.4 → 1.13 (return to the legacy backend) | REAL-CLIENT VERIFIED | run `20260916-231735`: on 1.13 from 23:19:49, clean Disconnect 23:22:17 (148 s); break, place, entity, container and walk server-confirmed on 1.13; 10/10 Keep Alives; two chat lines reached the server; 0 translation failures in all four sessions; Conduit's own "Connecting to/Connected to" messages rendered on the client |
| 1.15.2 (578) | 1.20.4 | REAL-CLIENT VERIFIED | run `20260916-232609`: joined 23:28:16, clean Disconnect 23:30:03; break, place, entity, container and walk confirmed over RCON; 7/7 Keep Alives; 218 chunks, 167 entity spawns; two chat lines, echoed on the client; 0 translation failures. The client must be joined through Direct Connect: launched with `--server` it connects while still loading resources and crashes tesselating the first chunk, straight to a vanilla 1.15.2 server as well (see `run-matrix.ps1 -NoAutoConnect`, `join-direct.ps1`) |
| 1.15.2 (578) | 1.20.4 → `/server` 1.13 → `/server` 1.20.4 | REAL-CLIENT VERIFIED | run `20260916-234601`: both worlds loaded; back on 1.20.4 for 127 s; break, place, entity, container and walk confirmed over RCON; 8/8 Keep Alives; two chat lines; ended with the client's Disconnect button; 0 translation failures in every session. Needed `11e32a0`: the legacy world reload wrote the 1.13 Respawn layout under 1.14's id `0x3A`, which 1.15 uses for Resource Pack Send, and the client disconnected reading a URL length of 268435455; without the reload it sat on "Loading terrain" |
| 1.16.5 (754) | 1.20.4 | REAL-CLIENT VERIFIED | Modrinth launch (the offline harness launch has Multiplayer disabled by the client's account check). Joined 00:01:31; break, place, entity, container and walk confirmed over RCON; chat reached the server; 0 translation failures |
| 1.16.5 (754) | 1.20.4 → `/server` 1.21.8 → `/server` 1.20.4 | REAL-CLIENT VERIFIED | run `20260917-001451`: both switches completed, `0x39` Respawn pair behind each switched Join Game; back on 1.20.4 for 169 s with break, place, entity, container and walk confirmed over RCON, 8/8 Keep Alives, two chat lines, client Disconnect; 0 translation failures. Needed `1772c71` (Conduit's "Connecting to…" lacked the 1.16 sender UUID; client disconnected at readerIndex 114 + 8) and `29c8abf` (no world reload for 1.16.2+; client stuck on "Loading terrain"). On the superflat 1.21.8 test world the ground is not drawn: the player is at y −60 and Via does not carry extended world height to 1.16 clients |
| 1.16.5 (754) | 1.13 (direct or after `/server`) | FAILED (Via) | client disconnects with "Incomplete set of tags received from server". Same with no switch (control run `20260917-000814`). The Tags packet ViaVersion 5.11.0 builds from a 1.13 server lacks tags a 1.16.x client requires (128 block, 64 item, 16 entity-type tags present in the 1.20.4-sourced packet the client accepts, e.g. `beacon_base_blocks`, `climbable`, `arrows`, `impact_projectiles`); Conduit forwards the packet unmodified. Not fixed: supplying the tags would be Conduit authoring a translation Via does not provide |
| 1.21.8 (772) | 1.20.4 | REAL-CLIENT VERIFIED | run `20260917-004718`: joined 00:47:58; break, place, entity, container and walk confirmed over RCON; 7/7 Keep Alives; 109 chunks; two chat lines; 0 translation failures; client log clean. Needed `edbab76`: ViaBackwards' missing registries (chicken/cow/pig/frog/painting variants, wolf sound variant and five more) were written after Finish Configuration and the client refused to load its registries |
| 1.21.8 (772) | 1.20.4 → `/server` 1.13 | REAL-CLIENT VERIFIED | same run: on 1.13 from 00:49:49 to a client Disconnect at 00:51:56; break, place, entity, container and walk confirmed over RCON; 9/9 Keep Alives; 153 chunks; two chat lines; 0 translation failures. Needed `edbab76`: a chunk translated on the backend-reader thread reached the client ahead of the Join Game released by its Configuration acknowledgement |
| 1.21.8 (772) | 1.20.4 → 1.13 → 1.20.4 → 1.13 → 1.20.4 | REAL-CLIENT VERIFIED | run `20260917-013443`: every switch completed; after the first return break, place, entity, container and walk confirmed over RCON with 7/7 Keep Alives and two chat lines; after the second return held 96 s with 7/7 Keep Alives, 232 position packets, chat reached the server, client Disconnect; 0 translation failures in all five sessions. Needed `b5cb59c`. Earlier run `20260917-003208` (before it): the 1.20.4 server closed the connection over Client Information ("found 1 bytes extra whilst reading packet 0") and Conduit aborted the switch with "backend finished configuration before known packs"
| 26.2 (776) | 1.20.4 | REAL-CLIENT VERIFIED | run `20260917-005235`: joined 00:53:11, held 151 s; break, place, entity, container and walk confirmed over RCON; two chat lines; no Keep Alive timeout (no published 26.2 ids to count them by); 0 translation failures |
| 26.2 (776) | 1.20.4 → `/server` 1.13 | REAL-CLIENT VERIFIED | same run: on 1.13 for 108 s; break, place, entity, container and walk confirmed over RCON; two chat lines; 0 translation failures |
| 26.2 (776) | 1.20.4 → 1.13 → 1.20.4 → 1.13 → 1.20.4 | REAL-CLIENT VERIFIED | run `20260917-014219`: every switch completed; after the first return break, place, entity, container and walk confirmed over RCON and two chat lines; after the second return held 98 s with 7 server Keep Alives and no timeout, chat reached the server, client Disconnect; 0 translation failures. Needed `b5cb59c`; failed the same way as 1.21.8 before it
| 1.12.2 (340) | 1.20.4 → 1.13 → 1.20.4, re-run after `11e32a0` | REAL-CLIENT VERIFIED | run `20260916-235108`: break, place, entity, container confirmed over RCON after the return; 6/6 Keep Alives; 0 translation failures |
| 1.20.4 (765) | 1.13 ↔ 1.13.2, six switches | REAL-CLIENT VERIFIED | switching re-run after `ac59183`, 6/6, no timeouts |
| 1.20.4 (765) | 1.21.8 | REAL-CLIENT PARTIAL | joined, chat, combat |
| 1.20.4 (765) | 1.13 → 1.21.8 → 1.13 → 26.2 → 1.13 → 1.21.8 → 26.2 → 1.13 → 1.21.8 → 26.2 → 1.21.8 | REAL-CLIENT PARTIAL | run `20260917-020738`: all ten switches completed, chat reached every backend except the first 26.2 visit (the player spawned at night and was killed; the death screen took the input); last session held 75 s with 10/10 Keep Alives and walking, client Disconnect; 0 switch failures. Gameplay probe not run (RCON only on the 1.20.4 and 1.13 test servers). Needed `b5cb59c`: before it the switch onto 1.21.8 timed out, because Via's reply to the backend's Known Packs was never flushed |
| 1.20.4 (765) | 1.20.4 (DIRECT, no Via) | REAL-CLIENT VERIFIED | run `20260917-025439`: joined 02:55:32; break, place, entity, container and walk confirmed over RCON; 10/10 Keep Alives; 266 position packets; 218 chunks; three chat lines; the clientbound dump starts with an uncompressed Login Success and carries no Set Compression. Needed `2de8348`. Before it (runs `20260917-014956`, `-015635`, `-015856`, `-023323`, `-023851`) the client was disconnected with "Badly compressed packet - actual length of uncompressed payload 0 is does not match declared size 103": with forwarding `none` a DIRECT initial join never had its backend login completed by Conduit, so the backend's Set Compression reached the client, Conduit read both sockets in the wrong format, the backend's Finish Configuration (`00 02`) parsed as a plugin message and ended the session, and the fallback switch's uncompressed Start Configuration (`67`) was read by the client as a declared size of 103 |
| 1.20.4 (765) | 1.20.4 DIRECT → 1.13 → 1.21.8 → 26.2 → 1.20.4 DIRECT | REAL-CLIENT VERIFIED | same run: every switch completed with chat on each backend (the `/server v1204` typed on 26.2 was lost to a death screen and repeated after respawning); back on 1.20.4 DIRECT, break, place, entity, container and walk confirmed over RCON again, 9 Keep Alives answered, 275 position packets, chat, held 89 s, client Disconnect; no switch failures |
| 1.20.4 (765) | 26.2 | REAL-CLIENT PARTIAL | joined, chat |
| 1.20.4, 1.8.9, 1.12.2 re-run after `edbab76` | 1.13 ↔ 1.13.2 (six switches); 1.20.4 → 1.13 → 1.20.4 | REAL-CLIENT VERIFIED | 1.20.4: 6/6 switches, 0 translation failures; 1.8.9 and 1.12.2: connected past 60 s after returning, 6/6 Keep Alives each. 1.15.2 and 1.16.5 were not re-run after `edbab76` |
| 1.8.9, 1.12.2 re-run after `b5cb59c` | 1.20.4 → 1.13 → 1.20.4 | REAL-CLIENT VERIFIED | runs `20260917-021900`, `20260917-022327`: break, place, entity, container confirmed over RCON after the return; 6/6 Keep Alives each; held 85 s; client Disconnect; 0 switch failures. 1.7.6, 1.15.2 and 1.16.5 not re-run: `b5cb59c` only changes paths where the client has a Configuration phase |
| 1.21.8, 26.2, 1.8.9, 1.12.2 re-run after `2de8348` | 1.20.4 → 1.13 → 1.20.4 | REAL-CLIENT VERIFIED | runs `20260917-030420`, `20260917-031258`, `20260917-031727`, `20260917-032123`: break, place, entity, container confirmed over RCON after the return for all four; Keep Alives 8/8 (1.21.8), 6/6 (1.8.9, 1.12.2), 6 from the server with no timeout (26.2); 86 s holds; chat; client Disconnect; 0 translation failures. 1.7.6, 1.15.2 and 1.16.5 not re-run: `2de8348` only changes the initial join, and their verified joins are TRANSLATED, which already completed the backend login |

### The harness verdict is not evidence

`switch-matrix.ps1` and `check-matrix.ps1` print a one-line verdict. It is a
convenience, not the result. `switch-matrix.ps1` reported "2 of 2 switches
completed, session intact" for a 1.8.9 session the backend timed out 30 s later,
because it stops watching 14 s after the last switch and never reads the backend
for a timeout; it counts a translation being *built*, not the player arriving.
The source of truth is the three logs together: the backend's own log (joins,
`lost connection` and its reason, chat lines), the proxy trace (client packets
received against client packets handed to the translator, Keep Alive both ways,
translation failures), and the client (its log, and its screen when it
disconnects, which is where the 1.7.6 decoder error was only visible).

From 1.12.2 on, gameplay is checked with `gameplay-probe.ps1`, which arranges a
test pad over RCON, drives the real client at known geometry, and reads each
result back from the server (`execute if block`, entity health, chest and player
inventory) rather than from a screenshot. `session-evidence.sh` then counts one
backend session of the proxy trace by number. A probe step whose server-side
setup failed is a harness failure, not a client result, and is reported as such.

Two harness artefacts to read around: every run kills all `java.exe` first, so
the previous proxy logs a fallback "switch" as its backends die; read a run's
logs before starting the next. And screenshots capture the whole desktop.

Do not read Via dependency presence as VERIFIED gameplay. 765 → 393 is VERIFIED
because a real 1.20.4 client moved, mined, fought a mob to an advancement,
chatted and ran commands in a real 1.13 world for the length of a run, with
zero translation failures. 393 → 765 is VERIFIED because a real 1.13 client
spent fifteen minutes in a real 1.20.4 world — walking, mining, placing a chest,
opening it, moving and splitting a stack inside it, killing a mob, chatting, and
finally choosing Disconnect — with 1 132 serverbound packets across 17 Play
types translated and zero translation failures. Container and block state were
read back out of the 1.20.4 server over RCON rather than judged from the
client's screen. That direction needed Conduit to repair a recipe list
ViaBackwards 5.11.0 encodes in a way a 1.13 client cannot read. Details of both
directions, including the exact bytes of the recipe defect and the upstream
check, are in `docs/VALIDATION_VIA_393_765.md`.

The native 393 ↔ 765 path is unaffected by the Via work and was re-checked with
a real 1.13 client against a real 1.20.4 server after it: in-world, rendering,
command tree intact.

## What the statuses mean

Four independent axes. Collapsing them into one "supported" flag is how a proxy
ends up advertising a path it cannot carry, so they are kept apart.

- **Catalog entry** - Conduit knows the protocol number and release names. Says
  nothing about whether it can speak it.
- **Codec status** - how well Conduit knows that protocol's wire format.
  - `DECLARED` - packet table authored directly for this protocol.
  - `DERIVED` - inherits a declared table plus this release's published delta.
    Usable; this exact protocol number has not been audited packet by packet and
    its capabilities are inherited rather than confirmed.
  - `VERIFIED` - authored *and* exercised against a real client/server of that
    version. Only a real wire test promotes a protocol here. **393 (1.13) is
    currently the only VERIFIED protocol** - see
    `work/real-client-validation/RESULTS-393-NATIVE.md`.
- **Translation support** - `DIRECT` (same version), `TRANSLATED` (a translator
  is registered for that *ordered* pair), `UNSUPPORTED`.
- **Completeness** - `FULL` or `PARTIAL`. A `DIRECT` path over a `DERIVED` codec
  is `PARTIAL`, because nothing has validated that table on the wire.
- **Validation** (`ValidationStatus`) - how far the *pairing* has been proven,
  independent of the above: `CODEC_PARTIAL`, `CODEC_COMPLETE`,
  `DIRECT_VERIFIED`, `TRANSLATED_PARTIAL`, `TRANSLATED_VERIFIED`. A passing unit
  test proves the codec, not the pairing; only a real client of the source
  version reaching a real server of the target version promotes a pair to
  VERIFIED.

Translation is never mirrored. A registered 393 -> 765 translator says nothing
about 765 -> 393; the two directions face different problems (fields to
synthesise one way and discard the other, a configuration state that exists on
one side only, non-injective block-state and metadata id maps) and are
registered independently.

## Current state, honestly

The codec foundation covers 37 of the 38 distinct protocol numbers in the
1.13-26.2 range. Translation coverage: **6 ordered pairs**. The matrix is still
mostly `UNSUPPORTED`, and that is the accurate picture.

**393 ↔ 765 is verified in BOTH directions with real clients and real servers.**
See `docs/VALIDATION_393_765.md` / `work/real-client-validation/RESULTS-393-765-CROSS.md`.

**393 ↔ 404 is verified in BOTH directions** (plus DIRECT 404↔404) with official
Mojang jars and scripted protocol clients. The only gameplay-critical delta is
Slot wire form; see `docs/DELTA_393_404.md` and `docs/VALIDATION_393_404.md`.
Completeness stays `PARTIAL`: recipes/advancements/trades that embed Slot are
dropped rather than rematerialised.

**404 ↔ 477 is script-verified in BOTH directions** (plus DIRECT 477↔477) with
official Mojang jars and scripted protocol clients. Core deltas: Join Game/
Respawn, Open Window menu ids, chunk heightmaps + Update Light, Position packing,
Block Place, and 1.14's per-section block count.

Block states, items and entity types are **semantic**, resolved by identifier and
property set from the registries the official jars emit. They are not numeric
passthrough, which would have been wrong for the large majority of each table:
only 748 of 8599 block states, 108 of 790 items and 6 of 95 entity types keep
their meaning across this pair.

Additionally, **404 → 477 is real-client core-gameplay verified**: a real 1.13.2
client held a real 1.14 server through a full gameplay burst, 51,175 packets, no
translation or decoder faults.

**477 → 404 remains scripted-probe verified.** The arrow crash that blocked it is
fixed — 1.14 had changed Spawn Object's type from the legacy object enumeration
to the entity registry id, so a dropped item arrived as an arrow — and entity
metadata is now aligned against measured per-entity layouts. Every field a 477
client receives through Conduit was compared against what a real 1.14 server
sends for the same entity, across 86 entity types, with zero mismatches. It is
not upgraded further only because the real 1.14.0 client cannot render in this
environment even with no proxy in the path; see `docs/METADATA_404_477.md`.

See `docs/DELTA_404_477.md`, `docs/VALIDATION_404_477.md` and
`work/real-client-validation/RESULTS-477-REALCLIENT.md`. Completeness stays
`PARTIAL`: per-entity-class metadata is unmodelled; recipes/tags/advancements
dropped.

765 ↔ 766 remains `TRANSLATED_PARTIAL`: a translator exists and unit tests
pass, but no real cross-version run has been done.

Known gaps:

- **Protocol 485 (1.14.2)** has no codec - no published packet data to derive
  from. It stays a catalog entry.
- **Protocol 776 (26.2)** and **763 (1.20.1)** have thin declared tables (38 and
  21 packets). 26.2 has no published packet data yet, so it cannot be enriched
  by derivation the way the rest of the range was.
- **393 and 404 have verified pairings.** Codec status for 404 remains
  `DERIVED` (inherited packet ids); the *pairing* axes are
  `DIRECT_VERIFIED` / `TRANSLATED_VERIFIED`.
- **Entity type ids are copied numerically** across the 393 ↔ 765 pair. The
  entity registry gained entries between the versions, so a mob can render as
  the wrong model while sitting at the correct position with correct motion.
  Fixable with a type-name mapping.
- Derived versions inherit their base's `ProtocolCapabilities`. Per-release
  capability auditing is outstanding for all 32 derived protocols.
- **Backend login plugin requests other than Velocity modern forwarding are
  refused.** Conduit completes every backend login itself, including a DIRECT
  initial join since `2de8348` (switches and MODERN joins always did), so a
  backend that sends another login query, such as a Forge login handshake,
  fails the connection instead of having it relayed to the client. Forge and
  NeoForge real-client joins are not verified either way.

## Protocols

| Protocol | Release | Family | Codec | Packets | Config state | Direct | Source |
| ---: | --- | --- | --- | ---: | --- | --- | --- |
| 393 | 1.13 | V1_13 | VERIFIED | 55 | no | DIRECT/FULL | authored from published 1.13 packet ids; exercised end-to-end by the official Minecraft 1.13 client against the official 1.13 server through Conduit (login, chunks, movement, combat, death, respawn, advancements; ~3 minutes, no disconnect) |
| 401 | 1.13.1 | V1_13 | DERIVED | 55 | no | DIRECT/PARTIAL | published packet ids for 1.13.1; capabilities inherited from 1.13 (derived from 1.13) |
| 404 | 1.13.2 | V1_13 | DERIVED | 55 | no | DIRECT/FULL | published packet ids for 1.13.2; capabilities inherited from 1.13.1 (derived from 1.13.1); DIRECT pairing verified — see VALIDATION_393_404.md |
| 477 | 1.14 | V1_14 | DERIVED | 64+ | no | DIRECT/PARTIAL | published packet ids for 1.14; DIRECT pairing script-verified; a real 1.14 client crashes rendering even on a byte-identical passthrough stream, so DIRECT rests on the probe — see VALIDATION_404_477.md |
| 480 | 1.14.1 | V1_14 | DERIVED | 64 | no | DIRECT/PARTIAL | published packet ids for 1.14.1; capabilities inherited from 1.14 (derived from 1.14) |
| 490 | 1.14.3 | V1_14 | DERIVED | 64 | no | DIRECT/PARTIAL | published packet ids for 1.14.3; capabilities inherited from 1.14.1 (derived from 1.14.1) |
| 498 | 1.14.4 | V1_14 | DERIVED | 64 | no | DIRECT/PARTIAL | published packet ids for 1.14.4; capabilities inherited from 1.14.3 (derived from 1.14.3) |
| 573 | 1.15 | V1_15 | DERIVED | 64 | no | DIRECT/PARTIAL | published packet ids for 1.15; capabilities inherited from 1.14.4 (derived from 1.14.4) |
| 575 | 1.15.1 | V1_15 | DERIVED | 64 | no | DIRECT/PARTIAL | published packet ids for 1.15.1; capabilities inherited from 1.15 (derived from 1.15) |
| 578 | 1.15.2 | V1_15 | DERIVED | 64 | no | DIRECT/PARTIAL | published packet ids for 1.15.2; capabilities inherited from 1.15.1 (derived from 1.15.1) |
| 735 | 1.16 | V1_16 | DERIVED | 64 | no | DIRECT/PARTIAL | published packet ids for 1.16; capabilities inherited from 1.15.2 (derived from 1.15.2) |
| 736 | 1.16.1 | V1_16 | DERIVED | 64 | no | DIRECT/PARTIAL | published packet ids for 1.16.1; capabilities inherited from 1.16 (derived from 1.16) |
| 751 | 1.16.2 | V1_16 | DERIVED | 64 | no | DIRECT/PARTIAL | published packet ids for 1.16.2; capabilities inherited from 1.16.1 (derived from 1.16.1) |
| 753 | 1.16.3 | V1_16 | DERIVED | 64 | no | DIRECT/PARTIAL | published packet ids for 1.16.3; capabilities inherited from 1.16.2 (derived from 1.16.2) |
| 754 | 1.16.5 | V1_16 | DERIVED | 64 | no | DIRECT/PARTIAL | published packet ids for 1.16.5; capabilities inherited from 1.16.3 (derived from 1.16.3) |
| 755 | 1.17 | V1_17 | DERIVED | 63 | no | DIRECT/PARTIAL | published packet ids for 1.17; capabilities inherited from 1.16.5 (derived from 1.16.5) |
| 756 | 1.17.1 | V1_17 | DERIVED | 64 | no | DIRECT/PARTIAL | published packet ids for 1.17.1; capabilities inherited from 1.17 (derived from 1.17) |
| 757 | 1.18 | V1_18 | DERIVED | 65 | no | DIRECT/PARTIAL | published packet ids for 1.18; capabilities inherited from 1.17.1 (derived from 1.17.1) |
| 758 | 1.18.2 | V1_18 | DERIVED | 65 | no | DIRECT/PARTIAL | published packet ids for 1.18.2; capabilities inherited from 1.18 (derived from 1.18) |
| 759 | 1.19 | V1_19 | DERIVED | 65 | no | DIRECT/PARTIAL | published packet ids for 1.19; capabilities inherited from 1.18.2 (derived from 1.18.2) |
| 760 | 1.19.2 | V1_19 | DERIVED | 65 | no | DIRECT/PARTIAL | published packet ids for 1.19.2; capabilities inherited from 1.19 (derived from 1.19) |
| 761 | 1.19.3 | V1_19 | DERIVED | 66 | no | DIRECT/PARTIAL | published packet ids for 1.19.3; capabilities inherited from 1.19.2 (derived from 1.19.2) |
| 762 | 1.19.4 | V1_19 | DERIVED | 66 | no | DIRECT/PARTIAL | published packet ids for 1.19.4; capabilities inherited from 1.19.3 (derived from 1.19.3) |
| 763 | 1.20.1 | V1_20 | DECLARED | 21 | no | DIRECT/FULL | authored packet table |
| 764 | 1.20.2 | V1_20 | DERIVED | 80 | yes | DIRECT/PARTIAL | published packet ids for 1.20.2; capabilities inherited from 1.20.4 (derived from 1.20.4) |
| 765 | 1.20.4 | V1_20 | DECLARED | 80 | yes | DIRECT/FULL | authored packet table |
| 766 | 1.20.5 | V1_20 | DECLARED | 42 | yes | DIRECT/FULL | authored packet table |
| 767 | 1.21 | V1_21 | DERIVED | 85 | yes | DIRECT/PARTIAL | published packet ids for 1.21; capabilities inherited from 1.20.4 (derived from 1.20.4) |
| 768 | 1.21.3 | V1_21 | DERIVED | 84 | yes | DIRECT/PARTIAL | published packet ids for 1.21.3; capabilities inherited from 1.21 (derived from 1.21) |
| 769 | 1.21.4 | V1_21 | DERIVED | 84 | yes | DIRECT/PARTIAL | published packet ids for 1.21.4; capabilities inherited from 1.21.3 (derived from 1.21.3) |
| 770 | 1.21.5 | V1_21 | DERIVED | 84 | yes | DIRECT/PARTIAL | published packet ids for 1.21.5; capabilities inherited from 1.21.4 (derived from 1.21.4) |
| 771 | 1.21.6 | V1_21 | DERIVED | 84 | yes | DIRECT/PARTIAL | published packet ids for 1.21.6; capabilities inherited from 1.21.5 (derived from 1.21.5) |
| 772 | 1.21.8 | V1_21 | DERIVED | 84 | yes | DIRECT/PARTIAL | published packet ids for 1.21.8; capabilities inherited from 1.21.6 (derived from 1.21.6) |
| 773 | 1.21.10 | V1_21 | DERIVED | 84 | yes | DIRECT/PARTIAL | published packet ids for 1.21.10; capabilities inherited from 1.21.8 (derived from 1.21.8) |
| 774 | 1.21.11 | V1_21 | DERIVED | 84 | yes | DIRECT/PARTIAL | published packet ids for 1.21.11; capabilities inherited from 1.21.10 (derived from 1.21.10) |
| 775 | 26.1 | V26 | DERIVED | 84 | yes | DIRECT/PARTIAL | published packet ids for 26.1; capabilities inherited from 1.21.11 (derived from 1.21.11) |
| 776 | 26.2 | V26 | DECLARED | 38 | yes | DIRECT/FULL | authored packet table |

### Registered translators (ordered pairs)

| Client | Backend | Support | Completeness | Validation | Notes |
| ---: | ---: | --- | --- | --- | --- |
| 393 | 404 | TRANSLATED | PARTIAL | TRANSLATED_VERIFIED | Slot rematerialisation; recipes/advancements/trades dropped |
| 404 | 393 | TRANSLATED | PARTIAL | TRANSLATED_VERIFIED | Slot rematerialisation; recipes/advancements/trades dropped |
| 393 | 765 | TRANSLATED | PARTIAL | TRANSLATED_VERIFIED | core gameplay verified both ways; cosmetics incomplete |
| 765 | 393 | TRANSLATED | PARTIAL | TRANSLATED_VERIFIED | configuration synthesis; core gameplay verified |
| 765 | 766 | TRANSLATED | PARTIAL | TRANSLATED_PARTIAL | control/login/config; JoinGame/registry unsupported |
| 766 | 765 | TRANSLATED | PARTIAL | TRANSLATED_PARTIAL | control/login/config; JoinGame/registry unsupported |

protocols with codecs: 37; matrix cells: 1369; direct: 37; translated: 6; unsupported: 1326


## Test artifacts

`artifacts/ARTIFACTS.md` records the Minecraft client and server jars used for
validation: release, protocol, kind, Java requirement, availability, and
SHA-256. All 76 jars (38 releases x client+server) were fetched from Mojang's
official version manifest and verified against Mojang's published SHA-1. The
jars themselves are excluded from Git.

## Regenerating

```
python tools/export_protocols.py      # release -> protocol, from ProtocolVersion.java
python tools/packetids.py check       # cross-check declared tables vs published data
python tools/gen_revisions.py         # regenerate ProtocolRevisions.java deltas
python tools/mcartifacts.py fetch     # mirror + verify Minecraft jars
python tools/mcartifacts.py report    # regenerate artifacts/ARTIFACTS.md
```

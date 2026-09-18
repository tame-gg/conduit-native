# Conduit compatibility matrix (Minecraft 1.13 - 26.2)

Generated from the live registries, not hand-maintained: every row is what
`CompatibilityRegistry.resolve(client, backend)` would tell a real session.
Regenerate after any protocol change. The *translation* program targets
1.13-26.2; 1.7.6, 1.8.9 and 1.12.2 have declared codecs of their own and are
carried DIRECT and through Via, which the DIRECT matrix below covers.

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

**Re-checked after the DIRECT pass**, on a build made from the current source
(2026-09-17, 17:38 - 17:57), because the DIRECT fixes touch code every session
goes through:

| Run | Path | Result |
|---|---|---|
| `switch-matrix.ps1 -Client 1.21.8 -A v1204 -B v113 -Rounds 2` | 1.20.4 ↔ 1.13 | 4 of 4 switches completed, session intact (joins v1204=3 v113=2, 4 translators built) |
| `switch-matrix.ps1 -Client 26.2 -A v1204 -B v113 -Rounds 2` | 1.20.4 ↔ 1.13 | 4 of 4 switches completed, session intact |
| `switch-matrix.ps1 -Client 1.20.4 -A v113 -B v1132 -Rounds 3` | 1.13 ↔ 1.13.2, each switch through a full Configuration phase | 6 of 6 switches completed, session intact (joins v113=4 v1132=3, 7 translators built) |
| `regress-legacy.ps1 -Client 1.8.9` | 1.20.4 → `/server` 1.13 → `/server` 1.20.4, with the Tab checks | both switches; break, place, entity and container server-confirmed after the return; two chat lines; held 102 s; clean Disconnect; no proxy errors |
| `regress-legacy.ps1 -Client 1.12.2` | same | same, held 94 s |

## DIRECT same-version real-client matrix (1.7.6 - 26.2)

Every distinct protocol Conduit has a codec for, each tested on its own: a real client of that
release, joined through Conduit with translation disabled (`[translation] enabled = false`, no Via
anywhere in the session), to two real servers of the same release. Runs of 2026-09-17; every row is
one run, named in the evidence list below the table.

**Where the version list comes from.** `ProtocolDefinition.BY_NUMBER` holds 40 codecs: the declared
tables 5, 47, 340, 393, 763, 765, 766 and 776, and the 32 protocols `ProtocolRevisions.ALL` derives.
Protocol 485 (1.14.2) is a catalog entry with no codec (no published packet data to derive one
from); it is listed as UNSUPPORTED BY CONDUIT, and a real 1.14.2 client was pointed at Conduit to
record what it is told. Releases that share a protocol (1.7.10, 1.16.4, 1.18.1, 1.19.1, 1.20, 1.20.3,
1.20.6, 1.21.1, 1.21.2, 1.21.7, 1.21.9, 26.1.1, 26.1.2) are the same wire protocol as the release
tested in their row; each protocol was run once, with the release named in VERSION.

**What one run does.** Backend A and backend B are two vanilla servers of the client's release
(offline mode), with RCON. The client joins A through Conduit, then:

- F3 screenshot for the brand; the player list held open (from this batch on);
- the gameplay probe, read back from the server over RCON rather than from the screen: a gold block
  broken, oak planks placed, a pig hit (health below 10), five diamonds moved out of a chest into the
  hotbar (chest emptied and player inventory counted), and a walk (position before and after);
- commands: `/con` and `/ser` typed with the suggestion list screenshotted (command tree), Tab
  pressed on each (completion), Enter (`/conduit` output), `/server` (server list), `/server ` Tab
  (argument suggestions and completion), `/server direct` ("already connected");
- `/server second`, then on B: `/conduit`, `/server`, `/ser` Tab, chat; `/server direct` back to A;
  `/conduit` again; held for more than 60 s after the return; Disconnect from the pause menu.

Keep Alive is counted per backend session in the proxy trace (and no backend logged a timeout);
compression is taken as working when the backend's Set Compression was consumed by Conduit's
backend login and the session carried on; Configuration when the session passed through that state.

**Legend.** PASS: observed. FAIL: observed not to work. PARTIAL: part of the item observed. N/A: the
release has no such thing (no compression before 1.8, no Configuration phase before 1.20.2, no
command tree before 1.13, no brand line on the F3 screen before 1.13). BLOCKED: nothing to test
against (no Paper build with Velocity support for that release). UNSUPPORTED: Conduit does not
implement it. UNTESTED: not run.

OVERALL STATUS covers the DIRECT checklist and the forwarding modes Conduit implements (none and
modern). Legacy (BungeeCord IP forwarding) and BungeeGuard are not implemented by Conduit for any
release: `Forwarders.create` handles `none` and `modern` only, and Conduit configured with either
refuses to start. The configuration is now rejected as it loads (`forwarding.mode must be none or
modern: Conduit does not implement legacy forwarding`); the runs recorded under
`logs-forwarding/startup-*` predate that and show `java.lang.UnsupportedOperationException: forwarding
mode legacy is not implemented`, and the same for `bungeeguard`.

<!-- DIRECT TABLES -->

### Every version, every checklist item

| VERSION | PROTOCOL | CLIENT | SERVER | DIRECT JOIN | LOGIN | COMPRESSION | CONFIGURATION | WORLD LOAD | KEEP ALIVE | CHAT | MOVEMENT | BLOCK BREAK | BLOCK PLACE | ENTITY INTERACTION | INVENTORY | COMMAND TREE | /server | /conduit | TAB COMPLETION | SERVER SWITCH | BRANDING | FORWARDING NONE | MODERN FORWARDING | LEGACY FORWARDING | BUNGEEGUARD | OVERALL STATUS |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| 1.7.6 | 5 | vanilla 1.7.6 (offline launch) | vanilla 1.7.6 x2 | PASS | PASS | N/A | N/A | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS | N/A | PASS | PASS | PASS | PASS | N/A | PASS | N/A | UNSUPPORTED | UNSUPPORTED | VERIFIED |
| 1.8.9 | 47 | vanilla 1.8.9 (offline launch) | vanilla 1.8.9 x2 | PASS | PASS | PASS | N/A | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS | N/A | PASS | PASS | PASS | PASS | N/A | PASS | N/A | UNSUPPORTED | UNSUPPORTED | VERIFIED |
| 1.12.2 | 340 | vanilla 1.12.2 (offline launch) | vanilla 1.12.2 x2 | PASS | PASS | PASS | N/A | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS | N/A | PASS | PASS | PASS | PASS | N/A | PASS | N/A | UNSUPPORTED | UNSUPPORTED | VERIFIED |
| 1.13 | 393 | vanilla 1.13 (offline launch) | vanilla 1.13 x2 | PASS | PASS | PASS | N/A | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS | BLOCKED | UNSUPPORTED | UNSUPPORTED | VERIFIED |
| 1.13.1 | 401 | vanilla 1.13.1 (offline launch) | vanilla 1.13.1 x2 | PASS | PASS | PASS | N/A | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS | UNSUPPORTED | UNSUPPORTED | VERIFIED |
| 1.13.2 | 404 | vanilla 1.13.2 (offline launch) | vanilla 1.13.2 x2 | PASS | PASS | PASS | N/A | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS | UNSUPPORTED | UNSUPPORTED | VERIFIED |
| 1.14 | 477 | vanilla 1.14 (offline launch, Direct Connect) | vanilla 1.14 x2 | PASS | PASS | PASS | N/A | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS | UNSUPPORTED | UNSUPPORTED | VERIFIED |
| 1.14.1 | 480 | vanilla 1.14.1 (offline launch, Direct Connect) | vanilla 1.14.1 x2 | PASS | PASS | PASS | N/A | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS | UNSUPPORTED | UNSUPPORTED | VERIFIED |
| 1.14.2 | 485 | vanilla 1.14.2 (offline launch, Direct Connect) | n/a (refused before any backend) | UNSUPPORTED | UNSUPPORTED | UNSUPPORTED | UNSUPPORTED | UNSUPPORTED | UNSUPPORTED | UNSUPPORTED | UNSUPPORTED | UNSUPPORTED | UNSUPPORTED | UNSUPPORTED | UNSUPPORTED | UNSUPPORTED | UNSUPPORTED | UNSUPPORTED | UNSUPPORTED | UNSUPPORTED | UNSUPPORTED | UNSUPPORTED | UNSUPPORTED | UNSUPPORTED | UNSUPPORTED | UNSUPPORTED BY CONDUIT |
| 1.14.3 | 490 | vanilla 1.14.3 (offline launch, Direct Connect) | vanilla 1.14.3 x2 | PASS | PASS | PASS | N/A | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS | UNSUPPORTED | UNSUPPORTED | VERIFIED |
| 1.14.4 | 498 | vanilla 1.14.4 (offline launch, Direct Connect) | vanilla 1.14.4 x2 | PASS | PASS | PASS | N/A | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS | UNSUPPORTED | UNSUPPORTED | VERIFIED |
| 1.15 | 573 | vanilla 1.15 (offline launch, Direct Connect) | vanilla 1.15 x2 | PASS | PASS | PASS | N/A | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS | UNSUPPORTED | UNSUPPORTED | VERIFIED |
| 1.15.1 | 575 | vanilla 1.15.1 (offline launch, Direct Connect) | vanilla 1.15.1 x2 | PASS | PASS | PASS | N/A | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS | UNSUPPORTED | UNSUPPORTED | VERIFIED |
| 1.15.2 | 578 | vanilla 1.15.2 (offline launch, Direct Connect) | vanilla 1.15.2 x2 | PASS | PASS | PASS | N/A | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS | UNSUPPORTED | UNSUPPORTED | VERIFIED |
| 1.16 | 735 | vanilla 1.16 (offline launch) | vanilla 1.16 x2 | PASS | PASS | PASS | N/A | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS | BLOCKED | UNSUPPORTED | UNSUPPORTED | VERIFIED |
| 1.16.1 | 736 | vanilla 1.16.1 (offline launch, Direct Connect) | vanilla 1.16.1 x2 | PASS | PASS | PASS | N/A | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS | UNSUPPORTED | UNSUPPORTED | VERIFIED |
| 1.16.2 | 751 | vanilla 1.16.2 (offline launch) | vanilla 1.16.2 x2 | PASS | PASS | PASS | N/A | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS | UNSUPPORTED | UNSUPPORTED | VERIFIED |
| 1.16.3 | 753 | vanilla 1.16.3 (offline launch) | vanilla 1.16.3 x2 | PASS | PASS | PASS | N/A | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS | UNSUPPORTED | UNSUPPORTED | VERIFIED |
| 1.16.5 | 754 | vanilla 1.16.5 (Modrinth launch, Mojang-authenticated account) | vanilla 1.16.5 x2 | PASS | PASS | PASS | N/A | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS | UNSUPPORTED | UNSUPPORTED | VERIFIED |
| 1.17 | 755 | vanilla 1.17 (offline launch, Direct Connect) | vanilla 1.17 x2 | PASS | PASS | PASS | N/A | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS | UNSUPPORTED | UNSUPPORTED | VERIFIED |
| 1.17.1 | 756 | vanilla 1.17.1 (offline launch) | vanilla 1.17.1 x2 | PASS | PASS | PASS | N/A | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS | UNSUPPORTED | UNSUPPORTED | VERIFIED |
| 1.18 | 757 | vanilla 1.18 (offline launch) | vanilla 1.18 x2 | PASS | PASS | PASS | N/A | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS | UNSUPPORTED | UNSUPPORTED | VERIFIED |
| 1.18.2 | 758 | vanilla 1.18.2 (offline launch) | vanilla 1.18.2 x2 | PASS | PASS | PASS | N/A | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS | UNSUPPORTED | UNSUPPORTED | VERIFIED |
| 1.19 | 759 | vanilla 1.19 (offline launch) | vanilla 1.19 x2 | PASS | PASS | PASS | N/A | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS | UNSUPPORTED | UNSUPPORTED | VERIFIED |
| 1.19.2 | 760 | vanilla 1.19.2 (offline launch) | vanilla 1.19.2 x2 | PASS | PASS | PASS | N/A | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS | UNSUPPORTED | UNSUPPORTED | VERIFIED |
| 1.19.3 | 761 | vanilla 1.19.3 (offline launch) | vanilla 1.19.3 x2 | PASS | PASS | PASS | N/A | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS | UNSUPPORTED | UNSUPPORTED | VERIFIED |
| 1.19.4 | 762 | vanilla 1.19.4 (offline launch) | vanilla 1.19.4 x2 | PASS | PASS | PASS | N/A | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS | UNSUPPORTED | UNSUPPORTED | VERIFIED |
| 1.20.1 | 763 | vanilla 1.20.1 (offline launch) | vanilla 1.20.1 x2 | PASS | PASS | PASS | N/A | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS | UNSUPPORTED | UNSUPPORTED | VERIFIED |
| 1.20.2 | 764 | vanilla 1.20.2 (offline launch) | vanilla 1.20.2 x2 | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS | UNSUPPORTED | UNSUPPORTED | VERIFIED |
| 1.20.4 | 765 | vanilla 1.20.4 (offline launch) | vanilla 1.20.4 x2 | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS | UNSUPPORTED | UNSUPPORTED | VERIFIED |
| 1.20.5 | 766 | vanilla 1.20.5 (offline launch) | vanilla 1.20.5 x2 | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS | UNSUPPORTED | UNSUPPORTED | VERIFIED |
| 1.21 | 767 | vanilla 1.21 (offline launch) | vanilla 1.21 x2 | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS | UNSUPPORTED | UNSUPPORTED | VERIFIED |
| 1.21.3 | 768 | vanilla 1.21.3 (offline launch) | vanilla 1.21.3 x2 | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS | UNSUPPORTED | UNSUPPORTED | VERIFIED |
| 1.21.4 | 769 | vanilla 1.21.4 (offline launch) | vanilla 1.21.4 x2 | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS | UNSUPPORTED | UNSUPPORTED | VERIFIED |
| 1.21.5 | 770 | vanilla 1.21.5 (offline launch) | vanilla 1.21.5 x2 | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS | UNSUPPORTED | UNSUPPORTED | VERIFIED |
| 1.21.6 | 771 | vanilla 1.21.6 (offline launch) | vanilla 1.21.6 x2 | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS | UNSUPPORTED | UNSUPPORTED | VERIFIED |
| 1.21.8 | 772 | vanilla 1.21.8 (offline launch) | vanilla 1.21.8 x2 | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS | UNSUPPORTED | UNSUPPORTED | VERIFIED |
| 1.21.10 | 773 | vanilla 1.21.10 (offline launch) | vanilla 1.21.10 x2 | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS | UNSUPPORTED | UNSUPPORTED | VERIFIED |
| 1.21.11 | 774 | vanilla 1.21.11 (offline launch) | vanilla 1.21.11 x2 | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS | UNSUPPORTED | UNSUPPORTED | VERIFIED |
| 26.1 | 775 | vanilla 26.1 (offline launch) | vanilla 26.1 x2 | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS | UNSUPPORTED | UNSUPPORTED | VERIFIED |
| 26.2 | 776 | vanilla 26.2 (offline launch) | vanilla 26.2 x2 | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS | UNSUPPORTED | UNSUPPORTED | VERIFIED |

### Commands, per version

| VERSION | COMMAND TREE | /server | /conduit | TAB COMPLETION | SERVER SWITCH | RESULT |
|---|---|---|---|---|---|---|
| 1.7.6 | N/A | PASS | PASS | PASS | PASS | VERIFIED |
| 1.8.9 | N/A | PASS | PASS | PASS | PASS | VERIFIED |
| 1.12.2 | N/A | PASS | PASS | PASS | PASS | VERIFIED |
| 1.13 | PASS | PASS | PASS | PASS | PASS | VERIFIED |
| 1.13.1 | PASS | PASS | PASS | PASS | PASS | VERIFIED |
| 1.13.2 | PASS | PASS | PASS | PASS | PASS | VERIFIED |
| 1.14 | PASS | PASS | PASS | PASS | PASS | VERIFIED |
| 1.14.1 | PASS | PASS | PASS | PASS | PASS | VERIFIED |
| 1.14.3 | PASS | PASS | PASS | PASS | PASS | VERIFIED |
| 1.14.4 | PASS | PASS | PASS | PASS | PASS | VERIFIED |
| 1.15 | PASS | PASS | PASS | PASS | PASS | VERIFIED |
| 1.15.1 | PASS | PASS | PASS | PASS | PASS | VERIFIED |
| 1.15.2 | PASS | PASS | PASS | PASS | PASS | VERIFIED |
| 1.16 | PASS | PASS | PASS | PASS | PASS | VERIFIED |
| 1.16.1 | PASS | PASS | PASS | PASS | PASS | VERIFIED |
| 1.16.2 | PASS | PASS | PASS | PASS | PASS | VERIFIED |
| 1.16.3 | PASS | PASS | PASS | PASS | PASS | VERIFIED |
| 1.16.5 | PASS | PASS | PASS | PASS | PASS | VERIFIED |
| 1.17 | PASS | PASS | PASS | PASS | PASS | VERIFIED |
| 1.17.1 | PASS | PASS | PASS | PASS | PASS | VERIFIED |
| 1.18 | PASS | PASS | PASS | PASS | PASS | VERIFIED |
| 1.18.2 | PASS | PASS | PASS | PASS | PASS | VERIFIED |
| 1.19 | PASS | PASS | PASS | PASS | PASS | VERIFIED |
| 1.19.2 | PASS | PASS | PASS | PASS | PASS | VERIFIED |
| 1.19.3 | PASS | PASS | PASS | PASS | PASS | VERIFIED |
| 1.19.4 | PASS | PASS | PASS | PASS | PASS | VERIFIED |
| 1.20.1 | PASS | PASS | PASS | PASS | PASS | VERIFIED |
| 1.20.2 | PASS | PASS | PASS | PASS | PASS | VERIFIED |
| 1.20.4 | PASS | PASS | PASS | PASS | PASS | VERIFIED |
| 1.20.5 | PASS | PASS | PASS | PASS | PASS | VERIFIED |
| 1.21 | PASS | PASS | PASS | PASS | PASS | VERIFIED |
| 1.21.3 | PASS | PASS | PASS | PASS | PASS | VERIFIED |
| 1.21.4 | PASS | PASS | PASS | PASS | PASS | VERIFIED |
| 1.21.5 | PASS | PASS | PASS | PASS | PASS | VERIFIED |
| 1.21.6 | PASS | PASS | PASS | PASS | PASS | VERIFIED |
| 1.21.8 | PASS | PASS | PASS | PASS | PASS | VERIFIED |
| 1.21.10 | PASS | PASS | PASS | PASS | PASS | VERIFIED |
| 1.21.11 | PASS | PASS | PASS | PASS | PASS | VERIFIED |
| 26.1 | PASS | PASS | PASS | PASS | PASS | VERIFIED |
| 26.2 | PASS | PASS | PASS | PASS | PASS | VERIFIED |

### Login and compression, per version

| VERSION | PROTOCOL | BACKEND SESSIONS | BACKEND SET COMPRESSION | BACKEND LOGIN SUCCESS | CLIENT'S FIRST PACKET | CLIENT LOGIN ACK / CONFIGURATION |
|---|---|---|---|---|---|---|
| 1.7.6 | 5 | 3 | none (1.7 has no compression) | 3 of 3 | id 0x02 (43 bytes, uncompressed) | N/A (no Configuration phase) |
| 1.8.9 | 47 | 3 | 3 of 3, packet length ['3'] (threshold 256) | 3 of 3 | id 0x02 (43 bytes, uncompressed) | N/A (no Configuration phase) |
| 1.12.2 | 340 | 3 | 3 of 3, packet length ['3'] (threshold 256) | 3 of 3 | id 0x02 (44 bytes, uncompressed) | N/A (no Configuration phase) |
| 1.13 | 393 | 3 | 3 of 3, packet length ['3'] (threshold 256) | 3 of 3 | id 0x02 (43 bytes, uncompressed) | N/A (no Configuration phase) |
| 1.13.1 | 401 | 3 | 3 of 3, packet length ['3'] (threshold 256) | 3 of 3 | id 0x02 (44 bytes, uncompressed) | N/A (no Configuration phase) |
| 1.13.2 | 404 | 3 | 3 of 3, packet length ['3'] (threshold 256) | 3 of 3 | id 0x02 (44 bytes, uncompressed) | N/A (no Configuration phase) |
| 1.14 | 477 | 3 | 3 of 3, packet length ['3'] (threshold 256) | 3 of 3 | id 0x02 (43 bytes, uncompressed) | N/A (no Configuration phase) |
| 1.14.1 | 480 | 3 | 3 of 3, packet length ['3'] (threshold 256) | 3 of 3 | id 0x02 (44 bytes, uncompressed) | N/A (no Configuration phase) |
| 1.14.3 | 490 | 3 | 3 of 3, packet length ['3'] (threshold 256) | 3 of 3 | id 0x02 (44 bytes, uncompressed) | N/A (no Configuration phase) |
| 1.14.4 | 498 | 3 | 3 of 3, packet length ['3'] (threshold 256) | 3 of 3 | id 0x02 (44 bytes, uncompressed) | N/A (no Configuration phase) |
| 1.15 | 573 | 3 | 3 of 3, packet length ['3'] (threshold 256) | 3 of 3 | id 0x02 (43 bytes, uncompressed) | N/A (no Configuration phase) |
| 1.15.1 | 575 | 3 | 3 of 3, packet length ['3'] (threshold 256) | 3 of 3 | id 0x02 (44 bytes, uncompressed) | N/A (no Configuration phase) |
| 1.15.2 | 578 | 3 | 3 of 3, packet length ['3'] (threshold 256) | 3 of 3 | id 0x02 (44 bytes, uncompressed) | N/A (no Configuration phase) |
| 1.16 | 735 | 3 | 3 of 3, packet length ['3'] (threshold 256) | 3 of 3 | id 0x02 (22 bytes, uncompressed) | N/A (no Configuration phase) |
| 1.16.1 | 736 | 3 | 3 of 3, packet length ['3'] (threshold 256) | 3 of 3 | id 0x02 (23 bytes, uncompressed) | N/A (no Configuration phase) |
| 1.16.2 | 751 | 3 | 3 of 3, packet length ['3'] (threshold 256) | 3 of 3 | id 0x02 (23 bytes, uncompressed) | N/A (no Configuration phase) |
| 1.16.3 | 753 | 3 | 3 of 3, packet length ['3'] (threshold 256) | 3 of 3 | id 0x02 (23 bytes, uncompressed) | N/A (no Configuration phase) |
| 1.16.5 | 754 | 3 | 3 of 3, packet length ['3'] (threshold 256) | 3 of 3 | id 0x02 (30 bytes, uncompressed) | N/A (no Configuration phase) |
| 1.17 | 755 | 3 | 3 of 3, packet length ['3'] (threshold 256) | 3 of 3 | id 0x02 (22 bytes, uncompressed) | N/A (no Configuration phase) |
| 1.17.1 | 756 | 3 | 3 of 3, packet length ['3'] (threshold 256) | 3 of 3 | id 0x02 (23 bytes, uncompressed) | N/A (no Configuration phase) |
| 1.18 | 757 | 3 | 3 of 3, packet length ['3'] (threshold 256) | 3 of 3 | id 0x02 (22 bytes, uncompressed) | N/A (no Configuration phase) |
| 1.18.2 | 758 | 3 | 3 of 3, packet length ['3'] (threshold 256) | 3 of 3 | id 0x02 (23 bytes, uncompressed) | N/A (no Configuration phase) |
| 1.19 | 759 | 3 | 3 of 3, packet length ['3'] (threshold 256) | 3 of 3 | id 0x02 (23 bytes, uncompressed) | N/A (no Configuration phase) |
| 1.19.2 | 760 | 3 | 3 of 3, packet length ['3'] (threshold 256) | 3 of 3 | id 0x02 (24 bytes, uncompressed) | N/A (no Configuration phase) |
| 1.19.3 | 761 | 3 | 3 of 3, packet length ['3'] (threshold 256) | 3 of 3 | id 0x02 (24 bytes, uncompressed) | N/A (no Configuration phase) |
| 1.19.4 | 762 | 3 | 3 of 3, packet length ['3'] (threshold 256) | 3 of 3 | id 0x02 (24 bytes, uncompressed) | N/A (no Configuration phase) |
| 1.20.1 | 763 | 3 | 3 of 3, packet length ['3'] (threshold 256) | 3 of 3 | id 0x02 (24 bytes, uncompressed) | N/A (no Configuration phase) |
| 1.20.2 | 764 | 3 | 3 of 3, packet length ['3'] (threshold 256) | 3 of 3 | id 0x02 (24 bytes, uncompressed) | CONFIGURATION id=0x3/1, CONFIGURATION id=0x1/25, CONFIGURATION id=0x0/14, CONFIGURATION id=0x2/1 |
| 1.20.4 | 765 | 3 | 3 of 3, packet length ['3'] (threshold 256) | 3 of 3 | id 0x02 (24 bytes, uncompressed) | CONFIGURATION id=0x3/1, CONFIGURATION id=0x1/25, CONFIGURATION id=0x0/14, CONFIGURATION id=0x2/1 |
| 1.20.5 | 766 | 3 | 3 of 3, packet length ['3'] (threshold 256) | 3 of 3 | id 0x02 (25 bytes, uncompressed) | CONFIGURATION id=0x3/1, CONFIGURATION id=0x2/25, CONFIGURATION id=0x0/14, CONFIGURATION id=0x7/24 |
| 1.21 | 767 | 3 | 3 of 3, packet length ['3'] (threshold 256) | 3 of 3 | id 0x02 (24 bytes, uncompressed) | CONFIGURATION id=0x3/1, CONFIGURATION id=0x2/25, CONFIGURATION id=0x0/14, CONFIGURATION id=0x7/22 |
| 1.21.3 | 768 | 3 | 3 of 3, packet length ['3'] (threshold 256) | 3 of 3 | id 0x02 (24 bytes, uncompressed) | CONFIGURATION id=0x3/1, CONFIGURATION id=0x2/25, CONFIGURATION id=0x0/15, CONFIGURATION id=0x7/24 |
| 1.21.4 | 769 | 3 | 3 of 3, packet length ['3'] (threshold 256) | 3 of 3 | id 0x02 (24 bytes, uncompressed) | CONFIGURATION id=0x3/1, CONFIGURATION id=0x2/25, CONFIGURATION id=0x0/15, CONFIGURATION id=0x7/24 |
| 1.21.5 | 770 | 3 | 3 of 3, packet length ['3'] (threshold 256) | 3 of 3 | id 0x02 (24 bytes, uncompressed) | CONFIGURATION id=0x3/1, CONFIGURATION id=0x2/25, CONFIGURATION id=0x0/15, CONFIGURATION id=0x7/24 |
| 1.21.6 | 771 | 3 | 3 of 3, packet length ['3'] (threshold 256) | 3 of 3 | id 0x02 (24 bytes, uncompressed) | CONFIGURATION id=0x3/1, CONFIGURATION id=0x2/25, CONFIGURATION id=0x0/15, CONFIGURATION id=0x7/24 |
| 1.21.8 | 772 | 3 | 3 of 3, packet length ['3'] (threshold 256) | 3 of 3 | id 0x02 (24 bytes, uncompressed) | CONFIGURATION id=0x3/1, CONFIGURATION id=0x2/25, CONFIGURATION id=0x0/15, CONFIGURATION id=0x7/24 |
| 1.21.10 | 773 | 3 | 3 of 3, packet length ['3'] (threshold 256) | 3 of 3 | id 0x02 (25 bytes, uncompressed) | CONFIGURATION id=0x3/1, CONFIGURATION id=0x2/25, CONFIGURATION id=0x0/15, CONFIGURATION id=0x7/25 |
| 1.21.11 | 774 | 3 | 3 of 3, packet length ['3'] (threshold 256) | 3 of 3 | id 0x02 (25 bytes, uncompressed) | CONFIGURATION id=0x3/1, CONFIGURATION id=0x2/25, CONFIGURATION id=0x0/15, CONFIGURATION id=0x7/25 |
| 26.1 | 775 | 3 | 3 of 3, packet length ['3'] (threshold 256) | 3 of 3 | id 0x02 (23 bytes, uncompressed) | CONFIGURATION id=0x3/1, CONFIGURATION id=0x2/25, CONFIGURATION id=0x0/15, CONFIGURATION id=0x7/22 |
| 26.2 | 776 | 3 | 3 of 3, packet length ['3'] (threshold 256) | 3 of 3 | id 0x02 (39 bytes, uncompressed) | CONFIGURATION id=0x3/1, CONFIGURATION id=0x2/25, CONFIGURATION id=0x0/15, CONFIGURATION id=0x7/22 |

### The runs behind the table

- **1.7.6** (5): DIRECT, forwarding none `1.7.6-none-offline-20260917-135232`
- **1.8.9** (47): DIRECT, forwarding none `1.8.9-none-offline-20260917-135759`
- **1.12.2** (340): DIRECT, forwarding none `1.12.2-none-offline-20260917-140303`
- **1.13** (393): DIRECT, forwarding none `1.13-none-offline-20260917-140811`; BLOCKED: Paper 1.13 has no Velocity (modern forwarding) support
- **1.13.1** (401): DIRECT, forwarding none `1.13.1-none-offline-20260917-141328`; modern forwarding `1.13.1-modern-offline-20260917-111946` (build df43d90)
- **1.13.2** (404): DIRECT, forwarding none `1.13.2-none-offline-20260917-141846`; modern forwarding `1.13.2-modern-offline-20260917-112305`
- **1.14** (477): DIRECT, forwarding none `1.14-none-offline-20260917-142405`; modern forwarding `1.14-modern-offline-20260917-112626`
- **1.14.1** (480): DIRECT, forwarding none `1.14.1-none-offline-20260917-143045`; modern forwarding `1.14.1-modern-offline-20260917-113102`
- **1.14.2** (485): unsupported-protocol run `1.14.2-unsupported-20260917-104002`: protocol 485 has no codec; server list shows the entry as incompatible, labelled "Conduit 1.7.10" (the status exchange falls back to the oldest table, protocol 5); joining shows "Failed to connect to the server / Unsupported Minecraft version."; Conduit logs "Connection closed: unsupported Minecraft protocol: 485"
- **1.14.3** (490): DIRECT, forwarding none `1.14.3-none-offline-20260917-143723`; modern forwarding `1.14.3-modern-offline-20260917-113536`
- **1.14.4** (498): DIRECT, forwarding none `1.14.4-none-offline-20260917-144352`; modern forwarding `1.14.4-modern-offline-20260917-114008`
- **1.15** (573): DIRECT, forwarding none `1.15-none-offline-20260917-145020`; modern forwarding `1.15-modern-offline-20260917-114439`
- **1.15.1** (575): DIRECT, forwarding none `1.15.1-none-offline-20260917-145648`; modern forwarding `1.15.1-modern-offline-20260917-114911`
- **1.15.2** (578): DIRECT, forwarding none `1.15.2-none-offline-20260917-150315`; modern forwarding `1.15.2-modern-offline-20260917-115342`
- **1.16** (735): DIRECT, forwarding none `1.16-none-offline-20260917-150942`; BLOCKED: no Paper build for 1.16
- **1.16.1** (736): DIRECT, forwarding none `1.16.1-none-offline-20260917-151614`; modern forwarding `1.16.1-modern-offline-20260917-115813`
- **1.16.2** (751): DIRECT, forwarding none `1.16.2-none-offline-20260917-152247`; modern forwarding `1.16.2-modern-offline-20260917-120247`
- **1.16.3** (753): DIRECT, forwarding none `1.16.3-none-offline-20260917-152924`; modern forwarding `1.16.3-modern-offline-20260917-120723`
- **1.16.5** (754): DIRECT, forwarding none `1.16.5-none-online-20260917-132929`; modern forwarding `1.16.5-modern-online-20260917-133512`; the only modern-forwarding row with a Mojang-authenticated player: Paper 1.16.5 with velocity-support online-mode true accepted the forwarded profile (Authenticated=true, signed textures), answered in forwarding version 1
- **1.17** (755): DIRECT, forwarding none `1.17-none-offline-20260917-153607`; modern forwarding `1.17-modern-offline-20260917-121157`
- **1.17.1** (756): DIRECT, forwarding none `1.17.1-none-offline-20260917-154257`; modern forwarding `1.17.1-modern-offline-20260917-121650`
- **1.18** (757): DIRECT, forwarding none `1.18-none-offline-20260917-154842`; modern forwarding `1.18-modern-offline-20260917-122020`
- **1.18.2** (758): DIRECT, forwarding none `1.18.2-none-offline-20260917-155440`; modern forwarding `1.18.2-modern-offline-20260917-122404`
- **1.19** (759): DIRECT, forwarding none `1.19-none-offline-20260917-160038`; modern forwarding `1.19-modern-offline-20260917-133848`
- **1.19.2** (760): DIRECT, forwarding none `1.19.2-none-offline-20260917-160617`; modern forwarding `1.19.2-modern-offline-20260917-180325`
- **1.19.3** (761): DIRECT, forwarding none `1.19.3-none-offline-20260917-175648`; modern forwarding `1.19.3-modern-offline-20260917-180805`
- **1.19.4** (762): DIRECT, forwarding none `1.19.4-none-offline-20260917-161752`; modern forwarding `1.19.4-modern-offline-20260917-124204`
- **1.20.1** (763): DIRECT, forwarding none `1.20.1-none-offline-20260917-162314`; modern forwarding `1.20.1-modern-offline-20260917-124525`
- **1.20.2** (764): DIRECT, forwarding none `1.20.2-none-offline-20260917-162836`; modern forwarding `1.20.2-modern-offline-20260917-124845`; failed before its fix `1.20.2-none-offline-20260917-083845` (build fda9d91)
- **1.20.4** (765): DIRECT, forwarding none `1.20.4-none-offline-20260917-163358`; modern forwarding `1.20.4-modern-offline-20260917-104133` (build e8665f3)
- **1.20.5** (766): DIRECT, forwarding none `1.20.5-none-offline-20260917-163928`; modern forwarding `1.20.5-modern-offline-20260917-125205`
- **1.21** (767): DIRECT, forwarding none `1.21-none-offline-20260917-164449`; modern forwarding `1.21-modern-offline-20260917-125525`
- **1.21.3** (768): DIRECT, forwarding none `1.21.3-none-offline-20260917-165011`; modern forwarding `1.21.3-modern-offline-20260917-125847`; failed before its fix `1.21.3-none-offline-20260917-091931` (build 801890c)
- **1.21.4** (769): DIRECT, forwarding none `1.21.4-none-offline-20260917-165534`; modern forwarding `1.21.4-modern-offline-20260917-130207`
- **1.21.5** (770): DIRECT, forwarding none `1.21.5-none-offline-20260917-170055`; modern forwarding `1.21.5-modern-offline-20260917-130529`
- **1.21.6** (771): DIRECT, forwarding none `1.21.6-none-offline-20260917-170618`; modern forwarding `1.21.6-modern-offline-20260917-130853`
- **1.21.8** (772): DIRECT, forwarding none `1.21.8-none-offline-20260917-171139`; modern forwarding `1.21.8-modern-offline-20260917-131216`
- **1.21.10** (773): DIRECT, forwarding none `1.21.10-none-offline-20260917-171700`; modern forwarding `1.21.10-modern-offline-20260917-131537`
- **1.21.11** (774): DIRECT, forwarding none `1.21.11-none-offline-20260917-172221`; modern forwarding `1.21.11-modern-offline-20260917-131900`
- **26.1** (775): DIRECT, forwarding none `26.1-none-offline-20260917-172743`; modern forwarding `26.1-modern-offline-20260917-132223`
- **26.2** (776): DIRECT, forwarding none `26.2-none-offline-20260917-173304`; modern forwarding `26.2-modern-offline-20260917-132545`

<!-- END DIRECT TABLES -->

### Conduit defects the matrix found, and their fixes

Each was reproduced with the real client first, traced to the first packet or state that went wrong,
fixed, locked by a regression test that fails without the fix, and re-run on the same release.

| Commit | Releases | What the real client showed | First wrong packet / state | Fix |
|---|---|---|---|---|
| `4d92d25` | 1.7.6, 1.8.9, 1.12.2 | disconnected on the first Tab press in chat | the client's Tab-Complete request read with 1.13's layout (text length taken as a transaction id) | read and answer Tab-Complete in the pre-1.13 layout; complete Conduit's own command names |
| `f34833a` | 1.7.6, 1.8.9, 1.12.2 | `/server` switch dropped by both backends ("Bad packet id 21", "Bad packet id 4") | client settings replayed to the new backend while it was still decoding Login | hold the switched client until the new backend's Join Game |
| `050ef9d` | 1.12.2 (through Via) | Tab turned `/server` into `/server /server` | a pending command-name completion merged into a later, unrelated Tab reply | merge names only into the reply to their own request |
| `e261623` | 1.16, 1.16.1 | "Loading terrain" forever after `/server` | no Respawn pair after the second Join Game (the 1.16.2+ pair did not cover 1.16/1.16.1) | world-key Respawn pair from each release's own Join Game layout |
| `5230ee8` | 1.17 - 1.20.1 | "Loading terrain" forever after `/server` | no Respawn pair for 1.17+; derived tables carried 1.16.5's Respawn id `0x39`, and 1.20.1 had none | the pair for every release 1.16 - 1.20.1; correct Respawn ids |
| `29fb173` | 1.19 - 1.20.1 | refused at login ("Timed out" on 1.19) | Login Start's optional signature / UUID fields rejected as extra bytes | read and write each release's Login Start layout |
| `fda9d91` | 1.19 - 1.19.4 | `/conduit` and `/server` answered by the backend: "Unknown or incomplete command" | Chat Command (no slash) not recognised; derived 1.19 tables inherited 1.13's legacy chat | 1.19 command chat capability; System Chat with the 1.19 chat type id |
| `801890c` | 1.20.2 | "Connection Lost: DecoderException … MalformedJsonException at line 1 column 1" on `/conduit`; `/server` broke the same way | Conduit's System Chat reply written as network NBT (compound `0x0A`); 1.20.2 still reads a JSON string | NBT text only from 1.20.3 (`ProtocolEras.textComponentNbt`), for System Chat and both Disconnects |
| `6e409ff` | 1.21.2, 1.21.3 | disconnected at once after joining: "player_info_update was larger than I expected, found 1 bytes extra" | Conduit's own ADD_PLAYER (id `0x40`, actions `0x9D`) carried the show-hat action and byte, which came with 1.21.4 | hat action only from 1.21.4 (`ProtocolEras.playerInfoHat`) |
| `e8665f3` | 1.20.2+ with forwarding none | the player listed twice in the tab list, the copy with no latency | Conduit's own ADD_PLAYER used the Login Start UUID while the client's Login Success carried the backend's | the entry takes the UUID from the Login Success the client received |

### Harness notes (not Conduit defects)

- **1.14 - 1.16.5, 1.17** are joined through Direct Connect after the client has loaded: launched with
  `--server`, 1.14 - 1.16.x crash tesselating their first chunk while resources still load (also with
  no proxy), and 1.17 crashes rendering the connect screen.
- **1.16.4 / 1.16.5** offline launches disable Multiplayer (account check); 1.16.5 was run with a real
  Microsoft account launched by the Modrinth App.
- **1.14 / 1.14.1**: the vanilla server runs RCON commands off the server thread, where block entities
  are invisible (reproduced on a bare 1.14 server: `setblock`/`fill` chests come out empty and `data get
  block` finds no block entity). The container check there places a chest item carrying the diamonds
  from the client and counts the diamonds on the player afterwards.
- **1.7.6**: no rotation in `/tp` and no position/entity queries over RCON. The player's saved rotation
  aims the client (up on A for break/place, down on B for entity/container), in creative so the saved
  position cannot kill it; the entity hit is evidenced by the server's Entity Status (hurt, then dead)
  for the pig in the clientbound dump, after the client's Use Entity.
- A 1.7.6 run where the player arrived dead on B (death screen took all input, so `/server direct` was
  never typed) and a run whose break/place aim missed a single block were harness failures, fixed in
  `set-rotation.py` and `probe-direct.ps1` and re-run.
- **1.19.2 and 1.19.3 are joined through Direct Connect as well.** Launched with `--server`, those
  clients spend 21 - 23 s in their datafixer pass ("4283 Datafixer optimizations took 21024
  milliseconds") while the connect screen counts its own 30-second login budget, and abandon the join
  before the world arrives — the backend logs the player in and then a plain "lost connection:
  Disconnected" about 30 s after the client started connecting, with no error on either side and the
  clientbound bytes already sent (319 KB on one run). Conduit is not in that decision: the same client
  and the same build pass on the re-run, and 1.19 won the same race on the first try. Three runs were
  redone this way (`1.19.3-none-offline-...-175648`, `1.19.2-modern-...-180325`,
  `1.19.3-modern-...-180805`) and all three passed.
- The 26.2 client and server log `Unable to locate English counter names in registry Perflib 009` and
  a JNA `Win32Exception`, from oshi reading Windows performance counters on this machine. It has
  nothing to do with the proxy; the run passed around it.

### Forwarding and authentication

- **none**: every DIRECT row. Backends in offline mode; Conduit completes the backend login itself
  (Set Compression and Login Success consumed, see the login table) and the backend names the player
  by its own offline UUID.
- **modern** (Velocity `velocity:player_info`): backend A is Paper with Velocity support enabled and
  the shared secret, backend B vanilla. A row passes only when the Paper log shows the player placed in
  the world and Conduit's trace shows it answered Paper's forwarding request both at the join and on
  `/server direct` back to Paper. Runs use `-Quick`: no gameplay probe or suggestion screens on A (those
  are what the DIRECT row covers); chat, `/conduit`, `/server`, both switches, the hold and the
  Disconnect remain. Paper 1.13 has no Velocity support (BLOCKED) and there is no Paper build for 1.16
  (BLOCKED); before 1.13 there is no login plugin message, so modern forwarding does not apply (N/A).
- **legacy** and **bungeeguard**: UNSUPPORTED BY CONDUIT for every release (startup refusal, above).
- **Authentication**: every row but 1.16.5 runs with `[authentication] mode = "offline"` and a harness
  client launched offline. **1.16.5 is the authenticated pair**, both DIRECT and modern, run with
  `mode = "online"` and a real Microsoft account launched by the Modrinth App, because an offline
  1.16.5 launch has Multiplayer disabled by the client's own account check. In those runs Conduit sent
  the Encryption Request, enabled client encryption, accepted the Mojang session, and — for the modern
  row — forwarded the authenticated profile to a Paper 1.16.5 with `velocity-support.online-mode: true`,
  which accepted it (`Authenticated=true`, one signed `textures` property, answered in forwarding
  version 1). The account's name, UUID and tokens are not recorded here, the run result stores the
  player as "(launcher account)", and the harness never reads or stores the launcher's process
  command line.

### What the forwarding matrix found

| Commit | Backends | What the backend did | Fix |
|---|---|---|---|
| `df43d90` | Paper 1.13.1 | sent `velocity:player_info` with no version byte; Conduit refused it ("malformed modern forwarding request payload") and the join fell back to the other server | an empty request is answered in forwarding version 1, the only version such a backend reads |
| `bab7da1` | Paper 1.19 (asks 2), Paper 1.19.2 (asks 3) | Conduit answered in the requested version but without the chat signing key those versions carry, and the backend read the key's expiry past the end: "IndexOutOfBoundsException: readerIndex(65) + length(8) exceeds writerIndex(65)" | requests for 2 or 3 are answered in version 1, which those backends accept |

`126c34f` came out of the same pass, from a warning rather than a failure: Conduit could not read a
1.13.2 server's Declare Recipes packet (44886 bytes, 524 recipes) because the repair read every slot
as 1.13's short id, and replaced it with an empty list, so every 1.13.2 client had an empty recipe
book. The DIRECT checklist passed around it, because crafting itself is the server's business.

### Harness changes made during the pass

- Join detection reads `"<name>[/address] logged in with entity id"` rather than "joined the game":
  Paper 1.13.x never prints the join message to its console, and a run that waited for it stopped a
  Paper the player had already joined.
- After the server's join line the run waits for the client's own `Loaded <n> advancements` before
  driving keys: a 1.19.3 client that auto-connected mid-bootstrap sat on "Connecting to the
  server..." and the first Escape cancelled the join.

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
1.13-26.2 range, plus three declared tables below it — 5 (1.7.6), 47 (1.8.9) and
340 (1.12.2) — which exist so those clients can be carried DIRECT and through
Via, and which the family enum still calls `LEGACY_OUT_OF_SCOPE`: 40 codecs in
all. Translation coverage: **8 ordered pairs**. The matrix is still mostly
`UNSUPPORTED`, and that is the accurate picture.

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
  relayed only during the first login.** A plugin can answer one itself
  (`BackendLoginPluginMessageEvent`). Otherwise, while the client is still
  logging in, the request goes to the client and its answer back to the backend
  (see Login plugin queries); on a switch or a fallback the client has no login
  phase left, so an unanswered request fails that connection. Of Forge-family
  real clients, only NeoForge 20.2.93's initial join is verified.

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
| 404 | 477 | TRANSLATED | PARTIAL | TRANSLATED_VERIFIED | real 1.13.2 client held a real 1.14 server through a gameplay burst; block states, items and entity types resolved by name; recipes/tags/advancements dropped |
| 477 | 404 | TRANSLATED | PARTIAL | TRANSLATED_VERIFIED | scripted 1.14 probe sustained play on a real 1.13.2 server; entity metadata aligned against measured per-entity layouts across 86 entity types |
| 765 | 393 | TRANSLATED | PARTIAL | TRANSLATED_VERIFIED | configuration synthesis; core gameplay verified |
| 765 | 766 | TRANSLATED | PARTIAL | TRANSLATED_PARTIAL | control/login/config; JoinGame/player-info/registry unsupported |
| 766 | 765 | TRANSLATED | PARTIAL | TRANSLATED_PARTIAL | control/login/config; JoinGame/player-info/registry unsupported |

protocols with codecs: 40; matrix cells: 1600; direct: 40; translated: 8; unsupported: 1552; of which partial: 39


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
python tools/gen_revisions.py         # regenerate ProtocolRevisions.java deltas -- DIFF IT, see below
python tools/mcartifacts.py fetch     # mirror + verify Minecraft jars
python tools/mcartifacts.py report    # regenerate artifacts/ARTIFACTS.md
```

`gen_revisions.py` is **not** currently idempotent against the committed
`ProtocolRevisions.java`, so run it only to compare: re-running it today rewrites
the file 75 lines shorter. Two reasons, both checked on 2026-09-17.

- `packetids.py`'s kind-to-published-name map does not resolve every kind
  Conduit knows (`packetids.py check` prints them as `unresolved`), so the
  regenerated deltas are missing mappings the committed table has — 25 of 1.14's
  alone.
- The Respawn ids for 1.16 through 1.20.1 were authored by hand in `5230ee8`
  after a real client sat on "Loading terrain" after `/server`; the generator does
  not know them, so regenerating reintroduces that bug.

Regeneration would also *add* the correct Respawn ids for 1.21 through 26.1,
which the derived tables inherit wrongly from 1.20.4 (`0x45`). Nothing reads them:
`LegacyWorldReload` runs only for clients up to protocol 763, and no other path
encodes Respawn for a modern client. Left as is rather than changed without a real
client that can show the difference.

## Forge and NeoForge

Most rows below are `OFFLINE/SCRIPTED`: `ModLoaderTests` drives Conduit's own
code — a socket client and socket backends through a real `MinecraftProxy`, and
the real `BackendLoginPipeline` on packets built from the 1.20.4 table.

One run is not. A **real NeoForge 20.2.93 client and a real NeoForge 20.2.93
server** (Minecraft 1.20.2, protocol 764, offline mode) were provisioned from
NeoForge's own headless installer and driven through Conduit, with a logging TCP
relay on both sides capturing the wire. Rows sourced from it are marked
`REAL-CLIENT`. That player now joins and plays: on the final build, 12 of 12
joins through Conduit reached the world and stayed connected. It took three
fixes, each recorded below: the handshake token, the login-query pump, and taking
Conduit's socket threads off a Windows JDK poller that loses wakeups.

Where the capture and the public documentation disagree, the capture wins, and
it disagrees twice.

### Handshake address tokens

A modded client appends a NUL-delimited token to the Server Address field of the
Handshake packet.

**The trailing NUL is not there.** Captured from the real client:

```
C>S 15 00 fc05 0e "127.0.0.1\0FML3" 63eb 02
```

Protocol 764, host length 14 = `127.0.0.1` + `\0` + `FML3`. One leading NUL, the
token, and the end of the string. The minecraft.wiki page documents the token as
`"\0FML2\0"`, and Conduit matched that doubly-delimited form, so a real NeoForge
handshake fell through to `IllegalArgumentException: malformed FML address marker
in handshake host`, `MinecraftProxy` counted it a malformed handshake, and the
socket closed **with nothing written back**. That was the whole failure: the
client sat on "Connecting to…" until it gave up, with no message anywhere.

`FmlAddressMarkers.parse` now splits the host on NUL and accepts the token with
or without a trailing delimiter. An unrecognised token is still refused, and
`ModLoaderTests` drives that too.

| Token | Status | Conduit |
|---|---|---|
| `\0FML` / `\0FML\0` | documented, Forge 1.7.2 - 1.12.2 | parsed, classified Forge, carried to the backend unchanged |
| `\0FML2` / `\0FML2\0` | documented, Forge 1.13 - 1.20.1 | same |
| `\0FML3` / `\0FML3\0` | **observed** from NeoForge 20.2.93 (MC 1.20.2) | same |
| `\0FORGE` / `\0FORGE\0` | documented only — **never seen on any wire here** | accepted; no client has been shown to send it |

Sources for the documented rows: minecraft.wiki's Forge Handshake page for FML
and FML2, Gate's `modernforge` package for FML3 and FORGE. Gate's note that FORGE
replaced FML2/FML3 for 1.20.2+ does not describe NeoForge 20.2.93, which sends
FML3 at Minecraft 1.20.2.

No token identifies NeoForge — 20.2.93 sends the FML3 a Forge client of that era
sends. Conduit classifies every token as Forge and lets the client's
`minecraft:brand` promote it to NeoForge, never the other way round.

Conduit never invents a token. `FmlAddressMarkers.markerFor` returns whatever the
client sent and nothing else: which token a loader uses is a property of the
client's version, not of its family, and a client that sent none is telling the
backend it speaks no FML handshake at all.

### Login plugin queries

A backend's login-phase query on a channel Conduit has no answer for is the
client's to answer. `BackendLoginPipeline` forwards it and carries the client's
`Login Plugin Response` back to the backend byte for byte, matched on the query's
message id. This is off unless the caller enables it, because only a connection
whose client is still in LOGIN can produce a response.

**NeoForge 1.20.2 still uses login queries, and pipelines them.** The claim that
1.20.2+ negotiates entirely in the Configuration phase does not hold for NeoForge
20.2.93. The backend-side capture, from Conduit's Login Start onwards:

```
30.592 C>S    47  handshake "127.0.0.1\0FML3" + Login Start
30.635 S>C   108  Login Plugin Request 0x00  fml:loginwrapper -> fml:handshake  (mod list)
30.686 S>C   651  Login Plugin Request 0x01  ... neoforge:split
30.736 S>C  1218  Login Plugin Request 0x02  ... minecraft:command_argument_type
30.785 S>C 53044  Login Plugin Request 0x03  ... minecraft:sound_event
  ...          (0x04 .. 0x13: particle_type, item, block, entity_type, menu, ...)
31.635 S>C  1312  Login Plugin Request 0x14  ... neoforge-server.toml
```

Twenty-one requests, about 130 KB, inside one second, **none of them waiting for
a reply**. The client's FML handler answers the batch, not each request in turn:
its log shows `Recieved login wrapper packet event for channel fml:handshake with
index 0` and then silence.

Conduit's login loop was strict request-then-response. It forwarded query
`0x00`, blocked reading the client for an answer that FML will not send on its
own, and the read deadline ended the session. That was a shape problem, not a
parsing one: while the client is in LOGIN, both directions have to be pumped
concurrently. `BackendLoginPipeline` holds the outstanding message ids (bounded
at 256) and settles them in any order. While a query is owed, a pump thread in
`PlayerSession` carries each client response to the backend as it arrives, and
the login loop keeps forwarding the backend's queries as they arrive. The pump
stops the moment the backend leaves LOGIN, before Login Success reaches the
client, so the client's Login Acknowledged is read by the login loop and nobody
else.

- **Initial join**: works, `REAL-CLIENT`, on NeoForge 20.2.93.
- **`/server` switch**: not possible, and it fails loudly rather than quietly.
  The player is already in Play on another backend and has no login phase left.
  Answering on their behalf means Conduit authoring a Forge mod-list reply, which
  it will not do. Forge 1.13 - 1.20.1 asks on `fml:loginwrapper` during login, so
  a switch onto such a backend aborts with `backend asked login plugin channel
  fml:loginwrapper with no client login phase to answer it` and the session falls
  back, unless a plugin answers the request (`BackendLoginPluginMessageEvent`).

### The join that stalled one time in two (Windows)

With the pump in place, the real NeoForge client joined, and then about half its
joins froze on "Loading terrain" until the server timed the player out: 6 of 17
through Conduit, 0 of 16 straight to the server. The client's last log line was
always NeoForge's `Injected NeoForgeConnectionNetworkFilter`, which turned out to
be a coincidence of timing, not a cause.

The client was not at fault. A debugger attached to the stalled client showed its
read state healthy (autoRead on, read interest registered, no packet held in any
decoder or in the flow-control queue) and **0 bytes waiting** in its socket's
receive buffer, twice, four seconds apart. It was starving. At the same moment
Conduit's backend reader was parked in a write to that client, in the JDK's
virtual-thread write poller, while the session's client reader was parked in the
read poller on the same socket. `jcmd <pid> Thread.vthread_pollers` showed the
socket registered once in each.

That is [JDK-8334574](https://bugs.openjdk.org/browse/JDK-8334574), open in
JDK 25 and 26 and not fixed in Corretto 25.0.4. On Windows the JDK parks a
virtual thread's socket read and its socket write through two separate wepoll
handles, and a readiness event can surface on the wrong handle, where it is
dropped. The write never learns that the socket drained. A standalone reproducer
with no Minecraft and no Conduit in it (one socket, a virtual-thread writer, a
virtual-thread reader, a slow peer) hung 13 of 17 runs on JDK 25.0.4. It hung 0
of 6 on JDK 21, which registers through a helper thread by default, and 3 of 5
on JDK 21 with `-Djdk.useDirectRegister=true`. No `jdk.pollerMode` avoids it on
Windows.

Nothing about it is modded-specific. Any session is exposed once a write has to
wait. NeoForge's uncompressed join burst simply made that happen on nearly
every join.

`network/SocketThreads` is the fix: on Windows, every thread Conduit starts that
may block on a socket is a platform thread (`conduit-io-N`), which blocks in the
operating system and never reaches that poller. Elsewhere they stay virtual. On
the fixed build: 0 of 12 stalls, and 0 of 2 behind a deliberately slow relay that
stalled the old build 3 of 3. `ConcurrencyTests` checks that a Windows session
runs on those threads. When a JDK with the fix ships, `SocketThreads` can go back
to virtual threads everywhere.

### Configuration-phase payloads

`OFFLINE/SCRIPTED`, and note the order of events: this was written expecting
1.20.2+ to negotiate here. The real NeoForge 20.2.93 run then showed it
negotiating in the login phase instead, so what follows says the relay is sound,
not that any loader uses it. `ModLoaderTests` drives a socket client with
`\0FORGE\0`, two socket backends, a real `MinecraftProxy`, one `/server` between
them. What holds:

- a backend's `neoforge:register` Configuration custom payload reaches the client
  byte for byte;
- the client's own `minecraft:register` Configuration payload reaches the backend
  byte for byte;
- `minecraft:brand` reaches the client (rewritten by `BrandRewriter`, as it is for
  every session — that one is not passthrough and is not meant to be);
- after `/server`, the new backend's payload reaches the reconfiguring client and
  the old backend's does not leak into the new Configuration phase;
- the switch handshake carries the client's `\0FORGE\0` to the new backend too.

Nothing about this is loader-specific: the relay is the ordinary plugin-message
path, which is why it needed no loader-specific work, unlike the login-query
range above.

One gap in the same path: while a session is SWITCHING, a client Configuration
packet is written straight to the new backend and skips `forwardPluginMessage`,
so its channel is not classified and no `PluginMessageEvent` fires for it. The
bytes are unaffected.

### Channel registration is carried across a switch

A client announces its plugin channels once, with `minecraft:register`, while it
first configures. Conduit did not remember them, so a backend reached by
`/server` was never told. In the switch above the new backend received no
`minecraft:register` at all. That was not modded-specific: it was every mod
channel and every plugin channel, on the second and every later backend a
player reached.

`modded/RegisteredChannels` now records register/unregister announcements,
bounded at 256 channels. The session replays them to every backend it switches
to, on the channel name the client itself used (`minecraft:register`, or
`REGISTER` before 1.13). A backend with a Configuration phase gets them there,
before the commit. One without gets them in Play, after it. `ModLoaderTests`
asserts that the new backend in the switch above receives exactly the channels
the client announced to the first.

### What is and is not covered

| Loader | Where it negotiates | Conduit |
|---|---|---|
| Forge 1.7.2 - 1.12.2 (FML1) | Play phase, `FML\|HS` plugin messages | token and channels classified; the messages relay as ordinary custom payloads. A switch needs a `HandshakeReset` (discriminator -2) on `FML\|HS` before the new server's handshake. `modded/FmlHandshakeReset` authors that packet (1.7's short-lengthed payload and 1.8+'s alike). Conduit sends it to an FML1 client on every switch, after the commit and before the queued packets. The packet is unit-tested; **the exchange that follows it has not been driven against a real Forge 1.7 - 1.12 client** |
| Forge 1.13 - 1.20.1 (FML2/FML3) | Login phase, `fml:loginwrapper` queries | initial join: queries relayed and pumped as above, `OFFLINE/SCRIPTED`. A switch onto such a backend is refused, since there is no login phase left to answer in |
| NeoForge 20.2.93 (MC 1.20.2) | **observed**: login phase, 21 pipelined `fml:loginwrapper` queries | joins and plays, `REAL-CLIENT`: 12 of 12 joins on the final build reached the world and stayed connected |
| Forge / NeoForge later than 1.20.2 | documentation says Configuration phase, ordinary custom payloads | untested against any real build. The configuration-phase relay below is scripted-only and loader-agnostic |
| Fabric | Configuration/Play custom payloads | channel classification only; nothing loader-specific is needed |

Mod-list synchronisation is not implemented for any loader. Conduit observes and
classifies; it does not parse, cache or replay a mod list, and it does not
negotiate registries. A backend switch between two modded servers therefore
carries no loader state across.

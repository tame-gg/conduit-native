# ViaVersion engine, 393 ↔ 765, real clients and real servers

Both directions of the pair, each with a real official Minecraft client, a real
official Minecraft server, and Conduit between them with
`translation.enabled = true` and `engine = "via-preferred"`. Nothing synthetic:
no stub client, no replayed capture, no fabricated packets.

| Direction | Status |
|---|---|
| 765 → 393 (modern client, old backend) | **TRANSLATED / VERIFIED** |
| 393 → 765 (old client, modern backend) | **TRANSLATED / VERIFIED** — after a Conduit-side recipe repair |

Runners: `work/real-client-validation/run-via-765-393.ps1` and
`run-via-393-765.ps1`. Conduit commits under test: direction A `d6a457c`,
direction B `5be7657`.

---

# Direction A — 765 → 393: VERIFIED

| Piece | Detail |
|---|---|
| Client | Official Minecraft **1.20.4**, protocol **765**, offline, `ViaTestXV` |
| Proxy | Conduit `127.0.0.1:25572`, `config/conduit-via-765-to-393.toml` |
| Backend | Official Minecraft **1.13** server, protocol **393**, port 25613 |
| Engine selected | **ViaVersion** |
| Via path | 26 protocols, `Protocol1_20_2To1_20_3` → … → `Protocol1_13To1_13_1` |
| Translation failures | **0** |
| Conduit warnings | **0** |
| Serverbound packets translated | **553**, 14 distinct Play packet types |
| Session | survived the full run; no timeout, no unexplained disconnect |

Verified in this run:

| Item | Evidence |
|---|---|
| Handshake | session established |
| Login (offline) | Login Success translated 393 → 765 by Via |
| Backend compression | handled by Conduit outside Via, as designed |
| Configuration | Via synthesised the phase the 1.13 backend does not have |
| Configuration → Play | client left Configuration and entered the world |
| World join | `ViaTestXV joined the game` on the 1.13 server |
| Chunks | terrain, water and structures rendered |
| Player position | movement packets translated throughout |
| Block interaction | Player Action and Use Item On translated serverbound |
| Entities, entity metadata | a hostile mob was rendered, fought and killed |
| Advancements | `ViaTestXV has made the advancement [Monster Hunter]` |
| Chat | messages round-tripped |
| Commands | `/conduit`, `/conduit servers` returned output through the proxy |
| Keepalive | no timeout across the run |
| Inventory, item stacks | hotbar held a stack of 64 |

Not exercised in this run, and therefore not claimed: online-mode encryption
(the run is offline), and clean client-initiated disconnect (the session was
torn down by the harness).

## Server switching, 765 → 393 then 765 → 404

`config/conduit-via-765-switch.toml` adds a second backend, a real 1.13.2 server
(protocol **404**) on port 25614, and the client issued `/server smp`. Topology
read from the configs, not from notes: lobby = real Minecraft **1.13** (393) on
25613, smp = real Minecraft **1.13.2** (404) on 25614, client = real **1.20.4**
(765).

**Status: PARTIAL.** Three faults were found here, each proven from the wire and
each hidden behind the one before it. All three are fixed. The switch now gets
much further and still does not complete, and the remaining stop is recorded
below rather than claimed as working.

### Fault 1 — Conduit wrote the client's dialect at the backend

The 1.13.2 backend closed the connection with

```
DecoderException: Packet 2/0 (of) was larger than I expected,
found 7 bytes extra whilst reading packet 0
```

The handshake and Login Start were **correct** — traced as
`handshakeProtocol=404 loginProtocol=404`, `loginStart = 00 09 "ViaSwitch"`,
username only, exactly what 1.13.2 defines. The earlier guess that the switch's
own Login Start was malformed was wrong.

The packet the backend actually choked on was the next one Conduit wrote:

```
backend-write smp:25614  00 05 65 6e 5f 75 73 0c 00 01 7f 01 00 01
```

That is `replayClientInformation` stamping the **client's** packet id onto the
**client's** body: 1.20.4 Configuration id `0x00`, a phase 1.13.2 does not have,
with a body carrying the two trailing booleans 1.20.4 added. Read as Login Start
it is `00`, the string `"en_us"`, and then `0c 00 01 7f 01 00 01` — **exactly the
seven bytes** the server reported.

Fixed by sending the replay through the session's translator, like every other
client packet, and by skipping it entirely for a backend that reaches Play in the
same breath as Login Success.

### Fault 2 — the translation was swapped while the old backend was still live

With fault 1 fixed, the **old** backend died instead:

```
Packet 0/23 (mz) was larger than I expected, found 16 bytes extra
```

Traced: `backend-write lobby:25613 len=26  17 c0 55 2d 39 …`. Packet `0x17` with
a 25-byte body is the 1.20.4 **Set Player Position**, delivered raw to a 1.13
server. The identical payload one line earlier in the run had gone out correctly
as `10 …`, the 1.13 id.

Preparing a switch replaced the session's translator, backend protocol and packet
table before the new backend existed, so every client packet in that window was
encoded for a backend it was not being written to. A translation now belongs to a
backend connection: built for the target, used only for that target's login, and
installed at the commit that stops the old backend receiving anything.

### Fault 3 — the switch-time translator never left Login

With both fixed, both backends disconnect cleanly and the switch fails with
`client did not finish configuration`. The trace showed why: the Via session
opened for the switch sat at `LOGIN/LOGIN` and passed the new backend's Join Game
through **untranslated**. A session opened for a first connection learns its
states by watching the packets that cause the transitions; a session opened for a
switch sees none of them, because Conduit performs that login itself and withholds
the new backend's Login Success from the client.

It is now told both states explicitly, through the same public
`ProtocolInfo` surface `setServerState` already used. Once it could see where it
was, it asked the client to reconfigure — and Conduit was swallowing the
`Acknowledge Configuration` it was waiting on, while it held the entire world
stream behind that reply. That packet is now passed to the translator too.

### Where it stops now

With all three fixed, the sequence observed on the wire is:

| Step | Result |
|---|---|
| Old backend keeps flowing during preparation | **correct** — no more foreign packets |
| New backend handshake + Login Start | **correct** — `404`/`404`, username only |
| New backend login, `ViaSwitch joined the game` | OK |
| Client protocol across the switch | held at **765** |
| Backend protocol across the switch | **393 → 404** |
| Via path rebuilt for the new pair | 26 → 25 protocols, ending `Protocol1_13_2To1_14` |
| Translator primed at the real states | `PLAY/PLAY` |
| Translator holds Join Game and reconfigures the client | Start Configuration reaches the client |
| Client acknowledges, translator moves to `CONFIGURATION` | OK |
| Translator emits the synthesised phase | **Registry Data, 38 962 bytes, delivered** |
| Translator replays the held world stream | **fails** |
| Client reaches Play on the new backend | **no** |

### The first divergence, against a control

The chunk rewriting errors reported by Via — `ERROR IN Protocol1_19_4To1_20 IN
REMAP OF LEVEL_CHUNK_WITH_LIGHT (0x24)` and friends — are a consequence, not the
fault. The fault is earlier, and a control run pins it down.

**Control:** `config/conduit-via-765-to-404.toml` puts the same 1.20.4 client on
the same real 1.13.2 server directly, no switch, same engine. It **works**:
`ViaDirect joined the game`, 183 serverbound packets, **zero** Via remap errors,
zero translation failures, session stable for the length of the run. So the
765 → 404 pair is not the problem, and neither is Via's ability to build a
Configuration phase for a backend that has none. Switching is.

Putting the two traces side by side, the first divergence is exactly one thing —
what the translator emits for the client's Configuration phase.

| | Direct 765 → 404 (works) | After `/server smp` (fails) |
|---|---|---|
| Feature Flags `0x08` | 20 bytes | **absent** |
| Registry Data `0x05` | 38 962 bytes | 38 962 bytes |
| Update Tags `0x09` | 103 bytes | **absent** |
| **Finish Configuration `0x02`** | **1 byte** | **absent** |

The client is never given Finish Configuration, so it cannot leave the phase, so
`configurationAck` never arrives and the switch times out. Every chunk the
translator is handed after that is one it is being asked to rewrite for a client
it still believes is mid-configuration, which is where the remap errors come
from.

The other visible difference is what the translator's client half is when the
backend's Join Game reaches it. Directly it is `CONFIGURATION/PLAY` and the whole
bundle follows. After a switch it is `PLAY/PLAY`, the translator asks for a
reconfiguration of its own (`0x67` Start Configuration), the client acknowledges,
and only Registry Data follows.

Two candidate causes were tested and **ruled out**:

* *Output stuck on the embedded channel's task queue.* Pumping that queue
  repeatedly until it stops producing changes nothing.
* *The 765 → 404 pair.* The control above.

### What the translator actually produces on the switch path

The claim above that the remaining three packets "are never produced at all" was
inferred from the proxy trace, and it is **wrong** for two of them. Driving a real
765 → 404 `ConduitViaSession` through both lifecycles offline, with no client and
no server, settles it:

| | Fresh lifecycle | Switch lifecycle |
|---|---|---|
| on backend Join Game | — | `0x67` Start Configuration, `0xb` (3 B) |
| on the client's acknowledgement | `0x8` (20 B), `0x5` (38 962 B), `0x9` (103 B), `0x2` (1 B) | `0x5` (38 962 B), `0x9` (103 B), `0x2` (1 B) |

The byte counts are identical to the real runs, so the harness is faithful. On the
switch path the translator **does** produce Update Tags and Finish Configuration,
in the same synchronous call that produces Registry Data. They are produced and
drained; they are lost afterwards, inside Conduit. The trace showed `0x67, 0xb,
0x5` because `flushTranslatorExtras` traces each extra immediately *before*
writing it and, on a write failure, abandoned the rest of the queue without a
word — so the two that follow a failing Registry Data were never traced and never
sent. That swallow is now logged (`Dropped clientbound extra …`); the next switch
run names what fails instead of hiding it.

Feature Flags is a **separate and now-proven** fault. `Protocol1_19_1To1_19_3`
creates it inline while handling the backend's Join Game and sends it down the
rest of the chain, where `Protocol1_20To1_20_2` either queues it into the
configuration bridge or cancels it outright, depending on that bridge's phase. A
fresh login sets the phase to `PROFILE_SENT` when the backend's Login Success
passes through Via, and the packet is queued and replayed as configuration `0x08`.
A switch never shows Via a Login Success — Conduit performs that login itself —
so the phase is still `NONE`, and the packet meets `cancelClientbound` and is
dropped. This is the one place where the "primed" translator genuinely lacks a
lifecycle event the fresh one has. Telling Via where the states are, as
`adoptStates` does, does not substitute for it, because the bridge phase is not
part of the protocol state.

No fix for either is implemented here: the Feature Flags cause is proven but the
write failure behind the lost Update Tags and Finish Configuration is not, and
one unproven half is not a basis for changing the switch path.

### Reproducing the offline lifecycle comparison

The harness is not committed. It opens two `ConduitViaTranslator`s for 765 → 404,
gives one `adoptStates(PLAY, PLAY)` and the other the bridge phase a Login Success
would set, feeds each a synthetic 1.13.2 Join Game (`0x25`) and then the
acknowledgement its own path expects, and prints every extra drained. It needs
only `scripts/_classpath.ps1`.

### Reproducing the comparison

```
powershell -File run-via-765-404.ps1 -PlaySeconds 200    # control: works
powershell -File run-via-765-switch.ps1 -PlaySeconds 400 # switch: fails
```

Then compare `extra →client` lines in the two proxy traces. The control shows
`0x8`, `0x5`, `0x9`, `0x2`; the switch shows `0x67`, `0xb`, `0x5`.

**Server switching under Via: rebinding VERIFIED, completion UNVERIFIED — PARTIAL.**
The client is disconnected cleanly with `Could not connect to smp.` rather than
being left in a broken world.

---

# Direction B — 393 → 765: VERIFIED

| Piece | Detail |
|---|---|
| Client | Official Minecraft **1.13**, protocol **393**, offline, `ViaTest13` |
| Proxy | Conduit `127.0.0.1:25571`, `config/conduit-via-393-to-765.toml` |
| Backend | Official Minecraft **1.20.4** server, protocol **765**, port 25604 |
| Engine selected | **ViaVersion** (`Translator: ViaVersion` in the session diagnostic) |
| Via path | 26 protocols, `Protocol1_13_1To1_13` → … → `Protocol1_20_3To1_20_2` |
| ViaVersion / ViaBackwards / ViaRewind | 5.11.0 / 5.11.0 / 4.1.3 |
| ViaLegacy | not loaded |
| Translation failures | **0** |
| Serverbound packets translated | **1 132**, 17 distinct Play packet types |
| Clientbound Play packet types translated | **43** distinct 1.13 ids |
| Session | **15 min 1 s**, ended by the player choosing Disconnect |

Verified in this run. Server-side state was read over RCON, so each row is a
fact about the 1.20.4 server, not only about what the 1.13 client drew:

| Item | Evidence |
|---|---|
| Handshake | session established |
| Login (offline) | Login Success translated 765 → 393 by Via |
| Backend compression | handled by Conduit outside Via, as designed |
| Configuration | the backend Configuration phase driven **through** Via |
| Configuration → Play | client left Configuration and entered the world |
| World join | `ViaTest13 joined the game` on the 1.20.4 server |
| Chunks | terrain rendered; Chunk Data among the 43 clientbound types |
| Player position | 889 Position, 17 Position And Look, 4 Look, 11 Teleport Confirm |
| Movement | the player walked and was teleported repeatedly |
| Block breaking | 8 Player Digging (`0x18`); a dirt block at -48 72 -15 went to air |
| Block placement | 2 Player Block Placement (`0x29`); a chest exists at -48 73 -15 |
| Containers | that chest opened as a `Chest` screen: Open Window (`0x14`), Window Items (`0x15` ×7), Set Slot (`0x17`) |
| Container interaction | 7 Click Window (`0x08`) moved and split a stack; the chest holds 64 stone in slot 13, then 32 after a split |
| Close window | 3 Close Window (`0x09`) |
| Item stacks | `/give` delivered 16 chests and 64 stone, rendered with correct icons and counts |
| Inventory | Creative Slot (`0x24` ×4), Set Carried Item (`0x21`) |
| Entities | a named pig spawned, rendered with its model and floating name |
| Entity metadata | the custom name and its visibility flag both arrived |
| Entity interaction | 24 Interact Entity (`0x0D`); the pig went 10.0 → 7.0 health, then died |
| Item pickup | the porkchop it dropped ended up in the hotbar |
| Chat | a message from the 1.13 client reached the 1.20.4 server and was broadcast; `[Rcon]` messages rendered on the client |
| Keepalive | 57 exchanges over 15 minutes, no timeout |
| Declare Recipes | **898 of 903 recipes delivered and accepted**, see below |
| Disconnect | the player chose **Disconnect** in the game menu; the server logged `lost connection: Disconnected` / `left the game` |

Not exercised in this run, and therefore not claimed: online-mode encryption
(the run is offline). Via cancelled a large number of clientbound packets during
the run — that is Via declining to deliver packets 1.13 has no equivalent for,
which is its normal behaviour, and Conduit reported no translation failure at
all.

One cosmetic difference was visible and is not a protocol fault: Via warns that
the 1.17 world height cannot be represented below 1.17, so a 1.13 client sees
void below y 0 and above 256.

## The recipe fault, and what Conduit does about it

This is what previously ended the session a few packets after world join. The
client showed:

```
Internal Exception: io.netty.handler.codec.DecoderException:
Non [a-z0-9/._-] character in path of location: minecraft:<NUL>
```

Captured with `-Dconduit.dump.clientbound`, the eighth clientbound packet is
Declare Recipes (1.13 id `0x54`, 903 recipes, 485 920 bytes). Five recipes —
`minecraft:silence_`, `wayfinder_`, `shaper_`, `raiser_` and
`host_armor_trim_smithing_template`, at indices 127, 163, 310, 560 and 722 —
are well-formed `crafting_shaped` recipes whose **result** is a 1.20 smithing
template with no 1.13 item. Each result slot is written as:

```
ff ff        item id -1  (no 1.13 counterpart)
02           count 2
0a 00 00 …   an NBT compound
```

A 1.13 slot with id `-1` is empty and carries no count and no tag, so the client
reads the count and the tag as the start of the next recipe — a string of length
2 whose bytes are `0a 00`, which is not a valid resource location path. That is
exactly the reported error, and every recipe after the first one is unreadable.

### Whose fault it is

Not Conduit. On this path Conduit does not produce the packet. The clientbound
dump is taken at the socket write, after every Conduit stage, and for the Via
engine the brand rewriter, command merger, deferred-Play gate and Play-phase
profile adapter are all bypassed. The bytes are the translated output unmodified.

The defect is in the ViaBackwards 5.11.0 downgrade of Declare Recipes toward
1.13, for a recipe whose result has no item on the target version: the id is
mapped to `-1` but the count and the tag are still written. All 6 849 ingredient
slots in the same packet are non-empty, so only result slots are affected here.

Ruled out: `pass-original-item-name-to-resource-packs` in `viabackwards.yml`.
Setting it to `false` shrinks the packet from 485 920 to 437 674 bytes and
strips the injected display names, but reproduces the same malformed slots at
the same recipes. No ViaVersion or ViaBackwards configuration option was found
that avoids it.

Checked upstream on 2026-09-16: **5.11.0 is the current release** of both
ViaVersion and ViaBackwards — `5.12.0-SNAPSHOT` is the only newer thing in the
repository, and it is not a release. No commit or issue in the ViaBackwards
tracker addresses the 1.13 recipe result slot. There is nothing to upgrade to,
so the dependency was left where it is.

### What Conduit does

`gg.tame.conduit.protocol.RecipeListRepair` runs at Conduit's own socket write,
on Conduit's own outbound byte stream, and is written from the published 1.13
wire layout. It parses the packet twice:

* If the **strict** reading — an empty slot carries nothing — accounts for the
  packet exactly, the packet is correct and is forwarded **by identity**. That is
  the path every healthy packet takes, including the one Conduit's own native
  translators produce, and the path a fixed upstream release would take.
* Otherwise a **tolerant** reading, in which every slot carries a count and a
  tag whatever its id, is tried. If that accounts for the packet exactly, the
  recipe boundaries are known. Conduit re-emits the list with the recipes that
  contain an empty slot removed and the count corrected. Every other recipe is
  copied byte for byte — nothing is re-encoded, nothing is invented.
* If neither reading fits, Conduit emits an **empty recipe list**, which is a
  valid packet that leaves the recipe book unpopulated, and logs it. It does not
  forward bytes it knows will disconnect the client.

On the real capture: 903 recipes in, 898 out, 485 920 bytes down to 482 790,
and an independent strict parse consumes the result exactly. The five dropped
recipes could not have been useful to the client — their result item does not
exist on its version.

This reproduces no third-party implementation. It is a Conduit-owned integrity
check over Conduit's own output, driven by the 1.13 protocol layout and by the
captured bytes, and it is deliberately narrow: only a 1.13-family client, only
this packet. No ViaVersion fork was made and no Via source was consulted for it.
The upstream defect is recorded here, with the exact bytes, so it can be
reported and so the repair can be removed once a release fixes it.

## Faults found and fixed in Conduit on the way here

Each was found by a real client, in this order, and each blocked the next.

1. **Via was never shut down.** Its platform executors are not daemon threads,
   so the test JVM never exited. Fixed by an explicit stop plus a shutdown hook.
2. **Conduit's native configuration absorber starved Via.** Via reached Join
   Game with an empty dimension registry and failed to remap it. Fixed by
   running the backend's configuration phase through Via when Via is the engine.
3. **Via's backend state never left Login.** Conduit writes Login Acknowledged
   itself, so Via never saw the transition and mis-read the first Configuration
   packet. Fixed by telling Via about the transition Conduit performed.
4. **Every packet Via generated itself threw an NPE.** Conduit installed Via's
   no-op injector; the handler names it reports did not resolve, and the
   session's decoder had no predecessor to fire reads at. Fixed with a Conduit
   injector and an anchor handler.
5. **Via's extra packets arrived several packets late.** Via schedules some of
   them on the channel event loop, which an `EmbeddedChannel` runs only on
   demand. A one-byte Login Acknowledged landed after the backend reached Play,
   where the same id is a packet with a body, and the 1.20.4 server closed the
   connection with a decoder underflow. Fixed by draining pending tasks after
   each transform.
6. **Conduit sent a second Login Acknowledged** alongside Via's. Fixed by
   sending Conduit's only when Via did not send one.
7. **Conduit's native compensation layer rewrote Via's output.** Bypassed for
   the Via engine.
8. **Conduit overwrote Via's connection state on every packet.** Via derives the
   client and backend states from the packets it writes; Conduit's is one state
   owned by whichever thread touched it last. A 1.13 backend answers Login
   Success with Join Game immediately, on the backend reader thread, while the
   client's Login Acknowledged is still in flight to the client reader — so Via
   was told LOGIN and the one Join Game of the session was decoded in the wrong
   state and dropped, and the client waited forever for the Finish Configuration
   that packet would have produced. Fixed by priming Via's state once and
   leaving both halves to Via after that. This is what turned direction A from a
   server-side timeout into a verified session.

## Reproducing

From `work/real-client-validation/`:

```
powershell -File run-via-765-393.ps1 -PlaySeconds 120
powershell -File run-via-393-765.ps1 -PlaySeconds 260
powershell -File run-via-765-switch.ps1 -PlaySeconds 190
```

Logs land in `logs-via/`: the proxy trace, the backend server logs, the client
log, and the raw clientbound byte stream.

`drive-client.ps1` sends real input to the running client — `chat:` for chat and
commands, `at:X,Y` to place the cursor on a specific GUI slot, `holdleft` to
break a block. It presses keys with the scan code the key actually has, because
GLFW ignores an event whose scan code is zero; Escape in particular never
reached the client before that, which is why containers could be opened but not
closed.

`rcon.py` drives the backend server's own console (`enable-rcon` in
`server.properties`). Setting up a container test and then reading the result
back out of the server is what makes a container row evidence rather than a
screenshot: `python rcon.py 25575 <password> "data get block -48 73 -15"`.

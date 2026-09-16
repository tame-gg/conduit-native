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

`config/conduit-via-765-switch.toml` adds a second backend, a real 1.13.2
server (protocol **404**) on port 25614, and the client issued `/server smp`.

What worked: `clientProtocolVersion` stayed **765** across the switch;
`backendProtocolVersion` moved **393 → 404**; Conduit re-selected TRANSLATED/Via
for the new pair and Via rebuilt the path for it (25 protocols, correctly now
ending at `Protocol1_13_2To1_14` rather than `Protocol1_13To1_13_1`); the old
backend saw a clean disconnect; the player **joined the 1.13.2 server**.

What then failed: the 1.13.2 backend closed the connection with

```
DecoderException: Packet 2/0 (of) was larger than I expected,
found 7 bytes extra whilst reading packet 0
```

State 2 packet 0 on a 1.13.2 server is **Login Start**, so the switch wrote a
Login Start the new backend could not read to the end. That is in Conduit's
switch login path, which runs before and outside any translator, so it is
unlikely to be Via-specific — but it has not been reproduced on the native
engine, so that is a statement about where the code sits, not a measurement.

**Server switching under Via: rebinding VERIFIED, completion UNVERIFIED.**

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
| Serverbound packets translated | **585**, 13 distinct Play packet types |
| Clientbound Play packet types translated | **53** distinct ids |
| Session | **7 min 11 s**, no timeout, no unexplained disconnect |

Verified in this run:

| Item | Evidence |
|---|---|
| Handshake | session established |
| Login (offline) | Login Success translated 765 → 393 by Via |
| Backend compression | handled by Conduit outside Via, as designed |
| Configuration | the backend Configuration phase driven **through** Via |
| Configuration → Play | client left Configuration and entered the world |
| World join | `ViaTest13 joined the game` on the 1.20.4 server |
| Chunks | terrain rendered; Chunk Data among the 53 clientbound types |
| Player position | 421 Position + 21 Position And Look + 105 Look, translated |
| Movement | the player walked; Flying and Teleport Confirm translated |
| Block breaking | Player Digging (`0x18`) translated serverbound |
| Held item / hotbar | Set Carried Item (`0x21`), Creative Slot (`0x24`) |
| Entities, entity metadata | Spawn and Entity Metadata among the clientbound types |
| Chat | a message from the 1.13 client reached the 1.20.4 server and was broadcast |
| Keepalive | 27 exchanges over 7 minutes, no timeout |
| Declare Recipes | **898 of 903 recipes delivered and accepted**, see below |
| Disconnect | client close propagated; server logged `lost connection` / `left the game` |

Not exercised in this run, and therefore not claimed: online-mode encryption
(the run is offline), container windows (Click Window / Close Window), block
placement, and entity interaction. Those were not driven; they were not observed
failing. Via cancelled 1 333 clientbound packets during the run — that is Via
declining to deliver packets 1.13 has no equivalent for, which is its normal
behaviour, and Conduit reported no translation failure at all.

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
log, and the raw clientbound byte stream. `drive-client.ps1` sends real input to
the running client, including `chat:/server smp` for the switch run.

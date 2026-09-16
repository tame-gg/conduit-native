# ViaVersion engine, 393 → 765, real client and real server

Real official Minecraft 1.13 client → Conduit (`translation.enabled = true`,
`engine = "via-preferred"`) → real official Minecraft 1.20.4 server. Nothing
synthetic: no stub client, no replayed capture, no fabricated packets.

Runner: `work/real-client-validation/run-via-393-765.ps1`. Conduit commit under test: `d886090`.

| Piece | Detail |
|---|---|
| Client | Official Minecraft **1.13**, protocol **393**, offline, `ViaTest13` |
| Proxy | Conduit `127.0.0.1:25571`, `config/conduit-via-393-to-765.toml` |
| Backend | Official Minecraft **1.20.4** server, protocol **765**, port 25604 |
| Engine selected | **ViaVersion** (`Translator: ViaVersion` in the session diagnostic) |
| Via path | 26 protocols, `Protocol1_13_1To1_13` → … → `Protocol1_20_3To1_20_2` |
| ViaVersion / ViaBackwards / ViaRewind | 5.11.0 / 5.11.0 / 4.1.3 |
| ViaLegacy | not loaded |
| Status | **TRANSLATED / UNVERIFIED** — joins the world, then disconnects |

## What actually happens now

| Stage | Result |
|---|---|
| Handshake | OK |
| Login (offline) | OK — Login Success translated 765 → 393 by Via |
| Backend compression | OK — consumed by Conduit outside Via, as designed |
| Configuration (backend only) | OK — driven **through** Via, 5 packets |
| Configuration → Play | OK — Via emits Join Game, brand and Tags for 393 |
| World join | OK — server logs `ViaTest13 joined the game` |
| Play translation | ~20 clientbound packets translated before the fault |
| Player position / chunks / entities / chat / inventory | **not reached** |
| Serverbound Play translation | **not reached** — client never sends a Play packet |
| Disconnect | client-side decoder fault, see below |

Translation failures reported by Conduit: **zero**. Nothing in the run was
dropped by Conduit or refused by Via. The session dies on the *content* of one
packet Via produced.

## The blocking fault

The client shows:

```
Internal Exception: io.netty.handler.codec.DecoderException:
Non [a-z0-9/._-] character in path of location: minecraft:<NUL>
```

Captured with `-Dconduit.dump.clientbound`, the eighth clientbound packet is
Declare Recipes (1.13 id `0x54`, 903 recipes, 485 920 bytes). Recipe 127,
`minecraft:silence_armor_trim_smithing_template`, is a well-formed 3×3
`crafting_shaped` with nine correctly encoded ingredients. Its **result slot**
is then written as:

```
ff ff        item id -1  (no 1.13 counterpart)
02           count 2
0a 00 00 …   an NBT compound
```

A 1.13 slot with id `-1` is empty and carries no count and no tag, so the
client reads the count and the tag as the start of recipe 128 — a string of
length 2 whose bytes are `0a 00`, which is not a valid resource location path.
That is exactly the reported error, and every recipe after it is unreadable.

### Whose fault it is

Not Conduit's. On this path Conduit does not touch the packet. The clientbound
dump is taken at the socket write, after every Conduit stage, and for the Via
engine the brand rewriter, command merger, deferred-Play gate and Play-phase
profile adapter are all bypassed. The bytes are Via's output unmodified.

The fault is in the ViaBackwards 5.11.0 downgrade of Declare Recipes toward
1.13, for a recipe whose **result** has no item on the target version: the id
is mapped to `-1` but the count and tag are still written.

Ruled out: `pass-original-item-name-to-resource-packs` in `viabackwards.yml`.
Setting it to `false` shrinks the packet from 485 920 to 437 674 bytes and
strips the injected display names, but reproduces the same malformed slot at
the same recipe. No ViaVersion or ViaBackwards configuration option was found
that avoids it.

Per the project's code-origin rule this was **not** worked around by
reimplementing Via's item encoding inside Conduit, and no ViaVersion fork was
made. The defect is recorded here with the exact bytes so it can be reported
upstream or retested against a later Via release.

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

## Reproducing

```
powershell -File run-via-393-765.ps1 -PlaySeconds 75
```

Logs land in `logs-via/`: the proxy trace, the backend server log, the client
log, and the raw clientbound byte stream.

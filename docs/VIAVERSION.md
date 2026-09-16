# ViaVersion ecosystem integration

Conduit remains an independently implemented Minecraft proxy (`koels` / Conduit).
Cross-version packet translation can be provided by the ViaVersion ecosystem as
**third-party** libraries. Conduit owns only its platform adapter and proxy code.

## Enable

```toml
[translation]
enabled = true
engine = "via-preferred"   # via-preferred | via | native
via-backwards = true
via-rewind = true          # needed for 1.7.x / 1.8.x clients
via-legacy = true          # needed for backends ≤ 1.7.10 (incl. 1.7.6)
data-folder = "via"
```

Default is **disabled** so existing native paths stay unchanged until operators opt in.

## Roles

| Component | Role |
|---|---|
| ViaVersion 5.11.0 | Newer clients → older backends |
| ViaBackwards 5.11.0 | Older clients → newer backends |
| ViaRewind 4.1.3 | 1.7.x / 1.8.x clients on 1.9+ |
| ViaLegacy 3.0.16 | Clients → backends ≤ 1.7.10 (opt-in; needs extra deps) |

ViaLegacy is **off by default**. Enable `translation.via-legacy=true` only when
proxying to ≤1.7.10 backends, and ensure ViaLegacy's transitive libraries
(e.g. MinecraftBiome / seedfinding) are on the classpath.

## Architecture

Conduit does not use Netty for proxy I/O. Via is integrated through
`ProtocolTranslator` using Via’s public `UserConnection.transform*` APIs.

```
client AES? → framing → ProtocolTranslator (Via or native or identity)
                              ↓
                     backend framing → zlib? → backend
```

Same-version connections stay **DIRECT** (`IdentityTranslator`). Via receives
plaintext, uncompressed packet bodies: client encryption and backend
compression both stay outside it.

### What Via needs from Conduit

Via is not only a function from one packet to another. Three things it expects
of a host platform are not free on Conduit's architecture, and each of them
silently breaks translation if it is missing:

**A channel it can address.** Via emits packets of its own — a Login
Acknowledged for a client too old to send one, a Finish Configuration, a
Player Info — and it finds the two ends of a connection by asking its injector
for the encoder and decoder handler names, then firing at whatever sits *before*
the decoder. `ConduitViaInjector` reports the names `ConduitViaSession` puts on
its per-session `EmbeddedChannel`, and the session keeps an anchor handler in
front of the decoder so that lookup resolves. There is still no Netty in
Conduit's proxy I/O: the channel exists only as Via's address space for one
connection, never as a data path.

**Its extras taken promptly.** Some of those packets are handed to the channel's
event loop rather than emitted inline, and an `EmbeddedChannel` runs that queue
only when asked. The session drains pending tasks after every transform, so an
extra reaches the wire in the protocol state it was written for.

**Both connection states, kept apart.** Via tracks the client state and the
backend state independently, and across the 1.20.2 boundary they genuinely
differ — a 1.13 client is in Play while the backend is still in Configuration.
Conduit tells Via only where the *client* is, plus the one backend transition
Conduit performs on Via's behalf (it writes Login Acknowledged itself, from the
backend's protocol definition, so Via never sees that packet). Everything else
about the backend half is Via's to decide.

### Configuration phase

For a client older than 1.20.2 talking to a 1.20.2+ backend, Conduit normally
absorbs the backend's Configuration phase so the client never sees a state it
does not have. With Via as the engine that is wrong: the registry data, tags
and feature flags sent in that phase are exactly what Via needs to rewrite Join
Game and every dimension-shaped packet after it. So when Via is the engine the
phase is run *through* Via instead, and Via decides what — usually nothing until
it has seen Join Game — reaches the client.

### Native compensations are bypassed

Brand injection, command-tree merging, synthesised Configuration transitions,
deferred Play and the Play-phase profile rewrites exist because Conduit's own
translators leave those gaps. Via closes them itself. Doing both produces a
second brand, a duplicated player entry or a transition the client has already
made, so on the Via path Via's output goes to the client as it stands.

## Protocol ceiling (Via 5.11.0)

- Floor of interest: Minecraft **1.7.6** (protocol 5)
- Via register tops out at **26.2** (protocol 776)
- **26.3 is not supported** by this Via release — do not claim it

## Modes

| Mode | Meaning |
|---|---|
| DIRECT | `clientProtocol == backendProtocol` |
| TRANSLATED | Via path and/or native translator |
| UNSUPPORTED | neither path exists |

`clientProtocolVersion` and `backendProtocolVersion` are kept separately for the
life of a session. The client's stays fixed; the backend's changes on a server
switch, which rebuilds the Via session for the new pair through
`ConduitViaTranslator.rebindBackend`.

## Verification status

Real-client status is recorded in `docs/VALIDATION_VIA_393_765.md`, and nothing is
called VERIFIED on the strength of a smoke test, a successful login, or the
dependency merely containing the protocol.

| Pair | Engine | Status |
|---|---|---|
| 765 → 393 | Via | **TRANSLATED / VERIFIED** — real 1.20.4 client, real 1.13 server, gameplay through Via, 0 translation failures |
| 765 → 404 after `/server` | Via | **PARTIAL** — client protocol held, backend protocol and Via path rebound correctly, session then dropped by the new backend on Conduit's switch-time Login Start |
| 393 → 765 | Via | **TRANSLATED / VERIFIED** — real 1.13 client, real 1.20.4 server, 15 min of gameplay through Via, 1 132 serverbound packets across 17 Play types, 0 translation failures |
| 393 → 765 | native | unchanged; see `RESULTS-393-765-CROSS.md` |
| 5 (1.7.6) → modern | Via + ViaRewind | **UNVERIFIED** — no real-client run has been performed |
| 765 → 404 direct | Via | **TRANSLATED / VERIFIED** — control run, no switch; joins and plays with 0 failures |
| 765 → 404 after `/server` | Via | **PARTIAL** — same pair, reached by switching, stops when the client's Configuration phase is never finished; see `docs/VALIDATION_VIA_393_765.md` |
| anything → 26.3 | Via | **UNSUPPORTED** — Via 5.11.0 does not register it |

### A defect in the dependency, and Conduit's response

ViaBackwards 5.11.0, downgrading Declare Recipes to 1.13, writes a recipe result
whose item has no 1.13 counterpart as item id `-1` *followed by* a count and an
NBT tag. A 1.13 slot with id `-1` is empty and carries neither, so the client
reads them as the start of the next recipe and rejects the packet. Five recipes
in a vanilla 1.20.4 recipe list are affected, and the whole session ended on the
first of them.

5.11.0 is the current release of both ViaVersion and ViaBackwards as of
2026-09-16, and nothing upstream addresses this, so there was no fix to upgrade
to. `gg.tame.conduit.protocol.RecipeListRepair` instead validates the packet at
Conduit's own socket write: a packet that reads correctly against the 1.13
layout is forwarded by identity, one that only reads under the malformed layout
is re-emitted without the recipes carrying an empty slot, and one that fits
neither is replaced with an empty recipe list and logged. Nothing is re-encoded;
kept recipes are copied byte for byte.

The exact bytes, the ruled-out configuration options, the upstream check, and
the reasoning that places the fault outside Conduit are in
`docs/VALIDATION_VIA_393_765.md`.

This was not worked around by reimplementing Via's item encoding inside Conduit,
no ViaVersion fork was made, and no Via source was consulted for the repair. The
repair should be removed once an upstream release fixes the encoding.

## Licensing

ViaVersion / ViaBackwards / ViaRewind common artifacts are **GPLv3**.
ViaLegacy follows its upstream license. Distributing a Conduit build that links
these artifacts requires complying with those licenses (including source
availability for GPLv3-covered combined works as applicable).

Upstream projects: https://github.com/ViaVersion

Conduit integration code under `gg.tame.conduit.viaversion` is independently
authored and is **not** a copy of Velocity, BungeeCord, ViaLoader, or Via internals.

## Build

```powershell
./scripts/fetch-via.ps1
./scripts/test.ps1
```

See also `docs/VIAVERSION_INTEGRATION_PLAN.md` and `docs/COMPATIBILITY.md`.

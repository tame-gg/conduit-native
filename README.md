# Conduit

Conduit is an independently implemented Minecraft: Java Edition proxy foundation. It is not a
Velocity or Velocity-CTD fork, and it has no dependency on either implementation.

The legacy `tame-gg/conduit` checkout is intentionally separate and untouched.

Current version: **0.6.0**.

## Build and test

Requires a JDK capable of compiling Java 21 source. On Windows:

```powershell
./scripts/test.ps1
./scripts/run.ps1 -ConfigPath run/conduit.toml
```

`--check-config <path>` validates a configuration without binding a listener.

## Supported Minecraft versions

Native intercept codecs (version + state + direction packet IDs) exist for:

| Minecraft | Protocol | Configuration | Real vanilla client |
|---|---|---|---|
| 1.20.1 | 763 | no | not tested |
| 1.20.3 / 1.20.4 | 765 | yes | 1.20.4 login/Play previously verified; switching retest pending |
| 26.2 | 776 | yes | **codec includes 26.2 `minecraft:hello` (Should Authenticate boolean); real vanilla PLAY verification pending** |

Catalog only (handshake known, **no codec**, cannot connect):

1.7.10 (5), 1.8.9 (47), 1.12.2 (340), 1.16.5 (754), 1.19.4 (762), 1.20.2 (764), 1.21/1.21.1 (767), 1.21.4 (769), 1.21.8 (772).

Unknown handshake versions disconnect. They are never decoded as 1.20.4.

**Direct:** same codec version only (`765→765`, `763→763`, `776→776`).

**Translated:** none. `1.20.4 → 26.2` and `26.2 → 1.20.4` are UNSUPPORTED.

26.2 clientbound `minecraft:hello` (Encryption Request) includes a trailing **Should Authenticate** boolean that 1.20.4 does not. Initial routing prefers backends whose probed protocol is DIRECT for the connecting client (so 26.2 clients skip 1.20.4 lobby).

Backends may be configured as:

```toml
[servers.smp]
address = "203.0.113.10:25921"
```

or `host` + `port`. Commands never print those addresses. At startup Conduit status-pings each backend and logs its advertised protocol.

Lobby and survival are Paper 1.20.4 with **ViaVersion 5.11.0**, so a 26.2 client can join them on the **client's** protocol (776). Status ping still reports 765; that is the native server version, not a Conduit translator.

Backend Set Compression is consumed by Conduit and never forwarded to the client. Client↔Conduit stays uncompressed; Conduit unwraps compressed backend frames. Forwarding Set Compression caused 26.2 vanilla `DataFormatException: incorrect header check`.

## Authentication

```toml
[authentication]
mode = "offline"   # or "online"
# session-url = "https://sessionserver.mojang.com/session/minecraft/hasJoined"
# timeout-millis = 5000
```

### Online

```
Login Start
  → Encryption Request (RSA 1024, per-login verify token)
  → Encryption Response
  → AES/CFB8/NoPadding on the entire client byte stream
  → Minecraft signed SHA-1 server hash
  → HTTPS hasJoined
  → authenticated UUID / username / properties (`textures` including cape JSON when Mojang sent it)
  → Login Start to each backend uses that UUID/name (never the raw client Login Start after auth)
  → modern forwarding includes the same property list
  → Login Success and player-info ADD_PLAYER for this UUID are filled if the backend omitted textures
```

Login Start UUID is **not** authoritative in online mode. Failures disconnect the client;
there is no silent fallback to offline identity. Mojang authentication is not repeated when
switching backends; the authenticated `PlayerProfile` on the TCP session is reused. Each backend
connection gets a fresh modern forwarding HMAC payload with those properties.

Cape data lives inside the signed `textures` property (Base64 JSON with optional `CAPE.url`).
Conduit preserves that blob; it does not download capes or invent URLs. A client without a cape
in `textures` stays cape-less.

Logs print `PlayerProfile.summary()` (property presence only, never values or signatures).

### Offline

Login Start identity is forwarded as an **unauthenticated** profile. Do not treat it as
Mojang-verified.

## Encryption

Client↔Conduit traffic uses Minecraft protocol encryption after a successful handshake.
Backend↔Conduit remains plaintext (Paper stays offline and trusts modern forwarding).
Switching backends does **not** restart the client encryption handshake.

Private keys, shared secrets, and session bodies are not logged.

## Servers and routing

```toml
[servers.lobby]
host = "127.0.0.1"
port = 25570

[servers.survival]
host = "127.0.0.1"
port = 25571

[routing]
initial = ["lobby"]
fallback = ["lobby", "survival"]
```

`routing.initial` is tried in order after authentication. Matching native protocol on a later server
does not jump the queue (a 26.2 client still starts on lobby when lobby is first). If the current backend socket dies,
`routing.fallback` is tried next, skipping the dead server and any server that already failed
in that incident (no reconnect loop). If none accept, the client is disconnected.

`/server` only lists configured names. Addresses, ports, and secrets are never shown.

Modern forwarding sends the TCP address Conduit accepted. Connecting to `127.0.0.1` therefore
forwards `127.0.0.1`. Optional `forwarding.player-address` overrides that for remote backends.
Local Paper does not care. Lobby → survival on a 26.2 client failed because Play packet id 16
(`configuration_acknowledged`) was forwarded onto 1.20.4 login, not because of the IP.

## Commands

Native command framework (not Velocity's):

* `/server` — `Current server: <name>` then available names
* `/server <name>` — switch; exact match wins over prefix; ambiguous prefixes are rejected.
  26.2 reconfiguration waits for Known Packs (same as Velocity #1302) before applying the new
  backend's registry. ViaVersion on a 1.20.4 Paper box is a new handshake from Conduit; it can
  work if that plugin translates configuration.
* `/conduit` — version and `Current server: <name>`
* `/send current <server>` — same as `/server <server>` (player only)
* `/send <player> <server>` — move that online player (`server.send.others`)
* `/send <server> <server>` — move everyone on the source server (`server.send.mass`)

Tab completion for `/send` offers `current`, backend names, and online players through CommandManager.

Permission nodes (stub currently grants them to connected players):

* `server.use`
* `server.send`
* `server.send.others`
* `server.send.mass`
* `conduit.info`

`/send` never lists backend addresses. Mass moves run with bounded concurrency; a failed player stays on the source backend.

Proxy commands are intercepted and not forwarded to Paper. Other commands are forwarded.

On protocol 765, Conduit merges `/server`, `/conduit`, and `/send` into the backend **Declare Commands**
tree (brigadier node indexes are rewritten). If a backend tree cannot be decoded, the original
backend packet is forwarded unchanged (those proxy commands may still execute but can appear red).

## Canonical authenticated profile

After `hasJoined`, `LoginPipeline` holds one `PlayerProfile` (`AuthenticatedPlayerProfile.freeze`
keeps UUID, username, and signed `textures`). `PlayerSession.authenticatedProfile()` is that object.
Every `BackendConnection` receives the same reference for Login Start and modern forwarding.
Switching does not call Mojang again and does not allocate an empty profile.

Modern forwarding (`velocity:player_info`) is versioned with `ModernForwardingVersion` (1–4),
independent of Minecraft protocol 765/776. The body is: forwarding version, client IP, UUID,
username, property list (HMAC-SHA-256 prefix). 26.2 Paper still uses that layout; extra Velocity
key fields for versions 2–3 are omitted because Conduit terminates the client chat key separately.

## Switching (protocol 765 and 776)

The client TCP connection stays on Conduit.

1. New backend: handshake, Login Start (authenticated profile), modern forwarding
2. Login Success is **not** sent to the client (same as Velocity; the client already left Login)
3. Conduit sends **Login Acknowledged** to the backend (the client ack is dropped as a duplicate)
4. Client: Start Configuration → Configuration Acknowledged
5. Forward configuration packets (registry, tags, brand) then Finish Configuration
6. Client Finish Configuration ack is forwarded to the new backend (this is what enters Play)
7. After Join Game, Conduit writes `player_info_update` ADD_PLAYER for the local UUID with the
   canonical properties (including signed `textures` and, on 26.2, listed + hat). Reconfiguration
   clears TAB; hiding Login Success means the client would otherwise wait for a backend ADD_PLAYER
   that ViaVersion/1.20.4 often omits or sends without textures.
8. Swap session backend; close the old backend

Conduit rewrites backend ADD_PLAYER for the local UUID to the canonical properties when present.
Other players' properties are forwarded unchanged. Conduit does not invent skins or capes.

On 776 the new backend reaches Play before the client finishes reconfiguring, because 776 waits on
Known Packs. Backend Play packets read during that wait are forwarded rather than silently dropped.
Tracing a real switch showed this window does not open in practice, so it was not the cause of the
lost skin layers; it is still a correctness hole and the drop path now logs. 765 does not read the
backend while waiting, so it never had it.

Skin layers and capes survive a switch because Conduit replays the client's cached **Client
Information** packet to each new backend. The client sends it once, during its initial
configuration; it carries Displayed Skin Parts (cape, jacket, sleeves, pants, hat). A backend that
never receives it broadcasts defaults, and a 26.2 backend sends a full `player_info_update`
including `hat=false`, which lands after Conduit's entry and overwrites it. The textures property is
intact throughout — it was never the profile that was lost.

The clientbound profile rewrites are scoped to the client's connection state. Packet ids are only
unique within a state: Login Success is id `2`, and id `2` in Configuration is Configuration
Disconnect on 776 and Finish Configuration on 765.

`PlayerInfoUpdate.ensureOwnTextures` re-serializes only what it decoded and requires the decode to
consume the packet exactly. Any decode failure or leftover byte forwards the backend packet
unchanged instead of emitting a half-parsed rewrite with the remainder appended. Packets with
nothing to substitute are forwarded byte-for-byte.

The 26.2 player-info layout was checked against ViaVersion 5.11.0's own definitions rather than
assumed: a 1-byte action set (`BitSetType(8)`, unchanged since 1.21.4 and still current for 26.x),
bits 0-7 in the order add / initialize chat / game mode / listed / latency / display name / list
priority / hat, with boolean-prefixed optionals. Play ids for 776 match `ClientboundPackets26_1`
(player info update `0x46`, login `0x31`, start configuration `0x76`, system chat `0x79`).

Client protocol state and backend protocol state are tracked separately.

Brand rewrite and Finish Configuration detection are **state-scoped**. Packet id `2` in Play is
not treated as Finish Configuration (that mismatch produced a vanilla decoder crash).

## Server brand

Conduit intercepts only the `minecraft:brand` plugin message:

* `Paper` → `Paper (Conduit)`
* `Purpur` → `Purpur (Conduit)`
* `Fabric` → `Fabric (Conduit)`
* `Paper (Conduit)` stays `Paper (Conduit)`
* missing brand → `Conduit`

## Modern forwarding

Verified against **Paper git-Paper-499 (MC: 1.20.4)**. Keep Paper `online-mode=false`,
Velocity modern forwarding enabled, a matching secret, and
`network-compression-threshold=-1`.

## Real vanilla client

Verified previously:

```text
Minecraft 1.20.4 → Conduit (online) → Paper 1.20.4-499
```

Login, encryption, Mojang `hasJoined`, modern forwarding, Play, and chat.

Lobby ⇄ Survival with a real vanilla 1.20.4 client after these protocol fixes is **not**
claimed until that pass is repeated.

1.20.1 is **not** claimed as real-client compatible.

## Testing notes

`./scripts/test.ps1` covers encryption, mocked hasJoined, online-mode identity substitution,
command dispatch, command-graph index validation, brand rewriting, routing matches,
protocol 763 vs 765 lookup, two-backend switch/fallback mocks, and authenticated profile
property round-trips (hasJoined, forwarding, Login Success, 26.2 player-info).

## Out of scope here

Velocity plugins, native plugin API, legacy/BungeeGuard forwarding, ViaVersion-style
translation, and full compression.

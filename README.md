# Conduit

Conduit is an independently implemented Minecraft: Java Edition proxy foundation. It is not a
Velocity or Velocity-CTD fork, and it has no dependency on either implementation.

The legacy `tame-gg/conduit` checkout is intentionally separate and untouched.

Current version: **0.9.0**. Native plugin API version: **1**.

## Build and test

Requires a JDK capable of compiling Java 21 source. On Windows:

```powershell
./scripts/fetch-via.ps1   # optional ViaVersion ecosystem jars
./scripts/test.ps1
./scripts/run.ps1 -ConfigPath run/conduit.toml
```

`--check-config <path>` validates a configuration without binding a listener.

### Optional ViaVersion translation

Set `[translation] enabled = true` to use ViaVersion / ViaBackwards / ViaRewind as the
preferred cross-version engine. See `docs/VIAVERSION.md` and `docs/LICENSING_VIA.md`.
Native translators are kept as fallback. **Minecraft 26.3 is not supported by Via 5.11.0.**

## Supported Minecraft versions

### Modern compatibility program (1.13 → 26.2)

Conduit's current roadmap targets the **modern Java protocol era**: Minecraft **1.13 through 26.2**.

**Minecraft 1.12.2 and older are OUT OF SCOPE** for this program (catalog may list them as `LEGACY_UNSUPPORTED`). They will be a separate legacy project later.

Three distinct concepts:

| Concept | Meaning |
|---|---|
| **Catalog** | Version/protocol identity is known |
| **DIRECT** | Client and backend share a Conduit codec |
| **TRANSLATED** | A real translator exists between codecs |

Do **not** read the catalog as "everything is supported."

### Native intercept codecs (DIRECT)

| Minecraft | Protocol | Configuration | Status |
|---|---|---|---|
| 1.13 | 393 | no | DIRECT / VERIFIED — exercised end-to-end by the official 1.13 client, and as both ends of the 393 ↔ 765 translated pair |
| 1.20.1 | 763 | no | DIRECT / codec |
| 1.20.3 / 1.20.4 | 765 | yes | DIRECT; 1.20.4 login/Play previously verified |
| 1.20.5 / 1.20.6 | 766 | yes | DIRECT codec; translated path from 765 is PARTIAL |
| 26.2 | 776 | yes | DIRECT codec; real vanilla PLAY verification pending |

### Catalog releases (identity only unless codec listed above)

Modern named releases are mapped from public protocol data (Minecraft Wiki / PrismarineJS), including aliases that share a protocol number (e.g. 1.20 + 1.20.1 → 763, 1.16.4 + 1.16.5 → 754).

Legacy catalog markers (no modern-program work): 1.7.10 (5), 1.8.9 (47), 1.12.2 (340).

Unknown handshake versions disconnect. They are never decoded as 1.20.4.

### Translation matrix

| Client → Backend | Support | Completeness |
|---|---|---|
| same codec (393, 763, 765, 766, 776) | DIRECT | FULL for mature paths; 393 PARTIAL until real-client verified |
| **393 ↔ 765** | **TRANSLATED** | **CORE GAMEPLAY VERIFIED (bidirectional)** — login, world, movement, blocks, entities, health, chat, items, inventory, containers (including chest open/click/close), equipment, metadata and attributes against real vanilla clients/servers via scripted probes. Sounds, particles, scoreboards, titles, boss bars, block-entity data, recipes and advancements remain deliberately unsupported. Not equivalent to a human gameplay session. See `docs/VALIDATION_393_765.md`. |
| 765 ↔ 766 | TRANSLATED | PARTIAL (control/login/config; Join Game unsupported) |
| 765 → 776 | UNSUPPORTED | intentional until a real translator exists |

**393 ↔ 765 is CORE GAMEPLAY VERIFIED, not FULL.** Ordinary survival play crosses
the pair in both directions under scripted real-client/server probes, but the
unsupported list above is intentional and human mouse/keyboard play was not
exercised here.

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

Player-facing output is clean and polished: hierarchy, colors, and concise wording —
Minecraft-proxy UX, not a chat dashboard. No ASCII boxes, click-to-connect, or address leaks.

* `/server` — `You are currently connected to: …` plus a scannable list (`●` current, `○` others)
* `/server <name>` — switch; unique prefixes resolve (`surv` → `survival`), ambiguous ones list the matches
* `/lobby`, `/survival`, … — one shortcut per configured backend, except where the name is already a Conduit command (`server`, `send`, `glist`, `plist`, `find`, `alert`, `ping`, `hub`, `gkick`, `conduit`)
* `/send <player> <server>` — move one player
* `/send <server> <server>` — move everyone on a backend (bounded concurrency; a player who fails to move stays put)
* `/send current <server>` — move yourself
* `/glist`, `/plist <server>`, `/find <player>`, `/alert <message>`, `/ping`, `/hub`, `/gkick <player> [reason]`
* `/conduit` — branded version line plus current server and counts
* `/conduit servers` — name + Online/Offline status (more detail than `/server`, still compact)
* `/conduit health` — cached backend health with hysteresis counters
* `/conduit maintenance <on|off|status>` — native maintenance mode
* `/conduit drain|undrain <server>` — rolling-restart drain
* `/conduit doctor` / `/conduit diagnostics` — operator checks (no secrets)
* `/conduit attack <on|off|status>` — runtime attack-mode tightening
* `/conduit cache invalidate <address>` — drop a mod-handshake cache entry
* `/conduit uptime`, `/conduit metrics` — operator readouts
* `/conduit dump` — counters, version and server names to a text file; safe to paste into an issue
* `/conduit heap` — JVM heap dump. **Contains the forwarding secret, session tokens and player data.** Treat the `.hprof` as a credential

Both write to `dumps/` beside `conduit.toml` — never the shell's working directory — with a timestamped
name no command argument can influence. `dumps/` is in the shipped `.gitignore`; on POSIX the directory
is created `rwx------`.
* `/conduit reload` — live-safe reload; lists exact restart-required keys
* `/conduit plugins` — proxy plugins only (does not shadow Paper `/plugins`)
* `/conduit info` (default) and `/conduit help` — permission-filtered list

No built-in command has a short alias; the `/<server>` shortcuts above are the only extra names Conduit
registers. Plugins may register aliases of their own. Command names are case-insensitive.

Permissions: `conduit.server`, `conduit.server.send`, `conduit.server.send.player`, `conduit.server.send.mass`, `conduit.info`, `conduit.maintenance.bypass`, `conduit.drain.bypass`, …
`/send <your own name> <server>` needs only `conduit.server.send`; `conduit.server.send.player` is for moving somebody else.

## Operations (Phase 1)

| Feature | Status |
|---------|--------|
| Maintenance mode (+ flag persistence, MOTD, allowlist) | IMPLEMENTED |
| Backend health probes + hysteresis (3 fail / 2 recover defaults) | IMPLEMENTED |
| Drain / undrain | IMPLEMENTED |
| Health-aware fallback / initial routing | IMPLEMENTED |
| Client version gating (`[versions]`) | IMPLEMENTED |
| Graceful shutdown (bounded transfer then disconnect) | IMPLEMENTED |
| Config migration foundation (append missing Ops defaults) | PARTIAL (flat loader; comments best-effort) |
| Metrics HTTP endpoint | UNSUPPORTED (command diagnostics only) |
| Security (throttle / bot filter / channel guard / attack mode) | IMPLEMENTED (application-level; not DDoS protection) |
| Modded (known-packs / detection / Forge / NeoForge / Fabric routing / packet queue) | IMPLEMENTED (protocol-level; **NOT REAL-CLIENT VERIFIED** for Forge/NeoForge) |

### Security (Phase 2)

Application-level abuse mitigation only — not upstream DDoS protection.

* **Connection throttle** — early accept-path limits per IPv4 /32 or IPv6 /64 (NAT-friendly defaults: 40 attempts / 1s window, 32 concurrent). Aggregate drop logs.
* **Bot filter** — strikes for idle/malformed TCP; status/list pings are legitimate; temporary blocks after threshold. Handshake read timeout cancels once Minecraft data arrives.
* **Channel guard** — configurable plugin-message channel rules (`log` / `drop` / `kick`); unknown channels allowed; `minecraft:brand` and `velocity:*` never blocked by default. Disabled by default.
* **Attack mode** — `/conduit attack on|off|status` tightens live throttle/bot thresholds. **Runtime only** (reset on restart).

Permissions: `conduit.attack`.

```toml
[security.throttle]
enabled = true
max-attempts = 40
window-ms = 1000
max-concurrent = 32
ipv4-prefix = 32
ipv6-prefix = 64

[security.bot-filter]
enabled = true
strike-threshold = 10
handshake-timeout-ms = 3000
block-duration-ms = 60000

[security.channel-guard]
enabled = false
default-action = "log"
block-list = ["wdl:init", "wdl:control"]
log-list = ["schematica"]

[security.attack-mode]
throttle-max-attempts = 8
bot-strike-threshold = 3
```

### Modded / Forge / NeoForge (Phase 3)

Protocol-level mod compatibility — not a claim of “all Forge versions”.

| Area | Status |
|------|--------|
| Known-packs limit (default 1024) + validation | IMPLEMENTED |
| Mod loader detection (Vanilla / Fabric / Forge / NeoForge / Unknown) | IMPLEMENTED |
| FML1 / FML2 / FML3 address-marker strip/preserve | IMPLEMENTED |
| Handshake classification cache (bounded + TTL) | IMPLEMENTED |
| Per-server `mod-loaders` routing | IMPLEMENTED |
| Switch packet queue (bounded) | IMPLEMENTED |
| Forge / NeoForge real-client join | **NOT REAL-CLIENT VERIFIED** |
| Full Forge handshake proxying for every FML version | PARTIAL (markers + channels + routing; not every handshake payload rewritten) |

* Unknown clients default to **allow** (`modded.unknown-policy = "allow"`).
* Backends without `mod-loaders` accept all families (vanilla-friendly).
* Modded clients are not treated as bots merely for Forge/Fabric channels.
* `/conduit cache invalidate <source>` clears handshake-cache entries for a source IP.
* Permissions: `conduit.cache`.

```toml
[modded]
enabled = true
known-packs-limit = 1024
handshake-cache = true
handshake-cache-capacity = 4096
handshake-cache-ttl-ms = 300000
forge-compat = true
neoforge-compat = true
fabric-compat = true
unknown-policy = "allow"
packet-queue-enabled = true
packet-queue-max-depth = 512
log-mod-handshakes = false

# Optional alias:
# [protocol]
# known-packs-limit = 1024

[servers.lobby]
host = "127.0.0.1"
port = 25566
# omit mod-loaders = accept all
# mod-loaders = ["vanilla", "fabric"]

[servers.forge]
host = "127.0.0.1"
port = 25567
mod-loaders = ["forge", "neoforge"]
```

Unhealthy backends are excluded from **new** routing only. Existing players are not kicked by a failed probe.

Version gate is separate from protocol translation. `versions.strict-backend-match=true` refuses switches when Conduit has no translator between client and advertised backend codecs (default `false` keeps Via-style backends usable).

## Server switching

Switches **prepare** the target backend while the current backend stays active (`CONNECTED`).
Only after login/forwarding succeeds does Conduit **commit** (brief `SWITCHING` + client reconfiguration when required).

If the target refuses, times out (~4s), or fails before the client leaves Play:

* the attempt is discarded
* the player stays on the current server
* message: `<server> is unavailable. Please try again later.`

Once the client has entered Minecraft's configuration phase for a switch, some failures may still require disconnecting the client (protocol limitation).
## Version support

| Client | Backend | Mode | Status |
|--------|---------|------|--------|
| 1.20.4 (765) | 1.20.4 (765) | DIRECT | Tested |
| 1.20.5 (766) | 1.20.5 (766) | DIRECT | Codec present; unit-tested |
| 26.2 (776) | 26.2 (776) | DIRECT | Tested |
| 1.20.1 (763) | 1.20.1 (763) | DIRECT | Codec present; limited testing |
| 765 ↔ 766 | login/config/control | TRANSLATED (PARTIAL) | Semantic codec + golden unit tests; **NOT REAL-CLIENT VERIFIED** |
| 765 ↔ 776 | — | UNSUPPORTED in Conduit | Needs backend ViaVersion or a future translator |
| 1.7.10–1.19.x | — | UNSUPPORTED | Catalog identities only — no codecs |

Do **not** read this as “supports 1.7.10–26.2”. Only listed DIRECT codecs and the PARTIAL 765↔766 translator are real.

### Protocol translation architecture (Phase 4)

Pipeline (already framed / decompressed / decrypted):

`wire packet → SemanticCodec.decode → ProtocolTranslator → SemanticCodec.encode → wire packet`

* Client and backend protocol versions are tracked independently on `PlayerSession`.
* `ProtocolCompatibility`: `DIRECT` | `TRANSLATED` | `UNSUPPORTED`.
* Semantic packets live under `gg.tame.conduit.protocol.semantic.*`.
* Pair translator: `gg.tame.conduit.protocol.translate.Protocol765To766Translator`.
* Trace: `-Dconduit.trace=true` (no secrets).
* Unknown / unsafe packets fail closed with `TranslationException` — never blind ID forwarding.

`PlayerSession` tracks `clientProtocol` and `backendProtocol` independently for that future work.

Permission nodes (replaceable `PermissionProvider`; default is permissive):

* `conduit.server`
* `conduit.server.send`
* `conduit.server.send.player`
* `conduit.server.send.mass`
* `conduit.info`
* `conduit.admin`
* `conduit.command.plugins` / `glist` / `find` / `alert` / `ping` / `hub` / `gkick` / `plist`
* `conduit.command.dump` / `heap` / `reload` / `uptime`
* `conduit.command.maintenance` / `drain` / `health` / `doctor` / `diagnostics`
* `conduit.attack`, `conduit.cache`

`conduit.admin` satisfies any `/conduit` subcommand node on its own.

`/send` never lists backend addresses. Mass moves run with bounded concurrency; a failed player stays on the source backend.

Proxy commands are intercepted and not forwarded to Paper. Other commands are forwarded.

On protocol 765, Conduit merges `/server`, `/conduit`, and `/send` — plus every `/conduit`
subcommand, the `/<server>` shortcuts, and any command a plugin registered, aliases included —
into the backend **Declare Commands** tree (brigadier node indexes are rewritten). If a backend
tree cannot be decoded, the original backend packet is forwarded unchanged (those proxy commands
may still execute but can appear red).

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

## Online-mode flag and TAB faces

26.2's Join Game (`ClientboundLoginPacket`) ends with `onlineMode` then `enforcesSecureChat`, both
single-byte booleans. `PlayerTabOverlay.render` reads `ClientPacketListener.onlineMode()` and skips
the player-list **face** when it is false, while still drawing the name and ping bars.

Backends behind modern forwarding run `online-mode=false` and report false, so every TAB face
disappeared on every server, initial join included. The player-info entry and its signed `textures`
were correct throughout; the face was never drawn to begin with.

Conduit performs the Mojang session handshake itself, so for an authenticated profile it sets that
flag before forwarding Join Game. It is never set for an offline-mode session.

The flag is the second-to-last byte, so `CommonPlayerSpawnInfo` (whose layout moves between
versions) is never decoded. Both trailing bytes are validated as booleans first; anything else
leaves the packet untouched. 765 has no such field and is not touched.

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

## Performance

Measured causes of proxy-side hitching (not Minecraft server tick lag):

1. **Flush after every packet** — `MinecraftFrames.write` used to `flush()` every frame, producing syscall spikes and stally movement.
2. **Registry Key allocation** — `ProtocolDefinition.is` allocated a `Key` on every packet-id check.
3. **Per-packet stream wrappers** — packet id reads created `ByteArrayInputStream`/`DataInputStream` for every frame.
4. **AES/CFB8** — encrypt/decrypt allocated a `byte[1]` and a new `cipher.update` array per byte.
5. **Inflater per compressed packet** — new `Inflater`/`Deflater` on every wrap/unwrap.
6. **15ms poll** while switching starved backend reads instead of waiting on session state.

Steady play now coalesces TCP writes (`writeUnflushed` + flush when the opposite socket has no more queued bytes), uses TCP_NODELAY, reuses crypto/compression buffers, and bounds deferred Play (512 packets).

Event-loop utilization is **not applicable**: Conduit uses blocking sockets on virtual threads, not a shared NIO selector. Mojang HTTPS runs on the connecting virtual thread before Play.

Metrics (quiet; packet tracing remains `-Dconduit.trace=true`):

`players`, `backends`, packets/sec, bytes/sec, authentications, backend connect ms, switch ms, decode/encode failures.

## Native plugin API

Package `gg.tame.conduit.api`. Plugins are JARs in `plugins/` with `conduit-plugin.yml`:

```yaml
id: example
name: ExamplePlugin
version: 1.0.0
main: com.example.ExamplePlugin
api-version: 1
```

`api-version` is the Conduit API integer, not a Minecraft protocol. Incompatible plugins are rejected.

Optional `depend: [other-plugin]` orders loading; a plugin whose dependencies are missing is skipped.

```java
package example;

import gg.tame.conduit.api.command.CommandManager;
import gg.tame.conduit.api.event.Subscribe;
import gg.tame.conduit.api.event.player.PlayerServerConnectedEvent;
import gg.tame.conduit.api.plugin.ConduitPlugin;
import gg.tame.conduit.api.text.Text;
import java.time.Duration;

public final class ExamplePlugin extends ConduitPlugin {
  @Override public void onEnable() {
    proxy().commands().register(this, CommandManager.Command.builder("example")
        .alias("ex")
        .permission("example.use")
        .handler((source, arguments) -> source.sendMessage(Text.of("hello " + source.username())))
        .completer((source, arguments) -> java.util.List.of("here", "there"))
        .build());
    proxy().events().register(this, this);
    getScheduler().buildTask(this, () -> getLogger().info("tick"))
        .delay(Duration.ofSeconds(5)).repeat(Duration.ofMinutes(1)).schedule();
  }

  @Subscribe public void onConnected(PlayerServerConnectedEvent event) {
    getLogger().info(event.player().username() + " reached " + event.target().getName());
  }
}
```

A `@Subscribe` method takes exactly one event and may declare a supertype of the ones it wants (`Event`
itself catches everything); a parameter that is not an `Event` is rejected at registration. Settings go in
`plugins/<id>/`, which `dataDirectory()` returns; `SimplePluginConfiguration.load(path, defaults)` reads a
`key=value` file there, creating it from the defaults and appending any key it is missing.

Lifecycle: discover → validate → classload → dependency order → onLoad/onEnable. Disable unregisters events, commands, and scheduler tasks and closes the plugin classloader. Data lives in `plugins/<id>/`.

A jar that fails any of those steps is logged and skipped — a bad descriptor, a duplicate id, a main class
that will not initialize, or an `onEnable` that throws never stops the proxy from starting or the other
plugins from loading, and the rejected jar's classloader is closed so the file is not left locked.

Events include proxy start/shutdown, login/auth/disconnect, server connect/connected/switch/failed (cancellable connect), chat/command execute, plugin enable/disable, plugin messaging.

Scheduler tasks run on `conduit-scheduler` threads, never on player socket threads.

## Velocity compatibility

**PARTIAL — verified with an in-repo plugin compiled against `com.velocitypowered:velocity-api`.**

Not verified with LuckPerms, ViaVersion, or other production Velocity plugins.

Architecture:

```text
Velocity plugin JAR (velocity-plugin.json)
      ↓
src/compat-velocity adapters
      ↓
native Conduit API
      ↓
Conduit core
```

Fetch compile-time jars (never a Conduit core dependency):

```powershell
./scripts/fetch-velocity-compat.ps1
```

`./scripts/test.ps1` compiles core, then `src/compat-velocity` against `lib/*.jar` (includes `slf4j-nop`), then runs Phase9 which builds a real Velocity-API plugin and loads it.

See `docs/VELOCITY_COMPATIBILITY.md` for the support matrix. Unsupported APIs throw; they are never faked.

## Protocol translation

**PARTIAL.** `765↔766` semantic translation exists for known control packets. `765→776` remains `UNSUPPORTED`. `Protocol765To776Translator` still throws. Identity forwarding is same-codec only. Join Game / full play remapping is not claimed.

## Out of scope here

Full Velocity API packages, BungeeGuard, and a finished 765↔776 translator.

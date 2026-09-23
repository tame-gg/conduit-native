# Conduit

Conduit is an independently implemented Minecraft: Java Edition proxy foundation. It is not a
Velocity or Velocity-CTD fork, and it has no dependency on either implementation.

`tame-gg/conduit` is intentionally separate and untouched.

Current version: **0.9.7**. Native plugin API version: **1**.

## License

Conduit is free software, licensed under the GNU General Public License version 3 or (at your
option) any later version (`GPL-3.0-or-later`). See `LICENSE`. Third-party components keep their
own licenses; they are listed in `THIRD-PARTY-NOTICES`. `docs/LICENSING_VIA.md` explains how Conduit
uses the GPL ViaVersion projects and what a binary distribution has to carry. `gradle distZip`
builds one that includes its Corresponding Source. Each Java source file names its license with an
`SPDX-License-Identifier` line.

## Build and test

Requires a JDK capable of compiling Java 21 source. On Windows:

```powershell
./scripts/fetch-via.ps1   # ViaVersion ecosystem jars: required to compile and start, downloaded, never committed
./scripts/test.ps1
./scripts/run.ps1 -ConfigPath run/conduit.toml
```

`--check-config <path>` validates a configuration without binding a listener. A mistake in the file stops
the check, or the start, with one line and no stack trace naming the file, the line, the setting, what is
written there and what is allowed, for example
`Configuration error: conduit.toml line 3: listener.port must be 1..65535, found 70000`. A legal but
doubtful value is one `WARN` instead: a server host that does not resolve (Conduit looks it up only at
start), or `versions.enabled = true` with nothing to gate. `#` starts a comment anywhere outside a quoted
string. Every path in a configuration -- `plugins/`, `via/`, `forwarding.secret`, `status.favicon` -- is
resolved against the folder that configuration is in, so the file belongs in the folder you start
Conduit from, not in a `config/` subfolder of it.

### Starting from nothing

`java -jar conduit-<version>.jar conduit.toml` in an empty folder is enough. There is no setup step:
Conduit ships the configuration it would otherwise have complained was missing, so a first start
writes `conduit.toml`, creates `plugins/`, generates `forwarding.secret` and comes up on the
defaults. ViaVersion is not inside the jar: the first start that can reach
`repo.viaversion.com` installs it into `lib/via`, and a start that cannot says so and carries on with
cross-version play off.

The defaults are the safe ones, not the convenient ones: `authentication.mode = "online"`, so Conduit
does the Mojang check, and `forwarding.mode = "none"`, so nothing is forwarded until you say what to
forward it with. The secret is generated anyway, so turning forwarding on later is one line in
`conduit.toml` plus a copy into each backend.

### Upgrading

A newer Conduit rewrites `conduit.toml` into its own layout and keeps every value you set. Settings
introduced since your file was written arrive in their proper section rather than appended to the
bottom; a setting this version does not read is moved to the end of the file under a header saying so,
never dropped; and the file you had is kept beside it as `conduit.toml.bak-<schema>`. `[ops]
schema-version` is how Conduit knows which layout your file is in, and is the one line in it that is
not yours to set.

### Where ViaVersion comes from

ViaVersion lives in `lib/via` beside the configuration, not in the jar. Two reasons: it is
GPL-3.0-or-later object code, so a jar carrying it could only be handed on together with Via's own
Corresponding Source, and it would be out of date within weeks -- Via supports each new Minecraft
release long before a Conduit release can, and 5.12.0 registers Minecraft 26.3.

A first start with nothing in `lib/via` installs the set this build is pinned to from
`repo.viaversion.com`, each jar checked against the SHA-256 published beside it. After that, with
`[updates] via = true` (the default), Conduit looks for a newer release on each start and downloads
it into the same place, keeping the jars it replaces in `lib/via/superseded`. The Apache-2.0
libraries Via needs -- fastutil, Netty, Guava -- are in the jar, so what is fetched is Via itself
and nothing else.

Four rules keep that from being worse than no updater at all:

- **It cannot stop Conduit starting.** An unreachable repository, a blocked proxy, a full disk or a
  checksum that does not match all end the same way: a line in the log and a proxy that comes up. If
  there is no Via at all, cross-version play is off and the log says so; a client may still join any
  backend on its own protocol.
- **Releases only, and inside the version line.** Snapshots are ignored, and a new major version is
  reported rather than taken -- `gg.tame.conduit.viaversion` extends Via's internal classes, and a
  major bump is where those change.
- **The whole set or none of it.** An updated `viaversion-common` against an older `viaversion-api`
  is the one combination guaranteed not to work, so a partial `lib/via` is ignored with a warning.
- **Never a downgrade.** A stale `lib/via` left over from an older Conduit is ignored rather than used.

For a machine with no route to the internet, put the five jars in `lib/via` yourself -- the versions
this build pins are in `src/main/resources/gg/tame/conduit/via-bundled.properties` -- or point
`-Dconduit.via.repository=<url>` at an internal mirror of the same layout.

`updates.check-only = true` reports a newer version without downloading it. `-Dconduit.via.update=false`
turns the check off for one start, and `-Dconduit.via.repository=<url>` points it at an internal mirror
of the same layout. `--check-config` never touches the network.

The check is the one thing between starting Conduit and Conduit listening that waits on somebody else's
server, so it is remembered: `updates.via-check-interval-hours` (default 12) is how long an answer
stands for, and a start within that window does not ask again. A check that could not reach the
repository is not remembered, so an unreachable network is retried on the next start rather than held
off for the interval. Set it to 0 to check on every start. The time of the last successful check is
kept in `lib/via/.last-update-check`, and deleting that file forces the next start to check.

Note that an updated Via can know Minecraft versions Conduit has no packet table for. A client on one
of those is still refused -- Conduit reads a client's own packets before any translator is chosen -- but
a backend on one is not, so raising Via past what Conduit knows is not the same as Conduit supporting
that version.

### The console

Ordinary lines are white, warnings yellow and errors red, and ViaVersion and the Velocity
compatibility layer log through the same formatter rather than `java.util.logging`'s two-line default.
Colour is off when output is redirected, when `NO_COLOR` is set and when `TERM=dumb`;
`-Dconduit.color=true` or `=false` overrides all of that, which is what an old `conhost` without
virtual-terminal processing needs.

Everything the console shows is also kept in `logs/latest.log` beside `conduit.toml`, without the
colour codes: Conduit's lines, ViaVersion's, plugins', command replies and stack traces. On each
start the previous session's log is compressed to `logs/<date>-<n>.log.gz`. Attach those to a bug
report. The JVM's own start-up warnings go around Java's streams and are on the console only.

### ViaVersion translation (on by default)

`[translation] enabled = true` is the default, with `engine = "via-preferred"`: ViaVersion /
ViaBackwards / ViaRewind are the cross-version engine and Conduit's native translators are the
fallback for any ordered pair Via has no path for. This is what gives a cross-version player
scoreboards, titles and boss bars, which the native pairs below drop — and sounds and particles on
every native pair except 393 ↔ 765, which now has generated tables of its own.

Set `enabled = false` for native-only translation, and read the completeness column before you do.
See `docs/VIAVERSION.md` and `docs/LICENSING_VIA.md`. **Minecraft 26.3 is not supported by Via
5.11.0.**

## Supported Minecraft versions

### Modern compatibility program (1.13 → 26.2)

Conduit's current roadmap targets the **modern Java protocol era**: Minecraft **1.13 through 26.2**.

**Minecraft 1.12.2 and older are out of scope for this program**, which is not the same as being refused: 1.7.6, 1.8.x and 1.12.2 each have a declared codec, so a client on one logs in and is carried across by ViaRewind or ViaVersion. What they do not get is the modern program's work -- no native translators, no end-to-end verification. Treat them as untested.

**The oldest client Conduit admits is 1.7.6** (protocol 5). A 1.7.5 or older client is turned away at the handshake with "Unsupported Minecraft version", whatever ViaVersion reports as its own floor at start: the login path needs a codec of Conduit's own, and there is none below protocol 5. A proper legacy project comes later.

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
| 26.2 | 776 | yes | DIRECT / VERIFIED — the official 26.2 client joined a real 26.2 server through Conduit and stood in the world, on the DIRECT path (`config/conduit-26.2.toml`) |

### Catalog releases (identity only unless codec listed above)

Modern named releases are mapped from public protocol data (Minecraft Wiki / PrismarineJS), including aliases that share a protocol number (e.g. 1.20 + 1.20.1 → 763, 1.16.4 + 1.16.5 → 754).

Pre-1.13 codecs, declared rather than verified: 1.7.6-1.7.10 (5), 1.8.x (47), 1.12.2 (340). Each covers the packets Conduit reads on its own behalf -- handshake, login, disconnect -- from published ids, with the cross-version work left to ViaRewind and ViaVersion. Enough to join and be routed; not exercised by the modern program's testing.

Unknown handshake versions disconnect. They are never decoded as 1.20.4.

### Translation matrix

**This table is about Conduit's own translators, which run when `[translation] enabled = false`
or when Via has no path for the pair. It is not a list of what a version supports.** On a DIRECT
path — client and backend on the same codec — nothing is translated and nothing is dropped, and
with the default `via-preferred` engine Via carries the cross-version pairs instead. No Minecraft
version loses sounds because of anything in this table.

| Client → Backend | Native support | Completeness of the native path |
|---|---|---|
| same codec (393, 763, 765, 766, 776) | DIRECT | FULL for mature paths; 393 PARTIAL until real-client verified |
| **393 ↔ 765** | **TRANSLATED** | **CORE GAMEPLAY VERIFIED (bidirectional)** — login, world, movement, blocks, entities, health, chat, items, inventory, containers (including chest open/click/close), equipment, metadata and attributes against real vanilla clients/servers via scripted probes. Sounds and particles are translated in both directions through generated registry tables (662 ↔ 1539 sound events, 50 ↔ 101 particle types, all resolved by name), including a plugin's own sounds via Named Sound Effect and the block, item and dust payloads particles carry. Scoreboards, titles, boss bars, block-entity data, recipes and advancements are still dropped on purpose by *this translator*; Via carries them. Not equivalent to a human gameplay session. See `docs/VALIDATION_393_765.md`. |
| 765 ↔ 766 | TRANSLATED | PARTIAL (control/login/config; Join Game unsupported) |
| 765 → 776 | none | no native translator; carried by Via under the default engine |

**393 ↔ 765 is CORE GAMEPLAY VERIFIED, not FULL.** Ordinary survival play crosses
the pair in both directions under scripted real-client/server probes, but the
dropped list above is intentional for the native translator and human
mouse/keyboard play was not exercised here. Sounds and particles are no longer on
it: both are checked against real 1.13 and 1.20.4 servers, comparing the registry
id that reaches the client against Mojang's own registry report rather than
against Conduit's own table. Nobody has listened to or looked at them, though.

To try 26.2 yourself: `scripts/build-dist.ps1` packages a runnable build into `dist/`, and `dist/run.cmd conduit-26.2.toml` starts it on the 26.2 test configuration packaged beside it, which routes to the provisioned 26.2, second 26.2 and 1.20.4 backends so `/server` covers DIRECT, a same-version switch and the Via-carried cross-version hop.

26.2 clientbound `minecraft:hello` (Encryption Request) includes a trailing **Should Authenticate** boolean that 1.20.4 does not. Initial routing prefers backends whose probed protocol is DIRECT for the connecting client (so 26.2 clients skip 1.20.4 lobby).

Backends may be configured as:

```toml
[servers.smp]
address = "203.0.113.10:25921"
```

or `host` + `port`. Commands never print those addresses. At startup Conduit status-pings each backend and logs its advertised protocol.

Lobby and survival are Paper 1.20.4 with **ViaVersion 5.11.0**, so a 26.2 client can join them on the **client's** protocol (776). Status ping still reports 765; that is the native server version, not a Conduit translator.

Backend Set Compression is consumed by Conduit and never forwarded to the client: forwarding it caused 26.2 vanilla `DataFormatException: incorrect header check`. The client link is compressed separately, by Conduit's own Set Compression sent just before the client's Login Success, at `listener.compression-threshold` (default 256, `-1` for off; 1.7 clients have no compression). Conduit unwraps compressed backend frames and re-compresses what it sends each client.

## Authentication

```toml
[authentication]
mode = "offline"   # or "online"
# session-url = "https://sessionserver.mojang.com/session/minecraft/hasJoined"
# timeout-millis = 5000
# kick-existing-players = false
```

### One session per player

A login of a player who is already connected is refused ("You are already connected to this
network."): the same UUID, or the same name in any letter case, which in offline mode is what the
UUID comes from. The refusal is Conduit's own, made before any plugin sees the new login. A session
that is already ending (the player quit and rejoined at once) is waited for, up to 12 seconds, and
its `PlayerDisconnectEvent` reaches plugins before the new login's `PlayerSetupEvent`. With
`kick-existing-players = true` the new login wins instead: the existing session is kicked with "You
logged in from another location." and the new login waits for it to end. That happens as soon as the
new login has authenticated, before maintenance or any plugin decides on it. In offline mode that lets
anyone who types a player's name kick them, so it is off by default. It is read at each login, so
`/conduit reload` applies it unless a setting that needs a restart changed in the same reload.
A plugin that replaces a player's profile (`GameProfileRequestEvent`) does not get round this: the
account that logged in and the replacement are both counted as connected.

### Transfers (1.20.5+)

A client another server sent here with a Transfer packet (handshake intent 3) logs in like any other;
there is no setting that refuses transfers, but a `PlayerPreLoginEvent` listener sees
`transferred()` and may deny them. Its backends are asked for an ordinary login, since a vanilla server
refuses transfers by default. `Player.transferred()` says how a player arrived, and
`Player.transferToHost(host, port)` sends a 1.20.5+ player elsewhere with a Transfer packet (false for an
older client). Every Transfer -- a plugin's, or one the backend sends -- fires `PlayerTransferEvent`
first, which can cancel it or change the address. A 1.20.4 or older handshake with intent 3 is
malformed and closed, as before.

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
in that incident (no reconnect loop). If none accept, the client is disconnected with a message naming
the server it lost. A `PlayerKickedFromServerEvent` redirect to a server the same join or fallback has
already tried is ignored, so a listener that sends every refused player to the lobby cannot send them
back to a lobby that refused them.

`/server` only lists configured names. Addresses, ports, and secrets are never shown.

Server names ignore case, so `[servers.Lobby]` beside `[servers.lobby]` is refused, and `routing.initial` and
`routing.fallback` must spell each name as its `[servers.<name>]` header does. A server at Conduit's own
listener address and port is refused.

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
* `/gban <player|address> [duration] [reason]` — ban from the whole network; no duration means permanent
  (in online mode a name is looked up at Mojang first: a name no account has is refused, and a real
  one's account UUID is banned with it, so a name change does not get round the ban)
* `/gunban <player|address>` — lift it
* `/gbanlist [page]` — every ban in force: the reason, who made it and how long it has left
* `/gwhitelist <on|off|add|remove|list|clear|status>` — close the network to all but a list
* `/conduit` — the subcommands you may run; `/conduit info` is the branded version line plus current server and counts
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
* `/conduit shutdown [reason]` — console only: the graceful shutdown, every player shown the reason
  (or the configured shutdown message). Ctrl+C, or a service manager's stop, runs the same shutdown.
* `stop [reason]` / `end [reason]` — console only: the same shutdown, under the names hosting panels send.
  A player's `/stop` is not Conduit's and goes to the backend.
* `/conduit plugins` — proxy plugins only (does not shadow Paper `/plugins`)
* `/conduit info` (default) and `/conduit help` — permission-filtered list

No built-in command but the console's `stop`/`end` has an alias; the `/<server>` shortcuts above are the only extra names Conduit
registers. Plugins may register aliases of their own. Command names are case-insensitive. A name may start
with a slash, as WorldEdit's do: players type one more (`//wand` runs `/wand`, never `wand`).

Permissions: `/server`, the `/<server>` shortcuts, `/hub`, `/ping` and `/conduit help` need none, so every
player can pick a server with or without a permissions plugin. Every administrative command, and every
`/conduit` subcommand, has a node of its own (see [Permission nodes](#permission-nodes)): `conduit.command.doctor`
gives `/conduit doctor` and nothing else. For a player who holds no `/conduit` node at all, `/conduit` is not
there and their line goes to the backend. `/conduit shutdown` is the console's alone.
Maintenance lets in the names on its allowlist and players a permission plugin grants `conduit.maintenance.bypass`
or `conduit.admin`, asked once the plugin has set the player up.

With no permissions plugin, Conduit's default provider grants a player **no node at all** — not Conduit's,
not another plugin's: the administrative commands are the console's until a plugin such as LuckPerms hands
them out. That is what a Velocity plugin already expects of a check nobody answered.

## Backend plugins: the BungeeCord channel

A plugin running on a Paper or Spigot backend has no API to reach its proxy with. What it has is a
plugin message on the channel `BungeeCord`, renamed `bungeecord:main` when 1.13 made channel names
namespaced. A hub plugin's `/hub`, and a queue plugin that runs on a backend rather than on the
proxy, is that message and nothing else. Conduit answers it, under both names, so those plugins work
unchanged.

```toml
[messaging]
bungeecord-channel = true
```

Supported: `Connect`, `ConnectOther`, `IP`, `IPOther`, `PlayerCount`, `PlayerList`, `GetServers`,
`GetServer`, `UUID`, `UUIDOther`, `ServerIP`, `Message`, `MessageRaw`, `KickPlayer`, `Forward` and
`ForwardToPlayer`.

`Connect` goes through the same path `/server` takes, so backend health, the switch's own rules and
every event a plugin listens for all still apply: a plugin message is not a way around what a
command has to obey. A message on this channel is consumed and never passed on to the client, since
it is addressed to the proxy and a client has no business being handed the network's player list;
plugins still see it first through `PluginMessageEvent` and may cancel it. It is answered only for a
message that arrived **from a backend**, never from a client, so a modded client cannot forge a
`KickPlayer` for somebody else.

`Forward` and `ForwardToPlayer` carry a plugin's own payload between backends, passed through
untouched and never interpreted. One limit is worth knowing, and it belongs to the protocol rather
than to Conduit: a message reaches a server through a player already on it, because that is the only
connection a proxy holds to a backend. **A server with nobody on it cannot be reached**, whether the
target is named, `ALL` or `ONLINE`. BungeeCord and Velocity have the same limit.

It is on by default: a plugin that expects this channel and does not get it fails *silently*, since
the message is delivered and simply never acted on, and that is a far worse default than answering.
Turn it off for a network where no backend is trusted to move, kick and message players.

Proxy-side plugins are a different question: a queue or hub plugin built as a **Velocity** jar goes
through the compatibility adapter instead, and what it can do is in `docs/VELOCITY_COMPATIBILITY.md`.

## Bans and the whitelist

Both are operational state rather than configuration: they live in `bans.txt` and `whitelist.txt`
beside `conduit.toml`, are written the moment a command changes them, and are in force on the next
login attempt. Nothing here is read from `conduit.toml` and nothing needs `/conduit reload`.

```
/gban Steve griefing               a permanent ban, with a reason
/gban Steve 7d griefing            the same for a week; 30m, 2h, 7d, 4w and perm are understood
/gban 198.51.100.7 open proxy      an address instead of a name
/gunban Steve
/gwhitelist on
/gwhitelist add Steve
```

A ban is matched on three things, so it holds: the name (case-insensitively), the account, and the
address. Banning someone who is online bans their account alongside their name, so changing it does
not get them back in, and kicks them with the message the ban will show them from now on. Banning an
address kicks everyone connected from it. A temporary ban simply stops matching when its time is up
and is dropped from the file the next time it is written; the kick screen tells the player how long
is left, or that it is permanent. An offline player's account cannot be banned, only their name:
Conduit does not look names up at Mojang, and a guessed UUID is worse than none.

The whitelist is checked after bans and before maintenance, so a player has to pass all three.
`conduit.whitelist.bypass` gets in without being on the list, which is how the staff who turned it
on do not lock themselves out. Taking someone off while it is on also kicks them. It is deliberately
not the maintenance allowlist: maintenance is a passing state with its own server-list message, and
the whitelist is a standing policy edited in place.

Both files are plain text, one record per line, and may be edited by hand while the proxy is
stopped. A line that cannot be read is skipped with a warning rather than taking the start down, and
a `whitelist.txt` that cannot be read at all leaves the whitelist **off** — an unreadable file
must not be the reason a whole network is shut out.

## Server list

```toml
[status]
motd = "&bConduit&r network\n&7Two lines, if you like"
display-max-players = 100
favicon = "server-icon.png"
player-sample = 12
player-sample-server = false
```

`motd` takes `&` codes: `&0`-`&9` and `&a`-`&f` colours (which, as in the game, clear every decoration),
`&l` bold, `&o` italic, `&n` underlined, `&m` strikethrough, `&k` obfuscated, `&r` plain; `\n` starts the
second line. It is
the plain `"Conduit"` when unset. `display-max-players` (default 100) is only the number shown after the
slash: Conduit has no join cap. `favicon` is a 64x64 PNG, relative to the config file; one that cannot be
read, is the wrong size, or is over about 20 KB is logged at load and the list shows no icon. The answer
always carries the real online count and, when the player count is hovered, the names of up to
`player-sample` players (default 12) from across the network -- 0 names nobody, for a network where who
is online is not public. `player-sample-server = true` follows each name with the server that player is
on, as `Steve (lobby)`. Maintenance's MOTD and the
version gate's name and message replace these while they apply, and a plugin can change any of it in
`ServerListPingEvent`. Applied live by `/conduit reload`.

## Operations (Phase 1)

| Feature | Status |
|---------|--------|
| Maintenance mode (+ flag persistence, MOTD, allowlist) | IMPLEMENTED |
| Backend health probes + hysteresis (3 fail / 2 recover defaults) | IMPLEMENTED |
| Drain / undrain | IMPLEMENTED |
| Health-aware fallback / initial routing | IMPLEMENTED |
| Client version gating (`[versions]`) | IMPLEMENTED |
| Graceful shutdown (players told why, then disconnected) | IMPLEMENTED |
| Config migration foundation (append missing Ops defaults) | PARTIAL (flat loader; comments best-effort) |
| Unknown settings (a misspelt key) named in a startup warning instead of silently ignored | IMPLEMENTED |
| Metrics HTTP endpoint (Prometheus text format) | IMPLEMENTED (optional, off by default; see Metrics below) |
| Security (throttle / bot filter / channel guard / attack mode) | IMPLEMENTED (application-level; not DDoS protection) |
| Modded (known-packs / detection / Forge / NeoForge / Fabric routing / packet queue) | IMPLEMENTED (protocol-level; real-client verified for NeoForge 20.2.93 only) |

### Metrics

`/conduit metrics` prints the counters and rates. With `[metrics] prometheus-address = "127.0.0.1:9225"`
Conduit also serves them at `GET /metrics` in Prometheus' text format: players by `path` (`direct`,
`translated`) and per server, open backend connections, enabled plugins, shutdown and maintenance state,
each backend's health, draining and last ping time, and counters for accepted, throttled and malformed
connections, bot-filter and channel-guard actions, authentications, sessions ended and their total
time in the game, backend connects and connect
failures, switches and switch failures, fallbacks, Via translation failures, plugin task failures, and
packets and bytes each way. It carries the operator's server names and nothing about players,
addresses, tokens or paths. It has no authentication: keep it on loopback or a private network. It is
off unless the address is set, the address takes effect at start, and one thread answers every
scrape; a connection whose exchange is not over within 3 s, a request sent in part and then left, is
closed. The address must resolve and must not share the port Conduit listens on.

### Security (Phase 2)

Application-level abuse mitigation only — not upstream DDoS protection.

* **Connection throttle** — early accept-path limits per IPv4 /32 or IPv6 /64 (NAT-friendly defaults: 40 attempts / 1s window, 32 concurrent). Aggregate drop logs.
* **Bot filter** — strikes for idle/malformed TCP; status/list pings are legitimate; temporary blocks after threshold. Handshake read timeout cancels once Minecraft data arrives.
* **Login deadline** — the handshake timeout bounds each read; on top of that a connection has 60 s from its handshake to reach Play (or finish a status exchange) for everything read from the client, so a peer that announces a large frame and trickles it byte by byte is cut off instead of holding its slot for hours.
* **Usernames** — a Login Start name must be 1–16 characters from `!` to `~`, the rule vanilla servers apply; anything else (empty, spaces, control characters, `§`, non-ASCII) is refused before any plugin sees the player, and the refusal never echoes the name into the log.
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
| Known-packs limit (default 1024) + validation; an over-limit client is told why and the log names the setting to raise | IMPLEMENTED |
| Mod loader detection (Vanilla / Fabric / Forge / NeoForge / Unknown) | IMPLEMENTED |
| FML1 / FML2 / FML3 address-marker strip/preserve | IMPLEMENTED |
| Handshake classification cache (bounded + TTL) | IMPLEMENTED |
| Per-server `mod-loaders` routing | IMPLEMENTED |
| Switch packet queue (bounded) | IMPLEMENTED |
| Forge / NeoForge real-client join | REAL-CLIENT VERIFIED for NeoForge 20.2.93 (MC 1.20.2) only; every other Forge/NeoForge version **NOT REAL-CLIENT VERIFIED** (see `docs/COMPATIBILITY.md`) |
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
While checks are enabled, a player's connection to a backend that cannot be reached at all (refused,
timed out) counts as a failed probe too, so the next player is not sent there; a backend that answers
and refuses the player (a whitelist, a full server) does not count. A switch to a server marked
unhealthy (`/server`, `/send`, a plugin's connect) fails at once, and `/hub` picks the first initial or
fallback server that is neither unhealthy nor draining. Servers plugins register are probed like
configured ones, and an unregistered server's health, drain flag and advertised version are forgotten.
Turning checks off (`/conduit reload`) clears every verdict, since no probe would be left to clear one.

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

### Permission nodes

Asked through `PermissionProvider`, which a permissions plugin replaces (LuckPerms does, through the
Velocity layer). The default grants a player no node, except that the operators named in
`[permissions] operators` (names or UUIDs) hold every `conduit.` node until a permissions plugin is
installed; the console holds every node.

A player may not `/gkick` or `/gban` another player who holds that same command's node, or
`conduit.punish.exempt`, so staff cannot turn those on each other. The console always may. A
permissions plugin answers only for the players it has loaded, so who held those nodes is recorded
at each login and leave in `protected-players.txt`, and `/gban` on someone offline is refused by that
record (or by `[permissions] operators`) rather than going ahead because nobody could be asked.

Staff are told when anyone is kicked, banned or unbanned (`[Staff] Kyle banned Griefer permanently: griefing`):
every player who may kick or ban, and anyone given `conduit.notify.moderation` to watch without acting.
The console is told too, so the line is in `logs/latest.log`.

* `/send` (a player, `current`, or a whole server): `conduit.command.send`
* `/glist`, `/plist`, `/find`, `/alert`, `/gkick`: `conduit.command.glist` / `plist` / `find` / `alert` / `gkick`
* `/conduit` (`info`), `servers`, `plugins`, `uptime`, `metrics`, `health`: `conduit.command.info` / `servers` /
  `plugins` / `uptime` / `metrics` / `health`
* `/conduit reload`, `maintenance`, `drain` and `undrain`, `doctor`, `diagnostics`, `attack`, `cache`, `dump`,
  `heap`: `conduit.command.reload` / `maintenance` / `drain` / `doctor` / `diagnostics` / `attack` / `cache` /
  `dump` / `heap`
* `/gban`, `/gunban` and `/gbanlist`: `conduit.command.gban`; `/gwhitelist`: `conduit.command.gwhitelist`
* `conduit.maintenance.bypass`, `conduit.drain.bypass`: past maintenance, onto a draining server
* `conduit.whitelist.bypass`: in while the whitelist is on, without being on it
* `conduit.admin`: stands for every `conduit.` node above, except one the permissions plugin denies
  outright — a player given `conduit.admin` and an explicit `false` on one node is refused that one

None for `/server`, the `/<server>` shortcuts, `/hub`, `/ping` or `/conduit help`. Tab completion and the
command tree a 1.13+ client is sent offer a player only the commands and `/conduit` subcommands they may run.
The tree is declared again whenever a command is registered or unregistered, or a permissions plugin installs
or withdraws its provider, so a connected player does not have to switch servers to see the change.

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

`forwarding.secret-file`, relative to `conduit.toml`, must exist and hold the secret. That is checked when
the configuration loads, so by `--check-config` and `conduit reload` too. `forwarding.mode` is `none` or
`modern`; `legacy` and `bungeeguard` are refused, as Conduit does not implement them.

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

Event-loop utilization is **not applicable**: Conduit uses blocking sockets on virtual threads (platform threads on Windows, because of JDK-8334574; see `SocketThreads`), not a shared NIO selector. Mojang HTTPS runs on the connecting thread before Play.

### How many players one instance holds

A session reads each of its two sockets on a thread of its own. On Linux those are virtual threads and
cost heap; on Windows they are platform threads, because of JDK-8334574, and cost an operating-system
thread each. So the same session costs about forty-five kilobytes on one platform and two OS threads on
the other, and that is the whole difference between the two rows below.

`scripts/load-probe.ps1` measures it: N fake players hold real 1.8.9 sessions on the DIRECT path, each
answering a keep-alive so both relay directions are known to be moving. Held twenty seconds, `-Xss512k`:

| Players | OS threads (Linux) | Heap (Linux) | OS threads (Windows) | Heap (Windows) |
|--:|--:|--:|--:|--:|
| 100 | 20 | 48 MiB | 228 | 47 MiB |
| 500 | 21 | 64 MiB | 1029 | 63 MiB |
| 1000 | 19 | 92 MiB | 2027 | 85 MiB |

Every connection stayed healthy in all six runs, Windows included: two thousand threads is expensive, not
broken. Measured on a 6-core Ubuntu 24.04 machine (kernel 6.8, Temurin 25, peak RSS 440 MiB for the whole
run) and a 12-core Windows 11 machine (Temurin 25).

**Run more than about 500 players on Linux.** Windows is fine for development and for a small server, but
its thread count grows with the player count and nothing in Conduit's configuration changes that -- it is
the workaround for a JDK bug, not a tuning choice. A Windows instance past a thousand players is spending
more on thread stacks and on scheduling them than on the proxying. The ceiling is not a hard limit and
Conduit will not refuse the connections; it is where the cost stops being worth it.

Raising the ceiling on Windows means reading connections on a selector instead of on a thread each, which
is a change to the relay rather than a setting. Until that exists, Linux is the answer.

Metrics (quiet; packet tracing remains `-Dconduit.trace=true`):

`players`, `backends`, packets/sec, bytes/sec, authentications, backend connect ms, switch ms, decode/encode failures,
backend connect failures, Via translation failures, plugin task failures.

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

Optional `depend: [other-plugin]` orders loading; a plugin whose dependencies are missing, or failed to
enable, is skipped. `optional-dependencies: [other-plugin]` orders loading when that plugin is present and
is ignored when it is not. Dependencies order enabling only; a plugin's classes cannot see another's.

```java
package com.example;

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

To compile a plugin, `./scripts/api-jar.ps1` builds `build/conduit-api-0.9.7.jar` (the version is
`Conduit.VERSION`) and its `-sources.jar`: the `gg.tame.conduit.api` classes and nothing else. Then:

```powershell
javac --release 21 -cp build/conduit-api-0.9.7.jar -d classes src/com/example/ExamplePlugin.java
jar --create --file plugins/example.jar conduit-plugin.yml -C classes .
```

With Gradle: `compileOnly(files("path/to/conduit-api-0.9.7.jar"))`. The proxy provides the API at run time,
so do not ship it inside the plugin. Use `gg.tame.conduit.api` only: the rest of Conduit (`session`,
`network`, `protocol` and so on) is internal and changes without notice. `ApiBoundaryTests` checks that the
API compiles on its own and that this example loads.

A `@Subscribe` method takes exactly one event and may declare a supertype of the ones it wants (`Event`
itself catches everything); a parameter that is not an `Event` is rejected at registration. Settings go in
`plugins/<id>/`, which `dataDirectory()` returns; `SimplePluginConfiguration.load(path, defaults)` reads a
`key=value` file there, creating it from the defaults and appending any key it is missing.

Lifecycle: discover → validate → classload → dependency order → onLoad/onEnable → `PluginEnableEvent`.
Disable fires `PluginDisableEvent`, calls `onDisable`, then unregisters the plugin's listeners, commands,
scheduler tasks and permission provider and closes its classloader; disabling a plugin disables its
dependents first, and shutdown disables everything in reverse enable order. From then on the plugin may
not register a listener, command, task or permission provider again (`IllegalStateException`), so code of
it still running at the disable cannot bring any of them back. Data lives in `plugins/<id>/`.

A jar that fails any of those steps is logged and skipped — a bad descriptor, a duplicate id, a main class
that will not initialize, or an `onEnable` that throws never stops the proxy from starting or the other
plugins from loading, and the rejected jar's classloader is closed so the file is not left locked. A
plugin whose `depend` cannot be met is named in the log with the reason: a dependency that is not
installed, a dependency cycle (`ping -> pong -> ping`), or a dependency that failed for one of those.

Events include proxy start/pre-shutdown/shutdown (`ProxyPreShutdownEvent`: new players are refused and
everyone online is still connected; the shutdown waits for its listeners), reload (after `/conduit reload`
applied), a plugin registering or unregistering a server, the client's settings and brand, server-list ping (`ServerListPingEvent`: MOTD, counts, sample,
version and icon are all settable, the counts can be hidden; cancelling leaves the client with no answer),
pre-login (`PlayerPreLoginEvent`: the claimed name and UUID, before authentication; deny it before any
encryption, or force online or offline mode for that connection, and `sendLoginPluginMessage` asks a 1.13+
client something while it logs in, the login waiting for the answer), a backend's login query
(`BackendLoginPluginMessageEvent`: reply for the player, or leave it to go to the client as before), game profile (`GameProfileRequestEvent`:
replace the profile -- UUID, name, skin -- that backends and the client's tab list see; `Player.gameProfile()`
reads it back), transfer (`PlayerTransferEvent`: cancel or redirect a 1.20.5+ Transfer, a plugin's or a
backend's),
player setup (`PlayerSetupEvent`: after authentication and before anything is decided about the player, so
a permission plugin loads them here), login (deniable; maintenance refuses before it), auth,
initial-server choice, server connect (cancellable and redirectable, for the first server too),
connected/switch/switch-failed (switch-failed also for each first-server candidate that fails, with no
source), kicked-from-server (`PlayerKickedFromServerEvent`: the backend's reason and a result of
`Disconnect`, `Redirect` or `Notify`, for a kick while playing and for a login refused during a switch,
a first connection or a fallback, including a 1.20.2+ server's refusal in its configuration phase, which
always ends the session), configuration (`PlayerConfigurationEvent`: a 1.20.2+ client's Configuration
phase at its first join and on each switch, where Conduit relays it without Via; a listener can hold the
phase open with `holdFinish`, and packs offered meanwhile go to the client as Configuration packets),
post-login, disconnect (exactly once for every player set up, whether they
played, were let in but taken by no server, were refused, or left during the login; `loginStatus()` says
which), chat (cancellable; clients before 1.19 only), tab completion (`PlayerTabCompleteEvent`: change the
backend's answer to a Tab press; clients before 1.13 only),
command execute (cancellable) and its outcome (`PostCommandEvent`: executed, threw, or forwarded to the
backend), handshakes (`ConnectionHandshakeEvent`, for pings and logins alike, built only when listened
for), plugin channels a client registers and unregisters (`PlayerChannelRegisterEvent`,
`PlayerChannelUnregisterEvent`), plugin enable/disable, and plugin messages (cancellable, both
directions). Every event's Javadoc names the thread
it fires on; events fire synchronously, and player events fire on that player's connection threads, so a
listener must not block. `@Subscribe(order = ...)` orders listeners from `FIRST` to `LAST`. A listener
that throws is logged and the others still run.

Scheduler tasks run on their own plugin's `conduit-plugin-<id>-N` threads, never on player socket
threads or another plugin's, so a task that blocks holds up only its own plugin; a repeating task never
overlaps itself. A task that throws is logged and, if repeating, runs again next time; a task that keeps
failing is logged in full once, then only counted, at its 2nd, 4th, 8th... failure in a row. A disabled
plugin's threads end once any task still running returns, and proxy shutdown interrupts any that has not.

`proxy().serverListDefaults()` is the configured `[status]` answer, and `RegisteredServer.ping()` asks a
backend for its status now instead of reading the cached `status()`: version, counts and player sample,
description and favicon. It never blocks the caller, and a backend that does not answer gives an offline
status rather than a failed future.

A `Player` can be shown titles, the action bar, proxy-owned `BossBar`s, a tab-list header and footer
and tab-list entries of the proxy's own, and played (and stopped) `Sound`s by name, each written in
that client's protocol. Boss bars, the header and entries are sent again after a server switch; what a
client's release cannot show or play is documented on each method (see also "Display by client
release" and "Sounds by client release" in `docs/VELOCITY_COMPATIBILITY.md`).

A command's `requires((source, arguments) -> ...)` decides whether the proxy has that command at all
for a source: when it says no, a player's command goes on to their backend and `execute` returns false,
where a missing `permission` instead tells the player they may not use it. A `CommandExecuteEvent`
listener may `setCommand` (what the proxy then looks up and runs, and what a pre-1.19 client's backend
gets) or `forwardToServer()` (the backend gets it even when the proxy has a command by that name).

`Player.ping()` is the round trip of the last keep-alive the client answered, `-1` until it has
answered one; `Player.clientBrand()` is what the client last sent on the brand channel.
`proxy().shutdown(Text reason)` stops the proxy with every player shown that reason.

A `Player` can be offered resource packs of the proxy's own (`sendResourcePack`, and from 1.20.3
`removeResourcePack` and `clearResourcePacks`), in the client's protocol: Resource Pack Send through
1.20.2, Push and Pop by UUID from 1.20.3. The client's answers arrive as
`PlayerResourcePackStatusEvent`, for the proxy's packs and for the ones its server offers; answers
about the proxy's packs never reach the server. A server's offer goes past `ServerResourcePackOfferEvent` first (cancel it and the server is told the client declined; `setPack` offers the client another, whose answers reach the server as about its own), and its removal past `ServerResourcePackRemoveEvent`.
`resourcePacks()` lists what the client was offered and still has or is deciding on; a loaded pack
offered again unchanged counts as loaded while the client answers again. Once it lists 64 packs, a
server's further offers still reach the client and the client's answers the server, but those packs are
not listed and their answers raise no `PlayerResourcePackStatusEvent`.

`Player.spoofChatInput(input)` sends the backend a chat line or command as if the player had typed it
(for a 1.19+ client, which signs what it says, only a command, and only from 1.20.5).
`updateCustomChatCompletions` changes the words a 1.19.1+ client offers on Tab in chat,
`setServerLinks` the `ServerLink`s in a 1.21+ client's pause menu, and `storeCookie`/`requestCookie`
keep up to 5 KiB on a 1.20.5+ client under a key; the answer to a request arrives as
`PlayerCookieReceiveEvent` and never reaches the backend, whose own cookies pass through untouched.

Other plugin formats plug in through `PluginManager.registerLoader(PluginLoader)`. The Velocity layer
uses it, from the one bootstrap Conduit calls (`VelocityBoot.install(ConduitProxy)`), and its plugins
then go through the same lifecycle as native ones; a loader's `close()` is called once at shutdown,
after every plugin is disabled, to let go of what the loader itself holds.

## Velocity compatibility

**PARTIAL — verified with in-repo plugins compiled against `com.velocitypowered:velocity-api`
(`VelocityCompatTests`), and with real plugins run unmodified: LuckPerms 5.5.71, MiniMOTD 2.2.5,
Maintenance 5.1.0, Server Redirect 1.4.3, mclo.gs 3.3.3, velocity-hub 1.10-SNAPSHOT, ForcePack
1.3.75-SNAPSHOT and TitleAnnouncer 3.1.0.** Each one's result and its remaining gaps are in
`docs/VELOCITY_COMPATIBILITY.md`.

This is not full Velocity compatibility. Titles, the action bar, boss bars, the player-list header and
footer, a player's tab-list entries and sounds work through the adapter (only the proxy's own entries: the
backend's are not tracked), for a player or for everyone on the proxy or a server, and so do
resource packs a plugin offers. Plugins built around the
backend's tab list or scoreboard teams, voice chat, Bedrock
players, packet injection or Velocity's own network pipeline (ViaVersion and its relatives) are not
expected to work.

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

`./scripts/test.ps1` compiles core, then `src/compat-velocity` against `lib/*.jar` (includes `slf4j-jdk14`, so what plugins log through SLF4J reaches Conduit's `java.util.logging` loggers), then runs Phase9 and VelocityCompatTests, which build real Velocity-API plugins and load them.

See `docs/VELOCITY_COMPATIBILITY.md` for the support matrix. Unsupported APIs throw; they are never faked.

## Protocol translation

**PARTIAL.** `765↔766` semantic translation exists for known control packets. `765→776` remains `UNSUPPORTED`. `Protocol765To776Translator` still throws. Identity forwarding is same-codec only. Join Game / full play remapping is not claimed.

## Out of scope here

Full Velocity API packages, BungeeGuard, and a finished 765↔776 translator.

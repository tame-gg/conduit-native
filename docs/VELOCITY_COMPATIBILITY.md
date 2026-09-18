# Velocity plugin compatibility

Conduit is not Velocity. It can run many plugins built against the published
`com.velocitypowered:velocity-api` (3.4.0) through a compatibility adapter, but not every Velocity
plugin will work. Each call listed below is marked **supported**, **partial**, or **unsupported**,
and names the test or real plugin that verifies it. If a feature is not listed, it does not work.

Where these marks come from: **VCT** is `VelocityCompatTests`, which compiles purpose-built plugins
against the real velocity-api (its annotation processor writes their `velocity-plugin.json`), loads
them from jars into a real `MinecraftProxy`, and drives them with a scripted 1.8 client and two
scripted backends. **LLT** is `LoginLifecycleTests`, which does the same for the login lifecycle with
a plugin shaped like LuckPerms. **VDT** is `VelocityDisplayTests`: a plugin compiled the same way
drives titles, the action bar, boss bars, the player-list header and tab-list entries for scripted
1.12.2 clients on two scripted 1.12.2 backends, and every packet the clients get is checked. **DAT** is
`DisplayApiTests`, the native API underneath: every display packet byte for byte for each protocol
family, and scripted 1.8, 1.20.4 and Via-translated 1.8-on-1.12.2 sessions, each through a server
switch. **P9** is `Phase9Tests`. **BPT** is `BackendPingTests`, which pings scripted backends through
the native API and through a compiled Velocity plugin. **VLT** is `VelocityLifecycleTests`: what a
disabled Velocity plugin leaves behind, seen from the plugins that stay. **VET** is
`VelocityEventsTests`, which checks the proxy, server, settings and brand events a compiled plugin is
sent by a real proxy and a scripted 1.8 client. **SKT** is `ServerKickTests`: scripted 1.20.4 clients
and backends turned away at login, in the configuration phase, while playing, on a switch and on a
fallback, natively and through a compiled Velocity plugin. **VTT** is `VelocityTextTests`: a compiled
plugin sends RGB, decorated, clickable and translatable text as chat and as its server-list description
to scripted 1.12.2 and 1.20.4 clients, which get it in their own form, and the adapter's mapping is
checked both ways. **TFT** is `TextFidelityTests`, the text codec underneath, for every client release.
**RPT** is `ResourcePackTests`: resource-pack bytes per era, and scripted 1.8 and 1.20.4 clients
answering the proxy's packs and a server's across a switch, with a compiled Velocity plugin.
**VST** is `VelocitySoundTests`: a compiled plugin plays and stops Adventure sounds for scripted
1.20.4 and 1.12.2 clients. **SAT** is `SoundApiTests`, the native sound API and its packets for
every client release, with scripted 1.8 and 1.20.4 sessions.
**LFT** is `LoginFlowTests`: pre-login decisions, game profiles and transfers against scripted 1.8 and
1.20.4/1.20.5 clients and backends, a local stand-in for the session server, and compiled plugins.
**TCT** is `TabCompleteEventTests`: a scripted 1.8 client presses Tab, a scripted 1.8 backend answers,
and a native listener and a compiled Velocity plugin change the answer. **PXT** is `PlayerExtrasTests`:
spoofed chat, custom chat completions, server links and cookies, their packet ids for each release,
and scripted 1.8, 1.20.4, 1.20.5 and 1.21 clients and servers, natively and through a compiled plugin.
**CCH** is `ChannelCommandHandshakeEventTests`: a scripted 1.8 client registers channels (among them a
hostile list of most of a megabyte), runs proxy and backend commands and pings, against a scripted 1.8
backend that must get every packet byte for byte, natively and through a compiled plugin.
**VAT** is `VelocityAudienceTests`: a compiled plugin announces through the proxy and its servers to
scripted 1.8 clients, and hears `ServerPostConnectEvent`, a player's pointers and the config
libraries. **CET** is `ConfigurationEventsTests`: a scripted 1.20.4 client joins and switches between
two scripted 1.20.4 servers while one compiled plugin records the five configuration events and
another sends a pack from `PlayerConfigurationEvent`; a 1.8 client raises none of them.
The **real plugins** are listed at the end.

The adapter is a clean-room implementation. It was written from velocity-api's public interfaces,
its Javadoc, the public documentation and observable behaviour. No code from Velocity's proxy,
BungeeCord, Waterfall or Paper is used or ported.

## Architecture and isolation boundary

```
Conduit core  ->  native API (gg.tame.conduit.api)  ->  Velocity adapter (src/compat-velocity)  ->  Velocity plugins
```

- **The adapter uses only the native API.** Everything under `gg.tame.conduit.compat.velocity`
  imports only `gg.tame.conduit.api.*`: the proxy, players, servers, commands, events, scheduler,
  permissions and text. It plugs in through a single extension point,
  `PluginManager.registerLoader(PluginLoader)`. Core finds `VelocityBoot` by class name and calls
  `install(ConduitProxy)`, so core never imports a Velocity type.
- **Conduit's lifecycle manages Velocity plugins.** Each Velocity plugin is loaded as a native
  plugin: Conduit's plugin manager orders, enables, lists (`[velocity]`) and disables it, and
  releases every command and task the plugin registered.
- **Plugins cannot link to Conduit.** Each plugin jar has its own class loader, and that loader
  hides every `gg.tame.conduit.*` class and resource. A plugin can link against the velocity-api
  and the libraries shipped with it, and against the jars of other Velocity plugins (so a plugin can
  use its dependency's API). It cannot link against Conduit, not even the native API (VCT:
  `/vtest internals`).
- **Plugins only ever receive Velocity API types.** Every object handed to a plugin implements
  Velocity API interfaces only, and none of its public methods takes or returns a Conduit type
  (VCT checks this with reflection).
- **This is not a sandbox.** Plugins run in the proxy's JVM with full rights, and deep reflection
  can reach anything, exactly as with native Conduit plugins. The boundary above controls what
  plugins link against and which API they are given. It is not a security boundary.

**Threads.** Velocity plugin code never runs on a player's connection thread. Event handlers,
command bodies, tab completions, scheduled task bodies and a plugin's permission function (when
Conduit itself checks a permission) all run on the adapter's own pool of daemon platform threads.
When a connection thread needs a plugin's answer (deny this login? redirect this connect? may this
player run /server?), it waits at most 10 seconds and then continues with the event as the plugin
left it, or with the permission denied, logging a warning. The pool ends when the proxy stops, once every plugin
has been disabled (VLT).

**Class path.** The proxy class path supplies what velocity-api's POM declares and plugins expect
the proxy to provide:
- Adventure 4.26.1, including MiniMessage and `adventure-text-logger-slf4j`
- Mojang Brigadier, Guava, Gson and snakeyaml 1.33
- Guice 6.0.0 with `jakarta.inject-api` 2.0.1: its `@Inject` annotation, and the `Injector` a plugin
  can ask for (see below)
- slf4j bound to `java.util.logging`
- Configurate 4.1.2 (HOCON, YAML and Gson), with geantyref and Typesafe Config
- night-config 3.8.4 (core and TOML), which velocity-api's POM does not name but Velocity's proxy
  carries, and which plugins such as ForcePack compile against without bundling it

The jars are fetched by `scripts/fetch-velocity-compat.ps1` (VAT loads Configurate and night-config
from a plugin). toml4j and caffeine are **not** on the class path.

## Loading plugins

| Behaviour | Status | Verified by |
|---|---|---|
| `velocity-plugin.json` at the jar root (written by velocity-api's annotation processor from `@Plugin`): id, name, version, description, url, authors, dependencies, main | Supported | VCT, P9, real plugins |
| Rejects without affecting the proxy: malformed JSON, invalid id, missing main class, throwing constructor, uninjectable parameter, missing required dependency. The jar is unlocked straight away. | Supported | VCT `brokenPluginsAreRejected` |
| Required dependencies are enabled first, and a missing one rejects the plugin. Optional ones are enabled first when present. | Supported | VCT (vlib→vtest, needy, optdep) |
| A plugin can use the classes of other Velocity plugins | Supported | VCT (`vtest` calls `vlib.VLib`) |
| Constructed when enabled, at startup in dependency order. The main instance is registered as a listener automatically. | Supported | VCT |
| `ProxyInitializeEvent` when the proxy starts | Supported | VCT, P9, real plugins |
| `ProxyShutdownEvent` exactly once: when the proxy stops, or when Conduit disables that plugin alone. Its listeners, commands, tasks, executor and class loader are then released. | Supported | VCT |
| `velocity-plugin.toml`, and jars without `velocity-plugin.json` | Unsupported | a jar without the file is offered to the native loader |

## Constructor injection

Conduit does its own injection of the main class. It is built from one constructor: the one marked
`@Inject` (`javax.inject`, `jakarta.inject` or `com.google.inject`), otherwise its only constructor,
otherwise its no-argument constructor. After that, `@Inject` fields (including final ones) and
`@Inject` methods are filled in, superclass first. Static members are left alone.

A plugin that asks for Guice's `Injector` gets a real one, built once per plugin, with the bindings
in the table below (`@DataDirectory Path` included). The plugin can `getInstance` from it
and make child injectors with its own modules, as MiniMOTD does. The one difference is
`java.util.logging.Logger`: every Guice injector binds that itself, so through Guice it is Guice's
logger named after the class it is injected into, not the plugin's Conduit logger.

| Injectable type | Status | Verified by |
|---|---|---|
| `ProxyServer`, `org.slf4j.Logger`, `@DataDirectory Path`, `PluginContainer`, `PluginDescription` | Supported | VCT |
| `ComponentLogger` (Adventure), `java.util.logging.Logger`, `EventManager`, `CommandManager`, `PluginManager`, `Scheduler` | Supported | VCT (ComponentLogger, EventManager, PluginManager, CommandManager, Scheduler directly and through Guice) |
| `com.google.inject.Injector`: `getInstance`, `createChildInjector` with the plugin's own modules and `@Named` bindings | Supported | VCT (`guice:`), MiniMOTD |
| The plugin's own concrete classes, built the same way, as bStats' `Metrics.Factory` is | Supported | VCT, velocity-hub |
| Loggers write to the plugin's Conduit logger `plugin.<id>`. `@DataDirectory` is `<plugins dir>/<id>`. | Supported | VCT |
| Any other type in the main class's own injection (Guice `Provider`s, `@Named` qualifiers, interfaces the plugin binds in its own modules) | Unsupported: the plugin is rejected with a message that names the type. Through the `Injector`, Guice's own rules apply. | VCT `badinject` |

## ProxyServer

| API | Status | Verified by |
|---|---|---|
| `getPlayer(String/UUID)`, `getAllPlayers`, `getPlayerCount`, `matchPlayer` | Supported | VCT |
| `getServer`, `getAllServers`, `matchServer`, `registerServer` (a taken name throws), `unregisterServer` | Supported | VCT |
| `sendMessage(Component)`: broadcasts to every player and the console | Supported | VCT |
| As an Adventure `ForwardingAudience` of every player: titles, the action bar, the header and footer, boss bars, resource packs and the rest reach each player, who shows or refuses them as they would alone (see Players and servers). `audiences()` is the players; the console is not among them, though chat sent to the proxy still reaches it | Supported | VAT, TitleAnnouncer |
| `getCommandManager`, `getEventManager`, `getScheduler`, `getChannelRegistrar`, `getPluginManager`, `getConsoleCommandSource` | Supported | VCT |
| `getVersion` (name `Conduit`), `getBoundAddress` | Supported | VCT |
| `shutdown()` stops the real proxy. `isShuttingDown()` | Supported | VCT (`/vlibstop`) |
| `shutdown(Component)`: every player is kicked with that reason instead of Conduit's configured shutdown message | Supported | `ShutdownTests` |
| `getConfiguration()`: `getServers`, `isOnlineMode`, `getAttemptConnectionOrder` (routing's initial servers) | Partial | VCT |
| `getConfiguration()`: `getMotd`, `getShowMaxPlayers`, `getFavicon`, from Conduit's `[status]` settings as configured (what a client is sent can differ, after maintenance or a ping listener) | Supported | VCT (`serverlist:`), Maintenance |
| `getConfiguration()`: `getForcedHosts` is empty, because Conduit has no forced hosts | Supported | Maintenance |
| `getConfiguration()`: every other setting (query, compression, rate limits, timeouts, announce-forge, proxy-connection checks) | Unsupported (throws) | |
| `createResourcePackBuilder(url)`: a pack for the proxy to offer. `setId` (without one, an id derived from the URL, so offering the same URL again replaces the pack), `setHash` (a 20-byte SHA-1, else it throws), `setShouldForce`, `setPrompt`; the info's origin is `PLUGIN_ON_PROXY` | Supported | RPT |
| `createRawRegisteredServer`, `closeListeners` | Unsupported (throws) | |

## Players and servers

| API | Status | Verified by |
|---|---|---|
| `getUsername`, `getUniqueId`, `identity`, `isOnlineMode`, `isActive`, `getProtocolVersion`, `getProtocolState`, `getVirtualHost` | Supported | VCT |
| Adventure pointers (`get(Identity.UUID)` and the like): the player's UUID, name, effective locale and permission checker | Supported | VAT, TitleAnnouncer |
| `hasPermission` / `getPermissionValue`: the player's function from `PermissionsSetupEvent` (see Permissions), or, when no plugin set one, Conduit's permission provider, which answers TRUE or FALSE and never UNDEFINED | Supported | VCT, LuckPerms, Maintenance |
| `getRemoteAddress`: the client's address and the port it connected from; the port is 0 when `forwarding.player-address` makes the address a configured one | Supported | VCT, `ClientSettingsTests` |
| `getRawVirtualHost`: host name only, with FML markers removed | Partial | |
| `getGameProfile`, `getGameProfileProperties`: the id, name and properties (`textures` with its signature) that backends are told: the session server's for an online-mode player, none for an offline one, or what a `GameProfileRequestEvent` set | Supported | LFT |
| `setGameProfileProperties`: Conduit does not change a player's profile once they have logged in | Unsupported (throws) | |
| `transferToHost`: a 1.20.5+ player is sent a Transfer packet (in Play, or in Configuration) after `PreTransferEvent`, and connects to that address by itself. A client before 1.20.5 throws `IllegalArgumentException`, as Velocity documents. `getHandshakeIntent`: `TRANSFER` for a player who arrived by transfer, `LOGIN` otherwise | Supported | LFT |
| `getPing`: the round trip of the last keep-alive the client answered, measured by Conduit between writing it and reading the answer, as the game measures it; `-1` ("unknown") until the client has answered one, up to a keep-alive interval after joining | Supported | `PlayerLatencyTests` |
| `getPlayerSettings`: what the client last sent, read in its own release's layout: language, view distance, chat mode and colours, skin parts, main hand, text filtering, server listing and particles (`hasSentPlayerSettings()` then true). Fields a release does not send, and every field before the client has sent any, are the vanilla client's defaults. `getEffectiveLocale` is what a plugin set with `setEffectiveLocale`, or else the client's locale, and null before the client has sent it. | Supported | VCT, `ClientSettingsTests` |
| `getClientBrand`: what the client last sent on `minecraft:brand` (`MC|Brand` before 1.13); null until it has | Supported | VET |
| `sendMessage(Component)`, `sendRichMessage` (MiniMessage) | Partial: see [Text formatting](#text-formatting) for what is kept and what each client release is sent | VCT, VTT, TFT, Maintenance |
| `disconnect(Component)`: a real kick screen, with the same formatting kept | Supported | VCT |
| `createConnectionRequest(...).connect()`: `SUCCESS`, `ALREADY_CONNECTED`, `CONNECTION_IN_PROGRESS`, `CONNECTION_CANCELLED`, and `SERVER_DISCONNECTED` for any failure, with its reason | Supported | VCT, velocity-hub |
| `connectWithIndication`, `fireAndForget`: on failure the player is told "Unable to connect to &lt;server&gt;" | Supported | VCT |
| `getCurrentServer`, and on `ServerConnection`: `getServer`, `getServerInfo`, `getPlayer`, `getPreviousServer` (from the last switch) | Supported | VCT |
| `sendPluginMessage` to the client (`Player`), or to the backend (`ServerConnection`, and `RegisteredServer` through a player connected to it); byte-array and encoder forms | Supported | VCT |
| `RegisteredServer`: `getServerInfo`, `getPlayersConnected`, `sendMessage` | Supported | VCT |
| `RegisteredServer` as an Adventure `ForwardingAudience` of the players connected to it, as the proxy is of every player | Supported | VAT, TitleAnnouncer |
| `RegisteredServer.ping()`: Conduit asks the backend for its status now. The `ServerPing` has the backend's version name and protocol, online and maximum counts, player sample, description and favicon; `getModinfo()` is empty, as Conduit does not read a backend's mod list. The description keeps what Conduit Text carries (see [Text formatting](#text-formatting)): RGB colours, decorations, translations with their arguments and the common events are kept; fonts, keybind, score, selector and NBT components are not. The handshake announces protocol `-1` (no client version, so the backend answers with its own) and the host of the server's configured address; `[health] timeout-ms` bounds the whole ping. A backend that cannot be reached, does not answer in time, or sends something that is not a status answer (malformed or oversized JSON, a connection closed mid-answer) fails the future with an `IOException`. Pings run on a bounded pool of socket threads, 32 at once; up to 1024 more wait their turn inside their own timeout, and beyond that a ping fails at once. Callbacks run on the adapter's threads. | Partial | VCT (`backend-ping`, `dead-ping`), BPT |
| `RegisteredServer.ping(PingOptions)`: the answer is as for `ping()`, and every option is honoured: the protocol version is announced in the handshake (`UNKNOWN` announces `-1`), the virtual host is the host the handshake names (none: the configured address's host), and a timeout replaces `[health] timeout-ms` (zero: that default) | Supported | BPT |
| On a player: `showTitle`, `sendTitlePart` (`TITLE`, `SUBTITLE`, `TIMES`), `clearTitle`, `resetTitle`, `sendActionBar` | Supported, as far as the client's release has them (see [Display by client release](#display-by-client-release)). Text keeps what chat keeps (see `sendMessage`). Title times become whole ticks, rounded down. Anything sent while the client has no world to show it in (before its first Join Game, or while a 1.20.2+ client is reconfigured by a switch) is held, the last 16, and shown once it has one. | VDT, DAT |
| On a player: `showBossBar`, `hideBossBar` (and Adventure's `BossBar.addViewer`/`removeViewer`, which call them). Name, progress, colour, overlay and all three flags; every change a plugin makes to the bar reaches every player viewing it. The bar is shown again after each server switch. The adapter holds one listener on the plugin's bar while anyone views it, and removes it when the last viewer hides the bar or disconnects. | Supported for 1.9+ clients. A 1.7 or 1.8 client has no boss bar: it is sent nothing, though it still counts as a viewer. | VDT, DAT |
| On a player: `sendPlayerListHeaderAndFooter`, `sendPlayerListHeader`, `sendPlayerListFooter`, `clearPlayerListHeaderAndFooter`, `getPlayerListHeader`, `getPlayerListFooter`, and `TabList.setHeaderAndFooter` / `clearHeaderAndFooter`. The pair is sent again after each server switch. A backend that sends its own replaces the proxy's on the client until the proxy's next send or switch: the last writer wins. The getters return what plugins set through the proxy, never a backend's. | Supported for 1.8+ clients; a 1.7 client has no header and is sent nothing | VDT, DAT |
| `Player.getTabList()` entries: `buildEntry` / `TabListEntry.builder()`, `addEntry`, `addEntries`, `getEntry`, `getEntries`, `containsEntry`, `removeEntry`, `clearAll`, and an entry's `setDisplayName`, `setLatency`, `setGameMode`, `setListed`, `setListOrder`, `setShowHat` | Partial: the proxy's own entries only. The backend's entries are not tracked, so `getEntries` and `containsEntry` see only entries plugins added and `clearAll` removes only those; the backend's list is never touched. Entries stay across server switches and are sent again. An entry with a chat session throws. Which clients are sent them, and which fields, is under [Display by client release](#display-by-client-release). | VDT (1.12.2), DAT |
| On a player: `sendResourcePackOffer`, `sendResourcePack(url)` and `(url, hash)`, Adventure's `sendResourcePacks` (every pack in the request with its prompt and required flag; `replace` clears first; its callback is told each answer), `removeResourcePacks`, `clearResourcePacks`, `getAppliedResourcePack(s)`, `getPendingResourcePack(s)`. Written in the client's own protocol: one Resource Pack Send through 1.20.2 (URL and hash, and from 1.17 the required flag and prompt), Push and Pop naming the pack by UUID from 1.20.3. An offer made while the client is logging in or switching servers waits until it has a world again, except one made while the configuration events hold the client in its Configuration phase (see Events), which goes at once as a Configuration packet. The client's answers about the proxy's packs never reach the backend; its answers about the backend's packs still do. The proxy's packs stay across server switches; a backend's are neither removed nor offered again on a switch. `getAppliedResourcePacks` and `getPendingResourcePacks` list what was offered through the proxy, by a plugin (`PLUGIN_ON_PROXY`) or by the backend (`DOWNSTREAM_SERVER`), as the client has answered: a declined, failed or dropped pack leaves the lists, and a loaded pack offered again unchanged stays applied while the client answers again. The prompt keeps what Conduit Text carries. | Partial: a client before 1.20.3 holds one pack and has no way to drop one, so `removeResourcePacks` and `clearResourcePacks` do nothing for it, and its answers name no pack, so they are matched to the offers in the order it got them (1.8's by hash first). `getShouldForce` is honoured by 1.17+ clients, which disconnect themselves when the player declines; Conduit does not kick an older client that declines. A 1.7 client is sent nothing. | RPT, VAT, CET, ForcePack |
| On a player: `playSound(Sound, x, y, z)`, `playSound(Sound)` and `playSound(Sound, Sound.Emitter.self())`, `stopSound(SoundStop)` (and Adventure's `stopSound(Sound)`), with the sound's name, source, volume, pitch and seed | Partial: sent by name, so any sound the client knows plays. A sound at a position reaches every client from 1.7. A sound at the player (`playSound(Sound)`, or `Emitter.self()`) follows the player and reaches only 1.19.3+ clients; older ones are sent nothing, since Conduit does not know where the player stands. Stopping reaches 1.9.3+ clients. Anything sent before the client has a world, or while a switch reconfigures it, is dropped. Any other emitter throws. See [Sounds by client release](#sounds-by-client-release). | VST, SAT |
| `spoofChatInput`: the input goes to the player's backend as if they had typed it, a line starting with `/` as a command and anything else as chat. The proxy's own commands and `CommandExecuteEvent` and `PlayerChatEvent` do not see it. Input longer than the client's own chat box takes (256 characters, or 100 before 1.11) throws `IllegalArgumentException`. Nothing is sent before the player is in Play on a backend. | Partial: a 1.19+ client signs what it says, and the proxy cannot sign for it. For such a client only commands can be sent, and only from 1.20.5, as the unsigned command such a client sends itself; a backend refuses one whose arguments it expects signed (`/msg`) and may disconnect the player over it. Chat from a 1.19+ client, and commands from a 1.19 to 1.20.4 one, throw `UnsupportedOperationException`: those packets carry the client's acknowledgement of the chat it has seen, which a backend checks. | PXT |
| `addCustomChatCompletions`, `removeCustomChatCompletions`, `setCustomChatCompletions`: sent at once to a 1.19.1+ client in Play; an older client, or one not in Play, is sent nothing. The proxy does not keep them. | Supported | PXT |
| `setServerLinks`: sent at once to a 1.21+ client, in Play or in Configuration. Built-in types and custom labels, the label keeping what Conduit Text carries. A client before 1.21 throws `IllegalArgumentException`. The backend can replace them with links of its own. Nothing is sent during the login, before `PostLoginEvent`. | Supported | PXT |
| `storeCookie`, `requestCookie`: sent at once to a 1.20.5+ client, in Play or in Configuration. A client before 1.20.5 throws `IllegalArgumentException`, as does a cookie over 5 KiB (5120 bytes). The answer to a request arrives as `CookieReceiveEvent` and never reaches the backend; a backend's own cookies, requests and answers pass through untouched. Nothing is sent during the login, before `PostLoginEvent`. | Partial: an answer is matched to a plugin's request by key alone, so when a plugin and the backend ask for the same key at once the two answers may reach each other (they carry the same cookie). No Login-phase cookies. | PXT |
| Books, dialogs, signed-message chat, `getModInfo`, `getIdentifiedKey`; and titles, the action bar, boss bars, the header and resource packs on the console as an audience | Unsupported: every one throws `UnsupportedOperationException` naming the API, including Adventure methods that are silent no-ops by default | VCT (`openBook`) |

### Display by client release

What the proxy shows a player is written in the client's own protocol, beside whatever the backend
shows it, straight to the client even when Via translates the session. Every packet id comes from
the published PrismarineJS minecraft-data tables for that release (26.2, which that data does not
have yet, from the minecraft.wiki packet list for protocol 776). DAT checks the bytes for releases
across every family below (1.8, 1.12.2, 1.13, 1.14, 1.15, 1.16, 1.16.5, 1.17, 1.19, 1.19.2, 1.19.3,
1.20.1, 1.20.2, 1.20.4, 1.20.5, 1.21, 1.21.2, 1.21.4, 1.21.5, 1.21.9, 26.1 and 26.2, as each feature
applies). No real Minecraft client has been shown any of it.

- **1.7 (protocol 5)**: nothing. It has no titles, action bar, boss bar or header, and its player
  list names entries by string. Title and action-bar calls send nothing (the action bar is not turned
  into chat, which would flood it for plugins that refresh it every tick); boss bars and entries are
  only remembered.
- **Titles**: 1.8 through 1.16.5 get the one Title packet, in 1.8's action numbering for 1.8 and
  1.11's (which added the action bar and moved times, hide and reset up by one) from 1.12.2; 1.17 and
  later get a packet per action. Text is JSON through 1.20.2 and network NBT from 1.20.3.
- **Action bar**: on 1.8 it is a game-info (position 2) chat line, which that client shows as plain
  text, so colour, bold and italic are written into the text as section codes and clicks and hovers
  are dropped. 1.12.2 through 1.16.5 get Title action 2; 1.17 and later Set Action Bar Text.
- **Boss bars**: 1.9 and later (so 1.12.2 onwards among Conduit's tables). A bar's id is a random
  version-8 UUID, which cannot collide with the version-4 UUIDs vanilla and common servers give theirs.
- **Header and footer**: 1.8 and later.
- **Tab-list entries**: 1.8 through 1.19.2 get one Player Info packet per action (1.19 to 1.19.2 with
  no profile key), where every entry is listed, so `setListed` has no effect. 1.19.3, 1.19.4 and
  1.20.2 onwards get Player Info Update and Remove; list order is sent from 1.21.2 and the hat from
  1.21.4. **1.20.1 is not sent entries**: Conduit's 1.20.1 table has no Player Info ids, and adding
  them would also change how Conduit treats 1.20.1's own player entry, which is out of this change's
  scope. There, as on 1.7, entries are only remembered.
- **After a server switch** a client is sent the proxy's boss bars, header and entries again after
  the new backend's Join Game, whether or not it dropped them, and for a client with no
  Configuration phase after every Respawn as well.

### Sounds by client release

Sounds are sent by name, never by a release's sound registry id, so no sound tables are needed and a
sound missing from a client's own list plays nothing. Clients before 1.9 name their sounds
differently (`random.orb`, not `entity.experience_orb.pickup`), and a plugin has to use their names
to be heard there. Packet ids come from the same public data as the display packets, and SAT checks
every form byte for byte.

| Client | At a position | At the player | Stop |
|---|---|---|---|
| 1.7, 1.8 | Named Sound Effect, no category, pitch as a byte (63 is normal, capped at 255) | nothing | nothing: no such packet or channel |
| 1.12.2 | Named Sound Effect with its category, pitch as a float | nothing | the `MC\|StopSound` plugin channel (category name, then sound name; empty means any), as the protocol documentation of the time describes it; not checked against a real client |
| 1.13 to 1.18.2 | as 1.12.2 | nothing | Stop Sound |
| 1.19 to 1.19.2 | as 1.12.2, with the seed | nothing: an entity's sound is named by registry id | Stop Sound |
| 1.19.3 to 26.2 | Sound Effect, the name inline, with the seed | Entity Sound Effect on the player's own entity id, from the Join Game the client was last sent | Stop Sound |

The `ui` source exists from 26.1; earlier clients are sent it as `master`. A missing seed is chosen at
random. A sound has no fixed range: the client works it out from the volume.

### Text formatting

Every Adventure component a plugin hands the adapter (chat, kick and deny reasons, titles, the action
bar, boss-bar names, the player-list header and tab-list display names, server-list descriptions)
becomes Conduit Text, and Text a plugin reads (kick reasons, ping descriptions, the header getters)
becomes Adventure again.

- **Kept, both ways:** literal and translatable components (arguments and fallback included), named
  and RGB colours, bold, italic, underlined, strikethrough and obfuscated each as on, explicitly off
  or unset, insertion, show-text hovers, and open-URL, run-command, suggest-command,
  copy-to-clipboard and change-page clicks. VTT checks that the mapping gives back the same component.
- **Flattened:** keybind, score, selector, NBT and object components become their plain rendering
  (a keybind shows its key name, such as `key.jump`), children included, keeping their own style.
- **Dropped:** fonts, shadow colours, show-item and show-entity hovers, and open-file, show-dialog,
  custom and callback clicks.

Each client is then sent what its release can show (TFT checks every release Conduit has a table for,
and VTT a real plugin's text on 1.12.2 and 1.20.4 clients and pings):

| Client | Sent |
|---|---|
| 1.7 | JSON; RGB as the nearest of the sixteen named colours; no insertion, copy-to-clipboard or fallback; hovers under `value` |
| 1.8 to 1.14 | as 1.7, with insertion |
| 1.15 | as 1.8, with copy-to-clipboard clicks |
| 1.16 to 1.19.3 | RGB colours; hovers under `contents` |
| 1.19.4 to 1.21.4 | translation fallbacks too; network NBT from 1.20.3 in Play and Configuration |
| 1.21.5 to 26.2 | `click_event` and `hover_event`, with a click's value under `url`, `command`, `page` or `value`. A click this client would refuse, and with it the whole message, is left off: an open-URL that is not http(s), a command with a section sign or control character, a page that is not a positive number |

The 1.8 action bar is a chat line the client shows as plain text, so there colours and decorations
become section codes (RGB as the nearest named colour), and clicks and hovers are dropped.

## Permissions

| API | Status | Verified by |
|---|---|---|
| `PermissionsSetupEvent` for every joining player, once, from Conduit's native `PlayerSetupEvent`: after authentication and Conduit's own security checks, and before anything is decided about the player, maintenance and `LoginEvent` included. The login waits for it (at most 10 seconds). Its default provider answers what Conduit's permission provider answers. Not fired for the console, which has every permission. | Supported | VCT (`permsetup`), LLT, LuckPerms |
| The function a plugin sets answers for that player everywhere: `Player.hasPermission` / `getPermissionValue` for every Velocity plugin, and Conduit's own checks (its commands, native plugins' command permissions). Conduit hears a boolean, so UNDEFINED is "no" there, as `hasPermission` reads it. | Supported | VCT (`/nsecret` refused for Erin), LuckPerms (`/server` refused, then allowed after `/lpv user … permission set conduit.server true`) |
| Conduit's maintenance mode lets a player in when their function grants `conduit.maintenance.bypass` or `conduit.admin` (TRUE; FALSE and UNDEFINED do not). A player the plugin set no function for is answered by Conduit's own provider, and Conduit's permissive default never lets anyone through maintenance. A function that throws or takes over 10 seconds refuses the bypass. The maintenance allowlist by name works whatever the plugin says. | Supported | LLT, LuckPerms |
| A player asked about before their `PermissionsSetupEvent` has finished, or after they left, has no permissions while a plugin's functions are in force | Supported | |
| When the plugin that set the functions is disabled, they stop answering and Conduit's permission provider is back for every player, the permissive default included. A function set for a player whose setup was still running when its plugin was disabled is not kept. | Supported | VCT (vtest disabled, Erin back on the default), LLT (`Midway`), LuckPerms (`disable luckperms`) |
| A function whose provider's code comes from no enabled Velocity plugin (its class loader, or a parent of it, is no plugin's) is not kept, with a warning, and the player gets Conduit's permissions: Conduit could never take it away again | Partial | LLT (`Midway`) |

## Commands

| API | Status | Verified by |
|---|---|---|
| `SimpleCommand`: execute, suggest, `hasPermission`, aliases, `alias()` of the invocation | Supported | VCT, Maintenance, velocity-hub |
| `RawCommand`: execute, suggest and `hasPermission`; the argument string arrives exactly as typed, runs of spaces kept (`hasPermission` sees them collapsed) | Supported | VCT, `CommandForwardingTests` |
| `BrigadierCommand`: parsed, permission-checked, executed and completed on the proxy with the Brigadier library, and syntax errors are shown to the player | Partial: clients are sent only the command's literal name, not its argument nodes, which is also true of Conduit's own commands; completion is not covered by a test | VCT (execute and syntax error) |
| `metaBuilder`, `register`, `unregister`, `getCommandMeta`, `getAliases` (Velocity-registered aliases only), `hasCommand` (any proxy command, including Conduit's own and native plugins') | Supported | VCT |
| Registering an alias the same plugin already holds replaces its command (mclo.gs registers each Brigadier subcommand under one meta); another plugin's alias throws | Supported | VCT (`/vre`, `reregister:true`), mclo.gs |
| An alias that starts with `/` is registered like any other: exactly one slash is taken off what a player or the console types, and the rest must match an alias exactly, so `//lpv` reaches `/lpv` and never `lpv`. It is in the command tree sent to 1.13+ clients, so they accept `//lpv`. | Supported | VCT (`/vt`), LLT (`//lpx`), LuckPerms (`//lpv info` from the console) |
| An alias that contains a space, or is slashes alone, is accepted but not registered, with an info line in the log: Conduit's command line splits on spaces, so nobody could type it | Partial | |
| `executeAsync` as a player or the console: fires `CommandExecuteEvent` first, and returns false when there is no proxy command of that name | Partial: an unknown command is not forwarded to the backend | VCT |
| `executeImmediatelyAsync`, `offerSuggestions` | Supported | VCT |
| `offerBrigadierSuggestions`; `CommandMeta` hints | Unsupported (throws); hints are ignored | |
| When a command's `hasPermission` (a Brigadier command's `requires`) is false for the source, the proxy acts as if it had no such command: a player's command goes on to their backend, as on Velocity, and `executeAsync` completes false. The command stays in the command tree clients are sent, which is not filtered per player. LuckPerms' `/lpv` alias is such a command for players, since it lets only the console run it. | Supported | `CommandForwardingTests` |

**Name conflicts.**
- A Velocity plugin that registers a name Conduit uses for a built-in command takes that name
  over, and Conduit logs a warning. The built-ins are `/hub`, `/server`, `/send`, `/glist` and the
  others, including each `/<server>` shortcut. When the plugin goes away, the built-in comes back.
- `/conduit` is reserved and cannot be registered.
- A name that another plugin already holds throws `IllegalArgumentException`, as Velocity's
  contract requires.
- Verified by velocity-hub, which takes over `/hub` and `/lobby`.

## Events

`@Subscribe` methods can take the event, or the event and a `Continuation`. They can return void or
`EventTask`. Methods declared in superclasses count too. Handlers run highest `priority` first.
`PostOrder` values other than `NORMAL` map to fixed priorities (FIRST, EARLY, LATE, LAST). A handler
that is still running async, or has not resumed its continuation, holds back the next one. A
handler that throws is logged, and the other handlers still run. The functional
`register(plugin, Class, priority, EventHandler)` form works, and so do `fire`/`fireAndForget` for a
plugin's own events. Verified by VCT: priority order, `EventTask.async`, `Continuation`,
`PostOrder.LAST`, and a functional handler.

| Velocity event | Status | Verified by |
|---|---|---|
| `ProxyInitializeEvent`, `ProxyShutdownEvent` | Fired | VCT |
| `PreLoginEvent`: once the client's Login Start is read, before online-mode authentication; the handshake, protocol and version gate are already checked. `getUsername` and `getUniqueId` are what the client claims (the UUID is null for a client whose Login Start carries none, and for a 1.19.1-1.20.1 client that sends exactly the UUID its name gives). `denied(reason)` refuses the login before any encryption or session-server request, with the reason on the disconnect screen; such a login never gets `PermissionsSetupEvent`, `LoginEvent` or `DisconnectEvent`. `forceOfflineMode()` on an online-mode proxy skips encryption and the session server for that connection: the player is then an unverified offline player (`isOnlineMode()` false, UUID as the client sent it or derived from the name), and that is the profile forwarded to backends. `forceOnlineMode()` on an offline-mode proxy authenticates that connection against Conduit's configured session URL, which must then be HTTPS or loopback, or the login is refused; a failed check refuses it too. On a proxy already in that mode each is the same as `allowed()`. `getConnection()` is a `LoginPhaseConnection` with the remote address (port 0), the dialled host and port, the protocol, the LOGIN state and the handshake intent (`TRANSFER` for a client that arrived by transfer); `sendLoginPluginMessage` and `getIdentifiedKey` are unsupported and throw. | Fired | LFT |
| `GameProfileRequestEvent`: after `PreLoginEvent` and authentication, before the conflicting-login check and `PermissionsSetupEvent`, with the profile the session server gave (online mode) or the client's claim (offline), and `isOnlineMode()`. A profile a plugin sets is the player's from then on: `getUniqueId`, `getUsername` and `getGameProfile` report it, modern forwarding sends it to every backend (with forwarding `none`, it is the name and UUID in the backend's Login Start), and it is the client's own tab-list entry. `isOnlineMode()` of the player does not change. The account that logged in still counts for the one-session-per-player check next to the replacement, so neither a second login of that account nor another account given a connected player's UUID or name gets in. Property signatures are passed on unchecked. A profile whose name is not 1 to 16 characters from `!` to `~` is not used, and a warning is logged. Conduit has no legacy (BungeeCord) forwarding. | Fired | LFT |
| `PreTransferEvent`: before every Transfer packet a 1.20.5+ player is sent, whether a plugin called `transferToHost` or the backend they are on sent one. `denied()` sends nothing, and the player stays where they are; `transferTo(address)` sends them there instead. A result left as it was sends the backend's own packet unchanged. | Fired | LFT |
| `LoginEvent`: denying it shows the reason on the client's disconnect screen, and no backend is ever contacted. Not fired for a player Conduit's maintenance mode refuses (they get `PermissionsSetupEvent` and `DisconnectEvent` only), nor for a client that left during `PermissionsSetupEvent`, nor for a login a newer login of the same player displaced during it. | Fired | VCT, LLT |
| `PostLoginEvent`: fired after the first backend is connected, whereas Velocity fires it before | Fired (partial) | VCT |
| `PlayerChooseInitialServerEvent`: `setInitialServer` works | Fired | VCT |
| `ServerPreConnectEvent`: can deny, or redirect with `allowed(otherServer)`; fired for the first connection and for switches. If every first server is denied, the connection is closed without a message. | Fired | VCT (deny, redirect), Maintenance |
| `ServerConnectedEvent` (with the previous server), `ServerPostConnectEvent` (after every connection, the first with no previous server; handlers are not waited for). For the first server of a client whose Configuration phase raises the configuration events below, both come once that phase has finished | Fired | VCT (ServerConnectedEvent), VAT, CET, ForcePack |
| `PlayerEnterConfigurationEvent`, `PlayerEnteredConfigurationEvent`, `PlayerConfigurationEvent`, `PlayerFinishConfigurationEvent`, `PlayerFinishedConfigurationEvent`: for a 1.20.2+ client at its first join and on every switch that reconfigures it, in that order, with the server it is being configured for; Enter only on a switch, as its Javadoc says. Enter is waited for before the client is asked to reconfigure, and Finish before it is told to finish (each for at most 10 seconds; Velocity documents 5). Entered and then `PlayerConfigurationEvent` run while the server's phase is relayed, and the phase does not finish until both are done, for at most 10 seconds: a pack a plugin offers meanwhile goes to the client as a Configuration packet (Push from 1.20.3, Send on 1.20.2), and the client's answers reach `PlayerResourcePackStatusEvent` and never the server. Finished is not waited for. `getProtocolState()` is `CONFIGURATION` from Entered to Finish and `PLAY` at Finished | Partial: only where Conduit relays the phase itself between a client and a server that both have one. Not when Via translates the session, since Via builds or translates the phase, its Finish Configuration included; not for a server without a Configuration phase, nor for a reconfiguration the server starts itself. A 1.20.1 or older client has no Configuration phase. While the phase is held, the proxy relays nothing more from the server, which is waiting for the client to finish. During a switch's phase, `getCurrentServer()` is still the server being left, until the switch completes. | CET, ForcePack |
| `DisconnectEvent`: exactly once for every player who got `PermissionsSetupEvent`, however the login ended. `SUCCESSFUL_LOGIN` for a player who got `PostLoginEvent`; `PRE_SERVER_JOIN` for one let in by `LoginEvent` whom no server took; `CANCELLED_BY_PROXY` for a login refused by maintenance, a denied `LoginEvent`, a kick during the login or a proxy shutdown; `CANCELLED_BY_USER_BEFORE_COMPLETE` for a client that hung up while its login was being decided. `CONFLICTING_LOGIN` for a login that a newer login of the same player displaced before it finished, which only happens with `[authentication] kick-existing-players = true`; a player who had finished logging in and is displaced that way leaves with `SUCCESSFUL_LOGIN`. A second login of a player who is already connected (the same UUID, or the same name in any letter case) is refused before `PermissionsSetupEvent`, so plugins never see it and it gets no `DisconnectEvent`. `CANCELLED_BY_USER` is never reported. | Fired | VCT, LLT |
| `PluginMessageEvent`: only for channels registered with the `ChannelRegistrar`, in both directions; `handled()` stops forwarding | Fired | VCT |
| `CommandExecuteEvent`: `denied()` works; `command(...)` rewrites what the proxy looks up and runs; `forwardToServer()` and `forwardToServer(command)` send it to the backend even when the proxy has a command by that name. A rewritten command reaches the backend as rewritten only from a client before 1.19; a later client's commands can carry argument signatures, so the backend gets the command as typed, and a warning is logged | Fired (partial) | VCT, `CommandForwardingTests` |
| `PlayerChatEvent`: pre-1.19 clients only (signed chat is relayed untouched); `denied()` works, and `message(...)` sends the backend the rewritten line instead of the typed one (a rewrite into a command, starting with `/`, is refused with a warning) | Fired (partial) | VCT, `CommandForwardingTests` |
| `PermissionsSetupEvent`: see Permissions | Fired | VCT, LLT, LuckPerms |
| `ProxyPingEvent`: fired for every server-list request with a `ServerPing` built from Conduit's answer (after its `[status]` settings, maintenance and version gate), and the plugin's `ServerPing` is what the client is sent: description, version name and protocol, online and maximum counts, player sample, favicon. A denied result sends no answer and closes the connection. `getConnection()` gives the remote address, the dialled host and port, the protocol and the STATUS state. The description keeps what Conduit Text carries (see [Text formatting](#text-formatting)) and is written in the pinging client's own form: a 1.16+ client sees RGB colours, an older one the nearest named colours. `nullPlayers()` hides the counts, which the client shows as "???". Mod info is not honoured. | Fired (partial) | VCT (`ping:`), VTT, MiniMOTD, Maintenance |
| `KickedFromServerEvent`: fired when a backend kicks a player or refuses them (`kickedDuringServerConnect()`), with Conduit's own default as the result: `DisconnectPlayer` with the backend's reason when kicked while playing, `Notify` with the reason when a switch, a first connection or a fallback was refused at login. `DisconnectPlayer`, `Notify` and `RedirectPlayer` are all honoured. `Notify` outside a connect disconnects with its message, as the API says. `RedirectPlayer` without a message shows the kick reason once the player arrives, and with `Component.empty()` shows nothing. During a first connection a redirect names the next server to try, and its message is not shown because there is no chat yet. A redirect after a kick whose target refuses too is decided by that refusal's own event (`DisconnectPlayer` ends with its reason, `RedirectPlayer` names the next server, up to four in a row, `Notify` ends with the first server's reason). A result left as it was keeps the backend's reason exactly as the backend wrote it. | Fired | VCT (`kicked:`: redirect after a kick, Notify on a refused switch), SKT |
| `KickedFromServerEvent` for a 1.20.2+ server that sends a Configuration Disconnect before it has finished configuring the player: their first server, a switch's target or a fallback, once the client has entered that server's configuration phase. `kickedDuringServerConnect()` is true and the default is `DisconnectPlayer` with the backend's reason, shown exactly as it wrote it. The client has already left the server it was on and cannot be configured for another server, so the session always ends: `Notify` disconnects with its message, and `RedirectPlayer` is not honoured (the backend's reason is shown and a warning logged). No other server is tried afterwards. The same applies to a client older than 1.20.2 once it has been sent its first server's Login Success. A Configuration Disconnect during a reconfiguration the backend itself starts while the player is playing is still relayed to the client without the event. | Fired (partial) | SKT |
| `ServerRegisteredEvent`, `ServerUnregisteredEvent`: when a plugin registers or unregisters a server while the proxy runs (`registerServer`/`unregisterServer`, or Conduit's own API), once it is done: the server can already be looked up, or no longer can. Handlers are not waited for. Not fired for the servers in Conduit's configuration, which exist before any plugin loads. | Fired | VET |
| `ProxyPreShutdownEvent`: once Conduit has stopped accepting players and before any player is moved or kicked. The shutdown waits for its handlers, for at most 10 seconds, so a handler that never finishes delays it rather than stopping it. | Fired | VET |
| `ProxyReloadEvent`: after `/conduit reload` has applied the configuration; not after a reload that failed. Handlers are not waited for. | Fired | VET, Maintenance |
| `PlayerSettingsChangedEvent`: every time the client sends its settings, with what `getPlayerSettings` then returns: every field the client's release sends. Handlers are not waited for. | Fired | VET, `ClientSettingsTests` |
| `PlayerClientBrandEvent`: when the client sends `minecraft:brand` (`MC|Brand` before 1.13); the brand still goes on to the backend. Handlers are not waited for. | Fired | VET |
| `PlayerResourcePackStatusEvent`: every answer the client gives about a pack offered through the proxy, a plugin's or the backend's (`getPackInfo().getOrigin()` says which), with the pack's id and info; answers about a plugin's pack end at the proxy, answers about the backend's still reach it. Before 1.20.3 the answer is matched to an offer as described under Players. Handlers are not waited for, and `setOverwriteKick` has nothing to overrule: Conduit never kicks over a declined pack. | Fired | RPT |
| `ListenerBoundEvent`: for the one Minecraft listener, after `ProxyInitializeEvent`, as the proxy starts accepting players. `ListenerCloseEvent`: at shutdown, just after Conduit stops accepting players (Velocity fires it just before), and before `ProxyPreShutdownEvent`. There is no query listener. | Fired (partial) | VET |
| `TabCompleteEvent`: for a client before 1.13, each time its backend answers a Tab press, with what the player had typed and the answer as the mutable `getSuggestions()` list; the client is sent the list as the handlers leave it. When a command name is being completed, the proxy's matching commands are already in the list. Not fired when the proxy answers the Tab press itself (the arguments of a proxy command), nor for a 1.13+ client, as Velocity documents. Waited for; if the handlers take longer than the adapter waits, the client gets the list as it was. | Fired | TCT |
| `CookieReceiveEvent`: when a 1.20.5+ client answers a plugin's `requestCookie`, with the key and the cookie (null when it has none under that key). The answer ends at the proxy whatever the result: the backend never asked for it. Not fired for the answers to a backend's own requests, which reach the backend untouched. Handlers are not waited for. | Fired (partial) | PXT |
| `PlayerChannelRegisterEvent`, `PlayerChannelUnregisterEvent`: when the client sends `minecraft:register` or `minecraft:unregister` (`REGISTER`, `UNREGISTER` before 1.13), with the channels that one message names: a namespaced name as a `MinecraftChannelIdentifier`, any other as a `LegacyChannelIdentifier`. At most 256 channels per message, each at most 256 characters; blank and longer names are left out, and a message naming none raises no event. The message still reaches the backend unchanged. Not fired for what the client sends while a server switch is under way. Handlers are not waited for. | Fired (partial) | CCH |
| `PostCommandInvocationEvent`: once the proxy is done with a command a player, the console or a plugin ran. `EXECUTED` when a proxy command ran (or the source was told it may not use it); `EXCEPTION` when it threw; `SYNTAX_ERROR` when a `BrigadierCommand` could not parse what was typed; `FORWARDED` when a player's command went on to their backend. A Velocity plugin's command is reported once its body has run on the adapter's threads, with that body's outcome. Not fired for a command a `CommandExecuteEvent` cancelled. Handlers are not waited for. | Fired | CCH |
| `ConnectionHandshakeEvent`: for every connection's handshake, a ping (`STATUS`), a login (`LOGIN`) or an arrival by transfer (`TRANSFER`), before the proxy answers anything or checks the protocol. `getConnection()` gives the remote address, the dialled host and port (Forge markers removed), the protocol and the `HANDSHAKE` state. Handlers are not waited for, and nothing is built unless a plugin listens. | Fired | CCH |
| `PlayerAvailableCommandsEvent` (Conduit only appends its own commands to the root of a backend's command tree and never decodes the tree into nodes, because an argument node's parser and its properties are encoded differently across the releases it supports, by name before 1.19 and by a numeric id whose table shifts in later releases, and it has that table for 1.20.1 and 1.20.4 only), `PlayerModInfoEvent`, `ServerResourcePackSendEvent` and `ServerResourcePackRemoveEvent` (a backend's packs pass through untouched; plugins cannot deny or replace them), `CookieStoreEvent` and `CookieRequestEvent` (a backend's cookies pass through untouched), `ServerLoginPluginMessageEvent`, `ProxyQueryEvent` | **Never fired.** A listener for one is still registered, but Conduit logs a warning naming the handler that will never be called. | VCT (`ProxyQueryEvent`) |

## Scheduler, messaging, plugins, console

| API | Status | Verified by |
|---|---|---|
| `Scheduler.buildTask` (Runnable or Consumer), `delay`, `repeat`, `clearDelay`, `clearRepeat`, `schedule`, `ScheduledTask.cancel`, `status`, `tasksByPlugin`. Conduit's scheduler keeps the time, so tasks die with the plugin; bodies run on adapter threads, and a repeating task skips a run instead of overlapping itself. | Supported | VCT |
| `ChannelRegistrar.register/unregister` (`MinecraftChannelIdentifier`, `LegacyChannelIdentifier`). The registering plugin is found from its code on the calling stack, and when Conduit disables it its channels are unregistered, unless another enabled plugin registered them too. | Supported | VCT, VLT |
| `PluginManager`: `fromInstance`, `getPlugin`, `getPlugins` (Velocity plugins only), `isLoaded`, `addToClasspath` | Supported | VCT |
| `PluginContainer`: `getDescription`, `getInstance`, `getExecutorService` (shut down at disable) | Supported | VCT |
| `ConsoleCommandSource`: has every permission; messages go to Conduit's console | Supported | VCT (runs a command as the console) |

## Real plugins tried

Each was downloaded from the project's own Modrinth page through the Modrinth API (the file's
SHA-512 matched the one Modrinth lists), and run unmodified, outside the repository, in a real
Conduit with two scripted 1.8 backends, scripted 1.8 clients, status requests and console commands
(ForcePack and TitleAnnouncer also with scripted 1.12.2 clients and backends, and ForcePack's packs
served by a local HTTP server). bStats reporting was disabled for these runs. Real Minecraft clients
were not used.

| Plugin | Version | License | SHA-256 | Result |
|---|---|---|---|---|
| [LuckPerms](https://modrinth.com/plugin/luckperms) (Luck) | 5.5.71 (Velocity) | MIT | `a3ac92ef4e29fd87a4d706dc92445fd946f1caf1d33526ca18422a62554fa18a` | **Works, with one gap.** It downloads its libraries into its data folder, adds them with `addToClasspath`, creates its H2 storage and enables. Console and player `/lpv` commands work (info, user and group permission set/check/info, creategroup, parent add, sync), with formatted chat and click/hover. Its `PermissionsSetupEvent` handler loads each user and installs their function, and its `LoginEvent` check passes. Permissions it sets answer through Conduit: a player without `conduit.server` is refused Conduit's `/server` and allowed once it is set; wildcards (`luckperms.*`), group inheritance and a `world=<server>` context (following the player's server) all answer. Disabling it gives every player Conduit's default back. Re-run for the login lifecycle (with the libraries it had downloaded in the first run, and its automatic translation downloads switched off in its config): with Conduit's maintenance mode on, a player is refused until `/lpv user … permission set conduit.maintenance.bypass true` (or `conduit.admin true`), and then let in; one set to `false` stays out; once LuckPerms is disabled, the player it let in is refused again, since Conduit's permissive default grants no bypass. LuckPerms only knows a user who has tried to join, which a refused login counts as. Its `/luckpermsvelocity` and `/lpv` aliases are registered, and `//lpv info` from the console runs; LuckPerms lets only the console use those aliases, so a player's `//lpv` was refused in that run; such a command now goes to the backend as on Velocity (see Commands). Re-run against Conduit started from a `conduit.toml`, with Maintenance 5.1.0 also loaded: a player's `//lpv info` reaches the backend as chat, while their `/lpv info` and the console's `//lpv info` run LuckPerms; the bypass holds as above, a name on Conduit's maintenance allowlist gets in with no permission (also once LuckPerms is disabled), a bypass unset while the player is offline refuses their next login, and a second login of a connected player is refused ("You are already connected to this network.") without keeping them out once both connections are gone, whether or not maintenance let the first one in. Gap: its messages were always English in the first run, since `getPlayerSettings` then had only the default locale. The client's locale now reaches `getPlayerSettings` (VCT); LuckPerms has not been re-run with a client that sends one. |
| [MiniMOTD](https://modrinth.com/plugin/minimotd) (jpenilla) | 2.2.5 | MIT | `b5d01ae7596b951f842d105d8a70552bf75761ced2bd413739d627c59b058ed5` | **Works.** It loads through the Guice `Injector` and child injector it asks for, and its MOTDs (chosen at random from its config), maximum player count (69 in its default config) and `/minimotd reload` all take effect on the server list. Re-run after the text-model change with status requests as 1.8, 1.12.2, 1.20.4 and 26.2 clients: its default gradients (`MiniMOTD Default`, `Gradients`, `much wow`) reach 1.20.4 and 26.2 as per-letter RGB colours such as `#e76d55`, and 1.8 and 1.12.2 as the nearest named colours; the italic and underlined line keeps both decorations for every release. `/minimotd reload` from the console and from a 1.8 player works; the player's reply keeps its gradient prefix, in named colours. |
| [Maintenance](https://modrinth.com/plugin/maintenance) (kennytv) | 5.1.0 | GPL-3.0 | `06f7d8fe346a5c35d1a2c38db1f12ea841bdc70df9496a20bf4c5accd972c8a5` | **Works for global maintenance.** `/maintenance` help, `on`/`off`, `status` and `add`, from the console and from a player. With maintenance on, the server list shows its MOTD, its "Maintenance" version text and its hover lines; a player without `maintenance.bypass` is refused at login with its kick message, and players online without it are kicked when it is turned on; run with LuckPerms, a player granted `maintenance.*` joins and runs its commands. It needs snakeyaml from the proxy (supplied). Per-server maintenance was not tried. Re-run after Conduit's login lifecycle was restructured: without a permission plugin nobody is refused, because Conduit's default grants every node, `maintenance.bypass` included (as in the first run). With LuckPerms 5.5.71, turning maintenance on kicks an online player without `maintenance.bypass` with its message and leaves one given it through `/lpv` online; while it is on, a player without it is refused at login and the one with it can leave and join again, also after a restart (LuckPerms keeps the node in its H2 storage); after `/maintenance off` from the console the refused player joins. `/conduit reload` now reaches its `ProxyReloadEvent` handler, which reloads its config files. |
| [Server Redirect](https://modrinth.com/plugin/server-redirect) (KaiKikuchi) | Plugin-1.4.3 | MIT | `276c4ddf054c91c81d0c0af5eb75e5222b80b08b439e0046e84c70b4770c0c19` | **Works.** `/redirect` and `/fallback` (one player or `*`) send its `srvredirect:red` / `srvredirect:fal` plugin messages to the client, from the console and from a player. A client that announces the mod on `srvredirect:ann` is recognised, and `/ifplayercanredirect` / `/ifplayercannotredirect` then run their command as the console. Its own event (`PlayerRedirectEvent`) goes through `EventManager.fire`. Re-run after channel registrations became per plugin: while it is enabled, `/redirect` sends `srvredirect:red`, `/fallback *` sends `srvredirect:fal` to every player, and an announce on `srvredirect:ann` still makes `/ifplayercanredirect` run its command. Once Conduit disables it, its commands are gone. |
| [mclo.gs](https://modrinth.com/plugin/mclogs) (Aternos) | 3.3.3 (Velocity) | MIT | `61acac8f0a9a9aedbaa9ce1ce1dcd784bd305e58a10aabc8c0eec218a3f9ef53` | **Works for what was tried.** Its Brigadier commands `/mclogsp` and `/mclogsv` run from the console and a player, with its messages, click events and Brigadier syntax errors. It needed a plugin to be able to register its own alias again (now supported). Uploading a log to mclo.gs was not tried, as it sends the log to that service. |
| [velocity-hub](https://modrinth.com/plugin/velocity-hub-command) (stellarcielo) | 1.10-SNAPSHOT | MIT | `e7a348beb3ce74d36494e4d31fbb4df56f7f0a5f6314be417056f07fb4ad8f77` | **Works** (rechecked). It takes over `/hub` and `/lobby`, which send the player to the configured hub and detect "already connected". Its `ProxyInitializeEvent` and `PostLoginEvent` handlers run. It needs the plugin's own `Metrics.Factory` to be injected (supported). It writes its config to `plugins/velocity-hub` under the working directory rather than to `@DataDirectory`; that is the plugin's own choice. Re-run: its `/hub` replaces Conduit's own, so Conduit's choice of a lobby health checks allow does not apply; it always uses the one server its config names (`hubServerName`, `hub` by default, which has to be changed to a registered server). With that server marked unhealthy, its connection request fails at once, without dialling the server, and the player is told "Unable to connect to lobby: lobby is unavailable"; once it is healthy again, `/hub` sends them there. At startup it looks online for a newer release of itself, and logs an error when it cannot reach the service. |
| [ForcePack](https://modrinth.com/plugin/forcepack) (SamB440) | 1.3.75-SNAPSHOT (paper-velocity jar) | GPL-3.0-only | `38edb1890ce06448895ed074eeedf52bcd4e2818a70ef871e2f14446b7bf3d54` | **Works for per-server packs on 1.8 and 1.12.2 clients, and on 1.20.4 in its configuration-phase mode at the first join.** It needs night-config's TOML reader from the proxy, which Velocity's proxy carries: without it, its `ProxyInitializeEvent` handler failed with `NoClassDefFoundError` (now supplied). It checks its configured pack's SHA-1 by downloading it at startup. It sends a server's pack from `ServerPostConnectEvent`, which Conduit used to raise only on switches, so nobody was sent a pack on joining (now raised for the first server too, VAT). Now a 1.8 and a 1.12.2 player get the lobby's pack a second after joining (its URL with `#<hash>` appended, and the hash), and again every second (its `update-gui` for clients up to 1.12) until they say it loaded; the loaded status reaches the backend as its `forcepack:status` plugin message and the player gets its configured message, while a player who declines is kicked with its message. Leaving for a server without a pack sends its unload pack; coming back sends the lobby's pack again once the player has loaded the unload pack (one who has not answered it still holds the lobby's, and ForcePack only tells the backend it is loaded). Found and fixed: when a player's answer crossed one of its repeats, Conduit forgot the loaded pack at the repeat, so the repeats never stopped; a loaded pack offered again unchanged now stays applied (VAT). Its own code, as on Velocity: it sends the pack once more after it has loaded, and it does not cancel a player's repeating task when they leave before loading, so that task then logs a `NullPointerException` every second (seen after the decline kick). Re-run in its configuration-phase mode (`use-configuration-phase = true`) with scripted 1.20.4 clients and servers: at the first join its `PlayerConfigurationEvent` handler holds the phase, the client is pushed the lobby's pack as a Configuration packet, its answers (accepted, downloaded, loaded) reach its status handler, the backend gets its `forcepack:status` message, and only then is the client told to finish; its `ServerPostConnectEvent` handler then sees the join was handled. A player who declines is kicked with its message during the Configuration phase. Found and fixed: the first server's `ServerPostConnectEvent` used to come before the phase, so the pack was sent twice, once by each handler (it now comes after, CET). A switch to a server without a pack has its packs popped during that server's phase. Gap: on a switch back to a server with a pack, its status handler reads the server from `getCurrentServer()`, which on Conduit is still the server being left until the switch completes, so it ignores the answers and the phase finishes only when Conduit's 10-second wait runs out. Not tried: global and group packs, its web server and commands. |
| [TitleAnnouncer](https://modrinth.com/plugin/titleannouncer) (4drian3d) | 3.1.0 (Velocity) | GPL-3.0-or-later | `2a77e05c6face7dec692a9918bb578deb64e8286fa43c20e52edf9dff7546a1e` | **Works.** It loads its HOCON config with Configurate, which velocity-api's POM names: without it, its commands failed to build (`NoClassDefFoundError` inside Guice) and none was registered (now supplied). `/vannounce title`, `actionbar`, `chat` and `bossbar` reach 1.8 and 1.12.2 players, from the console and from a player, with the targets `self`, `player:<name>`, `all` (the proxy), `server` and `server:<name>`; MiniMessage gradients and decorations arrive as JSON, 1.8 gets the action bar as a chat line, boss bars on 1.12.2 count down each second and go when done, `/vtitleannouncer clear <target> bossbar` and `title` work, and `/vtitleannouncer` shows its about menu. Found and fixed: with `all` or a server as target, titles, the action bar, boss bars and `clearTitle` threw `UnsupportedOperationException` (chat alone worked), because the proxy and servers were chat-only audiences; they now forward to their players. Its boss bars never counted down or cleared, because it finds a player with `get(Identity.UUID)`, which was empty; players now answer Adventure's pointers (VAT). Its target is a Brigadier string, so `"player:<name>"` and `"server:<name>"` need quotes, on Velocity too. `sound` was not tried. |

The two extra plugins were picked from Modrinth's most-downloaded Velocity plugins, skipping those
whose core is something the adapter does not support: Simple Voice Chat and Plasmo Voice (voice
chat), ViaVersion, ViaBackwards and ViaRewind (they hook Velocity's network pipeline; Conduit
translates with Via itself), SkinsRestorer (game profile properties), Geyser (Bedrock), TAB (it reads
and rewrites the backend's tab list and scoreboard teams, which Conduit does not track), PacketEvents
(packet injection) and Raknetify (transport). ForcePack and TitleAnnouncer were picked later as
open-source plugins whose core is resource packs, and titles, boss bars and MiniMessage, through
velocity-api and Adventure alone (no Velocity internals, no Netty, no database, no update check).

## Build

```powershell
./scripts/test.ps1 -Only gg.tame.conduit.tests.VelocityCompatTests
./scripts/test.ps1 -Only gg.tame.conduit.tests.VelocityDisplayTests
```

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

The jars are fetched by `scripts/fetch-velocity-compat.ps1`. Configurate, toml4j and caffeine are
**not** on the class path.

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
| `hasPermission` / `getPermissionValue`: the player's function from `PermissionsSetupEvent` (see Permissions), or, when no plugin set one, Conduit's permission provider, which answers TRUE or FALSE and never UNDEFINED | Supported | VCT, LuckPerms, Maintenance |
| `getRemoteAddress`: the address is correct, but the port is always 0 because Conduit's API does not carry it | Partial | VCT |
| `getRawVirtualHost`: host name only, with FML markers removed | Partial | |
| `getGameProfile`: id and name only, no properties such as skins | Partial | VCT |
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
| `RegisteredServer.ping()`: Conduit asks the backend for its status now. The `ServerPing` has the backend's version name and protocol, online and maximum counts, player sample, description and favicon; `getModinfo()` is empty, as Conduit does not read a backend's mod list. The description keeps what Conduit Text carries (see [Text formatting](#text-formatting)): RGB colours, decorations, translations with their arguments and the common events are kept; fonts, keybind, score, selector and NBT components are not. The handshake announces protocol `-1` (no client version, so the backend answers with its own) and the host of the server's configured address; `[health] timeout-ms` bounds the whole ping. A backend that cannot be reached, does not answer in time, or sends something that is not a status answer (malformed or oversized JSON, a connection closed mid-answer) fails the future with an `IOException`. Pings run on a bounded pool of socket threads, 32 at once; up to 1024 more wait their turn inside their own timeout, and beyond that a ping fails at once. Callbacks run on the adapter's threads. | Partial | VCT (`backend-ping`, `dead-ping`), BPT |
| `RegisteredServer.ping(PingOptions)`: the answer is as for `ping()`, and every option is honoured: the protocol version is announced in the handshake (`UNKNOWN` announces `-1`), the virtual host is the host the handshake names (none: the configured address's host), and a timeout replaces `[health] timeout-ms` (zero: that default) | Supported | BPT |
| On a player: `showTitle`, `sendTitlePart` (`TITLE`, `SUBTITLE`, `TIMES`), `clearTitle`, `resetTitle`, `sendActionBar` | Supported, as far as the client's release has them (see [Display by client release](#display-by-client-release)). Text keeps what chat keeps (see `sendMessage`). Title times become whole ticks, rounded down. Anything sent while the client has no world to show it in (before its first Join Game, or while a 1.20.2+ client is reconfigured by a switch) is held, the last 16, and shown once it has one. | VDT, DAT |
| On a player: `showBossBar`, `hideBossBar` (and Adventure's `BossBar.addViewer`/`removeViewer`, which call them). Name, progress, colour, overlay and all three flags; every change a plugin makes to the bar reaches every player viewing it. The bar is shown again after each server switch. The adapter holds one listener on the plugin's bar while anyone views it, and removes it when the last viewer hides the bar or disconnects. | Supported for 1.9+ clients. A 1.7 or 1.8 client has no boss bar: it is sent nothing, though it still counts as a viewer. | VDT, DAT |
| On a player: `sendPlayerListHeaderAndFooter`, `sendPlayerListHeader`, `sendPlayerListFooter`, `clearPlayerListHeaderAndFooter`, `getPlayerListHeader`, `getPlayerListFooter`, and `TabList.setHeaderAndFooter` / `clearHeaderAndFooter`. The pair is sent again after each server switch. A backend that sends its own replaces the proxy's on the client until the proxy's next send or switch: the last writer wins. The getters return what plugins set through the proxy, never a backend's. | Supported for 1.8+ clients; a 1.7 client has no header and is sent nothing | VDT, DAT |
| `Player.getTabList()` entries: `buildEntry` / `TabListEntry.builder()`, `addEntry`, `addEntries`, `getEntry`, `getEntries`, `containsEntry`, `removeEntry`, `clearAll`, and an entry's `setDisplayName`, `setLatency`, `setGameMode`, `setListed`, `setListOrder`, `setShowHat` | Partial: the proxy's own entries only. The backend's entries are not tracked, so `getEntries` and `containsEntry` see only entries plugins added and `clearAll` removes only those; the backend's list is never touched. Entries stay across server switches and are sent again. An entry with a chat session throws. Which clients are sent them, and which fields, is under [Display by client release](#display-by-client-release). | VDT (1.12.2), DAT |
| On a player: `sendResourcePackOffer`, `sendResourcePack(url)` and `(url, hash)`, Adventure's `sendResourcePacks` (every pack in the request with its prompt and required flag; `replace` clears first; its callback is told each answer), `removeResourcePacks`, `clearResourcePacks`, `getAppliedResourcePack(s)`, `getPendingResourcePack(s)`. Written in the client's own protocol: one Resource Pack Send through 1.20.2 (URL and hash, and from 1.17 the required flag and prompt), Push and Pop naming the pack by UUID from 1.20.3. An offer made while the client is logging in or switching servers waits until it has a world again. The client's answers about the proxy's packs never reach the backend; its answers about the backend's packs still do. The proxy's packs stay across server switches; a backend's are neither removed nor offered again on a switch. `getAppliedResourcePacks` and `getPendingResourcePacks` list what was offered through the proxy, by a plugin (`PLUGIN_ON_PROXY`) or by the backend (`DOWNSTREAM_SERVER`), as the client has answered: a declined, failed or dropped pack leaves the lists. The prompt keeps what Conduit Text carries. | Partial: a client before 1.20.3 holds one pack and has no way to drop one, so `removeResourcePacks` and `clearResourcePacks` do nothing for it, and its answers name no pack, so they are matched to the offers in the order it got them (1.8's by hash first). `getShouldForce` is honoured by 1.17+ clients, which disconnect themselves when the player declines; Conduit does not kick an older client that declines. A 1.7 client is sent nothing. | RPT |
| Sounds, books, dialogs, signed-message chat, cookies, transfer, server links, custom chat completions, `spoofChatInput`, `getModInfo`, `getIdentifiedKey`, `getHandshakeIntent`, game profile properties; and titles, the action bar, boss bars, the header and resource packs on `ProxyServer`, a `RegisteredServer` or the console as audiences (only a player's are supported) | Unsupported: every one throws `UnsupportedOperationException` naming the API, including Adventure methods that are silent no-ops by default | VCT (`stopSound`) |

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
| `RawCommand`: the argument string arrives with runs of spaces collapsed | Partial | VCT |
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
| `LoginEvent`: denying it shows the reason on the client's disconnect screen, and no backend is ever contacted. Not fired for a player Conduit's maintenance mode refuses (they get `PermissionsSetupEvent` and `DisconnectEvent` only), nor for a client that left during `PermissionsSetupEvent`, nor for a login a newer login of the same player displaced during it. | Fired | VCT, LLT |
| `PostLoginEvent`: fired after the first backend is connected, whereas Velocity fires it before | Fired (partial) | VCT |
| `PlayerChooseInitialServerEvent`: `setInitialServer` works | Fired | VCT |
| `ServerPreConnectEvent`: can deny, or redirect with `allowed(otherServer)`; fired for the first connection and for switches. If every first server is denied, the connection is closed without a message. | Fired | VCT (deny, redirect), Maintenance |
| `ServerConnectedEvent` (with the previous server), `ServerPostConnectEvent` (switches only) | Fired | VCT (ServerConnectedEvent) |
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
| `PreLoginEvent`, `GameProfileRequestEvent`, `TabCompleteEvent`, `PlayerAvailableCommandsEvent`, `PostCommandInvocationEvent`, `PlayerModInfoEvent`, `PlayerChannel(Un)RegisterEvent`, `ConnectionHandshakeEvent`, `ServerResourcePackSendEvent` and `ServerResourcePackRemoveEvent` (a backend's packs pass through untouched; plugins cannot deny or replace them), cookie and configuration-phase events, `ServerLoginPluginMessageEvent`, `ProxyQueryEvent`, `PreTransferEvent` | **Never fired.** A listener for one is still registered, but Conduit logs a warning naming the handler that will never be called. | VCT (`ProxyQueryEvent`) |

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
Conduit with two scripted 1.8 backends, scripted 1.8 clients, status requests and console commands.
bStats reporting was disabled for these runs. Real Minecraft clients were not used.

| Plugin | Version | License | SHA-256 | Result |
|---|---|---|---|---|
| [LuckPerms](https://modrinth.com/plugin/luckperms) (Luck) | 5.5.71 (Velocity) | MIT | `a3ac92ef4e29fd87a4d706dc92445fd946f1caf1d33526ca18422a62554fa18a` | **Works, with one gap.** It downloads its libraries into its data folder, adds them with `addToClasspath`, creates its H2 storage and enables. Console and player `/lpv` commands work (info, user and group permission set/check/info, creategroup, parent add, sync), with formatted chat and click/hover. Its `PermissionsSetupEvent` handler loads each user and installs their function, and its `LoginEvent` check passes. Permissions it sets answer through Conduit: a player without `conduit.server` is refused Conduit's `/server` and allowed once it is set; wildcards (`luckperms.*`), group inheritance and a `world=<server>` context (following the player's server) all answer. Disabling it gives every player Conduit's default back. Re-run for the login lifecycle (with the libraries it had downloaded in the first run, and its automatic translation downloads switched off in its config): with Conduit's maintenance mode on, a player is refused until `/lpv user … permission set conduit.maintenance.bypass true` (or `conduit.admin true`), and then let in; one set to `false` stays out; once LuckPerms is disabled, the player it let in is refused again, since Conduit's permissive default grants no bypass. LuckPerms only knows a user who has tried to join, which a refused login counts as. Its `/luckpermsvelocity` and `/lpv` aliases are registered, and `//lpv info` from the console runs; LuckPerms lets only the console use those aliases, so a player's `//lpv` was refused in that run; such a command now goes to the backend as on Velocity (see Commands), which has not been re-run with LuckPerms. Gap: its messages were always English in the first run, since `getPlayerSettings` then had only the default locale. The client's locale now reaches `getPlayerSettings` (VCT); LuckPerms has not been re-run with a client that sends one. |
| [MiniMOTD](https://modrinth.com/plugin/minimotd) (jpenilla) | 2.2.5 | MIT | `b5d01ae7596b951f842d105d8a70552bf75761ced2bd413739d627c59b058ed5` | **Works.** It loads through the Guice `Injector` and child injector it asks for, and its MOTDs (chosen at random from its config), maximum player count and `/minimotd reload` all take effect on the server list. In that run its gradients arrived with hex colours snapped to the nearest named colour; Conduit now sends RGB colours to 1.16+ clients (VTT), and MiniMOTD has not been re-run since. |
| [Maintenance](https://modrinth.com/plugin/maintenance) (kennytv) | 5.1.0 | GPL-3.0 | `06f7d8fe346a5c35d1a2c38db1f12ea841bdc70df9496a20bf4c5accd972c8a5` | **Works for global maintenance.** `/maintenance` help, `on`/`off`, `status` and `add`, from the console and from a player. With maintenance on, the server list shows its MOTD, its "Maintenance" version text and its hover lines; a player without `maintenance.bypass` is refused at login with its kick message, and players online without it are kicked when it is turned on; run with LuckPerms, a player granted `maintenance.*` joins and runs its commands. It needs snakeyaml from the proxy (supplied). Per-server maintenance was not tried. Re-run after Conduit's login lifecycle was restructured: without a permission plugin nobody is refused, because Conduit's default grants every node, `maintenance.bypass` included (as in the first run). With LuckPerms 5.5.71, turning maintenance on kicks an online player without `maintenance.bypass` with its message and leaves one given it through `/lpv` online; while it is on, a player without it is refused at login and the one with it can leave and join again, also after a restart (LuckPerms keeps the node in its H2 storage); after `/maintenance off` from the console the refused player joins. `/conduit reload` now reaches its `ProxyReloadEvent` handler, which reloads its config files. |
| [Server Redirect](https://modrinth.com/plugin/server-redirect) (KaiKikuchi) | Plugin-1.4.3 | MIT | `276c4ddf054c91c81d0c0af5eb75e5222b80b08b439e0046e84c70b4770c0c19` | **Works.** `/redirect` and `/fallback` (one player or `*`) send its `srvredirect:red` / `srvredirect:fal` plugin messages to the client, from the console and from a player. A client that announces the mod on `srvredirect:ann` is recognised, and `/ifplayercanredirect` / `/ifplayercannotredirect` then run their command as the console. Its own event (`PlayerRedirectEvent`) goes through `EventManager.fire`. Re-run after channel registrations became per plugin: while it is enabled, `/redirect` sends `srvredirect:red`, `/fallback *` sends `srvredirect:fal` to every player, and an announce on `srvredirect:ann` still makes `/ifplayercanredirect` run its command. Once Conduit disables it, its commands are gone. |
| [mclo.gs](https://modrinth.com/plugin/mclogs) (Aternos) | 3.3.3 (Velocity) | MIT | `61acac8f0a9a9aedbaa9ce1ce1dcd784bd305e58a10aabc8c0eec218a3f9ef53` | **Works for what was tried.** Its Brigadier commands `/mclogsp` and `/mclogsv` run from the console and a player, with its messages, click events and Brigadier syntax errors. It needed a plugin to be able to register its own alias again (now supported). Uploading a log to mclo.gs was not tried, as it sends the log to that service. |
| [velocity-hub](https://modrinth.com/plugin/velocity-hub-command) (stellarcielo) | 1.10-SNAPSHOT | MIT | `e7a348beb3ce74d36494e4d31fbb4df56f7f0a5f6314be417056f07fb4ad8f77` | **Works** (rechecked). It takes over `/hub` and `/lobby`, which send the player to the configured hub and detect "already connected". Its `ProxyInitializeEvent` and `PostLoginEvent` handlers run. It needs the plugin's own `Metrics.Factory` to be injected (supported). It writes its config to `plugins/velocity-hub` under the working directory rather than to `@DataDirectory`; that is the plugin's own choice. Re-run: its `/hub` replaces Conduit's own, so Conduit's choice of a lobby health checks allow does not apply; it always uses the one server its config names (`hubServerName`, `hub` by default, which has to be changed to a registered server). With that server marked unhealthy, its connection request fails at once, without dialling the server, and the player is told "Unable to connect to lobby: lobby is unavailable"; once it is healthy again, `/hub` sends them there. At startup it looks online for a newer release of itself, and logs an error when it cannot reach the service. |

The two extra plugins were picked from Modrinth's most-downloaded Velocity plugins, skipping those
whose core is something the adapter does not support: Simple Voice Chat and Plasmo Voice (voice
chat), ViaVersion, ViaBackwards and ViaRewind (they hook Velocity's network pipeline; Conduit
translates with Via itself), SkinsRestorer (game profile properties), Geyser (Bedrock), TAB (it reads
and rewrites the backend's tab list and scoreboard teams, which Conduit does not track), PacketEvents
(packet injection) and Raknetify (transport).

## Build

```powershell
./scripts/test.ps1 -Only gg.tame.conduit.tests.VelocityCompatTests
./scripts/test.ps1 -Only gg.tame.conduit.tests.VelocityDisplayTests
```

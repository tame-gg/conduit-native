# Velocity plugin compatibility

Conduit is not Velocity. It can run many plugins built against the published
`com.velocitypowered:velocity-api` (3.4.0) through a compatibility adapter, but not every Velocity
plugin will work. Each call listed below is marked **supported**, **partial**, or **unsupported**,
and names the test or real plugin that verifies it. If a feature is not listed, it does not work.

Where these marks come from: **VCT** is `VelocityCompatTests`, which compiles purpose-built plugins
against the real velocity-api (its annotation processor writes their `velocity-plugin.json`), loads
them from jars into a real `MinecraftProxy`, and drives them with a scripted 1.8 client and two
scripted backends. **P9** is `Phase9Tests`. The **real plugins** are listed at the end.

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
left it, or with the permission denied, logging a warning.

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
| `shutdown(Component)`: Conduit kicks players with its configured message | Unsupported (throws) | |
| `getConfiguration()`: `getServers`, `isOnlineMode`, `getAttemptConnectionOrder` (routing's initial servers) | Partial | VCT |
| `getConfiguration()`: `getMotd`, `getShowMaxPlayers`, `getFavicon`, from Conduit's `[status]` settings as configured (what a client is sent can differ, after maintenance or a ping listener) | Supported | VCT (`serverlist:`), Maintenance |
| `getConfiguration()`: `getForcedHosts` is empty, because Conduit has no forced hosts | Supported | Maintenance |
| `getConfiguration()`: every other setting (query, compression, rate limits, timeouts, announce-forge, proxy-connection checks) | Unsupported (throws) | |
| `createRawRegisteredServer`, `closeListeners`, `createResourcePackBuilder` | Unsupported (throws) | |

## Players and servers

| API | Status | Verified by |
|---|---|---|
| `getUsername`, `getUniqueId`, `identity`, `isOnlineMode`, `isActive`, `getProtocolVersion`, `getProtocolState`, `getVirtualHost` | Supported | VCT |
| `hasPermission` / `getPermissionValue`: the player's function from `PermissionsSetupEvent` (see Permissions), or, when no plugin set one, Conduit's permission provider, which answers TRUE or FALSE and never UNDEFINED | Supported | VCT, LuckPerms, Maintenance |
| `getRemoteAddress`: the address is correct, but the port is always 0 because Conduit's API does not carry it | Partial | VCT |
| `getRawVirtualHost`: host name only, with FML markers removed | Partial | |
| `getGameProfile`: id and name only, no properties such as skins | Partial | VCT |
| `getPing`: always `-1`, which the API defines as "unknown" | Partial | |
| `getPlayerSettings`: the vanilla client's defaults (locale `en_US`, view distance 12, chat shown with colours, every skin part, right hand), and `hasSentPlayerSettings()` is false, because Conduit's API does not carry the client's settings. A plugin that localises by the client's locale always picks English. | Partial | LuckPerms |
| `sendMessage(Component)`, `sendRichMessage` (MiniMessage) | Partial: keeps colour (hex snapped to the nearest named colour), bold, italic, run-command clicks and text hovers; other decorations and events are dropped, and translatable, keybind, score and selector components are sent as plain text | VCT, Maintenance |
| `disconnect(Component)`: a real kick screen, with the same formatting kept | Supported | VCT |
| `createConnectionRequest(...).connect()`: `SUCCESS`, `ALREADY_CONNECTED`, `CONNECTION_IN_PROGRESS`, `CONNECTION_CANCELLED`, and `SERVER_DISCONNECTED` for any failure, with its reason | Supported | VCT, velocity-hub |
| `connectWithIndication`, `fireAndForget`: on failure the player is told "Unable to connect to &lt;server&gt;" | Supported | VCT |
| `getCurrentServer`, and on `ServerConnection`: `getServer`, `getServerInfo`, `getPlayer`, `getPreviousServer` (from the last switch) | Supported | VCT |
| `sendPluginMessage` to the client (`Player`), or to the backend (`ServerConnection`, and `RegisteredServer` through a player connected to it); byte-array and encoder forms | Supported | VCT |
| `RegisteredServer`: `getServerInfo`, `getPlayersConnected`, `sendMessage` | Supported | VCT |
| `RegisteredServer.ping()`: Conduit asks the backend for its status now. The `ServerPing` has its version and player counts; its description is empty and it has no favicon or player sample, because Conduit's probe does not keep them. A backend that does not answer fails the future. Callbacks run on the adapter's threads. | Partial | VCT (`backend-ping`, `dead-ping`) |
| `RegisteredServer.ping(PingOptions)`: `PingOptions.DEFAULT` only; any other options throw | Partial | |
| Titles, action bar, boss bars, sounds, books, dialogs, signed-message chat, resource packs, tab list and its header/footer, cookies, transfer, server links, custom chat completions, `spoofChatInput`, `getEffectiveLocale`, `getClientBrand`, `getModInfo`, `getIdentifiedKey`, `getHandshakeIntent`, game profile properties | Unsupported: every one throws `UnsupportedOperationException` naming the API, including Adventure methods that are silent no-ops by default | VCT (`sendActionBar`) |

## Permissions

| API | Status | Verified by |
|---|---|---|
| `PermissionsSetupEvent` for every joining player, once, after authentication and before `LoginEvent`, with a default provider that answers what Conduit's permission provider answers. Not fired for the console, which has every permission. | Supported | VCT (`permsetup`), LuckPerms |
| The function a plugin sets answers for that player everywhere: `Player.hasPermission` / `getPermissionValue` for every Velocity plugin, and Conduit's own checks (its commands, native plugins' command permissions). Conduit hears a boolean, so UNDEFINED is "no" there, as `hasPermission` reads it. | Supported | VCT (`/nsecret` refused for Erin), LuckPerms (`/server` refused, then allowed after `/lpv user … permission set conduit.server true`) |
| While such a function is in force, a player who has not been through `PermissionsSetupEvent` yet has no permissions. This covers Conduit's own maintenance bypass check, which runs before login: a Velocity permissions plugin cannot grant `conduit.maintenance.bypass`, and the maintenance allowlist by name still works. | Partial | |
| When the plugin that set the functions is disabled, they stop answering and Conduit's permission provider is back for every player, the permissive default included | Supported | VCT (vtest disabled, Erin back on the default), LuckPerms (`disable luckperms`) |

## Commands

| API | Status | Verified by |
|---|---|---|
| `SimpleCommand`: execute, suggest, `hasPermission`, aliases, `alias()` of the invocation | Supported | VCT, Maintenance, velocity-hub |
| `RawCommand`: the argument string arrives with runs of spaces collapsed | Partial | VCT |
| `BrigadierCommand`: parsed, permission-checked, executed and completed on the proxy with the Brigadier library, and syntax errors are shown to the player | Partial: clients are sent only the command's literal name, not its argument nodes, which is also true of Conduit's own commands; completion is not covered by a test | VCT (execute and syntax error) |
| `metaBuilder`, `register`, `unregister`, `getCommandMeta`, `getAliases` (Velocity-registered aliases only), `hasCommand` (any proxy command, including Conduit's own and native plugins') | Supported | VCT |
| Registering an alias the same plugin already holds replaces its command (mclo.gs registers each Brigadier subcommand under one meta); another plugin's alias throws | Supported | VCT (`/vre`, `reregister:true`), mclo.gs |
| An alias that starts with `/` or contains a space (LuckPerms adds `/lpv` so that `//lpv` works on Velocity) is accepted but not registered, with an info line in the log: Conduit's command line strips one slash and splits on spaces, so nobody could type it | Partial | VCT (`/vt`), LuckPerms |
| `executeAsync` as a player or the console: fires `CommandExecuteEvent` first, and returns false when there is no proxy command of that name | Partial: an unknown command is not forwarded to the backend | VCT |
| `executeImmediatelyAsync`, `offerSuggestions` | Supported | VCT |
| `offerBrigadierSuggestions`; `CommandMeta` hints | Unsupported (throws); hints are ignored | |
| When a command's `hasPermission` returns false, the player is told "You do not have permission"; Velocity would forward the command to the backend instead | Partial | |

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
| `LoginEvent`: denying it shows the reason on the client's disconnect screen, and no backend is ever contacted | Fired | VCT |
| `PostLoginEvent`: fired after the first backend is connected, whereas Velocity fires it before | Fired (partial) | VCT |
| `PlayerChooseInitialServerEvent`: `setInitialServer` works | Fired | VCT |
| `ServerPreConnectEvent`: can deny, or redirect with `allowed(otherServer)`; fired for the first connection and for switches. If every first server is denied, the connection is closed without a message. | Fired | VCT (deny, redirect), Maintenance |
| `ServerConnectedEvent` (with the previous server), `ServerPostConnectEvent` (switches only) | Fired | VCT (ServerConnectedEvent) |
| `DisconnectEvent` (`SUCCESSFUL_LOGIN` or `PRE_SERVER_JOIN`) | Fired | VCT |
| `PluginMessageEvent`: only for channels registered with the `ChannelRegistrar`, in both directions; `handled()` stops forwarding | Fired | VCT |
| `CommandExecuteEvent`: `denied()` works; `command(...)` and `forwardToServer(...)` are not honoured, and a warning is logged | Fired (partial) | VCT |
| `PlayerChatEvent`: pre-1.19 clients only (signed chat is relayed untouched); `denied()` works; rewriting the message is not honoured, and a warning is logged | Fired (partial) | VCT |
| `PermissionsSetupEvent`: see Permissions | Fired | VCT, LuckPerms |
| `ProxyPingEvent`: fired for every server-list request with a `ServerPing` built from Conduit's answer (after its `[status]` settings, maintenance and version gate), and the plugin's `ServerPing` is what the client is sent: description, version name and protocol, online and maximum counts, player sample, favicon. A denied result sends no answer and closes the connection. `getConnection()` gives the remote address, the dialled host (port 0: Conduit's event does not carry it), the protocol and the STATUS state. Formatting is kept as Conduit Text carries it, so hex colours are snapped to the nearest named colour. `nullPlayers()` (hiding the counts) and mod info are not honoured. | Fired (partial) | VCT (`ping:`), MiniMOTD, Maintenance |
| `KickedFromServerEvent`: fired when a backend kicks a player or refuses their login (`kickedDuringServerConnect()`), with Conduit's own default as the result: `DisconnectPlayer` with the backend's reason when kicked while playing, `Notify` with the reason when a switch or a first connection was refused. `DisconnectPlayer`, `Notify` and `RedirectPlayer` are all honoured. `Notify` outside a connect disconnects with its message, as the API says. `RedirectPlayer` without a message shows the kick reason once the player arrives, and with `Component.empty()` shows nothing. During a first connection a redirect names the next server to try, and its message is not shown because there is no chat yet. A result left as it was keeps the backend's reason exactly as the backend wrote it. | Fired | VCT (`kicked:`: redirect after a kick, Notify on a refused switch) |
| `ProxyReloadEvent`, `PreLoginEvent`, `GameProfileRequestEvent`, `TabCompleteEvent`, `PlayerAvailableCommandsEvent`, `PostCommandInvocationEvent`, `PlayerSettingsChangedEvent`, `PlayerClientBrandEvent`, `PlayerModInfoEvent`, `PlayerChannel(Un)RegisterEvent`, `ConnectionHandshakeEvent`, resource pack, cookie and configuration-phase events, `ServerLoginPluginMessageEvent`, `ServerRegisteredEvent`/`ServerUnregisteredEvent`, `Listener(Bound/Close)Event`, `ProxyPreShutdownEvent`, `ProxyQueryEvent`, `PreTransferEvent` | **Never fired.** A listener for one is still registered, but Conduit logs a warning naming the handler that will never be called. | VCT (`ProxyReloadEvent`), Maintenance |

## Scheduler, messaging, plugins, console

| API | Status | Verified by |
|---|---|---|
| `Scheduler.buildTask` (Runnable or Consumer), `delay`, `repeat`, `clearDelay`, `clearRepeat`, `schedule`, `ScheduledTask.cancel`, `status`, `tasksByPlugin`. Conduit's scheduler keeps the time, so tasks die with the plugin; bodies run on adapter threads, and a repeating task skips a run instead of overlapping itself. | Supported | VCT |
| `ChannelRegistrar.register/unregister` (`MinecraftChannelIdentifier`, `LegacyChannelIdentifier`) | Supported | VCT |
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
| [LuckPerms](https://modrinth.com/plugin/luckperms) (Luck) | 5.5.71 (Velocity) | MIT | `a3ac92ef4e29fd87a4d706dc92445fd946f1caf1d33526ca18422a62554fa18a` | **Works, with two gaps.** It downloads its libraries into its data folder, adds them with `addToClasspath`, creates its H2 storage and enables. Console and player `/lpv` commands work (info, user and group permission set/check/info, creategroup, parent add, sync), with formatted chat and click/hover. Its `PermissionsSetupEvent` handler loads each user and installs their function, and its `LoginEvent` check passes. Permissions it sets answer through Conduit: a player without `conduit.server` is refused Conduit's `/server` and allowed once it is set; wildcards (`luckperms.*`), group inheritance and a `world=<server>` context (following the player's server) all answer. Disabling it gives every player Conduit's default back. Gaps: its `/lpv`-with-a-slash aliases are not registered (see Commands), and its messages are always English, since `getPlayerSettings` has only the default locale. |
| [MiniMOTD](https://modrinth.com/plugin/minimotd) (jpenilla) | 2.2.5 | MIT | `b5d01ae7596b951f842d105d8a70552bf75761ced2bd413739d627c59b058ed5` | **Works.** It loads through the Guice `Injector` and child injector it asks for, and its MOTDs (chosen at random from its config), maximum player count and `/minimotd reload` all take effect on the server list. Its gradients arrive with hex colours snapped to the nearest named colour. |
| [Maintenance](https://modrinth.com/plugin/maintenance) (kennytv) | 5.1.0 | GPL-3.0 | `06f7d8fe346a5c35d1a2c38db1f12ea841bdc70df9496a20bf4c5accd972c8a5` | **Works for global maintenance.** `/maintenance` help, `on`/`off`, `status` and `add`, from the console and from a player. With maintenance on, the server list shows its MOTD, its "Maintenance" version text and its hover lines; a player without `maintenance.bypass` is refused at login with its kick message, and players online without it are kicked when it is turned on; run with LuckPerms, a player granted `maintenance.*` joins and runs its commands. It needs snakeyaml from the proxy (supplied). Its `ProxyReloadEvent` handler is never called. Per-server maintenance was not tried. |
| [Server Redirect](https://modrinth.com/plugin/server-redirect) (KaiKikuchi) | Plugin-1.4.3 | MIT | `276c4ddf054c91c81d0c0af5eb75e5222b80b08b439e0046e84c70b4770c0c19` | **Works.** `/redirect` and `/fallback` (one player or `*`) send its `srvredirect:red` / `srvredirect:fal` plugin messages to the client, from the console and from a player. A client that announces the mod on `srvredirect:ann` is recognised, and `/ifplayercanredirect` / `/ifplayercannotredirect` then run their command as the console. Its own event (`PlayerRedirectEvent`) goes through `EventManager.fire`. |
| [mclo.gs](https://modrinth.com/plugin/mclogs) (Aternos) | 3.3.3 (Velocity) | MIT | `61acac8f0a9a9aedbaa9ce1ce1dcd784bd305e58a10aabc8c0eec218a3f9ef53` | **Works for what was tried.** Its Brigadier commands `/mclogsp` and `/mclogsv` run from the console and a player, with its messages, click events and Brigadier syntax errors. It needed a plugin to be able to register its own alias again (now supported). Uploading a log to mclo.gs was not tried, as it sends the log to that service. |
| [velocity-hub](https://modrinth.com/plugin/velocity-hub-command) (stellarcielo) | 1.10-SNAPSHOT | MIT | `e7a348beb3ce74d36494e4d31fbb4df56f7f0a5f6314be417056f07fb4ad8f77` | **Works** (rechecked). It takes over `/hub` and `/lobby`, which send the player to the configured hub and detect "already connected". Its `ProxyInitializeEvent` and `PostLoginEvent` handlers run. It needs the plugin's own `Metrics.Factory` to be injected (supported). It writes its config to `plugins/velocity-hub` under the working directory rather than to `@DataDirectory`; that is the plugin's own choice. |

The two extra plugins were picked from Modrinth's most-downloaded Velocity plugins, skipping those
whose core is something the adapter does not support: Simple Voice Chat and Plasmo Voice (voice
chat), ViaVersion, ViaBackwards and ViaRewind (they hook Velocity's network pipeline; Conduit
translates with Via itself), SkinsRestorer (game profile properties), Geyser (Bedrock), TAB (tab
list), PacketEvents (packet injection) and Raknetify (transport).

## Build

```powershell
./scripts/test.ps1 -Only gg.tame.conduit.tests.VelocityCompatTests
```

# Velocity compatibility on Conduit

Conduit is not Velocity. This layer lets **some** plugins compiled against
`com.velocitypowered:velocity-api` run on Conduit by adapting to the native API.

Status of this tree: **PARTIAL — verified with a Velocity-API plugin compiled in-repo (Phase9)**.
Not verified with LuckPerms, ViaVersion, or other production Velocity plugins.

Build:

```powershell
./scripts/fetch-velocity-compat.ps1
./scripts/test.ps1
```

`slf4j-nop` is included so Velocity plugins that inject `org.slf4j.Logger` do not pull a real logging backend into tests.

| API | Status | Notes |
|---|---|---|
| `ProxyServer.getPlayer/getAllPlayers/getPlayerCount` | SUPPORTED | Native player index |
| `ProxyServer.getServer/getAllServers/matchServer` | SUPPORTED | Configured backends |
| `ProxyServer.registerServer/unregisterServer` | SUPPORTED | Native ServerManager |
| `ProxyServer.createRawRegisteredServer` | PARTIAL | Not in the live server map |
| `Player.getUsername/getUniqueId/isOnlineMode` | SUPPORTED | Authenticated profile |
| `Player.sendMessage(Component)` | PARTIAL | Plain-text Adventure serialize only |
| `Player.disconnect(Component)` | PARTIAL | Plain-text reason |
| `Player.createConnectionRequest` | SUPPORTED | Native `connect` |
| `Player.sendPluginMessage(ChannelIdentifier, byte[])` | SUPPORTED | Protocol adapter |
| `Player.getCurrentServer` | SUPPORTED | |
| `Player` tab list, resource packs, cookies, sounds, dialogs, settings | UNSUPPORTED | Throws |
| `RegisteredServer.getServerInfo/getPlayersConnected` | SUPPORTED | |
| `RegisteredServer.ping` | UNSUPPORTED | |
| `CommandManager` + `SimpleCommand` | SUPPORTED | Aliases, execute, suggest |
| `BrigadierCommand` | UNSUPPORTED | Needs a Conduit brigadier graph |
| `EventManager.register` + `@Subscribe` | SUPPORTED | Velocity annotation, not native |
| `ProxyInitializeEvent` / `ProxyShutdownEvent` | SUPPORTED | Bridged from native |
| `LoginEvent` / `PostLoginEvent` / `DisconnectEvent` | SUPPORTED | LoginEvent deny disconnects |
| `ServerPreConnectEvent` / `ServerConnectedEvent` | SUPPORTED | Cancel maps to native cancel |
| `EventTask` continuations | UNSUPPORTED | Handlers run synchronously |
| `Scheduler.buildTask` | SUPPORTED | Isolated pool, not socket threads |
| `PluginManager` / `PluginContainer` | PARTIAL | Load/list/disable; no `addToClasspath` |
| `ChannelRegistrar` | PARTIAL | In-memory register only |
| `ProxyConfig` | PARTIAL | Best-effort; no Velocity config file |
| `ConsoleCommandSource` | PARTIAL | Logs to stdout |
| Adventure rich text / MiniMessage | PARTIAL | Flattened to plain string |
| Scoreboard, BossBar, ResourcePackInfo | UNSUPPORTED | |
| Velocity internals (`com.velocitypowered.proxy`) | UNSUPPORTED | Never implemented |

Never faked: ping, brigadier, tab list, and resource packs throw `UnsupportedOperationException`.

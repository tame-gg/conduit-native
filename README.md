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

## Protocol

Supported version: **Minecraft 1.20.4 / protocol 765**.

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
  → authenticated UUID / username / properties
  → modern forwarding
```

Login Start UUID is **not** authoritative in online mode. Failures disconnect the client;
there is no silent fallback to offline identity. Mojang authentication is not repeated when
switching backends; the authenticated profile is reused.

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

`routing.initial` is tried in order after authentication. If the current backend socket dies,
`routing.fallback` is tried next, skipping the dead server and any server that already failed
in that incident (no reconnect loop). If none accept, the client is disconnected.

`/server` only lists configured names. Addresses, ports, and secrets are never shown.

## Commands

Native command framework (not Velocity's):

* `/server` — list backend names
* `/server <name>` — switch; exact match wins over prefix; ambiguous prefixes are rejected
* `/server` tab completion — configured names only
* `/conduit` — proxy version and current backend name

The Minecraft client stays connected to Conduit. Switching uses protocol 765 Start
Configuration → backend login + modern forwarding (hidden Login Success) → configuration →
Play, then the old backend is closed.

Already connected: `You are already connected to lobby.`
Failed switch: `Unable to connect to survival.` (existing backend kept)

## Server brand

Conduit intercepts only the `minecraft:brand` plugin message:

* `Paper` → `Paper (Conduit)`
* `Purpur` → `Purpur (Conduit)`
* `Paper (Conduit)` stays `Paper (Conduit)`
* missing brand → `Conduit`

## Modern forwarding

Verified against **Paper git-Paper-499 (MC: 1.20.4)**. Keep Paper `online-mode=false`,
Velocity modern forwarding enabled, a matching secret, and
`network-compression-threshold=-1`.

## Real vanilla client

Conduit has been validated with a real vanilla Minecraft 1.20.4 client using online-mode
authentication and modern forwarding to Paper 1.20.4-499 (Play, chat, authenticated UUID).

Real vanilla `/server` switching between two Paper processes was **not** repeated in this
phase. Multi-backend switching is covered by mock-backend integration tests
(lobby ⇄ survival, client TCP remains open; failed switch keeps lobby).

## Testing notes

`./scripts/test.ps1` covers encryption, mocked hasJoined, online-mode identity substitution,
command dispatch, brand rewriting, routing matches, and two-backend switch/fallback mocks.

## Out of scope here

Velocity plugins, native plugin API, legacy/BungeeGuard forwarding, multi-version support,
and full compression.

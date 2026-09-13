# Conduit

Conduit is an independently implemented Minecraft: Java Edition proxy foundation. It is not a
Velocity or Velocity-CTD fork, and it has no dependency on either implementation.

The legacy `tame-gg/conduit` checkout is intentionally separate and untouched.

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
there is no silent fallback to offline identity.

Session URL must be HTTPS except for loopback HTTP used by tests.

### Offline

Login Start identity is forwarded as an **unauthenticated** profile. Do not treat it as
Mojang-verified.

## Encryption

Client↔Conduit traffic uses Minecraft protocol encryption after a successful handshake.
Backend↔Conduit remains plaintext (Paper stays offline and trusts modern forwarding).

Private keys, shared secrets, and session bodies are not logged.

## Modern forwarding

Verified against **Paper git-Paper-499 (MC: 1.20.4)**. Keep Paper `online-mode=false`,
Velocity modern forwarding enabled, a matching secret, and
`network-compression-threshold=-1`.

## Real vanilla client

Conduit has been validated with a real vanilla Minecraft 1.20.4 client using online-mode
authentication and modern forwarding to Paper 1.20.4-499:

* official 1.20.4 `client.jar` (protocol 765)
* Encryption Request → Encryption Response → AES/CFB8
* Mojang `hasJoined` succeeded
* Paper received the Mojang-authenticated UUID/username (not the Login Start UUID)
* the client reached Play, joined the world, and sent chat

## Testing notes

`./scripts/test.ps1` covers encryption, server-hash known values, mocked hasJoined, and
online-mode identity substitution without a live Mojang account.

## Out of scope here

Plugins, commands, legacy/BungeeGuard forwarding, multi-version support, and full
compression.

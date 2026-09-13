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

Verified against **Paper git-Paper-499 (MC: 1.20.4)** with a protocol-765 probe (not the
Mojang launcher GUI): Login Plugin `velocity:player_info` version 4, HMAC payload, Login
Success, Configuration, Play. Paper logged the forwarded test UUID.

Keep Paper `online-mode=false`, Velocity modern forwarding enabled, shared secret, and
`network-compression-threshold=-1`.

## Testing notes

Automated tests cover encryption, server-hash known values, mocked hasJoined, and
online-mode → forwarding identity substitution. They do **not** use a real Mojang account
or the official vanilla client. Vanilla-client online-mode compatibility is therefore
**not** claimed.

## Out of scope here

Plugins, commands, legacy/BungeeGuard forwarding, multi-version support, and full
compression.

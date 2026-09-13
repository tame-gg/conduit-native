# Conduit

Conduit is an independently implemented Minecraft: Java Edition proxy foundation. It is not a
Velocity or Velocity-CTD fork, and it has no dependency on either implementation.

This initial milestone provides a native `gg.tame.conduit` namespace, validated configuration,
forwarding-secret handling with redacted diagnostics, an explicit connection state machine, a
bounded Minecraft-style VarInt frame decoder, a small NIO listener, and dependency-free tests.

The legacy `tame-gg/conduit` checkout is intentionally separate and untouched.

## Build and test

Requires a JDK capable of compiling Java 21 source. On Windows:

```powershell
./scripts/test.ps1
./scripts/run.ps1 -ConfigPath run/conduit.toml
```

`--check-config <path>` validates a configuration without binding a listener.

## Current scope

This is a protocol-aware MVP for protocol 765 (Minecraft 1.20.4). It supports native backend
selection, Login Start validation, local status/ping, and a live modern-forwarding Login Plugin
Request/Response exchange on the backend connection.

Modern forwarding:

* Channel: `velocity:player_info`
* Request: one unsigned version byte (only version `1` is implemented)
* Response: Minecraft Login Plugin Response (id `0x02`) with the matching message id, success flag,
  32-byte HMAC-SHA-256, then the signed forwarding body (version, IP, UUID, username, properties)

The mock client → Conduit → mock backend fixture verifies those wire bytes, including HMAC.

After Login Success, Conduit switches to CONFIGURATION and then byte-relays. Finish Configuration
(id `0x02` in protocol 765) is recognized for LOGIN → CONFIGURATION → PLAY tests. Packets that do
not need proxy interpretation are relayed once that login-plugin exchange has completed.

## Authentication boundary

Conduit does **not** perform Minecraft online-mode authentication. Login Start identity is treated as
an unverified proxy profile. Modern forwarding authenticates the *forwarding payload* to the backend
with a shared secret; that is not the same as Mojang session authentication. If a backend sends an
Encryption Request, the connection is closed rather than silently continuing.

Set Compression during login is understood so a later compressed Login Success can still be
identified. Prefer `network-compression-threshold=-1` on backends until a broader compression path
is tested.

## Paper

Real Paper interoperability is **not** claimed in this milestone. No Paper server was exercised in
the accompanying test run. A Paper backend would need `online-mode=false`, Velocity modern forwarding
enabled with the same secret Conduit uses, and protocol 765 clients. Only forwarding format version 1
is implemented; a backend that requests version 2+ is rejected (fail closed, no legacy downgrade).

## Explicitly out of scope here

Legacy/BungeeGuard forwarding, client encryption/online-mode, plugins, commands, and extra protocol
versions.

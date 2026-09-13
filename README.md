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
* Request: one unsigned version byte. Paper 1.20.4 sends `4` (lazy session). Conduit accepts
  versions `1` through `4` and echoes that version in the signed body. Version `5+` is rejected
  (fail closed, no legacy downgrade).
* Message IDs are signed VarInts and may be negative; Paper uses `ThreadLocalRandom.nextInt()`.
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

## Paper 1.20.4 interoperability

Demonstrated against official **Paper git-Paper-499 (MC: 1.20.4)**, protocol 765, Java 21:

* Paper Velocity modern forwarding enabled, shared secret, `online-mode=false`,
  `network-compression-threshold=-1`, listen `127.0.0.1` only
* Conduit `forwarding.mode = modern` with the same secret
* A protocol-765 login probe (not the Mojang launcher GUI) completed:

  Login Start → Paper Login Plugin Request (`velocity:player_info`, version byte `4`) →
  Conduit HMAC response → Paper Login Success with the forwarded UUID/username →
  Configuration Finish → Play Login (`0x29`). Paper logged the forwarded UUID and
  “Player left the game” after the probe disconnected.

Versions 2–3 include optional chat-key material in the Velocity spec. Paper 1.20.4’s login
handler accepts 1–4 and reads address/profile immediately after the version VarInt; Conduit
echoes the requested version with the version-1 field layout (no extra key block). Version 5+
is still rejected.

## Explicitly out of scope here

Legacy/BungeeGuard forwarding, client encryption/online-mode, plugins, commands, and extra protocol
versions.

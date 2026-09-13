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

This is a foundation, not yet a complete Minecraft proxy. It does not yet provide backend routing,
login, server switching, forwarding serialization, a plugin runtime, or Velocity compatibility.

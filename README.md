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

This is a protocol-aware MVP, not yet a complete Minecraft proxy. It supports protocol 765
(Minecraft 1.20.4), native backend selection, Login Start validation, transparent post-login relay,
and local status/ping. It does not authenticate clients itself, inspect encrypted/compressed traffic,
or yet run the modern-forwarding login-plugin exchange against Paper. Modern payload generation is
implemented and HMAC-protected, but live modern mode fails safely until that adapter has an
interoperability fixture. Legacy and BungeeGuard are explicitly unsupported.

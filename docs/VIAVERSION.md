# ViaVersion ecosystem integration

Conduit remains an independently implemented Minecraft proxy (`koels` / Conduit).
Cross-version packet translation can be provided by the ViaVersion ecosystem as
**third-party** libraries. Conduit owns only its platform adapter and proxy code.

## Enable

```toml
[translation]
enabled = true
engine = "via-preferred"   # via-preferred | via | native
via-backwards = true
via-rewind = true          # needed for 1.7.x / 1.8.x clients
via-legacy = true          # needed for backends ≤ 1.7.10 (incl. 1.7.6)
data-folder = "via"
```

Default is **disabled** so existing native paths stay unchanged until operators opt in.

## Roles

| Component | Role |
|---|---|
| ViaVersion 5.11.0 | Newer clients → older backends |
| ViaBackwards 5.11.0 | Older clients → newer backends |
| ViaRewind 4.1.3 | 1.7.x / 1.8.x clients on 1.9+ |
| ViaLegacy 3.0.16 | Clients → backends ≤ 1.7.10 (opt-in; needs extra deps) |

ViaLegacy is **off by default**. Enable `translation.via-legacy=true` only when
proxying to ≤1.7.10 backends, and ensure ViaLegacy's transitive libraries
(e.g. MinecraftBiome / seedfinding) are on the classpath.

## Architecture

Conduit does not use Netty for proxy I/O. Via is integrated through
`ProtocolTranslator` using Via’s public `UserConnection.transform*` APIs.

```
client AES? → framing → ProtocolTranslator (Via or native or identity)
                              ↓
                     backend framing → zlib? → backend
```

Same-version connections stay **DIRECT** (`IdentityTranslator`).

## Protocol ceiling (Via 5.11.0)

- Floor of interest: Minecraft **1.7.6** (protocol 5)
- Via register tops out at **26.2** (protocol 776)
- **26.3 is not supported** by this Via release — do not claim it

## Modes

| Mode | Meaning |
|---|---|
| DIRECT | `clientProtocol == backendProtocol` |
| TRANSLATED | Via path and/or native translator |
| UNSUPPORTED | neither path exists |

## Licensing

ViaVersion / ViaBackwards / ViaRewind common artifacts are **GPLv3**.
ViaLegacy follows its upstream license. Distributing a Conduit build that links
these artifacts requires complying with those licenses (including source
availability for GPLv3-covered combined works as applicable).

Upstream projects: https://github.com/ViaVersion

Conduit integration code under `gg.tame.conduit.viaversion` is independently
authored and is **not** a copy of Velocity, BungeeCord, ViaLoader, or Via internals.

## Build

```powershell
./scripts/fetch-via.ps1
./scripts/test.ps1
```

See also `docs/VIAVERSION_INTEGRATION_PLAN.md` and `docs/COMPATIBILITY.md`.

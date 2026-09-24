# Conduit × ViaVersion integration plan

Organization: **koels**. Project: independently implemented Conduit proxy
(`gg.tame.conduit`). Via* projects remain third-party dependencies.

## Selected ecosystem versions

| Component | Artifact | Version | Role |
|---|---|---|---|
| ViaVersion | `com.viaversion:viaversion-common` (+ api) | **5.11.0** | Newer client → older backend |
| ViaBackwards | `com.viaversion:viabackwards-common` | **5.11.0** | Older client → newer backend |
| ViaRewind | `com.viaversion:viarewind-common` | **4.1.3** | **Required** for 1.7.x / 1.8.x clients on 1.9+ |
| ViaLegacy | `net.raphimc:ViaLegacy` | **3.0.16** | **Required** only when backends are ≤1.7.10 (incl. 1.7.6) |

Upstream artifacts are sufficient. **No ViaVersion fork** unless a later blocker appears.

### Protocol ceiling (verified against ViaVersion 5.11.0)

- Lowest target of interest: **1.7.6** (`ProtocolVersion.v1_7_6`, protocol **5**)
- Highest registered in this Via release: **26.2** (protocol **776**)
- **Minecraft 26.3 is NOT registered** in ViaVersion 5.11.0 (`ProtocolVersion.isRegistered` for a 26.3 id is false; `getClosest("26.3")` is null). Treat 26.3 pairs as **UNSUPPORTED** until a Via release adds them.

## Conduit architecture facts (integration constraints)

Conduit Independent does **not** use Netty for its proxy I/O. It uses:

- blocking sockets before Play (virtual threads; platform threads on Windows, JDK-8334574), then an NIO selector relay (`network/ConnectionSelector`) for playing sessions
- `PacketTransport` (client) with optional AES/CFB8 **outside** packet bodies
- `MinecraftFrames` VarInt framing
- backend-only zlib compression in `BackendConnection` / `PacketCompression`
- `ProtocolTranslator` operating on already framed, decompressed `byte[]` packets

Therefore Via is **not** injected into a Conduit Netty pipeline. Translation hooks into
`ProtocolTranslator` via Via’s public `UserConnection.transformServerbound` /
`transformClientbound` APIs. Netty is only a Via runtime dependency (`ByteBuf`,
optional `EmbeddedChannel` for extra-packet capture).

Encryption and compression stay where they are today:

```
client TCP → [AES] → framing → (decrypted body) → Via transform? → backend framing → [zlib] → backend TCP
```

Via always sees plaintext, uncompressed packet bodies.

## Required Via platform contracts (5.11.0)

Independently authored Conduit types:

| Via contract | Conduit type | Binding |
|---|---|---|
| `UserConnectionViaVersionPlatform` | `ConduitViaPlatform` | proxy=`true`, data folder, logger, name/version |
| `ViaPlatformLoader` | `ConduitViaPlatformLoader` | registers `VersionProvider` |
| `VersionProvider` | `ConduitViaVersionProvider` | returns per-connection **backend** protocol |
| `ViaInjector` | `NoopInjector` (upstream) | no Netty injection into Conduit I/O |
| `ViaCommandHandler` | upstream | optional; updates toggle only |
| `UserConnection` + `ProtocolPipelineImpl` | `ConduitViaSession` | one session per translated player |
| ViaBackwards / ViaRewind / ViaLegacy `*Platform` | thin Conduit wrappers calling public `init(File)` | load add-ons after Via bootstrap |

## DIRECT vs TRANSLATED selection

Preserve independent `clientProtocol` and `backendProtocol`.

```
if client == backend:
    DIRECT   (IdentityTranslator; Via not engaged)
else if Via enabled and ProtocolManager.getProtocolPath(client, backend) != null:
    TRANSLATED via ViaProtocolTranslator
else if native TranslatorRegistry has the ordered pair:
    TRANSLATED via native translator (fallback / experimental)
else:
    UNSUPPORTED
```

Config key (proposed): `translation.engine = via-preferred | via | native`
(default `via-preferred`).

## Server switching

On backend change:

1. Keep client protocol stable.
2. Tear down / replace `ConduitViaSession` for the new backend protocol.
3. Re-run handshake priming against Via so the protocol path matches the new backend.
4. Do not leave prior entity trackers / storables attached (`clearStoredObjects` / new connection).

## What Conduit authors vs consumes

**Newly authored by Conduit:** platform adapter, session bridge, translator,
compatibility selection, config, diagnostics, docs, tests.

**Consumed as dependencies:** ViaVersion, ViaBackwards, ViaRewind, ViaLegacy,
Netty, Guava, Fastutil (as required by those artifacts).

**Not copied:** Velocity/Bungee/Paper/Via internals, mappings, or handler bodies.

## First verification pairs

| Pair | Expected | Priority |
|---|---|---|
| 765 → 765 | DIRECT | smoke |
| 393 → 765 | TRANSLATED (Via+Backwards) | primary |
| 765 → 393 | TRANSLATED (Via) | primary |
| 776 → 776 | DIRECT | smoke |
| 5 (1.7.6) → 765 | TRANSLATED (Rewind+Backwards) | later / UNVERIFIED until real client |
| modern → 5 | TRANSLATED (ViaLegacy) | later / UNVERIFIED |
| * → 26.3 / 26.3 → * | UNSUPPORTED on Via 5.11.0 | document only |

## Where this actually stands

The plan above is the design. What a real 1.13 client and a real 1.20.4 server
found when it was first exercised end to end, and what had to change, is
recorded in `docs/VALIDATION_VIA_393_765.md`. In short:
seven faults in Conduit's integration were found and fixed, in the order a real
client hits them — Via never being shut down, the native configuration absorber
starving Via of registry data, Via's backend state never leaving Login, Via's
injector being the no-op one so every packet Via generated died in a null
lookup, those packets then arriving several packets late, a duplicated Login
Acknowledged, and Conduit's native compensation layer rewriting Via's output.

An eighth fault followed — Conduit overwriting Via's connection state after
login — and after that both directions of the pair reach the world and play.

The last thing in the way was not Conduit's: ViaBackwards 5.11.0 encodes five
recipes in a way a 1.13 client cannot read, and the session ended on the first
of them. There is no fixed release to upgrade to, so Conduit validates the
recipe list at its own socket write and drops only the recipes that cannot be
represented. Nothing was reimplemented from Via and no fork was made.

**393 ↔ 765 is now VERIFIED in both directions with real clients and real
servers.** Steps not yet reached: completing a server switch under Via, 1.7.6
through ViaRewind, any decision about whether ViaLegacy is required, and
performance measurement. None of those should be claimed until they have been
run against real endpoints.

## Licensing

See `docs/LICENSING_VIA.md`: the Via artifacts Conduit uses, their licenses,
and how a Conduit distribution meets them. Conduit is GPL-3.0-or-later.

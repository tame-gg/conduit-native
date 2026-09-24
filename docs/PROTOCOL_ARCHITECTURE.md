# Conduit multi-version protocol architecture

Scope: Minecraft 1.13 (protocol 393) through 26.3 (protocol 777). 1.12.2 and
older are deliberately postponed.

## Attribution

BungeeCord and Velocity were studied as architectural references for how a
multi-version Minecraft proxy separates client and backend protocol state, keeps
a protocol registry maintainable across many revisions, and decides when a
packet must be understood versus merely relayed.

**Conduit's implementation is independently authored.** No code, class
structure, package layout, test, or data file was copied from either project,
and Conduit does not depend on either at build or run time. What was taken is
the shape of the engineering problem, not a solution to it.

Packet-id data comes from public published sources — Mojang's official version
manifest for releases and artifacts, and PrismarineJS `minecraft-data` for
packet-id tables. Both are data, not implementation.

## The three paths

The proxy decides using **both** protocol numbers, never the backend's alone:

| Condition | Path |
| --- | --- |
| `clientProtocol == backendProtocol` | `DIRECT` |
| ordered pair has a registered translator | `TRANSLATED` |
| otherwise | `UNSUPPORTED` |

`UNSUPPORTED` is a legitimate state, not a bug. It is how the proxy fails closed
instead of forwarding bytes whose layout it cannot vouch for.

## Packet identity

`PacketKind` is the version-independent identity. Numeric ids belong to a
protocol version and live only in that version's `ProtocolDefinition`.

`SET_HEALTH` is `0x44` on 393 and `0x5B` on 765 — which is exactly why no part
of the system may compute a target id from a source id. There is no
`targetId = sourceId + offset` anywhere, and adding one would be a defect
regardless of whether it happened to work for some pair.

## Scaling the registry: declared tables plus deltas

The original design gave every protocol a full positional packet table. At five
protocols that was ~28 KB of source; at 38 it would have been a few hundred KB of
hand-transcribed, largely duplicated data, where a single mistyped hex digit
silently corrupts a stream. Handshake, status and login rows are identical across
almost the whole range.

So a protocol now enters the registry one of two ways:

- **Declared** (`ProtocolDefinition.define`) — spells out its whole table.
  Appropriate for a version with a genuinely novel layout. Conduit declares 393,
  763, 765, 766, 776. 777 (26.3) is a delta on 776 kept beside it in
  `ProtocolDefinition` rather than in the generated `ProtocolRevisions`: its ids
  come from Mojang's own packet report (`server.jar --reports`), which is also
  what every id in the 776 table was checked against.
- **Derived** (`ProtocolDefinition.derive` + `ProtocolRevision`) — inherits an
  existing table and applies only the mappings that release changed, including
  explicit removals via `PacketMapping.removed`. 32 protocols are derived.

The cost of adding a version is now proportional to what the version actually
changed. Measured deltas bear this out: 1.14 moved 42 mappings from 1.13.2, while
1.13.1, 1.13.2, 1.14.1, 1.21.8 and 1.21.11 moved **zero** — their published id
tables are byte-identical to their predecessors.

Derivation is a chain, so registration order matters; `ProtocolRevisions.ALL` is
ordered and `buildRegistry` fails loudly on an unregistered base or a duplicate.

### Why not fold revisions blindly

An earlier sketch had one lineage where every revision folded onto the last. That
is wrong here: Conduit's declared tables cover different subsets (763 covers 21
packets, 765 covers 80), so folding would have leaked 1.13-era play ids into
1.20.1's table and called the result support. Each derived version therefore
names its base explicitly, and a version with no published data to derive from —
1.14.2 — is simply left out rather than given a neighbour's table.

## Status is four axes, not one flag

`CodecStatus` (NONE/DERIVED/DECLARED/VERIFIED), `TranslationSupport`,
`CompatibilityCompleteness`, and catalog membership are separate and must stay
separate. A protocol can have a complete codec and no translator to anything; a
protocol can have an authored table no real client has ever exercised.

Concretely: `CompatibilityRegistry` downgrades a same-version `DIRECT` path to
`PARTIAL` when the codec is only `DERIVED`, so the 32 derived protocols do not
advertise themselves as fully validated. Exactly one protocol is `VERIFIED`
(393), and only because a real client and a real server exercised it.

## One registry for translators

Translatable pairs were previously written out twice — once to decide
`TranslationSupport`, once to pick the translator instance — which let the two
disagree and let Conduit advertise a pair it could not carry.

`TranslatorRegistry` is now the single source of truth. It is keyed by the
**ordered** pair `(clientProtocol, backendProtocol)` and never mirrors a
registration, because the two directions are genuinely different problems:

| | 393 → 765 | 765 → 393 |
| --- | --- | --- |
| Configuration state | must be synthesised/absorbed for a client that has none | must be suppressed toward a backend that has none |
| Join Game fields | fields the source lacks must be invented | fields the target lacks must be dropped |
| Block states | 393 ids → 765 ids | 765 ids → 393 ids, non-injective |
| Chat | legacy chat → system chat | system chat → legacy chat, signing discarded |

## Semantic translation

```
source wire → source codec → semantic representation → translator → target codec → target wire
```

Translation operates on meaning. Identical packet names do not imply identical
schemas, and packets are not relayed byte-for-byte across versions whose layouts
differ.

## DIRECT stays cheap

A codec existing for a protocol is not a reason to decode every packet through
it. On a `DIRECT` path Conduit forwards bytes transparently wherever inspection
is unnecessary; only packets it must inspect, rewrite, route, authenticate or
translate get semantic handling.

## Fail closed, but decide first

When a packet cannot be relayed as-is, the choice is `TRANSLATE`, `SYNTHESIZE`,
`DROP`, or `UNSUPPORTED` — decided per packet. Dropping everything whose schema
differs is as wrong as forwarding it blindly.

## Tooling

| Tool | Job |
| --- | --- |
| `tools/export_protocols.py` | exports release → protocol from `ProtocolVersion.java`, the single catalog source |
| `tools/packetids.py check` | cross-checks hand-authored tables against published data |
| `tools/gen_revisions.py` | regenerates `ProtocolRevisions.java` deltas |
| `tools/mcartifacts.py` | mirrors and verifies official Minecraft jars |

`packetids.py check` is the important one: it confirmed all 198 hand-authored
packet ids across 1.13, 1.20.1, 1.20.4 and 1.20.5 agree exactly with published
data — zero mismatches. It prints data for review and never writes Java, because
a wrong packet id is a silently corrupted stream rather than a test failure.

## Outstanding

- 393 is the only `VERIFIED` protocol: the official 1.13 client sustained real
  play against the official 1.13 server through Conduit
  (`work/real-client-validation/RESULTS-393-NATIVE.md`). Every other protocol is
  `DECLARED` or `DERIVED` and awaits its own wire test.
- 393 ↔ 765 remains `PARTIAL` in both directions and continues as the
  cross-version track. It is not evidence of native 393 support.
- Per-release `ProtocolCapabilities` auditing is outstanding for all 32 derived
  protocols; they currently inherit their base's.
- 485 (1.14.2) has no codec; 776 (26.2), 777 (26.3) and 763 (1.20.1) have thin
  tables: only the packets Conduit reads or writes itself.
- 26.3 is native only for a 26.3 client on a 26.3 backend. Every other pair
  involving 777 goes through ViaVersion (5.12.0 registers 26.3); Conduit has no
  native 776 &harr; 777 translator and is not meant to.

# Conduit compatibility matrix (Minecraft 1.13 - 26.2)

Generated from the live registries, not hand-maintained: every row is what
`CompatibilityRegistry.resolve(client, backend)` would tell a real session.
Regenerate after any protocol change. 1.12.2 and older are deliberately out of
scope for this program's *native* codecs.

## ViaVersion ecosystem (optional)

When `[translation] enabled = true`, Conduit prefers the ViaVersion graph for
TRANSLATED pairs (see `docs/VIAVERSION.md`). Native translators remain as
fallback/experimental.

| Client → Backend | Mode | Verification |
|---|---|---|
| same protocol (codec present) | DIRECT | VERIFIED / codec-dependent |
| 393 → 765 (Via enabled) | TRANSLATED | UNVERIFIED — real 1.13 client joins a real 1.20.4 world through Via, then disconnects on Via's Declare Recipes downgrade |
| 765 → 393 (Via enabled) | TRANSLATED | UNVERIFIED — no real-endpoint run yet |
| 1.7.6 (5) → modern (Via+Rewind) | TRANSLATED | UNVERIFIED — no real-client run yet |
| modern → 1.7.6 (Via+Legacy) | TRANSLATED | UNVERIFIED — ViaLegacy not loaded; not shown to be required |
| * → 26.3 / 26.3 → * on Via 5.11.0 | UNSUPPORTED | Via does not register 26.3 |

Do not read Via dependency presence as VERIFIED gameplay. The 393 → 765 Via run
above reaches the world and translates in both structural directions with zero
translation failures reported by Conduit, and is still UNVERIFIED: no gameplay
was exercised, and the session ends on a malformed packet. Details, including
the exact bytes and why the fault sits outside Conduit, are in
`docs/VALIDATION_VIA_393_765.md`.

The native 393 ↔ 765 path is unaffected by the Via work and was re-checked with
a real 1.13 client against a real 1.20.4 server after it: in-world, rendering,
command tree intact.

## What the statuses mean

Four independent axes. Collapsing them into one "supported" flag is how a proxy
ends up advertising a path it cannot carry, so they are kept apart.

- **Catalog entry** - Conduit knows the protocol number and release names. Says
  nothing about whether it can speak it.
- **Codec status** - how well Conduit knows that protocol's wire format.
  - `DECLARED` - packet table authored directly for this protocol.
  - `DERIVED` - inherits a declared table plus this release's published delta.
    Usable; this exact protocol number has not been audited packet by packet and
    its capabilities are inherited rather than confirmed.
  - `VERIFIED` - authored *and* exercised against a real client/server of that
    version. Only a real wire test promotes a protocol here. **393 (1.13) is
    currently the only VERIFIED protocol** - see
    `work/real-client-validation/RESULTS-393-NATIVE.md`.
- **Translation support** - `DIRECT` (same version), `TRANSLATED` (a translator
  is registered for that *ordered* pair), `UNSUPPORTED`.
- **Completeness** - `FULL` or `PARTIAL`. A `DIRECT` path over a `DERIVED` codec
  is `PARTIAL`, because nothing has validated that table on the wire.
- **Validation** (`ValidationStatus`) - how far the *pairing* has been proven,
  independent of the above: `CODEC_PARTIAL`, `CODEC_COMPLETE`,
  `DIRECT_VERIFIED`, `TRANSLATED_PARTIAL`, `TRANSLATED_VERIFIED`. A passing unit
  test proves the codec, not the pairing; only a real client of the source
  version reaching a real server of the target version promotes a pair to
  VERIFIED.

Translation is never mirrored. A registered 393 -> 765 translator says nothing
about 765 -> 393; the two directions face different problems (fields to
synthesise one way and discard the other, a configuration state that exists on
one side only, non-injective block-state and metadata id maps) and are
registered independently.

## Current state, honestly

The codec foundation covers 37 of the 38 distinct protocol numbers in the
1.13-26.2 range. Translation coverage: **6 ordered pairs**. The matrix is still
mostly `UNSUPPORTED`, and that is the accurate picture.

**393 ↔ 765 is verified in BOTH directions with real clients and real servers.**
See `docs/VALIDATION_393_765.md` / `work/real-client-validation/RESULTS-393-765-CROSS.md`.

**393 ↔ 404 is verified in BOTH directions** (plus DIRECT 404↔404) with official
Mojang jars and scripted protocol clients. The only gameplay-critical delta is
Slot wire form; see `docs/DELTA_393_404.md` and `docs/VALIDATION_393_404.md`.
Completeness stays `PARTIAL`: recipes/advancements/trades that embed Slot are
dropped rather than rematerialised.

**404 ↔ 477 is script-verified in BOTH directions** (plus DIRECT 477↔477) with
official Mojang jars and scripted protocol clients. Core deltas: Join Game/
Respawn, Open Window menu ids, chunk heightmaps + Update Light, Position packing,
Block Place, and 1.14's per-section block count.

Block states, items and entity types are **semantic**, resolved by identifier and
property set from the registries the official jars emit. They are not numeric
passthrough, which would have been wrong for the large majority of each table:
only 748 of 8599 block states, 108 of 790 items and 6 of 95 entity types keep
their meaning across this pair.

Additionally, **404 → 477 is real-client core-gameplay verified**: a real 1.13.2
client held a real 1.14 server through a full gameplay burst, 51,175 packets, no
translation or decoder faults.

**477 → 404 remains scripted-probe verified.** The arrow crash that blocked it is
fixed — 1.14 had changed Spawn Object's type from the legacy object enumeration
to the entity registry id, so a dropped item arrived as an arrow — and entity
metadata is now aligned against measured per-entity layouts. Every field a 477
client receives through Conduit was compared against what a real 1.14 server
sends for the same entity, across 86 entity types, with zero mismatches. It is
not upgraded further only because the real 1.14.0 client cannot render in this
environment even with no proxy in the path; see `docs/METADATA_404_477.md`.

See `docs/DELTA_404_477.md`, `docs/VALIDATION_404_477.md` and
`work/real-client-validation/RESULTS-477-REALCLIENT.md`. Completeness stays
`PARTIAL`: per-entity-class metadata is unmodelled; recipes/tags/advancements
dropped.

765 ↔ 766 remains `TRANSLATED_PARTIAL`: a translator exists and unit tests
pass, but no real cross-version run has been done.

Known gaps:

- **Protocol 485 (1.14.2)** has no codec - no published packet data to derive
  from. It stays a catalog entry.
- **Protocol 776 (26.2)** and **763 (1.20.1)** have thin declared tables (38 and
  21 packets). 26.2 has no published packet data yet, so it cannot be enriched
  by derivation the way the rest of the range was.
- **393 and 404 have verified pairings.** Codec status for 404 remains
  `DERIVED` (inherited packet ids); the *pairing* axes are
  `DIRECT_VERIFIED` / `TRANSLATED_VERIFIED`.
- **Entity type ids are copied numerically** across the 393 ↔ 765 pair. The
  entity registry gained entries between the versions, so a mob can render as
  the wrong model while sitting at the correct position with correct motion.
  Fixable with a type-name mapping.
- Derived versions inherit their base's `ProtocolCapabilities`. Per-release
  capability auditing is outstanding for all 32 derived protocols.

## Protocols

| Protocol | Release | Family | Codec | Packets | Config state | Direct | Source |
| ---: | --- | --- | --- | ---: | --- | --- | --- |
| 393 | 1.13 | V1_13 | VERIFIED | 55 | no | DIRECT/FULL | authored from published 1.13 packet ids; exercised end-to-end by the official Minecraft 1.13 client against the official 1.13 server through Conduit (login, chunks, movement, combat, death, respawn, advancements; ~3 minutes, no disconnect) |
| 401 | 1.13.1 | V1_13 | DERIVED | 55 | no | DIRECT/PARTIAL | published packet ids for 1.13.1; capabilities inherited from 1.13 (derived from 1.13) |
| 404 | 1.13.2 | V1_13 | DERIVED | 55 | no | DIRECT/FULL | published packet ids for 1.13.2; capabilities inherited from 1.13.1 (derived from 1.13.1); DIRECT pairing verified — see VALIDATION_393_404.md |
| 477 | 1.14 | V1_14 | DERIVED | 64+ | no | DIRECT/PARTIAL | published packet ids for 1.14; DIRECT pairing script-verified; a real 1.14 client crashes rendering even on a byte-identical passthrough stream, so DIRECT rests on the probe — see VALIDATION_404_477.md |
| 480 | 1.14.1 | V1_14 | DERIVED | 64 | no | DIRECT/PARTIAL | published packet ids for 1.14.1; capabilities inherited from 1.14 (derived from 1.14) |
| 490 | 1.14.3 | V1_14 | DERIVED | 64 | no | DIRECT/PARTIAL | published packet ids for 1.14.3; capabilities inherited from 1.14.1 (derived from 1.14.1) |
| 498 | 1.14.4 | V1_14 | DERIVED | 64 | no | DIRECT/PARTIAL | published packet ids for 1.14.4; capabilities inherited from 1.14.3 (derived from 1.14.3) |
| 573 | 1.15 | V1_15 | DERIVED | 64 | no | DIRECT/PARTIAL | published packet ids for 1.15; capabilities inherited from 1.14.4 (derived from 1.14.4) |
| 575 | 1.15.1 | V1_15 | DERIVED | 64 | no | DIRECT/PARTIAL | published packet ids for 1.15.1; capabilities inherited from 1.15 (derived from 1.15) |
| 578 | 1.15.2 | V1_15 | DERIVED | 64 | no | DIRECT/PARTIAL | published packet ids for 1.15.2; capabilities inherited from 1.15.1 (derived from 1.15.1) |
| 735 | 1.16 | V1_16 | DERIVED | 64 | no | DIRECT/PARTIAL | published packet ids for 1.16; capabilities inherited from 1.15.2 (derived from 1.15.2) |
| 736 | 1.16.1 | V1_16 | DERIVED | 64 | no | DIRECT/PARTIAL | published packet ids for 1.16.1; capabilities inherited from 1.16 (derived from 1.16) |
| 751 | 1.16.2 | V1_16 | DERIVED | 64 | no | DIRECT/PARTIAL | published packet ids for 1.16.2; capabilities inherited from 1.16.1 (derived from 1.16.1) |
| 753 | 1.16.3 | V1_16 | DERIVED | 64 | no | DIRECT/PARTIAL | published packet ids for 1.16.3; capabilities inherited from 1.16.2 (derived from 1.16.2) |
| 754 | 1.16.5 | V1_16 | DERIVED | 64 | no | DIRECT/PARTIAL | published packet ids for 1.16.5; capabilities inherited from 1.16.3 (derived from 1.16.3) |
| 755 | 1.17 | V1_17 | DERIVED | 63 | no | DIRECT/PARTIAL | published packet ids for 1.17; capabilities inherited from 1.16.5 (derived from 1.16.5) |
| 756 | 1.17.1 | V1_17 | DERIVED | 64 | no | DIRECT/PARTIAL | published packet ids for 1.17.1; capabilities inherited from 1.17 (derived from 1.17) |
| 757 | 1.18 | V1_18 | DERIVED | 65 | no | DIRECT/PARTIAL | published packet ids for 1.18; capabilities inherited from 1.17.1 (derived from 1.17.1) |
| 758 | 1.18.2 | V1_18 | DERIVED | 65 | no | DIRECT/PARTIAL | published packet ids for 1.18.2; capabilities inherited from 1.18 (derived from 1.18) |
| 759 | 1.19 | V1_19 | DERIVED | 65 | no | DIRECT/PARTIAL | published packet ids for 1.19; capabilities inherited from 1.18.2 (derived from 1.18.2) |
| 760 | 1.19.2 | V1_19 | DERIVED | 65 | no | DIRECT/PARTIAL | published packet ids for 1.19.2; capabilities inherited from 1.19 (derived from 1.19) |
| 761 | 1.19.3 | V1_19 | DERIVED | 66 | no | DIRECT/PARTIAL | published packet ids for 1.19.3; capabilities inherited from 1.19.2 (derived from 1.19.2) |
| 762 | 1.19.4 | V1_19 | DERIVED | 66 | no | DIRECT/PARTIAL | published packet ids for 1.19.4; capabilities inherited from 1.19.3 (derived from 1.19.3) |
| 763 | 1.20.1 | V1_20 | DECLARED | 21 | no | DIRECT/FULL | authored packet table |
| 764 | 1.20.2 | V1_20 | DERIVED | 80 | yes | DIRECT/PARTIAL | published packet ids for 1.20.2; capabilities inherited from 1.20.4 (derived from 1.20.4) |
| 765 | 1.20.4 | V1_20 | DECLARED | 80 | yes | DIRECT/FULL | authored packet table |
| 766 | 1.20.5 | V1_20 | DECLARED | 42 | yes | DIRECT/FULL | authored packet table |
| 767 | 1.21 | V1_21 | DERIVED | 85 | yes | DIRECT/PARTIAL | published packet ids for 1.21; capabilities inherited from 1.20.4 (derived from 1.20.4) |
| 768 | 1.21.3 | V1_21 | DERIVED | 84 | yes | DIRECT/PARTIAL | published packet ids for 1.21.3; capabilities inherited from 1.21 (derived from 1.21) |
| 769 | 1.21.4 | V1_21 | DERIVED | 84 | yes | DIRECT/PARTIAL | published packet ids for 1.21.4; capabilities inherited from 1.21.3 (derived from 1.21.3) |
| 770 | 1.21.5 | V1_21 | DERIVED | 84 | yes | DIRECT/PARTIAL | published packet ids for 1.21.5; capabilities inherited from 1.21.4 (derived from 1.21.4) |
| 771 | 1.21.6 | V1_21 | DERIVED | 84 | yes | DIRECT/PARTIAL | published packet ids for 1.21.6; capabilities inherited from 1.21.5 (derived from 1.21.5) |
| 772 | 1.21.8 | V1_21 | DERIVED | 84 | yes | DIRECT/PARTIAL | published packet ids for 1.21.8; capabilities inherited from 1.21.6 (derived from 1.21.6) |
| 773 | 1.21.10 | V1_21 | DERIVED | 84 | yes | DIRECT/PARTIAL | published packet ids for 1.21.10; capabilities inherited from 1.21.8 (derived from 1.21.8) |
| 774 | 1.21.11 | V1_21 | DERIVED | 84 | yes | DIRECT/PARTIAL | published packet ids for 1.21.11; capabilities inherited from 1.21.10 (derived from 1.21.10) |
| 775 | 26.1 | V26 | DERIVED | 84 | yes | DIRECT/PARTIAL | published packet ids for 26.1; capabilities inherited from 1.21.11 (derived from 1.21.11) |
| 776 | 26.2 | V26 | DECLARED | 38 | yes | DIRECT/FULL | authored packet table |

### Registered translators (ordered pairs)

| Client | Backend | Support | Completeness | Validation | Notes |
| ---: | ---: | --- | --- | --- | --- |
| 393 | 404 | TRANSLATED | PARTIAL | TRANSLATED_VERIFIED | Slot rematerialisation; recipes/advancements/trades dropped |
| 404 | 393 | TRANSLATED | PARTIAL | TRANSLATED_VERIFIED | Slot rematerialisation; recipes/advancements/trades dropped |
| 393 | 765 | TRANSLATED | PARTIAL | TRANSLATED_VERIFIED | core gameplay verified both ways; cosmetics incomplete |
| 765 | 393 | TRANSLATED | PARTIAL | TRANSLATED_VERIFIED | configuration synthesis; core gameplay verified |
| 765 | 766 | TRANSLATED | PARTIAL | TRANSLATED_PARTIAL | control/login/config; JoinGame/registry unsupported |
| 766 | 765 | TRANSLATED | PARTIAL | TRANSLATED_PARTIAL | control/login/config; JoinGame/registry unsupported |

protocols with codecs: 37; matrix cells: 1369; direct: 37; translated: 6; unsupported: 1326


## Test artifacts

`artifacts/ARTIFACTS.md` records the Minecraft client and server jars used for
validation: release, protocol, kind, Java requirement, availability, and
SHA-256. All 76 jars (38 releases x client+server) were fetched from Mojang's
official version manifest and verified against Mojang's published SHA-1. The
jars themselves are excluded from Git.

## Regenerating

```
python tools/export_protocols.py      # release -> protocol, from ProtocolVersion.java
python tools/packetids.py check       # cross-check declared tables vs published data
python tools/gen_revisions.py         # regenerate ProtocolRevisions.java deltas
python tools/mcartifacts.py fetch     # mirror + verify Minecraft jars
python tools/mcartifacts.py report    # regenerate artifacts/ARTIFACTS.md
```

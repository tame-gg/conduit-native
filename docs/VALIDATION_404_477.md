# Validation: protocol 477 (1.14) and 404↔477

Starting commit: `730f7b3`. Current: `fd9f1f8`.

## Artifacts

| Jar | SHA-256 (local) | Mojang SHA-1 |
| --- | --- | --- |
| `minecraft-client-1.14.jar` | `93907CBF0655AA5F…` | sha1-verified at download |
| `minecraft-server-1.14.jar` | `671E3D334DD601C5…` | sha1-verified at download |
| `minecraft-*-1.13.2.jar` | (existing) | sha1-verified |

## Test classification

| Path | Method | Result |
| --- | --- | --- |
| 477 ↔ 477 DIRECT | SCRIPTED PROBE (`ItemGameplayProbe477`) vs real 1.14 jar through Conduit | **PASS** |
| 404 → 477 TRANSLATED | SCRIPTED PROBE (`ItemGameplayProbe404`) vs real 1.14 jar through Conduit | **PASS** |
| 477 → 404 TRANSLATED | SCRIPTED PROBE (`ItemGameplayProbe477`) vs real 1.13.2 jar through Conduit | **PASS** |
| 404 → 477 TRANSLATED | **REAL 1.13.2 client** vs real 1.14 server through Conduit | **CORE GAMEPLAY VERIFIED** — 34,234 packets, 0 translation failures, 0 decoder faults, connected throughout |
| 477 → 404 TRANSLATED | **REAL 1.14 client** vs real 1.13.2 server through Conduit | **NOT VERIFIED** — client crashes on arrow metadata |
| 477 ↔ 477 DIRECT | **REAL 1.14 client** vs real 1.14 server through Conduit | **INCONCLUSIVE** — client crashes rendering a block model on a byte-identical passthrough stream |
| HUMAN GAMEPLAY SESSION | not possible here: synthetic OS input does not reach a detached Minecraft window | — |

Real-client runs are driven from each backend's console, so they cover the
clientbound half only. Nobody clicks; the serverbound half stays the probes' job.
Full write-up: `work/real-client-validation/RESULTS-477-REALCLIENT.md`.

Scripted probes exercised: login, play join, position, creative inventory, container click/close, block place/break, use item, chest place/open (when geometry allowed), keepalive/teleport confirm, sustained drain.

## Unit tests

`scripts/test.ps1` — all foundation tests including `Phase21_404_477_TranslationTests` **PASS**.

## Known limitations

- **477 → 404 is not real-client verified.** A real 1.14 client on a 1.13.2
  backend crashes with `ClassCastException` on `minecraft:arrow`. 1.14 reshaped
  `AbstractArrow`'s own metadata fields, and withholding subclass metadata for
  object entities did not clear it, so the bad field reaches the client by
  another path. This is the top open item.
- **Entity metadata above the base-class region is modelled per pair, not per
  entity class.** The two insertions 1.14 made (`Entity.pose`,
  `LivingEntity.sleepingPos`) describe every entity's base region, but a subclass
  that changed independently is not covered. Object entities therefore carry
  subclass metadata only where the layout is known unchanged — a dropped item's
  stack is allowed, everything else is withheld. That costs those entities their
  subclass display state; it never produces a wrong field. A per-class metadata
  table is the real fix.
- Particle metadata values are not copied across this pair (they throw rather
  than risk a mis-parsed stream), matching the 393 ↔ 404 behaviour.
- Recipes / advancements / tags / trade lists dropped as optional.
- Use Bed absorbed (removed in 1.14); Update Light, View Position and View
  Distance are absorbed toward 404 and synthesised toward 477.
- Heightmaps are sent to 1.14 as an empty compound; the client builds its own.
- Velocity compat jars are optional at runtime; missing them only logs an
  install error and does not block native Conduit.

### Resolved in this milestone

- Block states, items and entity types are no longer passed through numerically.
  Only 748 of 8599 block states, 108 of 790 items and 6 of 95 entity types
  actually keep their meaning across this pair, so passthrough was wrong for the
  large majority of each table. All 8599 block states and all 790 items now
  round-trip 404 → 477 → 404 exactly, and every state arrives with an identical
  block name and property set.
- 1.14's per-section non-air block count is read and written.
- Spawn Mob and Spawn Player carry their trailing metadata through translation.
- The player's own entity is registered as living from Join Game.
- Multi Block Change encodes through `BlockChangesCodec`.

## How to reproduce

```powershell
powershell -NoProfile -File scripts\_validate-404-477.ps1        # scripted probes
powershell -NoProfile -File scripts\_validate-477-realclient.ps1 # real clients
```

Regenerating the mapping tables from the official jars:

```powershell
java -cp artifactsin\minecraft-server-1.13.2.jar net.minecraft.data.Main --reports
java -cp artifactsin\minecraft-server-1.14.jar   net.minecraft.data.Main --reports
python tools\gen_mappings_pair.py 404 <reports-1.13.2> 477 <reports-1.14>
python tools\gen_entitytypes_404.py
```

Configs: `config/conduit-477-native.toml`, `config/conduit-404-to-477.toml`, `config/conduit-477-to-404.toml`.

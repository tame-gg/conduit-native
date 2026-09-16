# Validation: protocol 477 (1.14) and 404↔477

Starting commit: `730f7b3`

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
| HUMAN GAMEPLAY SESSION | not run in this milestone | — |

Scripted probes exercised: login, play join, position, creative inventory, container click/close, block place/break, use item, chest place/open (when geometry allowed), keepalive/teleport confirm, sustained drain.

## Unit tests

`scripts/test.ps1` — all foundation tests including `Phase21_404_477_TranslationTests` **PASS**.

## Known limitations (PARTIAL)

- Block/item numeric ids passed through for 404↔477 (early ids match; later ids e.g. chest state may diverge).
- Recipes / advancements / tags / trade lists dropped.
- Use Bed absorbed (removed in 1.14).
- No human client session yet for 477.
- Velocity compat jars are optional at runtime; missing them only logs an install error and does not block native Conduit.

## How to reproduce

```powershell
powershell -NoProfile -File scripts\_validate-404-477.ps1
```

Configs: `config/conduit-477-native.toml`, `config/conduit-404-to-477.toml`, `config/conduit-477-to-404.toml`.

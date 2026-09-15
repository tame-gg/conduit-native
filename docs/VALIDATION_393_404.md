# 393 ↔ 404 validation results

Commit under test: recorded in `RESULTS-393-404-*.txt` beside these logs.
Stamp of the green run: `20260915-183722`.

## Paths

| Path | Client | Backend | Result |
| --- | --- | --- | --- |
| DIRECT 404↔404 | scripted protocol-404 (`ItemGameplayProbe404`) | official 1.13.2 server `:25614` | **ok** — login, play, place/break, inventory, chest open+click, entities, metadata, keepalive |
| TRANSLATED 393→404 | scripted protocol-393 | official 1.13.2 server | **ok** — login, play, place/break, inventory slots, entities, metadata, keepalive; sustained 60s idle also ok. Chest open did not fire in this world spawn (geometry), not a translator failure |
| TRANSLATED 404→393 | scripted protocol-404 | official 1.13 server `:25613` | **ok** — login, play, place/break, **chest OPENED + click**, entities, metadata, keepalive; sustained 60s ok |

Conduit err logs for the translated runs: **0** `Translation failed` lines after the metadata entityId fix.

## Notes

- Backend `network-compression-threshold=-1` for scripted probes (Conduit still absorbs compression when enabled; probes do not speak compressed client frames).
- Recipes / advancements / trade lists still dropped (Slot-embedded, not rematerialised).
- Official GUI clients were not required for this stamp; jars are Mojang-official from `artifacts/`.

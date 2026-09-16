# Entity metadata and Spawn Object across 404 ↔ 477

This documents the fault that crashed a real Minecraft 1.14 client, why the
earlier diagnosis was wrong, and the architecture that replaced it.

## The crash

```
java.lang.ClassCastException: bca cannot be cast to java.lang.Byte
    at awb.v(SourceFile:699)      # AbstractArrow, reading its flags
    Entity Type: minecraft:arrow
```

`bca` is 1.14's `ItemStack` (confirmed with `javap` against the real client
jar). So the client was reading an item stack out of a field `AbstractArrow`
keeps a byte in.

## Root cause — it was not the metadata

**1.14 changed Spawn Object's `type` field from the legacy object enumeration to
the entity-type registry id.** The published delta records only that the field
widened from `i8` to `VarInt`, and Conduit had implemented exactly that: it
widened the field and passed the number through.

Measured directly, by summoning the same entities on the real 1.13.2 and 1.14
servers and recording what each sends (`MetadataSchemaProbe`):

| Entity | 1.13.2 Spawn Object type | 1.14 Spawn Object type |
|---|---|---|
| `minecraft:item` | 2 (legacy enumeration) | 34 (entity registry) |
| `minecraft:arrow` | 60 (legacy enumeration) | 2 (entity registry) |

The chain was therefore:

1. The 1.13.2 server spawns a dropped item as object type **2**.
2. Conduit forwards **2**, widened to a VarInt.
3. The 1.14 client reads registry id **2** and builds a **`minecraft:arrow`**.
4. The server sends that entity's metadata — a `Slot` at the item's index.
5. Conduit translates it faithfully, as an item's metadata.
6. The client, holding an arrow, reads that index as `AbstractArrow`'s byte of
   flags, and casts an `ItemStack` to a `Byte`.

The metadata pipeline was correct at every step. The entity *class* the client
instantiated was wrong, and the metadata was only where the damage surfaced.
This is why withholding subclass metadata did not help: the dropped item's stack
was explicitly allowed through, being the one field that visibly matters.

Spawn Object now resolves through each side's own namespace, by identifier, and
fails closed when an entity has no counterpart.

### Why unit tests did not catch it

Conduit's encoder and decoder agreed with each other; the packet was well
formed; every field after the type was correct. Nothing in the packet is wrong
in isolation — the fault only exists once something instantiates an entity from
that id, which only a real client does. A test could only have caught it by
asserting the type against the *other* protocol's registry, which is exactly the
assertion nobody writes when they believe the field is a passthrough.

## The second fault — a pair-wide rule cannot describe this pair

Metadata indices had been translated by one rule for the whole protocol pair:
`Entity` gained `pose`, `LivingEntity` gained `sleepingPos`, so shift by one
below the mob block and by two above it. That is correct for the base classes
and cannot express what 1.14 did to concrete classes:

| Entity | 1.13.2 | 1.14 | What changed |
|---|---|---|---|
| `arrow` | `6:0,7:12,8:1` | `7:0,8:12,9:0,10:1` | `pierceLevel` inserted mid-block; colour moves by **two** |
| `trident` | `6:0,7:12,8:0` | `7:0,8:12,9:0,10:0` | same insertion |
| `villager` | `…,13:1` | `…,15:16` | profession became `VillagerData` |
| `zombie` | `…,13:1,14:7,15:7` | `…,15:1,16:7` | a field removed |
| `horse` | `…,14:12,15:1,16:1` | `…,16:12,17:1` | a field removed |
| `firework_rocket` | `6:6,7:1` | `7:6,8:17,9:7` | attached entity became `OptVarInt` |

Under the old rule the arrow's colour (a VarInt) landed on 1.14's `pierceLevel`
(a Byte), and a villager's profession (a VarInt) landed on `VillagerData`. Both
are client-side casts waiting to happen.

## The architecture that replaced it

Layouts are **measured, not derived**:

- `MetadataSchemaProbe` connects straight to a real server, summons every entity
  type in the 1.13.2 registry, and records each entity's `(index, serializer)`
  pairs.
- `tools/gen_entity_metadata_schema.py` turns that into
  `entity_metadata_404.txt` and `entity_metadata_477.txt`, keyed by entity
  identifier so the two sides line up by name rather than by ids 1.14 reshuffled.
- `EntityMetadataSchemas` aligns two layouts with a **longest common subsequence
  over their serializer sequences**.

That single mechanism covers every case above with no per-entity branches:

- **inserted** field — everything after it shifts, and the arrow's colour still
  lands on the colour;
- **removed** field — no counterpart, so it is not sent;
- **retyped** field — the serializers do not match, so it is not sent rather
  than landing a VarInt on a structure the client will cast.

The observed serializer is additionally checked against the recorded layout for
that index, so a stream that disagrees with the capture drops the field instead
of trusting a stale alignment.

`Protocol404To477Translator` tracks each entity's identifier — from Join Game for
the player's own entity, from Spawn Mob through the entity registry, and from
Spawn Object through the namespace fix above — so metadata is always translated
against the right entity's layout.

### Entities covered

80 entity types on 404 and 86 on 477 have a measured layout, covering every
family the task named: `Entity`, `LivingEntity`, `Mob`, `Player`, animals,
monsters, `AbstractArrow` (`arrow`, `spectral_arrow`, `trident`), other
projectiles, `ItemEntity`, `FallingBlock`, `ItemFrame`, vehicles and
`ArmorStand`.

### What remains fail-closed

- An entity with no measured layout falls back to the base classes, which every
  entity shares, and its concrete class's own fields are dropped.
- A field whose serializer disagrees with the recorded layout is dropped.
- A field with no counterpart on the target is dropped.
- **Particle** metadata values are not translated: their payload shape depends on
  a particle id that 1.14 also renumbered. An unreadable value now truncates the
  remainder of that entity's metadata block and keeps the connection, instead of
  throwing and ending the session over one cosmetic field.

## Verification

Running a protocol-477 client **through Conduit** against the real 1.13.2 server
and comparing every field it receives against what the real 1.14 server sends
for the same entity (`tools/compare_metadata_schema.py`):

```
entities compared: 86
OK: every field Conduit sends lands where 1.14 reads that field
conduit translation faults: 0
```

The 107 `PLAY_SET_ENTITY_METADATA` drops in that run are all "no metadata fields
survived" — blocks consisting only of fields with no 1.13.2 counterpart, where
the entity keeps its client-side defaults.

## Reproducing the tables

```powershell
powershell -NoProfile -File scripts\_capture-metadata-schema.ps1
python tools\gen_entity_metadata_schema.py <schema-404.txt> <schema-477.txt>
powershell -NoProfile -File scripts\_validate-477-metadata.ps1
python tools\compare_metadata_schema.py <native-477.txt> <through-conduit.txt>
```

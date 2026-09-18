# lib/

Scripts download the jars in this directory. They are not committed.

- `./scripts/fetch-velocity-compat.ps1` fetches the Velocity API jars for the optional compat layer into `lib/`.
- `./scripts/fetch-via.ps1` fetches ViaVersion, ViaBackwards, ViaRewind and ViaLegacy, plus Netty, Guava and fastutil, into `lib/via/`. Conduit does not compile or start without these.

They are third-party code. The Via jars are GPL-3.0-or-later, except ViaVersion's MIT `api` module. See `THIRD-PARTY-NOTICES` and `docs/LICENSING_VIA.md`.

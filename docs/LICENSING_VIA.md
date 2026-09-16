# Third-party attribution — ViaVersion ecosystem

Conduit (koels) is an independently implemented Minecraft proxy.

The optional translation integration consumes published artifacts from:

| Project | Version used | License | Upstream |
|---|---|---|---|
| ViaVersion | 5.11.0 | GPLv3 | https://github.com/ViaVersion/ViaVersion |
| ViaBackwards | 5.11.0 | GPLv3 | https://github.com/ViaVersion/ViaBackwards |
| ViaRewind | 4.1.3 | GPLv3 | https://github.com/ViaVersion/ViaRewind |
| ViaLegacy | 3.0.16 | see upstream | https://github.com/ViaVersion/ViaLegacy |

Netty, Guava, and Fastutil are transitive/runtime libraries required by those
artifacts.

Conduit does **not** claim ownership of Via* implementations, mappings, or
protocol tables. Integration code in `gg.tame.conduit.viaversion` was written
for Conduit against public Via platform APIs.

Source for the Via* projects is available from their upstream repositories.
When redistributing a Conduit binary that links GPLv3 Via artifacts, provide
Corresponding Source as required by GPLv3.

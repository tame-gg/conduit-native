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

No ViaVersion, ViaBackwards, ViaRewind or ViaLegacy source is copied, ported,
adapted or mechanically translated into Conduit, and none of those projects is
forked. Where a Via API requires Conduit to implement an interface —
`ViaPlatform`, `ViaInjector`, `ViaPlatformLoader`, `VersionProvider`,
`StorableObject` — the implementation is written against the interface's public
contract. Via's own implementations of those interfaces are not used as a
reference for Conduit's.

One defect in ViaBackwards 5.11.0 is known and documented in
`work/real-client-validation/RESULTS-VIA-393-765.md`. It is recorded there
rather than patched around by reproducing Via's encoding inside Conduit, which
is why no fork exists and why this file still describes plain third-party
dependency use.

Source for the Via* projects is available from their upstream repositories.
When redistributing a Conduit binary that links GPLv3 Via artifacts, provide
Corresponding Source as required by GPLv3.

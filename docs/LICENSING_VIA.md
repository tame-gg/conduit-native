# Third-party licensing: the ViaVersion ecosystem

Conduit is an independently implemented Minecraft proxy. It is licensed under
**GPL-3.0-or-later**; see `LICENSE`. Third-party notices and license texts are in
`THIRD-PARTY-NOTICES`.

This file records how Conduit uses the Via projects and what each way of
distributing Conduit requires. It is not legal advice.

## Components and licenses

The licenses below were checked against each project's repository at the
version Conduit uses.

| Artifact | Version | License | Source |
|---|---|---|---|
| `viaversion-api` | 5.11.0 | MIT (the `api/` directory only) | https://github.com/ViaVersion/ViaVersion/tree/5.11.0/api |
| `viaversion-common` | 5.11.0 | GPL-3.0-or-later | https://github.com/ViaVersion/ViaVersion/tree/5.11.0 |
| `viabackwards-common` | 5.11.0 | GPL-3.0-or-later | https://github.com/ViaVersion/ViaBackwards/tree/5.11.0 |
| `viarewind-common` | 4.1.3 | GPL-3.0-or-later | https://github.com/ViaVersion/ViaRewind/tree/4.1.3 |
| `net.raphimc:ViaLegacy` | 3.0.16 | GPL-3.0-or-later | https://github.com/ViaVersion/ViaLegacy/tree/v3.0.16 (the file headers still name github.com/RaphiMC/ViaLegacy) |

- ViaVersion's README says: "The entirety of the API directory is licensed under
  the MIT License". It says everything else is GPLv3, "including the
  end-product as a whole". The `viaversion-common` jar also contains copies of
  the MIT API classes.
- ViaBackwards, ViaRewind and ViaLegacy have no MIT module. Every source header
  reads "either version 3 of the License, or (at your option) any later
  version".
- Netty, Guava, fastutil and the other runtime libraries are Apache-2.0 or MIT.
  Both licenses are compatible with GPLv3. `THIRD-PARTY-NOTICES` has the full
  list.

## How Conduit consumes them

This section describes what the build scripts and the code do. The
documentation elsewhere may not match it.

- **Downloaded, not committed.** `scripts/fetch-via.ps1` downloads the jars
  from `repo.viaversion.com` and Maven Central into `lib/via/`, and `.gitignore`
  excludes that directory. No Via source, jar or sources jar is tracked, and
  none ever has been.
- **Compile-time and runtime dependency, not optional.** `src/main` does not
  compile without the Via jars. The launcher does not start without them either,
  even with `[translation] enabled = false`: `ConduitRuntime` always calls
  `ConduitViaBootstrap`. `scripts/run.ps1` runs Conduit on the full classpath
  from `scripts/_classpath.ps1`.
- **Same process, same class loader.** Nothing isolates Via behind a plugin
  boundary.
- **More than the MIT API.** `gg.tame.conduit.viaversion` does all of the
  following:
  - It extends two GPL classes: `UserConnectionViaVersionPlatform` and
    `BaseVersionProvider`.
  - It calls or instantiates GPL classes: `ViaManagerImpl`, `ViaCommandHandler`,
    `UserConnectionImpl` and `ProtocolPipelineImpl`.
  - It implements the GPL interfaces `ViaBackwardsPlatform`, `ViaRewindPlatform`
    and `ViaLegacyPlatform`.
  - It implements the MIT API interfaces `ViaInjector`, `ViaPlatformLoader` and
    `StorableObject`.

  Conduit and Via therefore form one combined work. Because Conduit is itself
  GPL-3.0-or-later, that combination can be conveyed under GPLv3.

No ViaVersion, ViaBackwards, ViaRewind or ViaLegacy source is copied, ported,
adapted or mechanically translated into Conduit, and none of those projects is
forked. Conduit's subclasses and interface implementations are written against
the public contract of those types, not against Via's own implementations.
The ViaBackwards 5.11.0 defect recorded in `docs/VALIDATION_VIA_393_765.md` is
documented there and has not been patched in a copy of Via.

## What each distribution channel requires

### Running Conduit (building it and operating a server)

GPLv3 section 2 allows running the software and modifying it privately without
conditions. GPLv3, unlike the AGPL, has no clause that applies to network use,
so players connecting to a server do not trigger any obligation.

### This git repository

The repository conveys Conduit's source under GPL-3.0-or-later. It contains no
Via code. The unmodified MIT and Apache-2.0 jars in `lib/velocity-compat/`,
whether in the working tree or in the history, need their notices and license
texts. `THIRD-PARTY-NOTICES` provides them. The build does not use those
copies. They are expected to be untracked; `scripts/fetch-velocity-compat.ps1`
downloads the ones the build uses.

### A binary distribution

Two builds produce one: `gradle distZip`, `distTar` and `installDist` (see
`build.gradle.kts`), and `scripts/build-jar.ps1`, whose single `conduit.jar` has
the Via classes merged into it, and the Velocity-compatibility libraries (MIT,
Apache-2.0, LGPL-3.0 night-config, public-domain aopalliance) with them; each of
those keeps its own license and notice files under `META-INF/licenses/<jar>/`,
and the jar carries `LICENSE` and `THIRD-PARTY-NOTICES` in `META-INF/`. Both convey object code for Conduit and for the
four GPL Via jars, so both carry what GPLv3 requires:

| In the distribution | Meets |
|---|---|
| `LICENSE`: Conduit's notice and the GPLv3 text | Sections 4 and 5: the license text, kept intact, accompanies the program |
| `THIRD-PARTY-NOTICES` | Copyright and license notices for every bundled MIT and Apache-2.0 component |
| `source/conduit/`: Conduit's source, build files, scripts and generators from the tree that was built | Section 6: Corresponding Source for Conduit, including "the scripts used to control" building it (section 1) |
| `source/third-party/*.zip`: the upstream release-tag archives of ViaVersion 5.11.0, ViaBackwards 5.11.0, ViaRewind 4.1.3 and ViaLegacy v3.0.16, downloaded by the `fetchViaSource` task or by `scripts/fetch-via-source.ps1` | Section 6: Corresponding Source for the GPL jars |

`scripts/build-jar.ps1 -SkipSource` omits both source folders. What it produces
is for running on the machine that built it and is not a distribution: handing
it to anyone would convey the Via object code without its source. The script
says so when the flag is used.

The Via versions live in three places that have to agree: `build.gradle.kts`,
`scripts/fetch-via.ps1` (the jars) and `scripts/fetch-via-source.ps1` (their
source). Changing one alone produces a distribution whose source does not match
its binaries.

The Corresponding Source travels inside the same archive as the object code.
That meets section 6 however the archive is published, whether as a
GitHub release asset, a download page or a copy handed to someone. No written
offer (section 6(b)) is needed, and there is no separate source server to keep
online (section 6(d)).

When the Via versions in `build.gradle.kts` change, the source archives follow,
because the version values are shared. Anyone who conveys a bundle assembled
some other way, for example `out/` zipped together with `lib/via/`, has to
include the same items by hand.

### An updated ViaVersion at runtime

`[updates] via = true` has Conduit download a newer ViaVersion from
`repo.viaversion.com` into `lib/via` and use it in place of the bundled copies.
That is the operator's machine fetching a library from its own publisher, not
Conduit conveying it, so it adds no obligation to Conduit's distribution and the
jars involved never enter one.

What it does mean is that a running Conduit may be combined with a Via the
`source/third-party/` archives beside it do not correspond to. The Corresponding
Source shipped with a distribution matches the Via merged into that jar; anyone
who conveys the combination they are actually running -- the jar together with an
updated `lib/via` -- has to include the source of that Via instead, from the same
upstream tags.

## Open points

These questions are not settled by the license texts:

- **Upstream tags as Corresponding Source.** The source archives are taken from
  the upstream release tags. Conduit assumes those tags built the published
  Maven jars, and does not verify it by rebuilding them.
- **Plugins.** Native Conduit plugins and Velocity-API plugins are loaded into
  the same process as Conduit. The FSF treats a plug-in that shares function
  calls and data structures with a GPL program as part of a combined work
  (GPL FAQ `#GPLPlugins`). Whether non-GPL plugins may be distributed for
  Conduit is therefore open, as it is for other GPL servers. Conduit does not
  distribute third-party plugins, so this does not affect Conduit's own
  distribution. If the owner wants to allow non-GPL plugins explicitly, a
  GPLv3 section 7 additional permission for the plugin API could do so.
- **Mojang-derived data.** The tables under `src/main/resources` were generated
  from data that Mojang's server jars emit. The Via licenses do not cover them,
  and GPL-3.0-or-later can only cover what Conduit owns. Whether ID mappings of
  that kind are copyrightable at all is outside this file's scope.

# Swiko 1.0.1

This patch release expands desktop packaging without changing the 1.0 language
core. It adds macOS, Windows, and Linux JVM packages, a JVM-only build mode, and
native host packaging for Apple Silicon Macs, Intel Macs, Windows x64, and
Linux. Platform-oriented JVM packages contain the same checked interpreter JAR
and need Java 17 or later.

The hosted workflow builds native targets on their actual operating systems.
Native archives are available only after the corresponding job succeeds; a
workflow definition does not mean a macOS or Windows job has already passed.
Use the artifact's build report and the repository's Actions run as evidence.

The Kotlin frontend and generated Kotlin applications now write explicit UTF-8
bytes, preserving Unicode on Java 17 installations with a legacy Windows
default encoding. Generated Swift and the native frontend explicitly import
the Windows C runtime when needed.

`BUILD-REPORT.md` records actual toolchains, validation results, and native
system-library requirements. Verify artifacts with SHA256SUMS. The release tool
checks extracted distributions and source rebuilding before publishing.

`--backend jvm` needs Kotlin and Java without a Swift installation. Its report
identifies native and compiler checks that were not run. `--backend all` remains
the default and builds both frontends plus the current host's native archive.

The CLI, source generators, and no-argument host interface are the verified
release scope. Classes, collections, modules, generics, concurrency, package
registries, a debugger, framework bindings, and mobile SDK distributions are
future work. Swift/Kotlin source compatibility is not claimed. Resource guards
do not make this a security sandbox.

Linux native requirements remain in the ABI report. Native macOS builds target
macOS 13.0; execution is verified on the actual host image. Windows native
packages include matching Swift runtime DLLs and retain the documented Visual
C++ Redistributable prerequisite. Signing, notarization, a universal Mac binary,
Windows ARM64 native packages, and mobile installers are not included. See
[PLATFORMS.md](PLATFORMS.md) for usage and native build status.

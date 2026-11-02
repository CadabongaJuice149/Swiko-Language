# Platform options

Swiko has a portable Kotlin/JVM implementation and a Swift native
implementation. They run the same `.swk` files. Platform-specific native
executables must be built and tested on their actual operating system;
renaming a Linux binary cannot create a macOS or Windows application.

## Packages you can use now

The platform-oriented JVM packages contain the same checked interpreter JAR,
examples, documentation, and notices. They require **Java 17 or later**.
They do not contain a native Swift executable or an operating-system installer.

| Computer | Package | How to run |
| --- | --- | --- |
| macOS, Apple Silicon or Intel | `swiko-1.0.1-macos-jvm.zip` | `java -jar swiko.jar examples/hello.swk` |
| Windows, x64 or ARM64 with a compatible Java runtime | `swiko-1.0.1-windows-jvm.zip` | `java -jar .\swiko.jar .\examples\hello.swk` |
| Linux with a compatible Java runtime | `swiko-1.0.1-linux-jvm.zip` | `java -jar swiko.jar examples/hello.swk` |
| Any supported Java platform | `swiko-1.0.1-jvm.zip` or the standalone JAR | `java -jar swiko.jar --help` |

Install a Java runtime built for your operating system and CPU. Extract the
ZIP, open Terminal or PowerShell in its directory, then run the command above.
No Swift or Kotlin compiler is needed to run the JAR.

The JVM bytecode targets Java 17. The Linux build report records the Java and
Kotlin versions used for the verified local build. A portable JVM package
created on Linux is not evidence that a native macOS or Windows build passed.

## Native build targets

| Native target | Actual build host in the workflow | Status before that job runs |
| --- | --- | --- |
| Linux x86_64 | `ubuntu-24.04` | Linux build and packaging can be verified locally |
| macOS Apple Silicon / arm64 | `macos-15` | Host validation required |
| macOS Intel / x86_64 | `macos-15-intel` | Host validation required |
| Windows x86_64 | `windows-2022` | Host validation required |

The native release command chooses its own host operating system and CPU.
It checks native file headers and packages that host's result. macOS packages
preserve executable permissions; Windows packages include the required Swift
runtime DLLs. Each native archive must pass its host's extraction and execution
checks before it is published as an artifact.

Native Windows ARM64, universal macOS binaries, mobile SDKs, signed macOS
installers, and signed Windows installers are not included in this version.
Unsigned native downloads may require the operating system's normal local
approval. No signing or notarization is claimed.
The Windows native package requires the Microsoft Visual C++ 2015–2022 x64
Redistributable in addition to its app-local Swift DLLs. The build report lists
the actual operating-system dependencies and macOS deployment target.

## Build from source on your computer

Install JDK 21 and Kotlin/JVM 2.2.21. For a native build, also install Swift
6.0.3 and its platform prerequisites: Xcode command-line tools on macOS, or
the supported Visual Studio C++/Windows SDK components on Windows.

Bootstrap the Kotlin release tool from the project root:

```text
mkdir build
kotlinc tools/Release.kt -jvm-target 17 -include-runtime -d build/release-tool.jar
```

If `build` already exists, skip the directory-creation command. Select compiler
executables through `SWIKO_KOTLINC` and `SWIKO_SWIFTC` if they are not on PATH.

For a JVM-only release without installing Swift:

```text
java -jar build/release-tool.jar release --root . --backend jvm
```

For both implementations and a genuine native package for the current host:

```text
java -jar build/release-tool.jar release --root . --backend all
```

Use separate source checkouts or copy the output before switching backend
modes; a successful release publishes a complete versioned output directory.
Read its `BUILD-REPORT.md` and `SHA256SUMS`. JVM-only reports identify the
compiler and native checks that were not run.

The native toolchain can have a nonstandard runtime/notices layout. The release
tool accepts `SWIKO_SWIFT_RUNTIME_DIR` for extra runtime DLL search roots on
Windows and `SWIKO_SWIFT_NOTICES_DIR` for matching Swift notices. See errors
from the actual toolchain rather than substituting libraries from another
Swift release.

## Build on genuine hosted operating systems

`.github/workflows/platform-release.yml` defines four independent host jobs.
It runs on pull requests, pushes to `swiko/**` branches, a `v*` tag that exactly
matches VERSION, or manually with `all` or `jvm`. It selects JDK 21, pins the
Kotlin 2.2.21 and Swift 6.0.3 setup actions, and checks
the Kotlin compiler archive's SHA-256 before using it.

The Windows job reads ICU's version from the actual installed SDK headers
before collecting notices. It uses the matching vendored 74.1 notice only
when that version is observed; a different observed version selects the
corresponding official ICU release notice. Missing or ambiguous headers stop
the native job rather than assuming the Linux runtime's provenance.

Each full native job builds both frontends, runs language, CLI, compiler, and
host-library tests, creates archives, extracts and executes them, and rebuilds
the extracted source. Only successful jobs upload their release directories.
The workflow does not create a public GitHub Release, change sharing, or sign
binaries. It has read-only repository permissions.

Native validation is determined by completed Actions jobs and their build
reports, rather than by the existence of a workflow file. To obtain validated
native artifacts, run **Swiko platform releases** in your repository's Actions
and download the successful host's artifact. A job failure means
that platform remains unverified; the package must not be relabeled as tested.

Runner labels and action revisions were checked against their official
repositories when this workflow was prepared. Hosted images can change;
the actual build report records the toolchains used by a completed run.

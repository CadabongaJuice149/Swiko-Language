# Swiko 1.0.1

Swiko is a small language based on Swift and Kotlin. Its implementation,
compiler backends, build tool, and test runners are written in those two
languages. Programs use `.swk` files.

The 1.0 core includes a statically checked command-line interpreter, compilers
that generate Swift and Kotlin source, and library entry points for host apps.
Both frontends accept the same language and can generate either target.

```kotlin
val nickname: String? = null
fun greet(name: String): String {
    return "Hello, " + name
}
print(greet(nickname ?? "World"))
```

## Install a release

| Artifact | Requirements |
| --- | --- |
| `swiko-1.0.1-macos-jvm.zip` | Apple Silicon or Intel Mac with Java 17 or later |
| `swiko-1.0.1-windows-jvm.zip` | Windows with a compatible Java 17 or later runtime |
| `swiko-1.0.1-linux-jvm.zip`, generic `-jvm.zip`, or standalone `.jar` | A compatible Java 17 or later runtime |
| `swiko-1.0.1-linux-x86_64.tar.gz` | Linux x86_64 with system libraries listed in the build report |
| `swiko-1.0.1-macos-arm64.zip` or `-macos-x86_64.zip` | Matching Mac architecture; native host build and report required |
| `swiko-1.0.1-windows-x86_64.zip` | Windows x64; native host build, bundled DLLs, and Visual C++ runtime requirements in the report |
| `swiko-1.0.1-source.zip` | Kotlin/JVM 2.2.21 and JDK 21; Swift 6.0.3 additionally for native builds |

The platform-oriented JVM ZIPs contain the same interpreter JAR with suitable
usage instructions. They require Java, and do not require a Swift or Kotlin
compiler. Choose the Java runtime for your operating system and CPU.

Native archives are produced only by a successful build on their actual host
operating system and architecture. A package name or workflow configuration is
not evidence of a completed native build: check that artifact's `BUILD-REPORT.md`
and the corresponding Actions run. See [PLATFORMS.md](PLATFORMS.md) for targets
and verification status. Native Windows ARM64, universal Mac binaries, signed
installers, and mobile SDKs are outside this release.

Verify downloaded artifacts against `SHA256SUMS`. The Linux native executable
statically includes the Swift standard library and still needs the system
C/C++ libraries listed in its report. macOS native builds target macOS 13.0 or
later and use the operating system's Swift runtime. Windows native packages
keep the executable and matching Swift DLLs together, and require the Visual
C++ 2015–2022 x64 Redistributable. Native execution is verified on the build
host; older operating systems are not inferred to be tested from deployment
settings alone.

On macOS or Linux, extract the JVM ZIP and open Terminal in its directory:

```text
java -jar swiko.jar examples/hello.swk
java -jar swiko.jar --check examples/functions.swk
java -jar swiko.jar --eval 'print("Hello, Swiko!")'
```

On Windows, extract the Windows JVM ZIP and open PowerShell in its directory:

```powershell
java -jar .\swiko.jar .\examples\hello.swk
java -jar .\swiko.jar --check .\examples\functions.swk
java -jar .\swiko.jar --version
```

The same `.swk` programs and CLI options work in all JVM packages.

After extracting a verified Linux or macOS native distribution:

```text
./bin/swiko examples/hello.swk
./bin/swiko --version
```

For a verified Windows native distribution, keep all `bin` files together:

```powershell
.\bin\swiko.exe .\examples\hello.swk
.\bin\swiko.exe --version
```

Run `--help` for all options. `-` reads standard input. `--` ends options for
a filename beginning with a hyphen. Type errors reject a program before it
executes, including errors in skipped branches.

## Compile and embed

Generate real Swift or Kotlin source, then use the target compiler:

```text
java -jar swiko.jar --emit-swift examples/app.swk > App.swift
swiftc App.swift -o app

java -jar swiko.jar --emit-kotlin examples/app.swk > App.kt
kotlinc App.kt -jvm-target 17 -include-runtime -d app.jar
java -jar app.jar
```

Add `--library` to generate a host library without an application entry point.
The library exposes `swikoRun()` to return captured output and `swikoMain()` to
print it. Each call uses fresh execution state. Swift entry points throw errors;
Kotlin entry points throw exceptions. See [app development](APP_DEVELOPMENT.md)
and [compiler tooling](COMPILER.md).

## Build, test, and release

Install Kotlin/JVM 2.2.21 and JDK 21; add Swift 6.0.3 for native builds. Bootstrap
from the project root, creating `build` first if it does not exist. Use
`--backend jvm` to build without Swift on Windows, macOS, or
Linux. Use `--backend all` to build both implementations and a genuine native
package for the current host:

```text
mkdir build
kotlinc tools/Release.kt -jvm-target 17 -include-runtime -d build/release-tool.jar
java -jar build/release-tool.jar build --root . --backend jvm
java -jar build/release-tool.jar test --root . --backend jvm
java -jar build/release-tool.jar release --root . --backend jvm

java -jar build/release-tool.jar release --root . --backend all
```

Use `SWIKO_KOTLINC` and `SWIKO_SWIFTC` to select compiler executables when they
are not on PATH. The default backend is `all`. Native toolchain prerequisites
and Windows runtime/notice selectors are documented in [PLATFORMS.md](PLATFORMS.md).
Each command uses fresh staging. Full releases run language, CLI, compiler,
example, archive, and extracted-source checks before publishing
`dist/swiko-1.0.1/`. JVM-only releases run the applicable Kotlin language, CLI,
example, archive, and source checks; their reports mark the Swift/native checks
as not run. A failure prevents publication.

Backends publish a complete versioned output directory, so use separate
checkouts or save the artifacts before switching backend modes. The source
archive includes the complete release tool, tests, and hosted platform workflow.
That workflow builds Linux x64, Apple Silicon Mac, Intel Mac, and Windows x64
on genuine hosts, and uploads artifacts only for successful jobs.

`BUILD-REPORT.md` records actual toolchains and check results. `SHA256SUMS`
covers artifacts; source hashes record inputs. Archive ordering and timestamps
are normalized. Bit-for-bit compiler output across machines is not claimed.

## Supported language

The stable core has immutable and mutable bindings, typed functions, lexical
scope, recursion, nullable values, checked 64-bit arithmetic, strings, booleans,
conditions, and loops. [SPEC.md](SPEC.md) defines the complete rules and limits.
[CHANGELOG.md](CHANGELOG.md) describes migration from the prototype.

Classes, collections, generics, imports, concurrency, a debugger, package
registries, and app-framework bindings are outside the 1.0 core. Source
generation and library integration support native and JVM host apps; they do
not replace the full Swift/Kotlin frameworks. Resource guards do not make
execution a hostile-code security sandbox.

Toolchains, the JVM, native runtime, and operating system have their own
dependencies. See [THIRD_PARTY.md](THIRD_PARTY.md) for notices. Bundled licenses
do not imply an open-source license for the Swiko project's original source.

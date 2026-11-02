# Swiko 1.0.0

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
| `swiko-1.0.0-jvm.zip` or standalone `.jar` | Java 17 or later |
| `swiko-1.0.0-linux-x86_64.tar.gz` | Linux x86_64 with system libraries listed in the build report |
| `swiko-1.0.0-source.zip` | Kotlin/JVM 2.2.21, JDK 21, Swift 6.0.3 for the verified build |

Verify artifacts against `SHA256SUMS`. The Linux distribution includes the
Swift standard library statically; running it does not require a Swift
installation. It still needs the operating system's C/C++ libraries. Windows
and macOS users can run the JVM distribution or build from source. Native
macOS, Windows, ARM, iOS, and Android artifacts are not included.

After extracting the JVM distribution:

```text
java -jar swiko.jar examples/hello.swk
java -jar swiko.jar --check examples/functions.swk
java -jar swiko.jar --eval 'print("Hello, Swiko!")'
```

After extracting the Linux distribution:

```text
./bin/swiko examples/hello.swk
./bin/swiko --version
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

Install the verified Kotlin, Java, and Swift toolchains. Bootstrap from the
project root. The automated native release builder currently requires Linux
x86_64, GNU tar, `ldd`, and `readelf`:

```text
mkdir -p build
kotlinc tools/Release.kt -jvm-target 17 -include-runtime -d build/release-tool.jar
java -jar build/release-tool.jar build --root .
java -jar build/release-tool.jar test --root .
java -jar build/release-tool.jar release --root .
```

Use `SWIKO_KOTLINC` and `SWIKO_SWIFTC` to select compiler executables when they
are not on PATH. Each command uses fresh staging. Release creation runs language,
CLI, compiler, example, archive, and extracted-source checks before publishing
`dist/swiko-1.0.0/`. A failure prevents publication. The source archive includes
the complete release tool and tests.

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

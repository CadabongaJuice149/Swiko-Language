# Application development

Swiko generates a standalone native or JVM program, or a library callable from
a Swift or Kotlin host. The 1.0 host interface runs a no-argument program and
returns its textual output. It does not expose classes, host objects, parameter
injection, or UI-framework bindings.

## Standalone application

```text
java -jar swiko.jar --check examples/app.swk
java -jar swiko.jar --emit-swift examples/app.swk > App.swift
swiftc App.swift -o app
./app
```

For the JVM:

```text
java -jar swiko.jar --emit-kotlin examples/app.swk > App.kt
kotlinc App.kt -jvm-target 17 -include-runtime -d app.jar
java -jar app.jar
```

Generated source contains typed functions, statements, and target runtime
helpers. It contains no Swiko interpreter. Arithmetic, source errors, buffered
output, and checked language types retain their defined behavior.

These Terminal commands apply to macOS and Linux. With a verified Windows
Swift toolchain, use an `.exe` output and run it from PowerShell:

```powershell
cmd /d /c "java -jar swiko.jar --emit-swift examples\app.swk > App.swift"
swiftc App.swift -o app.exe
.\app.exe
```

The `cmd` redirection preserves the emitted UTF-8 source on older PowerShell
versions. PowerShell 7.4 or later preserves native stdout bytes with its normal
redirection. The Windows JVM equivalent is:

```powershell
cmd /d /c "java -jar swiko.jar --emit-kotlin examples\app.swk > App.kt"
kotlinc App.kt -jvm-target 17 -include-runtime -d app.jar
java -jar .\app.jar
```

Generated native executables need their target toolchain's runtime deployment
requirements. Producing source on one operating system does not create a native
executable for another; compile and validate it on the intended target.

## Swift host

```text
java -jar swiko.jar --emit-swift --library examples/app.swk > Generated.swift
swiftc Generated.swift examples/hosts/SwiftHost.swift -o host-app
./host-app
```

The generated API is:

```swift
public func swikoRun() throws -> String
public func swikoMain() throws
```

Use `try swikoRun()` to obtain text for a view, document, or command-line
response. It does not print. `swikoMain` prints the successful result. A failed
run throws without publishing buffered output. The host owns scheduling, UI
updates, permissions, platform SDKs, and application distribution.

## Kotlin host

```text
java -jar swiko.jar --emit-kotlin --library examples/app.swk > Generated.kt
kotlinc Generated.kt examples/hosts/KotlinHost.kt -jvm-target 17 -include-runtime -d host-app.jar
java -jar host-app.jar
```

The generated API is:

```kotlin
fun swikoRun(): String
fun swikoMain(): Unit
```

Call `swikoRun()` for output without printing. Each call starts with fresh
variables, counters, and output. Kotlin/JVM is the supported Kotlin target;
completed compiler tests are recorded per host in its build report. Kotlin/Native
and Kotlin Multiplatform compilation are not release targets. Integrating generated
code into a particular iOS, desktop, or Android app requires that platform's
SDK and a host project.

The same no-argument host APIs are retained in 1.0.1. The desktop packaging
changes do not add UI frameworks, mobile installers, or Java runtime bundling.

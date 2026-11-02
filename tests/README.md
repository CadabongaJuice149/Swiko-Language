# Shared conformance tests

Every `.swk` fixture in `cases/` has exactly one expected result:

- `.out`: the complete standard output of a successful run, including newlines.
- `.error`: exit status 1, empty buffered standard output, and a source line
  and column in standard error. An
  empty file accepts any language error message; a nonempty file additionally
  requires that exact substring in the diagnostic.

By default, the harness runs each fixture against both the Kotlin and Swift interpreters.
Each process has a 15-second timeout. It requires exact output and empty standard
error for successful programs. These cases cover the shared language contract,
including checked integer boundaries, lexical scope, static type checks in
unexecuted code, nullable values, lazy operators, functions, memory limits,
and runaway execution diagnostics.

From the project directory, after building both interpreters:

```text
kotlinc kotlin/Conformance.kt -include-runtime -d build/conformance.jar
java -jar build/conformance.jar build/swiko.jar build/swiko-swift tests/cases
```

For a JVM-only build, replace the native executable argument with the literal
`--jvm-only`. This runs the Kotlin implementation and does not count or claim
Swift coverage:

```text
java -jar build/conformance.jar build/swiko.jar --jvm-only tests/cases
java -jar build/cli-conformance.jar build/swiko.jar --jvm-only .
```

Use `build/swiko-swift.exe` for a Windows native build. The runners locate
`java.exe` on Windows and `java` on macOS/Linux from the active JDK. Captured
text uses UTF-8, and CRLF output and fixture line endings normalize to LF.

`CliConformance.kt` exercises both command-line interfaces, including option
errors, eval, stdin, check mode, strict UTF-8, initial BOM, CRLF and bare-CR
normalization, Unicode scalar source columns, and source/token byte limits.
It also checks Unicode filenames containing spaces. The JVM checks force a
legacy Windows-1252 default charset to verify that program output, source
emission, and Unicode diagnostic paths still use UTF-8.

```text
kotlinc kotlin/CliConformance.kt -include-runtime -d build/cli-conformance.jar
java -jar build/cli-conformance.jar build/swiko.jar build/swiko-swift .
```

`CompilerConformance.kt` checks both frontends against both generated target
languages, compiling and running real target source. It also checks library
embedding and generated runtime diagnostics. Compiler commands default to
`kotlinc` and `swiftc`; set `SWIKO_KOTLINC` and `SWIKO_SWIFTC` to explicit paths
when needed.

The full compiler suite requires a genuine native frontend and both target
compilers. It is excluded from JVM-only release validation. Native executable
headers are checked against the actual host's format and architecture: ELF on
Linux, Mach-O on macOS, and PE on Windows. Generated executables use `.exe` on
Windows. Separately linked Swift host tests use `.so`, `.dylib`, or `.dll` plus
its import library as appropriate; no Unix rpath flag is passed on Windows.
Windows `.bat`/`.cmd` Kotlin compiler entry points are resolved to the installed
compiler's Java main class, avoiding shell quoting and variable expansion.

Windows tests may use `SWIKO_SWIFT_RUNTIME_DIRS` to identify required Swift SDK
DLL directories, separated by the platform path separator. Host tests also use
the configured SDK and compiler runtime paths. Native Windows and macOS
results must come from runs on those operating systems; Linux checks and
platform-plan simulations do not establish native compatibility.

```text
kotlinc kotlin/CompilerConformance.kt -include-runtime -d build/compiler-conformance.jar
java -jar build/compiler-conformance.jar build/swiko.jar build/swiko-swift .
```

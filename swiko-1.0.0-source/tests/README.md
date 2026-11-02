# Shared conformance tests

Every `.swk` fixture in `cases/` has exactly one expected result:

- `.out`: the complete standard output of a successful run, including newlines.
- `.error`: exit status 1, empty buffered standard output, and a source line
  and column in standard error. An
  empty file accepts any language error message; a nonempty file additionally
  requires that exact substring in the diagnostic.

The harness runs each fixture against both the Kotlin and Swift interpreters.
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

`CliConformance.kt` exercises both command-line interfaces, including option
errors, eval, stdin, check mode, strict UTF-8, initial BOM, CRLF and bare-CR
normalization, Unicode scalar source columns, and source/token byte limits.

```text
kotlinc kotlin/CliConformance.kt -include-runtime -d build/cli-conformance.jar
java -jar build/cli-conformance.jar build/swiko.jar build/swiko-swift .
```

`CompilerConformance.kt` checks both frontends against both generated target
languages, compiling and running real target source. It also checks library
embedding and generated runtime diagnostics. Compiler commands default to
`kotlinc` and `swiftc`; set `SWIKO_KOTLINC` and `SWIKO_SWIFTC` to explicit paths
when needed.

```text
kotlinc kotlin/CompilerConformance.kt -include-runtime -d build/compiler-conformance.jar
java -jar build/compiler-conformance.jar build/swiko.jar build/swiko-swift .
```

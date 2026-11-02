# Compiler and command-line reference

Both frontends support the same commands. `swiko` below means a verified native
binary on Linux or macOS; Windows uses `swiko.exe`. `java -jar swiko.jar` is
equivalent on macOS, Windows, and Linux with a compatible Java 17 or later
runtime. See [PLATFORMS.md](PLATFORMS.md) for packages and build status.

```text
swiko FILE
swiko --eval SOURCE
swiko -
swiko --check FILE
swiko --emit-swift FILE
swiko --emit-kotlin FILE
swiko --emit-swift --library FILE
swiko --emit-kotlin --library FILE
swiko --help
swiko --version
```

For tools that need byte-exact Unicode argument transport, both frontends also
accept `--utf8-args BASE64`. Encode the complete ordinary argument array as UTF-8,
terminate every argument with a NUL byte, concatenate the records, then encode
those bytes as standard Base64. An empty array is encoded as zero bytes. Invalid
Base64, invalid UTF-8, missing terminators, and oversized payloads are usage
errors. The decoded arguments go through the normal CLI and language checks;
transport is expanded once, without recursion.

This is useful with Windows Java launchers, which can narrow ordinary command
lines to the system ANSI code page before `main` receives them. ASCII arguments
and source read from UTF-8 files or stdin work normally; tools passing Unicode
filenames or inline source can use this transport. Native Windows clients should
also use correct Windows argument quoting when starting a child process.

Check and emit modes also accept `--eval SOURCE` or `-`. Exactly one source and
at most one mode are allowed. Library mode requires an emit mode. `--` ends
options, allowing `-- --program.swk`. Unknown, duplicate, conflicting, or
incomplete options are usage errors.

## Pipeline

1. Read bounded, strictly decoded UTF-8 input; normalize BOM and newlines.
2. Tokenize and parse the complete source with token and nesting limits.
3. Resolve names and check every branch and function body.
4. Execute the checked program or emit the chosen target source.

Self-recursion is supported; other declarations must precede references.
Non-Unit functions must return on all paths. Loops are conservatively treated
as possibly executing zero times. Nullable values require coalescing before
arithmetic or boolean use, and retain their nullable annotation after assignment.

## Targets

Backends translate checked AST nodes into target declarations, expressions,
functions, and control flow. Symbols are renamed to avoid target collisions.
Checked behavior uses helpers written in the target language.

Int maps to Swift Int64 or Kotlin Long. Bool maps to Bool or Boolean. String
maps to the target String type. Nullable types remain nullable. Swift may use
a Unit marker to preserve nullable Unit and Unit comparisons. Library mode
exports `swikoRun` and `swikoMain` without executing on import.

Generated source is intended to be retained and reviewed. Target compilers
optimize it and produce native code or JVM bytecode. The platform build workflow
selects Swift 6.0.3 and Kotlin/JVM 2.2.21 on JDK 21; each completed build report
records its actual host and compiler tests. A compiler working on Linux is not
evidence that an unexecuted macOS or Windows job passed. Swiko does not ship a
custom VM or linker. The host interface takes no arguments in this core release.

Swiko CLI output and generated Kotlin application output use UTF-8 regardless
of Java's default text encoding. On Windows PowerShell 5.1, ordinary `>`
redirection can transcode native output to UTF-16. Use PowerShell 7.4 or later,
or perform source-file redirection through `cmd /d /c` to preserve the compiler's
UTF-8 bytes. For example:

```powershell
cmd /d /c "java -jar swiko.jar --emit-kotlin examples\app.swk > App.kt"
```

## Diagnostics

```text
program.swk:4:12: error: message
```

Diagnostics go to stderr. Lines and columns start at one; columns count Unicode
scalar values. Eval uses `<eval>` and stdin uses `<stdin>`. Successful checking
is silent. Successful emission writes only target source to stdout. A rejected
program emits no target source.

| Status | Meaning |
| --- | --- |
| 0 | Successful run, check, emission, help, or version |
| 1 | Source, syntax, type, runtime, or resource error |
| 2 | Command-line usage error |

The library API returns a result or throws instead of choosing a process exit
status. Generated runtime errors retain original Swiko source locations.

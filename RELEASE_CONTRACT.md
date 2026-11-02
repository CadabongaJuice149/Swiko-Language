# Swiko 1.0 implementation contract

This contract coordinates the first stable release of the documented core.
Implementation, build tools, and test runners use Swift and Kotlin only.
Swift and Kotlin source generators provide standalone applications and a
`swikoMain` entry point for integration into host applications. This release
does not claim full Swift/Kotlin feature parity or framework bindings.

## Frontend and checking

Keep the existing grammar and primitive values from SPEC.md. All programs
are parsed and statically checked before execution or source generation.
The checker walks every branch and function body, including unreachable code.
Bindings retain their declared/inferred types. Assignments, call arguments,
returns, conditions, and operators must type-check before execution.

Types are Int, Bool, String, Unit, nullable forms, and the internal null-literal
type. A matching nonnullable type can flow to its nullable form. Null can flow
only to nullable types. Null inference still requires an explicit annotation.
Arithmetic and ordering require nonnullable Int, string addition requires two
nonnullable String values, and boolean operators require nonnullable Bool.
Equality allows matching base types with either nullability and any comparison
against null. Equality between two Unit values is valid. Coalescing accepts a
left operand of T or T? and a compatible right operand of T, T?, or null; its
result is nonnullable if the left is nonnullable or the right is nonnullable.
For a null-literal left operand, use the right operand type. Both operands are
checked even though runtime evaluation remains lazy.

Functions are name-call-only. Function/parameter/body declarations share their
lexical rules from SPEC.md. A function is available for self-recursion, but
references to declarations that appear later are rejected. Every non-Unit
function must return on all paths; an if with both returning branches returns
on all paths. Loops are conservatively treated as possibly executing zero
times. Unit? functions may fall through with Unit. Return outside a function,
unknown symbols, duplicate declarations, assigning immutable names, and
reserved print declarations/assignments are errors even in skipped branches.

## CLI

Both implementations accept the same options:

```
swiko [--check | --emit-swift | --emit-kotlin] [--library] FILE
swiko [--check | --emit-swift | --emit-kotlin] [--library] --eval SOURCE
swiko [--check | --emit-swift | --emit-kotlin] [--library] -
swiko --help
swiko --version
```

Both frontends additionally accept `--utf8-args BASE64` as an ASCII-safe launcher
transport. Decode standard Base64 into strict UTF-8 NUL-terminated argument
records, then apply the ordinary CLI rules exactly once. The empty byte sequence
represents zero arguments. Maximum encoded characters: 1,500,000; maximum decoded
bytes: 1,100,000. Invalid transport is a usage error. This avoids Windows Java's
external ANSI argument conversion; it does not change the language or its limits.

Default mode runs the program. FILE is one UTF-8 .swk source file; `-` reads
UTF-8 standard input. `--check` produces no output on success. Emit modes write
generated UTF-8 source to stdout. `--library` is valid only with an emit mode.
`--` ends option parsing for a filename beginning with a hyphen. Duplicate or
conflicting modes, extra arguments, missing values, and unknown options are
usage errors. `--version` writes `Swiko 1.0.1` with a newline for both builds.
Statuses: 0 success/help/version, 1 language/source/runtime error, 2 CLI usage.
Errors have `filename:line:column: error: message` on stderr. Columns count
Unicode scalar values. Eval filename is `<eval>`; stdin filename is `<stdin>`.

## Resource and input rules

Validate UTF-8 strictly; permit one initial UTF-8 BOM and normalize CRLF and
bare CR to LF before lexing. Reject oversized input before reading all bytes.
Limits: source 1,048,576 UTF-8 bytes; tokens 100,000; string value 1,048,576
UTF-8 bytes; output 4,194,304 UTF-8 bytes; existing steps 100,000, call depth128,
parse nesting128, operator chains128, expression evaluation depth512.
Check string/output sizes before concatenating or appending. Buffered output
is written only after successful execution. Generated programs preserve
checked arithmetic, strict types, lazy operators, string/output limits,
100,000 execution steps and call-depth128. Generated programs count steps at
statement/function/loop boundaries, so the exact cutoff can differ from
interpreter evaluation-step counting. Generated code is not a security sandbox.

## Source generation

Both frontends emit both Swift and Kotlin from the checked AST. Emit real
target expressions, statements, and typed functions; do not embed an
interpreter or original .swk source as a substitute for compilation. Rename
all user symbols deterministically to avoid target keywords/collisions.
Kotlin Int maps to Long; Swift Int maps to Int64; Bool maps to Boolean/Bool;
Unit maps to Kotlin Unit and a Swift Unit helper value where needed; nullable
Unit must be represented correctly. The host entry point returns Unit/Void. Preserve
Int64 minimum, checked overflow, truncating division, modulo overflow,
Unicode-scalar-exact string equality, newline print, and lazy coalescing.
Runtime helpers may be emitted in the target language with source locations.

Standalone output has a platform main entry. Library output exposes public
`swikoRun()` returning captured output as String without printing, and
`swikoMain()` printing that output (both throw in Swift). It has no application
main and does not execute on import. Host entries are intentionally no-argument
for this core; `swikoMain` returns Unit/Void.
Runtime state belongs to each entry-point invocation so repeated library
calls do not share counters or buffered output.
Kotlin output must compile with Kotlin2.2.21/JDK21, Swift with Swift6.0.3.
Generated helper implementation is Swift or Kotlin only.

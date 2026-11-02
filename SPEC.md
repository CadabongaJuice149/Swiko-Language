# Swiko 1.0.1 language specification

This document defines the stable core language. Swift and Kotlin frontends
implement the same grammar, checking rules, interpreter semantics, and source
compiler targets. All project implementation, tooling, and test-runner source
uses Swift or Kotlin. Swiko programs use `.swk`.

## Feature origins

| Feature | Basis |
| --- | --- |
| `val`, `var`, `fun`, colon return types, `Unit`, `null` | Kotlin |
| 64-bit `Int`, `Bool`, newline-producing `print` | Swift |
| Nullable `T?`, type annotations, local inference | Swift and Kotlin |
| Lazy coalescing `??` | Swift |
| Braces, lexical scope, conditions, loops, returns | Swift and Kotlin |
| Checked integer arithmetic | Swift |
| Checking before execution and typed source compilation | Swift and Kotlin |

Swift/Kotlin source compatibility and complete feature parity are not goals
of this core release. Toolchains and operating-system runtimes have their own
dependencies beyond this project's two implementation languages.

## Source and lexical rules

Input is strict UTF-8 with an optional single initial BOM. CRLF and bare CR
normalize to LF. Identifiers contain ASCII letters or underscore followed by
ASCII letters, digits, or underscore. Unicode scalar values are supported in
strings. `//` comments continue to the next newline.

Statements end at a newline, semicolon, closing brace, or EOF. A compound
statement's closing brace also establishes a statement boundary. Newlines
inside parentheses are ignored. Other expressions remain on one line.
Blocks require braces. Conditions may be parenthesized. `else` may begin on
the next line, and `else if` chains are supported. A semicolon between `}`
and `else` is not permitted.

String literals use double quotes. Supported escapes are `\n`, `\r`, `\t`,
`\"`, and `\\`. Raw newlines and other escapes are rejected. String equality
compares exact Unicode scalar sequences, without normalization.

Integer literals contain decimal digits. A leading minus is unary negation,
with `-9223372036854775808` additionally recognized as the minimum Int literal.
Out-of-range literals fail before execution.

## Declarations and scope

```kotlin
val title: String = "Swiko"
var count = 0
var nickname: String? = null

fun greet(name: String): String {
    return "Hello, " + name
}

while count < 3 {
    print(greet(nickname ?? title))
    count = count + 1
}
```

`val` is immutable; `var` is mutable. Both need an initializer. A type may be
inferred except when the initializer has only the null-literal type. Inferred
bindings retain their checked type, including nullability, across assignments.

Every block creates lexical scope, including a standalone `{ ... }`. A function
body shares its declaration scope with its parameters. Duplicate names in one
scope are errors; inner scopes may shadow outer names. `print` is reserved.

Functions require types on every parameter and on the return value. Arguments
are positional, and parameters are immutable. Functions capture their lexical
environment. Self-recursion is supported; a declaration must otherwise precede
all references to it, including references in function bodies. Mutual recursion
through forward references is not supported. Functions can only be called by
name and cannot be stored as values or returned.

Top-level statements execute in order. A function named `main` is ordinary and
must be called explicitly. `return` is valid only inside a function. Bare
`return` produces Unit. Unit and Unit? functions may fall through with Unit.
All other functions must return on every path. An if returns on every path
only if both branches do; loops conservatively may execute zero times.
Unreachable statements are still checked.

## Static types

The complete program is parsed and checked before running or generating code.
All branches and function bodies are checked. There are four base types:
`Int`, `Bool`, `String`, and `Unit`, with nullable forms `T?`.

Int is signed 64-bit. Bool values are `true` and `false`. Unit is the value of
an operation with no meaningful result. `null` has an internal null-literal
type and is assignable only to nullable types. A nonnullable T can be assigned
to T?, but T? cannot be assigned to T. There are no implicit conversions
between different base types.

Assignments, function arguments, returns, conditions, and operators are checked
against these types. Arithmetic and ordering require nonnullable Int operands.
String addition requires two nonnullable Strings. Boolean operations and
conditions require nonnullable Bool. Assigning a nonnull value to a nullable
binding does not narrow its type; use `??` to obtain a nonnullable value.

Equality permits operands with the same base type, regardless of nullability,
or a comparison against a null literal. Equality of Unit values is supported.
Different nonnull base types cannot be compared.

`a ?? b` checks both operands but evaluates b only when a is null. With a
null-literal left operand, the result has b's type. Otherwise both operands
must have compatible base types; the right operand may also be null. The
result is nonnullable when either the left operand is nonnullable or the right
operand is nonnullable. Otherwise the result remains nullable.

## Operators and execution

Precedence from highest to lowest:

1. Named function calls.
2. Unary `!` and `-`.
3. `*`, `/`, `%`.
4. `+`, `-`.
5. `<`, `<=`, `>`, `>=`.
6. `==`, `!=`.
7. `&&`.
8. `||`.
9. `??`.

Binary operators associate left except `??`, which associates right. Assignment
is a statement. `&&`, `||`, and `??` are lazy. Other operands and positional
arguments evaluate left to right.

Integer overflow is an error. Division truncates toward zero. Division and
remainder by zero are errors, as are both minimum Int divided by -1 and minimum
Int remainder -1. String `+` concatenates without implicit number conversion.

`print(value)` accepts exactly one value, appends its representation and a
newline to a buffer, and returns Unit. Values render as decimal integers,
literal `true`/`false`, string contents, `null`, or `Unit`. Output is published
only after the program succeeds; a failed run discards it. CLI output and
diagnostics use UTF-8 on every operating system, regardless of the JVM's
default text encoding.

## Limits and errors

| Resource | Limit |
| --- | --- |
| Source UTF-8 bytes before normalization | 1,048,576 |
| Tokens | 100,000 |
| One string value in UTF-8 bytes | 1,048,576 |
| Buffered output in UTF-8 bytes, including newlines | 4,194,304 |
| Interpreter evaluation/execution steps | 100,000 |
| Nested function calls | 128 |
| Syntax nesting | 128 |
| Operators in a precedence chain | 128 |
| Nested interpreter expression evaluations | 512 |

Input, concatenation, and output limits are checked before excessive growth.
These guards do not form a hostile-code security sandbox.

Language, source-reading, and runtime errors use
`filename:line:column: error: message` on stderr with process status 1. Positions
start at one; columns count Unicode scalars. CLI usage errors use status 2;
successful actions use 0. Checking succeeds silently. Source generation writes
only target code and emits nothing for rejected input. See [COMPILER.md](COMPILER.md).

## Source compilation and embedding

Either frontend can emit Swift or Kotlin declarations, expressions, typed
functions, and control flow with target-language runtime helpers. A compiler
does not embed the interpreter or the original program as data. User symbols
are renamed to avoid host keywords and helper collisions.

Int maps to Swift Int64 or Kotlin Long. Bool and String use matching host
types. Swift may use a marker value for semantic Unit; nullable Unit is retained.
Checked arithmetic, lazy operators, exact string equality, output buffering,
and string/output limits are preserved.

Generated programs enforce 100,000 steps and call depth 128. They count steps
at statement/function/loop boundaries, so an exact cutoff may differ from the
interpreter's expression-inclusive count. Target compiler diagnostics and host
resource constraints also apply.

Standalone source includes a platform main. Library mode has no platform main
and does not execute on import. Public `swikoRun()` returns captured output as
String without printing; `swikoMain()` prints it and returns Unit/Void. Both
throw in Swift. Every call starts with fresh program state and counters.

## Scope beyond 1.0

Classes, collections, generics, protocols/interfaces, extensions, modules,
string interpolation, optional chaining, concurrency, framework bindings,
debugging tools, and package registries are not included. Future features must
derive from Swift or Kotlin. The 1.0 core has no file or network operations.

# Swiko 1.0.0

This release stabilizes the documented core and adds a complete local
build/test/package pipeline. It includes Swift and Kotlin interpreters, static
checking, both source compiler targets, host-library APIs, and source/JVM/Linux
distributions.

`BUILD-REPORT.md` records actual toolchains, validation results, and native
system-library requirements. Verify artifacts with SHA256SUMS. The release tool
checks extracted distributions and source rebuilding before publishing.

Static checking replaces the prototype's execution-only checks. Errors in
skipped code reject a program. Nullable operands require coalescing before
arithmetic, ordering, or boolean use. Non-Unit functions must return on all
paths, and function bodies cannot reference later declarations.

The CLI, source generators, and no-argument host interface are the verified
release scope. Classes, collections, modules, generics, concurrency, package
registries, a debugger, framework bindings, and mobile SDK distributions are
future work. Swift/Kotlin source compatibility is not claimed. Resource guards
do not make this a security sandbox.

The JVM package requires Java 17 or later. The native package targets the Linux
x86_64 host described in the build report; older glibc and untested platforms
are not claimed compatible.

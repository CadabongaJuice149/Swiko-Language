# Third-party components

Swiko source, release tooling, and tests use Swift and Kotlin without extra
application-library dependencies.

The JVM JAR bundles the Kotlin standard library. The Linux native executable
statically links Swift runtime and Foundation components. Their applicable
toolchain-provided license and notice files are included in their distributions.
The release builder copies them from the selected toolchain installations.

The native build also includes ICU 74.1 / Unicode 15.1 notices under
`licenses/icu/`, matching the verified Swift 6.0.3 toolchain headers. The original
upstream release notice and its provenance are retained there. Kotlin runtime
notices are embedded in the standalone interpreter JAR as well as included in
the JVM archive.

The Java runtime, target compilers, system C/C++ libraries, and operating system
are separate components with their own licenses. Compiler archives are not
redistributed in this release.

No open-source license has been selected for the Swiko project's original
source. Bundled component licenses do not grant rights to that original source
or replace the project owner's license choice. Select a project license before
offering the source under an open-source license.

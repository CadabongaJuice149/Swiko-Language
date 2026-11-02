# Third-party components

Swiko source, release tooling, and tests use Swift and Kotlin without extra
application-library dependencies.

All JVM packages and the standalone JAR bundle the Kotlin standard library.
Applicable Kotlin notices are embedded under `META-INF/licenses/kotlin` and
included separately in JVM archives.

The Linux native executable statically links Swift runtime and Foundation
components. macOS native builds use system runtime libraries. Windows native
packages redistribute matching Swift runtime DLLs next to the executable. Their
applicable notices accompany the distribution, using the selected toolchain's
license or the vendored matching Swift 6.0.3 license when its installer layout
does not expose that file. The Microsoft Visual C++ Redistributable remains a
separate Windows prerequisite; this package does not grant a license to
redistribute arbitrary Windows SDK or system DLLs.

The verified Linux Swift 6.0.3 toolchain embeds ICU 74.1 / Unicode 15.1.
`licenses/icu/` retains its upstream notice and provenance. That Linux evidence
does not establish another platform's ICU version. When Windows runtime DLLs
include ICU, provide their matching upstream notices through
`SWIKO_SWIFT_NOTICES_DIR`; the release tool requires those notices before
packaging. macOS system ICU is not redistributed. A shared source or
documentation archive may retain the Linux ICU notice without claiming that
every platform links that component.

The Java runtime, target compilers, system C/C++ libraries, and operating system
are separate components with their own licenses. Compiler archives are not
redistributed in this release.

No open-source license has been selected for the Swiko project's original
source. Bundled component licenses do not grant rights to that original source
or replace the project owner's license choice. Select a project license before
offering the source under an open-source license.

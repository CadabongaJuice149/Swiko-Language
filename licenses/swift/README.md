# Swift runtime notice provenance

`LICENSE.txt` is the unmodified license supplied in the official Swift 6.0.3
Linux toolchain at `usr/share/swift/LICENSE.txt`. The verified compiler archive
was downloaded from:

https://download.swift.org/swift-6.0.3-release/ubuntu2404/swift-6.0.3-RELEASE/swift-6.0.3-RELEASE-ubuntu24.04.tar.gz

Native build jobs select Swift 6.0.3. The release tool prefers a license from
the selected toolchain and uses this pinned notice when that installation
does not include the standard license path. This notice does not replace
additional component notices required when runtime DLLs are redistributed.

The Linux ICU 74.1 / Unicode 15.1 notice has separate provenance under
`licenses/icu`. Other runtime bundles must supply notices matching their own
components; the Linux ICU version is not assumed for macOS or Windows.

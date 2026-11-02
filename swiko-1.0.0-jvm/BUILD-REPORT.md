# Swiko 1.0.0 build and verification report

Command: `release`

All recorded commands completed successfully.

This archive report records compiler, ABI, and test gates completed before archive assembly. Package extraction and clean-source rebuild results appear in the final outer BUILD-REPORT.md.

```text
Kotlin compiler: info: kotlinc-jvm 2.2.21 (JRE 21.0.12.1+1-1-deb13u1-Debian)
```

```text
Swift compiler: Swift version 6.0.3 (swift-6.0.3-RELEASE)
Target: x86_64-unknown-linux-gnu
```

```text
JVM: 21.0.12.1 (Debian)
```

```text
Host: Linux amd64
```

```text
Native dependencies:
linux-vdso.so.1 (0x00007f37ee2bc000)
	libm.so.6 => /lib/x86_64-linux-gnu/libm.so.6 (0x00007f37eac30000)
	libstdc++.so.6 => /lib/x86_64-linux-gnu/libstdc++.so.6 (0x00007f37ea9ca000)
	libgcc_s.so.1 => /lib/x86_64-linux-gnu/libgcc_s.so.1 (0x00007f37ea99d000)
	libc.so.6 => /lib/x86_64-linux-gnu/libc.so.6 (0x00007f37ea7a9000)
	/lib64/ld-linux-x86-64.so.2 (0x00007f37ee2be000)
```

```text
Native ABI symbol versions: CXXABI_1.3, CXXABI_1.3.11, GLIBCXX_3.4, GLIBCXX_3.4.11, GLIBCXX_3.4.14, GLIBCXX_3.4.17, GLIBCXX_3.4.18, GLIBCXX_3.4.21, GLIBCXX_3.4.29, GLIBCXX_3.4.30, GLIBC_2.11, GLIBC_2.14, GLIBC_2.15, GLIBC_2.16, GLIBC_2.17, GLIBC_2.2.5, GLIBC_2.27, GLIBC_2.29, GLIBC_2.3, GLIBC_2.3.2, GLIBC_2.3.4, GLIBC_2.32, GLIBC_2.33, GLIBC_2.34, GLIBC_2.35, GLIBC_2.38, GLIBC_2.4, GLIBC_2.6, GLIBC_2.7, GLIBC_2.8, GLIBC_2.9
```

```text
Shared conformance: 162 passed, 0 failed (81 cases × 2 implementations)
```

```text
CLI conformance: 72 passed, 0 failed (CLI and input checks across 2 implementations)
```

```text
Compiler conformance: 34 passed, 0 failed (compiler and host-integration checks)
```

```text
Examples: 4 programs passed in both implementations
```

Native support is limited to the tested Linux x86_64 ABI. The JVM package requires JRE 17 or newer.
Checksums detect changes; they are not a publisher signature. Archive entries use fixed timestamps; universal bit-for-bit compiler reproducibility is not claimed.

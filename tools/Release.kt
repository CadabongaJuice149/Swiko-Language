import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.FileVisitResult
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import java.util.Comparator
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlin.io.path.*
import kotlin.system.exitProcess

/** Dependency-free build, verification, and release tooling, implemented only in Kotlin. */
private class ReleaseFailure(message: String) : RuntimeException(message)

private data class HostPlatform(val os: String, val arch: String) {
    val nativeName get() = "$os-$arch"
    val executableSuffix get() = if (os == "windows") ".exe" else ""
    fun requireNative() {
        if (os !in setOf("linux", "macos", "windows") || arch !in setOf("x86_64", "arm64") || (os == "windows" && arch != "x86_64")) {
            throw ReleaseFailure("Native builds support Linux/macOS x86_64 or arm64 and Windows x86_64; use --backend jvm on this host")
        }
    }
    companion object {
        fun detect(osName: String = System.getProperty("os.name"), architecture: String = System.getProperty("os.arch")): HostPlatform {
            val os = when {
                osName.lowercase().contains("windows") -> "windows"
                osName.lowercase().contains("mac") || osName.lowercase().contains("darwin") -> "macos"
                osName.lowercase().contains("linux") -> "linux"
                else -> "other"
            }
            val arch = when (architecture.lowercase()) {
                "amd64", "x86_64", "x64" -> "x86_64"
                "aarch64", "arm64" -> "arm64"
                else -> "other"
            }
            return HostPlatform(os, arch)
        }
    }
}

private class Release(private val root: Path, private val command: String, private val backend: String) {
    private val build = root.resolve("build")
    private val version = root.resolve("VERSION").readText().trim().also {
        if (!it.matches(Regex("[0-9]+\\.[0-9]+\\.[0-9]+(?:-[0-9A-Za-z.-]+)?"))) {
            throw ReleaseFailure("VERSION must contain a semantic version")
        }
    }
    private val base = "swiko-$version"
    private val host = HostPlatform.detect()
    private val native = backend == "all"
    private val nativeFile get() = "swiko-swift${host.executableSuffix}"
    private val java = javaExecutable()
    private val kotlinc = executable(System.getenv("SWIKO_KOTLINC") ?: "kotlinc")
    private val swiftc by lazy { executable(System.getenv("SWIKO_SWIFTC") ?: "swiftc") }
    private lateinit var stage: Path
    private lateinit var project: Path
    private var logIndex = 0
    private var preparedRelease: Path? = null
    private val evidence = mutableListOf<String>()
    private val windowsDlls = linkedMapOf<String, Path>()
    private val outputNames = mutableListOf("swiko.jar", "conformance.jar")

    fun run() {
        requireProject()
        build.createDirectories()
        FileChannel.open(build.resolve(".release.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE).use { channel ->
            val lock = try { channel.tryLock() } catch (_: Exception) { null }
                ?: throw ReleaseFailure("Another release-tool process is using this project")
            lock.use {
                stage = Files.createTempDirectory(build, ".release-stage-")
                try {
                    val sourceBefore = sourceManifest()
                    project = stage.resolve("project").createDirectories()
                    for (file in sourceFiles()) copy(file, project.resolve(root.relativize(file)))
                    if (sourceBefore != sourceManifest(project)) throw ReleaseFailure("Source changed while taking the build snapshot")
                    build()
                    if (command != "build") test()
                    if (command == "release") {
                        if (sourceBefore != sourceManifest()) throw ReleaseFailure("Project source changed during the build; retry after edits finish")
                        packageRelease(sourceBefore)
                    }
                    publishBuild()
                    preparedRelease?.let { release ->
                        val destination = root.resolve("dist/$base")
                        publishDirectory(release, destination)
                        println("Release artifacts: $destination")
                    }
                    println("${command.replaceFirstChar { it.uppercase() }} succeeded for Swiko $version")
                } finally {
                    cleanupBestEffort(stage)
                }
            }
        }
    }

    private fun requireProject() {
        for (file in listOf("kotlin/Swiko.kt", "swift/Swiko.swift", "kotlin/Conformance.kt", "README.md", "SPEC.md", "VERSION")) {
            if (!root.resolve(file).isRegularFile()) throw ReleaseFailure("Missing project file: $file")
        }
        if (native) host.requireNative()
    }

    private fun build() {
        println("Building Swiko $version from source")
        val kotlinVersion = run("Kotlin version", listOf(kotlinc.toString(), "-version"), 30)
        evidence += "Kotlin compiler: ${kotlinVersion.trim()}"
        evidence += "Backend: $backend; host platform: ${host.nativeName}"
        if (native) evidence += "Swift compiler: ${run("Swift version", listOf(swiftc.toString(), "--version"), 30).trim()}"
        evidence += "JVM: ${System.getProperty("java.version")} (${System.getProperty("java.vendor")})"
        evidence += "Host: ${System.getProperty("os.name")} ${System.getProperty("os.arch")}"
        compileKotlin(project.resolve("kotlin/Swiko.kt"), stage.resolve("swiko.jar"), "Kotlin interpreter")
        if (native) {
            val flags = when (host.os) {
                "linux" -> listOf("-static-stdlib")
                "macos" -> listOf("-target", "${if (host.arch == "arm64") "arm64" else "x86_64"}-apple-macosx13.0")
                else -> emptyList()
            }
            run("Swift interpreter", listOf(swiftc.toString(), "-O") + flags + listOf("-module-cache-path",
                stage.resolve("swift-module-cache").toString(), project.resolve("swift/Swiko.swift").toString(),
                "-o", stage.resolve(nativeFile).toString()), 300)
            outputNames += nativeFile
            validateNativeHeader(stage.resolve(nativeFile), host)
            if (host.os == "windows") bundleWindowsRuntime(stage.resolve(nativeFile), stage)
        }
        compileKotlin(project.resolve("kotlin/Conformance.kt"), stage.resolve("conformance.jar"), "Conformance runner")
        for ((sourceName, artifactName, label) in listOf(
            Triple("CliConformance.kt", "cli-conformance.jar", "CLI test runner"),
            Triple("CompilerConformance.kt", "compiler-conformance.jar", "Compiler test runner")
        )) {
            if (!native && sourceName == "CompilerConformance.kt") continue
            val source = project.resolve("kotlin/$sourceName")
            if (!source.isRegularFile()) throw ReleaseFailure("Missing release test runner: kotlin/$sourceName")
            compileKotlin(source, stage.resolve(artifactName), label)
            outputNames += artifactName
        }
        verifyVersion(listOf(java, "-jar", stage.resolve("swiko.jar").toString()), "Kotlin version smoke")
        if (!native) {
            evidence += "Swift frontend and target compiler integration: not run (--backend jvm); no Swift compiler required"
            return
        }
        verifyNativeVersion(stage.resolve(nativeFile), "Swift version smoke")
        if (host.os == "macos") {
            val dynamic = run("Native dependencies", listOf("otool", "-L", stage.resolve(nativeFile).toString()), 30)
            val dependencies = dynamic.lineSequence().drop(1).map { it.trim().substringBefore(" (") }.filter { it.isNotBlank() }.toList()
            if (dependencies.any { !it.startsWith("/usr/lib/") && !it.startsWith("/System/Library/") }) {
                throw ReleaseFailure("macOS executable depends on a non-system library; bundle it before releasing:\n$dynamic")
            }
            evidence += "Native dependencies:\n${dynamic.trim()}"
            evidence += "macOS deployment target: 13.0; execution verified on build host only"
            return
        }
        if (host.os == "windows") return
        val dynamic = run("Native dependencies", listOf("ldd", stage.resolve(nativeFile).toString()), 30)
        if (dynamic.contains("not found") || dynamic.contains(swiftc.parent.parent.toString()) || dynamic.contains("libswift") || dynamic.contains("libFoundation")) {
            throw ReleaseFailure("Native executable depends on missing or toolchain runtime libraries")
        }
        val elf = run("Native dynamic metadata", listOf("readelf", "-d", stage.resolve(nativeFile).toString()), 30)
        if (elf.contains("(RPATH)") || elf.contains("(RUNPATH)")) throw ReleaseFailure("Native executable contains a runtime search path")
        val symbols = run("Native ABI metadata", listOf("readelf", "--version-info", stage.resolve(nativeFile).toString()), 30)
        val abi = Regex("(?:GLIBC|GLIBCXX|CXXABI)_[0-9.]+").findAll(symbols).map { it.value }.distinct().sorted().toList()
        evidence += "Native dependencies:\n${dynamic.trim()}"
        evidence += "Native ABI symbol versions: ${abi.joinToString(", ")}"
    }

    private fun compileKotlin(source: Path, target: Path, label: String) {
        run(label, listOf(kotlinc.toString(), source.toString(), "-jvm-target", "17", "-include-runtime", "-d", target.toString()), 180)
        normalizeJar(target)
    }

    private fun test() {
        println("Running shared language conformance tests")
        val result = run("Shared conformance", listOf(java, "-jar", stage.resolve("conformance.jar").toString(),
            stage.resolve("swiko.jar").toString(), if (native) stage.resolve(nativeFile).toString() else "--jvm-only", project.resolve("tests/cases").toString()), 600)
        evidence += "Shared conformance: ${result.trim()}"
        for ((artifact, label) in listOf("cli-conformance.jar" to "CLI conformance", "compiler-conformance.jar" to "Compiler conformance")) {
            if (!native && artifact == "compiler-conformance.jar") continue
            val result = run(label, listOf(java, "-jar", stage.resolve(artifact).toString(),
                stage.resolve("swiko.jar").toString(), if (native) stage.resolve(nativeFile).toString() else "--jvm-only", project.toString()), if (artifact == "compiler-conformance.jar") 1200 else 600)
            evidence += "$label: ${result.trim()}"
        }
        val examples = project.resolve("examples").listDirectoryEntries("*.swk").sortedBy { it.name }
        for (example in examples) {
            val kotlinOutput = run("Kotlin example ${example.name}", listOf(java, "-jar", stage.resolve("swiko.jar").toString(), example.toString()), 30)
            if (native) {
                val swiftOutput = runNative("Swift example ${example.name}", stage.resolve(nativeFile), listOf(example.toString()))
                if (kotlinOutput != swiftOutput) throw ReleaseFailure("Example ${example.name} differs between implementations")
            }
        }
        evidence += "Examples: ${examples.size} programs passed in ${if (native) "both implementations" else "the JVM implementation"}"
    }

    private fun packageRelease(manifest: String) {
        println("Creating and testing release packages")
        val release = stage.resolve("release").createDirectories()
        val assembly = stage.resolve("packages").createDirectories()
        val sourceRoot = assembly.resolve("$base-source").createDirectories()
        for (source in sourceFiles(project)) copy(source, sourceRoot.resolve(project.relativize(source)))
        val sourceZip = release.resolve("$base-source.zip")
        zipDirectory(sourceRoot, sourceZip, "$base-source")
        copy(stage.resolve("swiko.jar"), release.resolve("$base.jar"))

        val jvmRoot = assembly.resolve("$base-jvm").createDirectories()
        copyDocs(jvmRoot)
        copy(stage.resolve("swiko.jar"), jvmRoot.resolve("swiko.jar"))
        copyRuntimeNotices(jvmRoot, includeKotlin = true, includeSwift = false)
        jvmRoot.resolve("BUILD-REPORT.md").writeText(report(packagedSnapshot = true))
        val jvmZip = release.resolve("$base-jvm.zip")
        zipDirectory(jvmRoot, jvmZip, "$base-jvm")
        for (platform in listOf("macos", "windows", "linux")) {
            val name = "$base-$platform-jvm"
            val platformRoot = assembly.resolve(name).createDirectories()
            copyDirectory(jvmRoot, platformRoot)
            platformRoot.resolve("INSTALL.md").writeText(jvmInstall(platform, version, host.nativeName))
            val archive = release.resolve("$name.zip")
            zipDirectory(platformRoot, archive, name)
        }

        val nativeRoot = if (native) assembly.resolve("$base-${host.nativeName}").createDirectories() else null
        val nativeArchive = if (native) release.resolve("$base-${host.nativeName}.${if (host.os == "linux") "tar.gz" else "zip"}") else null
        if (nativeRoot != null && nativeArchive != null) {
            copyDocs(nativeRoot)
            copy(stage.resolve(nativeFile), nativeRoot.resolve("bin/swiko${host.executableSuffix}"))
            for ((name, dll) in windowsDlls) copy(dll, nativeRoot.resolve("bin/$name"))
            copyRuntimeNotices(nativeRoot, includeKotlin = false, includeSwift = true)
            nativeRoot.resolve("BUILD-REPORT.md").writeText(report(packagedSnapshot = true))
            nativeRoot.resolve("INSTALL.md").writeText(nativeInstall(host, version))
            if (host.os != "windows") setPermissions(nativeRoot)
            when (host.os) {
                "linux" -> run("Native archive", listOf("tar", "--sort=name", "--mtime=@0", "--owner=0", "--group=0", "--numeric-owner",
                    "-czf", nativeArchive.toString(), "-C", assembly.toString(), nativeRoot.name), 120)
                "macos" -> run("Native archive", listOf("ditto", "-c", "-k", "--keepParent", nativeRoot.toString(), nativeArchive.toString()), 120)
                "windows" -> zipDirectory(nativeRoot, nativeArchive, nativeRoot.name)
            }
        }

        val unpack = stage.resolve("unpacked").createDirectories()
        unzip(jvmZip, unpack)
        val unpackedJvm = unpack.resolve("$base-jvm")
        verifyVersion(listOf(java, "-jar", unpackedJvm.resolve("swiko.jar").toString()), "Packaged JVM version")
        val jvmHello = run("Packaged JVM example", listOf(java, "-jar", unpackedJvm.resolve("swiko.jar").toString(), unpackedJvm.resolve("examples/hello.swk").toString()), 30)
        for (platform in listOf("macos", "windows", "linux")) {
            val name = "$base-$platform-jvm"
            unzip(release.resolve("$name.zip"), unpack)
            val packageJar = unpack.resolve("$name/swiko.jar")
            if (sha256(packageJar) != sha256(stage.resolve("swiko.jar"))) throw ReleaseFailure("$platform JVM package changed the verified interpreter JAR")
            verifyVersion(listOf(java, "-jar", packageJar.toString()), "Packaged $platform JVM version on ${host.nativeName}")
            val hello = run("Packaged $platform JVM example on ${host.nativeName}", listOf(java, "-jar", packageJar.toString(), unpack.resolve("$name/examples/hello.swk").toString()), 30)
            if (hello != jvmHello) throw ReleaseFailure("$platform JVM archive does not match the verified JVM output")
        }
        if (nativeRoot != null && nativeArchive != null) {
            when (host.os) {
                "linux" -> run("Extract native archive", listOf("tar", "-xzf", nativeArchive.toString(), "-C", unpack.toString()), 60)
                "macos" -> run("Extract native archive", listOf("ditto", "-x", "-k", nativeArchive.toString(), unpack.toString()), 60)
                "windows" -> unzip(nativeArchive, unpack)
            }
            val unpackedNative = unpack.resolve(nativeRoot.name)
            val executable = unpackedNative.resolve("bin/swiko${host.executableSuffix}")
            if (!Files.isExecutable(executable)) throw ReleaseFailure("Native archive did not preserve executable permissions")
            validateNativeHeader(executable, host)
            verifyNativeVersion(executable, "Packaged native version")
            val nativeHello = runNative("Packaged native example", executable, listOf(unpackedNative.resolve("examples/hello.swk").toString()))
            if (jvmHello != nativeHello) throw ReleaseFailure("Extracted packages do not produce matching example output")
        }
        unzip(sourceZip, unpack)
        val unpackedSource = unpack.resolve("$base-source")
        if (unpackedSource.resolve("VERSION").readText().trim() != version) throw ReleaseFailure("Source archive VERSION mismatch")
        val clean = stage.resolve("source-rebuild").createDirectories()
        compileKotlin(unpackedSource.resolve("tools/Release.kt"), clean.resolve("release-tool.jar"), "Extracted source build-tool bootstrap")
        run("Extracted source clean build", listOf(java, "-jar", clean.resolve("release-tool.jar").toString(),
            "build", "--root", unpackedSource.toString(), "--backend", backend), 900)
        verifyVersion(listOf(java, "-jar", unpackedSource.resolve("build/swiko.jar").toString()), "Extracted source Kotlin version")
        if (native) verifyNativeVersion(unpackedSource.resolve("build/$nativeFile"), "Extracted source Swift version")
        evidence += "Packaging: source, generic JVM and macOS/Windows/Linux JVM ZIPs created and extracted successfully${if (native) "; ${host.nativeName} native archive also verified" else ""}"
        evidence += "JVM platform archives contain the identical verified JAR; execution smoke tests ran on ${host.nativeName}, not on other operating systems"
        evidence += "Package smoke tests: distributed executables passed --version and hello example on this host"
        evidence += "Clean source archive rebuild: build tool bootstrapped with --backend $backend; selected interpreters/test runners compiled and passed --version"
        if (manifest != sourceManifest()) throw ReleaseFailure("Project source changed during packaging; retry after edits finish")
        release.resolve("SOURCE-SHA256SUMS").writeText(manifest)
        release.resolve("BUILD-REPORT.md").writeText(report())
        val sums = release.listDirectoryEntries().sortedBy { it.name }.joinToString("") { "${sha256(it)}  ${it.name}\n" }
        release.resolve("SHA256SUMS").writeText(sums)
        verifyChecksums(release)
        preparedRelease = release
    }

    private fun copyDocs(target: Path) {
        for (file in sourceFiles(project)) {
            val relative = project.relativize(file)
            val top = relative.getName(0).toString()
            if ((relative.nameCount == 1 && (file.extension in setOf("md", "txt") || file.name in setOf("VERSION", "LICENSE", "NOTICE"))) || top in setOf("examples", "licenses", "docs")) {
                copy(file, target.resolve(relative))
            }
        }
    }

    private fun copyRuntimeNotices(target: Path, includeKotlin: Boolean, includeSwift: Boolean) {
        if (includeKotlin) {
            val licenseRoot = kotlinc.toRealPath().parent.parent.resolve("license")
            if (!licenseRoot.resolve("LICENSE.txt").isRegularFile()) throw ReleaseFailure("Kotlin distribution license directory not found: $licenseRoot")
            Files.walk(licenseRoot).use { paths ->
                paths.filter { Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS) }.forEach { copy(it, target.resolve("licenses/kotlin").resolve(licenseRoot.relativize(it))) }
            }
        }
        if (includeSwift) {
            val license = listOf(swiftc.toRealPath().parent.parent.resolve("share/swift/LICENSE.txt"), project.resolve("licenses/swift/LICENSE.txt"))
                .firstOrNull { it.isRegularFile() } ?: throw ReleaseFailure("Swift notice missing; supply matching toolchain notice at licenses/swift/LICENSE.txt")
            copy(license, target.resolve("licenses/swift/LICENSE.txt"))
            if (host.os == "linux" && !target.resolve("licenses/icu/LICENSE").isRegularFile()) {
                throw ReleaseFailure("Native distribution requires the matching ICU notice at licenses/icu/LICENSE")
            }
            System.getenv("SWIKO_SWIFT_NOTICES_DIR")?.takeIf { it.isNotBlank() }?.let {
                val notices = Path.of(it).toAbsolutePath().normalize()
                if (!notices.isDirectory()) throw ReleaseFailure("SWIKO_SWIFT_NOTICES_DIR is not a directory")
                copyDirectory(notices, target.resolve("licenses/swift-runtime"))
            }
        }
    }

    private fun verifyVersion(prefix: List<String>, label: String) {
        val output = run(label, prefix + "--version", 30)
        if (output.replace("\r\n", "\n") != "Swiko $version\n") throw ReleaseFailure("$label did not report exactly Swiko $version")
    }

    private fun publishBuild() {
        for (name in outputNames) {
            val from = stage.resolve(name)
            val to = build.resolve(name)
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        }
        build.resolve("BUILD-REPORT.md").writeText(report())
    }

    private fun report(packagedSnapshot: Boolean = false) = buildString {
        append("# Swiko $version build and verification report\n\n")
        append("Command: `$command --backend $backend`\n\n")
        append("All recorded commands completed successfully.\n\n")
        if (packagedSnapshot) append("This archive report records compiler, ABI, and test gates completed before archive assembly. Package extraction and clean-source rebuild results appear in the final outer BUILD-REPORT.md.\n\n")
        for (entry in evidence) append("```text\n${entry.trim()}\n```\n\n")
        append("Native execution is verified only on the actual build host ${host.nativeName}${if (native) "" else "; native builds were not requested"}. JVM packages require Java 17 or newer.\n")
        append("macOS and Windows JVM archives contain the same platform-neutral JAR; packaging them on Linux does not claim execution tests on those operating systems.\n")
        if (host.os == "macos" && native) append("Native macOS deployment target is 13.0. Code signing/notarization requires the distributor's Apple credentials and is not performed by this tool.\n")
        if (host.os == "windows" && native) append("Windows native runtime DLLs are app-local; Windows system libraries and Visual C++ 2015–2022 x64 Redistributable remain prerequisites.\n")
        append("Checksums detect changes; they are not a publisher signature. Archive entries use fixed timestamps; universal bit-for-bit compiler reproducibility is not claimed.\n")
    }

    private fun verifyNativeVersion(executable: Path, label: String) {
        val output = runNative(label, executable, listOf("--version"))
        if (output.replace("\r\n", "\n") != "Swiko $version\n") throw ReleaseFailure("$label did not report exactly Swiko $version")
    }

    private fun runNative(label: String, executable: Path, arguments: List<String>): String =
        run(label, listOf(executable.toString()) + arguments, 30, cleanNativeEnvironment = true)

    private fun bundleWindowsRuntime(executable: Path, destination: Path) {
        val info = run("Swift runtime paths", listOf(swiftc.toString(), "-print-target-info"), 30)
        val directories = linkedSetOf<Path>()
        directories.add(swiftc.toRealPath().parent)
        directories.add(swiftc.toRealPath().parent.parent.resolve("lib/swift"))
        (System.getenv("SWIKO_SWIFT_RUNTIME_DIR") ?: System.getenv("SWIKO_SWIFT_RUNTIME_DIRS"))?.split(File.pathSeparator)?.filter { it.isNotBlank() }?.forEach {
            directories.add(Path.of(it).toAbsolutePath().normalize())
        }
        System.getenv("SDKROOT")?.takeIf { it.isNotBlank() }?.let {
            directories.add(Path.of(it).resolve("usr/bin"))
            directories.add(Path.of(it).resolve("usr/lib/swift"))
        }
        var ancestor: Path? = swiftc.toRealPath().parent
        repeat(6) {
            ancestor?.let {
                directories.add(it.resolve("Platforms/Windows.platform/Developer/SDKs/Windows.sdk/usr/bin"))
                ancestor = it.parent
            }
        }
        Regex("\"runtimeLibraryPaths\"\\s*:\\s*\\[([^]]*)]").find(info)?.groupValues?.get(1)?.let { array ->
            for (match in Regex("\"((?:\\\\.|[^\"\\\\])*)\"").findAll(array)) {
                directories.add(Path.of(match.groupValues[1].replace("\\\\", "\\").replace("\\/", "/")))
            }
        }
        val dllCandidates = linkedMapOf<String, Path>()
        for (directory in directories.filter { it.isDirectory() }) {
            Files.walk(directory, 5).use { paths -> paths.filter { it.isRegularFile() && it.extension.equals("dll", true) }.forEach {
                dllCandidates.putIfAbsent(it.name.lowercase(), it)
            } }
        }
        for (directory in (System.getenv("PATH") ?: "").split(File.pathSeparator)) {
            val path = Path.of(directory.ifEmpty { "." })
            if (path.isDirectory()) Files.newDirectoryStream(path, "*.dll").use { paths -> paths.forEach {
                if (it.isRegularFile()) dllCandidates.putIfAbsent(it.name.lowercase(), it)
            } }
        }
        val system = Path.of(System.getenv("SystemRoot") ?: "C:\\Windows", "System32")
        val queue = ArrayDeque<Path>()
        val seen = mutableSetOf<String>()
        val systemDependencies = sortedSetOf<String>()
        queue.add(executable)
        while (queue.isNotEmpty()) {
            for (name in peImports(queue.removeFirst())) {
                val lower = name.lowercase()
                if (!seen.add(lower)) continue
                if (lower.startsWith("api-ms-") || lower.startsWith("ext-ms-")) { systemDependencies += name; continue }
                val swiftRuntime = lower.contains("swift") || lower.contains("foundation") || lower.startsWith("icu") || lower.contains("dispatch") || lower.contains("blocksruntime")
                val candidate = dllCandidates[lower]
                if (!swiftRuntime && system.resolve(name).isRegularFile()) { systemDependencies += name; continue }
                if (candidate == null) throw ReleaseFailure("Required Windows DLL not found: $name; set SWIKO_SWIFT_RUNTIME_DIR to the matching Swift runtime directories")
                val copied = destination.resolve(candidate.name)
                copy(candidate, copied)
                validateNativeHeader(copied, host)
                windowsDlls[candidate.name] = copied
                if (candidate.name !in outputNames) outputNames += candidate.name
                queue.add(copied)
            }
        }
        if (windowsDlls.isEmpty()) throw ReleaseFailure("Windows executable did not identify any app-local Swift runtime DLLs")
        if (windowsDlls.keys.any { it.contains("icu", true) } && System.getenv("SWIKO_SWIFT_NOTICES_DIR").isNullOrBlank()) {
            throw ReleaseFailure("Windows ICU DLLs require matching upstream runtime notices; set SWIKO_SWIFT_NOTICES_DIR (Linux ICU provenance cannot be assumed)")
        }
        evidence += "Windows app-local runtime DLLs:\n" + windowsDlls.entries.joinToString("\n") { "${it.key}  ${sha256(it.value)}" }
        evidence += "Windows system/redistributable dependencies: ${systemDependencies.joinToString(", ")}"
    }

    private fun run(label: String, arguments: List<String>, seconds: Long, cleanNativeEnvironment: Boolean = false): String {
        println("  $label")
        val logs = stage.resolve("logs").createDirectories()
        val log = logs.resolve("${logIndex++}.log")
        val process = try {
            val builder = ProcessBuilder(compilerCommand(arguments)).directory(project.toFile()).redirectErrorStream(true).redirectOutput(log.toFile())
            if (cleanNativeEnvironment) {
                builder.environment().remove("LD_LIBRARY_PATH")
                builder.environment().remove("DYLD_LIBRARY_PATH")
                builder.environment().remove("DYLD_FALLBACK_LIBRARY_PATH")
                if (host.os == "windows") {
                    val windows = System.getenv("SystemRoot") ?: "C:\\Windows"
                    builder.environment().keys.filter { it.equals("PATH", ignoreCase = true) }.toList().forEach { builder.environment().remove(it) }
                    builder.environment()["PATH"] = "$windows\\System32;$windows"
                }
            }
            builder.start()
        } catch (error: Exception) {
            throw ReleaseFailure("$label could not start: ${error.message}")
        }
        if (!process.waitFor(seconds, TimeUnit.SECONDS)) {
            process.toHandle().descendants().forEach { it.destroyForcibly() }
            process.destroyForcibly()
            process.waitFor(5, TimeUnit.SECONDS)
            throw ReleaseFailure("$label exceeded ${seconds}s timeout")
        }
        val output = if (log.fileSize() <= 4L * 1024 * 1024) log.readText() else "[output exceeded 4 MiB; omitted]"
        if (process.exitValue() != 0) throw ReleaseFailure("$label failed (exit ${process.exitValue()}):\n$output")
        if (label.contains("conformance", true) && output.isNotBlank()) println(output.trim())
        return output
    }

    private fun sourceFiles(directory: Path = root): List<Path> = releaseSourceFiles(directory)

    private fun sourceManifest(directory: Path = root) = sourceFiles(directory).joinToString("") { "${sha256(it)}  ${directory.relativize(it).toString().replace('\\', '/')}\n" }

    private fun normalizeJar(jar: Path) {
        val normalized = jar.resolveSibling("${jar.name}.normalized")
        val licenseRoot = kotlinc.toRealPath().parent.parent.resolve("license")
        if (!licenseRoot.resolve("LICENSE.txt").isRegularFile()) throw ReleaseFailure("Kotlin distribution license directory not found: $licenseRoot")
        val notices = Files.walk(licenseRoot).use { paths ->
            paths.filter { Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS) }.toList().associateBy {
                "META-INF/licenses/kotlin/${licenseRoot.relativize(it).toString().replace('\\', '/')}"
            }
        }
        ZipFile(jar.toFile()).use { source ->
            ZipOutputStream(Files.newOutputStream(normalized)).use { output ->
                val original = source.entries().asSequence().associateBy { it.name }
                val names = (original.keys + notices.keys).sortedWith(compareBy<String> { if (it == "META-INF/MANIFEST.MF") 0 else 1 }.thenBy { it })
                for (name in names) {
                    val clean = ZipEntry(name).apply { time = 0L }
                    output.putNextEntry(clean)
                    val notice = notices[name]
                    if (notice != null) Files.newInputStream(notice).use { it.copyTo(output) }
                    else if (!original.getValue(name).isDirectory) source.getInputStream(original.getValue(name)).use { it.copyTo(output) }
                    output.closeEntry()
                }
            }
        }
        Files.move(normalized, jar, StandardCopyOption.REPLACE_EXISTING)
    }

    private fun zipDirectory(directory: Path, archive: Path, prefix: String) {
        ZipOutputStream(Files.newOutputStream(archive)).use { output ->
            val files = Files.walk(directory).use { paths -> paths.filter { Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS) }.sorted(compareBy { directory.relativize(it).toString() }).toList() }
            for (file in files) {
                val entry = ZipEntry("$prefix/${directory.relativize(file).toString().replace('\\', '/')}").apply { time = 0L }
                output.putNextEntry(entry)
                Files.newInputStream(file).use { it.copyTo(output) }
                output.closeEntry()
            }
        }
    }

    private fun unzip(archive: Path, destination: Path) {
        ZipFile(archive.toFile()).use { zip ->
            for (entry in zip.entries()) {
                val target = destination.resolve(entry.name).normalize()
                if (!target.startsWith(destination)) throw ReleaseFailure("Unsafe archive entry: ${entry.name}")
                if (entry.isDirectory) target.createDirectories() else {
                    target.parent.createDirectories()
                    zip.getInputStream(entry).use { input -> Files.newOutputStream(target).use { input.copyTo(it) } }
                }
            }
        }
    }

    private fun verifyChecksums(directory: Path) {
        for (line in directory.resolve("SHA256SUMS").readLines()) {
            val fields = line.split("  ", limit = 2)
            if (fields.size != 2 || sha256(directory.resolve(fields[1])) != fields[0]) throw ReleaseFailure("Release checksum verification failed")
        }
    }

    private fun setPermissions(directory: Path) {
        Files.walk(directory).use { paths -> paths.forEach { file ->
            val mode = if (file.isDirectory() || file == directory.resolve("bin/swiko")) "rwxr-xr-x" else "rw-r--r--"
            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString(mode))
        } }
    }
}

private fun executable(name: String): Path {
    if (name.contains('/') || name.contains('\\')) {
        val path = Path.of(name).toAbsolutePath().normalize()
        if (!Files.isExecutable(path)) throw ReleaseFailure("Compiler executable not found: $path")
        return path
    }
    val windows = HostPlatform.detect().os == "windows"
    val names = if (windows && !name.contains('.')) listOf(name, "$name.exe", "$name.bat", "$name.cmd") else listOf(name)
    for (directory in (System.getenv("PATH") ?: "").split(File.pathSeparator)) {
        for (candidateName in names) {
            val candidate = Path.of(directory.ifEmpty { "." }, candidateName).toAbsolutePath().normalize()
            if (Files.isExecutable(candidate) && candidate.isRegularFile()) return candidate
        }
    }
    throw ReleaseFailure("Compiler '$name' not found; set SWIKO_KOTLINC and SWIKO_SWIFTC to compiler paths")
}

private fun javaExecutable(): String = Path.of(System.getProperty("java.home"), "bin",
    if (HostPlatform.detect().os == "windows") "java.exe" else "java").toString()

/** Batch wrappers are not executable images; invoke the bundled compiler main without a command shell. */
private fun compilerCommand(arguments: List<String>): List<String> {
    if (HostPlatform.detect().os != "windows" || arguments.isEmpty() || Path.of(arguments.first()).extension.lowercase() !in setOf("bat", "cmd")) return arguments
    val home = Path.of(arguments.first()).toAbsolutePath().normalize().parent.parent
    if (!home.resolve("lib/kotlin-compiler.jar").isRegularFile()) throw ReleaseFailure("Kotlin batch selector must point inside an official compiler distribution")
    return listOf(javaExecutable(), "-Dfile.encoding=UTF-8", "-Dkotlin.home=$home", "-cp", home.resolve("lib").toString() + File.separator + "*",
        "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler") + arguments.drop(1)
}

private fun copyDirectory(source: Path, destination: Path) {
    Files.walk(source).use { paths -> paths.filter { Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS) }.forEach {
        copy(it, destination.resolve(source.relativize(it)))
    } }
}

private fun jvmInstall(platform: String, version: String, verifiedHost: String): String = """
# Swiko $version for ${if (platform == "macos") "macOS (Apple Silicon and Intel)" else platform.replaceFirstChar { it.uppercase() }} — JVM distribution

Install Java 17 or later for your operating system and CPU, extract this ZIP,
open ${if (platform == "windows") "Windows Terminal or Command Prompt" else "Terminal"} in this directory, and run:

```text
java -jar swiko.jar --version
java -jar swiko.jar examples/hello.swk
java -jar swiko.jar --check examples/app.swk
```

This is the platform-neutral Kotlin/JVM implementation, not a native executable
or an installer. It provides the same Swiko language, checker, and Swift/Kotlin
source generators on each operating system. This archive contains the exact
verified JAR from the generic JVM package. Packaging and extraction smoke tests
were executed on $verifiedHost; this does not claim an execution test on another
operating system. Native artifacts are built only by their matching OS runners.
See BUILD-REPORT.md, README.md, and the included licenses for details.
""".trimIndent() + "\n"

private fun nativeInstall(host: HostPlatform, version: String): String = """
# Swiko $version — native ${host.nativeName}

Extract this archive, open a terminal in its directory, and run:

```text
${if (host.os == "windows") ".\\bin\\swiko.exe" else "./bin/swiko"} --version
${if (host.os == "windows") ".\\bin\\swiko.exe" else "./bin/swiko"} examples/hello.swk
```

${when (host.os) {
    "linux" -> "The Swift standard library is statically linked. System C/C++ libraries and the ABI versions listed in BUILD-REPORT.md remain required."
    "macos" -> "Requires macOS 13 or later and this archive's CPU architecture. This build uses the operating system's Swift runtime and system libraries. The executable is unsigned and not notarized; platform distribution signing requires the owner's Apple credentials."
    else -> "Keep all files in bin together: the Swift runtime DLLs are app-local. Requires Windows x86_64 and the Microsoft Visual C++ 2015–2022 x64 Redistributable. BUILD-REPORT.md records the inspected DLL dependencies. This executable is unsigned."
}}

This native executable was built, architecture-checked, and run on the matching
${host.nativeName} host. No binary for another OS was produced by renaming it.
See BUILD-REPORT.md for exact host and toolchain evidence.
""".trimIndent() + "\n"

private fun validateNativeHeader(file: Path, host: HostPlatform) {
    val header = Files.newInputStream(file).use { it.readNBytes(4096) }
    fun u16(offset: Int): Int {
        if (offset < 0 || offset + 2 > header.size) throw ReleaseFailure("Truncated native executable header: $file")
        return (header[offset].toInt() and 255) or ((header[offset + 1].toInt() and 255) shl 8)
    }
    fun u32(offset: Int): Long = u16(offset).toLong() or (u16(offset + 2).toLong() shl 16)
    val expected = when (host.os) {
        "linux" -> {
            if (header.size < 20 || header[0] != 0x7f.toByte() || header[1] != 'E'.code.toByte() || header[2] != 'L'.code.toByte() || header[3] != 'F'.code.toByte() || header[4] != 2.toByte() || header[5] != 1.toByte()) throw ReleaseFailure("Native artifact is not a little-endian 64-bit ELF executable")
            u16(18) == if (host.arch == "arm64") 183 else 62
        }
        "macos" -> {
            if (u32(0) != 0xfeedfacfL) throw ReleaseFailure("Native artifact is not a 64-bit Mach-O executable")
            u32(4) == if (host.arch == "arm64") 0x0100000cL else 0x01000007L
        }
        "windows" -> {
            if (u16(0) != 0x5a4d) throw ReleaseFailure("Native artifact is not a PE executable")
            val pe = u32(0x3c).toInt()
            if (u32(pe) != 0x00004550L) throw ReleaseFailure("Native artifact lacks a PE signature")
            u16(pe + 4) == 0x8664
        }
        else -> false
    }
    if (!expected) throw ReleaseFailure("Native executable architecture does not match ${host.nativeName}")
}

/** Inspect actual PE import tables, including delay-loaded dependencies, without relying on developer tools. */
private fun peImports(file: Path): List<String> {
    val bytes = file.readBytes()
    val data = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
    fun bounds(offset: Int, size: Int) {
        if (offset < 0 || size < 0 || offset.toLong() + size > bytes.size) throw ReleaseFailure("Malformed PE import metadata in ${file.name}")
    }
    fun u16(offset: Int): Int { bounds(offset, 2); return data.getShort(offset).toInt() and 0xffff }
    fun u32(offset: Int): Int { bounds(offset, 4); val value = data.getInt(offset); if (value < 0) throw ReleaseFailure("Unsupported PE offset in ${file.name}"); return value }
    if (u16(0) != 0x5a4d) throw ReleaseFailure("Not a PE file: $file")
    val pe = u32(0x3c)
    if (u32(pe) != 0x4550) throw ReleaseFailure("Bad PE signature: $file")
    val optional = pe + 24
    val magic = u16(optional)
    val directories = when (magic) { 0x20b -> optional + 112; 0x10b -> optional + 96; else -> throw ReleaseFailure("Unknown PE optional header") }
    val imageBase = if (magic == 0x20b) data.getLong(optional + 24) else u32(optional + 28).toLong()
    val sectionCount = u16(pe + 6)
    val sections = optional + u16(pe + 20)
    fun offset(rva: Int): Int {
        for (index in 0 until sectionCount) {
            val section = sections + index * 40
            val start = u32(section + 12)
            val size = maxOf(u32(section + 8), u32(section + 16))
            if (rva >= start && rva.toLong() < start.toLong() + size) return u32(section + 20) + rva - start
        }
        if (rva < u32(optional + 60)) return rva
        throw ReleaseFailure("PE import points outside mapped sections: ${file.name}")
    }
    fun name(rva: Int): String {
        val start = offset(rva)
        bounds(start, 1)
        var end = start
        while (end < bytes.size && end - start < 32768 && bytes[end] != 0.toByte()) end++
        if (end == bytes.size || end - start >= 32768) throw ReleaseFailure("Unterminated PE import name")
        val value = String(bytes, start, end - start, Charsets.US_ASCII)
        if (value.isBlank() || value.contains('/') || value.contains('\\')) throw ReleaseFailure("Unsafe PE DLL name")
        return value
    }
    val imports = linkedSetOf<String>()
    for ((directoryIndex, descriptorSize) in listOf(1 to 20, 13 to 32)) {
        val rva = u32(directories + directoryIndex * 8)
        val size = u32(directories + directoryIndex * 8 + 4)
        if (rva == 0 || size == 0) continue
        var descriptor = offset(rva)
        for (index in 0 until (size / descriptorSize + 1).coerceAtMost(65536)) {
            bounds(descriptor, descriptorSize)
            if ((0 until descriptorSize).all { bytes[descriptor + it] == 0.toByte() }) break
            val rawName = u32(descriptor + if (directoryIndex == 1) 12 else 4)
            val nameRva = if (directoryIndex == 13 && u32(descriptor) and 1 == 0) (rawName.toLong() - imageBase).toInt() else rawName
            if (nameRva != 0) imports += name(nameRva)
            descriptor += descriptorSize
        }
    }
    return imports.toList()
}

private fun copy(source: Path, destination: Path) {
    destination.parent.createDirectories()
    Files.copy(source, destination, StandardCopyOption.REPLACE_EXISTING)
}

private fun sha256(file: Path): String {
    val digest = MessageDigest.getInstance("SHA-256")
    BufferedInputStream(Files.newInputStream(file)).use { input ->
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
}

private fun deleteTree(directory: Path) {
    if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) return
    Files.walk(directory).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } }
}

private fun cleanupBestEffort(directory: Path, remove: (Path) -> Unit = ::deleteTree) {
    try {
        remove(directory)
    } catch (error: Exception) {
        System.err.println("warning: Could not remove generated temporary files at $directory (${error.message ?: error.javaClass.simpleName})")
    }
}

private val releaseRootFiles = setOf(
    ".gitignore", "VERSION", "README.md", "SPEC.md", "APP_DEVELOPMENT.md", "COMPILER.md",
    "RELEASE_NOTES.md", "CHANGELOG.md", "THIRD_PARTY.md", "RELEASE_CONTRACT.md", "SECURITY.md",
    "CONTRIBUTING.md", "CODE_OF_CONDUCT.md", "LICENSE", "LICENSE.md", "LICENSE.txt", "NOTICE", "NOTICE.txt", "PLATFORMS.md", "BUILDING.md"
)
private val releaseSourceDirectories = setOf("kotlin", "swift", "tools", "examples", "tests", "licenses", "docs", ".github")

private fun allowedReleaseSource(relative: Path): Boolean {
    val parts = relative.map { it.toString() }
    if (parts.isEmpty() || parts.any { it.isEmpty() }) return false
    if (parts.size == 1) return parts.single() in releaseRootFiles
    if (parts.first() == ".github") return parts.size == 3 && parts[1] == "workflows" &&
        !parts[2].startsWith('.') && parts[2].substringAfterLast('.', "") in setOf("yml", "yaml")
    if (parts.any { it.startsWith('.') }) return false
    val name = parts.last()
    val extension = name.substringAfterLast('.', "")
    return when (parts.first()) {
        "kotlin", "tools" -> extension == "kt"
        "swift" -> extension == "swift"
        "examples" -> extension in setOf("swk", "kt", "swift")
        "tests" -> (parts.size == 2 && name == "README.md") ||
            (parts.getOrNull(1) == "cases" && extension in setOf("swk", "out", "error"))
        "docs" -> extension == "md"
        "licenses" -> name in setOf("LICENSE", "LICENSE.txt", "NOTICE", "NOTICE.txt", "COPYRIGHT", "COPYRIGHT.txt", "README.md") ||
            name.matches(Regex("[A-Za-z0-9_.-]+_(?:license|LICENSE|notice)\\.(?:txt|md)"))
        else -> false
    }
}

private fun releaseSourceFiles(directory: Path): List<Path> {
    val result = mutableListOf<Path>()
    Files.walkFileTree(directory, object : SimpleFileVisitor<Path>() {
        override fun preVisitDirectory(path: Path, attributes: BasicFileAttributes): FileVisitResult {
            if (path == directory) return FileVisitResult.CONTINUE
            val parts = directory.relativize(path).map { it.toString() }
            return if (parts.first() in releaseSourceDirectories && (parts.first() == ".github" && parts.drop(1).none { it.startsWith('.') } || parts.none { it.startsWith('.') })) FileVisitResult.CONTINUE
            else FileVisitResult.SKIP_SUBTREE
        }
        override fun visitFile(path: Path, attributes: BasicFileAttributes): FileVisitResult {
            if (attributes.isRegularFile && allowedReleaseSource(directory.relativize(path))) result.add(path)
            return FileVisitResult.CONTINUE
        }
    })
    return result.sortedBy { directory.relativize(it).toString() }
}

private fun publishDirectory(source: Path, destination: Path, cleanup: (Path) -> Unit = ::deleteTree) {
    destination.parent.createDirectories()
    val backup = destination.resolveSibling(".${destination.name}.previous-${System.nanoTime()}")
    val hadPrevious = Files.exists(destination, LinkOption.NOFOLLOW_LINKS)
    if (hadPrevious) Files.move(destination, backup, StandardCopyOption.ATOMIC_MOVE)
    try {
        Files.move(source, destination, StandardCopyOption.ATOMIC_MOVE)
    } catch (error: Exception) {
        if (hadPrevious) Files.move(backup, destination, StandardCopyOption.ATOMIC_MOVE)
        throw error
    }
    if (hadPrevious) cleanupBestEffort(backup, cleanup)
}

private fun releaseToolSelfTest() {
    val allowed = listOf(".gitignore", "VERSION", "README.md", "PLATFORMS.md", ".github/workflows/release.yml", "tools/Release.kt", "kotlin/Swiko.kt", "swift/Swiko.swift",
        "examples/hosts/Host.kt", "examples/hello.swk", "tests/README.md", "tests/cases/hello.out", "tests/cases/error.error", "licenses/icu/LICENSE")
    val forbidden = listOf(".env", ".aws/credentials", "id_rsa", "private.md", "unknown.txt", "build/private.kt", "dist/private.swift",
        "examples/.private/Hidden.swift", "kotlin/.secret/Hidden.kt", "kotlin/token.pem", "tools/driver.sh", "tests/cases/private.key", "docs/private.key", "licenses/private.pem", ".github/secrets.yml", ".github/workflows/.env")
    for ((os, arch, expected) in listOf(
        Triple("Linux", "amd64", "linux-x86_64"), Triple("Linux", "aarch64", "linux-arm64"),
        Triple("Mac OS X", "aarch64", "macos-arm64"), Triple("Darwin", "x86_64", "macos-x86_64"),
        Triple("Windows 11", "amd64", "windows-x86_64")
    )) check(HostPlatform.detect(os, arch).nativeName == expected) { "Incorrect platform package plan for $os/$arch" }
    for (name in allowed) check(allowedReleaseSource(Path.of(name))) { "Allowed release input rejected: $name" }
    for (name in forbidden) check(!allowedReleaseSource(Path.of(name))) { "Private/unknown release input accepted: $name" }
    val directory = Files.createTempDirectory("swiko-release-tool-self-test-")
    val originalError = System.err
    val warnings = ByteArrayOutputStream()
    var symlinkExercised = true
    try {
        for (name in allowed + forbidden) {
            val file = directory.resolve(name)
            file.parent.createDirectories()
            file.writeText("synthetic fixture")
        }
        try { Files.createSymbolicLink(directory.resolve("kotlin/Linked.kt"), directory.resolve(".env")) }
        catch (error: java.nio.file.FileSystemException) {
            if (HostPlatform.detect().os != "windows") throw error
            symlinkExercised = false
            println("Symlink creation is unavailable to this Windows account; source traversal still rejects symbolic-link attributes")
        }
        val actual = releaseSourceFiles(directory).map { directory.relativize(it).toString().replace('\\', '/') }.toSet()
        check(actual == allowed.toSet()) { "Source traversal included unlisted files or followed a symlink" }
        val publication = directory.resolve("publication").createDirectories()
        val incoming = publication.resolve("incoming").createDirectories()
        incoming.resolve("marker").writeText("new")
        val destination = publication.resolve("published").createDirectories()
        destination.resolve("marker").writeText("old")
        System.setErr(PrintStream(warnings))
        publishDirectory(incoming, destination) { throw java.io.IOException("synthetic cleanup failure") }
        check(destination.resolve("marker").readText() == "new") { "Cleanup failure lost the committed release" }
        check(warnings.toString().contains("warning:")) { "Cleanup failure did not emit a warning" }
        cleanupBestEffort(directory.resolve("unused")) { throw java.io.IOException("synthetic stage cleanup failure") }
        var rejected = false
        try { publishDirectory(publication.resolve("missing"), destination) }
        catch (_: Exception) { rejected = true }
        check(rejected && destination.resolve("marker").readText() == "new") { "Failed publication did not restore the previous release" }
    } finally {
        System.setErr(originalError)
        deleteTree(directory)
    }
    println("Release-tool self-test passed: host/package plans, input allowlist, ${if (symlinkExercised) "symlink rejection" else "symlink capability unavailable (skipped)"}, cleanup warning, committed-release preservation, and rollback")
}

fun main(arguments: Array<String>) {
    if (arguments.contentEquals(arrayOf("--self-test"))) {
        try { releaseToolSelfTest() }
        catch (error: Exception) { System.err.println("release-tool self-test: ${error.message}"); exitProcess(1) }
        return
    }
    if (arguments.isEmpty() || arguments.contentEquals(arrayOf("--help"))) {
        println("Usage: release-tool <build|test|release> [--root <project-directory>] [--backend all|jvm]\n       release-tool --self-test\nSet SWIKO_KOTLINC and (for all) SWIKO_SWIFTC when compilers are outside PATH.\nDefault backend all builds and tests both implementations on this host. Backend jvm needs Kotlin/JDK only and creates portable JVM distributions, not native binaries for another OS.\nEach command rebuilds from source. 'release' runs tests and package extraction/rebuild checks before publishing dist/swiko-<version>/.")
        return
    }
    var root = Path.of(".").toAbsolutePath().normalize()
    var command: String? = null
    var backend = "all"
    var backendSet = false
    var index = 0
    while (index < arguments.size) {
        when (val option = arguments[index++]) {
            "build", "test", "release" -> if (command == null) command = option else {
                System.err.println("error: specify exactly one command"); exitProcess(2)
            }
            "--root" -> {
                if (index == arguments.size) { System.err.println("error: --root needs a directory"); exitProcess(2) }
                root = Path.of(arguments[index++]).toAbsolutePath().normalize()
            }
            "--backend" -> {
                if (backendSet || index == arguments.size || arguments[index] !in setOf("all", "jvm")) {
                    System.err.println("error: --backend requires exactly one value: all or jvm"); exitProcess(2)
                }
                backend = arguments[index++]
                backendSet = true
            }
            else -> { System.err.println("error: unknown option '$option'"); exitProcess(2) }
        }
    }
    if (command == null) { System.err.println("error: a build, test, or release command is required"); exitProcess(2) }
    try {
        Release(root, command, backend).run()
    } catch (error: Exception) {
        System.err.println("release-tool: ${error.message ?: error.javaClass.simpleName}")
        exitProcess(1)
    }
}

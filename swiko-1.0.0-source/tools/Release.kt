import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.PrintStream
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

private class Release(private val root: Path, private val command: String) {
    private val build = root.resolve("build")
    private val version = root.resolve("VERSION").readText().trim().also {
        if (!it.matches(Regex("[0-9]+\\.[0-9]+\\.[0-9]+(?:-[0-9A-Za-z.-]+)?"))) {
            throw ReleaseFailure("VERSION must contain a semantic version")
        }
    }
    private val base = "swiko-$version"
    private val java = Path.of(System.getProperty("java.home"), "bin", "java").toString()
    private val kotlinc = executable(System.getenv("SWIKO_KOTLINC") ?: "kotlinc")
    private val swiftc = executable(System.getenv("SWIKO_SWIFTC") ?: "swiftc")
    private lateinit var stage: Path
    private lateinit var project: Path
    private var logIndex = 0
    private var preparedRelease: Path? = null
    private val evidence = mutableListOf<String>()
    private val outputNames = mutableListOf("swiko.jar", "swiko-swift", "conformance.jar")

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
        if (!System.getProperty("os.name").lowercase().contains("linux")) {
            throw ReleaseFailure("This native release builder currently supports Linux only; build the Kotlin source directly on other platforms")
        }
        if (System.getProperty("os.arch") !in setOf("amd64", "x86_64")) {
            throw ReleaseFailure("Native release packaging currently supports Linux x86_64 only")
        }
    }

    private fun build() {
        println("Building Swiko $version from source")
        val kotlinVersion = run("Kotlin version", listOf(kotlinc.toString(), "-version"), 30)
        val swiftVersion = run("Swift version", listOf(swiftc.toString(), "--version"), 30)
        evidence += "Kotlin compiler: ${kotlinVersion.trim()}"
        evidence += "Swift compiler: ${swiftVersion.trim()}"
        evidence += "JVM: ${System.getProperty("java.version")} (${System.getProperty("java.vendor")})"
        evidence += "Host: ${System.getProperty("os.name")} ${System.getProperty("os.arch")}"
        compileKotlin(project.resolve("kotlin/Swiko.kt"), stage.resolve("swiko.jar"), "Kotlin interpreter")
        run("Swift interpreter", listOf(swiftc.toString(), "-O", "-static-stdlib", "-module-cache-path",
            stage.resolve("swift-module-cache").toString(), project.resolve("swift/Swiko.swift").toString(),
            "-o", stage.resolve("swiko-swift").toString()), 240)
        compileKotlin(project.resolve("kotlin/Conformance.kt"), stage.resolve("conformance.jar"), "Conformance runner")
        for ((sourceName, artifactName, label) in listOf(
            Triple("CliConformance.kt", "cli-conformance.jar", "CLI test runner"),
            Triple("CompilerConformance.kt", "compiler-conformance.jar", "Compiler test runner")
        )) {
            val source = project.resolve("kotlin/$sourceName")
            if (!source.isRegularFile()) throw ReleaseFailure("Missing release test runner: kotlin/$sourceName")
            compileKotlin(source, stage.resolve(artifactName), label)
            outputNames += artifactName
        }
        verifyVersion(listOf(java, "-jar", stage.resolve("swiko.jar").toString()), "Kotlin version smoke")
        verifyVersion(listOf(stage.resolve("swiko-swift").toString()), "Swift version smoke")
        val dynamic = run("Native dependencies", listOf("ldd", stage.resolve("swiko-swift").toString()), 30)
        if (dynamic.contains("not found") || dynamic.contains(swiftc.parent.parent.toString()) || dynamic.contains("libswift") || dynamic.contains("libFoundation")) {
            throw ReleaseFailure("Native executable depends on missing or toolchain runtime libraries")
        }
        val elf = run("Native dynamic metadata", listOf("readelf", "-d", stage.resolve("swiko-swift").toString()), 30)
        if (elf.contains("(RPATH)") || elf.contains("(RUNPATH)")) throw ReleaseFailure("Native executable contains a runtime search path")
        val symbols = run("Native ABI metadata", listOf("readelf", "--version-info", stage.resolve("swiko-swift").toString()), 30)
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
            stage.resolve("swiko.jar").toString(), stage.resolve("swiko-swift").toString(), project.resolve("tests/cases").toString()), 600)
        evidence += "Shared conformance: ${result.trim()}"
        for ((artifact, label) in listOf("cli-conformance.jar" to "CLI conformance", "compiler-conformance.jar" to "Compiler conformance")) {
            val result = run(label, listOf(java, "-jar", stage.resolve(artifact).toString(),
                stage.resolve("swiko.jar").toString(), stage.resolve("swiko-swift").toString(), project.toString()), if (artifact == "compiler-conformance.jar") 900 else 600)
            evidence += "$label: ${result.trim()}"
        }
        val examples = project.resolve("examples").listDirectoryEntries("*.swk").sortedBy { it.name }
        for (example in examples) {
            val kotlinOutput = run("Kotlin example ${example.name}", listOf(java, "-jar", stage.resolve("swiko.jar").toString(), example.toString()), 30)
            val swiftOutput = run("Swift example ${example.name}", listOf(stage.resolve("swiko-swift").toString(), example.toString()), 30)
            if (kotlinOutput != swiftOutput) throw ReleaseFailure("Example ${example.name} differs between implementations")
        }
        evidence += "Examples: ${examples.size} programs passed in both implementations"
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

        val nativeRoot = assembly.resolve("$base-linux-x86_64").createDirectories()
        copyDocs(nativeRoot)
        copy(stage.resolve("swiko-swift"), nativeRoot.resolve("bin/swiko"))
        copyRuntimeNotices(nativeRoot, includeKotlin = false, includeSwift = true)
        nativeRoot.resolve("BUILD-REPORT.md").writeText(report(packagedSnapshot = true))
        setPermissions(nativeRoot)
        val nativeArchive = release.resolve("$base-linux-x86_64.tar.gz")
        run("Native archive", listOf("tar", "--sort=name", "--mtime=@0", "--owner=0", "--group=0", "--numeric-owner",
            "-czf", nativeArchive.toString(), "-C", assembly.toString(), nativeRoot.name), 120)

        val unpack = stage.resolve("unpacked").createDirectories()
        unzip(jvmZip, unpack)
        val unpackedJvm = unpack.resolve("$base-jvm")
        verifyVersion(listOf(java, "-jar", unpackedJvm.resolve("swiko.jar").toString()), "Packaged JVM version")
        val jvmHello = run("Packaged JVM example", listOf(java, "-jar", unpackedJvm.resolve("swiko.jar").toString(), unpackedJvm.resolve("examples/hello.swk").toString()), 30)
        run("Extract native archive", listOf("tar", "-xzf", nativeArchive.toString(), "-C", unpack.toString()), 60)
        val unpackedNative = unpack.resolve("$base-linux-x86_64")
        if (!Files.isExecutable(unpackedNative.resolve("bin/swiko"))) throw ReleaseFailure("Native archive did not preserve executable permissions")
        verifyVersion(listOf(unpackedNative.resolve("bin/swiko").toString()), "Packaged native version")
        val nativeHello = run("Packaged native example", listOf(unpackedNative.resolve("bin/swiko").toString(), unpackedNative.resolve("examples/hello.swk").toString()), 30)
        if (jvmHello != nativeHello) throw ReleaseFailure("Extracted packages do not produce matching example output")
        unzip(sourceZip, unpack)
        val unpackedSource = unpack.resolve("$base-source")
        if (unpackedSource.resolve("VERSION").readText().trim() != version) throw ReleaseFailure("Source archive VERSION mismatch")
        val clean = stage.resolve("source-rebuild").createDirectories()
        compileKotlin(unpackedSource.resolve("tools/Release.kt"), clean.resolve("release-tool.jar"), "Extracted source build-tool bootstrap")
        run("Extracted source clean build", listOf(java, "-jar", clean.resolve("release-tool.jar").toString(),
            "build", "--root", unpackedSource.toString()), 600)
        verifyVersion(listOf(java, "-jar", unpackedSource.resolve("build/swiko.jar").toString()), "Extracted source Kotlin version")
        verifyVersion(listOf(unpackedSource.resolve("build/swiko-swift").toString()), "Extracted source Swift version")
        evidence += "Packaging: source ZIP, JVM ZIP, native tar.gz created and extracted successfully"
        evidence += "Package smoke tests: both distributed executables passed --version and hello example"
        evidence += "Clean source archive rebuild: build tool bootstrapped; its build command compiled interpreters and all test runners; both interpreters passed --version"
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
            val license = swiftc.toRealPath().parent.parent.resolve("share/swift/LICENSE.txt")
            if (!license.isRegularFile()) throw ReleaseFailure("Swift distribution license not found: $license")
            copy(license, target.resolve("licenses/swift/LICENSE.txt"))
            if (!target.resolve("licenses/icu/LICENSE").isRegularFile()) {
                throw ReleaseFailure("Native distribution requires the matching ICU notice at licenses/icu/LICENSE")
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
        append("Command: `$command`\n\n")
        append("All recorded commands completed successfully.\n\n")
        if (packagedSnapshot) append("This archive report records compiler, ABI, and test gates completed before archive assembly. Package extraction and clean-source rebuild results appear in the final outer BUILD-REPORT.md.\n\n")
        for (entry in evidence) append("```text\n${entry.trim()}\n```\n\n")
        append("Native support is limited to the tested Linux x86_64 ABI. The JVM package requires JRE 17 or newer.\n")
        append("Checksums detect changes; they are not a publisher signature. Archive entries use fixed timestamps; universal bit-for-bit compiler reproducibility is not claimed.\n")
    }

    private fun run(label: String, arguments: List<String>, seconds: Long): String {
        println("  $label")
        val logs = stage.resolve("logs").createDirectories()
        val log = logs.resolve("${logIndex++}.log")
        val process = try {
            ProcessBuilder(arguments).directory(project.toFile()).redirectErrorStream(true).redirectOutput(log.toFile()).start()
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
    for (directory in (System.getenv("PATH") ?: "").split(java.io.File.pathSeparator)) {
        val candidate = Path.of(directory.ifEmpty { "." }, name).toAbsolutePath().normalize()
        if (Files.isExecutable(candidate) && candidate.isRegularFile()) return candidate
    }
    throw ReleaseFailure("Compiler '$name' not found; set SWIKO_KOTLINC and SWIKO_SWIFTC to compiler paths")
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
    "CONTRIBUTING.md", "CODE_OF_CONDUCT.md", "LICENSE", "LICENSE.md", "LICENSE.txt", "NOTICE", "NOTICE.txt"
)
private val releaseSourceDirectories = setOf("kotlin", "swift", "tools", "examples", "tests", "licenses", "docs")

private fun allowedReleaseSource(relative: Path): Boolean {
    val parts = relative.map { it.toString() }
    if (parts.isEmpty() || parts.any { it.isEmpty() }) return false
    if (parts.size == 1) return parts.single() in releaseRootFiles
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
            return if (parts.first() in releaseSourceDirectories && parts.none { it.startsWith('.') }) FileVisitResult.CONTINUE
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
    val allowed = listOf(".gitignore", "VERSION", "README.md", "tools/Release.kt", "kotlin/Swiko.kt", "swift/Swiko.swift",
        "examples/hosts/Host.kt", "examples/hello.swk", "tests/README.md", "tests/cases/hello.out", "tests/cases/error.error", "licenses/icu/LICENSE")
    val forbidden = listOf(".env", ".aws/credentials", "id_rsa", "private.md", "unknown.txt", "build/private.kt", "dist/private.swift",
        "examples/.private/Hidden.swift", "kotlin/.secret/Hidden.kt", "kotlin/token.pem", "tools/driver.sh", "tests/cases/private.key", "docs/private.key", "licenses/private.pem")
    for (name in allowed) check(allowedReleaseSource(Path.of(name))) { "Allowed release input rejected: $name" }
    for (name in forbidden) check(!allowedReleaseSource(Path.of(name))) { "Private/unknown release input accepted: $name" }
    val directory = Files.createTempDirectory("swiko-release-tool-self-test-")
    val originalError = System.err
    val warnings = ByteArrayOutputStream()
    try {
        for (name in allowed + forbidden) {
            val file = directory.resolve(name)
            file.parent.createDirectories()
            file.writeText("synthetic fixture")
        }
        Files.createSymbolicLink(directory.resolve("kotlin/Linked.kt"), directory.resolve(".env"))
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
    println("Release-tool self-test passed: input allowlist, symlink rejection, cleanup warning, committed-release preservation, and rollback")
}

fun main(arguments: Array<String>) {
    if (arguments.contentEquals(arrayOf("--self-test"))) {
        try { releaseToolSelfTest() }
        catch (error: Exception) { System.err.println("release-tool self-test: ${error.message}"); exitProcess(1) }
        return
    }
    if (arguments.isEmpty() || arguments.contentEquals(arrayOf("--help"))) {
        println("Usage: release-tool <build|test|release> [--root <project-directory>]\n       release-tool --self-test\nSet SWIKO_KOTLINC and SWIKO_SWIFTC when compilers are outside PATH.\nEach command rebuilds from source. 'release' runs tests and package extraction/rebuild checks before publishing dist/swiko-<version>/.")
        return
    }
    var root = Path.of(".").toAbsolutePath().normalize()
    var command: String? = null
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
            else -> { System.err.println("error: unknown option '$option'"); exitProcess(2) }
        }
    }
    if (command == null) { System.err.println("error: a build, test, or release command is required"); exitProcess(2) }
    try {
        Release(root, command).run()
    } catch (error: Exception) {
        System.err.println("release-tool: ${error.message ?: error.javaClass.simpleName}")
        exitProcess(1)
    }
}

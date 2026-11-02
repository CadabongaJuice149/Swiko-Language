import java.nio.file.Files
import java.nio.file.Path
import java.io.RandomAccessFile
import java.io.File
import java.util.Comparator
import java.util.concurrent.TimeUnit
import kotlin.io.path.*

/** Compiles actual emitted Swift/Kotlin applications and host integrations. */
fun main(arguments: Array<String>) {
    if (arguments.size != 3 || arguments.getOrNull(1) == "--jvm-only") {
        System.err.println("Usage: compiler-conformance <swiko.jar> <swiko-native> <project-root>")
        System.err.println("Both native and JVM frontends are required; JVM-only releases must report this suite as skipped.")
        kotlin.system.exitProcess(2)
    }
    val jar = Path.of(arguments[0]).toAbsolutePath()
    val native = Path.of(arguments[1]).toAbsolutePath()
    val project = Path.of(arguments[2]).toAbsolutePath()
    require(jar.isRegularFile()) { "Missing JVM frontend: $jar" }
    require(native.isRegularFile()) { "Missing native frontend: $native" }
    require(project.isDirectory()) { "Missing project root: $project" }
    val platform = CompilerHost.current()
    val java = Path.of(System.getProperty("java.home"), "bin", if (platform.windows) "java.exe" else "java").toString()
    val kotlinc = System.getenv("SWIKO_KOTLINC")?.takeIf { it.isNotBlank() } ?: "kotlinc"
    val swiftc = System.getenv("SWIKO_SWIFTC")?.takeIf { it.isNotBlank() } ?: "swiftc"
    val build = project.resolve("build").createDirectories()
    val temporary = Files.createTempDirectory(build, "compiler-tests-")
    val moduleCache = temporary.resolve("module-cache").createDirectories().toString()
    val captures = temporary.resolve("captures").createDirectories()
    var captureNumber = 0
    var passed = 0
    var failed = 0
    val windowsRuntimeDirectories by lazy { if (platform.windows) windowsSwiftRuntimeDirectories(swiftc) else emptyList() }
    data class Result(val status: Int, val stdout: String, val stderr: String)
    fun run(command: List<String>, directory: Path, seconds: Long = 20): Result {
        val number = captureNumber++
        val stdout = captures.resolve("$number.out")
        val stderr = captures.resolve("$number.err")
        val prepared = if (platform.windows && command.first() == kotlinc) windowsKotlinCommand(command, java) else command
        val builder = ProcessBuilder(prepared).directory(directory.toFile())
            .redirectOutput(stdout.toFile()).redirectError(stderr.toFile())
        val loader = when {
            platform.windows -> "PATH"
            platform.mac -> "DYLD_LIBRARY_PATH"
            else -> "LD_LIBRARY_PATH"
        }
        val additions = listOf(directory.toString()) + windowsRuntimeDirectories
        val environment = builder.environment()
        val existingKey = environment.keys.firstOrNull { it.equals(loader, ignoreCase = platform.windows) } ?: loader
        environment[existingKey] = (additions + listOfNotNull(environment[existingKey])).joinToString(File.pathSeparator)
        val process = builder.start()
        val finished = process.waitFor(seconds, TimeUnit.SECONDS)
        if (!finished) {
            process.descendants().forEach { it.destroyForcibly() }
            process.destroyForcibly()
            process.waitFor()
            error("Process timed out after ${seconds}s: ${command.first()}")
        }
        return Result(process.exitValue(), stdout.readText().normalizeLines(), stderr.readText().normalizeLines())
    }
    fun verify(label: String, block: () -> Unit) {
        try {
            block()
            passed++
        } catch (error: Exception) {
            failed++
            System.err.println("FAIL $label: ${error.message?.take(7000)}")
        }
    }
    fun success(result: Result, stage: String) {
        check(result.status == 0) { "$stage exited ${result.status}: ${result.stderr.take(6500)}" }
        check(result.stderr.isEmpty()) { "$stage wrote unexpected stderr: ${result.stderr.take(6500)}" }
    }
    verify("native/frontend-platform") { verifyHostNativeImage(native, platform) }
    val frontends = listOf(
        "jvm" to listOf(java, "-jar", jar.toString()),
        "native" to listOf(native.toString())
    )
    val showcase = """
        // Compilation showcase: independent language features share one application.
        print(2 + 3 * 4)
        print(-7 / 3)
        print(-7 % 3)
        print(-9223372036854775808)
        val object = "keyword"
        print(object)
        var calls = 0
        fun fallback(): String {
            calls = calls + 1
            return "fallback"
        }
        var nickname: String? = null
        print(nickname ?? fallback())
        nickname = "Ada"
        print(nickname ?? fallback())
        print(calls)
        fun factorial(n: Int): Int {
            if n <= 1 { return 1 }
            return n * factorial(n - 1)
        }
        print(factorial(6))
        fun panic(): Bool {
            print(1 / 0)
            return true
        }
        print(false && panic())
        print(true || panic())
        var i = 0
        var total = 0
        while i < 4 {
            i = i + 1
            total = total + i
        }
        print(total)
        val label = "outer"
        { val label = "inner" }
        print(label)
        fun optionalUnit(): Unit? { }
        fun absentUnit(): Unit? { return null }
        print(optionalUnit())
        print(absentUnit())
        print("é" == "é")
        print("世界 👋" == "世界 👋")
        fun enclosing(x: Int): Int {
            fun nested(y: Int): Int { return x + y }
            return nested(5)
        }
        print(enclosing(10))
        fun main(): Unit { print("explicit-main") }
        main()
    """.trimIndent() + "\n"
    val expectedShowcase = listOf(
        "14", "-2", "-1", "-9223372036854775808", "keyword", "fallback", "Ada", "1",
        "720", "false", "true", "10", "outer", "Unit", "null", "false", "true", "15", "explicit-main"
    ).joinToString("\n", postfix = "\n")
    val showcaseSource = temporary.resolve("showcase.swk")
    showcaseSource.writeText(showcase)
    val runtimeCases = linkedMapOf(
        "overflow" to """
            print("buffered output must be discarded")
            val maximum = 9223372036854775807
            print(maximum + 1)
        """.trimIndent(),
        "string-limit" to """
            var text = "x"
            var count = 0
            while count < 21 {
                text = text + text
                count = count + 1
            }
            print(text)
        """.trimIndent(),
        "output-limit" to """
            var text = "x"
            var count = 0
            while count < 20 {
                text = text + text
                count = count + 1
            }
            var copies = 0
            while copies < 5 {
                print(text)
                copies = copies + 1
            }
        """.trimIndent(),
        "step-limit" to "while true { }",
        "call-depth" to """
            fun recurse(): Unit { recurse() }
            recurse()
        """.trimIndent()
    )
    val diagnostic = Regex(":\\d+:\\d+: error:")
    fun generatedCode(frontend: List<String>, source: Path, target: String, library: Boolean, directory: Path): String {
        val options = listOf("--emit-$target") + if (library) listOf("--library") else emptyList()
        val result = run(frontend + options + source.toString(), directory)
        success(result, "source emission")
        val code = result.stdout
        check(code.isNotBlank()) { "Emitter returned empty source" }
        if (source.readText().length > 100) {
            check(!code.contains(source.readText())) { "Generated source embeds the complete Swiko program" }
        }
        for (forbidden in listOf("class Interpreter", "class Lexer", "class Parser", "runSwiko(", "evalSwiko(")) {
            check(!code.contains(forbidden)) { "Generated source includes interpreter implementation: $forbidden" }
        }
        if (source == showcaseSource) {
            check(code.contains(if (target == "swift") "Int64" else "Long")) { "Generated source lacks the typed integer representation" }
        }
        check(code.contains(if (target == "swift") "func " else "fun ")) { "Generated source lacks target functions" }
        if (library) {
            check(code.contains("swikoMain")) { "Library lacks swikoMain entry" }
            check(code.contains("swikoRun")) { "Library lacks swikoRun entry" }
            if (target == "swift") {
                check(!code.contains("@main")) { "Library includes a Swift application entry point" }
            } else {
                check(!Regex("(?m)^\\s*(?:public\\s+)?fun\\s+main\\s*\\(").containsMatchIn(code)) {
                    "Library includes a Kotlin application main function"
                }
            }
        }
        return code
    }
    fun compile(target: String, code: String, directory: Path, library: Boolean): List<String> {
        val extension = if (target == "swift") "swift" else "kt"
        val generated = directory.resolve("Generated.$extension")
        generated.writeText(code)
        val binary = directory.resolve(if (target == "swift") "application${if (platform.windows) ".exe" else ""}" else "application.jar")
        if (library) {
            val host = directory.resolve("Host.$extension")
            host.writeText(if (target == "swift") """
                import SwikoGenerated

                @main struct SwikoConformanceHost {
                    static func main() throws {
                        Swift.print("host-before")
                        let first = try swikoRun()
                        let second = try swikoRun()
                        Swift.print(first == second)
                        Swift.print(first, terminator: "")
                        try swikoMain()
                        Swift.print("host-after")
                    }
                }
            """.trimIndent() + "\n" else """
                private fun writeHost(text: String) {
                    System.out.write(text.toByteArray(Charsets.UTF_8))
                    System.out.flush()
                }
                fun main() {
                    writeHost("host-before\n")
                    val first = swikoRun()
                    val second = swikoRun()
                    writeHost((first == second).toString() + "\n")
                    writeHost(first)
                    swikoMain()
                    writeHost("host-after\n")
                }
            """.trimIndent() + "\n")
            if (target == "swift") {
                val libraryFile = directory.resolve(when {
                    platform.windows -> "SwikoGenerated.dll"
                    platform.mac -> "libSwikoGenerated.dylib"
                    else -> "libSwikoGenerated.so"
                })
                val moduleFile = directory.resolve("SwikoGenerated.swiftmodule")
                val importLibrary = directory.resolve("SwikoGenerated.lib")
                val libraryOptions = when {
                    platform.windows -> listOf("-Xlinker", "/IMPLIB:${importLibrary}")
                    platform.mac -> listOf("-Xlinker", "-install_name", "-Xlinker", "@rpath/${libraryFile.fileName}")
                    else -> emptyList()
                }
                val emittedLibrary = run(listOf(
                    swiftc, "-module-cache-path", moduleCache, "-parse-as-library", "-emit-library", "-emit-module", "-module-name", "SwikoGenerated",
                    "-emit-module-path", moduleFile.toString(), generated.toString(), "-o", libraryFile.toString()
                ) + libraryOptions, directory, 120)
                check(emittedLibrary.status == 0) { "Swift library compilation failed: ${emittedLibrary.stderr.take(6500)}" }
                check(libraryFile.isRegularFile() && moduleFile.isRegularFile()) { "Swift library/module artifact missing" }
                verifyHostNativeImage(libraryFile, platform)
                if (platform.windows) check(importLibrary.isRegularFile()) { "Swift Windows import library missing: $importLibrary" }
                val hostRuntimeOptions = if (platform.windows) emptyList() else listOf("-Xlinker", "-rpath", "-Xlinker", directory.toString())
                val emittedHost = run(listOf(
                    swiftc, "-module-cache-path", moduleCache, "-parse-as-library", "-I", directory.toString(), "-L", directory.toString(), "-lSwikoGenerated"
                ) + hostRuntimeOptions + listOf(host.toString(), "-o", binary.toString()), directory, 120)
                check(emittedHost.status == 0) { "Swift host compilation/linkage failed: ${emittedHost.stderr.take(6500)}" }
                check(binary.isRegularFile()) { "Swift host artifact missing" }
                verifyHostNativeImage(binary, platform)
                return listOf(binary.toString())
            } else {
                val libraryFile = directory.resolve("library.jar")
                val emittedLibrary = run(listOf(kotlinc, generated.toString(), "-d", libraryFile.toString()), directory, 120)
                check(emittedLibrary.status == 0) { "Kotlin library compilation failed: ${emittedLibrary.stderr.take(6500)}" }
                check(libraryFile.isRegularFile()) { "Kotlin library artifact missing" }
                val emittedHost = run(listOf(
                    kotlinc, host.toString(), "-classpath", libraryFile.toString(), "-include-runtime", "-d", binary.toString()
                ), directory, 120)
                check(emittedHost.status == 0) { "Kotlin host compilation/linkage failed: ${emittedHost.stderr.take(6500)}" }
                check(binary.isRegularFile()) { "Kotlin host artifact missing" }
                val classpath = binary.toString() + System.getProperty("path.separator") + libraryFile.toString()
                return listOf(java, "-classpath", classpath, "HostKt")
            }
        }
        val command = if (target == "swift") listOf(swiftc, "-module-cache-path", moduleCache, generated.toString(), "-o", binary.toString())
            else listOf(kotlinc, generated.toString(), "-include-runtime", "-d", binary.toString())
        val result = run(command, directory, 120)
        check(result.status == 0) { "Target compilation exited ${result.status}: ${result.stderr.take(6500)}" }
        check(binary.isRegularFile()) { "Target compiler did not create an artifact" }
        if (target == "swift") verifyHostNativeImage(binary, platform)
        return if (target == "swift") listOf(binary.toString()) else listOf(java, "-jar", binary.toString())
    }
    for ((frontendName, frontend) in frontends) {
        var interpreterOutput: String? = null
        verify("$frontendName/showcase-interpreter") {
            val result = run(frontend + showcaseSource.toString(), temporary)
            success(result, "showcase interpretation")
            check(result.stdout == expectedShowcase) { "Interpreter output differs from the showcase expectation" }
            interpreterOutput = result.stdout
        }
        for (target in listOf("swift", "kotlin")) {
            var standalone: List<String>? = null
            for (library in listOf(false, true)) {
                val mode = if (library) "library" else "standalone"
                verify("$frontendName/$target/$mode") {
                    val directory = temporary.resolve("$frontendName-$target-$mode").createDirectories()
                    val code = generatedCode(frontend, showcaseSource, target, library, directory)
                    val executable = compile(target, code, directory, library)
                    if (!library) standalone = executable
                    val result = run(executable, directory)
                    success(result, "compiled application")
                    val body = interpreterOutput ?: expectedShowcase
                    val expected = if (library) "host-before\ntrue\n" + body + body + "host-after\n" else body
                    check(result.stdout == expected) {
                        "Compiled output differs from interpreter; expected ${expected.take(500)}, got ${result.stdout.take(500)}"
                    }
                }
            }
            if (target == "kotlin") verify("$frontendName/$target/legacy-default-encoding") {
                val executable = standalone ?: error("Standalone Kotlin application did not compile")
                val result = run(executable.take(1) + "-Dfile.encoding=windows-1252" + executable.drop(1), temporary)
                success(result, "compiled application with legacy JVM default encoding")
                check(result.stdout == (interpreterOutput ?: expectedShowcase)) { "Generated Kotlin application did not write UTF-8 with a legacy JVM default encoding" }
            }
            verify("$frontendName/$target/reject-static-error") {
                val directory = temporary.resolve("$frontendName-$target-invalid").createDirectories()
                val result = run(frontend + listOf("--emit-$target", "--eval", "if false { print(missing) }"), directory)
                check(result.status == 1) { "Invalid source exited ${result.status}, expected 1" }
                check(result.stdout.isEmpty()) { "Invalid source emitted target source" }
                check(diagnostic.containsMatchIn(result.stderr)) { "Missing source diagnostic: ${result.stderr}" }
                check(directory.listDirectoryEntries().isEmpty()) { "Invalid source created artifacts" }
            }
            for ((caseName, source) in runtimeCases) {
                verify("$frontendName/$target/runtime-$caseName") {
                    val directory = temporary.resolve("$frontendName-$target-$caseName").createDirectories()
                    val sourceFile = directory.resolve("$caseName.swk")
                    sourceFile.writeText(source + "\n")
                    val interpreted = run(frontend + sourceFile.toString(), directory)
                    check(interpreted.status == 1 && interpreted.stdout.isEmpty()) {
                        "Runtime-error interpretation must fail with buffered stdout empty: status ${interpreted.status}"
                    }
                    val code = generatedCode(frontend, sourceFile, target, false, directory)
                    val executable = compile(target, code, directory, false)
                    val result = run(executable, directory)
                    check(result.status == 1) { "Runtime error exited ${result.status}, expected 1: ${result.stderr.take(4000)}" }
                    check(result.stdout.isEmpty()) { "Runtime error leaked buffered output" }
                    check(diagnostic.containsMatchIn(result.stderr)) { "Compiled runtime error lacks source location: ${result.stderr.take(4000)}" }
                }
            }
        }
    }
    println("$passed passed, $failed failed (compiler and host-integration checks)")
    if (failed == 0) {
        Files.walk(temporary).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach { it.deleteIfExists() } }
    } else {
        System.err.println("Compiler test artifacts retained at $temporary")
        kotlin.system.exitProcess(1)
    }
}

private data class CompilerHost(val system: String, val architecture: String) {
    val windows get() = system == "windows"
    val mac get() = system == "macos"
    companion object {
        fun current(): CompilerHost {
            val os = System.getProperty("os.name").lowercase()
            val system = when {
                "windows" in os -> "windows"
                "mac" in os || "darwin" in os -> "macos"
                "linux" in os -> "linux"
                else -> error("Unsupported compiler-test host: $os")
            }
            val architecture = when (val value = System.getProperty("os.arch").lowercase()) {
                "amd64", "x86_64", "x64" -> "x86_64"
                "aarch64", "arm64" -> "arm64"
                else -> error("Unsupported compiler-test architecture: $value")
            }
            return CompilerHost(system, architecture)
        }
    }
}

/** Inspect the image actually produced on this host, without simulating another OS. */
private fun verifyHostNativeImage(path: Path, platform: CompilerHost) {
    RandomAccessFile(path.toFile(), "r").use { input ->
        fun bytes(offset: Long, length: Int): ByteArray {
            check(offset >= 0 && offset + length <= input.length()) { "Truncated native image: $path" }
            input.seek(offset)
            return ByteArray(length).also { input.readFully(it) }
        }
        fun number(data: ByteArray, offset: Int, length: Int, little: Boolean): Long {
            var result = 0L
            for (index in 0 until length) {
                val position = if (little) offset + length - 1 - index else offset + index
                result = (result shl 8) or (data[position].toInt() and 255).toLong()
            }
            return result
        }
        val header = bytes(0, 64)
        when (platform.system) {
            "linux" -> {
                check(header.take(4) == listOf(0x7f.toByte(), 'E'.code.toByte(), 'L'.code.toByte(), 'F'.code.toByte())) { "Native image is not ELF on Linux: $path" }
                check(header[4].toInt() == 2) { "Native ELF image is not 64-bit: $path" }
                check(header[5].toInt() in 1..2) { "Unknown ELF byte order: $path" }
                val machine = number(header, 18, 2, header[5].toInt() == 1)
                val expected = if (platform.architecture == "arm64") 183L else 62L
                check(machine == expected) { "ELF architecture $machine does not match host ${platform.architecture}: $path" }
            }
            "windows" -> {
                check(header[0] == 'M'.code.toByte() && header[1] == 'Z'.code.toByte()) { "Native image is not PE on Windows: $path" }
                val pe = bytes(number(header, 60, 4, true), 24)
                check(pe.take(4) == listOf('P'.code.toByte(), 'E'.code.toByte(), 0.toByte(), 0.toByte())) { "Missing PE signature: $path" }
                val machine = number(pe, 4, 2, true)
                val expected = if (platform.architecture == "arm64") 0xaa64L else 0x8664L
                check(machine == expected) { "PE architecture $machine does not match host ${platform.architecture}: $path" }
            }
            "macos" -> {
                val magic = number(header, 0, 4, false)
                val expected = if (platform.architecture == "arm64") 0x0100000cL else 0x01000007L
                when (magic) {
                    0xfeedfacfL, 0xcffaedfeL -> {
                        val cpu = number(header, 4, 4, magic == 0xcffaedfeL)
                        check(cpu == expected) { "Mach-O architecture $cpu does not match host ${platform.architecture}: $path" }
                    }
                    0xcafebabeL, 0xbebafecaL, 0xcafebabfL, 0xbfbafecaL -> {
                        val little = magic == 0xbebafecaL || magic == 0xbfbafecaL
                        val count = number(header, 4, 4, little)
                        check(count in 1..64) { "Invalid universal Mach-O architecture count: $path" }
                        val stride = if (magic == 0xcafebabfL || magic == 0xbfbafecaL) 32 else 20
                        val present = (0 until count.toInt()).any { index ->
                            number(bytes(8L + index.toLong() * stride, 4), 0, 4, little) == expected
                        }
                        check(present) { "Universal Mach-O image lacks host ${platform.architecture}: $path" }
                    }
                    else -> error("Native image is not a supported Mach-O binary on macOS: $path")
                }
            }
        }
    }
}

private fun compilerExecutable(command: String): Path? {
    val explicit = Path.of(command)
    if (explicit.isAbsolute || command.contains('/') || command.contains('\\')) return explicit.toAbsolutePath().takeIf { it.isRegularFile() }
    val names = if (command.substringAfterLast('.', "").lowercase() in setOf("exe", "bat", "cmd")) listOf(command)
        else listOf(command, "$command.exe", "$command.bat", "$command.cmd")
    for (directory in (System.getenv("PATH") ?: "").split(File.pathSeparator)) {
        for (name in names) {
            val candidate = Path.of(directory.ifEmpty { "." }, name).toAbsolutePath()
            if (candidate.isRegularFile()) return candidate
        }
    }
    return null
}

/** Avoid cmd.exe expansion when the Windows Kotlin distribution uses a batch launcher. */
private fun windowsKotlinCommand(command: List<String>, java: String): List<String> {
    val executable = compilerExecutable(command.first()) ?: error("Kotlin compiler executable not found: ${command.first()}")
    if (executable.extension.lowercase() !in setOf("bat", "cmd")) return listOf(executable.toString()) + command.drop(1)
    val home = executable.toRealPath().parent.parent
    check(home.resolve("lib/kotlin-compiler.jar").isRegularFile()) { "Kotlin compiler batch launcher lacks its distribution libraries: $home" }
    return listOf(java, "-Dkotlin.home=$home", "-classpath", home.resolve("lib").toString() + File.separator + "*", "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler") + command.drop(1)
}

private fun windowsSwiftRuntimeDirectories(swiftc: String): List<String> {
    val paths = linkedSetOf<Path>()
    listOf("SWIKO_SWIFT_RUNTIME_DIR", "SWIKO_SWIFT_RUNTIME_DIRS").forEach { variable ->
        System.getenv(variable)?.split(File.pathSeparator)?.filter { it.isNotBlank() }?.forEach { paths.add(Path.of(it)) }
    }
    System.getenv("SDKROOT")?.takeIf { it.isNotBlank() }?.let { paths.add(Path.of(it).resolve("usr/bin")) }
    compilerExecutable(swiftc)?.toRealPath()?.parent?.let { bin ->
        paths.add(bin)
        var ancestor: Path? = bin
        repeat(7) {
            ancestor?.let { paths.add(it.resolve("Platforms/Windows.platform/Developer/SDKs/Windows.sdk/usr/bin")) }
            ancestor = ancestor?.parent
        }
    }
    val runtimeDirectories = linkedSetOf<Path>()
    var visited = 0
    for (root in paths.filter { it.isDirectory() }) {
        runtimeDirectories.add(root)
        Files.walk(root, 6).use { entries ->
            val iterator = entries.iterator()
            while (iterator.hasNext()) {
                val entry = iterator.next()
                check(++visited <= 100_000) { "Swift runtime search exceeded 100,000 entries; set SWIKO_SWIFT_RUNTIME_DIR to runtime leaf directories" }
                if (entry.isRegularFile() && entry.extension.equals("dll", ignoreCase = true)) runtimeDirectories.add(entry.parent)
            }
        }
    }
    return runtimeDirectories.map { it.toString() }
}

private fun String.normalizeLines() = replace("\r\n", "\n")

import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator
import java.util.concurrent.TimeUnit
import kotlin.io.path.*

/** Compiles actual emitted Swift/Kotlin applications and host integrations. */
fun main(arguments: Array<String>) {
    if (arguments.size != 3) {
        System.err.println("Usage: compiler-conformance <swiko.jar> <swiko-native> <project-root>")
        kotlin.system.exitProcess(2)
    }
    val jar = Path.of(arguments[0]).toAbsolutePath()
    val native = Path.of(arguments[1]).toAbsolutePath()
    val project = Path.of(arguments[2]).toAbsolutePath()
    require(jar.isRegularFile()) { "Missing JVM frontend: $jar" }
    require(native.isRegularFile()) { "Missing native frontend: $native" }
    require(project.isDirectory()) { "Missing project root: $project" }
    val java = Path.of(System.getProperty("java.home"), "bin", "java").toString()
    val kotlinc = System.getenv("SWIKO_KOTLINC")?.takeIf { it.isNotBlank() } ?: "kotlinc"
    val swiftc = System.getenv("SWIKO_SWIFTC")?.takeIf { it.isNotBlank() } ?: "swiftc"
    val build = project.resolve("build").createDirectories()
    val temporary = Files.createTempDirectory(build, "compiler-tests-")
    val moduleCache = temporary.resolve("module-cache").createDirectories().toString()
    val captures = temporary.resolve("captures").createDirectories()
    var captureNumber = 0
    var passed = 0
    var failed = 0
    data class Result(val status: Int, val stdout: String, val stderr: String)
    fun run(command: List<String>, directory: Path, seconds: Long = 20): Result {
        val number = captureNumber++
        val stdout = captures.resolve("$number.out")
        val stderr = captures.resolve("$number.err")
        val process = ProcessBuilder(command).directory(directory.toFile())
            .redirectOutput(stdout.toFile()).redirectError(stderr.toFile()).start()
        val finished = process.waitFor(seconds, TimeUnit.SECONDS)
        if (!finished) {
            process.destroyForcibly()
            process.waitFor()
            error("Process timed out after ${seconds}s: ${command.first()}")
        }
        return Result(process.exitValue(), stdout.readText(), stderr.readText())
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
        val binary = directory.resolve(if (target == "swift") "application" else "application.jar")
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
                fun main() {
                    println("host-before")
                    val first = swikoRun()
                    val second = swikoRun()
                    println(first == second)
                    print(first)
                    swikoMain()
                    println("host-after")
                }
            """.trimIndent() + "\n")
            if (target == "swift") {
                val suffix = if (System.getProperty("os.name").lowercase().contains("mac")) "dylib" else "so"
                val libraryFile = directory.resolve("libSwikoGenerated.$suffix")
                val moduleFile = directory.resolve("SwikoGenerated.swiftmodule")
                val emittedLibrary = run(listOf(
                    swiftc, "-module-cache-path", moduleCache, "-parse-as-library", "-emit-library", "-emit-module", "-module-name", "SwikoGenerated",
                    "-emit-module-path", moduleFile.toString(), generated.toString(), "-o", libraryFile.toString()
                ), directory, 120)
                check(emittedLibrary.status == 0) { "Swift library compilation failed: ${emittedLibrary.stderr.take(6500)}" }
                check(libraryFile.isRegularFile() && moduleFile.isRegularFile()) { "Swift library/module artifact missing" }
                val emittedHost = run(listOf(
                    swiftc, "-module-cache-path", moduleCache, "-parse-as-library", "-I", directory.toString(), "-L", directory.toString(), "-lSwikoGenerated",
                    "-Xlinker", "-rpath", "-Xlinker", directory.toString(), host.toString(), "-o", binary.toString()
                ), directory, 120)
                check(emittedHost.status == 0) { "Swift host compilation/linkage failed: ${emittedHost.stderr.take(6500)}" }
                check(binary.isRegularFile()) { "Swift host artifact missing" }
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
            for (library in listOf(false, true)) {
                val mode = if (library) "library" else "standalone"
                verify("$frontendName/$target/$mode") {
                    val directory = temporary.resolve("$frontendName-$target-$mode").createDirectories()
                    val code = generatedCode(frontend, showcaseSource, target, library, directory)
                    val executable = compile(target, code, directory, library)
                    val result = run(executable, directory)
                    success(result, "compiled application")
                    val body = interpreterOutput ?: expectedShowcase
                    val expected = if (library) "host-before\ntrue\n" + body + body + "host-after\n" else body
                    check(result.stdout == expected) {
                        "Compiled output differs from interpreter; expected ${expected.take(500)}, got ${result.stdout.take(500)}"
                    }
                }
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

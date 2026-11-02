import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import java.util.concurrent.TimeUnit
import kotlin.io.path.*

/** Black-box CLI, UTF-8, source-normalization, and input-limit release checks. */
fun main(arguments: Array<String>) {
    // Windows' legacy ProcessBuilder mode does not escape embedded quotes.
    System.setProperty("jdk.lang.Process.allowAmbiguousCommands", "false")
    if (arguments.size != 3) {
        System.err.println("Usage: cli-conformance <swiko.jar> <swiko-native|--jvm-only> <project-root>")
        kotlin.system.exitProcess(2)
    }
    val project = Path.of(arguments[2]).toAbsolutePath()
    require(project.resolve("RELEASE_CONTRACT.md").isRegularFile()) { "Project contract not found" }
    val version = project.resolve("VERSION").readText().trim()
    require(version.matches(Regex("[0-9]+\\.[0-9]+\\.[0-9]+(?:-[0-9A-Za-z.-]+)?"))) { "Invalid project VERSION" }
    val jvmOnly = arguments[1] == "--jvm-only"
    val kotlinJar = Path.of(arguments[0]).toAbsolutePath()
    val swiftBinary = if (jvmOnly) null else Path.of(arguments[1]).toAbsolutePath()
    require(kotlinJar.isRegularFile()) { "Kotlin interpreter not found: $kotlinJar" }
    require(swiftBinary == null || swiftBinary.isRegularFile()) { "Swift interpreter not found: $swiftBinary" }
    val javaName = if (System.getProperty("os.name").lowercase().contains("windows")) "java.exe" else "java"
    val java = Path.of(System.getProperty("java.home"), "bin", javaName).toString()
    val implementations = buildList {
        add("Kotlin" to listOf(java, "-jar", kotlinJar.toString()))
        if (swiftBinary != null) add("Swift" to listOf(swiftBinary.toString()))
    }
    val temporary = Files.createTempDirectory("swiko-cli-")
    var passed = 0
    var failed = 0
    var serial = 0
    data class Result(val status: Int, val out: String, val err: String, val timedOut: Boolean)
    fun run(command: List<String>, options: List<String>, input: ByteArray = byteArrayOf()): Result {
        val id = serial++
        val stdin = temporary.resolve("stdin-$id")
        val stdout = temporary.resolve("stdout-$id")
        val stderr = temporary.resolve("stderr-$id")
        stdin.writeBytes(input)
        val windowsJava = javaName == "java.exe" && command.first() == java
        val transportedOptions = if (windowsJava && options.isNotEmpty() && options.first() != "--utf8-args") {
            val payload = options.joinToString("\u0000", postfix = "\u0000").toByteArray(Charsets.UTF_8)
            listOf("--utf8-args", Base64.getEncoder().encodeToString(payload))
        } else options
        val process = ProcessBuilder(command + transportedOptions).directory(temporary.toFile())
            .redirectInput(stdin.toFile()).redirectOutput(stdout.toFile()).redirectError(stderr.toFile()).start()
        val complete = process.waitFor(15, TimeUnit.SECONDS)
        if (!complete) { process.destroyForcibly(); process.waitFor() }
        return Result(process.exitValue(), stdout.readText().replace("\r\n", "\n"), stderr.readText().replace("\r\n", "\n"), !complete)
    }
    fun verify(label: String, condition: Boolean, result: Result) {
        if (condition && !result.timedOut) passed++ else {
            failed++
            System.err.println("FAIL $label: status=${result.status}, timeout=${result.timedOut}, stdout=${result.out.take(180).escaped()}, stderr=${result.err.take(300).escaped()}")
        }
    }
    fun source(name: String, bytes: ByteArray): Path = temporary.resolve(name).also { it.writeBytes(bytes) }
    val valid = "print(42)\n"
    val validFile = source("valid.swk", valid.toByteArray())
    val hyphenFile = source("-sample.swk", valid.toByteArray())
    val invalidFile = source("invalid.swk", "if false { val wrong: Int = \"x\" }\n".toByteArray())
    val malformed = source("malformed.swk", "print(\"".toByteArray() + byteArrayOf(0xC3.toByte(), 0x28) + "\")\n".toByteArray())
    val oversize = source("oversize.swk", ByteArray(1_048_577) { ' '.code.toByte() })
    val tokenLimit = source("tokens.swk", "0;\n".repeat(34_000).toByteArray())
    val normalized = source("normalized.swk", byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) +
        "val name = \"世界🙂\"\r\nprint(name)\rprint(7)\n".toByteArray())
    val scalar = source("scalar.swk", "//🙂\r\nprint(\"🙂\") @\r\n".toByteArray())
    val spacedDirectory = temporary.resolve("folder with spaces 世界").createDirectories()
    val unicodeFile = spacedDirectory.resolve("Unicode file 🙂.swk").also {
        it.writeText("print(\"世界🙂\")\n")
    }
    val unicodeErrorFile = spacedDirectory.resolve("Unicode diagnostic 世界.swk").also {
        it.writeText("print(missing)\n")
    }
    val invalidUtf8 = malformed.readBytes()
    val diagnostic = Regex(":\\d+:\\d+: error: ")
    try {
        for ((implementation, command) in implementations) {
            fun test(name: String, options: List<String>, input: ByteArray = byteArrayOf(), check: (Result) -> Boolean) {
                val result = run(command, options, input)
                verify("$implementation/$name", check(result), result)
            }
            test("version", listOf("--version")) { it.status == 0 && it.out == "Swiko $version\n" && it.err.isEmpty() }
            test("help", listOf("--help")) { it.status == 0 && it.out.contains("--check") && it.out.contains("--emit-swift") && it.err.isEmpty() }
            val usageCases = listOf(
                "missing-source" to emptyList(),
                "unknown-option" to listOf("--unknown"),
                "conflicting-modes" to listOf("--check", "--emit-kotlin", validFile.toString()),
                "duplicate-mode" to listOf("--check", "--check", validFile.toString()),
                "missing-eval" to listOf("--eval"),
                "extra-source" to listOf(validFile.toString(), validFile.toString()),
                "library-without-emission" to listOf("--library", validFile.toString()),
                "library-with-check" to listOf("--library", "--check", validFile.toString()),
                "duplicate-library" to listOf("--emit-kotlin", "--library", "--library", validFile.toString()),
                "help-extra-source" to listOf("--help", validFile.toString()),
                "version-extra-source" to listOf("--version", validFile.toString()),
                "eval-extra-source" to listOf("--eval", valid, validFile.toString()),
                "eval-trailing-option-terminator" to listOf("--eval", valid, "--"),
                "missing-escaped-source" to listOf("--")
            )
            for ((name, options) in usageCases) test(name, options) { it.status == 2 && it.out.isEmpty() && it.err.isNotEmpty() }
            test("file", listOf(validFile.toString())) { it.status == 0 && it.out == "42\n" && it.err.isEmpty() }
            test("unicode-path-with-spaces", listOf(unicodeFile.toString())) { it.status == 0 && it.out == "世界🙂\n" && it.err.isEmpty() }
            test("escaped-filename", listOf("--", hyphenFile.fileName.toString())) { it.status == 0 && it.out == "42\n" && it.err.isEmpty() }
            test("eval", listOf("--eval", valid)) { it.status == 0 && it.out == "42\n" && it.err.isEmpty() }
            val transported = Base64.getEncoder().encodeToString("--eval\u0000print(\"世界🙂\")\u0000".toByteArray(Charsets.UTF_8))
            test("utf8-argument-transport", listOf("--utf8-args", transported)) { it.status == 0 && it.out == "世界🙂\n" && it.err.isEmpty() }
            test("invalid-argument-transport", listOf("--utf8-args", "!!!")) { it.status == 2 && it.out.isEmpty() }
            test("unterminated-argument-transport", listOf("--utf8-args", "YWJj")) { it.status == 2 && it.out.isEmpty() }
            test("nonutf8-argument-transport", listOf("--utf8-args", "/wA=")) { it.status == 2 && it.out.isEmpty() }
            test("stdin", listOf("-"), valid.toByteArray()) { it.status == 0 && it.out == "42\n" && it.err.isEmpty() }
            test("check-file", listOf("--check", validFile.toString())) { it.status == 0 && it.out.isEmpty() && it.err.isEmpty() }
            test("check-eval", listOf("--check", "--eval", valid)) { it.status == 0 && it.out.isEmpty() && it.err.isEmpty() }
            test("check-stdin", listOf("--check", "-"), valid.toByteArray()) { it.status == 0 && it.out.isEmpty() && it.err.isEmpty() }
            test("check-has-no-runtime-effects", listOf("--check", "--eval", "print(1 / 0)")) { it.status == 0 && it.out.isEmpty() && it.err.isEmpty() }
            test("check-skipped-static-error", listOf("--check", invalidFile.toString())) { it.status == 1 && it.out.isEmpty() && diagnostic.containsMatchIn(it.err) }
            test("eval-error-location", listOf("--eval", "print(\"🙂\") @")) { it.status == 1 && it.out.isEmpty() && it.err.startsWith("<eval>:1:12: error:") }
            test("stdin-error-location", listOf("-"), "print(\"🙂\") @".toByteArray()) { it.status == 1 && it.out.isEmpty() && it.err.startsWith("<stdin>:1:12: error:") }
            test("unicode-scalar-line-column", listOf(scalar.toString())) { it.status == 1 && it.out.isEmpty() && it.err.startsWith("$scalar:2:12: error:") }
            test("bom-crlf-bare-cr", listOf(normalized.toString())) { it.status == 0 && it.out == "世界🙂\n7\n" && it.err.isEmpty() }
            test("bom-stdin", listOf("-"), byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + valid.toByteArray()) { it.status == 0 && it.out == "42\n" && it.err.isEmpty() }
            test("strict-utf8-file", listOf(malformed.toString())) { it.status == 1 && it.out.isEmpty() && diagnostic.containsMatchIn(it.err) }
            test("strict-utf8-stdin", listOf("-"), invalidUtf8) { it.status == 1 && it.out.isEmpty() && diagnostic.containsMatchIn(it.err) }
            test("source-byte-limit-file", listOf("--check", oversize.toString())) { it.status == 1 && it.out.isEmpty() && diagnostic.containsMatchIn(it.err) }
            test("source-byte-limit-stdin", listOf("--check", "-"), oversize.readBytes()) { it.status == 1 && it.out.isEmpty() && diagnostic.containsMatchIn(it.err) }
            test("token-limit", listOf("--check", tokenLimit.toString())) { it.status == 1 && it.out.isEmpty() && diagnostic.containsMatchIn(it.err) }
            test("buffered-runtime-error", listOf("--eval", "print(\"before\"); print(1 / 0)")) { it.status == 1 && it.out.isEmpty() && diagnostic.containsMatchIn(it.err) }
            if (implementation == "Kotlin") {
                val legacyCommand = listOf(command.first(), "-Dfile.encoding=windows-1252") + command.drop(1)
                val unicodeSource = "print(\"世界 👋\")"
                val output = run(legacyCommand, listOf("--eval", unicodeSource))
                verify("Kotlin/legacy-charset-unicode-output", output.status == 0 && output.out == "世界 👋\n" && output.err.isEmpty(), output)
                val options = listOf("--emit-swift", "--eval", unicodeSource)
                val normalEmission = run(command, options)
                val legacyEmission = run(legacyCommand, options)
                verify("Kotlin/legacy-charset-source-emission", normalEmission.status == 0 && normalEmission.err.isEmpty() &&
                    legacyEmission.status == 0 && legacyEmission.err.isEmpty() && legacyEmission.out == normalEmission.out, legacyEmission)
                val error = run(legacyCommand, listOf(unicodeErrorFile.toString()))
                verify("Kotlin/legacy-charset-unicode-diagnostic", error.status == 1 && error.out.isEmpty() &&
                    error.err.startsWith("$unicodeErrorFile:1:7: error:"), error)
            }
        }
    } finally {
        unicodeFile.deleteIfExists()
        unicodeErrorFile.deleteIfExists()
        spacedDirectory.deleteIfExists()
        Files.list(temporary).use { files -> files.forEach { Files.deleteIfExists(it) } }
        Files.deleteIfExists(temporary)
    }
    println("$passed passed, $failed failed (CLI and input checks across ${implementations.size} implementations)")
    if (failed != 0) kotlin.system.exitProcess(1)
}

private fun String.escaped(): String = "\"" + replace("\\", "\\\\").replace("\n", "\\n").replace("\r", "\\r").replace("\"", "\\\"") + "\""

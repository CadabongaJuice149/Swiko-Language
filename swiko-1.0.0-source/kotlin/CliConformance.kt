import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.*

/** Black-box CLI, UTF-8, source-normalization, and input-limit release checks. */
fun main(arguments: Array<String>) {
    if (arguments.size != 3) {
        System.err.println("Usage: cli-conformance <swiko.jar> <swiko-native> <project-root>")
        kotlin.system.exitProcess(2)
    }
    val project = Path.of(arguments[2]).toAbsolutePath()
    require(project.resolve("RELEASE_CONTRACT.md").isRegularFile()) { "Project contract not found" }
    val java = Path.of(System.getProperty("java.home"), "bin", "java").toString()
    val implementations = listOf(
        "Kotlin" to listOf(java, "-jar", Path.of(arguments[0]).toAbsolutePath().toString()),
        "Swift" to listOf(Path.of(arguments[1]).toAbsolutePath().toString())
    )
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
        val process = ProcessBuilder(command + options).directory(temporary.toFile())
            .redirectInput(stdin.toFile()).redirectOutput(stdout.toFile()).redirectError(stderr.toFile()).start()
        val complete = process.waitFor(15, TimeUnit.SECONDS)
        if (!complete) { process.destroyForcibly(); process.waitFor() }
        return Result(process.exitValue(), stdout.readText(), stderr.readText(), !complete)
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
    val invalidUtf8 = malformed.readBytes()
    val diagnostic = Regex(":\\d+:\\d+: error: ")
    try {
        for ((implementation, command) in implementations) {
            fun test(name: String, options: List<String>, input: ByteArray = byteArrayOf(), check: (Result) -> Boolean) {
                val result = run(command, options, input)
                verify("$implementation/$name", check(result), result)
            }
            test("version", listOf("--version")) { it.status == 0 && it.out == "Swiko 1.0.0\n" && it.err.isEmpty() }
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
            test("escaped-filename", listOf("--", hyphenFile.fileName.toString())) { it.status == 0 && it.out == "42\n" && it.err.isEmpty() }
            test("eval", listOf("--eval", valid)) { it.status == 0 && it.out == "42\n" && it.err.isEmpty() }
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
        }
    } finally {
        Files.list(temporary).use { files -> files.forEach { Files.deleteIfExists(it) } }
        Files.deleteIfExists(temporary)
    }
    println("$passed passed, $failed failed (CLI and input checks across ${implementations.size} implementations)")
    if (failed != 0) kotlin.system.exitProcess(1)
}

private fun String.escaped(): String = "\"" + replace("\\", "\\\\").replace("\n", "\\n").replace("\r", "\\r").replace("\"", "\\\"") + "\""

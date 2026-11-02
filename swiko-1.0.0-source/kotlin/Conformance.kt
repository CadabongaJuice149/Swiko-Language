import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.*

/** Shared black-box tests for the Kotlin and Swift Swiko implementations. */
fun main(arguments: Array<String>) {
    if (arguments.size != 3) {
        System.err.println("Usage: conformance <swiko.jar> <swiko-swift> <cases-directory>")
        kotlin.system.exitProcess(2)
    }
    val kotlinJar = Path.of(arguments[0]).toAbsolutePath()
    val swiftBinary = Path.of(arguments[1]).toAbsolutePath()
    val cases = Path.of(arguments[2]).toAbsolutePath()
    require(kotlinJar.isRegularFile()) { "Kotlin interpreter jar not found: $kotlinJar" }
    require(swiftBinary.isRegularFile()) { "Swift interpreter not found: $swiftBinary" }
    require(cases.isDirectory()) { "Test cases not found: $cases" }
    val fixtures = cases.listDirectoryEntries("*.swk").sortedBy { it.fileName.toString() }
    require(fixtures.isNotEmpty()) { "No .swk test cases found" }
    val javaCommand = Path.of(System.getProperty("java.home"), "bin", "java").toString()
    val implementations = listOf(
        "Kotlin" to listOf(javaCommand, "-jar", kotlinJar.toString()),
        "Swift" to listOf(swiftBinary.toString())
    )
    val location = Regex(":\\d+:\\d+: error: ")
    var passed = 0
    var failed = 0
    val temporary = Files.createTempDirectory("swiko-conformance-")
    try {
        for ((implementation, command) in implementations) {
            for (fixture in fixtures) {
                val name = fixture.nameWithoutExtension
                val expectedOutput = fixture.resolveSibling("$name.out")
                val expectedError = fixture.resolveSibling("$name.error")
                check(expectedOutput.exists() xor expectedError.exists()) {
                    "$fixture needs exactly one of .out or .error"
                }
                val stdoutFile = temporary.resolve("stdout")
                val stderrFile = temporary.resolve("stderr")
                val process = ProcessBuilder(command + fixture.toString())
                    .redirectOutput(stdoutFile.toFile())
                    .redirectError(stderrFile.toFile())
                    .start()
                val finished = process.waitFor(15, TimeUnit.SECONDS)
                if (!finished) {
                    process.destroyForcibly()
                    process.waitFor()
                }
                val stdout = stdoutFile.readText().replace("\r\n", "\n")
                val stderr = stderrFile.readText().replace("\r\n", "\n")
                val problem = when {
                    !finished -> "timed out after 15 seconds"
                    expectedOutput.exists() && process.exitValue() != 0 ->
                        "expected success, exit ${process.exitValue()}: ${stderr.trim()}"
                    expectedOutput.exists() && stdout != expectedOutput.readText() ->
                        "output mismatch; expected ${expectedOutput.readText().quoted()}, got ${stdout.quoted()}"
                    expectedOutput.exists() && stderr.isNotEmpty() ->
                        "unexpected stderr: ${stderr.quoted()}"
                    expectedError.exists() && process.exitValue() != 1 ->
                        "expected language error exit 1, got ${process.exitValue()}: ${stderr.quoted()}"
                    expectedError.exists() && stdout.isNotEmpty() ->
                        "language error leaked buffered output: ${stdout.take(120).quoted()}"
                    expectedError.exists() && !location.containsMatchIn(stderr) ->
                        "error did not include source line and column: ${stderr.quoted()}"
                    expectedError.exists() && expectedError.readText().trim().let { it.isNotEmpty() && !stderr.contains(it) } ->
                        "error missing expected text: ${stderr.quoted()}"
                    else -> null
                }
                if (problem == null) {
                    passed++
                } else {
                    failed++
                    System.err.println("FAIL $implementation/$name: $problem")
                }
            }
        }
    } finally {
        temporary.resolve("stdout").deleteIfExists()
        temporary.resolve("stderr").deleteIfExists()
        temporary.deleteIfExists()
    }
    println("$passed passed, $failed failed (${fixtures.size} cases × ${implementations.size} implementations)")
    if (failed != 0) kotlin.system.exitProcess(1)
}

private fun String.quoted(): String = "\"" + replace("\\", "\\\\").replace("\n", "\\n").replace("\t", "\\t").replace("\"", "\\\"") + "\""

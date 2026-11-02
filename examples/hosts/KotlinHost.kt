fun main() {
    val first = swikoRun()
    val second = swikoRun()
    check(first == second) { "Each call must use fresh Swiko state" }
    print(first)
}

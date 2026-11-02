import Foundation

@main
struct HostApplication {
    static func main() throws {
        let first = try swikoRun()
        let second = try swikoRun()
        precondition(first == second, "Each call must use fresh Swiko state")
        print(first, terminator: "")
    }
}

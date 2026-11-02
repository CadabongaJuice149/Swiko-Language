import Foundation
#if os(Windows)
import ucrt
#endif

// Swiko's native interpreter. The matching JVM implementation is Kotlin.
struct SourcePoint {
    let line: Int
    let column: Int
    var key: String { "\(line):\(column)" }
}

struct SwikoError: Error, CustomStringConvertible {
    let point: SourcePoint
    let message: String
    var description: String { "\(point.line):\(point.column): \(message)" }
}

enum TokenKind { case name, integer, string, symbol, newline, end }

struct Token {
    let kind: TokenKind
    let text: String
    let point: SourcePoint
}

final class Lexer {
    private let characters: [Unicode.Scalar]
    private var index = 0
    private var line = 1
    private var column = 1
    private var parentheses = 0

    init(_ source: String) { characters = Array(source.unicodeScalars) }

    private var current: Unicode.Scalar? {
        index < characters.count ? characters[index] : nil
    }
    private var point: SourcePoint { SourcePoint(line: line, column: column) }
    private func advance() {
        guard let scalar = current else { return }
        index += 1
        if scalar == "\n" { line += 1; column = 1 } else { column += 1 }
    }
    private func letter(_ scalar: Unicode.Scalar) -> Bool {
        (65...90).contains(scalar.value) || (97...122).contains(scalar.value) || scalar == "_"
    }
    private func digit(_ scalar: Unicode.Scalar) -> Bool { (48...57).contains(scalar.value) }

    func scan() throws -> [Token] {
        var tokens: [Token] = []
        while let scalar = current {
            if tokens.count >= 100_000 { throw SwikoError(point: point, message: "Token limit exceeded") }
            let start = point
            if scalar == " " || scalar == "\t" || scalar == "\r" { advance(); continue }
            if scalar == "\n" {
                advance()
                if parentheses == 0 { tokens.append(Token(kind: .newline, text: "\n", point: start)) }
                continue
            }
            if scalar == "/", index + 1 < characters.count, characters[index + 1] == "/" {
                while let c = current, c != "\n" { advance() }
                continue
            }
            if letter(scalar) {
                var name = ""
                while let c = current, letter(c) || digit(c) { name.unicodeScalars.append(c); advance() }
                tokens.append(Token(kind: .name, text: name, point: start))
                continue
            }
            if digit(scalar) {
                var number = ""
                while let c = current, digit(c) { number.unicodeScalars.append(c); advance() }
                tokens.append(Token(kind: .integer, text: number, point: start))
                continue
            }
            if scalar == "\"" {
                advance()
                var string = ""
                var closed = false
                while let c = current {
                    if c == "\"" { advance(); closed = true; break }
                    if c == "\n" || c == "\r" { throw SwikoError(point: point, message: "Newline in string literal") }
                    if c == "\\" {
                        advance()
                        guard let escape = current else { break }
                        switch escape {
                        case "n": string += "\n"
                        case "r": string += "\r"
                        case "t": string += "\t"
                        case "\"": string += "\""
                        case "\\": string += "\\"
                        default: throw SwikoError(point: point, message: "Unknown string escape")
                        }
                        advance()
                    } else { string.unicodeScalars.append(c); advance() }
                }
                guard closed else { throw SwikoError(point: start, message: "Unterminated string literal") }
                tokens.append(Token(kind: .string, text: string, point: start))
                continue
            }
            var symbol = String(scalar)
            if index + 1 < characters.count {
                let pair = symbol + String(characters[index + 1])
                if ["==", "!=", "<=", ">=", "&&", "||", "??"].contains(pair) { symbol = pair; advance() }
            }
            guard ["+", "-", "*", "/", "%", "!", "=", "<", ">", "?", "(", ")", "{", "}", ":", ",", ";", "==", "!=", "<=", ">=", "&&", "||", "??"].contains(symbol) else {
                throw SwikoError(point: start, message: "Unexpected character '\(symbol)'")
            }
            if symbol == "(" { parentheses += 1 }
            if symbol == ")" { parentheses = max(0, parentheses - 1) }
            advance()
            tokens.append(Token(kind: .symbol, text: symbol, point: start))
        }
        tokens.append(Token(kind: .end, text: "", point: point))
        return tokens
    }
}

struct CheckResult {
    let expressionTypes: [String: ValueType]
    let resolvedNames: [String: String]
    let declarationTypes: [String: ValueType]
}

private enum CheckedSymbol {
    case binding(ValueType, Bool, String)
    case function([ValueType], ValueType, String)
    var target: String {
        switch self { case let .binding(_, _, target), let .function(_, _, target): return target }
    }
}

private final class CheckScope {
    var symbols: [String: CheckedSymbol] = [:]
    let parent: CheckScope?
    init(_ parent: CheckScope? = nil) { self.parent = parent }
    func lookup(_ name: String) -> CheckedSymbol? { symbols[name] ?? parent?.lookup(name) }
}

final class Checker {
    private var expressionTypes: [String: ValueType] = [:]
    private var resolvedNames: [String: String] = [:]
    private var declarationTypes: [String: ValueType] = [:]
    private var nextName = 0
    private let intType = ValueType(name: "Int", nullable: false)
    private let boolType = ValueType(name: "Bool", nullable: false)
    private let stringType = ValueType(name: "String", nullable: false)
    private let unitType = ValueType(name: "Unit", nullable: false)
    private func name() -> String { defer { nextName += 1 }; return "s\(nextName)" }
    private func accepts(_ expected: ValueType, _ actual: ValueType) -> Bool {
        if actual.name == "null" { return expected.nullable }
        return expected.name == actual.name && (expected.nullable || !actual.nullable)
    }
    private func require(_ actual: ValueType, _ expected: ValueType, _ point: SourcePoint) throws {
        if !accepts(expected, actual) { throw SwikoError(point: point, message: "Expected \(expected), received \(actual)") }
    }
    private func define(_ name: String, _ symbol: CheckedSymbol, _ scope: CheckScope, _ point: SourcePoint) throws {
        if name == "print" || scope.symbols[name] != nil { throw SwikoError(point: point, message: "Duplicate or reserved declaration '\(name)'") }
        scope.symbols[name] = symbol
        resolvedNames[point.key] = symbol.target
    }
    func check(_ statements: [Statement]) throws -> CheckResult {
        _ = try check(statements, scope: CheckScope(), returning: nil)
        return CheckResult(expressionTypes: expressionTypes, resolvedNames: resolvedNames, declarationTypes: declarationTypes)
    }
    @discardableResult private func check(_ statements: [Statement], scope: CheckScope, returning: ValueType?) throws -> Bool {
        var returns = false
        for statement in statements {
            let statementReturns = try check(statement, scope: scope, returning: returning)
            returns = returns || statementReturns
        }
        return returns
    }
    private func check(_ statement: Statement, scope: CheckScope, returning: ValueType?) throws -> Bool {
        switch statement {
        case let .binding(identifier, mutable, annotation, initializer, point):
            let actual = try expression(initializer, scope)
            if annotation == nil && actual.name == "null" { throw SwikoError(point: point, message: "Null initialization requires a nullable type annotation") }
            let type = annotation ?? actual
            try require(actual, type, point)
            try define(identifier, .binding(type, mutable, name()), scope, point)
            declarationTypes[point.key] = type
        case let .assignment(identifier, value, point):
            if identifier == "print" { throw SwikoError(point: point, message: "'print' is reserved") }
            guard let symbol = scope.lookup(identifier) else { throw SwikoError(point: point, message: "Unknown name '\(identifier)'") }
            guard case let .binding(type, mutable, target) = symbol, mutable else { throw SwikoError(point: point, message: "Cannot assign immutable '\(identifier)'") }
            resolvedNames[point.key] = target
            try require(expression(value, scope), type, point)
        case let .expression(value): _ = try expression(value, scope)
        case let .function(identifier, parameters, result, body, point):
            try define(identifier, .function(parameters.map { $0.type }, result, name()), scope, point)
            let local = CheckScope(scope)
            for parameter in parameters {
                try define(parameter.name, .binding(parameter.type, false, name()), local, parameter.point)
                declarationTypes[parameter.point.key] = parameter.type
            }
            let returns = try check(body, scope: local, returning: result)
            if result.name != "Unit" && !returns { throw SwikoError(point: point, message: "Function must return \(result) on all paths") }
        case let .condition(condition, yes, no, _):
            try require(expression(condition, scope), boolType, condition.point)
            let yesReturns = try check(yes, scope: CheckScope(scope), returning: returning)
            let noReturns = try check(no, scope: CheckScope(scope), returning: returning)
            return yesReturns && noReturns
        case let .loop(condition, body, _):
            try require(expression(condition, scope), boolType, condition.point)
            _ = try check(body, scope: CheckScope(scope), returning: returning)
        case let .returning(value, point):
            guard let expected = returning else { throw SwikoError(point: point, message: "return is only valid inside a function") }
            let actual = try value.map { try expression($0, scope) } ?? unitType
            try require(actual, expected, point)
            return true
        case let .block(body, _): return try check(body, scope: CheckScope(scope), returning: returning)
        }
        return false
    }
    private func expression(_ value: Expression, _ scope: CheckScope) throws -> ValueType {
        let type: ValueType
        switch value {
        case let .literal(literal, _): type = ValueType(name: literal.typeName, nullable: false)
        case let .name(identifier, point):
            guard let symbol = scope.lookup(identifier) else { throw SwikoError(point: point, message: "Unknown name '\(identifier)'") }
            guard case let .binding(bindingType, _, target) = symbol else { throw SwikoError(point: point, message: "Functions are only usable in calls") }
            resolvedNames[point.key] = target
            type = bindingType
        case let .unary(operation, operand, point):
            type = operation == "!" ? boolType : intType
            try require(expression(operand, scope), type, point)
        case let .binary(left, operation, right, point):
            let a = try expression(left, scope)
            let b = try expression(right, scope)
            switch operation {
            case "??":
                if a.name == "null" { type = b }
                else {
                    if b.name != "null" && a.name != b.name { throw SwikoError(point: point, message: "Coalescing requires matching types") }
                    type = ValueType(name: a.name, nullable: a.nullable && (b.nullable || b.name == "null"))
                }
            case "==", "!=":
                if a.name != "null" && b.name != "null" && a.name != b.name { throw SwikoError(point: point, message: "Equality requires matching types") }
                type = boolType
            case "&&", "||":
                try require(a, boolType, point); try require(b, boolType, point)
                type = boolType
            case "+" where a == stringType && b == stringType: type = stringType
            default:
                try require(a, intType, point); try require(b, intType, point)
                type = ["<", "<=", ">", ">="].contains(operation) ? boolType : intType
            }
        case let .call(callee, arguments, point):
            guard case let .name(identifier, namePoint) = callee else { throw SwikoError(point: point, message: "Only named functions can be called") }
            if identifier == "print" {
                if arguments.count != 1 { throw SwikoError(point: point, message: "print expects one argument") }
                _ = try expression(arguments[0], scope)
                type = unitType
            } else {
                guard let symbol = scope.lookup(identifier) else { throw SwikoError(point: point, message: "Unknown function '\(identifier)'") }
                guard case let .function(parameters, result, target) = symbol else { throw SwikoError(point: point, message: "'\(identifier)' is not a function") }
                if parameters.count != arguments.count { throw SwikoError(point: point, message: "Incorrect argument count") }
                resolvedNames[namePoint.key] = target
                for (parameter, argument) in zip(parameters, arguments) { try require(expression(argument, scope), parameter, argument.point) }
                type = result
            }
        }
        expressionTypes[value.point.key] = type
        return type
    }
}

struct ValueType: Equatable, CustomStringConvertible {
    let name: String
    let nullable: Bool
    var description: String { name + (nullable ? "?" : "") }
    func accepts(_ value: Value) -> Bool {
        if case .null = value { return nullable }
        return value.typeName == name
    }
}

enum Value: Equatable {
    case integer(Int64), boolean(Bool), string(String), null, unit
    static func == (left: Value, right: Value) -> Bool {
        switch (left, right) {
        case let (.integer(a), .integer(b)): return a == b
        case let (.boolean(a), .boolean(b)): return a == b
        case let (.string(a), .string(b)): return a.unicodeScalars.elementsEqual(b.unicodeScalars)
        case (.null, .null), (.unit, .unit): return true
        default: return false
        }
    }
    var typeName: String {
        switch self {
        case .integer: return "Int"
        case .boolean: return "Bool"
        case .string: return "String"
        case .null: return "null"
        case .unit: return "Unit"
        }
    }
    var display: String {
        switch self {
        case let .integer(value): return String(value)
        case let .boolean(value): return value ? "true" : "false"
        case let .string(value): return value
        case .null: return "null"
        case .unit: return "Unit"
        }
    }
}

indirect enum Expression {
    case literal(Value, SourcePoint)
    case name(String, SourcePoint)
    case unary(String, Expression, SourcePoint)
    case binary(Expression, String, Expression, SourcePoint)
    case call(Expression, [Expression], SourcePoint)
    var point: SourcePoint {
        switch self {
        case let .literal(_, p), let .name(_, p), let .unary(_, _, p),
             let .binary(_, _, _, p), let .call(_, _, p): return p
        }
    }
}

struct Parameter {
    let name: String
    let type: ValueType
    let point: SourcePoint
}

indirect enum Statement {
    case binding(String, Bool, ValueType?, Expression, SourcePoint)
    case assignment(String, Expression, SourcePoint)
    case expression(Expression)
    case function(String, [Parameter], ValueType, [Statement], SourcePoint)
    case condition(Expression, [Statement], [Statement], SourcePoint)
    case loop(Expression, [Statement], SourcePoint)
    case returning(Expression?, SourcePoint)
    case block([Statement], SourcePoint)
    var point: SourcePoint {
        switch self {
        case let .binding(_, _, _, _, p), let .assignment(_, _, p),
             let .function(_, _, _, _, p), let .condition(_, _, _, p),
             let .loop(_, _, p), let .returning(_, p), let .block(_, p): return p
        case let .expression(expression): return expression.point
        }
    }
}

final class Parser {
    private let tokens: [Token]
    private var index = 0
    private var nesting = 0
    private let reserved: Set<String> = ["val", "var", "fun", "if", "else", "while", "return", "true", "false", "null", "print"]
    init(_ tokens: [Token]) { self.tokens = tokens }
    private var current: Token { tokens[index] }
    @discardableResult private func advance() -> Token {
        let token = current
        if token.kind != .end { index += 1 }
        return token
    }
    private func isText(_ text: String) -> Bool { current.text == text && current.kind != .string }
    @discardableResult private func match(_ text: String) -> Bool {
        if isText(text) { advance(); return true }
        return false
    }
    @discardableResult private func require(_ text: String) throws -> Token {
        guard isText(text) else { throw SwikoError(point: current.point, message: "Expected '\(text)'") }
        return advance()
    }
    private func identifier() throws -> Token {
        guard current.kind == .name, !reserved.contains(current.text) else {
            throw SwikoError(point: current.point, message: "Expected an identifier (keywords and print are reserved)")
        }
        return advance()
    }
    private func separators() { while current.kind == .newline || isText(";") { advance() } }
    private func lines() { while current.kind == .newline { advance() } }
    private func endStatement() throws {
        guard current.kind == .newline || current.kind == .end || isText(";") || isText("}") else {
            throw SwikoError(point: current.point, message: "Expected a newline or ';' after statement")
        }
    }
    private func enter() throws {
        nesting += 1
        if nesting > 128 { throw SwikoError(point: current.point, message: "Syntax nesting limit exceeded") }
    }
    private func type() throws -> ValueType {
        let token = try identifier()
        guard ["Int", "Bool", "String", "Unit"].contains(token.text) else {
            throw SwikoError(point: token.point, message: "Unknown type '\(token.text)'")
        }
        return ValueType(name: token.text, nullable: match("?"))
    }
    func parse() throws -> [Statement] {
        var statements: [Statement] = []
        separators()
        while current.kind != .end { statements.append(try statement()); separators() }
        return statements
    }
    private func block() throws -> [Statement] {
        try enter(); defer { nesting -= 1 }
        lines()
        try require("{")
        var statements: [Statement] = []
        separators()
        while !isText("}") {
            if current.kind == .end { throw SwikoError(point: current.point, message: "Expected '}'") }
            statements.append(try statement()); separators()
        }
        advance()
        return statements
    }
    private func statement() throws -> Statement {
        let token = current
        if match("val") || match("var") {
            let name = try identifier()
            let annotation = match(":") ? (try type()) : nil
            try require("=")
            let value = try expression()
            try endStatement()
            return .binding(name.text, token.text == "var", annotation, value, token.point)
        }
        if match("fun") {
            let name = try identifier()
            try require("(")
            var parameters: [Parameter] = []
            if !isText(")") {
                repeat {
                    let parameter = try identifier()
                    if parameters.contains(where: { $0.name == parameter.text }) {
                        throw SwikoError(point: parameter.point, message: "Duplicate parameter '\(parameter.text)'")
                    }
                    try require(":")
                    parameters.append(Parameter(name: parameter.text, type: try type(), point: parameter.point))
                } while match(",")
            }
            try require(")"); try require(":")
            let result = try type()
            return .function(name.text, parameters, result, try block(), token.point)
        }
        if match("if") {
            let condition = try expression()
            let yes = try block()
            // Consume line breaks only when they introduce an else.
            var lookahead = index
            while tokens[lookahead].kind == .newline { lookahead += 1 }
            var no: [Statement] = []
            if tokens[lookahead].kind == .name && tokens[lookahead].text == "else" {
                index = lookahead + 1
                if isText("if") {
                    try enter(); defer { nesting -= 1 }
                    no = [try statement()]
                } else { no = try block() }
            }
            return .condition(condition, yes, no, token.point)
        }
        if match("while") {
            let condition = try expression()
            return .loop(condition, try block(), token.point)
        }
        if match("return") {
            let value: Expression?
            if current.kind == .newline || current.kind == .end || isText(";") || isText("}") { value = nil }
            else { value = try expression() }
            try endStatement()
            return .returning(value, token.point)
        }
        if isText("{") { return .block(try block(), token.point) }
        if token.kind == .name, index + 1 < tokens.count, tokens[index + 1].kind == .symbol, tokens[index + 1].text == "=" {
            let name = try identifier()
            advance()
            let value = try expression()
            try endStatement()
            return .assignment(name.text, value, token.point)
        }
        let value = try expression()
        try endStatement()
        return .expression(value)
    }
    private func expression() throws -> Expression {
        try enter(); defer { nesting -= 1 }
        return try binary(0)
    }
    private let levels: [[String]] = [["??"], ["||"], ["&&"], ["==", "!="], ["<", "<=", ">", ">="], ["+", "-"], ["*", "/", "%"]]
    private func binary(_ level: Int) throws -> Expression {
        if level == levels.count { return try unary() }
        var left = try binary(level + 1)
        var chainLength = 0
        while current.kind == .symbol && levels[level].contains(current.text) {
            let operation = advance()
            chainLength += 1
            if chainLength > 128 { throw SwikoError(point: operation.point, message: "Expression chain limit exceeded") }
            if operation.text == "??" {
                try enter(); defer { nesting -= 1 }
                left = .binary(left, operation.text, try binary(level), operation.point)
            } else { left = .binary(left, operation.text, try binary(level + 1), operation.point) }
        }
        return left
    }
    private func unary() throws -> Expression {
        if isText("!") || isText("-") {
            let operation = advance()
            if operation.text == "-", current.kind == .integer, current.text == "9223372036854775808" {
                advance()
                return .literal(.integer(Int64.min), operation.point)
            }
            try enter(); defer { nesting -= 1 }
            return .unary(operation.text, try unary(), operation.point)
        }
        var value = try primary()
        while match("(") {
            var arguments: [Expression] = []
            if !isText(")") {
                repeat { arguments.append(try expression()) } while match(",")
            }
            try require(")")
            guard case .name = value else { throw SwikoError(point: value.point, message: "Only named functions can be called") }
            value = .call(value, arguments, value.point)
        }
        return value
    }
    private func primary() throws -> Expression {
        let token = advance()
        if token.kind == .integer {
            guard let number = Int64(token.text) else { throw SwikoError(point: token.point, message: "Integer literal out of range") }
            return .literal(.integer(number), token.point)
        }
        if token.kind == .string { return .literal(.string(token.text), token.point) }
        if token.kind == .name {
            if token.text == "true" { return .literal(.boolean(true), token.point) }
            if token.text == "false" { return .literal(.boolean(false), token.point) }
            if token.text == "null" { return .literal(.null, token.point) }
            if !reserved.contains(token.text) || token.text == "print" { return .name(token.text, token.point) }
        }
        if token.text == "(" && token.kind == .symbol {
            let value = try expression()
            try require(")")
            return value
        }
        throw SwikoError(point: token.point, message: "Expected an expression")
    }
}

final class Binding {
    var value: Value
    let type: ValueType
    let mutable: Bool
    init(value: Value, type: ValueType, mutable: Bool) { self.value = value; self.type = type; self.mutable = mutable }
}

final class Function {
    let parameters: [Parameter]
    let result: ValueType
    let body: [Statement]
    unowned let environment: Environment
    init(parameters: [Parameter], result: ValueType, body: [Statement], environment: Environment) {
        self.parameters = parameters; self.result = result; self.body = body; self.environment = environment
    }
}

enum Entry { case binding(Binding), function(Function) }

final class Environment {
    var entries: [String: Entry] = [:]
    let parent: Environment?
    init(parent: Environment? = nil) { self.parent = parent }
    func lookup(_ name: String) -> Entry? { entries[name] ?? parent?.lookup(name) }
    func declare(_ name: String, _ entry: Entry, at point: SourcePoint) throws {
        if entries[name] != nil || name == "print" { throw SwikoError(point: point, message: "Duplicate or reserved declaration '\(name)'") }
        entries[name] = entry
    }
}

struct Returned: Error { let value: Value; let point: SourcePoint }

final class Interpreter {
    private let checked: CheckResult
    private var output = ""
    private var steps = 0
    private var calls = 0
    private var evaluationDepth = 0
    init(checked: CheckResult) { self.checked = checked }
    private func tick(_ point: SourcePoint) throws {
        steps += 1
        if steps > 100_000 { throw SwikoError(point: point, message: "Execution step limit exceeded") }
    }
    func run(_ statements: [Statement]) throws -> String {
        let environment = Environment()
        try execute(statements, in: environment)
        return output
    }
    private func execute(_ statements: [Statement], in environment: Environment) throws {
        for statement in statements { try execute(statement, in: environment) }
    }
    private func scoped(_ statements: [Statement], in environment: Environment) throws {
        try execute(statements, in: Environment(parent: environment))
    }
    private func execute(_ statement: Statement, in environment: Environment) throws {
        try tick(statement.point)
        switch statement {
        case let .binding(name, mutable, annotation, expression, point):
            let value = try evaluate(expression, in: environment)
            let type = annotation ?? checked.declarationTypes[point.key] ?? ValueType(name: value.typeName, nullable: false)
            try check(value, type: type, at: point)
            try environment.declare(name, .binding(Binding(value: value, type: type, mutable: mutable)), at: point)
        case let .assignment(name, expression, point):
            guard let entry = environment.lookup(name) else { throw SwikoError(point: point, message: "Unknown name '\(name)'") }
            guard case let .binding(binding) = entry, binding.mutable else { throw SwikoError(point: point, message: "Cannot assign immutable '\(name)'") }
            let value = try evaluate(expression, in: environment)
            try check(value, type: binding.type, at: point)
            binding.value = value
        case let .expression(expression): _ = try evaluate(expression, in: environment)
        case let .function(name, parameters, result, body, point):
            try environment.declare(name, .function(Function(parameters: parameters, result: result, body: body, environment: environment)), at: point)
        case let .condition(expression, yes, no, _):
            let condition = try boolean(evaluate(expression, in: environment), at: expression.point)
            try scoped(condition ? yes : no, in: environment)
        case let .loop(expression, body, point):
            while try boolean(evaluate(expression, in: environment), at: expression.point) {
                try tick(point)
                try scoped(body, in: environment)
            }
        case let .returning(expression, point):
            guard calls > 0 else { throw SwikoError(point: point, message: "return is only valid inside a function") }
            throw Returned(value: try expression.map { try evaluate($0, in: environment) } ?? .unit, point: point)
        case let .block(body, _): try scoped(body, in: environment)
        }
    }
    private func check(_ value: Value, type: ValueType, at point: SourcePoint) throws {
        guard type.accepts(value) else { throw SwikoError(point: point, message: "Expected \(type), received \(value.typeName)") }
    }
    private func boolean(_ value: Value, at point: SourcePoint) throws -> Bool {
        guard case let .boolean(result) = value else { throw SwikoError(point: point, message: "Expected Bool, received \(value.typeName)") }
        return result
    }
    private func integer(_ value: Value, at point: SourcePoint) throws -> Int64 {
        guard case let .integer(result) = value else { throw SwikoError(point: point, message: "Expected Int, received \(value.typeName)") }
        return result
    }
    private func evaluate(_ expression: Expression, in environment: Environment) throws -> Value {
        try tick(expression.point)
        evaluationDepth += 1
        defer { evaluationDepth -= 1 }
        guard evaluationDepth <= 512 else { throw SwikoError(point: expression.point, message: "Expression depth limit exceeded") }
        switch expression {
        case let .literal(value, _): return value
        case let .name(name, point):
            guard let entry = environment.lookup(name) else { throw SwikoError(point: point, message: "Unknown name '\(name)'") }
            guard case let .binding(binding) = entry else { throw SwikoError(point: point, message: "Functions are only usable in calls") }
            return binding.value
        case let .unary(operation, operand, point):
            let value = try evaluate(operand, in: environment)
            if operation == "!" { return .boolean(try !boolean(value, at: point)) }
            let result = try integer(value, at: point).multipliedReportingOverflow(by: -1)
            guard !result.overflow else { throw SwikoError(point: point, message: "Integer overflow") }
            return .integer(result.partialValue)
        case let .binary(left, operation, right, point):
            let a = try evaluate(left, in: environment)
            if operation == "??" { return a == .null ? (try evaluate(right, in: environment)) : a }
            if operation == "&&" {
                return .boolean(try boolean(a, at: point) && boolean(evaluate(right, in: environment), at: point))
            }
            if operation == "||" {
                return .boolean(try boolean(a, at: point) || boolean(evaluate(right, in: environment), at: point))
            }
            let b = try evaluate(right, in: environment)
            if operation == "==" || operation == "!=" {
                if a != .null && b != .null && a.typeName != b.typeName {
                    throw SwikoError(point: point, message: "Equality requires matching types")
                }
                return .boolean(operation == "==" ? a == b : a != b)
            }
            if operation == "+", case let .string(x) = a, case let .string(y) = b {
                guard x.utf8.count <= 1_048_576 - y.utf8.count else { throw SwikoError(point: point, message: "String value limit exceeded") }
                return .string(x + y)
            }
            let x = try integer(a, at: point)
            let y = try integer(b, at: point)
            switch operation {
            case "<": return .boolean(x < y)
            case "<=": return .boolean(x <= y)
            case ">": return .boolean(x > y)
            case ">=": return .boolean(x >= y)
            default: break
            }
            let result: (partialValue: Int64, overflow: Bool)
            switch operation {
            case "+": result = x.addingReportingOverflow(y)
            case "-": result = x.subtractingReportingOverflow(y)
            case "*": result = x.multipliedReportingOverflow(by: y)
            case "/", "%":
                guard y != 0 else { throw SwikoError(point: point, message: "Division or remainder by zero") }
                guard !(x == Int64.min && y == -1) else { throw SwikoError(point: point, message: "Integer overflow") }
                return .integer(operation == "/" ? x / y : x % y)
            default: throw SwikoError(point: point, message: "Unknown operator '\(operation)'")
            }
            guard !result.overflow else { throw SwikoError(point: point, message: "Integer overflow") }
            return .integer(result.partialValue)
        case let .call(callee, arguments, point):
            guard case let .name(name, _) = callee else { throw SwikoError(point: point, message: "Only named functions can be called") }
            if name == "print" {
                guard arguments.count == 1 else { throw SwikoError(point: point, message: "print expects one argument") }
                let text = try evaluate(arguments[0], in: environment).display
                guard text.utf8.count + 1 <= 4_194_304 - output.utf8.count else { throw SwikoError(point: point, message: "Output limit exceeded") }
                output += text
                output += "\n"
                return .unit
            }
            guard let entry = environment.lookup(name) else { throw SwikoError(point: point, message: "Unknown function '\(name)'") }
            guard case let .function(function) = entry else { throw SwikoError(point: point, message: "'\(name)' is not a function") }
            guard arguments.count == function.parameters.count else { throw SwikoError(point: point, message: "Incorrect argument count for '\(name)'") }
            guard calls < 128 else { throw SwikoError(point: point, message: "Function call depth limit exceeded") }
            let local = Environment(parent: function.environment)
            for (parameter, argument) in zip(function.parameters, arguments) {
                let value = try evaluate(argument, in: environment)
                try check(value, type: parameter.type, at: argument.point)
                try local.declare(parameter.name, .binding(Binding(value: value, type: parameter.type, mutable: false)), at: parameter.point)
            }
            calls += 1
            defer { calls -= 1 }
            var result = Value.unit
            var resultPoint = point
            do { try execute(function.body, in: local) }
            catch let returned as Returned { result = returned.value; resultPoint = returned.point }
            try check(result, type: function.result, at: resultPoint)
            return result
        }
    }
}

func runSwiko(source: String) throws -> String {
    let (statements, checked) = try prepareSwiko(source: source)
    return try Interpreter(checked: checked).run(statements)
}

private func prepareSwiko(source: String) throws -> ([Statement], CheckResult) {
    let point = SourcePoint(line: 1, column: 1)
    guard source.utf8.count <= 1_048_576 else { throw SwikoError(point: point, message: "Source byte limit exceeded") }
    var normalized = source
    if normalized.hasPrefix("\u{FEFF}") { normalized.removeFirst() }
    normalized = normalized.replacingOccurrences(of: "\r\n", with: "\n").replacingOccurrences(of: "\r", with: "\n")
    let statements = try Parser(Lexer(normalized).scan()).parse()
    return (statements, try Checker().check(statements))
}

private func stderr(_ text: String) { FileHandle.standardError.write(Data(text.utf8)) }

private struct UsageError: Error { let message: String }
private func decodeUtf8Arguments(_ arguments: [String]) throws -> [String] {
    guard arguments.first == "--utf8-args" else { return arguments }
    guard arguments.count == 2, arguments[1].utf8.count <= 1_500_000,
          let data = Data(base64Encoded: arguments[1]) else {
        throw UsageError(message: "--utf8-args requires one bounded Base64 payload")
    }
    guard data.count <= 1_100_000 else { throw UsageError(message: "Argument payload size limit exceeded") }
    if data.isEmpty { return [] }
    guard let text = String(data: data, encoding: .utf8), text.last == "\0" else {
        throw UsageError(message: "Argument payload must contain NUL-terminated UTF-8 arguments")
    }
    return String(text.dropLast()).split(separator: "\0", omittingEmptySubsequences: false).map(String.init)
}
private struct CLIOptions {
    var mode = "run"
    var library = false
    var eval = false
    var source: String?
    var filename: String { eval ? "<eval>" : source == "-" ? "<stdin>" : source ?? "<cli>" }
    init(_ arguments: [String]) throws {
        var index = 0
        var optionParsing = true
        var modeSeen = false
        while index < arguments.count {
            let argument = arguments[index]
            if optionParsing && argument == "--" {
                if source != nil || index != arguments.count - 2 { throw UsageError(message: "-- must precede one filename") }
                optionParsing = false; index += 1; continue
            }
            if optionParsing && ["--check", "--emit-swift", "--emit-kotlin"].contains(argument) {
                if modeSeen { throw UsageError(message: "Duplicate or conflicting mode") }
                modeSeen = true; mode = String(argument.dropFirst(2))
            } else if optionParsing && argument == "--library" {
                if library { throw UsageError(message: "Duplicate --library") }
                library = true
            } else if optionParsing && argument == "--eval" {
                if source != nil { throw UsageError(message: "More than one source input") }
                index += 1
                if index >= arguments.count { throw UsageError(message: "--eval requires a source argument") }
                eval = true; source = arguments[index]
            } else if optionParsing && argument.hasPrefix("-") && argument != "-" {
                throw UsageError(message: "Unknown option '\(argument)'")
            } else {
                if source != nil { throw UsageError(message: "More than one source input") }
                source = argument
            }
            index += 1
        }
        if source == nil { throw UsageError(message: "A source file, --eval source, or - is required") }
        if library && !mode.hasPrefix("emit-") { throw UsageError(message: "--library requires an emit mode") }
    }
}

private func readSource(_ options: CLIOptions) throws -> String {
    if options.eval { return options.source! }
    let handle: FileHandle
    if options.source == "-" { handle = .standardInput }
    else { handle = try FileHandle(forReadingFrom: URL(fileURLWithPath: options.source!)) }
    defer { if options.source != "-" { try? handle.close() } }
    var bytes = Data()
    while let part = try handle.read(upToCount: min(65_536, 1_048_577 - bytes.count)), !part.isEmpty {
        if part.count > 1_048_576 - bytes.count { throw SwikoError(point: SourcePoint(line: 1, column: 1), message: "Source byte limit exceeded") }
        bytes.append(part)
    }
    guard let source = String(data: bytes, encoding: .utf8) else { throw SwikoError(point: SourcePoint(line: 1, column: 1), message: "Source must be valid UTF-8") }
    return source
}

private let usage = "Usage: swiko [--check | --emit-swift | --emit-kotlin] [--library] FILE\n       swiko [--check | --emit-swift | --emit-kotlin] [--library] --eval SOURCE\n       swiko [--check | --emit-swift | --emit-kotlin] [--library] -\n       swiko --utf8-args BASE64\n       swiko --help\n       swiko --version\n"
private let arguments: [String]
do { arguments = try decodeUtf8Arguments(Array(CommandLine.arguments.dropFirst())) }
catch let error as UsageError { stderr("<cli>:1:1: error: \(error.message)\n"); exit(2) }
catch { stderr("<cli>:1:1: error: Invalid argument payload\n"); exit(2) }
if arguments == ["--version"] { Swift.print("Swiko 1.0.1") }
else if arguments == ["--help"] { Swift.print(usage, terminator: "") }
else {
    var filename = "<cli>"
    do {
        let options = try CLIOptions(arguments)
        filename = options.filename
        let source = try readSource(options)
        let (statements, checked) = try prepareSwiko(source: source)
        switch options.mode {
        case "check": break
        case "emit-swift", "emit-kotlin":
            let generated = try SourceGenerator(statements: statements, checked: checked, sourceName: filename).generate(target: options.mode == "emit-swift" ? "swift" : "kotlin", library: options.library)
            Swift.print(generated, terminator: "")
        default: Swift.print(try Interpreter(checked: checked).run(statements), terminator: "")
        }
    } catch let error as UsageError {
        stderr("<cli>:1:1: error: \(error.message)\n\(usage)"); exit(2)
    } catch let error as SwikoError {
        stderr("\(filename):\(error.point.line):\(error.point.column): error: \(error.message)\n"); exit(1)
    } catch {
        stderr("\(filename):1:1: error: \(error.localizedDescription)\n"); exit(1)
    }
}
// Typed AST-to-source compilation for the two supported host languages.
final class SourceGenerator {
    private let statements: [Statement]
    private let checked: CheckResult
    private let sourceName: String
    private var target = "swift"
    private var bindings: [String: ValueType] = [:]
    private var signatures: [String: [ValueType]] = [:]

    init(statements: [Statement], checked: CheckResult, sourceName: String = "<generated>") {
        self.statements = statements
        self.checked = checked
        self.sourceName = sourceName
    }

    func generate(target: String, library: Bool) throws -> String {
        guard target == "swift" || target == "kotlin" else {
            throw SwikoError(point: SourcePoint(line: 1, column: 1), message: "Unsupported source target")
        }
        self.target = target
        try collect(statements)
        var source = target == "swift" ? swiftRuntime : kotlinRuntime
        if target == "swift" {
            source += "\npublic func swikoRun() throws -> String {\n    let swikoRuntime = SwikoRuntime(source: \(literal(sourceName)))\n"
            source += try emit(statements, indent: "    ", returnType: nil)
            source += "    return swikoRuntime.output\n}\n\npublic func swikoMain() throws {\n    Swift.print(try swikoRun(), terminator: \"\")\n}\n"
            if !library {
                source += "\ndo {\n    try swikoMain()\n} catch {\n    FileHandle.standardError.write(Data((String(describing: error) + \"\\n\").utf8))\n    exit(1)\n}\n"
            }
        } else {
            source += "\npublic fun swikoRun(): String {\n    val swikoRuntime = SwikoRuntime(\(literal(sourceName)))\n"
            source += try emit(statements, indent: "    ", returnType: nil)
            source += "    return swikoRuntime.output.toString()\n}\n\npublic fun swikoMain(): Unit {\n    swikoWriteUtf8(System.out, swikoRun())\n}\n"
            if !library {
                source += "\nfun main() {\n    try { swikoMain() } catch (error: SwikoGeneratedError) {\n        swikoWriteUtf8(System.err, (error.message ?: \"Runtime error\") + \"\\n\")\n        kotlin.system.exitProcess(1)\n    }\n}\n"
            }
        }
        return source
    }

    private func failure(_ point: SourcePoint, _ message: String) -> SwikoError {
        SwikoError(point: point, message: message)
    }
    private func name(_ point: SourcePoint) throws -> String {
        guard let value = checked.resolvedNames[point.key] else { throw failure(point, "Missing resolved symbol during source generation") }
        return value
    }
    private func type(_ expression: Expression) throws -> ValueType {
        guard let value = checked.expressionTypes[expression.point.key] else { throw failure(expression.point, "Missing checked expression type") }
        return value
    }
    private func collect(_ statements: [Statement]) throws {
        for statement in statements {
            switch statement {
            case let .binding(_, _, _, _, point):
                bindings[try name(point)] = checked.declarationTypes[point.key]
            case let .function(_, parameters, _, body, point):
                signatures[try name(point)] = parameters.map { $0.type }
                for parameter in parameters { bindings[try name(parameter.point)] = parameter.type }
                try collect(body)
            case let .condition(_, yes, no, _): try collect(yes); try collect(no)
            case let .loop(_, body, _), let .block(body, _): try collect(body)
            default: break
            }
        }
    }
    private func mapped(_ type: ValueType) -> String {
        let base: String
        if target == "swift" {
            switch type.name {
            case "Int": base = "Int64"
            case "Bool": base = "Bool"
            case "String": base = "String"
            case "Unit": base = "SwikoUnit"
            default: base = "SwikoUnit"
            }
        } else {
            switch type.name {
            case "Int": base = "Long"
            case "Bool": base = "Boolean"
            case "String": base = "String"
            case "Unit": base = "Unit"
            default: base = "Unit"
            }
        }
        return base + (type.nullable || type.name == "null" ? "?" : "")
    }
    private var unit: String { target == "swift" ? "SwikoUnit()" : "Unit" }
    private func location(_ point: SourcePoint) -> String { "\(point.line), \(point.column)" }
    private func call(_ method: String, _ arguments: [String]) -> String {
        let value = "swikoRuntime.\(method)(\(arguments.joined(separator: ", ")))"
        return target == "swift" ? "(try \(value))" : value
    }
    private func tick(_ point: SourcePoint) -> String { call("tick", [location(point)]) }
    private func literal(_ value: String) -> String {
        var pieces: [String] = []
        var part = ""
        var size = 0
        for scalar in value.unicodeScalars {
            if size >= 4000 { pieces.append(part); part = ""; size = 0 }
            switch scalar {
            case "\"": part += "\\\""
            case "\\": part += "\\\\"
            case "\n": part += "\\n"
            case "\r": part += "\\r"
            case "\t": part += "\\t"
            case "$" where target == "kotlin": part += "\\$"
            default:
                if scalar.value >= 32 && scalar.value <= 126 { part.unicodeScalars.append(scalar) }
                else if target == "swift" { part += "\\u{\(String(scalar.value, radix: 16))}" }
                else if scalar.value <= 0xffff { part += String(format: "\\u%04x", scalar.value) }
                else {
                    let value = scalar.value - 0x10000
                    part += String(format: "\\u%04x\\u%04x", 0xd800 + value / 1024, 0xdc00 + value % 1024)
                }
            }
            size += scalar.utf8.count
        }
        pieces.append(part)
        if target == "kotlin" && pieces.count > 1 {
            return "buildString { " + pieces.map { "append(\"\($0)\")" }.joined(separator: "; ") + " }"
        }
        if target == "swift" { return "\"" + pieces.joined() + "\"" }
        return "\"\(pieces[0])\""
    }
    private func null(_ expected: ValueType?) -> String {
        if target == "kotlin" { return "null" }
        let type = expected ?? ValueType(name: "Unit", nullable: true)
        return "Optional<\(mapped(ValueType(name: type.name, nullable: false)))>.none"
    }
    private func expression(_ expression: Expression, expected: ValueType? = nil) throws -> String {
        switch expression {
        case let .literal(value, _):
            switch value {
            case let .integer(number):
                if number == Int64.min { return target == "swift" ? "Int64.min" : "Long.MIN_VALUE" }
                return target == "swift" ? "Int64(\(number))" : "\(number)L"
            case let .boolean(value): return value ? "true" : "false"
            case let .string(value): return literal(value)
            case .null: return null(expected)
            case .unit: return unit
            }
        case let .name(_, point): return try name(point)
        case let .unary(operation, operand, point):
            let value = try self.expression(operand)
            if operation == "!" { return "(!\(value))" }
            return call("negate", [value, location(point)])
        case let .binary(left, operation, right, point):
            let leftType = try type(left)
            let rightType = try type(right)
            if operation == "??" {
                if leftType.name == "null" { return try self.expression(right, expected: expected) }
                if !leftType.nullable { return try self.expression(left, expected: expected) }
                let a = try self.expression(left, expected: leftType)
                let b = try self.expression(right, expected: ValueType(name: leftType.name, nullable: rightType.nullable || rightType.name == "null"))
                let body = "(\(a) \(target == "swift" ? "??" : "?:") \(b))"
                return target == "swift" ? "(try \(body))" : body
            }
            if operation == "==" || operation == "!=" {
                var equal: String
                if leftType.name == "null" && rightType.name == "null" { equal = "true" }
                else if leftType.name == "null" {
                    equal = "swikoRuntime.isNull(\(try self.expression(right)))"
                } else if rightType.name == "null" {
                    equal = "swikoRuntime.isNull(\(try self.expression(left)))"
                } else {
                    let method = leftType.name == "String" ? "equalString" : "equal"
                    equal = "swikoRuntime.\(method)(\(try self.expression(left)), \(try self.expression(right)))"
                }
                return operation == "!=" ? "(!\(equal))" : equal
            }
            let a = try self.expression(left)
            let b = try self.expression(right)
            if operation == "&&" || operation == "||" {
                let body = "(\(a) \(operation) \(b))"
                return target == "swift" ? "(try \(body))" : body
            }
            if ["<", "<=", ">", ">="].contains(operation) { return "(\(a) \(operation) \(b))" }
            let method: String
            switch operation {
            case "+": method = leftType.name == "String" ? "concat" : "add"
            case "-": method = "subtract"
            case "*": method = "multiply"
            case "/": method = "divide"
            case "%": method = "remainder"
            default: throw failure(point, "Unsupported operator during source generation")
            }
            return call(method, [a, b, location(point)])
        case let .call(callee, arguments, point):
            guard case let .name(rawName, _) = callee else { throw failure(point, "Only named functions can be compiled") }
            if rawName == "print" {
                let argument = arguments[0]
                let valueType = try type(argument)
                let show: String
                switch valueType.name {
                case "Int": show = "showInt"
                case "Bool": show = "showBool"
                case "String": show = "showString"
                case "Unit": show = "showUnit"
                default: show = "showNull"
                }
                let rendered = "swikoRuntime.\(show)(\(try self.expression(argument)))"
                return call("printValue", [rendered, location(point)])
            }
            let functionName = try name(callee.point)
            guard let parameters = signatures[functionName], parameters.count == arguments.count else {
                throw failure(point, "Missing function signature during source generation")
            }
            let values = try zip(arguments, parameters).map { try self.expression($0.0, expected: $0.1) }
            let body = "\(functionName)(\(values.joined(separator: ", ")))"
            return target == "swift" ? "(try \(body))" : body
        }
    }
    private func emit(_ statements: [Statement], indent: String, returnType: ValueType?) throws -> String {
        var output = ""
        for statement in statements {
            let point = statement.point
            output += indent + tick(point) + "\n"
            switch statement {
            case let .binding(_, mutable, _, initializer, point):
                guard let annotation = checked.declarationTypes[point.key] else { throw failure(point, "Missing binding type during source generation") }
                let keyword = target == "swift" ? (mutable ? "var" : "let") : (mutable ? "var" : "val")
                output += "\(indent)\(keyword) \(try name(point)): \(mapped(annotation)) = \(try expression(initializer, expected: annotation))\n"
            case let .assignment(_, value, point):
                let symbol = try name(point)
                output += "\(indent)\(symbol) = \(try expression(value, expected: bindings[symbol]))\n"
            case let .expression(value):
                output += indent + (target == "swift" ? "_ = " : "") + (try expression(value)) + "\n"
            case let .function(_, parameters, result, body, point):
                let arguments = try parameters.map { parameter -> String in
                    let symbol = try name(parameter.point)
                    return (target == "swift" ? "_ " : "") + symbol + ": " + mapped(parameter.type)
                }.joined(separator: ", ")
                if target == "swift" {
                    output += "\(indent)func \(try name(point))(\(arguments)) throws -> \(mapped(result)) {\n"
                    output += "\(indent)    \(call("enter", [location(point)]))\n\(indent)    defer { swikoRuntime.leave() }\n"
                    output += try emit(body, indent: indent + "    ", returnType: result)
                    if result.name == "Unit" { output += "\(indent)    return SwikoUnit()\n" }
                    output += "\(indent)}\n"
                } else {
                    output += "\(indent)fun \(try name(point))(\(arguments)): \(mapped(result)) {\n"
                    output += "\(indent)    \(call("enter", [location(point)]))\n\(indent)    try {\n"
                    output += try emit(body, indent: indent + "        ", returnType: result)
                    if result.name == "Unit" { output += "\(indent)        return Unit\n" }
                    output += "\(indent)    } finally { swikoRuntime.leave() }\n\(indent)}\n"
                }
            case let .condition(condition, yes, no, _):
                output += "\(indent)if \(target == "kotlin" ? "(" : "")\(try expression(condition))\(target == "kotlin" ? ")" : "") {\n"
                output += try emit(yes, indent: indent + "    ", returnType: returnType)
                if no.isEmpty { output += "\(indent)}\n" }
                else {
                    output += "\(indent)} else {\n"
                    output += try emit(no, indent: indent + "    ", returnType: returnType)
                    output += "\(indent)}\n"
                }
            case let .loop(condition, body, point):
                output += "\(indent)while \(target == "kotlin" ? "(" : "")\(try expression(condition))\(target == "kotlin" ? ")" : "") {\n"
                output += indent + "    " + tick(point) + "\n"
                output += try emit(body, indent: indent + "    ", returnType: returnType)
                output += "\(indent)}\n"
            case let .returning(value, _):
                output += indent + "return " + (try value.map { try expression($0, expected: returnType) } ?? unit) + "\n"
            case let .block(body, _):
                output += indent + (target == "swift" ? "do {\n" : "run {\n")
                output += try emit(body, indent: indent + "    ", returnType: returnType)
                output += indent + "}\n"
            }
        }
        return output
    }

    private var swiftRuntime: String {
        #"""
// Generated by Swiko 1.0.1. This is typed Swift source, not an interpreter.
import Foundation
#if os(Windows)
import ucrt
#endif

private struct SwikoUnit: Equatable {}
private struct SwikoGeneratedError: Error, CustomStringConvertible {
    let description: String
}
private final class SwikoRuntime {
    let source: String
    var output = ""
    private var outputBytes = 0
    private var steps = 0
    private var calls = 0
    init(source: String) { self.source = source }
    private func fail(_ line: Int, _ column: Int, _ message: String) throws -> Never {
        throw SwikoGeneratedError(description: "\(source):\(line):\(column): error: \(message)")
    }
    func tick(_ line: Int, _ column: Int) throws {
        steps += 1
        if steps > 100_000 { try fail(line, column, "Execution step limit exceeded") }
    }
    func enter(_ line: Int, _ column: Int) throws {
        if calls >= 128 { try fail(line, column, "Function call depth limit exceeded") }
        calls += 1
        try tick(line, column)
    }
    func leave() { calls -= 1 }
    func add(_ a: Int64, _ b: Int64, _ line: Int, _ column: Int) throws -> Int64 {
        let result = a.addingReportingOverflow(b)
        if result.overflow { try fail(line, column, "Integer overflow") }
        return result.partialValue
    }
    func subtract(_ a: Int64, _ b: Int64, _ line: Int, _ column: Int) throws -> Int64 {
        let result = a.subtractingReportingOverflow(b)
        if result.overflow { try fail(line, column, "Integer overflow") }
        return result.partialValue
    }
    func multiply(_ a: Int64, _ b: Int64, _ line: Int, _ column: Int) throws -> Int64 {
        let result = a.multipliedReportingOverflow(by: b)
        if result.overflow { try fail(line, column, "Integer overflow") }
        return result.partialValue
    }
    func negate(_ a: Int64, _ line: Int, _ column: Int) throws -> Int64 {
        let result = a.multipliedReportingOverflow(by: -1)
        if result.overflow { try fail(line, column, "Integer overflow") }
        return result.partialValue
    }
    func divide(_ a: Int64, _ b: Int64, _ line: Int, _ column: Int) throws -> Int64 {
        if b == 0 { try fail(line, column, "Division or remainder by zero") }
        if a == Int64.min && b == -1 { try fail(line, column, "Integer overflow") }
        return a / b
    }
    func remainder(_ a: Int64, _ b: Int64, _ line: Int, _ column: Int) throws -> Int64 {
        if b == 0 { try fail(line, column, "Division or remainder by zero") }
        if a == Int64.min && b == -1 { try fail(line, column, "Integer overflow") }
        return a % b
    }
    func concat(_ a: String, _ b: String, _ line: Int, _ column: Int) throws -> String {
        if a.utf8.count > 1_048_576 - b.utf8.count { try fail(line, column, "String size limit exceeded") }
        return a + b
    }
    func equal<T: Equatable>(_ a: T?, _ b: T?) -> Bool { a == b }
    func equalString(_ a: String?, _ b: String?) -> Bool {
        switch (a, b) {
        case (nil, nil): return true
        case let (.some(x), .some(y)): return x.unicodeScalars.elementsEqual(y.unicodeScalars)
        default: return false
        }
    }
    func isNull<T>(_ value: T?) -> Bool { value == nil }
    func showInt(_ value: Int64?) -> String { value.map(String.init) ?? "null" }
    func showBool(_ value: Bool?) -> String { value.map { $0 ? "true" : "false" } ?? "null" }
    func showString(_ value: String?) -> String { value ?? "null" }
    func showUnit(_ value: SwikoUnit?) -> String { value == nil ? "null" : "Unit" }
    func showNull<T>(_ value: T?) -> String { "null" }
    func printValue(_ value: String, _ line: Int, _ column: Int) throws -> SwikoUnit {
        let bytes = value.utf8.count
        if bytes >= 4_194_304 - outputBytes { try fail(line, column, "Output size limit exceeded") }
        outputBytes += bytes + 1
        output.append(value)
        output.append("\n")
        return SwikoUnit()
    }
}
"""#
    }

    private var kotlinRuntime: String {
        #"""
// Generated by Swiko 1.0.1. This is typed Kotlin source, not an interpreter.
private fun swikoWriteUtf8(output: java.io.OutputStream, text: String) {
    output.write(text.toByteArray(Charsets.UTF_8))
    output.flush()
}
private class SwikoGeneratedError(message: String) : RuntimeException(message)
private class SwikoRuntime(private val source: String) {
    val output = StringBuilder()
    private var outputBytes = 0
    private var steps = 0
    private var calls = 0
    private fun fail(line: Int, column: Int, message: String): Nothing =
        throw SwikoGeneratedError("$source:$line:$column: error: $message")
    fun tick(line: Int, column: Int) {
        if (++steps > 100_000) fail(line, column, "Execution step limit exceeded")
    }
    fun enter(line: Int, column: Int) {
        if (calls >= 128) fail(line, column, "Function call depth limit exceeded")
        calls++
        tick(line, column)
    }
    fun leave() { calls-- }
    fun add(a: Long, b: Long, line: Int, column: Int): Long {
        val result = a + b
        if (((a xor result) and (b xor result)) < 0) fail(line, column, "Integer overflow")
        return result
    }
    fun subtract(a: Long, b: Long, line: Int, column: Int): Long {
        val result = a - b
        if (((a xor b) and (a xor result)) < 0) fail(line, column, "Integer overflow")
        return result
    }
    fun multiply(a: Long, b: Long, line: Int, column: Int): Long {
        val result = a * b
        if ((a == Long.MIN_VALUE && b == -1L) || (b == Long.MIN_VALUE && a == -1L) ||
            (b != 0L && result / b != a)) fail(line, column, "Integer overflow")
        return result
    }
    fun negate(a: Long, line: Int, column: Int): Long {
        if (a == Long.MIN_VALUE) fail(line, column, "Integer overflow")
        return -a
    }
    fun divide(a: Long, b: Long, line: Int, column: Int): Long {
        if (b == 0L) fail(line, column, "Division or remainder by zero")
        if (a == Long.MIN_VALUE && b == -1L) fail(line, column, "Integer overflow")
        return a / b
    }
    fun remainder(a: Long, b: Long, line: Int, column: Int): Long {
        if (b == 0L) fail(line, column, "Division or remainder by zero")
        if (a == Long.MIN_VALUE && b == -1L) fail(line, column, "Integer overflow")
        return a % b
    }
    fun concat(a: String, b: String, line: Int, column: Int): String {
        if (a.toByteArray(Charsets.UTF_8).size > 1_048_576 - b.toByteArray(Charsets.UTF_8).size)
            fail(line, column, "String size limit exceeded")
        return a + b
    }
    fun equal(a: Any?, b: Any?): Boolean = a == b
    fun equalString(a: String?, b: String?): Boolean = a == b
    fun isNull(value: Any?): Boolean = value == null
    fun showInt(value: Long?): String = value?.toString() ?: "null"
    fun showBool(value: Boolean?): String = value?.toString() ?: "null"
    fun showString(value: String?): String = value ?: "null"
    fun showUnit(value: Unit?): String = if (value == null) "null" else "Unit"
    fun showNull(value: Any?): String = "null"
    fun printValue(value: String, line: Int, column: Int): Unit {
        val bytes = value.toByteArray(Charsets.UTF_8).size
        if (bytes >= 4_194_304 - outputBytes) fail(line, column, "Output size limit exceeded")
        outputBytes += bytes + 1
        output.append(value).append('\n')
    }
}
"""#
    }
}

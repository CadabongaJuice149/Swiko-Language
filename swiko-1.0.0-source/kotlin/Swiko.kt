import kotlin.io.path.Path
import kotlin.system.exitProcess
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import java.util.IdentityHashMap

private const val SOURCE_LIMIT = 1_048_576
private const val STRING_LIMIT = 1_048_576
private const val OUTPUT_LIMIT = 4_194_304
private fun bytes(text: String) = text.toByteArray(Charsets.UTF_8).size
private val origin = Token("source", "", 1, 1)

private data class Token(val kind: String, val text: String, val line: Int, val column: Int)
private class SwikoError(val token: Token, message: String) : RuntimeException(message)
private fun fail(token: Token, message: String): Nothing = throw SwikoError(token, message)

private class Lexer(private val source: String) {
    private var index = 0
    private var line = 1
    private var column = 1
    private var parentheses = 0
    private val tokens = mutableListOf<Token>()
    private val keywords = setOf("val", "var", "fun", "if", "else", "while", "return", "true", "false", "null")
    private fun advance(): Char {
        val c = source[index++]
        if (c == '\n') { line++; column = 1 }
        else if (!c.isLowSurrogate() || index < 2 || !source[index - 2].isHighSurrogate()) column++
        return c
    }
    private fun letter(c: Char) = c in 'a'..'z' || c in 'A'..'Z' || c == '_'
    fun scan(): List<Token> {
        while (index < source.length) {
            val start = index
            val row = line
            val col = column
            val c = advance()
            fun add(kind: String, text: String = source.substring(start, index)) {
                if (tokens.size >= 100_000) fail(Token(kind, text, row, col), "Token limit exceeded")
                tokens += Token(kind, text, row, col)
            }
            when {
                c == ' ' || c == '\t' || c == '\r' -> Unit
                c == '\n' -> if (parentheses == 0) add("newline")
                c == '/' && source.getOrNull(index) == '/' -> {
                    while (index < source.length && source[index] != '\n') advance()
                }
                letter(c) -> {
                    while (index < source.length && (letter(source[index]) || source[index] in '0'..'9')) advance()
                    val word = source.substring(start, index)
                    add(if (word in keywords) word else "identifier")
                }
                c in '0'..'9' -> {
                    while (index < source.length && source[index] in '0'..'9') advance()
                    add("integer")
                }
                c == '"' -> {
                    val value = StringBuilder()
                    var ended = false
                    while (index < source.length) {
                        val part = advance()
                        if (part == '"') { ended = true; break }
                        if (part == '\n' || part == '\r') fail(Token("string", "", row, col), "Newline in string literal")
                        if (part == '\\') {
                            if (index >= source.length) fail(Token("string", "", row, col), "Unterminated string")
                            val escaped = advance()
                            value.append(when (escaped) {
                                'n' -> '\n'; 'r' -> '\r'; 't' -> '\t'; '"' -> '"'; '\\' -> '\\'
                                else -> fail(Token("string", "", row, col), "Unsupported string escape")
                            })
                        } else value.append(part)
                    }
                    if (!ended) fail(Token("string", "", row, col), "Unterminated string")
                    val text = value.toString()
                    if (bytes(text) > STRING_LIMIT) fail(Token("string", "", row, col), "String size limit exceeded")
                    add("string", text)
                }
                else -> {
                    val pair = if (index < source.length) "$c${source[index]}" else ""
                    if (pair in setOf("==", "!=", "<=", ">=", "&&", "||", "??")) {
                        advance(); add(pair)
                    } else if (c in "()+-*/%{}:;,=<>!?") {
                        if (c == '(') parentheses++
                        if (c == ')' && parentheses > 0) parentheses--
                        add(c.toString())
                    } else fail(Token("invalid", c.toString(), row, col), "Unexpected character '$c'")
                }
            }
        }
        tokens += Token("eof", "", line, column)
        return tokens
    }
}

private data class Type(val name: String, val nullable: Boolean = false) {
    override fun toString() = name + if (nullable) "?" else ""
}
private sealed interface Value {
    data class Integer(val value: Long) : Value
    data class Text(val value: String) : Value
    data class BooleanValue(val value: Boolean) : Value
    data object Null : Value
    data object UnitValue : Value
}
private fun valueType(value: Value): String = when (value) {
    is Value.Integer -> "Int"
    is Value.Text -> "String"
    is Value.BooleanValue -> "Bool"
    Value.Null -> "null"
    Value.UnitValue -> "Unit"
}
private fun render(value: Value): String = when (value) {
    is Value.Integer -> value.value.toString()
    is Value.Text -> value.value
    is Value.BooleanValue -> value.value.toString()
    Value.Null -> "null"
    Value.UnitValue -> "Unit"
}
private fun accepts(type: Type, value: Value) =
    if (value == Value.Null) type.nullable else type.name == valueType(value)

private sealed class Expr(open val token: Token) {
    data class Literal(val value: Value, override val token: Token) : Expr(token)
    data class Variable(val name: String, override val token: Token) : Expr(token)
    data class Unary(val operator: Token, val operand: Expr) : Expr(operator)
    data class Binary(val left: Expr, val operator: Token, val right: Expr) : Expr(operator)
    data class Call(val name: Token, val arguments: List<Expr>) : Expr(name)
}
private data class Parameter(val name: Token, val type: Type)
private sealed class Stmt(open val token: Token) {
    data class Binding(val name: Token, val mutable: Boolean, val type: Type?, val initializer: Expr) : Stmt(name)
    data class Assign(val name: Token, val value: Expr) : Stmt(name)
    data class Expression(val expression: Expr) : Stmt(expression.token)
    data class Function(val name: Token, val parameters: List<Parameter>, val result: Type, val body: List<Stmt>) : Stmt(name)
    data class If(val condition: Expr, val yes: List<Stmt>, val no: List<Stmt>, override val token: Token) : Stmt(token)
    data class While(val condition: Expr, val body: List<Stmt>, override val token: Token) : Stmt(token)
    data class Return(val expression: Expr?, override val token: Token) : Stmt(token)
    data class Block(val body: List<Stmt>, override val token: Token) : Stmt(token)
}

private class Parser(private val tokens: List<Token>) {
    private var index = 0
    private var nesting = 0
    private val current get() = tokens[index]
    private fun at(kind: String) = current.kind == kind
    private fun take() = tokens[index++]
    private fun match(kind: String): Boolean = if (at(kind)) { take(); true } else false
    private fun require(kind: String, description: String = "'$kind'"): Token =
        if (at(kind)) take() else fail(current, "Expected $description")
    private fun separators() { while (at("newline") || at(";")) take() }
    private fun lines() { while (at("newline")) take() }
    private fun boundary() = at("newline") || at(";") || at("}") || at("eof")
    private fun end() {
        if (!boundary()) fail(current, "Expected a newline or semicolon after statement")
        separators()
    }
    private fun name(): Token {
        val token = require("identifier", "an identifier")
        if (token.text == "print") fail(token, "'print' is reserved")
        return token
    }
    private inline fun <T> nested(body: () -> T): T {
        nesting++
        if (nesting > 128) fail(current, "Source nesting limit exceeded")
        try { return body() } finally { nesting-- }
    }
    fun parse(): List<Stmt> {
        val statements = mutableListOf<Stmt>()
        separators()
        while (!at("eof")) { statements += statement(); separators() }
        return statements
    }
    private fun block(): List<Stmt> = nested {
        lines()
        require("{")
        val statements = mutableListOf<Stmt>()
        separators()
        while (!at("}") && !at("eof")) { statements += statement(); separators() }
        require("}")
        statements
    }
    private fun type(): Type {
        val token = require("identifier", "a type")
        if (token.text !in setOf("Int", "Bool", "String", "Unit")) fail(token, "Unknown type '${token.text}'")
        return Type(token.text, match("?"))
    }
    private fun statement(): Stmt {
        if (at("val") || at("var")) {
            val mutable = take().kind == "var"
            val token = name()
            val annotation = if (match(":")) type() else null
            require("=")
            val initializer = expression()
            end()
            return Stmt.Binding(token, mutable, annotation, initializer)
        }
        if (match("fun")) {
            val token = name()
            require("(")
            val parameters = mutableListOf<Parameter>()
            if (!at(")")) do {
                val parameter = name()
                if (parameters.any { it.name.text == parameter.text }) fail(parameter, "Duplicate parameter")
                require(":")
                parameters += Parameter(parameter, type())
            } while (match(","))
            require(")")
            require(":")
            val result = type()
            return Stmt.Function(token, parameters, result, block())
        }
        if (at("if")) {
            val token = take()
            val condition = expression()
            val yes = block()
            var lookahead = index
            while (tokens[lookahead].kind == "newline") lookahead++
            val no = if (tokens[lookahead].kind == "else") {
                index = lookahead + 1
                if (at("if")) listOf(nested { statement() }) else block()
            } else emptyList()
            return Stmt.If(condition, yes, no, token)
        }
        if (at("while")) {
            val token = take()
            val condition = expression()
            return Stmt.While(condition, block(), token)
        }
        if (at("return")) {
            val token = take()
            val expression = if (boundary()) null else expression()
            end()
            return Stmt.Return(expression, token)
        }
        if (at("{")) {
            val token = current
            return Stmt.Block(block(), token)
        }
        if (at("identifier") && tokens.getOrNull(index + 1)?.kind == "=") {
            val token = name()
            require("=")
            val expression = expression()
            end()
            return Stmt.Assign(token, expression)
        }
        val expression = expression()
        end()
        return Stmt.Expression(expression)
    }
    private fun expression(): Expr = nested { coalesce() }
    private fun coalesce(): Expr {
        val left = logicalOr()
        return if (at("??")) {
            val operator = take()
            Expr.Binary(left, operator, nested { coalesce() })
        } else left
    }
    private fun chain(operand: () -> Expr, operators: Set<String>): Expr {
        var value = operand()
        var length = 0
        while (current.kind in operators) {
            val operator = take()
            value = Expr.Binary(value, operator, operand())
            if (++length > 128) fail(operator, "Expression chain limit exceeded")
        }
        return value
    }
    private fun logicalOr() = chain(::logicalAnd, setOf("||"))
    private fun logicalAnd() = chain(::equality, setOf("&&"))
    private fun equality() = chain(::comparison, setOf("==", "!="))
    private fun comparison() = chain(::addition, setOf("<", "<=", ">", ">="))
    private fun addition() = chain(::multiplication, setOf("+", "-"))
    private fun multiplication() = chain(::unary, setOf("*", "/", "%"))
    private fun unary(): Expr {
        if (at("!") || at("-")) {
            val operator = take()
            if (operator.kind == "-" && at("integer") && current.text == "9223372036854775808") {
                take()
                return Expr.Literal(Value.Integer(Long.MIN_VALUE), operator)
            }
            return Expr.Unary(operator, nested { unary() })
        }
        return call()
    }
    private fun call(): Expr {
        var value = primary()
        while (match("(")) {
            val arguments = mutableListOf<Expr>()
            if (!at(")")) do { arguments += expression() } while (match(","))
            require(")")
            if (value !is Expr.Variable) fail(value.token, "Only named functions can be called")
            value = Expr.Call(value.token, arguments)
        }
        return value
    }
    private fun primary(): Expr {
        val token = take()
        return when (token.kind) {
            "integer" -> Expr.Literal(Value.Integer(token.text.toLongOrNull() ?: fail(token, "Integer literal outside Int range")), token)
            "string" -> Expr.Literal(Value.Text(token.text), token)
            "true", "false" -> Expr.Literal(Value.BooleanValue(token.kind == "true"), token)
            "null" -> Expr.Literal(Value.Null, token)
            "identifier" -> Expr.Variable(token.text, token)
            "(" -> {
                val value = expression()
                require(")")
                value
            }
            else -> fail(token, "Expected an expression")
        }
    }
}

private data class Symbol(val id: Int, val type: Type, val mutable: Boolean, val parameters: List<Type>? = null)
private class TypeScope(private val parent: TypeScope? = null) {
    private val entries = mutableMapOf<String, Symbol>()
    fun declare(token: Token, symbol: Symbol) {
        if (token.text == "print" || entries.containsKey(token.text)) fail(token, "Duplicate or reserved declaration '${token.text}'")
        entries[token.text] = symbol
    }
    fun lookup(token: Token): Symbol = entries[token.text] ?: parent?.lookup(token) ?: fail(token, "Unknown name '${token.text}'")
}
private fun compatible(target: Type, source: Type): Boolean =
    if (source.name == "null") target.nullable else target.name == source.name && (target.nullable || !source.nullable)

/** Resolves names and checks every branch before interpretation or generation. */
private class Checker {
    val types = IdentityHashMap<Expr, Type>()
    val symbols = IdentityHashMap<Token, Symbol>()
    val bindingTypes = IdentityHashMap<Stmt.Binding, Type>()
    private var nextId = 0
    fun check(statements: List<Stmt>) { sequence(statements, TypeScope(), null) }
    private fun requireType(expected: Type, actual: Type, token: Token) {
        if (!compatible(expected, actual)) fail(token, "Expected $expected, found $actual")
    }
    private fun declare(token: Token, type: Type, mutable: Boolean, scope: TypeScope, parameters: List<Type>? = null): Symbol {
        val symbol = Symbol(++nextId, type, mutable, parameters)
        scope.declare(token, symbol)
        symbols[token] = symbol
        return symbol
    }
    private fun resolve(token: Token, scope: TypeScope): Symbol = scope.lookup(token).also { symbols[token] = it }
    private fun sequence(statements: List<Stmt>, scope: TypeScope, result: Type?): Boolean {
        var returns = false
        for (statement in statements) {
            val current = statement(statement, scope, result)
            returns = returns || current
        }
        return returns
    }
    private fun statement(statement: Stmt, scope: TypeScope, result: Type?): Boolean {
        when (statement) {
            is Stmt.Binding -> {
                val initializer = expression(statement.initializer, scope)
                val type = statement.type ?: initializer.also {
                    if (it.name == "null") fail(statement.name, "Null initialization requires a nullable type annotation")
                }
                requireType(type, initializer, statement.name)
                declare(statement.name, type, statement.mutable, scope)
                bindingTypes[statement] = type
            }
            is Stmt.Assign -> {
                val symbol = resolve(statement.name, scope)
                if (symbol.parameters != null || !symbol.mutable) fail(statement.name, "Cannot assign to immutable name '${statement.name.text}'")
                requireType(symbol.type, expression(statement.value, scope), statement.name)
            }
            is Stmt.Expression -> expression(statement.expression, scope)
            is Stmt.Function -> {
                declare(statement.name, statement.result, false, scope, statement.parameters.map { it.type })
                val local = TypeScope(scope)
                for (parameter in statement.parameters) declare(parameter.name, parameter.type, false, local)
                val returned = sequence(statement.body, local, statement.result)
                if (statement.result.name != "Unit" && !returned) fail(statement.name, "Function must return ${statement.result} on every path")
            }
            is Stmt.If -> {
                requireType(Type("Bool"), expression(statement.condition, scope), statement.condition.token)
                val yes = sequence(statement.yes, TypeScope(scope), result)
                val no = sequence(statement.no, TypeScope(scope), result)
                return yes && no
            }
            is Stmt.While -> {
                requireType(Type("Bool"), expression(statement.condition, scope), statement.condition.token)
                sequence(statement.body, TypeScope(scope), result)
            }
            is Stmt.Return -> {
                if (result == null) fail(statement.token, "Return outside a function")
                requireType(result, statement.expression?.let { expression(it, scope) } ?: Type("Unit"), statement.token)
                return true
            }
            is Stmt.Block -> return sequence(statement.body, TypeScope(scope), result)
        }
        return false
    }
    private fun expression(expression: Expr, scope: TypeScope): Type {
        val type = when (expression) {
            is Expr.Literal -> Type(valueType(expression.value), expression.value == Value.Null)
            is Expr.Variable -> {
                val symbol = resolve(expression.token, scope)
                if (symbol.parameters != null) fail(expression.token, "Functions must be called by name")
                symbol.type
            }
            is Expr.Unary -> {
                val expected = Type(if (expression.operator.kind == "!") "Bool" else "Int")
                requireType(expected, expression(expression.operand, scope), expression.token)
                expected
            }
            is Expr.Binary -> {
                val left = expression(expression.left, scope)
                val right = expression(expression.right, scope)
                when (expression.operator.kind) {
                    "??" -> {
                        if (left.name == "null") right
                        else {
                            if (right.name != "null" && left.name != right.name) fail(expression.token, "Coalescing requires compatible types")
                            Type(left.name, left.nullable && (right.name == "null" || right.nullable))
                        }
                    }
                    "==", "!=" -> {
                        if (left.name != "null" && right.name != "null" && left.name != right.name) fail(expression.token, "Equality requires matching types")
                        Type("Bool")
                    }
                    "&&", "||" -> {
                        requireType(Type("Bool"), left, expression.token)
                        requireType(Type("Bool"), right, expression.token)
                        Type("Bool")
                    }
                    "+" -> {
                        if (left == Type("String") && right == Type("String")) Type("String")
                        else {
                            requireType(Type("Int"), left, expression.token)
                            requireType(Type("Int"), right, expression.token)
                            Type("Int")
                        }
                    }
                    else -> {
                        requireType(Type("Int"), left, expression.token)
                        requireType(Type("Int"), right, expression.token)
                        Type(if (expression.operator.kind in setOf("<", "<=", ">", ">=")) "Bool" else "Int")
                    }
                }
            }
            is Expr.Call -> {
                if (expression.name.text == "print") {
                    if (expression.arguments.size != 1) fail(expression.name, "print expects one argument")
                    expression(expression.arguments.single(), scope)
                    Type("Unit")
                } else {
                    val symbol = resolve(expression.name, scope)
                    val parameters = symbol.parameters ?: fail(expression.name, "Name is not a function")
                    if (parameters.size != expression.arguments.size) fail(expression.name, "Wrong number of arguments")
                    parameters.zip(expression.arguments).forEach { (expected, argument) -> requireType(expected, expression(argument, scope), argument.token) }
                    symbol.type
                }
            }
        }
        types[expression] = type
        return type
    }
}

/** Emits typed target programs, preserving lexical scopes and checked arithmetic. */
private class Generator(private val checked: Checker, private val swift: Boolean, private val filename: String, private val library: Boolean) {
    private val code = StringBuilder()
    private var depth = 0
    private fun line(text: String = "") { code.append("    ".repeat(depth)).append(text).append('\n') }
    private fun symbol(token: Token) = "swk${checked.symbols[token]?.id ?: error("Unresolved symbol") }"
    private fun type(type: Type): String {
        val base = when (type.name) {
            "Int" -> if (swift) "Int64" else "Long"
            "Bool" -> if (swift) "Bool" else "Boolean"
            "String" -> "String"
            "Unit" -> if (swift) "SwikoUnit" else "Unit"
            else -> if (swift) "SwikoUnit" else "Nothing"
        }
        return base + if (type.nullable) "?" else ""
    }
    private fun quote(text: String): String {
        if (!swift && bytes(text) > 4_000) {
            val chunks = mutableListOf<String>()
            val part = StringBuilder()
            var count = 0
            val points = text.codePoints().iterator()
            while (points.hasNext()) {
                val point = points.nextInt()
                val scalar = String(Character.toChars(point))
                val size = bytes(scalar)
                if (count + size > 4_000) { chunks += part.toString(); part.setLength(0); count = 0 }
                part.append(scalar); count += size
            }
            if (part.isNotEmpty()) chunks += part.toString()
            return "buildString { " + chunks.joinToString("; ") { "append(${quote(it)})" } + " }"
        }
        return buildString {
        append('"')
        for (c in text) when (c) {
            '\\' -> append("\\\\")
            '"' -> append("\\\"")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            '$' -> append(if (swift) "$" else "\\$")
            else -> if (c.code < 32 || c.code == 127) {
                if (swift) append("\\u{${c.code.toString(16)}}")
                else append("\\u${c.code.toString(16).padStart(4, '0')}")
            } else append(c)
        }
        append('"')
        }
    }
    private fun location(token: Token) = "${token.line}, ${token.column}"
    private fun runtime(name: String, arguments: String): String =
        if (swift) "(try skrt.$name($arguments))" else "skrt.$name($arguments)"
    private fun expression(expression: Expr): String = when (expression) {
        is Expr.Literal -> when (val value = expression.value) {
            is Value.Integer -> if (value.value == Long.MIN_VALUE) { if (swift) "Int64.min" else "Long.MIN_VALUE" }
                else if (swift) "Int64(${value.value})" else "${value.value}L"
            is Value.Text -> quote(value.value)
            is Value.BooleanValue -> value.value.toString()
            Value.Null -> if (swift) "nil" else "null"
            Value.UnitValue -> if (swift) "SwikoUnit()" else "Unit"
        }
        is Expr.Variable -> symbol(expression.token)
        is Expr.Unary -> if (expression.operator.kind == "!") "(!${expression(expression.operand)})"
            else runtime("negate", "${expression(expression.operand)}, ${location(expression.token)}")
        is Expr.Call -> {
            if (expression.name.text == "print") {
                val value = expression.arguments.single()
                val rendered = run {
                    val kind = checked.types[value] ?: error("Unchecked expression")
                    if (kind.name == "null") quote("null") else "skrt.show${kind.name}(${expression(value)})"
                }
                runtime("printValue", "$rendered, ${location(expression.token)}")
            } else {
                val call = "${symbol(expression.name)}(${expression.arguments.joinToString(", ") { expression(it) }})"
                if (swift) "(try $call)" else call
            }
        }
        is Expr.Binary -> {
            val a = expression(expression.left)
            val b = expression(expression.right)
            val left = checked.types[expression.left] ?: error("Unchecked expression")
            val right = checked.types[expression.right] ?: error("Unchecked expression")
            val operation = expression.operator.kind
            when (operation) {
                "??" -> if (left.name == "null") b else "($a ${if (swift) "??" else "?:"} $b)"
                "&&", "||", "<", "<=", ">", ">=" -> "($a $operation $b)"
                "==", "!=" -> {
                    val equality = when {
                        left.name == "null" && right.name == "null" -> "true"
                        swift && left.name == "null" -> "skrt.isNull($b)"
                        swift && right.name == "null" -> "skrt.isNull($a)"
                        swift && left.name == "String" && right.name == "String" -> "skrt.equalString($a, $b)"
                        else -> "($a == $b)"
                    }
                    if (operation == "!=") "(!$equality)" else equality
                }
                else -> {
                    val name = when (operation) {
                        "+" -> if (left.name == "String") "concat" else "add"
                        "-" -> "subtract"; "*" -> "multiply"; "/" -> "divide"; "%" -> "remainder"
                        else -> error("Unknown operator")
                    }
                    runtime(name, "$a, $b, ${location(expression.token)}")
                }
            }
        }
    }
    private fun body(statements: List<Stmt>) { for (statement in statements) statement(statement) }
    private fun statement(statement: Stmt) {
        line(runtime("tick", location(statement.token)))
        when (statement) {
            is Stmt.Binding -> line("${if (swift) { if (statement.mutable) "var" else "let" } else { if (statement.mutable) "var" else "val" }} ${symbol(statement.name)}: ${type(checked.bindingTypes[statement]!!)} = ${expression(statement.initializer)}")
            is Stmt.Assign -> line("${symbol(statement.name)} = ${expression(statement.value)}")
            is Stmt.Expression -> {
                val value = if (swift && checked.types[statement.expression]?.name == "null") "(nil as SwikoUnit?)" else expression(statement.expression)
                line((if (swift) "_ = " else "") + value)
            }
            is Stmt.Function -> {
                val parameters = statement.parameters.joinToString(", ") { "${if (swift) "_ " else ""}${symbol(it.name)}: ${type(it.type)}" }
                line(if (swift) "func ${symbol(statement.name)}($parameters) throws -> ${type(statement.result)} {"
                    else "fun ${symbol(statement.name)}($parameters): ${type(statement.result)} {")
                depth++
                line(runtime("enter", location(statement.name)))
                if (swift) line("defer { skrt.leave() }") else { line("try {"); depth++ }
                body(statement.body)
                if (statement.result.name == "Unit") line("return ${if (swift) "SwikoUnit()" else "Unit"}")
                if (!swift) { depth--; line("} finally { skrt.leave() }") }
                depth--; line("}")
            }
            is Stmt.If -> {
                line("if ${if (swift) expression(statement.condition) else "(${expression(statement.condition)})"} {")
                depth++; body(statement.yes); depth--
                if (statement.no.isEmpty()) line("}") else {
                    line("} else {"); depth++; body(statement.no); depth--; line("}")
                }
            }
            is Stmt.While -> {
                line("while ${if (swift) expression(statement.condition) else "(${expression(statement.condition)})"} {")
                depth++; line(runtime("tick", location(statement.token))); body(statement.body); depth--; line("}")
            }
            is Stmt.Return -> line("return ${statement.expression?.let { expression(it) } ?: if (swift) "SwikoUnit()" else "Unit"}")
            is Stmt.Block -> {
                line(if (swift) "do {" else "run {"); depth++; body(statement.body); depth--; line("}")
            }
        }
    }
    fun generate(statements: List<Stmt>): String {
        code.append(if (swift) swiftRuntime else kotlinRuntime)
        line()
        line(if (swift) "public func swikoRun() throws -> String {" else "public fun swikoRun(): String {")
        depth++
        line(if (swift) "let skrt = SwikoRuntime(source: ${quote(filename)})" else "val skrt = SwikoRuntime(${quote(filename)})")
        body(statements)
        line(if (swift) "return skrt.output" else "return skrt.output.toString()")
        depth--; line("}")
        line(if (swift) "public func swikoMain() throws { Swift.print(try swikoRun(), terminator: \"\") }"
            else "public fun swikoMain() { kotlin.io.print(swikoRun()) }")
        if (!library) {
            line(if (swift) "do { try swikoMain() } catch let error as SwikoGeneratedError {"
                else "fun main() { try { swikoMain() } catch (error: SwikoGeneratedError) {")
            depth++
            if (swift) {
                line("let message = error.description + \"\\n\"")
                line("FileHandle.standardError.write(Data(message.utf8)); exit(1)")
                depth--; line("} catch { FileHandle.standardError.write(Data(\"runtime error\\n\".utf8)); exit(1) }")
            } else {
                line("System.err.println(error.message)")
                line("kotlin.system.exitProcess(1)")
                depth--; line("} }")
            }
        }
        return code.toString()
    }
}

private val swiftRuntime = """
// Generated by Swiko 1.0.0. This is typed Swift source, not an interpreter.
import Foundation

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
    func showBool(_ value: Bool?) -> String { value.map { ${'$'}0 ? "true" : "false" } ?? "null" }
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
""".trimIndent() + "\n"

private val kotlinRuntime = """
// Generated by Swiko 1.0.0. This is typed Kotlin source, not an interpreter.
private class SwikoGeneratedError(message: String) : RuntimeException(message)
private class SwikoRuntime(private val source: String) {
    val output = StringBuilder()
    private var outputBytes = 0
    private var steps = 0
    private var calls = 0
    private fun fail(line: Int, column: Int, message: String): Nothing =
        throw SwikoGeneratedError("${'$'}source:${'$'}line:${'$'}column: error: ${'$'}message")
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
""".trimIndent() + "\n"

private sealed interface Entry {
    data class Binding(val type: Type, val mutable: Boolean, var value: Value) : Entry
    data class Function(val declaration: Stmt.Function, val closure: Environment) : Entry
}
private class Environment(private val parent: Environment? = null) {
    private val entries = mutableMapOf<String, Entry>()
    fun define(token: Token, entry: Entry) {
        if (token.text == "print") fail(token, "'print' is reserved")
        if (entries.containsKey(token.text)) fail(token, "Duplicate declaration '${token.text}'")
        entries[token.text] = entry
    }
    fun lookup(token: Token): Entry = entries[token.text] ?: parent?.lookup(token)
        ?: fail(token, "Unknown name '${token.text}'")
}
private class ReturnSignal(val value: Value, val at: Token) : RuntimeException(null, null, false, false)

private class Interpreter(private val checker: Checker) {
    private val output = StringBuilder()
    private var steps = 0
    private var calls = 0
    private var evaluationDepth = 0
    private var outputBytes = 0
    private fun tick(token: Token) {
        if (++steps > 100_000) fail(token, "Execution step limit exceeded")
    }
    fun run(statements: List<Stmt>): String {
        execute(statements, Environment())
        return output.toString()
    }
    private fun execute(statements: List<Stmt>, environment: Environment) {
        for (statement in statements) execute(statement, environment)
    }
    private fun execute(statement: Stmt, environment: Environment) {
        tick(statement.token)
        when (statement) {
            is Stmt.Binding -> {
                val value = evaluate(statement.initializer, environment)
                val type = checker.bindingTypes[statement] ?: error("Unchecked binding")
                checkType(type, value, statement.name)
                environment.define(statement.name, Entry.Binding(type, statement.mutable, value))
            }
            is Stmt.Assign -> {
                val entry = environment.lookup(statement.name)
                if (entry !is Entry.Binding || !entry.mutable) fail(statement.name, "Cannot assign to immutable name '${statement.name.text}'")
                val value = evaluate(statement.value, environment)
                checkType(entry.type, value, statement.name)
                entry.value = value
            }
            is Stmt.Expression -> evaluate(statement.expression, environment)
            is Stmt.Function -> environment.define(statement.name, Entry.Function(statement, environment))
            is Stmt.If -> {
                val condition = boolean(evaluate(statement.condition, environment), statement.condition.token)
                execute(if (condition) statement.yes else statement.no, Environment(environment))
            }
            is Stmt.While -> {
                while (boolean(evaluate(statement.condition, environment), statement.condition.token)) {
                    tick(statement.token)
                    execute(statement.body, Environment(environment))
                }
            }
            is Stmt.Return -> {
                if (calls == 0) fail(statement.token, "Return outside a function")
                throw ReturnSignal(statement.expression?.let { evaluate(it, environment) } ?: Value.UnitValue, statement.token)
            }
            is Stmt.Block -> execute(statement.body, Environment(environment))
        }
    }
    private fun checkType(type: Type, value: Value, token: Token) {
        if (!accepts(type, value)) fail(token, "Expected $type, found ${valueType(value)}")
    }
    private fun boolean(value: Value, token: Token): Boolean =
        (value as? Value.BooleanValue)?.value ?: fail(token, "Expected Bool, found ${valueType(value)}")
    private fun integer(value: Value, token: Token): Long =
        (value as? Value.Integer)?.value ?: fail(token, "Expected Int, found ${valueType(value)}")
    private fun evaluate(expression: Expr, environment: Environment): Value {
        tick(expression.token)
        evaluationDepth++
        try {
            if (evaluationDepth > 512) fail(expression.token, "Expression depth limit exceeded")
            return when (expression) {
            is Expr.Literal -> expression.value
            is Expr.Variable -> {
                val entry = environment.lookup(expression.token)
                if (entry is Entry.Binding) entry.value else fail(expression.token, "Functions must be called by name")
            }
            is Expr.Unary -> {
                val value = evaluate(expression.operand, environment)
                when (expression.operator.kind) {
                    "!" -> Value.BooleanValue(!boolean(value, expression.token))
                    else -> {
                        val number = integer(value, expression.token)
                        if (number == Long.MIN_VALUE) fail(expression.token, "Integer overflow")
                        Value.Integer(-number)
                    }
                }
            }
            is Expr.Binary -> binary(expression, environment)
            is Expr.Call -> call(expression, environment)
            }
        } finally {
            evaluationDepth--
        }
    }
    private fun binary(expression: Expr.Binary, environment: Environment): Value {
        val operator = expression.operator
        val left = evaluate(expression.left, environment)
        when (operator.kind) {
            "??" -> return if (left == Value.Null) evaluate(expression.right, environment) else left
            "&&" -> return Value.BooleanValue(boolean(left, operator) && boolean(evaluate(expression.right, environment), operator))
            "||" -> return Value.BooleanValue(boolean(left, operator) || boolean(evaluate(expression.right, environment), operator))
        }
        val right = evaluate(expression.right, environment)
        if (operator.kind == "==" || operator.kind == "!=") {
            if (left != Value.Null && right != Value.Null && valueType(left) != valueType(right)) {
                fail(operator, "Equality requires matching types")
            }
            return Value.BooleanValue(if (operator.kind == "==") left == right else left != right)
        }
        if (operator.kind == "+" && left is Value.Text && right is Value.Text) {
            if (bytes(left.value) + bytes(right.value) > STRING_LIMIT) fail(operator, "String size limit exceeded")
            return Value.Text(left.value + right.value)
        }
        val a = integer(left, operator)
        val b = integer(right, operator)
        return when (operator.kind) {
            "<" -> Value.BooleanValue(a < b)
            "<=" -> Value.BooleanValue(a <= b)
            ">" -> Value.BooleanValue(a > b)
            ">=" -> Value.BooleanValue(a >= b)
            "+" -> {
                val result = a + b
                if (((a xor result) and (b xor result)) < 0) fail(operator, "Integer overflow")
                Value.Integer(result)
            }
            "-" -> {
                val result = a - b
                if (((a xor b) and (a xor result)) < 0) fail(operator, "Integer overflow")
                Value.Integer(result)
            }
            "*" -> {
                val result = a * b
                if ((a == Long.MIN_VALUE && b == -1L) || (b == Long.MIN_VALUE && a == -1L) ||
                    (b != 0L && result / b != a)) fail(operator, "Integer overflow")
                Value.Integer(result)
            }
            "/", "%" -> {
                if (b == 0L) fail(operator, "Division or remainder by zero")
                if (a == Long.MIN_VALUE && b == -1L) fail(operator, "Integer overflow")
                Value.Integer(if (operator.kind == "/") a / b else a % b)
            }
            else -> fail(operator, "Unknown operator")
        }
    }
    private fun call(expression: Expr.Call, environment: Environment): Value {
        if (expression.name.text == "print") {
            if (expression.arguments.size != 1) fail(expression.name, "print expects one argument")
            val text = render(evaluate(expression.arguments.single(), environment))
            val count = bytes(text) + 1
            if (count > OUTPUT_LIMIT - outputBytes) fail(expression.name, "Output size limit exceeded")
            outputBytes += count
            output.append(text).append('\n')
            return Value.UnitValue
        }
        val function = environment.lookup(expression.name) as? Entry.Function
            ?: fail(expression.name, "Name '${expression.name.text}' is not a function")
        val declaration = function.declaration
        if (declaration.parameters.size != expression.arguments.size) fail(expression.name, "Wrong number of arguments")
        if (calls >= 128) fail(expression.name, "Function call depth limit exceeded")
        val local = Environment(function.closure)
        declaration.parameters.zip(expression.arguments).forEach { (parameter, argument) ->
            val value = evaluate(argument, environment)
            checkType(parameter.type, value, argument.token)
            local.define(parameter.name, Entry.Binding(parameter.type, false, value))
        }
        calls++
        try {
            try { execute(declaration.body, local) }
            catch (returned: ReturnSignal) {
                checkType(declaration.result, returned.value, returned.at)
                return returned.value
            }
            if (declaration.result.name != "Unit") fail(declaration.name, "Function must return ${declaration.result}")
            return Value.UnitValue
        } finally { calls-- }
    }
}

private fun normalize(source: String): String {
    if (bytes(source) > SOURCE_LIMIT) fail(origin, "Source size limit exceeded")
    return source.removePrefix("\uFEFF").replace("\r\n", "\n").replace('\r', '\n')
}
private fun prepare(source: String): Pair<List<Stmt>, Checker> {
    val statements = Parser(Lexer(normalize(source)).scan()).parse()
    val checker = Checker()
    checker.check(statements)
    return statements to checker
}

/** A fresh, statically checked program with bounded buffered output. */
fun runSwiko(source: String): String {
    val (statements, checker) = prepare(source)
    return Interpreter(checker).run(statements)
}

private class UsageError(message: String) : Exception(message)
private data class Options(val mode: String, val library: Boolean, val input: String, val eval: String?)
private fun options(args: Array<String>): Options {
    var mode = "run"
    var modeSet = false
    var library = false
    var input: String? = null
    var eval: String? = null
    var index = 0
    var ended = false
    while (index < args.size) {
        val argument = args[index++]
        when {
            !ended && argument == "--" -> {
                if (index != args.size - 1 || input != null || eval != null) throw UsageError("-- must precede one filename")
                ended = true
            }
            !ended && argument in setOf("--check", "--emit-swift", "--emit-kotlin") -> {
                if (modeSet) throw UsageError("Choose only one mode")
                mode = argument.removePrefix("--")
                modeSet = true
            }
            !ended && argument == "--library" -> {
                if (library) throw UsageError("Duplicate --library")
                library = true
            }
            !ended && argument == "--eval" -> {
                if (index == args.size || input != null || eval != null) throw UsageError("--eval needs exactly one source argument")
                eval = args[index++]
                input = "<eval>"
            }
            !ended && argument.startsWith("-") && argument != "-" -> throw UsageError("Unknown option '$argument'")
            else -> {
                if (input != null) throw UsageError("Expected exactly one source input")
                input = argument
            }
        }
    }
    if (input == null) throw UsageError("Expected a source file, -, or --eval SOURCE")
    if (library && mode !in setOf("emit-swift", "emit-kotlin")) throw UsageError("--library requires an emit mode")
    return Options(mode, library, input, eval)
}
private fun readBounded(stream: InputStream): String {
    val data = stream.readNBytes(SOURCE_LIMIT + 1)
    if (data.size > SOURCE_LIMIT) fail(origin, "Source size limit exceeded")
    return try {
        Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(data)).toString()
    } catch (_: java.nio.charset.CharacterCodingException) { fail(origin, "Source is not valid UTF-8") }
}
private val usage = """
Usage: swiko [--check | --emit-swift | --emit-kotlin] [--library] FILE
       swiko [--check | --emit-swift | --emit-kotlin] [--library] --eval SOURCE
       swiko [--check | --emit-swift | --emit-kotlin] [--library] -
       swiko --help | --version
""".trimIndent()

fun main(args: Array<String>) {
    if (args.contentEquals(arrayOf("--help"))) { println(usage); return }
    if (args.contentEquals(arrayOf("--version"))) { println("Swiko 1.0.0"); return }
    val options = try { options(args) }
    catch (error: UsageError) {
        System.err.println("error: ${error.message}\n$usage")
        exitProcess(2)
    }
    val filename = if (options.input == "-") "<stdin>" else options.input
    try {
        val source = options.eval ?: if (options.input == "-") readBounded(System.`in`)
            else Files.newInputStream(Path(options.input)).use(::readBounded)
        val (statements, checker) = prepare(source)
        when (options.mode) {
            "check" -> Unit
            "emit-swift", "emit-kotlin" -> kotlin.io.print(Generator(checker, options.mode == "emit-swift", filename, options.library).generate(statements))
            else -> kotlin.io.print(Interpreter(checker).run(statements))
        }
    } catch (error: SwikoError) {
        System.err.println("$filename:${error.token.line}:${error.token.column}: error: ${error.message}")
        exitProcess(1)
    } catch (_: StackOverflowError) {
        System.err.println("$filename:1:1: error: Source or execution nesting limit exceeded")
        exitProcess(1)
    } catch (error: java.io.IOException) {
        System.err.println("$filename:1:1: error: Cannot read source file (${error.javaClass.simpleName})")
        exitProcess(1)
    }
}

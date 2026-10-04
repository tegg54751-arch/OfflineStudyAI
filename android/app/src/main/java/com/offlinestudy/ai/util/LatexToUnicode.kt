package com.offlinestudy.ai.util

/**
 * Простые LaTeX-формулы → читаемый Unicode, полностью офлайн:
 * `\frac{a}{b}` → `a/b`, `x^2` → `x²`, `\sqrt{x}` → `√x`, `\ce{H2SO4}` → `H₂SO₄`.
 */
object LatexToUnicode {
    fun convert(latex: String): String =
        Parser(latex).parseSequence(untilClosingBrace = false)
            .replace("  ", " ")
            .trim()

    private val symbols = mapOf(
        "cdot" to "·", "times" to "×", "div" to "÷", "pm" to "±", "mp" to "∓",
        "leq" to "≤", "le" to "≤", "geq" to "≥", "ge" to "≥", "neq" to "≠", "ne" to "≠",
        "approx" to "≈", "equiv" to "≡", "sim" to "∼", "propto" to "∝",
        "infty" to "∞", "to" to "→", "rightarrow" to "→", "leftarrow" to "←", "Rightarrow" to "⇒",
        "Leftarrow" to "⇐", "leftrightarrow" to "↔", "Leftrightarrow" to "⇔", "rightleftharpoons" to "⇌",
        "uparrow" to "↑", "downarrow" to "↓",
        "alpha" to "α", "beta" to "β", "gamma" to "γ", "delta" to "δ", "epsilon" to "ε", "varepsilon" to "ε",
        "zeta" to "ζ", "eta" to "η", "theta" to "θ", "lambda" to "λ", "mu" to "μ", "nu" to "ν", "xi" to "ξ",
        "pi" to "π", "rho" to "ρ", "sigma" to "σ", "tau" to "τ", "phi" to "φ", "varphi" to "φ", "chi" to "χ",
        "psi" to "ψ", "omega" to "ω",
        "Gamma" to "Γ", "Delta" to "Δ", "Theta" to "Θ", "Lambda" to "Λ", "Pi" to "Π", "Sigma" to "Σ",
        "Phi" to "Φ", "Psi" to "Ψ", "Omega" to "Ω",
        "circ" to "°", "degree" to "°", "angle" to "∠", "perp" to "⊥", "parallel" to "∥", "triangle" to "△",
        "in" to "∈", "notin" to "∉", "subset" to "⊂", "cup" to "∪", "cap" to "∩", "emptyset" to "∅", "varnothing" to "∅",
        "forall" to "∀", "exists" to "∃", "sum" to "∑", "prod" to "∏", "int" to "∫", "partial" to "∂", "nabla" to "∇",
        "ldots" to "…", "dots" to "…", "cdots" to "⋯", "quad" to "  ", "qquad" to "    ",
        "N" to "ℕ", "Z" to "ℤ", "R" to "ℝ", "Q" to "ℚ", "lt" to "<", "gt" to ">"
    )

    private val functions = setOf(
        "sin", "cos", "tan", "tg", "cot", "ctg", "log", "lg", "ln", "exp", "lim", "max", "min",
        "arcsin", "arccos", "arctan", "arctg", "sec", "csc", "det"
    )

    private val superscripts = mapOf(
        '0' to '⁰', '1' to '¹', '2' to '²', '3' to '³', '4' to '⁴', '5' to '⁵', '6' to '⁶', '7' to '⁷', '8' to '⁸', '9' to '⁹',
        '+' to '⁺', '-' to '⁻', '−' to '⁻', '=' to '⁼', '(' to '⁽', ')' to '⁾', 'n' to 'ⁿ', 'i' to 'ⁱ', 'x' to 'ˣ', 'y' to 'ʸ',
        'a' to 'ᵃ', 'b' to 'ᵇ', 'c' to 'ᶜ', 'd' to 'ᵈ', 'e' to 'ᵉ', 'k' to 'ᵏ', 'm' to 'ᵐ', 'o' to 'ᵒ', 't' to 'ᵗ',
        '°' to '°', ' ' to ' '
    )

    private val subscripts = mapOf(
        '0' to '₀', '1' to '₁', '2' to '₂', '3' to '₃', '4' to '₄', '5' to '₅', '6' to '₆', '7' to '₇', '8' to '₈', '9' to '₉',
        '+' to '₊', '-' to '₋', '−' to '₋', '=' to '₌', '(' to '₍', ')' to '₎', 'a' to 'ₐ', 'e' to 'ₑ', 'o' to 'ₒ',
        'x' to 'ₓ', 'i' to 'ᵢ', 'n' to 'ₙ', 'm' to 'ₘ', 'k' to 'ₖ', 't' to 'ₜ', 'p' to 'ₚ', 's' to 'ₛ', 'r' to 'ᵣ', ' ' to ' '
    )

    fun toSuperscript(text: String): String {
        if (text.isEmpty()) return ""
        if (text.all { superscripts.containsKey(it) }) return text.map { superscripts.getValue(it) }.joinToString("")
        return if (text.length == 1) "^$text" else "^($text)"
    }

    fun toSubscript(text: String): String {
        if (text.isEmpty()) return ""
        if (text.all { subscripts.containsKey(it) }) return text.map { subscripts.getValue(it) }.joinToString("")
        return "_$text"
    }

    fun fraction(numerator: String, denominator: String): String {
        fun simple(s: String) = s.none { "+-−·×÷ =".contains(it) } && s.length <= 4
        val n = if (simple(numerator)) numerator else "($numerator)"
        val d = if (simple(denominator)) denominator else "($denominator)"
        return "$n/$d"
    }

    fun chemistry(text: String): String {
        val sb = StringBuilder()
        for (ch in text) {
            val previous = sb.lastOrNull()
            if (ch.isDigit() && previous != null && (previous.isLetter() || previous == ')' || previous == ']' || subscripts.containsValue(previous))) {
                sb.append(subscripts[ch] ?: ch)
            } else {
                sb.append(ch)
            }
        }
        return sb.toString().replace("<->", "⇄").replace("<=>", "⇌").replace("->", "→")
    }

    private class Parser(source: String) {
        private val chars = source.toCharArray()
        private var index = 0

        fun parseSequence(untilClosingBrace: Boolean): String {
            val out = StringBuilder()
            while (index < chars.size) {
                when (val ch = chars[index]) {
                    '}' -> { index++; if (untilClosingBrace) return out.toString() }
                    '{' -> { index++; out.append(parseSequence(true)) }
                    '\\' -> out.append(parseCommand())
                    '^' -> { index++; out.append(toSuperscript(parseArgument())) }
                    '_' -> { index++; out.append(toSubscript(parseArgument())) }
                    '$', '&' -> index++
                    '~' -> { index++; out.append(' ') }
                    else -> { out.append(ch); index++ }
                }
            }
            return out.toString()
        }

        private fun skipSpaces() { while (index < chars.size && chars[index] == ' ') index++ }

        private fun parseArgument(): String {
            skipSpaces()
            if (index >= chars.size) return ""
            val ch = chars[index]
            if (ch == '{') { index++; return parseSequence(true) }
            if (ch == '\\') return parseCommand()
            index++
            return ch.toString()
        }

        private fun parseOptionalArgument(): String? {
            skipSpaces()
            if (index >= chars.size || chars[index] != '[') return null
            index++
            var depth = 0
            val raw = StringBuilder()
            while (index < chars.size) {
                val ch = chars[index++]
                if (ch == '[') depth++
                if (ch == ']') { if (depth == 0) break; depth-- }
                raw.append(ch)
            }
            return convert(raw.toString())
        }

        private fun parseCommand(): String {
            index++ // '\'
            if (index >= chars.size) return ""
            val first = chars[index]
            if (!first.isLetter()) {
                index++
                return when (first) {
                    ',', ';', ':', ' ', '>' -> " "
                    '!' -> ""
                    '\\' -> "\n"
                    '{' -> "{"
                    '}' -> "}"
                    '(', ')', '[', ']' -> ""
                    '|' -> "‖"
                    else -> first.toString()
                }
            }
            val name = StringBuilder()
            while (index < chars.size && chars[index].isLetter()) name.append(chars[index++])
            return when (val n = name.toString()) {
                "frac", "dfrac", "tfrac" -> { val a = parseArgument(); val b = parseArgument(); fraction(a, b) }
                "sqrt" -> {
                    val degree = parseOptionalArgument()
                    val body = parseArgument()
                    val radicand = if (body.length > 1) "($body)" else body
                    if (!degree.isNullOrEmpty()) toSuperscript(degree) + "√" + radicand else "√$radicand"
                }
                "text", "textrm", "mathrm", "mathbf", "textbf", "mathit", "textit", "operatorname", "mbox", "boldsymbol" -> parseArgument()
                "mathbb" -> { val arg = parseArgument(); symbols[arg] ?: arg }
                "ce", "pu" -> chemistry(parseArgument())
                "vec", "overrightarrow" -> parseArgument() + "⃗"
                "overline", "bar" -> parseArgument() + "̅"
                "hat" -> parseArgument() + "̂"
                "left", "right", "big", "Big", "bigl", "bigr", "Bigl", "Bigr", "displaystyle", "limits", "nolimits" -> ""
                "begin", "end" -> { parseArgument(); if (n == "begin") "" else "\n" }
                else -> symbols[n] ?: if (functions.contains(n)) "$n " else n
            }
        }
    }
}

import Foundation

/// Превращает простые LaTeX-формулы в читаемый Unicode-текст полностью офлайн:
/// `\frac{a}{b}` → `a/b`, `x^2` → `x²`, `\sqrt{x}` → `√x`, `\ce{H2SO4}` → `H₂SO₄`.
///
/// Полноценная вёрстка LaTeX (многоэтажные дроби, матрицы) потребовала бы
/// стороннюю библиотеку; для школьных формул такого преобразования достаточно.
enum LatexToUnicode {
    static func convert(_ latex: String) -> String {
        var parser = Parser(chars: Array(latex))
        let result = parser.parseSequence(untilClosingBrace: false)
        return result
            .replacingOccurrences(of: "  ", with: " ")
            .trimmingCharacters(in: .whitespaces)
    }

    // MARK: - Таблицы символов

    static let symbols: [String: String] = [
        "cdot": "·", "times": "×", "div": "÷", "pm": "±", "mp": "∓",
        "leq": "≤", "le": "≤", "geq": "≥", "ge": "≥", "neq": "≠", "ne": "≠",
        "approx": "≈", "equiv": "≡", "sim": "∼", "propto": "∝",
        "infty": "∞", "to": "→", "rightarrow": "→", "leftarrow": "←", "Rightarrow": "⇒",
        "Leftarrow": "⇐", "leftrightarrow": "↔", "Leftrightarrow": "⇔", "rightleftharpoons": "⇌",
        "uparrow": "↑", "downarrow": "↓",
        "alpha": "α", "beta": "β", "gamma": "γ", "delta": "δ", "epsilon": "ε", "varepsilon": "ε",
        "zeta": "ζ", "eta": "η", "theta": "θ", "lambda": "λ", "mu": "μ", "nu": "ν", "xi": "ξ",
        "pi": "π", "rho": "ρ", "sigma": "σ", "tau": "τ", "phi": "φ", "varphi": "φ", "chi": "χ",
        "psi": "ψ", "omega": "ω",
        "Gamma": "Γ", "Delta": "Δ", "Theta": "Θ", "Lambda": "Λ", "Pi": "Π", "Sigma": "Σ",
        "Phi": "Φ", "Psi": "Ψ", "Omega": "Ω",
        "circ": "°", "degree": "°", "angle": "∠", "perp": "⊥", "parallel": "∥", "triangle": "△",
        "in": "∈", "notin": "∉", "subset": "⊂", "cup": "∪", "cap": "∩", "emptyset": "∅", "varnothing": "∅",
        "forall": "∀", "exists": "∃", "sum": "∑", "prod": "∏", "int": "∫", "partial": "∂", "nabla": "∇",
        "ldots": "…", "dots": "…", "cdots": "⋯", "quad": "  ", "qquad": "    ",
        "mathbb": "", "N": "ℕ", "Z": "ℤ", "R": "ℝ", "Q": "ℚ",
        "lt": "<", "gt": ">", "%": "%"
    ]

    static let functions: Set<String> = [
        "sin", "cos", "tan", "tg", "cot", "ctg", "log", "lg", "ln", "exp", "lim", "max", "min",
        "arcsin", "arccos", "arctan", "arctg", "sec", "csc", "det"
    ]

    static let superscripts: [Character: Character] = [
        "0": "⁰", "1": "¹", "2": "²", "3": "³", "4": "⁴", "5": "⁵", "6": "⁶", "7": "⁷", "8": "⁸", "9": "⁹",
        "+": "⁺", "-": "⁻", "−": "⁻", "=": "⁼", "(": "⁽", ")": "⁾", "n": "ⁿ", "i": "ⁱ", "x": "ˣ", "y": "ʸ",
        "a": "ᵃ", "b": "ᵇ", "c": "ᶜ", "d": "ᵈ", "e": "ᵉ", "k": "ᵏ", "m": "ᵐ", "o": "ᵒ", "t": "ᵗ",
        "°": "°", " ": " "
    ]

    static let subscripts: [Character: Character] = [
        "0": "₀", "1": "₁", "2": "₂", "3": "₃", "4": "₄", "5": "₅", "6": "₆", "7": "₇", "8": "₈", "9": "₉",
        "+": "₊", "-": "₋", "−": "₋", "=": "₌", "(": "₍", ")": "₎", "a": "ₐ", "e": "ₑ", "o": "ₒ",
        "x": "ₓ", "i": "ᵢ", "n": "ₙ", "m": "ₘ", "k": "ₖ", "t": "ₜ", "p": "ₚ", "s": "ₛ", "r": "ᵣ", " ": " "
    ]

    static func toSuperscript(_ text: String) -> String {
        if text.isEmpty { return "" }
        if text.allSatisfy({ superscripts[$0] != nil }) {
            return String(text.map { superscripts[$0]! })
        }
        return text.count == 1 ? "^" + text : "^(" + text + ")"
    }

    static func toSubscript(_ text: String) -> String {
        if text.isEmpty { return "" }
        if text.allSatisfy({ subscripts[$0] != nil }) {
            return String(text.map { subscripts[$0]! })
        }
        return "_" + text
    }

    static func fraction(_ numerator: String, _ denominator: String) -> String {
        func isSimple(_ s: String) -> Bool {
            !s.contains(where: { "+-−·×÷ =".contains($0) }) && s.count <= 4
        }
        let n = isSimple(numerator) ? numerator : "(\(numerator))"
        let d = isSimple(denominator) ? denominator : "(\(denominator))"
        return "\(n)/\(d)"
    }

    /// `H2SO4` → `H₂SO₄`, `->` → `→`
    static func chemistry(_ text: String) -> String {
        var result = ""
        var previous: Character?
        for ch in text {
            if ch.isNumber, let p = previous, p.isLetter || p == ")" || p == "]" || subscripts.values.contains(p) {
                result.append(subscripts[ch] ?? ch)
            } else {
                result.append(ch)
            }
            previous = result.last
        }
        return result
            .replacingOccurrences(of: "<->", with: "⇄")
            .replacingOccurrences(of: "<=>", with: "⇌")
            .replacingOccurrences(of: "->", with: "→")
    }

    // MARK: - Рекурсивный разбор

    private struct Parser {
        let chars: [Character]
        var index = 0

        mutating func parseSequence(untilClosingBrace: Bool) -> String {
            var output = ""
            while index < chars.count {
                let ch = chars[index]
                switch ch {
                case "}":
                    index += 1
                    if untilClosingBrace { return output }
                case "{":
                    index += 1
                    output += parseSequence(untilClosingBrace: true)
                case "\\":
                    output += parseCommand()
                case "^":
                    index += 1
                    output += LatexToUnicode.toSuperscript(parseArgument())
                case "_":
                    index += 1
                    output += LatexToUnicode.toSubscript(parseArgument())
                case "$", "&":
                    index += 1
                case "~":
                    index += 1
                    output += " "
                default:
                    output.append(ch)
                    index += 1
                }
            }
            return output
        }

        mutating func skipSpaces() {
            while index < chars.count, chars[index] == " " { index += 1 }
        }

        mutating func parseArgument() -> String {
            skipSpaces()
            guard index < chars.count else { return "" }
            let ch = chars[index]
            if ch == "{" {
                index += 1
                return parseSequence(untilClosingBrace: true)
            }
            if ch == "\\" {
                return parseCommand()
            }
            index += 1
            return String(ch)
        }

        mutating func parseOptionalArgument() -> String? {
            skipSpaces()
            guard index < chars.count, chars[index] == "[" else { return nil }
            index += 1
            var depth = 0
            var raw = ""
            while index < chars.count {
                let ch = chars[index]
                index += 1
                if ch == "[" { depth += 1 }
                if ch == "]" {
                    if depth == 0 { break }
                    depth -= 1
                }
                raw.append(ch)
            }
            return LatexToUnicode.convert(raw)
        }

        mutating func parseCommand() -> String {
            index += 1 // пропускаем '\'
            guard index < chars.count else { return "" }
            let first = chars[index]

            if !first.isLetter {
                index += 1
                switch first {
                case ",", ";", ":", " ", ">": return " "
                case "!": return ""
                case "\\": return "\n"
                case "{": return "{"
                case "}": return "}"
                case "(", ")", "[", "]": return ""
                case "|": return "‖"
                default: return String(first)
                }
            }

            var name = ""
            while index < chars.count, chars[index].isLetter {
                name.append(chars[index])
                index += 1
            }

            switch name {
            case "frac", "dfrac", "tfrac":
                let numerator = parseArgument()
                let denominator = parseArgument()
                return LatexToUnicode.fraction(numerator, denominator)
            case "sqrt":
                let degree = parseOptionalArgument()
                let body = parseArgument()
                let radicand = body.count > 1 ? "(\(body))" : body
                if let degree, !degree.isEmpty {
                    return LatexToUnicode.toSuperscript(degree) + "√" + radicand
                }
                return "√" + radicand
            case "text", "textrm", "mathrm", "mathbf", "textbf", "mathit", "textit", "operatorname", "mbox", "boldsymbol":
                return parseArgument()
            case "mathbb":
                let arg = parseArgument()
                return LatexToUnicode.symbols[arg] ?? arg
            case "ce", "pu":
                return LatexToUnicode.chemistry(parseArgument())
            case "vec", "overrightarrow":
                return parseArgument() + "\u{20D7}"
            case "overline", "bar":
                return parseArgument() + "\u{0305}"
            case "hat":
                return parseArgument() + "\u{0302}"
            case "left", "right", "big", "Big", "bigl", "bigr", "Bigl", "Bigr", "displaystyle", "limits", "nolimits":
                return ""
            case "begin", "end":
                _ = parseArgument() // окружение (cases, aligned…) — просто пропускаем
                return name == "begin" ? "" : "\n"
            default:
                if let symbol = LatexToUnicode.symbols[name] { return symbol }
                if LatexToUnicode.functions.contains(name) { return name + " " }
                return name
            }
        }
    }
}

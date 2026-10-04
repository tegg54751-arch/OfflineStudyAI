import SwiftUI

/// Лёгкий офлайн-рендерер Markdown для ответов модели:
/// заголовки, списки, нумерация, цитаты, блоки кода, таблицы (моноширинно),
/// жирный/курсив/`код` и формулы LaTeX ($...$, $$...$$, \(...\), \[...\]).
struct MarkdownView: View {
    let text: String

    var body: some View {
        let blocks = MarkdownParser.parse(text)
        VStack(alignment: .leading, spacing: 10) {
            ForEach(Array(blocks.enumerated()), id: \.offset) { _, block in
                blockView(block)
            }
        }
        .textSelection(.enabled)
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    @ViewBuilder
    private func blockView(_ block: MarkdownBlock) -> some View {
        switch block {
        case .heading(let level, let content):
            Text(MarkdownParser.inline(content))
                .font(level == 1 ? .title2.bold() : level == 2 ? .title3.bold() : .headline)
                .padding(.top, 2)

        case .paragraph(let content):
            Text(MarkdownParser.inline(content))
                .fixedSize(horizontal: false, vertical: true)

        case .bullet(let items):
            VStack(alignment: .leading, spacing: 6) {
                ForEach(Array(items.enumerated()), id: \.offset) { _, item in
                    HStack(alignment: .firstTextBaseline, spacing: 8) {
                        Circle()
                            .fill(Palette.violet)
                            .frame(width: 6, height: 6)
                            .alignmentGuide(.firstTextBaseline) { d in d[.bottom] + 1 }
                        Text(MarkdownParser.inline(item))
                            .fixedSize(horizontal: false, vertical: true)
                    }
                    .padding(.leading, 2)
                }
            }

        case .numbered(let items):
            VStack(alignment: .leading, spacing: 6) {
                ForEach(Array(items.enumerated()), id: \.offset) { _, item in
                    HStack(alignment: .firstTextBaseline, spacing: 8) {
                        Text("\(item.number).")
                            .fontWeight(.bold)
                            .foregroundStyle(Palette.violet)
                            .monospacedDigit()
                        Text(MarkdownParser.inline(item.text))
                            .fixedSize(horizontal: false, vertical: true)
                    }
                }
            }

        case .quote(let content):
            HStack(spacing: 10) {
                RoundedRectangle(cornerRadius: 2)
                    .fill(Palette.teal)
                    .frame(width: 4)
                Text(MarkdownParser.inline(content))
                    .italic()
                    .foregroundStyle(.secondary)
                    .fixedSize(horizontal: false, vertical: true)
            }

        case .code(let content):
            ScrollView(.horizontal, showsIndicators: false) {
                Text(content)
                    .font(.system(.callout, design: .monospaced))
                    .padding(12)
            }
            .background(Color.primary.opacity(0.06), in: RoundedRectangle(cornerRadius: 12, style: .continuous))

        case .formula(let content):
            ScrollView(.horizontal, showsIndicators: false) {
                Text(LatexToUnicode.convert(content))
                    .font(.system(.title3, design: .serif))
                    .italic()
                    .padding(.vertical, 10)
                    .padding(.horizontal, 14)
            }
            .frame(maxWidth: .infinity, alignment: .center)
            .background(Palette.indigo.opacity(0.08), in: RoundedRectangle(cornerRadius: 12, style: .continuous))

        case .table(let rows):
            ScrollView(.horizontal, showsIndicators: false) {
                Grid(alignment: .leading, horizontalSpacing: 14, verticalSpacing: 6) {
                    ForEach(Array(rows.enumerated()), id: \.offset) { index, row in
                        GridRow {
                            ForEach(Array(row.enumerated()), id: \.offset) { _, cell in
                                Text(MarkdownParser.inline(cell))
                                    .font(index == 0 ? .subheadline.bold() : .subheadline)
                            }
                        }
                        if index == 0 { Divider() }
                    }
                }
                .padding(12)
            }
            .background(Color.primary.opacity(0.05), in: RoundedRectangle(cornerRadius: 12, style: .continuous))

        case .divider:
            Divider()
        }
    }
}

// MARK: - Разбор

struct NumberedItem: Hashable {
    var number: Int
    var text: String
}

enum MarkdownBlock: Hashable {
    case heading(Int, String)
    case paragraph(String)
    case bullet([String])
    case numbered([NumberedItem])
    case quote(String)
    case code(String)
    case formula(String)
    case table([[String]])
    case divider
}

enum MarkdownParser {
    static func parse(_ source: String) -> [MarkdownBlock] {
        var blocks: [MarkdownBlock] = []
        let lines = source.replacingOccurrences(of: "\r\n", with: "\n").components(separatedBy: "\n")
        var index = 0
        var paragraph: [String] = []

        func flushParagraph() {
            if !paragraph.isEmpty {
                blocks.append(.paragraph(paragraph.joined(separator: "\n")))
                paragraph.removeAll()
            }
        }

        while index < lines.count {
            let rawLine = lines[index]
            let line = rawLine.trimmingCharacters(in: .whitespaces)

            // Пустая строка — конец абзаца.
            if line.isEmpty {
                flushParagraph()
                index += 1
                continue
            }

            // Блок кода ```
            if line.hasPrefix("```") {
                flushParagraph()
                var code: [String] = []
                index += 1
                while index < lines.count, !lines[index].trimmingCharacters(in: .whitespaces).hasPrefix("```") {
                    code.append(lines[index])
                    index += 1
                }
                index += 1
                blocks.append(.code(code.joined(separator: "\n")))
                continue
            }

            // Блочная формула $$ ... $$ или \[ ... \]
            if line.hasPrefix("$$") || line.hasPrefix("\\[") {
                flushParagraph()
                let closing = line.hasPrefix("$$") ? "$$" : "\\]"
                var body = String(line.dropFirst(2))
                if let range = body.range(of: closing) {
                    let after = String(body[range.upperBound...]).trimmingCharacters(in: .whitespaces)
                    body = String(body[..<range.lowerBound])
                    blocks.append(.formula(body))
                    if !after.isEmpty { paragraph.append(after) }
                    index += 1
                    continue
                }
                var parts = [body]
                index += 1
                while index < lines.count {
                    let next = lines[index]
                    if let range = next.range(of: closing) {
                        parts.append(String(next[..<range.lowerBound]))
                        index += 1
                        break
                    }
                    parts.append(next)
                    index += 1
                }
                blocks.append(.formula(parts.joined(separator: " ")))
                continue
            }

            // Горизонтальная линия
            if line == "---" || line == "***" || line == "___" {
                flushParagraph()
                blocks.append(.divider)
                index += 1
                continue
            }

            // Заголовки
            if line.hasPrefix("#") {
                let level = line.prefix(while: { $0 == "#" }).count
                if level <= 6, line.dropFirst(level).first == " " {
                    flushParagraph()
                    blocks.append(.heading(min(level, 3), String(line.dropFirst(level + 1))))
                    index += 1
                    continue
                }
            }

            // Таблица
            if line.hasPrefix("|") {
                flushParagraph()
                var rows: [[String]] = []
                while index < lines.count {
                    let row = lines[index].trimmingCharacters(in: .whitespaces)
                    guard row.hasPrefix("|") else { break }
                    let cells = row.trimmingCharacters(in: CharacterSet(charactersIn: "|"))
                        .components(separatedBy: "|")
                        .map { $0.trimmingCharacters(in: .whitespaces) }
                    let isSeparator = cells.allSatisfy { cell in
                        !cell.isEmpty && cell.allSatisfy { "-:".contains($0) }
                    }
                    if !isSeparator { rows.append(cells) }
                    index += 1
                }
                blocks.append(.table(rows))
                continue
            }

            // Маркированный список
            if let item = bulletContent(line) {
                flushParagraph()
                var items = [item]
                index += 1
                while index < lines.count {
                    let next = lines[index].trimmingCharacters(in: .whitespaces)
                    if let nextItem = bulletContent(next) {
                        items.append(nextItem)
                        index += 1
                    } else if !next.isEmpty, lines[index].hasPrefix("  "), !items.isEmpty {
                        items[items.count - 1] += " " + next // продолжение пункта
                        index += 1
                    } else {
                        break
                    }
                }
                blocks.append(.bullet(items))
                continue
            }

            // Нумерованный список
            if let item = numberedContent(line) {
                flushParagraph()
                var items = [item]
                index += 1
                while index < lines.count {
                    let next = lines[index].trimmingCharacters(in: .whitespaces)
                    if let nextItem = numberedContent(next) {
                        items.append(nextItem)
                        index += 1
                    } else if !next.isEmpty, lines[index].hasPrefix("  "), !items.isEmpty {
                        if let sub = bulletContent(next) {
                            items[items.count - 1].text += "\n• " + sub
                        } else {
                            items[items.count - 1].text += " " + next
                        }
                        index += 1
                    } else {
                        break
                    }
                }
                blocks.append(.numbered(items))
                continue
            }

            // Цитата
            if line.hasPrefix(">") {
                flushParagraph()
                var quote: [String] = []
                while index < lines.count {
                    let next = lines[index].trimmingCharacters(in: .whitespaces)
                    guard next.hasPrefix(">") else { break }
                    quote.append(String(next.dropFirst()).trimmingCharacters(in: .whitespaces))
                    index += 1
                }
                blocks.append(.quote(quote.joined(separator: "\n")))
                continue
            }

            paragraph.append(line)
            index += 1
        }
        flushParagraph()
        return blocks
    }

    private static func bulletContent(_ line: String) -> String? {
        for marker in ["- ", "* ", "• ", "+ ", "– "] where line.hasPrefix(marker) {
            return String(line.dropFirst(marker.count))
        }
        return nil
    }

    private static func numberedContent(_ line: String) -> NumberedItem? {
        let digits = line.prefix(while: { $0.isNumber })
        guard !digits.isEmpty, digits.count <= 3, let number = Int(digits) else { return nil }
        let rest = line.dropFirst(digits.count)
        guard let marker = rest.first, marker == "." || marker == ")" else { return nil }
        let afterMarker = rest.dropFirst()
        guard afterMarker.first == " " else { return nil }
        return NumberedItem(number: number, text: String(afterMarker.dropFirst()))
    }

    /// Строчное форматирование: формулы → Unicode, затем **жирный**, *курсив*, `код`.
    static func inline(_ text: String) -> AttributedString {
        let prepared = convertInlineFormulas(text)
        let options = AttributedString.MarkdownParsingOptions(
            allowsExtendedAttributes: false,
            interpretedSyntax: .inlineOnlyPreservingWhitespace,
            failurePolicy: .returnPartiallyParsedIfPossible
        )
        if let attributed = try? AttributedString(markdown: prepared, options: options) {
            return attributed
        }
        return AttributedString(prepared)
    }

    /// Находит `$...$` и `\(...\)` и заменяет на Unicode-формулы.
    static func convertInlineFormulas(_ text: String) -> String {
        guard text.contains("$") || text.contains("\\(") || text.contains("\\") else { return text }
        var result = ""
        var rest = Substring(text)

        while !rest.isEmpty {
            let dollar = rest.firstIndex(of: "$")
            let paren = rest.range(of: "\\(")?.lowerBound
            let next: String.Index?
            let isDollar: Bool
            switch (dollar, paren) {
            case let (d?, p?): next = min(d, p); isDollar = d < p
            case let (d?, nil): next = d; isDollar = true
            case let (nil, p?): next = p; isDollar = false
            default: next = nil; isDollar = false
            }
            guard let start = next else {
                result += rest
                break
            }
            result += rest[..<start]
            let openLength = isDollar ? (rest[start...].hasPrefix("$$") ? 2 : 1) : 2
            let afterOpen = rest.index(start, offsetBy: openLength)
            let closing = isDollar ? String(repeating: "$", count: openLength) : "\\)"
            if let close = rest[afterOpen...].range(of: closing) {
                let formula = String(rest[afterOpen..<close.lowerBound])
                // Похоже на цену ("100$") — оставляем как есть.
                if isDollar && formula.trimmingCharacters(in: .whitespaces).isEmpty {
                    result += rest[start..<close.upperBound]
                } else {
                    result += LatexToUnicode.convert(formula)
                }
                rest = rest[close.upperBound...]
            } else {
                // Незакрытая формула (ещё печатается) — показываем как есть, без знака $.
                let tail = String(rest[afterOpen...])
                result += tail.contains("\\") || tail.contains("^") ? LatexToUnicode.convert(tail) : tail
                rest = ""
            }
        }
        return result
    }
}

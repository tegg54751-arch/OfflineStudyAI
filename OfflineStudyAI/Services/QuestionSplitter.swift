import Foundation

/// Делит сообщение с несколькими вопросами на отдельные вопросы.
///
/// Поддерживаются:
///  • явные заголовки «Задание 1», «Вопрос 2», «№3»;
///  • нумерация «1. …», «2) …» (если это вопросы, а не варианты ответа);
///  • просто список строк, каждая из которых заканчивается «?».
/// Варианты ответа (а) б) в), 1) 2) 3) под вопросом) остаются внутри своего вопроса.
enum QuestionSplitter {
    struct Item {
        let label: String
        let text: String

        /// Короткий заголовок для ответа.
        var preview: String {
            let firstLine = text.split(separator: "\n").first.map(String.init) ?? text
            let cleaned = firstLine.replacingOccurrences(of: #"^\s*(задание|вопрос|задача|№)?\s*\d{1,2}\s*[\.\)]?\s*"#,
                                                         with: "", options: [.regularExpression, .caseInsensitive])
            let trimmed = cleaned.trimmingCharacters(in: .whitespaces)
            return trimmed.count > 70 ? String(trimmed.prefix(70)) + "…" : trimmed
        }
    }

    static let maxItems = 30

    static func split(_ text: String) -> [Item] {
        let lines = text.components(separatedBy: "\n")

        // 1. Явные заголовки.
        if let items = splitBy(lines: lines, pattern: #"^\s*(задание|вопрос|задача|№)\s*(\d{1,2})"#, numberGroup: 2), items.count >= 2 {
            return Array(items.prefix(maxItems))
        }

        // 2. Нумерованный список вопросов.
        if let items = splitBy(lines: lines, pattern: #"^\s*(\d{1,2})\s*[\.\)]\s+\S"#, numberGroup: 1), items.count >= 2 {
            let questionLike = items.filter { item in
                item.text.contains("?") || item.text.count >= 35 || item.text.contains("\n")
            }.count
            // Если «пункты» короткие и без вопросов — это варианты одного теста, а не отдельные вопросы.
            if questionLike * 2 >= items.count, !looksSequentialOptions(items) {
                return Array(items.prefix(maxItems))
            }
        }

        // 3. Несколько строк-вопросов, заканчивающихся «?».
        var items: [Item] = []
        var current: [String] = []
        for line in lines {
            let trimmed = line.trimmingCharacters(in: .whitespaces)
            if trimmed.isEmpty { continue }
            if trimmed.hasSuffix("?") {
                if !current.isEmpty { items.append(Item(label: "\(items.count + 1)", text: current.joined(separator: "\n"))) }
                current = [trimmed]
            } else if !current.isEmpty {
                current.append(trimmed) // варианты ответа к текущему вопросу
            }
        }
        if !current.isEmpty { items.append(Item(label: "\(items.count + 1)", text: current.joined(separator: "\n"))) }
        let questionCount = lines.filter { $0.trimmingCharacters(in: .whitespaces).hasSuffix("?") }.count
        if questionCount >= 3 && items.count >= 3 {
            return Array(items.prefix(maxItems))
        }
        return []
    }

    private static func splitBy(lines: [String], pattern: String, numberGroup: Int) -> [Item]? {
        guard let regex = try? NSRegularExpression(pattern: pattern, options: [.caseInsensitive]) else { return nil }
        var items: [Item] = []
        var currentLabel: String?
        var current: [String] = []
        for line in lines {
            let range = NSRange(line.startIndex..., in: line)
            if let match = regex.firstMatch(in: line, range: range),
               let numberRange = Range(match.range(at: numberGroup), in: line) {
                if let label = currentLabel {
                    items.append(Item(label: label, text: current.joined(separator: "\n").trimmingCharacters(in: .whitespacesAndNewlines)))
                }
                currentLabel = String(line[numberRange])
                current = [line]
            } else if currentLabel != nil {
                current.append(line)
            }
        }
        if let label = currentLabel {
            items.append(Item(label: label, text: current.joined(separator: "\n").trimmingCharacters(in: .whitespacesAndNewlines)))
        }
        return items.isEmpty ? nil : items
    }

    /// «1) да 2) нет 3) не знаю» — короткие пункты подряд под одним вопросом.
    private static func looksSequentialOptions(_ items: [Item]) -> Bool {
        let shortItems = items.filter { $0.text.count < 35 && !$0.text.contains("?") }.count
        return shortItems == items.count
    }
}

import Foundation

/// Собирает системный промпт и историю диалога для модели.
///
/// Маленькие модели (1.5–3B) плохо держат длинные системные инструкции,
/// зато хорошо выполняют короткое указание в КОНЦЕ последнего сообщения.
/// Поэтому ключевые требования (язык, формат, «только ответ», тест с вариантами)
/// дублируются прямо в последнем вопросе — в историю чата это не попадает.
enum PromptBuilder {
    /// Сколько последних сообщений диалога отдаём модели (дальше движок
    /// дополнительно обрежет историю по токенам, если она не влезает в контекст).
    static let maxHistoryMessages = 10

    /// Базовые правила (сформулированы автором приложения).
    static let corePrompt = """
    Ты — Index AI, офлайн-помощник для школьного обучения. Отвечай только на русском языке.
    Не выдумывай факты. Если не уверен в ответе, прямо скажи «Я не уверен» вместо того, чтобы придумывать ответ.
    Для математических и физических задач сначала выполни вычисления, затем проверь результат.
    Для исторических вопросов внимательно проверяй даты, имена и события.
    Для биологии, химии и географии используй общеизвестные школьные факты.
    Если вопрос содержит недостаточно информации, сообщи об этом.
    Ответ должен быть кратким, понятным ученику и соответствовать школьной программе.
    """

    static func systemPrompt(mode: ChatMode, subject: Subject, settings: AppSettings) -> String {
        var lines: [String] = [corePrompt]
        lines.append("Ученик учится в \(settings.grade.promptDescription).")

        switch settings.answerStyle {
        case .answerOnly:
            lines.append("Давай только итоговый ответ, без пересказа условия и без объяснений.")
        case .short:
            lines.append("Сначала сам ответ, потом 1–3 предложения пояснения.")
        case .detailed:
            lines.append("Сначала ответ, потом объяснение по шагам и пример.")
        }

        if subject != .general {
            lines.append("Предмет: \(subject.title).")
        }

        lines.append("Не переписывай условие задания и варианты ответа — сразу отвечай.")
        lines.append("Используй Markdown: **жирный** для главного, списки для шагов. Формулы — в LaTeX внутри $...$.")

        if mode == .solve {
            lines.append("""
            Режим «Решить задание». Формат:
            **Ответ:** итоговый ответ
            **Решение:** 2–5 коротких шагов с проверкой
            """)
        }
        return lines.joined(separator: "\n")
    }

    static func turns(for conversation: Conversation, settings: AppSettings, modelFileName: String?) -> [ChatTurn] {
        var turns = [ChatTurn(role: .system, content: systemPrompt(mode: conversation.mode, subject: conversation.subject, settings: settings))]

        let usable = conversation.messages.filter { message in
            guard message.kind != .error else { return false }
            let text = message.text.trimmingCharacters(in: .whitespacesAndNewlines)
            guard !text.isEmpty else { return false }
            // Ответы, в которых модель «съехала» на иероглифы, не показываем ей снова —
            // иначе она продолжит в том же духе.
            if message.role == .assistant && containsCJK(text) { return false }
            return true
        }

        for message in usable.suffix(maxHistoryMessages) {
            switch message.role {
            case .user:
                turns.append(ChatTurn(role: .user, content: message.text))
            case .assistant:
                turns.append(ChatTurn(role: .assistant, content: cleanAnswer(message.text)))
            }
        }

        // Короткая инструкция в конце последнего вопроса.
        if let lastUser = turns.lastIndex(where: { $0.role == .user }) {
            let lastMessage = usable.last(where: { $0.role == .user })
            let instruction = finalInstruction(
                for: turns[lastUser].content,
                kind: lastMessage?.kind ?? .normal,
                mode: conversation.mode,
                style: settings.answerStyle,
                previousTask: usable.last(where: { $0.role == .user && $0.kind != .answerOnly && $0.kind != .simpler && $0.kind != .example })?.text
            )
            var content = turns[lastUser].content
            if lastMessage?.kind == .solve {
                content = "Задание:\n\(content)"
            }
            content += "\n\n(\(instruction))"

            // У Qwen3 есть «режим размышлений» — для школьных ответов он только замедляет.
            if let name = modelFileName?.lowercased(), name.contains("qwen3"), !name.contains("2507"), !name.contains("instruct") {
                content += " /no_think"
            }
            turns[lastUser].content = content
        }
        return turns
    }

    /// Один вопрос из пачки: без истории чата, с жёстким кратким форматом.
    static func batchTurns(item: String, mode: ChatMode, subject: Subject, settings: AppSettings,
                           style: AnswerStyle, modelFileName: String?) -> [ChatTurn] {
        var system = systemPrompt(mode: .ask, subject: subject, settings: settings)
        system += "\nТебе дают один вопрос из списка. Ответь только на него."
        var instruction = finalInstruction(for: item, kind: .normal, mode: mode, style: style, previousTask: nil)
        if style == .answerOnly {
            instruction += " Выведи только ответ одной строкой. Если не уверен — напиши «Я не уверен»."
        } else {
            instruction += " Формат: «**Ответ:** …», затем не больше двух коротких предложений пояснения. Если не уверен — напиши «Я не уверен»."
        }
        var content = item + "\n\n(" + instruction + ")"
        if let name = modelFileName?.lowercased(), name.contains("qwen3"), !name.contains("2507"), !name.contains("instruct") {
            content += " /no_think"
        }
        return [ChatTurn(role: .system, content: system), ChatTurn(role: .user, content: content)]
    }

    // MARK: - Инструкция к последнему вопросу

    static func finalInstruction(for text: String, kind: MessageKind, mode: ChatMode, style: AnswerStyle, previousTask: String?) -> String {
        var parts: [String] = []
        let taskText = (kind == .answerOnly || wantsAnswerOnly(text)) ? (previousTask ?? text) : text
        let multipleChoice = looksLikeMultipleChoice(taskText)
        let manyTasks = countNumberedTasks(taskText) >= 2

        if kind == .answerOnly || wantsAnswerOnly(text) || style == .answerOnly {
            if manyTasks {
                parts.append("Выведи ТОЛЬКО ответы списком: «номер задания — ответ», каждый с новой строки. Не переписывай условия и не объясняй")
            } else if multipleChoice {
                parts.append("Выведи ТОЛЬКО правильный вариант: букву или номер и его текст. Не переписывай условие и не объясняй")
            } else {
                parts.append("Выведи ТОЛЬКО ответ одной-двумя строками. Не переписывай условие и не объясняй")
            }
        } else {
            let lower = taskText.lowercased()
            let matching = lower.contains("соответств")
            let blank = isFillInTheBlank(taskText)
            if manyTasks {
                parts.append("В тексте несколько заданий — ответь на каждое по порядку, начиная с его номера. Не переписывай условия")
            }
            if matching {
                parts.append("В задании на соответствие слева — пронумерованные элементы (1, 2, 3…), справа — буквы (А, Б, В…). Порядок строк НЕ означает соответствие: для каждого номера выбери букву по смыслу. Ответ в виде «1 — Б, 2 — А, 3 — В» и по одному предложению, почему")
            }
            if blank {
                parts.append("В задании с пропуском («…», «___») вставь подходящее по смыслу школьное слово или термин и напиши предложение целиком. Не придумывай названия, которых нет в учебнике")
            }
            if multipleChoice && !(matching && !manyTasks) {
                parts.append("В тесте с вариантами сначала напиши «**Ответ:** буква/номер — текст правильного варианта», затем одно-два предложения, почему. Не переписывай вопрос и варианты")
            }
            if parts.isEmpty && (mode == .solve || kind == .solve) {
                parts.append("Начни со строки «**Ответ:** …», потом коротко реши по шагам. Не переписывай условие")
            }
        }
        parts.append("Отвечай на русском языке")
        return parts.joined(separator: ". ") + "."
    }

    /// Пользователь просит только ответ без решения.
    static func wantsAnswerOnly(_ text: String) -> Bool {
        let lower = text.lowercased()
        let phrases = ["только ответ", "одни ответы", "без решения", "без объяснен", "просто ответ",
                       "только правильн", "скинь ответ", "дай ответ", "напиши ответ", "только буквы",
                       "без пояснен", "кратко ответ", "только вариант"]
        return phrases.contains { lower.contains($0) }
    }

    /// Задание «вставьте пропущенное слово».
    static func isFillInTheBlank(_ text: String) -> Bool {
        let lower = text.lowercased()
        if ["пропущ", "вставьте", "вставь ", "впишите", "заполните пропуск", "закончите предложение", "допишите"].contains(where: { lower.contains($0) }) {
            return true
        }
        return text.contains("___") || text.contains("…") || text.range(of: #"\.{3,}|_{2,}"#, options: .regularExpression) != nil
    }

    /// Похоже на тест: минимум два варианта вида «а) …», «Б. …», «1) …», «A) …».
    static func looksLikeMultipleChoice(_ text: String) -> Bool {
        let pattern = #"^\s*([А-Еа-еA-Ea-e]|[1-6])\s*[\)\.]\s*\S"#
        guard let regex = try? NSRegularExpression(pattern: pattern, options: [.anchorsMatchLines]) else { return false }
        let range = NSRange(text.startIndex..., in: text)
        let letterOptions = regex.matches(in: text, range: range).filter { match in
            guard let r = Range(match.range(at: 1), in: text) else { return false }
            return text[r].first?.isLetter == true
        }.count
        if letterOptions >= 2 { return true }
        // Варианты цифрами считаем тестом, только если в тексте есть слова-подсказки.
        let lower = text.lowercased()
        let hints = ["выбер", "вариант", "укажите", "какой из", "какая из", "какое из", "верн", "правильн"]
        return regex.numberOfMatches(in: text, range: range) >= 3 && (text.contains("?") || hints.contains { lower.contains($0) })
    }

    /// Сколько пронумерованных заданий в тексте («1.», «2.», «Задание 3» …).
    static func countNumberedTasks(_ text: String) -> Int {
        // Есть явные «Задание 1», «Задание 2» — считаем только их
        // (пронумерованные строки внутри задания — это элементы, а не задания).
        if let explicit = try? NSRegularExpression(pattern: #"(?m)^\s*(задание|задача|вопрос|№)\s*\d{1,2}"#, options: [.caseInsensitive]) {
            let count = explicit.numberOfMatches(in: text, range: NSRange(text.startIndex..., in: text))
            if count > 0 { return count }
        }
        let pattern = #"(?m)^\s*\d{1,2}\s*[\.\)]\s+\S"#
        guard let regex = try? NSRegularExpression(pattern: pattern) else { return 0 }
        let count = regex.numberOfMatches(in: text, range: NSRange(text.startIndex..., in: text))
        // Варианты одного теста или строки таблицы соответствия — не отдельные задания.
        if looksLikeMultipleChoice(text) || text.lowercased().contains("соответств") { return 0 }
        return count
    }

    static func containsCJK(_ text: String) -> Bool {
        text.unicodeScalars.contains { scalar in
            (0x3000...0x9FFF).contains(scalar.value) || (0xAC00...0xD7AF).contains(scalar.value) || (0xFF00...0xFFEF).contains(scalar.value)
        }
    }

    /// Убирает служебные блоки рассуждений `<think>…</think>` (Qwen3, DeepSeek-R1 и т.п.)
    /// и случайно сгенерированные маркеры шаблона.
    static func cleanAnswer(_ raw: String) -> String {
        var text = raw
        while let start = text.range(of: "<think>") {
            if let end = text.range(of: "</think>", range: start.upperBound..<text.endIndex) {
                text.removeSubrange(start.lowerBound..<end.upperBound)
            } else {
                text.removeSubrange(start.lowerBound..<text.endIndex) // ещё «думает»
            }
        }
        for marker in ["<|im_end|>", "<|im_start|>", "<|eot_id|>", "<end_of_turn>", "</s>"] {
            text = text.replacingOccurrences(of: marker, with: "")
        }
        return text.trimmingCharacters(in: .whitespacesAndNewlines)
    }
}

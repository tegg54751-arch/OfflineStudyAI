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

    static func systemPrompt(mode: ChatMode, subject: Subject, settings: AppSettings) -> String {
        var lines: [String] = []
        lines.append("Ты — «Offline Study AI», школьный помощник по всем предметам.")
        lines.append("Пиши ТОЛЬКО на русском языке. Никогда не используй китайский или другие языки.")
        lines.append("Ученик учится в \(settings.grade.promptDescription).")

        switch settings.answerStyle {
        case .answerOnly:
            lines.append("Давай только итоговый ответ, без пересказа условия и без объяснений.")
        case .short:
            lines.append("Отвечай кратко: сначала сам ответ, потом 2–4 предложения пояснения.")
        case .detailed:
            lines.append("Отвечай подробно: сначала ответ, потом объяснение по шагам и пример.")
        }

        if subject != .general {
            lines.append("Предмет: \(subject.title).")
        }

        lines.append("Никогда не переписывай условие задания и варианты ответа — сразу отвечай.")
        lines.append("Используй Markdown: **жирный** для главного, списки для шагов. Формулы — в LaTeX внутри $...$.")
        lines.append("Если не уверен — честно скажи. Не выдумывай факты.")

        if mode == .solve {
            lines.append("""
            Режим «Решить задание». Формат:
            **Ответ:** итоговый ответ
            **Решение:** 2–5 коротких шагов
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
            if let name = modelFileName?.lowercased(), name.contains("qwen3") {
                content += " /no_think"
            }
            turns[lastUser].content = content
        }
        return turns
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
        } else if multipleChoice {
            parts.append("Это тест с вариантами ответа. Не переписывай вопрос и варианты. Сначала напиши «**Ответ:** буква/номер — текст правильного варианта», затем одно-два предложения, почему")
        } else if mode == .solve || kind == .solve {
            parts.append("Начни со строки «**Ответ:** …», потом коротко реши по шагам. Не переписывай условие")
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
        let pattern = #"(?m)^\s*(задание\s*)?\d{1,2}\s*[\.\)]\s+\S"#
        guard let regex = try? NSRegularExpression(pattern: pattern, options: [.caseInsensitive]) else { return 0 }
        let count = regex.numberOfMatches(in: text, range: NSRange(text.startIndex..., in: text))
        // Если это варианты одного теста, а не отдельные задания — не считаем.
        return looksLikeMultipleChoice(text) && count <= 6 && !text.lowercased().contains("задание") ? 0 : count
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

import Foundation

/// Собирает системный промпт и историю диалога для модели.
enum PromptBuilder {
    /// Сколько последних сообщений диалога отдаём модели (дальше движок
    /// дополнительно обрежет историю по токенам, если она не влезает в контекст).
    static let maxHistoryMessages = 12

    static func systemPrompt(mode: ChatMode, subject: Subject, settings: AppSettings) -> String {
        var lines: [String] = []
        lines.append("Ты — «Offline Study AI», доброжелательный школьный помощник по всем предметам. Всегда отвечай на русском языке.")
        lines.append("Ученик учится в \(settings.grade.promptDescription).")

        switch settings.answerStyle {
        case .short:
            lines.append("Отвечай кратко и по делу: 3–6 предложений или короткий список. Главное — суть и понятность.")
        case .detailed:
            lines.append("Отвечай подробно: объясни суть, разбери по шагам, приведи пример. Без воды и повторов.")
        }

        if subject != .general {
            lines.append("Тема вопроса, скорее всего, относится к предмету «\(subject.title)».")
        }

        lines.append("Оформляй ответ в Markdown: **жирный** для ключевых терминов, списки для перечислений и шагов.")
        lines.append("Формулы записывай в LaTeX внутри знаков $...$, например $x^2 + 2x - 3 = 0$ или $\\frac{a}{b}$. Химические формулы — как $\\ce{H2SO4}$.")
        lines.append("Если не уверен в факте, дате или числе — честно скажи об этом. Ничего не выдумывай.")

        if mode == .solve {
            lines.append("""
            Сейчас режим «Решить задание». Строго соблюдай формат ответа:
            **Предмет:** название предмета
            **Что дано и что найти:** кратко
            **Решение:**
            1. первый шаг с пояснением
            2. следующий шаг…
            **Ответ:** итоговый ответ
            Тщательно проверяй каждое вычисление.
            """)
        }
        return lines.joined(separator: "\n")
    }

    static func turns(for conversation: Conversation, settings: AppSettings, modelFileName: String?) -> [ChatTurn] {
        var turns = [ChatTurn(role: .system, content: systemPrompt(mode: conversation.mode, subject: conversation.subject, settings: settings))]

        let usable = conversation.messages.filter { message in
            message.kind != .error && !message.text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
        }
        for message in usable.suffix(maxHistoryMessages) {
            switch message.role {
            case .user:
                let content = message.kind == .solve ? "Реши задание:\n\(message.text)" : message.text
                turns.append(ChatTurn(role: .user, content: content))
            case .assistant:
                turns.append(ChatTurn(role: .assistant, content: cleanAnswer(message.text)))
            }
        }

        // У Qwen3 есть «режим размышлений» — для школьных ответов он только замедляет.
        if let name = modelFileName?.lowercased(), name.contains("qwen3"),
           let lastUser = turns.lastIndex(where: { $0.role == .user }) {
            turns[lastUser].content += " /no_think"
        }
        return turns
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

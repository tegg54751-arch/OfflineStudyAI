import Foundation

/// Режим чата: обычный вопрос или пошаговое решение задания.
enum ChatMode: String, Codable, CaseIterable, Identifiable {
    case ask
    case solve

    var id: String { rawValue }

    var title: String {
        switch self {
        case .ask: "Вопрос"
        case .solve: "Решить задание"
        }
    }

    var icon: String {
        switch self {
        case .ask: "bubble.left.and.text.bubble.right"
        case .solve: "checklist"
        }
    }
}

enum MessageRole: String, Codable {
    case user
    case assistant
}

/// Тип сообщения — нужен, чтобы по-разному оформлять служебные запросы
/// («Объясни проще», «Дай пример») и ответы-ошибки.
enum MessageKind: String, Codable {
    case normal
    case solve
    case simpler
    case example
    case answerOnly
    case error
}

struct ChatMessage: Identifiable, Codable, Hashable {
    var id = UUID()
    var role: MessageRole
    var text: String
    var kind: MessageKind = .normal
    var date = Date()
    var isFavorite = false
    /// Статистика генерации (только для ответов AI).
    var tokensPerSecond: Double?
}

struct Conversation: Identifiable, Codable, Hashable {
    var id = UUID()
    var title: String = "Новый вопрос"
    var subject: Subject = .general
    var mode: ChatMode = .ask
    var createdAt = Date()
    var updatedAt = Date()
    var messages: [ChatMessage] = []

    /// Первый вопрос пользователя.
    var firstQuestion: String {
        messages.first(where: { $0.role == .user })?.text ?? title
    }

    /// Первый содержательный ответ AI.
    var firstAnswer: String {
        messages.first(where: { $0.role == .assistant && $0.kind != .error && !$0.text.isEmpty })?.text ?? ""
    }

    var isEmpty: Bool { messages.isEmpty }
}

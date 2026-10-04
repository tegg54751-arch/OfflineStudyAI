import Foundation

// MARK: - Абстракция AI-движка
//
// Всё приложение общается с моделью только через протокол `AIService`.
// Чтобы заменить llama.cpp на другой движок (MLX, Core ML, Apple Foundation
// Models и т.д.), достаточно написать новый класс, реализующий этот протокол,
// и передать его в `AIController` в `OfflineStudyAIApp.swift`.

struct ChatTurn: Sendable, Hashable {
    enum Role: String, Sendable {
        case system, user, assistant
    }
    var role: Role
    var content: String
}

struct GenerationParams: Sendable {
    var maxTokens: Int = 600
    var temperature: Double = 0.3
    var topK: Int32 = 40
    var topP: Float = 0.9
    var minP: Float = 0.05
    var repeatPenalty: Float = 1.1
}

struct GenerationStats: Sendable, Hashable {
    var promptTokens: Int
    var reusedPromptTokens: Int
    var generatedTokens: Int
    var promptSeconds: Double
    var generationSeconds: Double
    var stoppedByLimit: Bool

    var tokensPerSecond: Double {
        generationSeconds > 0 ? Double(generatedTokens) / generationSeconds : 0
    }
}

struct LoadedModelInfo: Sendable, Hashable {
    var fileName: String
    var description: String
    var fileSizeBytes: UInt64
    /// Сколько байт занимают веса модели (по данным llama.cpp).
    var weightsBytes: UInt64
    var parameterCount: UInt64
    var contextSize: Int
    var trainContextSize: Int
    var hasChatTemplate: Bool
    var loadSeconds: Double
}

enum AIStreamEvent: Sendable {
    case token(String)
    case finished(GenerationStats)
}

enum AIError: LocalizedError, Equatable {
    case noModelInstalled
    case modelNotLoaded
    case invalidModelFile
    case modelLoadFailed(String)
    case contextInitFailed
    case insufficientMemory(needed: UInt64, available: UInt64)
    case promptTooLong
    case decodeFailed(Int32)
    case busy

    var errorDescription: String? {
        switch self {
        case .noModelInstalled:
            return "Для работы AI необходимо установить локальную модель."
        case .modelNotLoaded:
            return "Модель не загружена в память."
        case .invalidModelFile:
            return "Файл не похож на модель в формате GGUF."
        case .modelLoadFailed(let name):
            return "Не удалось загрузить модель «\(name)». Возможно, файл повреждён или архитектура не поддерживается этой версией llama.cpp."
        case .contextInitFailed:
            return "Не удалось создать контекст модели. Попробуйте уменьшить размер контекста в настройках."
        case .insufficientMemory(let needed, let available):
            return "Недостаточно оперативной памяти: нужно примерно \(Format.bytes(needed)), доступно \(Format.bytes(available)). Закройте другие приложения, уменьшите контекст или используйте модель поменьше (например, 1.5B)."
        case .promptTooLong:
            return "Вопрос слишком длинный для контекста модели. Сократите текст или увеличьте контекст в настройках."
        case .decodeFailed(let code):
            return "Ошибка вычисления модели (код \(code)). Возможно, не хватает памяти — попробуйте начать новый чат."
        case .busy:
            return "Модель занята другой задачей."
        }
    }
}

protocol AIService: AnyObject {
    /// Название движка для экрана «AI-модель».
    var backendName: String { get }

    /// Загружает модель в память. Повторный вызов заменяет текущую модель.
    func load(modelAt url: URL, contextSize: Int) async throws -> LoadedModelInfo

    /// Освобождает всю память, занятую моделью и контекстом.
    func unload() async

    /// Потоковая генерация ответа. Отмена задачи-потребителя останавливает генерацию.
    func generate(turns: [ChatTurn], params: GenerationParams) -> AsyncThrowingStream<AIStreamEvent, Error>
}

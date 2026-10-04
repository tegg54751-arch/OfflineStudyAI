import SwiftUI
import Observation

/// Состояние ЕДИНСТВЕННОГО универсального чата (для всех предметов).
/// Живёт на уровне приложения, поэтому генерация не прерывается
/// при переключении вкладок.
@MainActor
@Observable
final class ChatSession {
    private(set) var conversation = Conversation()
    var input = ""
    private(set) var isGenerating = false
    private(set) var streamingMessageID: UUID?
    var errorMessage: String?

    private let ai: AIController
    private let history: HistoryStore
    private let settings: AppSettings
    private let models: ModelManager
    @ObservationIgnored private var generationTask: Task<Void, Never>?
    @ObservationIgnored private var generationToken = UUID()
    @ObservationIgnored private var rawBuffer = ""

    init(ai: AIController, history: HistoryStore, settings: AppSettings, models: ModelManager) {
        self.ai = ai
        self.history = history
        self.settings = settings
        self.models = models
    }

    var mode: ChatMode {
        get { conversation.mode }
        set { conversation.mode = newValue }
    }

    var lastAssistantMessageID: UUID? {
        conversation.messages.last(where: { $0.role == .assistant })?.id
    }

    // MARK: - Управление диалогом

    func newChat(mode: ChatMode = .ask, prefill: String = "") {
        finalizeCurrentGenerationIfNeeded()
        conversation = Conversation(mode: mode)
        input = prefill
        errorMessage = nil
    }

    func open(_ conversation: Conversation) {
        finalizeCurrentGenerationIfNeeded()
        self.conversation = conversation
        input = ""
        errorMessage = nil
    }

    func send() {
        let text = input.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !text.isEmpty, !isGenerating else { return }
        input = ""
        submit(text, kind: conversation.mode == .solve ? .solve : .normal)
    }

    func solve(_ taskText: String) {
        guard !isGenerating else { return }
        if !conversation.isEmpty { newChat(mode: .solve) }
        conversation.mode = .solve
        submit(taskText, kind: .solve)
    }

    func explainSimpler() {
        submit("Объясни проще, как будто мне 10 лет: простыми словами и со сравнением из жизни.", kind: .simpler)
    }

    func giveExample() {
        submit("Дай конкретный пример по этой теме — из жизни или задачу с решением.", kind: .example)
    }

    func answerOnly() {
        submit("Напиши только ответ, без условия и объяснений.", kind: .answerOnly)
    }

    func stop() {
        generationTask?.cancel()
    }

    func toggleFavorite(_ messageID: UUID) {
        guard let index = conversation.messages.firstIndex(where: { $0.id == messageID }) else { return }
        conversation.messages[index].isFavorite.toggle()
        history.upsert(conversation)
    }

    /// Вызывается, когда сообщение удалили/изменили в истории.
    func syncFromHistory() {
        if let stored = history.conversation(id: conversation.id), !isGenerating {
            conversation = stored
        } else if history.conversation(id: conversation.id) == nil, !isGenerating, !conversation.isEmpty {
            conversation = Conversation(mode: conversation.mode)
        }
    }

    // MARK: - Генерация

    /// Латиница нужна в английском, информатике и формулах — там её не штрафуем.
    static func shouldDiscourageLatin(subject: Subject, text: String) -> Bool {
        let latinFriendly: Set<Subject> = [.english, .informatics, .math, .physics, .chemistry]
        if latinFriendly.contains(subject) { return false }
        let latinLetters = text.unicodeScalars.filter { ($0.value >= 0x41 && $0.value <= 0x5A) || ($0.value >= 0x61 && $0.value <= 0x7A) }.count
        return latinLetters < 4
    }

    private func submit(_ text: String, kind: MessageKind) {
        guard !isGenerating else { return }
        errorMessage = nil

        if conversation.isEmpty {
            conversation.subject = SubjectDetector.detect(text)
            conversation.title = String(text.prefix(90))
            conversation.createdAt = Date()
        } else if conversation.subject == .general, kind == .normal || kind == .solve {
            conversation.subject = SubjectDetector.detect(text)
        }

        conversation.messages.append(ChatMessage(role: .user, text: text, kind: kind))
        let turns = PromptBuilder.turns(for: conversation, settings: settings, modelFileName: models.activeModel?.fileName)

        let reply = ChatMessage(role: .assistant, text: "", kind: kind == .error ? .normal : kind)
        conversation.messages.append(reply)
        conversation.updatedAt = Date()
        history.upsert(conversation)

        streamingMessageID = reply.id
        isGenerating = true
        rawBuffer = ""

        let token = UUID()
        generationToken = token
        var params = GenerationParams(maxTokens: settings.maxAnswerTokens, temperature: settings.temperature)
        params.discourageLatin = Self.shouldDiscourageLatin(subject: conversation.subject, text: text)

        // Много вопросов в одном сообщении — решаем каждый отдельно, по очереди.
        // Так маленькая модель отвечает точнее и не теряет вопросы в длинном списке.
        if kind == .normal || kind == .solve {
            let items = QuestionSplitter.split(text)
            if items.count >= 2 {
                runBatch(items: items, replyID: reply.id, token: token, baseParams: params)
                return
            }
        }

        generationTask = Task { [weak self] in
            guard let self else { return }
            var failure: Error?
            var stats: GenerationStats?
            do {
                for try await event in self.ai.generate(turns: turns, params: params) {
                    guard self.generationToken == token else { return }
                    switch event {
                    case .token(let piece):
                        self.rawBuffer += piece
                        self.updateMessage(id: reply.id, text: PromptBuilder.cleanAnswer(self.rawBuffer))
                    case .finished(let s):
                        stats = s
                    }
                }
            } catch is CancellationError {
                // остановлено пользователем
            } catch {
                failure = error
            }
            guard self.generationToken == token else { return }
            self.finishGeneration(messageID: reply.id, stats: stats, error: failure)
        }
    }

    private func runBatch(items: [QuestionSplitter.Item], replyID: UUID, token: UUID, baseParams: GenerationParams) {
        let mode = conversation.mode
        let subject = conversation.subject
        let style = settings.answerStyle
        let modelName = models.activeModel?.fileName

        generationTask = Task { [weak self] in
            guard let self else { return }
            var sections: [String] = []
            var failure: Error?
            var lastStats: GenerationStats?
            var totalTokens = 0
            var totalSeconds = 0.0

            for (index, item) in items.enumerated() {
                if Task.isCancelled || self.generationToken != token { break }
                let header = "**\(item.label).** _\(item.preview)_"
                let itemSubject = subject == .general ? SubjectDetector.detect(item.text) : subject
                let turns = PromptBuilder.batchTurns(item: item.text, mode: mode, subject: itemSubject,
                                                     settings: self.settings, style: style, modelFileName: modelName)
                var params = baseParams
                params.maxTokens = min(baseParams.maxTokens, style == .answerOnly ? 120 : 350)
                params.discourageLatin = Self.shouldDiscourageLatin(subject: itemSubject, text: item.text)

                var partial = ""
                let progress = "\n\n_Решаю \(index + 1) из \(items.count)…_"
                do {
                    for try await event in self.ai.generate(turns: turns, params: params) {
                        guard self.generationToken == token else { return }
                        switch event {
                        case .token(let piece):
                            partial += piece
                            let current = header + "\n" + PromptBuilder.cleanAnswer(partial)
                            self.updateMessage(id: replyID, text: (sections + [current]).joined(separator: "\n\n") + progress)
                        case .finished(let s):
                            lastStats = s
                            totalTokens += s.generatedTokens
                            totalSeconds += s.generationSeconds
                        }
                    }
                } catch is CancellationError {
                    break
                } catch {
                    failure = error
                    break
                }
                let answer = PromptBuilder.cleanAnswer(partial)
                sections.append(header + "\n" + (answer.isEmpty ? "_Нет ответа._" : answer))
                self.rawBuffer = sections.joined(separator: "\n\n")
                self.updateMessage(id: replyID, text: self.rawBuffer)
            }

            guard self.generationToken == token else { return }
            if sections.count < items.count && failure == nil && !sections.isEmpty {
                self.rawBuffer += "\n\n_Остановлено: решено \(sections.count) из \(items.count)._"
            }
            var stats = lastStats
            if totalSeconds > 0, var s = stats {
                s.generatedTokens = totalTokens
                s.generationSeconds = totalSeconds
                s.stoppedByLimit = false
                stats = s
            }
            self.finishGeneration(messageID: replyID, stats: stats, error: failure)
        }
    }

    private func updateMessage(id: UUID, text: String) {
        guard let index = conversation.messages.lastIndex(where: { $0.id == id }) else { return }
        conversation.messages[index].text = text
    }

    private func finishGeneration(messageID: UUID, stats: GenerationStats?, error: Error?) {
        if let index = conversation.messages.lastIndex(where: { $0.id == messageID }) {
            var message = conversation.messages[index]
            message.text = PromptBuilder.cleanAnswer(rawBuffer)
            message.tokensPerSecond = stats?.tokensPerSecond
            if let error {
                let description = (error as? LocalizedError)?.errorDescription ?? error.localizedDescription
                errorMessage = description
                if message.text.isEmpty {
                    message.text = description
                    message.kind = .error
                }
            } else if message.text.isEmpty {
                message.text = stats == nil ? "Генерация остановлена." : "Модель не дала ответа. Попробуйте переформулировать вопрос."
                message.kind = .error
            } else if stats?.stoppedByLimit == true {
                message.text += "\n\n_…ответ обрезан по лимиту длины. Напишите «продолжи», чтобы получить окончание._"
            }
            conversation.messages[index] = message
        }
        conversation.updatedAt = Date()
        history.upsert(conversation)
        isGenerating = false
        streamingMessageID = nil
        generationTask = nil
    }

    /// Если пользователь переключает диалог во время генерации — сохраняем частичный ответ.
    private func finalizeCurrentGenerationIfNeeded() {
        guard isGenerating, let id = streamingMessageID else { return }
        generationToken = UUID() // старая задача больше не трогает состояние
        generationTask?.cancel()
        finishGeneration(messageID: id, stats: nil, error: nil)
    }

    /// Приложение уходит в фон: GPU в фоне недоступен, поэтому останавливаемся корректно.
    func handleBackground() {
        if isGenerating { stop() }
        history.saveNow()
    }
}

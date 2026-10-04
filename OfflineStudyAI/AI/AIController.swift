import SwiftUI
import Observation

enum ModelState: Equatable {
    case notInstalled
    case notLoaded
    case loading
    case ready
    case generating
    case failed(String)

    var title: String {
        switch self {
        case .notInstalled: "Модель не установлена"
        case .notLoaded: "Не загружена в память"
        case .loading: "Загрузка…"
        case .ready: "Готова к работе"
        case .generating: "Генерирует ответ"
        case .failed: "Ошибка"
        }
    }

    var color: Color {
        switch self {
        case .notInstalled, .failed: .red
        case .notLoaded: .orange
        case .loading, .generating: .blue
        case .ready: .green
        }
    }
}

struct OfflineTestResult {
    var success: Bool
    var answer: String
    var networkAvailableDuringTest: Bool
    var networkDescription: String
    var loadSeconds: Double?
    var stats: GenerationStats?
    var error: String?
    var date = Date()
}

/// Связующее звено между UI и движком: состояние модели, автозагрузка,
/// освобождение памяти, обработка нехватки RAM и фонового режима.
@MainActor
@Observable
final class AIController {
    private(set) var state: ModelState = .notLoaded
    private(set) var loadedInfo: LoadedModelInfo?
    private(set) var lastStats: GenerationStats?
    private(set) var lastMemoryWarning: Date?

    private let service: AIService
    private let models: ModelManager
    private let settings: AppSettings
    @ObservationIgnored private var loadedURL: URL?
    @ObservationIgnored private var loadedContextSize = 0
    @ObservationIgnored private var loadTask: Task<LoadedModelInfo, Error>?
    @ObservationIgnored private var activeGenerations = 0

    var backendName: String { service.backendName }
    var isReady: Bool { state == .ready || state == .generating }
    var isGenerating: Bool { state == .generating }

    init(service: AIService, models: ModelManager, settings: AppSettings) {
        self.service = service
        self.models = models
        self.settings = settings
        refreshState()
    }

    func refreshState() {
        guard state != .loading, state != .generating else { return }
        if !models.hasModel {
            state = .notInstalled
        } else if loadedInfo == nil {
            if case .failed = state { return }
            state = .notLoaded
        } else {
            state = .ready
        }
    }

    // MARK: - Загрузка

    func preloadIfNeeded() async {
        guard settings.preloadOnLaunch, models.hasModel, loadedInfo == nil else {
            refreshState()
            return
        }
        _ = try? await ensureLoaded()
    }

    /// Гарантирует, что активная модель загружена с текущим размером контекста.
    @discardableResult
    func ensureLoaded() async throws -> LoadedModelInfo {
        models.refresh()
        guard let model = models.activeModel else {
            state = .notInstalled
            throw AIError.noModelInstalled
        }

        if let info = loadedInfo, loadedURL == model.url, loadedContextSize == settings.contextSize {
            return info
        }
        if let loadTask {
            return try await loadTask.value
        }

        // Проверка памяти до загрузки, чтобы iOS не завершила приложение.
        let available = DeviceInfo.availableAppMemory
        let needed = DeviceInfo.estimatedMemory(forModelFileSize: model.sizeBytes, contextSize: settings.contextSize)
        if available > 0 && needed > available + DeviceInfo.appFootprint / 4 {
            if loadedInfo != nil { await unload() }
            let recheck = DeviceInfo.availableAppMemory
            if recheck > 0 && needed > recheck {
                let error = AIError.insufficientMemory(needed: needed, available: recheck)
                state = .failed(error.errorDescription ?? "")
                throw error
            }
        }

        state = .loading
        let service = self.service
        let url = model.url
        let contextSize = settings.contextSize
        let task = Task { try await service.load(modelAt: url, contextSize: contextSize) }
        loadTask = task
        defer { loadTask = nil }

        do {
            let info = try await task.value
            loadedInfo = info
            loadedURL = url
            loadedContextSize = contextSize
            state = .ready
            return info
        } catch {
            loadedInfo = nil
            loadedURL = nil
            state = .failed((error as? LocalizedError)?.errorDescription ?? error.localizedDescription)
            throw error
        }
    }

    func unload() async {
        guard activeGenerations == 0 else { return }
        await service.unload()
        loadedInfo = nil
        loadedURL = nil
        loadedContextSize = 0
        state = models.hasModel ? .notLoaded : .notInstalled
    }

    // MARK: - Генерация

    func generate(turns: [ChatTurn], params: GenerationParams) -> AsyncThrowingStream<AIStreamEvent, Error> {
        AsyncThrowingStream { continuation in
            let task = Task { @MainActor in
                self.activeGenerations += 1
                defer {
                    self.activeGenerations -= 1
                    if self.state == .generating { self.state = .ready }
                }
                do {
                    try await self.ensureLoaded()
                    self.state = .generating
                    for try await event in self.service.generate(turns: turns, params: params) {
                        if case .finished(let stats) = event { self.lastStats = stats }
                        continuation.yield(event)
                    }
                    continuation.finish()
                } catch {
                    continuation.finish(throwing: error)
                }
            }
            continuation.onTermination = { _ in task.cancel() }
        }
    }

    // MARK: - Тест офлайн-работы

    func runOfflineTest(network: NetworkMonitor) async -> OfflineTestResult {
        let networkWasAvailable = network.isConnected
        let networkDescription = network.statusDescription
        let wasLoaded = loadedInfo != nil
        do {
            let info = try await ensureLoaded()
            let turns = [
                ChatTurn(role: .system, content: "Ты помощник. Отвечай очень коротко, на русском языке."),
                ChatTurn(role: .user, content: "Сколько будет 2 + 2? Ответь одним числом.")
            ]
            var answer = ""
            var stats: GenerationStats?
            for try await event in generate(turns: turns, params: GenerationParams(maxTokens: 24, temperature: 0)) {
                switch event {
                case .token(let piece): answer += piece
                case .finished(let s): stats = s
                }
            }
            answer = PromptBuilder.cleanAnswer(answer)
            return OfflineTestResult(
                success: !answer.isEmpty,
                answer: answer,
                networkAvailableDuringTest: networkWasAvailable,
                networkDescription: networkDescription,
                loadSeconds: wasLoaded ? nil : info.loadSeconds,
                stats: stats,
                error: answer.isEmpty ? "Модель вернула пустой ответ." : nil
            )
        } catch {
            return OfflineTestResult(
                success: false,
                answer: "",
                networkAvailableDuringTest: networkWasAvailable,
                networkDescription: networkDescription,
                loadSeconds: nil,
                stats: nil,
                error: (error as? LocalizedError)?.errorDescription ?? error.localizedDescription
            )
        }
    }

    // MARK: - Память и жизненный цикл

    /// iOS сообщает о нехватке памяти — освобождаем модель, если она не занята.
    func handleMemoryWarning() {
        lastMemoryWarning = Date()
        guard activeGenerations == 0 else { return }
        Task { await unload() }
    }

    /// В фоне iOS запрещает работу GPU (Metal), поэтому генерация
    /// останавливается, а модель по желанию выгружается из памяти.
    func scenePhaseChanged(_ phase: ScenePhase) {
        switch phase {
        case .background:
            if settings.unloadInBackground {
                Task {
                    // даём отменённой генерации корректно завершиться
                    var attempts = 0
                    repeat {
                        try? await Task.sleep(for: .milliseconds(200))
                        attempts += 1
                    } while activeGenerations > 0 && attempts < 15
                    await unload()
                }
            }
        case .active:
            refreshState()
            // Вернулись в приложение, а модель выгружена (iOS освободил память
            // или включена выгрузка в фоне) — загружаем сразу, не дожидаясь вопроса.
            Task { await preloadIfNeeded() }
        default:
            break
        }
    }
}

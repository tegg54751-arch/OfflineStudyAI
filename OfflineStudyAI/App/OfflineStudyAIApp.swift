import SwiftUI
import UIKit

@main
@MainActor
struct OfflineStudyAIApp: App {
    @State private var settings: AppSettings
    @State private var models: ModelManager
    @State private var history: HistoryStore
    @State private var ai: AIController
    @State private var session: ChatSession
    @State private var router = AppRouter()
    @State private var network = NetworkMonitor()

    @Environment(\.scenePhase) private var scenePhase

    init() {
        let settings = AppSettings()
        let models = ModelManager()
        let history = HistoryStore()
        // ← Здесь выбирается AI-движок. Чтобы заменить llama.cpp,
        //   передайте другую реализацию протокола AIService.
        let ai = AIController(service: LlamaAIService(), models: models, settings: settings)
        let session = ChatSession(ai: ai, history: history, settings: settings, models: models)

        _settings = State(initialValue: settings)
        _models = State(initialValue: models)
        _history = State(initialValue: history)
        _ai = State(initialValue: ai)
        _session = State(initialValue: session)
    }

    var body: some Scene {
        WindowGroup {
            RootView()
                .environment(settings)
                .environment(models)
                .environment(history)
                .environment(ai)
                .environment(session)
                .environment(router)
                .environment(network)
                .preferredColorScheme(settings.theme.colorScheme)
                .dynamicTypeSize(settings.textSize.range)
                .task {
                    await ai.preloadIfNeeded()
                }
                .onReceive(NotificationCenter.default.publisher(for: UIApplication.didReceiveMemoryWarningNotification)) { _ in
                    ai.handleMemoryWarning()
                }
        }
        .onChange(of: scenePhase) { _, phase in
            if phase == .background {
                session.handleBackground()
            }
            ai.scenePhaseChanged(phase)
        }
    }
}

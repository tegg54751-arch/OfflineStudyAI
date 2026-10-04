import SwiftUI

/// Нижняя навигация: Главная · Спросить (единый чат) · История · Настройки.
@MainActor
struct RootView: View {
    @Environment(AppRouter.self) private var router
    @Environment(AIController.self) private var ai

    var body: some View {
        @Bindable var router = router

        TabView(selection: $router.tab) {
            HomeView()
                .tabItem { Label("Главная", systemImage: "house.fill") }
                .tag(AppTab.home)

            ChatView()
                .tabItem { Label("Спросить", systemImage: "sparkles") }
                .tag(AppTab.chat)

            HistoryView()
                .tabItem { Label("История", systemImage: "clock.arrow.circlepath") }
                .tag(AppTab.history)

            SettingsView()
                .tabItem { Label("Настройки", systemImage: "gearshape.fill") }
                .tag(AppTab.settings)
        }
        .sheet(isPresented: $router.showModelScreen) {
            NavigationStack {
                ModelView()
                    .toolbar {
                        ToolbarItem(placement: .confirmationAction) {
                            Button("Готово") { router.showModelScreen = false }
                        }
                    }
            }
        }
    }
}

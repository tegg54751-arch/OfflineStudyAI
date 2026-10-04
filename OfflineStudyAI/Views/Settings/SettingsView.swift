import SwiftUI

@MainActor
struct SettingsView: View {
    @Environment(AppSettings.self) private var settings
    @Environment(HistoryStore.self) private var history
    @Environment(ChatSession.self) private var session
    @Environment(AIController.self) private var ai
    @Environment(ModelManager.self) private var models

    @State private var confirmClear = false

    var body: some View {
        @Bindable var settings = settings

        NavigationStack {
            Form {
                Section("Оформление") {
                    Picker("Тема", selection: $settings.theme) {
                        ForEach(AppTheme.allCases) { Text($0.title).tag($0) }
                    }
                    Picker("Размер текста", selection: $settings.textSize) {
                        ForEach(TextSizeOption.allCases) { Text($0.title).tag($0) }
                    }
                }

                Section {
                    Picker("Стиль ответа", selection: $settings.answerStyle) {
                        ForEach(AnswerStyle.allCases) { Text($0.title).tag($0) }
                    }
                    .pickerStyle(.segmented)
                    .listRowBackground(Color.clear)

                    Picker("Уровень", selection: $settings.grade) {
                        ForEach(GradeLevel.allCases) { Text($0.title).tag($0) }
                    }
                } header: {
                    Text("Ответы AI")
                } footer: {
                    Text("Уровень влияет на сложность объяснений: от простых слов до терминов уровня ЕГЭ.")
                }

                Section {
                    Picker("Контекст модели", selection: $settings.contextSize) {
                        Text("1024 токена — экономно").tag(1024)
                        Text("2048 токенов — оптимально").tag(2048)
                        Text("4096 токенов — длинные диалоги").tag(4096)
                    }
                    Picker("Длина ответа", selection: $settings.maxAnswerTokens) {
                        Text("Короткая (300)").tag(300)
                        Text("Средняя (600)").tag(600)
                        Text("Длинная (1000)").tag(1000)
                    }
                    VStack(alignment: .leading) {
                        HStack {
                            Text("Креативность")
                            Spacer()
                            Text(String(format: "%.1f", settings.temperature))
                                .foregroundStyle(.secondary)
                                .monospacedDigit()
                        }
                        Slider(value: $settings.temperature, in: 0...1, step: 0.1)
                    }
                    Toggle("Загружать модель при запуске", isOn: $settings.preloadOnLaunch)
                    Toggle("Выгружать модель в фоне", isOn: $settings.unloadInBackground)
                } header: {
                    Text("Производительность")
                } footer: {
                    Text("Больший контекст помнит длинный диалог, но требует больше оперативной памяти. Для учёбы лучше низкая креативность — ответы точнее. Изменение контекста применится при следующем запросе.")
                }

                Section("AI-модель") {
                    NavigationLink {
                        ModelView()
                    } label: {
                        HStack {
                            Label("Информация о модели", systemImage: "cpu")
                            Spacer()
                            Text(ai.state.title)
                                .font(.footnote)
                                .foregroundStyle(ai.state.color)
                        }
                    }
                    NavigationLink {
                        OfflineModeView()
                    } label: {
                        Label("Офлайн-режим и тест", systemImage: "airplane")
                    }
                }

                Section {
                    Button(role: .destructive) {
                        confirmClear = true
                    } label: {
                        Label("Очистить историю", systemImage: "trash")
                    }
                    .disabled(history.conversations.isEmpty)
                } header: {
                    Text("Данные")
                } footer: {
                    Text("Вопросов в истории: \(history.conversations.count). Избранное тоже будет удалено.")
                }

                Section("Приватность") {
                    Label("Вопросы, ответы и фото обрабатываются только на этом iPhone.", systemImage: "lock.shield")
                    Label("Нет серверов, аккаунтов, API-ключей и аналитики.", systemImage: "network.slash")
                }
                .font(.footnote)

                Section {
                    InfoRow(title: "Версия", value: Bundle.main.appVersion)
                    InfoRow(title: "Движок", value: ai.backendName)
                }
            }
            .scrollContentBackground(.hidden)
            .background(AppBackground())
            .navigationTitle("Настройки")
            .confirmationDialog("Удалить всю историю и избранное?", isPresented: $confirmClear, titleVisibility: .visible) {
                Button("Удалить всё", role: .destructive) {
                    history.clearAll()
                    session.syncFromHistory()
                }
                Button("Отмена", role: .cancel) {}
            }
        }
    }
}

extension Bundle {
    var appVersion: String {
        let version = infoDictionary?["CFBundleShortVersionString"] as? String ?? "1.0"
        let build = infoDictionary?["CFBundleVersion"] as? String ?? "1"
        return "\(version) (\(build))"
    }
}

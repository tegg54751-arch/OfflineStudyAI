import SwiftUI

/// Страница «Офлайн-режим»: статус и реальный локальный тест генерации.
@MainActor
struct OfflineModeView: View {
    @Environment(ModelManager.self) private var models
    @Environment(AIController.self) private var ai
    @Environment(NetworkMonitor.self) private var network

    @State private var isTesting = false
    @State private var result: OfflineTestResult?

    var body: some View {
        ScrollView {
            VStack(spacing: 16) {
                VStack(spacing: 16) {
                    StatusRow(ok: models.hasModel,
                              title: models.hasModel ? "AI-модель установлена" : "AI-модель не установлена",
                              subtitle: models.activeModel.map { "\($0.fileName) · \(Format.bytes($0.sizeBytes))" }
                                ?? "Установите модель в разделе «AI-модель»")
                    Divider()
                    StatusRow(ok: true, title: "Интернет не требуется",
                              subtitle: "Ответы генерирует модель на этом iPhone")
                    Divider()
                    StatusRow(ok: true, title: "Wi-Fi не требуется")
                    Divider()
                    StatusRow(ok: true, title: "Мобильная сеть не требуется")
                    Divider()
                    StatusRow(ok: true, title: "Распознавание фото — на устройстве",
                              subtitle: "Apple Vision, без отправки изображений")
                }
                .glassCard()

                HStack(spacing: 12) {
                    Image(systemName: network.isConnected ? "wifi" : "airplane")
                        .font(.title2)
                        .foregroundStyle(network.isConnected ? Color.blue : Color.green)
                        .frame(width: 44, height: 44)
                        .background(.thinMaterial, in: Circle())
                    VStack(alignment: .leading, spacing: 2) {
                        Text("Сеть сейчас").font(.caption).foregroundStyle(.secondary)
                        Text(network.statusDescription).font(.subheadline.weight(.semibold))
                    }
                    Spacer()
                }
                .glassCard(cornerRadius: 22, padding: 14)

                Button {
                    Task { await runTest() }
                } label: {
                    HStack {
                        if isTesting { ProgressView().tint(.white) }
                        Text(isTesting ? "Проверяю…" : "Проверить офлайн-работу")
                    }
                }
                .buttonStyle(PrimaryButtonStyle())
                .disabled(isTesting || !models.hasModel || ai.isGenerating)

                if network.isConnected {
                    Label("Для честной проверки включите авиарежим в Пункте управления, затем нажмите кнопку.", systemImage: "airplane")
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                        .frame(maxWidth: .infinity, alignment: .leading)
                }

                if let result {
                    resultCard(result)
                        .transition(.move(edge: .bottom).combined(with: .opacity))
                }

                explanationCard
            }
            .padding()
            .animation(.spring, value: result?.date)
        }
        .background(AppBackground())
        .navigationTitle("Офлайн-режим")
        .navigationBarTitleDisplayMode(.inline)
    }

    private func runTest() async {
        isTesting = true
        result = await ai.runOfflineTest(network: network)
        isTesting = false
    }

    private func resultCard(_ result: OfflineTestResult) -> some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack(spacing: 10) {
                Image(systemName: result.success ? "checkmark.seal.fill" : "xmark.octagon.fill")
                    .font(.largeTitle)
                    .foregroundStyle(result.success ? Color.green : Color.red)
                    .symbolEffect(.bounce, value: result.date)
                VStack(alignment: .leading) {
                    Text(result.success ? "Локальный AI работает" : "Тест не пройден")
                        .font(.headline)
                    Text(verdict(result))
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                }
            }

            if result.success {
                InfoRow(title: "Вопрос", value: "Сколько будет 2 + 2?")
                InfoRow(title: "Ответ модели", value: result.answer)
            }
            if let error = result.error {
                Text(error).font(.footnote).foregroundStyle(.red)
            }
            InfoRow(title: "Сеть во время теста", value: result.networkDescription)
            if let load = result.loadSeconds {
                InfoRow(title: "Загрузка модели", value: String(format: "%.1f с", load))
            }
            if let stats = result.stats {
                InfoRow(title: "Обработка вопроса", value: String(format: "%.2f с", stats.promptSeconds))
                InfoRow(title: "Скорость генерации", value: String(format: "%.1f токенов/с", stats.tokensPerSecond))
            }
            InfoRow(title: "Время теста", value: Format.date(result.date))
        }
        .glassCard()
    }

    private func verdict(_ result: OfflineTestResult) -> String {
        guard result.success else { return "Проверьте, что модель установлена и хватает памяти." }
        return result.networkAvailableDuringTest
            ? "Ответ получен локально. Сеть была включена, но не использовалась — повторите в авиарежиме для полной уверенности."
            : "Ответ получен при выключенной сети — генерация точно идёт на устройстве."
    }

    private var explanationCard: some View {
        VStack(alignment: .leading, spacing: 8) {
            Label("Как это устроено", systemImage: "info.circle")
                .font(.headline)
            Text("• Модель (.gguf) хранится в памяти iPhone и запускается движком llama.cpp на GPU (Metal).")
            Text("• В приложении нет сетевых запросов, серверов, API-ключей и аналитики.")
            Text("• История и избранное сохраняются в файл внутри приложения.")
            Text("• Монитор сети только показывает её состояние на этом экране.")
        }
        .font(.footnote)
        .glassCard()
    }
}

import SwiftUI
import UniformTypeIdentifiers

/// Раздел «AI-модель»: установка, выбор, статус, память.
@MainActor
struct ModelView: View {
    @Environment(ModelManager.self) private var models
    @Environment(AIController.self) private var ai
    @Environment(AppSettings.self) private var settings

    @State private var showImporter = false
    @State private var modelToDelete: LocalModel?
    @State private var memoryTick = Date()

    var body: some View {
        ScrollView {
            VStack(spacing: 16) {
                if models.models.isEmpty {
                    noModelCard
                } else {
                    activeModelCard
                }

                if let progress = models.importProgress {
                    importProgressCard(progress)
                }

                if let error = models.lastError {
                    Label(error, systemImage: "exclamationmark.triangle.fill")
                        .font(.footnote)
                        .foregroundStyle(.orange)
                        .glassCard(cornerRadius: 18)
                }

                memoryCard

                if models.models.count > 0 {
                    installedList
                }

                Button {
                    showImporter = true
                } label: {
                    Label("Импортировать модель (.gguf)", systemImage: "square.and.arrow.down")
                }
                .buttonStyle(PrimaryButtonStyle())
                .disabled(models.importProgress != nil)

                howToInstallCard
                recommendedCard
            }
            .padding()
        }
        .background(AppBackground())
        .navigationTitle("AI-модель")
        .navigationBarTitleDisplayMode(.inline)
        .fileImporter(isPresented: $showImporter, allowedContentTypes: [UTType(filenameExtension: "gguf") ?? .data, .data, .item]) { result in
            switch result {
            case .success(let url):
                Task {
                    await models.importModel(from: url)
                    ai.refreshState()
                }
            case .failure(let error):
                models.lastError = error.localizedDescription
            }
        }
        .confirmationDialog("Удалить модель?", isPresented: Binding(get: { modelToDelete != nil }, set: { if !$0 { modelToDelete = nil } }), titleVisibility: .visible) {
            Button("Удалить файл", role: .destructive) {
                guard let model = modelToDelete else { return }
                Task {
                    if ai.loadedInfo?.fileName == model.fileName { await ai.unload() }
                    models.delete(model)
                    ai.refreshState()
                }
            }
        } message: {
            Text(modelToDelete.map { "\($0.fileName) — \(Format.bytes($0.sizeBytes))" } ?? "")
        }
        .onAppear {
            models.refresh()
            ai.refreshState()
        }
        .task {
            // обновляем показатели памяти раз в 2 секунды, пока экран открыт
            while !Task.isCancelled {
                try? await Task.sleep(for: .seconds(2))
                memoryTick = Date()
            }
        }
    }

    // MARK: - Карточки

    private var noModelCard: some View {
        VStack(alignment: .leading, spacing: 12) {
            Image(systemName: "cpu")
                .font(.system(size: 36))
                .foregroundStyle(Palette.brandGradient)
            Text("Модель не установлена")
                .font(.title3.bold())
            Text("Для работы AI необходимо установить локальную модель.")
                .font(.subheadline)
            Text("Приложение не скачивает модель само и не требует интернета. Скопируйте файл .gguf на iPhone и импортируйте его кнопкой ниже.")
                .font(.footnote)
                .foregroundStyle(.secondary)
        }
        .glassCard()
    }

    private var activeModelCard: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack {
                VStack(alignment: .leading, spacing: 4) {
                    Text("Активная модель")
                        .font(.caption.weight(.semibold))
                        .foregroundStyle(.secondary)
                    Text(models.activeModel?.displayName ?? "—")
                        .font(.headline)
                }
                Spacer()
                HStack(spacing: 6) {
                    Circle().fill(ai.state.color).frame(width: 8, height: 8)
                    Text(ai.state.title).font(.caption.weight(.semibold))
                }
                .padding(.horizontal, 10)
                .padding(.vertical, 6)
                .background(ai.state.color.opacity(0.12), in: Capsule())
            }

            Divider()

            InfoRow(title: "Файл", value: models.activeModel?.fileName ?? "—", icon: "doc")
            InfoRow(title: "Размер модели", value: models.activeModel.map { Format.bytes($0.sizeBytes) } ?? "—", icon: "externaldrive")
            if let info = ai.loadedInfo {
                InfoRow(title: "Занимает в памяти (веса)", value: Format.bytes(info.weightsBytes), icon: "memorychip")
                InfoRow(title: "Параметров", value: Format.params(info.parameterCount), icon: "number")
                InfoRow(title: "Архитектура", value: info.description, icon: "cpu")
                InfoRow(title: "Контекст", value: "\(info.contextSize) из \(info.trainContextSize) токенов", icon: "text.alignleft")
                InfoRow(title: "Загрузка заняла", value: String(format: "%.1f с", info.loadSeconds), icon: "timer")
                if !info.hasChatTemplate {
                    Label("В модели нет шаблона чата — используется ChatML. Качество может быть хуже.", systemImage: "info.circle")
                        .font(.caption)
                        .foregroundStyle(.orange)
                }
            } else {
                InfoRow(title: "Статус загрузки", value: ai.state.title, icon: "memorychip")
            }
            if let stats = ai.lastStats {
                InfoRow(title: "Скорость генерации", value: String(format: "%.1f токенов/с", stats.tokensPerSecond), icon: "speedometer")
            }
            InfoRow(title: "Движок", value: ai.backendName, icon: "gearshape.2")

            if case .failed(let message) = ai.state {
                Text(message)
                    .font(.footnote)
                    .foregroundStyle(.red)
            }

            HStack(spacing: 10) {
                if ai.loadedInfo == nil {
                    Button {
                        Task { try? await ai.ensureLoaded() }
                    } label: {
                        Label(ai.state == .loading ? "Загрузка…" : "Загрузить в память", systemImage: "play.fill")
                    }
                    .buttonStyle(GlassButtonStyle())
                    .disabled(ai.state == .loading)
                } else {
                    Button {
                        Task { await ai.unload() }
                    } label: {
                        Label("Выгрузить из памяти", systemImage: "eject.fill")
                    }
                    .buttonStyle(GlassButtonStyle())
                    .disabled(ai.isGenerating)
                }
            }
        }
        .glassCard()
    }

    private var memoryCard: some View {
        let _ = memoryTick // перерисовка по таймеру
        let total = DeviceInfo.totalRAM
        let available = DeviceInfo.availableAppMemory
        let footprint = DeviceInfo.appFootprint
        let used = Double(footprint) / Double(max(1, footprint + available))

        return VStack(alignment: .leading, spacing: 12) {
            Label("Память устройства", systemImage: "memorychip")
                .font(.headline)

            if available > 0 {
                ProgressView(value: used) {
                    Text("Приложение использует \(Format.bytes(footprint))")
                        .font(.footnote)
                }
                .tint(used > 0.85 ? .red : used > 0.65 ? .orange : .green)
            }

            InfoRow(title: "Всего RAM", value: Format.bytes(total))
            InfoRow(title: "Доступно приложению", value: available > 0 ? Format.bytes(available) : "н/д (симулятор)")
            InfoRow(title: "Свободно на диске", value: Format.bytes(DeviceInfo.freeDiskSpace))
            if let model = models.activeModel {
                let needed = DeviceInfo.estimatedMemory(forModelFileSize: model.sizeBytes, contextSize: settings.contextSize)
                InfoRow(title: "Нужно для модели (оценка)", value: Format.bytes(needed))
            }
            if let warning = ai.lastMemoryWarning {
                Label("Последнее предупреждение iOS о нехватке памяти: \(Format.date(warning))", systemImage: "exclamationmark.triangle")
                    .font(.caption)
                    .foregroundStyle(.orange)
            }
        }
        .glassCard()
    }

    private var installedList: some View {
        VStack(alignment: .leading, spacing: 10) {
            Text("Установленные модели")
                .font(.headline)
            ForEach(models.models) { model in
                HStack(spacing: 12) {
                    Image(systemName: model.id == models.activeModel?.id ? "checkmark.circle.fill" : "circle")
                        .font(.title3)
                        .foregroundStyle(model.id == models.activeModel?.id ? Color.green : Color.secondary)
                    VStack(alignment: .leading, spacing: 2) {
                        Text(model.displayName)
                            .font(.subheadline.weight(.semibold))
                            .lineLimit(1)
                        Text(Format.bytes(model.sizeBytes))
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                    Spacer()
                    Button(role: .destructive) {
                        modelToDelete = model
                    } label: {
                        Image(systemName: "trash")
                    }
                    .buttonStyle(.borderless)
                    .disabled(ai.isGenerating)
                }
                .contentShape(Rectangle())
                .onTapGesture {
                    guard !ai.isGenerating, model.id != models.activeModel?.id else { return }
                    models.select(model)
                    Task {
                        await ai.unload()
                        _ = try? await ai.ensureLoaded()
                    }
                }
                if model.id != models.models.last?.id { Divider() }
            }
        }
        .glassCard()
    }

    private func importProgressCard(_ progress: Double) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("Импорт: \(models.importFileName ?? "")")
                .font(.subheadline.weight(.semibold))
                .lineLimit(1)
            ProgressView(value: progress)
            Text("\(Int(progress * 100))%")
                .font(.caption)
                .foregroundStyle(.secondary)
                .monospacedDigit()
        }
        .glassCard()
    }

    private var howToInstallCard: some View {
        VStack(alignment: .leading, spacing: 10) {
            Label("Как установить модель без интернета на iPhone", systemImage: "questionmark.circle")
                .font(.headline)
            Text("1. Скачайте файл .gguf на компьютере (один раз).")
            Text("2. Перенесите на iPhone: AirDrop, «Файлы» → iCloud/флешка, либо Finder (Mac) / приложение Apple Devices или iTunes (Windows) → Общие файлы → Index AI.")
            Text("3. Нажмите «Импортировать модель» и выберите файл. Файлы, скопированные через Finder/iTunes, подхватываются автоматически.")
        }
        .font(.footnote)
        .glassCard()
    }

    private var recommendedCard: some View {
        VStack(alignment: .leading, spacing: 10) {
            Label("Рекомендуемые модели", systemImage: "star")
                .font(.headline)
            recommended(name: "Qwen3 4B Instruct 2507 · IQ4_XS", file: "Qwen3-4B-Instruct-2507-IQ4_XS.gguf", size: "≈2.3 ГБ", note: "Рекомендуется. Самая точная из подходящих для iPhone с 6 ГБ+: обновлённая версия 4B без «размышлений», меньше памяти, чем Q4_K_M. Apache 2.0.")
            Divider()
            recommended(name: "Qwen3 1.7B · Q8_0", file: "Qwen3-1.7B-Q8_0.gguf", size: "≈1.8 ГБ", note: "Лучший баланс для iPhone с 6 ГБ RAM. Умнее Qwen2.5 1.5B. Лицензия Apache 2.0.")
            Divider()
            recommended(name: "Vikhr Qwen2.5 1.5B · Q4_K_M", file: "Vikhr-Qwen-2.5-1.5b-Instruct-Q4_K_M.gguf", size: "≈1.0 ГБ", note: "Дообучена на русском: лучше язык, быстрая. Apache 2.0.")
            Divider()
            recommended(name: "Qwen3 4B · Q4_K_M", file: "Qwen3-4B-Q4_K_M.gguf", size: "≈2.5 ГБ", note: "Самая умная из подходящих. Для 8 ГБ RAM; на 6 ГБ — только с контекстом 1024. Apache 2.0.")
            Divider()
            Text("Подходит любая instruct-модель в формате GGUF, которую поддерживает llama.cpp (Qwen, Llama 3.2, Gemma, Phi). Модели больше 4B на iPhone, как правило, не помещаются в память.")
                .font(.caption)
                .foregroundStyle(.secondary)
        }
        .glassCard()
    }

    private func recommended(name: String, file: String, size: String, note: String) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            HStack {
                Text(name).font(.subheadline.weight(.semibold))
                Spacer()
                Text(size).font(.caption.weight(.semibold)).foregroundStyle(.secondary)
            }
            Text(file)
                .font(.caption.monospaced())
                .foregroundStyle(Palette.violet)
                .textSelection(.enabled)
            Text(note)
                .font(.caption)
                .foregroundStyle(.secondary)
        }
    }
}

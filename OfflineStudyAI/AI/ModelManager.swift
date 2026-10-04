import Foundation
import Observation

/// Локальный файл модели.
struct LocalModel: Identifiable, Hashable {
    var url: URL
    var sizeBytes: UInt64
    var addedAt: Date

    var id: String { url.lastPathComponent }
    var fileName: String { url.lastPathComponent }
    var displayName: String {
        url.deletingPathExtension().lastPathComponent
            .replacingOccurrences(of: "-", with: " ")
            .replacingOccurrences(of: "_", with: " ")
    }
}

/// Управляет файлами моделей: поиск, импорт, удаление, выбор активной.
///
/// Модели хранятся в `Documents/Models`. Файл можно:
///  • импортировать кнопкой «Импортировать модель» (из «Файлов», AirDrop, флешки);
///  • скопировать в папку приложения через Finder (Mac) или Apple Devices / iTunes (Windows) —
///    при следующем открытии экрана он будет перенесён в `Models` автоматически.
/// Интернет для этого не нужен.
@MainActor
@Observable
final class ModelManager {
    private(set) var models: [LocalModel] = []
    private(set) var importProgress: Double?
    private(set) var importFileName: String?
    var lastError: String?

    private(set) var activeModelID: String? {
        didSet { UserDefaults.standard.set(activeModelID, forKey: "model.active") }
    }

    var activeModel: LocalModel? {
        models.first(where: { $0.id == activeModelID }) ?? models.first
    }

    var hasModel: Bool { !models.isEmpty }

    let modelsDirectory: URL

    init() {
        let documents = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
        modelsDirectory = documents.appendingPathComponent("Models", isDirectory: true)
        activeModelID = UserDefaults.standard.string(forKey: "model.active")
        prepareDirectory()
        refresh()
    }

    private func prepareDirectory() {
        try? FileManager.default.createDirectory(at: modelsDirectory, withIntermediateDirectories: true)
        // Модели большие — не отправляем их в резервную копию iCloud.
        var values = URLResourceValues()
        values.isExcludedFromBackup = true
        var dir = modelsDirectory
        try? dir.setResourceValues(values)
    }

    /// Перечитывает папку моделей и подхватывает .gguf, скопированные в Documents.
    func refresh() {
        let fm = FileManager.default
        let documents = modelsDirectory.deletingLastPathComponent()

        if let rootItems = try? fm.contentsOfDirectory(at: documents, includingPropertiesForKeys: nil) {
            for item in rootItems where item.pathExtension.lowercased() == "gguf" {
                let target = modelsDirectory.appendingPathComponent(item.lastPathComponent)
                if !fm.fileExists(atPath: target.path) {
                    try? fm.moveItem(at: item, to: target)
                }
            }
        }

        let keys: [URLResourceKey] = [.fileSizeKey, .creationDateKey]
        let items = (try? fm.contentsOfDirectory(at: modelsDirectory, includingPropertiesForKeys: keys)) ?? []
        models = items
            .filter { $0.pathExtension.lowercased() == "gguf" && Self.isGGUF($0) }
            .map { url in
                let values = try? url.resourceValues(forKeys: Set(keys))
                return LocalModel(url: url,
                                  sizeBytes: UInt64(values?.fileSize ?? 0),
                                  addedAt: values?.creationDate ?? Date())
            }
            .sorted { $0.addedAt > $1.addedAt }

        if let activeModelID, !models.contains(where: { $0.id == activeModelID }) {
            self.activeModelID = models.first?.id
        }
    }

    func select(_ model: LocalModel) {
        activeModelID = model.id
    }

    func delete(_ model: LocalModel) {
        do {
            try FileManager.default.removeItem(at: model.url)
        } catch {
            lastError = "Не удалось удалить файл: \(error.localizedDescription)"
        }
        refresh()
    }

    /// Проверка сигнатуры: файл GGUF начинается с байтов "GGUF".
    nonisolated static func isGGUF(_ url: URL) -> Bool {
        guard let handle = try? FileHandle(forReadingFrom: url) else { return false }
        defer { try? handle.close() }
        guard let magic = try? handle.read(upToCount: 4) else { return false }
        return magic == Data("GGUF".utf8)
    }

    /// Копирует выбранный пользователем файл в папку моделей с прогрессом.
    func importModel(from sourceURL: URL) async {
        lastError = nil
        let accessing = sourceURL.startAccessingSecurityScopedResource()
        defer { if accessing { sourceURL.stopAccessingSecurityScopedResource() } }

        guard Self.isGGUF(sourceURL) else {
            lastError = AIError.invalidModelFile.errorDescription
            return
        }

        let resourceValues = try? sourceURL.resourceValues(forKeys: [.fileSizeKey])
        let size = UInt64(resourceValues?.fileSize ?? 0)
        if size > 0, Int64(size) + 200_000_000 > DeviceInfo.freeDiskSpace {
            lastError = "Недостаточно места: файл \(Format.bytes(size)), свободно \(Format.bytes(DeviceInfo.freeDiskSpace))."
            return
        }

        var fileName = sourceURL.lastPathComponent
        if !fileName.lowercased().hasSuffix(".gguf") { fileName += ".gguf" }
        let destination = modelsDirectory.appendingPathComponent(fileName)

        importFileName = fileName
        importProgress = 0
        defer {
            importProgress = nil
            importFileName = nil
        }

        do {
            try await Self.copyFile(from: sourceURL, to: destination, totalSize: size) { [weak self] progress in
                Task { @MainActor in self?.importProgress = progress }
            }
            refresh()
            if let imported = models.first(where: { $0.id == fileName }) {
                activeModelID = imported.id
            }
        } catch {
            try? FileManager.default.removeItem(at: destination)
            lastError = "Не удалось импортировать модель: \(error.localizedDescription)"
        }
    }

    /// Потоковое копирование блоками по 8 МБ — не загружает гигабайтный файл в память.
    private nonisolated static func copyFile(
        from source: URL,
        to destination: URL,
        totalSize: UInt64,
        progress: @escaping @Sendable (Double) -> Void
    ) async throws {
        try await Task.detached(priority: .userInitiated) {
            let fm = FileManager.default
            if fm.fileExists(atPath: destination.path) {
                try fm.removeItem(at: destination)
            }
            fm.createFile(atPath: destination.path, contents: nil)

            let input = try FileHandle(forReadingFrom: source)
            let output = try FileHandle(forWritingTo: destination)
            defer {
                try? input.close()
                try? output.close()
            }

            let chunkSize = 8 * 1024 * 1024
            var copied: UInt64 = 0
            var lastReported = 0.0
            while true {
                try Task.checkCancellation()
                let chunk: Data? = try autoreleasepool {
                    try input.read(upToCount: chunkSize)
                }
                guard let chunk, !chunk.isEmpty else { break }
                try output.write(contentsOf: chunk)
                copied += UInt64(chunk.count)
                if totalSize > 0 {
                    let value = Double(copied) / Double(totalSize)
                    if value - lastReported >= 0.01 {
                        lastReported = value
                        progress(min(value, 1))
                    }
                }
            }
            try output.synchronize()
        }.value
    }
}

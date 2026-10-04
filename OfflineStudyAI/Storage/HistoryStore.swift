import Foundation
import Observation

/// Избранный ответ вместе с вопросом, на который он дан.
struct FavoriteItem: Identifiable, Hashable {
    var conversationID: UUID
    var question: String
    var answer: ChatMessage
    var subject: Subject
    var id: UUID { answer.id }
}

/// История и избранное. Хранится в одном JSON-файле в
/// `Application Support/history.json` — только на устройстве.
@MainActor
@Observable
final class HistoryStore {
    private(set) var conversations: [Conversation] = []

    private let fileURL: URL
    @ObservationIgnored private var saveTask: Task<Void, Never>?

    init() {
        let support = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        try? FileManager.default.createDirectory(at: support, withIntermediateDirectories: true)
        fileURL = support.appendingPathComponent("history.json")
        load()
    }

    // MARK: - Чтение

    var favorites: [FavoriteItem] {
        conversations.flatMap { conversation in
            conversation.messages.enumerated().compactMap { index, message -> FavoriteItem? in
                guard message.isFavorite, message.role == .assistant else { return nil }
                let question = conversation.messages[..<index].last(where: { $0.role == .user })?.text ?? conversation.firstQuestion
                return FavoriteItem(conversationID: conversation.id, question: question, answer: message, subject: conversation.subject)
            }
        }
        .sorted { $0.answer.date > $1.answer.date }
    }

    func conversation(id: UUID) -> Conversation? {
        conversations.first(where: { $0.id == id })
    }

    // MARK: - Изменение

    func upsert(_ conversation: Conversation) {
        guard !conversation.isEmpty else { return }
        if let index = conversations.firstIndex(where: { $0.id == conversation.id }) {
            conversations[index] = conversation
        } else {
            conversations.append(conversation)
        }
        conversations.sort { $0.updatedAt > $1.updatedAt }
        scheduleSave()
    }

    func delete(_ conversationID: UUID) {
        conversations.removeAll { $0.id == conversationID }
        scheduleSave()
    }

    func setFavorite(_ isFavorite: Bool, messageID: UUID, in conversationID: UUID) {
        guard let c = conversations.firstIndex(where: { $0.id == conversationID }),
              let m = conversations[c].messages.firstIndex(where: { $0.id == messageID }) else { return }
        conversations[c].messages[m].isFavorite = isFavorite
        scheduleSave()
    }

    func clearAll() {
        conversations.removeAll()
        scheduleSave()
    }

    // MARK: - Файл

    private func load() {
        guard let data = try? Data(contentsOf: fileURL) else { return }
        let decoder = JSONDecoder()
        decoder.dateDecodingStrategy = .iso8601
        if let decoded = try? decoder.decode([Conversation].self, from: data) {
            conversations = decoded.sorted { $0.updatedAt > $1.updatedAt }
        }
    }

    private func scheduleSave() {
        saveTask?.cancel()
        let snapshot = conversations
        let url = fileURL
        saveTask = Task.detached(priority: .utility) {
            try? await Task.sleep(for: .milliseconds(400))
            guard !Task.isCancelled else { return }
            let encoder = JSONEncoder()
            encoder.dateEncodingStrategy = .iso8601
            guard let data = try? encoder.encode(snapshot) else { return }
            try? data.write(to: url, options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
        }
    }

    /// Немедленное сохранение (например, при уходе приложения в фон).
    func saveNow() {
        saveTask?.cancel()
        let encoder = JSONEncoder()
        encoder.dateEncodingStrategy = .iso8601
        if let data = try? encoder.encode(conversations) {
            try? data.write(to: fileURL, options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
        }
    }
}

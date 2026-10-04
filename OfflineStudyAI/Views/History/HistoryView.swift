import SwiftUI
import UIKit

@MainActor
struct HistoryView: View {
    enum Filter: String, CaseIterable, Identifiable {
        case all = "Все вопросы"
        case favorites = "Избранное"
        var id: String { rawValue }
    }

    @Environment(HistoryStore.self) private var history
    @Environment(ChatSession.self) private var session
    @Environment(AppRouter.self) private var router

    @State private var filter: Filter = .all
    @State private var search = ""
    @State private var subjectFilter: Subject?
    @State private var selectedFavorite: FavoriteItem?

    var body: some View {
        NavigationStack {
            ZStack {
                AppBackground()
                VStack(spacing: 10) {
                    Picker("Фильтр", selection: $filter) {
                        ForEach(Filter.allCases) { Text($0.rawValue).tag($0) }
                    }
                    .pickerStyle(.segmented)
                    .padding(.horizontal)

                    if filter == .all {
                        subjectChips
                    }

                    content
                }
            }
            .navigationTitle("История")
            .searchable(text: $search, prompt: "Поиск по вопросам и ответам")
            .sheet(item: $selectedFavorite) { item in
                FavoriteDetailView(item: item)
            }
        }
    }

    // MARK: - Фильтр по предметам

    private var usedSubjects: [Subject] {
        let used = Set(history.conversations.map(\.subject))
        return Subject.allCases.filter { used.contains($0) }
    }

    @ViewBuilder
    private var subjectChips: some View {
        if usedSubjects.count > 1 {
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 8) {
                    chip(title: "Все", emoji: "✨", isOn: subjectFilter == nil) { subjectFilter = nil }
                    ForEach(usedSubjects) { subject in
                        chip(title: subject.title, emoji: subject.emoji, isOn: subjectFilter == subject) {
                            subjectFilter = subjectFilter == subject ? nil : subject
                        }
                    }
                }
                .padding(.horizontal)
            }
        }
    }

    private func chip(title: String, emoji: String, isOn: Bool, action: @escaping () -> Void) -> some View {
        Button(action: { withAnimation(.snappy) { action() } }) {
            HStack(spacing: 4) {
                Text(emoji)
                Text(title).font(.footnote.weight(.semibold))
            }
            .padding(.horizontal, 12)
            .padding(.vertical, 7)
            .background(isOn ? AnyShapeStyle(Palette.brandGradient) : AnyShapeStyle(.thinMaterial), in: Capsule())
            .foregroundStyle(isOn ? Color.white : Color.primary)
        }
        .buttonStyle(PressableStyle())
    }

    // MARK: - Списки

    private var filteredConversations: [Conversation] {
        history.conversations.filter { conversation in
            if let subjectFilter, conversation.subject != subjectFilter { return false }
            guard !search.isEmpty else { return true }
            return conversation.messages.contains { $0.text.localizedCaseInsensitiveContains(search) }
        }
    }

    private var filteredFavorites: [FavoriteItem] {
        history.favorites.filter { item in
            search.isEmpty
                || item.question.localizedCaseInsensitiveContains(search)
                || item.answer.text.localizedCaseInsensitiveContains(search)
        }
    }

    @ViewBuilder
    private var content: some View {
        switch filter {
        case .all:
            if filteredConversations.isEmpty {
                emptyState(icon: "clock", title: "История пуста", text: "Здесь появятся ваши вопросы и ответы. Всё хранится только на этом iPhone.")
            } else {
                List {
                    ForEach(filteredConversations) { conversation in
                        Button {
                            session.open(conversation)
                            router.tab = .chat
                        } label: {
                            ConversationRow(conversation: conversation)
                        }
                        .buttonStyle(PressableStyle())
                        .listRowBackground(Color.clear)
                        .listRowSeparator(.hidden)
                        .listRowInsets(EdgeInsets(top: 6, leading: 16, bottom: 6, trailing: 16))
                        .swipeActions {
                            Button(role: .destructive) {
                                history.delete(conversation.id)
                                session.syncFromHistory()
                            } label: {
                                Label("Удалить", systemImage: "trash")
                            }
                        }
                    }
                }
                .listStyle(.plain)
                .scrollContentBackground(.hidden)
            }

        case .favorites:
            if filteredFavorites.isEmpty {
                emptyState(icon: "star", title: "Нет избранного", text: "Нажмите «В избранное» под ответом, чтобы сохранить его здесь.")
            } else {
                List {
                    ForEach(filteredFavorites) { item in
                        Button {
                            selectedFavorite = item
                        } label: {
                            FavoriteRow(item: item)
                        }
                        .buttonStyle(PressableStyle())
                        .listRowBackground(Color.clear)
                        .listRowSeparator(.hidden)
                        .listRowInsets(EdgeInsets(top: 6, leading: 16, bottom: 6, trailing: 16))
                        .swipeActions {
                            Button {
                                history.setFavorite(false, messageID: item.answer.id, in: item.conversationID)
                                session.syncFromHistory()
                            } label: {
                                Label("Убрать", systemImage: "star.slash")
                            }
                            .tint(.orange)
                        }
                    }
                }
                .listStyle(.plain)
                .scrollContentBackground(.hidden)
            }
        }
    }

    private func emptyState(icon: String, title: String, text: String) -> some View {
        VStack(spacing: 12) {
            Spacer()
            Image(systemName: icon)
                .font(.system(size: 44))
                .foregroundStyle(Palette.brandGradient)
            Text(title).font(.title3.bold())
            Text(text)
                .font(.subheadline)
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
                .padding(.horizontal, 40)
            Spacer()
        }
    }
}

// MARK: - Строки

struct ConversationRow: View {
    let conversation: Conversation

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack {
                SubjectBadge(subject: conversation.subject)
                if conversation.mode == .solve {
                    Label("Задание", systemImage: "checklist")
                        .font(.caption.weight(.semibold))
                        .foregroundStyle(.secondary)
                }
                Spacer()
                Text(Format.date(conversation.updatedAt))
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
            Text(conversation.firstQuestion)
                .font(.headline)
                .lineLimit(2)
                .multilineTextAlignment(.leading)
            if !conversation.firstAnswer.isEmpty {
                Text(MarkdownParser.convertInlineFormulas(conversation.firstAnswer.replacingOccurrences(of: "**", with: "")))
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
                    .lineLimit(3)
                    .multilineTextAlignment(.leading)
            }
        }
        .foregroundStyle(.primary)
        .glassCard(cornerRadius: 22, padding: 14)
    }
}

struct FavoriteRow: View {
    let item: FavoriteItem

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack {
                Image(systemName: "star.fill").foregroundStyle(.yellow)
                SubjectBadge(subject: item.subject)
                Spacer()
                Text(Format.date(item.answer.date))
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
            Text(item.question)
                .font(.headline)
                .lineLimit(2)
            Text(MarkdownParser.convertInlineFormulas(item.answer.text.replacingOccurrences(of: "**", with: "")))
                .font(.subheadline)
                .foregroundStyle(.secondary)
                .lineLimit(4)
        }
        .foregroundStyle(.primary)
        .multilineTextAlignment(.leading)
        .glassCard(cornerRadius: 22, padding: 14)
    }
}

struct FavoriteDetailView: View {
    let item: FavoriteItem
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 16) {
                    SubjectBadge(subject: item.subject)
                    Text(item.question)
                        .font(.title3.bold())
                    MarkdownView(text: item.answer.text)
                        .glassCard(cornerRadius: 22)
                }
                .padding()
            }
            .background(AppBackground())
            .navigationTitle("Избранное")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    Button {
                        UIPasteboard.general.string = item.answer.text
                    } label: {
                        Image(systemName: "doc.on.doc")
                    }
                    .accessibilityLabel("Скопировать ответ")
                }
                ToolbarItem(placement: .topBarTrailing) {
                    ShareLink(item: "\(item.question)\n\n\(item.answer.text)")
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Готово") { dismiss() }
                }
            }
        }
    }
}

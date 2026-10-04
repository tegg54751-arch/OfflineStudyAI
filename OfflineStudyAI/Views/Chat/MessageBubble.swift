import SwiftUI
import UIKit

@MainActor
struct MessageBubble: View {
    let message: ChatMessage
    let isStreaming: Bool
    let showsFollowUps: Bool
    var onFavorite: () -> Void = {}
    var onSimpler: () -> Void = {}
    var onExample: () -> Void = {}

    @State private var copied = false

    var body: some View {
        switch message.role {
        case .user: userBubble
        case .assistant: assistantBubble
        }
    }

    // MARK: - Вопрос пользователя

    private var userBubble: some View {
        HStack {
            Spacer(minLength: 48)
            VStack(alignment: .trailing, spacing: 4) {
                if message.kind == .simpler || message.kind == .example {
                    Label(message.kind == .simpler ? "Объясни проще" : "Дай пример",
                          systemImage: message.kind == .simpler ? "wand.and.stars" : "lightbulb")
                        .font(.subheadline.weight(.semibold))
                        .foregroundStyle(.white)
                } else {
                    if message.kind == .solve {
                        Label("Задание", systemImage: "checklist")
                            .font(.caption.weight(.bold))
                            .foregroundStyle(.white.opacity(0.8))
                    }
                    Text(message.text)
                        .foregroundStyle(.white)
                        .textSelection(.enabled)
                }
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 12)
            .background(Palette.brandGradient, in: RoundedRectangle(cornerRadius: 22, style: .continuous))
            .shadow(color: Palette.violet.opacity(0.25), radius: 10, y: 4)
        }
    }

    // MARK: - Ответ AI

    private var assistantBubble: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack(spacing: 8) {
                Image(systemName: "sparkles")
                    .font(.caption.weight(.bold))
                    .foregroundStyle(.white)
                    .frame(width: 24, height: 24)
                    .background(Palette.brandGradient, in: Circle())
                Text("Offline Study AI")
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(.secondary)
                if let speed = message.tokensPerSecond, !isStreaming {
                    Text(String(format: "· %.1f ток/с", speed))
                        .font(.caption2)
                        .foregroundStyle(.tertiary)
                }
                Spacer()
            }

            if message.kind == .error {
                Label(message.text, systemImage: "exclamationmark.triangle.fill")
                    .foregroundStyle(.orange)
                    .font(.subheadline)
            } else if message.text.isEmpty && isStreaming {
                TypingIndicator()
                    .padding(.vertical, 4)
            } else {
                MarkdownView(text: message.text)
                if isStreaming {
                    TypingIndicator()
                }
            }

            if !isStreaming && message.kind != .error && !message.text.isEmpty {
                actionRow
                if showsFollowUps {
                    followUps
                        .transition(.move(edge: .bottom).combined(with: .opacity))
                }
            }
        }
        .glassCard(cornerRadius: 24, padding: 16)
        .padding(.trailing, 12)
        .contextMenu {
            Button { copy() } label: { Label("Скопировать", systemImage: "doc.on.doc") }
            Button { onFavorite() } label: {
                Label(message.isFavorite ? "Убрать из избранного" : "В избранное",
                      systemImage: message.isFavorite ? "star.slash" : "star")
            }
            ShareLink(item: message.text) { Label("Поделиться", systemImage: "square.and.arrow.up") }
        }
    }

    private var actionRow: some View {
        HStack(spacing: 18) {
            Button(action: copy) {
                Label(copied ? "Скопировано" : "Копировать", systemImage: copied ? "checkmark" : "doc.on.doc")
            }
            Button(action: onFavorite) {
                Label(message.isFavorite ? "В избранном" : "В избранное", systemImage: message.isFavorite ? "star.fill" : "star")
                    .foregroundStyle(message.isFavorite ? Color.yellow : Color.accentColor)
            }
            .sensoryFeedback(.success, trigger: message.isFavorite)
            Spacer()
        }
        .font(.footnote.weight(.semibold))
        .labelStyle(.titleAndIcon)
        .buttonStyle(PressableStyle())
    }

    private var followUps: some View {
        HStack(spacing: 10) {
            Button(action: onSimpler) {
                Label("Объясни проще", systemImage: "wand.and.stars")
            }
            .buttonStyle(GlassButtonStyle())
            Button(action: onExample) {
                Label("Дай пример", systemImage: "lightbulb")
            }
            .buttonStyle(GlassButtonStyle())
        }
    }

    private func copy() {
        UIPasteboard.general.string = message.text
        withAnimation { copied = true }
        Task {
            try? await Task.sleep(for: .seconds(1.5))
            withAnimation { copied = false }
        }
    }
}

import SwiftUI

@MainActor
struct HomeView: View {
    @Environment(ChatSession.self) private var session
    @Environment(AIController.self) private var ai
    @Environment(ModelManager.self) private var models
    @Environment(HistoryStore.self) private var history
    @Environment(AppRouter.self) private var router

    @Environment(\.openURL) private var openURL
    @State private var appeared = false

    var body: some View {
        NavigationStack {
            ZStack {
                AppBackground()
                ScrollView {
                    VStack(spacing: 20) {
                        header
                            .modifier(AppearEffect(appeared: appeared, delay: 0))
                        modelStatusCard
                            .modifier(AppearEffect(appeared: appeared, delay: 0.05))
                        universalCard
                            .modifier(AppearEffect(appeared: appeared, delay: 0.1))
                        telegramBanner
                            .modifier(AppearEffect(appeared: appeared, delay: 0.12))
                        if !history.conversations.isEmpty {
                            recentSection
                                .modifier(AppearEffect(appeared: appeared, delay: 0.15))
                        }
                    }
                    .padding(.horizontal)
                    .padding(.bottom, 30)
                }
            }
            .toolbar(.hidden, for: .navigationBar)
            .onAppear { appeared = true }
        }
    }

    // MARK: - Заголовок

    private var header: some View {
        VStack(alignment: .leading, spacing: 10) {
            Text("Index AI")
                .font(.system(size: 34, weight: .bold, design: .rounded))
                .foregroundStyle(Palette.brandGradient)
            HStack(spacing: 8) {
                StatusDot(color: .green)
                Text("Работает полностью офлайн")
                    .font(.subheadline.weight(.semibold))
            }
            .padding(.horizontal, 12)
            .padding(.vertical, 7)
            .background(.ultraThinMaterial, in: Capsule())
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(.top, 12)
    }

    // MARK: - Статус модели

    private var modelStatusCard: some View {
        Button {
            router.showModelScreen = true
        } label: {
            HStack(spacing: 14) {
                Image(systemName: "cpu.fill")
                    .font(.title2)
                    .foregroundStyle(.white)
                    .frame(width: 48, height: 48)
                    .background(ai.state.color.gradient, in: RoundedRectangle(cornerRadius: 14, style: .continuous))
                VStack(alignment: .leading, spacing: 3) {
                    Text(models.activeModel?.displayName ?? "AI-модель не установлена")
                        .font(.headline)
                        .lineLimit(1)
                    HStack(spacing: 6) {
                        Circle().fill(ai.state.color).frame(width: 7, height: 7)
                        Text(ai.state.title)
                            .font(.footnote)
                            .foregroundStyle(.secondary)
                    }
                }
                Spacer()
                if ai.state == .loading {
                    ProgressView()
                } else {
                    Image(systemName: "chevron.right")
                        .foregroundStyle(.tertiary)
                }
            }
            .foregroundStyle(.primary)
            .glassCard(cornerRadius: 24, padding: 14)
        }
        .buttonStyle(PressableStyle())
    }

    // MARK: - Единый раздел для всех предметов

    private var universalCard: some View {
        VStack(alignment: .leading, spacing: 16) {
            VStack(alignment: .leading, spacing: 4) {
                Text("Один помощник — все предметы")
                    .font(.title3.bold())
                Text("Спросите что угодно: предмет определится автоматически.")
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
            }

            FlowLayout(spacing: 8) {
                ForEach(Subject.showcase) { subject in
                    Button {
                        openChat(mode: .ask, prefill: subject.sampleQuestion)
                    } label: {
                        HStack(spacing: 5) {
                            Text(subject.emoji)
                            Text(subject.title)
                                .font(.footnote.weight(.semibold))
                        }
                        .padding(.horizontal, 11)
                        .padding(.vertical, 8)
                        .background(subject.tint.opacity(0.14), in: Capsule())
                        .foregroundStyle(.primary)
                    }
                    .buttonStyle(PressableStyle())
                }
            }

            Button {
                openChat(mode: .ask)
            } label: {
                Label("Задать вопрос", systemImage: "bubble.left.and.text.bubble.right.fill")
            }
            .buttonStyle(PrimaryButtonStyle())

            HStack(spacing: 10) {
                Button {
                    openChat(mode: .solve)
                } label: {
                    Label("Решить задание", systemImage: "checklist")
                }
                .buttonStyle(GlassButtonStyle())

                Button {
                    openChat(mode: .solve)
                    router.pendingPhotoSource = .camera
                } label: {
                    Label("Фото задания", systemImage: "camera.fill")
                }
                .buttonStyle(GlassButtonStyle())
            }
        }
        .glassCard(cornerRadius: 30, padding: 20)
    }

    // MARK: - Telegram-канал

    private static let telegramURL = URL(string: "https://t.me/IndexAIChannel")!

    private var telegramBanner: some View {
        Button {
            openURL(Self.telegramURL)
        } label: {
            HStack(spacing: 14) {
                Image(systemName: "paperplane.fill")
                    .font(.system(size: 22, weight: .semibold))
                    .foregroundStyle(Color(red: 0.13, green: 0.62, blue: 0.85))
                    .rotationEffect(.degrees(-12))
                    .offset(x: -1, y: 1)
                    .frame(width: 48, height: 48)
                    .background(.white, in: Circle())
                    .shadow(color: .black.opacity(0.15), radius: 6, y: 3)
                VStack(alignment: .leading, spacing: 3) {
                    Text("Наш Telegram-канал")
                        .font(.headline)
                    Text("Новости, обновления и новые модели")
                        .font(.footnote)
                        .opacity(0.85)
                }
                Spacer(minLength: 8)
                Image(systemName: "arrow.up.right")
                    .font(.footnote.weight(.bold))
                    .frame(width: 30, height: 30)
                    .background(.white.opacity(0.22), in: Circle())
            }
            .foregroundStyle(.white)
            .padding(14)
            .background {
                ZStack(alignment: .trailing) {
                    LinearGradient(
                        colors: [Color(red: 0.16, green: 0.67, blue: 0.93), Color(red: 0.12, green: 0.53, blue: 0.82)],
                        startPoint: .topLeading, endPoint: .bottomTrailing
                    )
                    Image(systemName: "paperplane.fill")
                        .font(.system(size: 92))
                        .foregroundStyle(.white.opacity(0.10))
                        .rotationEffect(.degrees(-12))
                        .offset(x: 18, y: 10)
                }
                .clipShape(RoundedRectangle(cornerRadius: 24, style: .continuous))
            }
            .overlay(RoundedRectangle(cornerRadius: 24, style: .continuous).strokeBorder(.white.opacity(0.18)))
            .shadow(color: Color(red: 0.13, green: 0.62, blue: 0.85).opacity(0.35), radius: 14, y: 6)
        }
        .buttonStyle(PressableStyle())
        .accessibilityLabel("Открыть Telegram-канал Index AI")
    }

    // MARK: - Недавние

    private var recentSection: some View {
        VStack(spacing: 10) {
            HStack {
                SectionTitle(text: "Недавние")
                Button("Все") { router.tab = .history }
                    .font(.subheadline.weight(.semibold))
            }
            ForEach(history.conversations.prefix(3)) { conversation in
                Button {
                    session.open(conversation)
                    router.tab = .chat
                } label: {
                    HStack(spacing: 12) {
                        Text(conversation.subject.emoji)
                            .font(.title2)
                            .frame(width: 44, height: 44)
                            .background(conversation.subject.tint.opacity(0.14), in: RoundedRectangle(cornerRadius: 12, style: .continuous))
                        VStack(alignment: .leading, spacing: 3) {
                            Text(conversation.firstQuestion)
                                .font(.subheadline.weight(.semibold))
                                .lineLimit(1)
                            Text(Format.date(conversation.updatedAt))
                                .font(.caption)
                                .foregroundStyle(.secondary)
                        }
                        Spacer()
                        Image(systemName: "chevron.right").foregroundStyle(.tertiary)
                    }
                    .foregroundStyle(.primary)
                    .glassCard(cornerRadius: 20, padding: 12)
                }
                .buttonStyle(PressableStyle())
            }
        }
    }

    private func openChat(mode: ChatMode, prefill: String = "") {
        if session.isGenerating {
            router.tab = .chat
            return
        }
        session.newChat(mode: mode, prefill: prefill)
        router.tab = .chat
    }
}

/// Плавное появление элементов главного экрана.
struct AppearEffect: ViewModifier {
    let appeared: Bool
    let delay: Double

    func body(content: Content) -> some View {
        content
            .opacity(appeared ? 1 : 0)
            .offset(y: appeared ? 0 : 18)
            .animation(.spring(response: 0.55, dampingFraction: 0.85).delay(delay), value: appeared)
    }
}

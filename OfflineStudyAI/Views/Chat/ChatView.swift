import SwiftUI
import PhotosUI

/// ЕДИНЫЙ раздел для всех предметов: вопросы, решение заданий, фото.
@MainActor
struct ChatView: View {
    @Environment(ChatSession.self) private var session
    @Environment(AIController.self) private var ai
    @Environment(AppRouter.self) private var router
    @Environment(ModelManager.self) private var models

    @FocusState private var inputFocused: Bool
    @State private var showCamera = false
    @State private var showPhotoPicker = false
    @State private var pickedItem: PhotosPickerItem?
    @State private var ocrImage: UIImage?
    @State private var ocrText = ""
    @State private var showOCRSheet = false
    @State private var isRecognizing = false
    @State private var ocrError: String?

    var body: some View {
        @Bindable var session = session

        NavigationStack {
            ZStack {
                AppBackground()

                VStack(spacing: 0) {
                    modePicker
                        .padding(.horizontal)
                        .padding(.top, 4)
                        .padding(.bottom, 8)

                    messagesList

                    ChatInputBar(
                        text: $session.input,
                        mode: session.mode,
                        isGenerating: session.isGenerating,
                        focus: $inputFocused,
                        onSend: { session.send() },
                        onStop: { session.stop() },
                        onCamera: openCamera,
                        onLibrary: { showPhotoPicker = true }
                    )
                    .background(.bar)
                }

                if isRecognizing {
                    recognizingOverlay
                }
            }
            .navigationTitle(session.conversation.isEmpty ? "Спросить" : session.conversation.subject.title)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    if !session.conversation.isEmpty {
                        SubjectBadge(subject: session.conversation.subject)
                    }
                }
                ToolbarItem(placement: .topBarTrailing) {
                    Button {
                        withAnimation(.spring) { session.newChat(mode: session.mode) }
                    } label: {
                        Image(systemName: "square.and.pencil")
                    }
                    .accessibilityLabel("Новый чат")
                }
            }
        }
        .fullScreenCover(isPresented: $showCamera) {
            CameraPicker { image in
                Task {
                    // ждём, пока закроется экран камеры, иначе лист с текстом не откроется
                    try? await Task.sleep(for: .milliseconds(600))
                    await recognize(image)
                }
            }
            .ignoresSafeArea()
        }
        .photosPicker(isPresented: $showPhotoPicker, selection: $pickedItem, matching: .images)
        .onChange(of: pickedItem) { _, item in
            guard let item else { return }
            Task {
                if let data = try? await item.loadTransferable(type: Data.self), let image = UIImage(data: data) {
                    await recognize(image)
                } else {
                    ocrError = "Не удалось открыть изображение."
                }
                pickedItem = nil
            }
        }
        .sheet(isPresented: $showOCRSheet) {
            OCRResultSheet(image: ocrImage, text: $ocrText) { action in
                showOCRSheet = false
                let text = ocrText.trimmingCharacters(in: .whitespacesAndNewlines)
                guard !text.isEmpty else { return }
                switch action {
                case .solve:
                    session.solve(text)
                case .ask:
                    if !session.conversation.isEmpty { session.newChat(mode: .ask) }
                    session.input = text
                    session.send()
                }
            }
        }
        .alert("Распознавание текста", isPresented: Binding(get: { ocrError != nil }, set: { if !$0 { ocrError = nil } })) {
            Button("OK", role: .cancel) {}
        } message: {
            Text(ocrError ?? "")
        }
        .onAppear(perform: handlePendingPhoto)
        .onChange(of: router.pendingPhotoSource) { _, _ in handlePendingPhoto() }
    }

    // MARK: - Части экрана

    private var modePicker: some View {
        @Bindable var session = session
        return Picker("Режим", selection: $session.mode) {
            ForEach(ChatMode.allCases) { mode in
                Text(mode.title).tag(mode)
            }
        }
        .pickerStyle(.segmented)
        .disabled(session.isGenerating)
    }

    private var messagesList: some View {
        ScrollViewReader { proxy in
            ScrollView {
                LazyVStack(spacing: 16) {
                    if ai.state == .notInstalled {
                        NoModelBanner()
                    } else if case .failed(let message) = ai.state {
                        ErrorBanner(message: message)
                    }

                    if session.conversation.isEmpty {
                        ChatEmptyState(mode: session.mode) { suggestion in
                            session.input = suggestion
                            inputFocused = true
                        }
                    }

                    ForEach(session.conversation.messages) { message in
                        MessageBubble(
                            message: message,
                            isStreaming: message.id == session.streamingMessageID,
                            showsFollowUps: message.id == session.lastAssistantMessageID && !session.isGenerating,
                            onFavorite: { session.toggleFavorite(message.id) },
                            onSimpler: { session.explainSimpler() },
                            onExample: { session.giveExample() }
                        )
                        .id(message.id)
                        .transition(.asymmetric(insertion: .move(edge: .bottom).combined(with: .opacity), removal: .opacity))
                    }

                    if ai.state == .loading {
                        HStack(spacing: 10) {
                            ProgressView()
                            Text("Загружаю модель в память…")
                                .font(.footnote)
                                .foregroundStyle(.secondary)
                        }
                        .padding(.top, 4)
                    }

                    Color.clear.frame(height: 4).id("bottom")
                }
                .padding(.horizontal)
                .padding(.vertical, 8)
                .animation(.spring(response: 0.4, dampingFraction: 0.85), value: session.conversation.messages.count)
            }
            .scrollDismissesKeyboard(.interactively)
            .onChange(of: session.conversation.messages.last?.text) { _, _ in
                proxy.scrollTo("bottom", anchor: .bottom)
            }
            .onChange(of: session.conversation.messages.count) { _, _ in
                withAnimation { proxy.scrollTo("bottom", anchor: .bottom) }
            }
            .onChange(of: session.conversation.id) { _, _ in
                proxy.scrollTo("bottom", anchor: .bottom)
            }
        }
    }

    private var recognizingOverlay: some View {
        VStack(spacing: 14) {
            ProgressView().controlSize(.large)
            Text("Распознаю текст на фото…")
                .font(.headline)
            Text("Обработка на устройстве, без интернета")
                .font(.footnote)
                .foregroundStyle(.secondary)
        }
        .padding(28)
        .glassCard(cornerRadius: 28)
        .padding(40)
        .transition(.scale.combined(with: .opacity))
    }

    // MARK: - Фото

    private func openCamera() {
        if CameraPicker.isAvailable {
            showCamera = true
        } else {
            ocrError = "Камера недоступна на этом устройстве."
        }
    }

    private func handlePendingPhoto() {
        guard let source = router.pendingPhotoSource else { return }
        router.pendingPhotoSource = nil
        switch source {
        case .camera: openCamera()
        case .library: showPhotoPicker = true
        }
    }

    private func recognize(_ image: UIImage) async {
        withAnimation { isRecognizing = true }
        defer { withAnimation { isRecognizing = false } }
        do {
            let text = try await OCRService.recognizeText(in: image)
            ocrImage = image
            ocrText = text
            showOCRSheet = true
        } catch {
            ocrError = (error as? LocalizedError)?.errorDescription ?? error.localizedDescription
        }
    }
}

// MARK: - Пустое состояние

struct ChatEmptyState: View {
    let mode: ChatMode
    var onSuggestion: (String) -> Void

    private var suggestions: [String] {
        switch mode {
        case .ask:
            return ["Что такое фотосинтез?",
                    "Почему меняются времена года?",
                    "Чем причастие отличается от деепричастия?",
                    "Что такое закон Ома?",
                    "Кто такой Пётр I и чем он известен?"]
        case .solve:
            return ["Реши уравнение: 3x + 7 = 22",
                    "Найди площадь треугольника с основанием 10 см и высотой 6 см",
                    "Сколько граммов соли в 200 г 15% раствора?",
                    "Поезд проехал 240 км за 3 часа. Найди скорость."]
        }
    }

    var body: some View {
        VStack(spacing: 18) {
            ZStack {
                Circle()
                    .fill(Palette.brandGradient)
                    .frame(width: 84, height: 84)
                    .shadow(color: Palette.violet.opacity(0.4), radius: 20, y: 8)
                Image(systemName: mode == .solve ? "checklist" : "sparkles")
                    .font(.system(size: 36, weight: .semibold))
                    .foregroundStyle(.white)
                    .symbolEffect(.pulse, options: .repeating)
            }
            .padding(.top, 20)

            VStack(spacing: 6) {
                Text(mode == .solve ? "Решение заданий" : "Один чат — все предметы")
                    .font(.title2.bold())
                Text(mode == .solve
                     ? "Введите условие или сфотографируйте задание. AI определит предмет и решит по шагам."
                     : "Биология, химия, математика, история, русский и всё остальное — предмет определяется автоматически.")
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
                    .multilineTextAlignment(.center)
            }

            FlowLayout(spacing: 6) {
                ForEach(Subject.showcase) { subject in
                    Text(subject.emoji)
                        .font(.title3)
                        .frame(width: 38, height: 38)
                        .background(subject.tint.opacity(0.12), in: Circle())
                }
            }
            .frame(maxWidth: 300)

            VStack(spacing: 10) {
                ForEach(suggestions, id: \.self) { suggestion in
                    Button {
                        onSuggestion(suggestion)
                    } label: {
                        HStack {
                            Text(suggestion)
                                .multilineTextAlignment(.leading)
                            Spacer()
                            Image(systemName: "arrow.up.left")
                                .foregroundStyle(.secondary)
                        }
                        .font(.subheadline)
                        .foregroundStyle(.primary)
                        .glassCard(cornerRadius: 18, padding: 14)
                    }
                    .buttonStyle(PressableStyle())
                }
            }
        }
        .padding(.bottom, 20)
    }
}

// MARK: - Баннеры

struct NoModelBanner: View {
    @Environment(AppRouter.self) private var router

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Label("Модель не установлена", systemImage: "cpu")
                .font(.headline)
                .foregroundStyle(.orange)
            Text("Для работы AI необходимо установить локальную модель.")
                .font(.subheadline)
            Button("Установить модель") {
                router.showModelScreen = true
            }
            .buttonStyle(PrimaryButtonStyle())
        }
        .glassCard()
    }
}

struct ErrorBanner: View {
    let message: String
    @Environment(AppRouter.self) private var router

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            Label("Проблема с моделью", systemImage: "exclamationmark.triangle.fill")
                .font(.headline)
                .foregroundStyle(.orange)
            Text(message)
                .font(.footnote)
            Button("Открыть настройки модели") {
                router.showModelScreen = true
            }
            .font(.footnote.weight(.semibold))
        }
        .glassCard()
    }
}

// MARK: - Проверка распознанного текста

struct OCRResultSheet: View {
    enum Action { case solve, ask }

    let image: UIImage?
    @Binding var text: String
    var onAction: (Action) -> Void
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 16) {
                    if let image {
                        Image(uiImage: image)
                            .resizable()
                            .scaledToFit()
                            .frame(maxHeight: 220)
                            .clipShape(RoundedRectangle(cornerRadius: 18, style: .continuous))
                            .frame(maxWidth: .infinity)
                    }

                    Text("Проверьте и при необходимости исправьте распознанный текст:")
                        .font(.subheadline)
                        .foregroundStyle(.secondary)

                    TextEditor(text: $text)
                        .frame(minHeight: 180)
                        .padding(8)
                        .scrollContentBackground(.hidden)
                        .background(.thinMaterial, in: RoundedRectangle(cornerRadius: 16, style: .continuous))

                    Label("Формулы с дробями и корнями распознаются как обычный текст — проверьте их.", systemImage: "info.circle")
                        .font(.footnote)
                        .foregroundStyle(.secondary)

                    Button {
                        onAction(.solve)
                    } label: {
                        Label("Решить задание", systemImage: "checklist")
                    }
                    .buttonStyle(PrimaryButtonStyle())

                    Button {
                        onAction(.ask)
                    } label: {
                        Label("Задать как вопрос", systemImage: "bubble.left")
                    }
                    .buttonStyle(GlassButtonStyle())
                }
                .padding()
            }
            .navigationTitle("Распознанный текст")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Отмена") { dismiss() }
                }
            }
        }
        .presentationDetents([.large])
    }
}

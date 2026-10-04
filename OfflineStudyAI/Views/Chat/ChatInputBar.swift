import SwiftUI

struct ChatInputBar: View {
    @Binding var text: String
    let mode: ChatMode
    let isGenerating: Bool
    var focus: FocusState<Bool>.Binding
    var onSend: () -> Void
    var onStop: () -> Void
    var onCamera: () -> Void
    var onLibrary: () -> Void

    private var canSend: Bool {
        !text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
    }

    var body: some View {
        HStack(alignment: .bottom, spacing: 10) {
            Menu {
                Button(action: onCamera) { Label("Сфотографировать задание", systemImage: "camera") }
                Button(action: onLibrary) { Label("Выбрать из Фото", systemImage: "photo.on.rectangle") }
            } label: {
                Image(systemName: "camera.viewfinder")
                    .font(.title3.weight(.semibold))
                    .frame(width: 44, height: 44)
                    .background(.thinMaterial, in: Circle())
            }
            .accessibilityLabel("Фото задания")

            TextField(mode == .solve ? "Введите условие задания…" : "Спросите что угодно по любому предмету…",
                      text: $text, axis: .vertical)
                .lineLimit(1...6)
                .focused(focus)
                .padding(.horizontal, 16)
                .padding(.vertical, 12)
                .background(.thinMaterial, in: RoundedRectangle(cornerRadius: 22, style: .continuous))
                .overlay(RoundedRectangle(cornerRadius: 22, style: .continuous).strokeBorder(.white.opacity(0.2)))

            Button {
                isGenerating ? onStop() : onSend()
            } label: {
                Image(systemName: isGenerating ? "stop.fill" : "arrow.up")
                    .font(.headline.weight(.bold))
                    .foregroundStyle(.white)
                    .frame(width: 44, height: 44)
                    .background(
                        Group {
                            if isGenerating {
                                Circle().fill(Color.red.gradient)
                            } else {
                                Circle().fill(canSend ? AnyShapeStyle(Palette.brandGradient) : AnyShapeStyle(Color.gray.opacity(0.4)))
                            }
                        }
                    )
                    .contentTransition(.symbolEffect(.replace))
            }
            .disabled(!isGenerating && !canSend)
            .buttonStyle(PressableStyle())
            .sensoryFeedback(.impact(weight: .light), trigger: isGenerating)
            .accessibilityLabel(isGenerating ? "Остановить генерацию" : "Отправить")
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 8)
    }
}

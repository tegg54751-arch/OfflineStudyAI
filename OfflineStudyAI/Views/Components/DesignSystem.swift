import SwiftUI

// MARK: - Цвета

enum Palette {
    static let indigo = Color(red: 0.36, green: 0.33, blue: 0.95)
    static let violet = Color(red: 0.55, green: 0.36, blue: 1.0)
    static let teal = Color(red: 0.0, green: 0.72, blue: 0.83)

    static let brandGradient = LinearGradient(
        colors: [indigo, violet, teal],
        startPoint: .topLeading,
        endPoint: .bottomTrailing
    )
}

// MARK: - Фон с мягкими «живыми» пятнами

struct AppBackground: View {
    @Environment(\.colorScheme) private var scheme
    @State private var animate = false

    var body: some View {
        ZStack {
            (scheme == .dark ? Color(red: 0.04, green: 0.04, blue: 0.09) : Color(red: 0.95, green: 0.95, blue: 0.99))
                .ignoresSafeArea()

            Circle()
                .fill(Palette.violet.opacity(scheme == .dark ? 0.35 : 0.22))
                .frame(width: 340, height: 340)
                .blur(radius: 90)
                .offset(x: animate ? 120 : 60, y: animate ? -260 : -320)

            Circle()
                .fill(Palette.teal.opacity(scheme == .dark ? 0.28 : 0.18))
                .frame(width: 300, height: 300)
                .blur(radius: 90)
                .offset(x: animate ? -130 : -80, y: animate ? 220 : 300)

            Circle()
                .fill(Palette.indigo.opacity(scheme == .dark ? 0.25 : 0.14))
                .frame(width: 260, height: 260)
                .blur(radius: 80)
                .offset(x: animate ? -60 : 40, y: animate ? -40 : 30)
        }
        .ignoresSafeArea()
        .onAppear {
            withAnimation(.easeInOut(duration: 9).repeatForever(autoreverses: true)) {
                animate = true
            }
        }
        .accessibilityHidden(true)
    }
}

// MARK: - «Стеклянная» карточка

struct GlassCardModifier: ViewModifier {
    var cornerRadius: CGFloat
    var padding: CGFloat?

    func body(content: Content) -> some View {
        content
            .padding(padding ?? 18)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(.ultraThinMaterial, in: RoundedRectangle(cornerRadius: cornerRadius, style: .continuous))
            .overlay(
                RoundedRectangle(cornerRadius: cornerRadius, style: .continuous)
                    .strokeBorder(
                        LinearGradient(colors: [.white.opacity(0.45), .white.opacity(0.06)],
                                       startPoint: .topLeading, endPoint: .bottomTrailing),
                        lineWidth: 1
                    )
            )
            .shadow(color: .black.opacity(0.08), radius: 20, x: 0, y: 10)
    }
}

extension View {
    func glassCard(cornerRadius: CGFloat = 26, padding: CGFloat? = nil) -> some View {
        modifier(GlassCardModifier(cornerRadius: cornerRadius, padding: padding))
    }
}

// MARK: - Кнопки

/// Лёгкое «вдавливание» при нажатии.
struct PressableStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .scaleEffect(configuration.isPressed ? 0.96 : 1)
            .opacity(configuration.isPressed ? 0.85 : 1)
            .animation(.spring(response: 0.25, dampingFraction: 0.7), value: configuration.isPressed)
    }
}

/// Основная градиентная кнопка.
struct PrimaryButtonStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(.headline)
            .foregroundStyle(.white)
            .frame(maxWidth: .infinity)
            .padding(.vertical, 16)
            .background(Palette.brandGradient, in: RoundedRectangle(cornerRadius: 20, style: .continuous))
            .shadow(color: Palette.violet.opacity(0.35), radius: 14, y: 6)
            .scaleEffect(configuration.isPressed ? 0.97 : 1)
            .animation(.spring(response: 0.25, dampingFraction: 0.7), value: configuration.isPressed)
    }
}

/// Вторичная «стеклянная» кнопка.
struct GlassButtonStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(.subheadline.weight(.semibold))
            .frame(maxWidth: .infinity)
            .padding(.vertical, 14)
            .background(.thinMaterial, in: RoundedRectangle(cornerRadius: 18, style: .continuous))
            .overlay(RoundedRectangle(cornerRadius: 18, style: .continuous).strokeBorder(.white.opacity(0.25)))
            .scaleEffect(configuration.isPressed ? 0.97 : 1)
            .animation(.spring(response: 0.25, dampingFraction: 0.7), value: configuration.isPressed)
    }
}

// MARK: - Мелкие элементы

struct SubjectBadge: View {
    let subject: Subject

    var body: some View {
        HStack(spacing: 4) {
            Text(subject.emoji)
            Text(subject.title)
        }
        .font(.caption.weight(.semibold))
        .padding(.horizontal, 10)
        .padding(.vertical, 5)
        .background(subject.tint.opacity(0.15), in: Capsule())
        .foregroundStyle(subject.tint)
    }
}

struct StatusDot: View {
    let color: Color
    @State private var pulse = false

    var body: some View {
        Circle()
            .fill(color)
            .frame(width: 9, height: 9)
            .overlay(
                Circle()
                    .stroke(color.opacity(0.5), lineWidth: 2)
                    .scaleEffect(pulse ? 2.2 : 1)
                    .opacity(pulse ? 0 : 1)
            )
            .onAppear {
                withAnimation(.easeOut(duration: 1.6).repeatForever(autoreverses: false)) { pulse = true }
            }
    }
}

struct SectionTitle: View {
    let text: String
    var body: some View {
        Text(text)
            .font(.title3.weight(.bold))
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.horizontal, 4)
    }
}

/// Строка «ключ — значение» для информационных карточек.
struct InfoRow: View {
    let title: String
    let value: String
    var icon: String?

    var body: some View {
        HStack(alignment: .firstTextBaseline) {
            if let icon {
                Image(systemName: icon)
                    .foregroundStyle(.secondary)
                    .frame(width: 22)
            }
            Text(title)
                .foregroundStyle(.secondary)
            Spacer(minLength: 12)
            Text(value)
                .fontWeight(.medium)
                .multilineTextAlignment(.trailing)
        }
        .font(.subheadline)
    }
}

/// Строка статуса с цветной точкой (экран «Офлайн-режим»).
struct StatusRow: View {
    let ok: Bool
    let title: String
    var subtitle: String?

    var body: some View {
        HStack(spacing: 14) {
            Image(systemName: ok ? "checkmark.circle.fill" : "xmark.circle.fill")
                .font(.title2)
                .foregroundStyle(ok ? Color.green : Color.red)
                .symbolEffect(.bounce, value: ok)
            VStack(alignment: .leading, spacing: 2) {
                Text(title).font(.body.weight(.semibold))
                if let subtitle {
                    Text(subtitle).font(.footnote).foregroundStyle(.secondary)
                }
            }
            Spacer()
        }
    }
}

/// Простая раскладка «в строку с переносом» для чипов.
struct FlowLayout: Layout {
    var spacing: CGFloat = 8

    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) -> CGSize {
        let maxWidth = proposal.width ?? .infinity
        var x: CGFloat = 0
        var y: CGFloat = 0
        var rowHeight: CGFloat = 0
        var widest: CGFloat = 0
        for subview in subviews {
            let size = subview.sizeThatFits(.unspecified)
            if x + size.width > maxWidth, x > 0 {
                x = 0
                y += rowHeight + spacing
                rowHeight = 0
            }
            x += size.width + spacing
            widest = max(widest, x - spacing)
            rowHeight = max(rowHeight, size.height)
        }
        return CGSize(width: proposal.width ?? widest, height: y + rowHeight)
    }

    func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) {
        var x = bounds.minX
        var y = bounds.minY
        var rowHeight: CGFloat = 0
        for subview in subviews {
            let size = subview.sizeThatFits(.unspecified)
            if x + size.width > bounds.maxX, x > bounds.minX {
                x = bounds.minX
                y += rowHeight + spacing
                rowHeight = 0
            }
            subview.place(at: CGPoint(x: x, y: y), proposal: ProposedViewSize(size))
            x += size.width + spacing
            rowHeight = max(rowHeight, size.height)
        }
    }
}

/// Индикатор генерации: три «дышащие» точки.
struct TypingIndicator: View {
    var body: some View {
        TimelineView(.animation) { context in
            let t = context.date.timeIntervalSinceReferenceDate
            HStack(spacing: 6) {
                ForEach(0..<3, id: \.self) { index in
                    let value = (sin(t * 5 - Double(index) * 0.9) + 1) / 2
                    Circle()
                        .fill(Palette.brandGradient)
                        .frame(width: 8, height: 8)
                        .scaleEffect(0.7 + 0.4 * value)
                        .opacity(0.45 + 0.55 * value)
                }
                Text("Думаю…")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
                    .padding(.leading, 4)
            }
        }
        .accessibilityLabel("Модель генерирует ответ")
    }
}

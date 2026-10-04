import SwiftUI
import Observation

enum AppTheme: String, CaseIterable, Identifiable {
    case system, light, dark
    var id: String { rawValue }
    var title: String {
        switch self {
        case .system: "Как в системе"
        case .light: "Светлая"
        case .dark: "Тёмная"
        }
    }
    var colorScheme: ColorScheme? {
        switch self {
        case .system: nil
        case .light: .light
        case .dark: .dark
        }
    }
}

enum TextSizeOption: String, CaseIterable, Identifiable {
    case system, small, medium, large, extraLarge
    var id: String { rawValue }
    var title: String {
        switch self {
        case .system: "Как в системе"
        case .small: "Мелкий"
        case .medium: "Обычный"
        case .large: "Крупный"
        case .extraLarge: "Очень крупный"
        }
    }
    var range: ClosedRange<DynamicTypeSize> {
        switch self {
        case .system: .xSmall ... .accessibility3
        case .small: .small ... .small
        case .medium: .large ... .large
        case .large: .xLarge ... .xLarge
        case .extraLarge: .xxxLarge ... .xxxLarge
        }
    }
}

enum AnswerStyle: String, CaseIterable, Identifiable {
    case answerOnly, short, detailed
    var id: String { rawValue }
    var title: String {
        switch self {
        case .answerOnly: "Только ответ"
        case .short: "Кратко"
        case .detailed: "Подробно"
        }
    }
}

enum GradeLevel: String, CaseIterable, Identifiable {
    case grade5to7, grade8to9, grade10to11
    var id: String { rawValue }
    var title: String {
        switch self {
        case .grade5to7: "5–7 класс"
        case .grade8to9: "8–9 класс"
        case .grade10to11: "10–11 класс"
        }
    }
    var promptDescription: String {
        switch self {
        case .grade5to7: "5–7 классе (10–13 лет). Объясняй очень просто, с примерами из жизни, без сложных терминов"
        case .grade8to9: "8–9 классе (14–15 лет). Объясняй понятно, вводи термины с пояснениями, уровень подготовки к ОГЭ"
        case .grade10to11: "10–11 классе (16–17 лет). Можно использовать научные термины, уровень подготовки к ЕГЭ"
        }
    }
}

/// Все пользовательские настройки. Хранятся в UserDefaults — локально.
@Observable
final class AppSettings {
    private let defaults = UserDefaults.standard

    var theme: AppTheme { didSet { defaults.set(theme.rawValue, forKey: Keys.theme) } }
    var textSize: TextSizeOption { didSet { defaults.set(textSize.rawValue, forKey: Keys.textSize) } }
    var answerStyle: AnswerStyle { didSet { defaults.set(answerStyle.rawValue, forKey: Keys.answerStyle) } }
    var grade: GradeLevel { didSet { defaults.set(grade.rawValue, forKey: Keys.grade) } }

    /// Размер контекста модели в токенах (память под KV-кэш растёт линейно).
    var contextSize: Int { didSet { defaults.set(contextSize, forKey: Keys.contextSize) } }
    /// Максимальная длина одного ответа в токенах.
    var maxAnswerTokens: Int { didSet { defaults.set(maxAnswerTokens, forKey: Keys.maxAnswerTokens) } }
    /// «Креативность» (temperature). Для учёбы лучше низкая.
    var temperature: Double { didSet { defaults.set(temperature, forKey: Keys.temperature) } }
    /// Выгружать модель из памяти, когда приложение уходит в фон.
    var unloadInBackground: Bool { didSet { defaults.set(unloadInBackground, forKey: Keys.unloadInBackground) } }
    /// Загружать модель сразу при запуске приложения.
    var preloadOnLaunch: Bool { didSet { defaults.set(preloadOnLaunch, forKey: Keys.preloadOnLaunch) } }

    init() {
        theme = AppTheme(rawValue: defaults.string(forKey: Keys.theme) ?? "") ?? .system
        textSize = TextSizeOption(rawValue: defaults.string(forKey: Keys.textSize) ?? "") ?? .system
        answerStyle = AnswerStyle(rawValue: defaults.string(forKey: Keys.answerStyle) ?? "") ?? .short
        grade = GradeLevel(rawValue: defaults.string(forKey: Keys.grade) ?? "") ?? .grade8to9
        let ctx = defaults.integer(forKey: Keys.contextSize)
        contextSize = ctx > 0 ? ctx : 2048
        let maxTok = defaults.integer(forKey: Keys.maxAnswerTokens)
        maxAnswerTokens = maxTok > 0 ? maxTok : 900
        temperature = defaults.object(forKey: Keys.temperature) as? Double ?? 0.1
        unloadInBackground = defaults.object(forKey: Keys.unloadInBackground) as? Bool ?? false
        preloadOnLaunch = defaults.object(forKey: Keys.preloadOnLaunch) as? Bool ?? true
    }

    private enum Keys {
        static let theme = "settings.theme"
        static let textSize = "settings.textSize"
        static let answerStyle = "settings.answerStyle"
        static let grade = "settings.grade"
        static let contextSize = "settings.contextSize"
        static let maxAnswerTokens = "settings.maxAnswerTokens"
        static let temperature = "settings.temperature"
        static let unloadInBackground = "settings.unloadInBackground"
        static let preloadOnLaunch = "settings.preloadOnLaunch"
    }
}

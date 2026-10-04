import SwiftUI

/// Предмет вопроса. Все предметы обслуживает ОДИН универсальный чат —
/// предмет определяется автоматически и используется только как метка
/// в истории и как подсказка для модели.
enum Subject: String, Codable, CaseIterable, Identifiable, Hashable {
    case biology
    case geography
    case chemistry
    case math
    case physics
    case history
    case russian
    case literature
    case english
    case informatics
    case social
    case general

    var id: String { rawValue }

    var title: String {
        switch self {
        case .biology: "Биология"
        case .geography: "География"
        case .chemistry: "Химия"
        case .math: "Математика"
        case .physics: "Физика"
        case .history: "История"
        case .russian: "Русский язык"
        case .literature: "Литература"
        case .english: "Английский"
        case .informatics: "Информатика"
        case .social: "Обществознание"
        case .general: "Общие знания"
        }
    }

    var emoji: String {
        switch self {
        case .biology: "🧬"
        case .geography: "🌍"
        case .chemistry: "⚗️"
        case .math: "📐"
        case .physics: "🔭"
        case .history: "📚"
        case .russian: "🇷🇺"
        case .literature: "📖"
        case .english: "🇬🇧"
        case .informatics: "💻"
        case .social: "⚖️"
        case .general: "🧠"
        }
    }

    var tint: Color {
        switch self {
        case .biology: .green
        case .geography: .teal
        case .chemistry: .purple
        case .math: .blue
        case .physics: .indigo
        case .history: .brown
        case .russian: .red
        case .literature: .orange
        case .english: .cyan
        case .informatics: .mint
        case .social: .pink
        case .general: .gray
        }
    }

    /// Пример вопроса — подставляется в поле ввода по нажатию на чип предмета.
    var sampleQuestion: String {
        switch self {
        case .biology: "Что такое фотосинтез?"
        case .geography: "Почему на Земле меняются времена года?"
        case .chemistry: "Чем отличается кислота от щёлочи?"
        case .math: "Как решать квадратные уравнения?"
        case .physics: "Что такое сила трения?"
        case .history: "Почему началась Первая мировая война?"
        case .russian: "Чем причастие отличается от деепричастия?"
        case .literature: "О чём роман «Капитанская дочка»?"
        case .english: "Когда используется Present Perfect?"
        case .informatics: "Что такое алгоритм?"
        case .social: "Что такое Конституция?"
        case .general: "Почему небо голубое?"
        }
    }

    /// Предметы, которые показываются на главном экране.
    static var showcase: [Subject] {
        [.biology, .geography, .chemistry, .math, .physics, .history, .russian, .literature, .english, .informatics, .social, .general]
    }
}

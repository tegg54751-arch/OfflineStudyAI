import SwiftUI
import Observation

enum AppTab: Hashable {
    case home, chat, history, settings
}

enum PhotoSource: Identifiable {
    case camera, library
    var id: Int { self == .camera ? 0 : 1 }
}

/// Навигация между вкладками и «отложенные» действия
/// (например, нажали «Фото задания» на главном экране → открыть камеру в чате).
@MainActor
@Observable
final class AppRouter {
    var tab: AppTab = .home
    var pendingPhotoSource: PhotoSource?
    var showModelScreen = false
}

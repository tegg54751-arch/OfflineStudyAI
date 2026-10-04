import Foundation
import Network
import Observation

/// Только ПОКАЗЫВАЕТ состояние сети на экране «Офлайн-режим».
/// NWPathMonitor читает состояние сетевых интерфейсов и не отправляет
/// никаких запросов. Генерация ответов от сети никак не зависит.
@Observable
final class NetworkMonitor {
    private(set) var isConnected = false
    private(set) var usesWiFi = false
    private(set) var usesCellular = false

    private let monitor = NWPathMonitor()

    init() {
        monitor.pathUpdateHandler = { [weak self] path in
            let connected = path.status == .satisfied
            let wifi = path.usesInterfaceType(.wifi)
            let cellular = path.usesInterfaceType(.cellular)
            DispatchQueue.main.async {
                self?.isConnected = connected
                self?.usesWiFi = wifi
                self?.usesCellular = cellular
            }
        }
        monitor.start(queue: DispatchQueue(label: "offline-study-ai.network-monitor"))
    }

    deinit {
        monitor.cancel()
    }

    var statusDescription: String {
        if !isConnected { return "Сети нет (авиарежим или нет сигнала)" }
        if usesWiFi { return "Подключён Wi-Fi" }
        if usesCellular { return "Подключена мобильная сеть" }
        return "Есть подключение"
    }
}

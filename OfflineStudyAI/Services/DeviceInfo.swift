import Foundation
import os

enum Format {
    static func bytes(_ value: UInt64) -> String {
        let formatter = ByteCountFormatter()
        formatter.countStyle = .memory
        formatter.allowedUnits = [.useMB, .useGB]
        return formatter.string(fromByteCount: Int64(clamping: value))
    }

    static func bytes(_ value: Int64) -> String {
        bytes(UInt64(max(0, value)))
    }

    static func params(_ count: UInt64) -> String {
        if count >= 1_000_000_000 {
            return String(format: "%.1f млрд", Double(count) / 1e9)
        }
        return String(format: "%.0f млн", Double(count) / 1e6)
    }

    static func date(_ date: Date) -> String {
        date.formatted(.dateTime.day().month(.abbreviated).hour().minute())
    }
}

/// Сведения о памяти устройства — всё читается локально через системные API.
enum DeviceInfo {
    /// Физическая RAM устройства.
    static var totalRAM: UInt64 {
        ProcessInfo.processInfo.physicalMemory
    }

    /// Сколько ещё памяти iOS разрешит выделить этому приложению до принудительного завершения.
    /// На симуляторе возвращает 0 (API работает только на устройстве).
    static var availableAppMemory: UInt64 {
        UInt64(max(0, os_proc_available_memory()))
    }

    /// Сколько памяти приложение использует сейчас (phys_footprint — то, что видит iOS).
    static var appFootprint: UInt64 {
        var info = task_vm_info_data_t()
        var count = mach_msg_type_number_t(MemoryLayout<task_vm_info_data_t>.size / MemoryLayout<integer_t>.size)
        let result = withUnsafeMutablePointer(to: &info) { pointer in
            pointer.withMemoryRebound(to: integer_t.self, capacity: Int(count)) {
                task_info(mach_task_self_, task_flavor_t(TASK_VM_INFO), $0, &count)
            }
        }
        return result == KERN_SUCCESS ? UInt64(info.phys_footprint) : 0
    }

    /// Свободное место на диске.
    static var freeDiskSpace: Int64 {
        let url = URL(fileURLWithPath: NSHomeDirectory())
        let values = try? url.resourceValues(forKeys: [.volumeAvailableCapacityForImportantUsageKey])
        return values?.volumeAvailableCapacityForImportantUsage ?? 0
    }

    /// Грубая оценка памяти, нужной для модели: веса + KV-кэш + рабочие буферы.
    static func estimatedMemory(forModelFileSize fileSize: UInt64, contextSize: Int) -> UInt64 {
        // KV-кэш для 1.5–3B моделей ~ 30–110 КБ на токен (с GQA меньше). Берём с запасом.
        let kvCache = UInt64(contextSize) * 50 * 1024
        let workBuffers: UInt64 = 200 * 1024 * 1024
        return fileSize + kvCache + workBuffers
    }
}

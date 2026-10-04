import UIKit
import Vision

/// Одна распознанная строка.
struct OCRLine: Identifiable, Hashable {
    let id = UUID()
    var text: String
    var confidence: Float
    /// Похоже на запись ручкой: цветные чернила (синие/красные/зелёные) или низкая уверенность.
    var isLikelyHandwritten: Bool
    var isIncluded: Bool
}

/// Распознавание текста на фото полностью на устройстве (Apple Vision).
/// VNRecognizeTextRequest не использует сеть; русский язык поддерживается с iOS 16.
///
/// Записи ручкой поверх задания (ответы ученика, пометки учителя) сбивают модель.
/// Поэтому для каждой строки определяется цвет «чернил»: печатный текст чёрный/серый,
/// а синяя, фиолетовая, красная или зелёная ручка — цветная. Такие строки
/// по умолчанию исключаются, пользователь может вернуть их одним нажатием.
enum OCRService {
    enum OCRError: LocalizedError {
        case invalidImage
        case nothingFound

        var errorDescription: String? {
            switch self {
            case .invalidImage: "Не удалось прочитать изображение."
            case .nothingFound: "Текст на фото не найден. Попробуйте сфотографировать ближе и при хорошем освещении."
            }
        }
    }

    static func recognizeLines(in image: UIImage) async throws -> [OCRLine] {
        // Приводим фото к ориентации «вверх», чтобы координаты Vision и пиксели совпадали.
        let prepared = normalized(image, maxSide: 3000)
        guard let cgImage = prepared.cgImage else { throw OCRError.invalidImage }

        let lines: [OCRLine] = try await Task.detached(priority: .userInitiated) {
            let request = VNRecognizeTextRequest()
            request.recognitionLevel = .accurate
            request.usesLanguageCorrection = true
            request.recognitionLanguages = ["ru-RU", "en-US"]
            request.minimumTextHeight = 0.012

            let handler = VNImageRequestHandler(cgImage: cgImage, orientation: .up, options: [:])
            try handler.perform([request])

            let sampler = InkSampler(cgImage: cgImage)
            return Self.assembleLines(request.results ?? [], sampler: sampler)
        }.value

        guard lines.contains(where: { !$0.text.trimmingCharacters(in: .whitespaces).isEmpty }) else {
            throw OCRError.nothingFound
        }
        return lines
    }

    static func text(from lines: [OCRLine]) -> String {
        lines.filter(\.isIncluded).map(\.text).joined(separator: "\n")
    }

    // MARK: - Сборка строк

    private struct Fragment {
        let text: String
        let box: CGRect
        let confidence: Float
        let ink: InkSampler.Ink
    }

    private static func assembleLines(_ observations: [VNRecognizedTextObservation], sampler: InkSampler?) -> [OCRLine] {
        let fragments = observations.compactMap { obs -> Fragment? in
            guard let candidate = obs.topCandidates(1).first else { return nil }
            let ink = sampler?.ink(in: obs.boundingBox) ?? .unknown
            return Fragment(text: candidate.string, box: obs.boundingBox, confidence: candidate.confidence, ink: ink)
        }
        // В Vision ось Y направлена вверх.
        let sorted = fragments.sorted { $0.box.midY > $1.box.midY }

        var rows: [[Fragment]] = []
        for fragment in sorted {
            if let last = rows.indices.last,
               let reference = rows[last].first,
               abs(reference.box.midY - fragment.box.midY) < min(reference.box.height, fragment.box.height) * 0.5 {
                rows[last].append(fragment)
            } else {
                rows.append([fragment])
            }
        }

        return layoutLines(rows)
    }

    /// Превращает строки фото в текст. Особый случай — таблицы в два столбца
    /// (задания «установите соответствие»): если склеить ячейки построчно, получится
    /// «1. Целлюлозная стенка А. Настоящие грибы», и модель решит, что 1 = А.
    /// Поэтому для таблиц выводим сначала весь левый столбец, затем весь правый.
    private static func layoutLines(_ rows: [[Fragment]]) -> [OCRLine] {
        struct Row {
            var printed: [Fragment]
            var handwritten: [Fragment]
            /// Ячейки строки: группы фрагментов, разделённые большим промежутком.
            var cells: [[Fragment]]
        }

        let prepared: [Row] = rows.map { row in
            let ordered = row.sorted { $0.box.minX < $1.box.minX }
            let printed = ordered.filter { !isHandwritten($0) }
            var cells: [[Fragment]] = []
            for fragment in printed {
                if let lastCell = cells.last, let previous = lastCell.last, fragment.box.minX - previous.box.maxX < 0.04 {
                    cells[cells.count - 1].append(fragment)
                } else {
                    cells.append([fragment])
                }
            }
            return Row(printed: printed, handwritten: ordered.filter { isHandwritten($0) }, cells: cells)
        }

        // Ищем непрерывный участок таблицы в два столбца.
        let twoColumnIndices = prepared.indices.filter { prepared[$0].cells.count == 2 }
        var tableRange: ClosedRange<Int>?
        var splitX: CGFloat = 0.5
        if twoColumnIndices.count >= 2, let first = twoColumnIndices.first, let last = twoColumnIndices.last {
            let rightStarts = twoColumnIndices.compactMap { prepared[$0].cells[1].first?.box.minX }
            let leftEnds = twoColumnIndices.compactMap { prepared[$0].cells[0].last?.box.maxX }
            splitX = ((rightStarts.min() ?? 0.5) + (leftEnds.max() ?? 0.5)) / 2
            // Разрешаем внутри таблицы строки-продолжения из одной ячейки.
            let inner = prepared[first...last]
            if inner.allSatisfy({ $0.cells.count <= 2 }) {
                tableRange = first...last
            }
        }

        func line(_ fragments: [Fragment], handwritten: Bool) -> OCRLine? {
            guard !fragments.isEmpty else { return nil }
            let confidence = fragments.map(\.confidence).reduce(0, +) / Float(fragments.count)
            return OCRLine(text: fragments.map(\.text).joined(separator: " "),
                           confidence: confidence, isLikelyHandwritten: handwritten, isIncluded: !handwritten)
        }

        var result: [OCRLine] = []
        var index = 0
        while index < prepared.count {
            if let range = tableRange, index == range.lowerBound {
                var left: [OCRLine] = []
                var right: [OCRLine] = []
                var notes: [OCRLine] = []
                for row in prepared[range] {
                    for cell in row.cells {
                        guard let cellLine = line(cell, handwritten: false) else { continue }
                        if (cell.first?.box.minX ?? 0) >= splitX - 0.02 {
                            right.append(cellLine)
                        } else {
                            left.append(cellLine)
                        }
                    }
                    if let note = line(row.handwritten, handwritten: true) { notes.append(note) }
                }
                result += left
                result.append(OCRLine(text: "", confidence: 1, isLikelyHandwritten: false, isIncluded: true))
                result += right
                result += notes
                index = range.upperBound + 1
                continue
            }
            let row = prepared[index]
            if let printedLine = line(row.printed, handwritten: false) { result.append(printedLine) }
            if let note = line(row.handwritten, handwritten: true) { result.append(note) }
            index += 1
        }
        return result
    }

    private static func isHandwritten(_ fragment: Fragment) -> Bool {
        switch fragment.ink {
        case .colored: return true
        case .dark: return fragment.confidence < 0.3
        case .unknown: return fragment.confidence < 0.3
        }
    }

    // MARK: - Подготовка изображения

    private static func normalized(_ image: UIImage, maxSide: CGFloat) -> UIImage {
        let size = image.size
        let longest = max(size.width, size.height)
        let scale = longest > maxSide ? maxSide / longest : 1
        if scale == 1 && image.imageOrientation == .up { return image }
        let newSize = CGSize(width: (size.width * scale).rounded(), height: (size.height * scale).rounded())
        let format = UIGraphicsImageRendererFormat()
        format.scale = 1
        return UIGraphicsImageRenderer(size: newSize, format: format).image { _ in
            image.draw(in: CGRect(origin: .zero, size: newSize))
        }
    }
}

/// Определяет цвет «чернил» в прямоугольнике строки по уменьшенной копии фото.
private struct InkSampler {
    enum Ink {
        case dark      // чёрный/серый — печатный текст или простой карандаш
        case colored   // синий, фиолетовый, красный, зелёный — ручка
        case unknown
    }

    private let pixels: [UInt8]
    private let width: Int
    private let height: Int

    init?(cgImage: CGImage, maxSide: Int = 1200) {
        let longest = max(cgImage.width, cgImage.height)
        let scale = longest > maxSide ? Double(maxSide) / Double(longest) : 1
        let w = max(1, Int(Double(cgImage.width) * scale))
        let h = max(1, Int(Double(cgImage.height) * scale))
        var buffer = [UInt8](repeating: 0, count: w * h * 4)
        let drawn: Bool = buffer.withUnsafeMutableBytes { raw in
            guard let context = CGContext(
                data: raw.baseAddress, width: w, height: h, bitsPerComponent: 8,
                bytesPerRow: w * 4, space: CGColorSpaceCreateDeviceRGB(),
                bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue
            ) else { return false }
            context.interpolationQuality = .medium
            context.draw(cgImage, in: CGRect(x: 0, y: 0, width: w, height: h))
            return true
        }
        guard drawn else { return nil }
        width = w
        height = h
        pixels = buffer
    }

    /// `box` — нормализованные координаты Vision (начало в левом нижнем углу).
    func ink(in box: CGRect) -> Ink {
        let x0 = max(0, Int(box.minX * Double(width)))
        let x1 = min(width - 1, Int(box.maxX * Double(width)))
        // Строка 0 буфера — верх изображения.
        let y0 = max(0, Int((1 - box.maxY) * Double(height)))
        let y1 = min(height - 1, Int((1 - box.minY) * Double(height)))
        guard x1 > x0, y1 > y0 else { return .unknown }

        // Яркость фона — светлый процентиль; чернила — пиксели заметно темнее фона.
        var lumas: [Int] = []
        lumas.reserveCapacity((x1 - x0 + 1) * (y1 - y0 + 1))
        for y in y0...y1 {
            for x in x0...x1 {
                let i = (y * width + x) * 4
                lumas.append((Int(pixels[i]) * 299 + Int(pixels[i + 1]) * 587 + Int(pixels[i + 2]) * 114) / 1000)
            }
        }
        let sortedLumas = lumas.sorted()
        let background = sortedLumas[Int(Double(sortedLumas.count - 1) * 0.85)]
        let threshold = background - max(40, background / 4)

        var count = 0, sumR = 0, sumG = 0, sumB = 0
        var index = 0
        for y in y0...y1 {
            for x in x0...x1 {
                defer { index += 1 }
                guard lumas[index] < threshold else { continue }
                let i = (y * width + x) * 4
                sumR += Int(pixels[i]); sumG += Int(pixels[i + 1]); sumB += Int(pixels[i + 2])
                count += 1
            }
        }
        guard count >= 12 else { return .unknown }

        let r = sumR / count, g = sumG / count, b = sumB / count
        let maxC = max(r, g, b), minC = min(r, g, b)
        // Насыщенность чернил относительно их яркости.
        let saturation = maxC > 0 ? Double(maxC - minC) / Double(maxC) : 0
        let blueish = b - max(r, g)
        let reddish = r - max(g, b)
        let greenish = g - max(r, b)
        if saturation > 0.28 && (blueish > 18 || reddish > 28 || greenish > 22) {
            return .colored
        }
        return .dark
    }
}

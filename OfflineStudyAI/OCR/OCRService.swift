import UIKit
import Vision

/// Распознавание текста на фото полностью на устройстве (Apple Vision).
/// VNRecognizeTextRequest не использует сеть; русский язык поддерживается с iOS 16.
///
/// Ограничение: Vision распознаёт строки текста, но не «понимает» вёрстку формул
/// (дроби в несколько этажей, корни). Поэтому после распознавания текст
/// показывается пользователю для проверки и правки.
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

    static func recognizeText(in image: UIImage) async throws -> String {
        let prepared = downscaled(image, maxSide: 3000)
        guard let cgImage = prepared.cgImage else { throw OCRError.invalidImage }
        let orientation = CGImagePropertyOrientation(prepared.imageOrientation)

        let text: String = try await Task.detached(priority: .userInitiated) {
            let request = VNRecognizeTextRequest()
            request.recognitionLevel = .accurate
            request.usesLanguageCorrection = true
            request.recognitionLanguages = ["ru-RU", "en-US"]
            request.minimumTextHeight = 0.012

            let handler = VNImageRequestHandler(cgImage: cgImage, orientation: orientation, options: [:])
            try handler.perform([request])

            let observations = request.results ?? []
            return Self.assembleLines(observations)
        }.value

        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { throw OCRError.nothingFound }
        return trimmed
    }

    /// Сортирует найденные строки сверху вниз и слева направо,
    /// склеивая фрагменты одной строки.
    private static func assembleLines(_ observations: [VNRecognizedTextObservation]) -> String {
        struct Fragment {
            let text: String
            let box: CGRect
        }
        let fragments = observations.compactMap { obs -> Fragment? in
            guard let candidate = obs.topCandidates(1).first else { return nil }
            return Fragment(text: candidate.string, box: obs.boundingBox)
        }
        // В Vision ось Y направлена вверх.
        let sorted = fragments.sorted { $0.box.midY > $1.box.midY }

        var lines: [[Fragment]] = []
        for fragment in sorted {
            if let lastIndex = lines.indices.last,
               let reference = lines[lastIndex].first,
               abs(reference.box.midY - fragment.box.midY) < min(reference.box.height, fragment.box.height) * 0.5 {
                lines[lastIndex].append(fragment)
            } else {
                lines.append([fragment])
            }
        }
        return lines
            .map { $0.sorted { $0.box.minX < $1.box.minX }.map(\.text).joined(separator: " ") }
            .joined(separator: "\n")
    }

    private static func downscaled(_ image: UIImage, maxSide: CGFloat) -> UIImage {
        let size = image.size
        let longest = max(size.width, size.height)
        guard longest > maxSide else { return image }
        let scale = maxSide / longest
        let newSize = CGSize(width: size.width * scale, height: size.height * scale)
        let format = UIGraphicsImageRendererFormat()
        format.scale = 1
        return UIGraphicsImageRenderer(size: newSize, format: format).image { _ in
            image.draw(in: CGRect(origin: .zero, size: newSize))
        }
    }
}

extension CGImagePropertyOrientation {
    init(_ orientation: UIImage.Orientation) {
        switch orientation {
        case .up: self = .up
        case .upMirrored: self = .upMirrored
        case .down: self = .down
        case .downMirrored: self = .downMirrored
        case .left: self = .left
        case .leftMirrored: self = .leftMirrored
        case .right: self = .right
        case .rightMirrored: self = .rightMirrored
        @unknown default: self = .up
        }
    }
}

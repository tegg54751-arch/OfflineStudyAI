import Foundation

/// Реализация `AIService` на базе llama.cpp (GGUF + Metal).
final class LlamaAIService: AIService {
    private let engine = LlamaEngine()

    let backendName = "llama.cpp · GGUF · Metal GPU"

    func load(modelAt url: URL, contextSize: Int) async throws -> LoadedModelInfo {
        try await engine.load(path: url.path, contextSize: contextSize)
    }

    func unload() async {
        await engine.unload()
    }

    func generate(turns: [ChatTurn], params: GenerationParams) -> AsyncThrowingStream<AIStreamEvent, Error> {
        let engine = self.engine
        return AsyncThrowingStream { continuation in
            let task = Task {
                do {
                    let stats = try await engine.generate(turns: turns, params: params) { piece in
                        continuation.yield(.token(piece))
                    }
                    continuation.yield(.finished(stats))
                    continuation.finish()
                } catch {
                    continuation.finish(throwing: error)
                }
            }
            continuation.onTermination = { _ in
                task.cancel()
            }
        }
    }
}

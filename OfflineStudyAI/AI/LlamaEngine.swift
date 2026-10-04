import Foundation
import llama

/// Однократная инициализация бэкенда ggml (Metal на iPhone).
private let llamaBackendInitialized: Void = {
    llama_backend_init()
}()

/// Тонкая обёртка над C-API llama.cpp.
///
/// Это `actor`: все обращения к модели выполняются последовательно,
/// поэтому одновременная генерация двух ответов невозможна по построению.
actor LlamaEngine {
    private var model: OpaquePointer?
    private var context: OpaquePointer?
    private var vocab: OpaquePointer?

    /// Токены, которые уже лежат в KV-кэше (для повторного использования
    /// общего префикса диалога — ускоряет продолжение чата).
    private var cachedTokens: [llama_token] = []

    /// Токены с иероглифами/каной/хангылем, которые запрещены при генерации.
    /// Маленькие многоязычные модели (особенно Qwen) иногда «съезжают» на китайский —
    /// запрет на уровне сэмплера делает это невозможным.
    private var bannedTokens: [llama_logit_bias] = []
    /// Мягкий штраф для токенов из латинских букв (см. GenerationParams.discourageLatin).
    private var latinPenaltyTokens: [llama_logit_bias] = []

    var isLoaded: Bool { model != nil && context != nil }

    // MARK: - Загрузка / выгрузка

    func load(path: String, contextSize: Int) throws -> LoadedModelInfo {
        unload()
        _ = llamaBackendInitialized

        let started = Date()
        let fileName = (path as NSString).lastPathComponent

        var modelParams = llama_model_default_params()
        #if targetEnvironment(simulator)
        modelParams.n_gpu_layers = 0
        #else
        modelParams.n_gpu_layers = 999 // все слои на GPU (Metal)
        #endif

        guard let loadedModel = llama_model_load_from_file(path, modelParams) else {
            throw AIError.modelLoadFailed(fileName)
        }

        let trainContext = Int(llama_model_n_ctx_train(loadedModel))
        var nCtx = max(512, contextSize)
        if trainContext > 0 { nCtx = min(nCtx, trainContext) }

        let threads = Int32(max(1, min(6, ProcessInfo.processInfo.activeProcessorCount - 2)))

        var ctxParams = llama_context_default_params()
        ctxParams.n_ctx = UInt32(nCtx)
        ctxParams.n_batch = 512
        ctxParams.n_ubatch = 512
        ctxParams.n_threads = threads
        ctxParams.n_threads_batch = threads

        guard let ctx = llama_init_from_model(loadedModel, ctxParams) else {
            llama_model_free(loadedModel)
            throw AIError.contextInitFailed
        }

        model = loadedModel
        context = ctx
        vocab = llama_model_get_vocab(loadedModel)
        cachedTokens = []
        (bannedTokens, latinPenaltyTokens) = buildTokenFilters()

        var descBuffer = [CChar](repeating: 0, count: 256)
        _ = llama_model_desc(loadedModel, &descBuffer, descBuffer.count)
        let description = String(cString: descBuffer)

        let fileSize = (try? FileManager.default.attributesOfItem(atPath: path)[.size] as? NSNumber)?.uint64Value ?? 0

        return LoadedModelInfo(
            fileName: fileName,
            description: description,
            fileSizeBytes: fileSize,
            weightsBytes: llama_model_size(loadedModel),
            parameterCount: llama_model_n_params(loadedModel),
            contextSize: Int(llama_n_ctx(ctx)),
            trainContextSize: trainContext,
            hasChatTemplate: llama_model_chat_template(loadedModel, nil) != nil,
            loadSeconds: Date().timeIntervalSince(started)
        )
    }

    func unload() {
        if let context { llama_free(context) }
        if let model { llama_model_free(model) }
        context = nil
        model = nil
        vocab = nil
        cachedTokens = []
        bannedTokens = []
        latinPenaltyTokens = []
    }

    // MARK: - Генерация

    func generate(
        turns: [ChatTurn],
        params: GenerationParams,
        onToken: @Sendable (String) -> Void
    ) throws -> GenerationStats {
        guard let context, let vocab else { throw AIError.modelNotLoaded }

        let nCtx = Int(llama_n_ctx(context))
        let maxNew = max(16, min(params.maxTokens, nCtx / 2))

        // 1. Применяем шаблон чата и при необходимости обрезаем старую историю,
        //    чтобы промпт + ответ поместились в контекст.
        let prompt = try buildPromptTokens(turns: turns, budget: nCtx - maxNew)

        // 2. Повторно используем общий префикс из KV-кэша.
        let memory = llama_get_memory(context)
        var common = 0
        while common < cachedTokens.count, common < prompt.count, cachedTokens[common] == prompt[common] {
            common += 1
        }
        if common == prompt.count { common -= 1 } // последний токен нужно пересчитать ради логитов
        if common < 0 { common = 0 }
        if common < cachedTokens.count {
            if !llama_memory_seq_rm(memory, 0, llama_pos(common), -1) {
                llama_memory_clear(memory, true)
                common = 0
            }
        }
        cachedTokens = Array(prompt.prefix(common))

        // 3. Обрабатываем остаток промпта пачками.
        let nBatch = max(1, Int(llama_n_batch(context)))
        var batch = llama_batch_init(Int32(nBatch), 0, 1)
        defer { llama_batch_free(batch) }

        let promptStart = Date()
        var position = common
        while position < prompt.count {
            if Task.isCancelled { throw CancellationError() }
            let end = min(position + nBatch, prompt.count)
            batch.n_tokens = 0
            for i in position..<end {
                Self.batchAdd(&batch, prompt[i], llama_pos(i), logits: i == prompt.count - 1)
            }
            let rc = llama_decode(context, batch)
            if rc != 0 {
                resetCache()
                throw rc == 1 ? AIError.promptTooLong : AIError.decodeFailed(rc)
            }
            cachedTokens.append(contentsOf: prompt[position..<end])
            position = end
        }
        let promptSeconds = Date().timeIntervalSince(promptStart)

        // 4. Сэмплер.
        guard let sampler = llama_sampler_chain_init(llama_sampler_chain_default_params()) else {
            throw AIError.contextInitFailed
        }
        defer { llama_sampler_free(sampler) }

        let nVocab = llama_vocab_n_tokens(vocab)
        let biases = params.discourageLatin ? bannedTokens + latinPenaltyTokens : bannedTokens
        if !biases.isEmpty {
            biases.withUnsafeBufferPointer { buffer in
                llama_sampler_chain_add(sampler, llama_sampler_init_logit_bias(nVocab, Int32(buffer.count), buffer.baseAddress))
            }
        }
        llama_sampler_chain_add(sampler, llama_sampler_init_penalties(nVocab, 64, params.repeatPenalty, 0, 0))
        if params.temperature <= 0.01 {
            llama_sampler_chain_add(sampler, llama_sampler_init_greedy())
        } else {
            llama_sampler_chain_add(sampler, llama_sampler_init_top_k(params.topK))
            llama_sampler_chain_add(sampler, llama_sampler_init_top_p(params.topP, 1))
            llama_sampler_chain_add(sampler, llama_sampler_init_min_p(params.minP, 1))
            llama_sampler_chain_add(sampler, llama_sampler_init_temp(Float(params.temperature)))
            llama_sampler_chain_add(sampler, llama_sampler_init_dist(UInt32.random(in: 1...UInt32.max)))
        }

        // 5. Цикл генерации с потоковой выдачей текста.
        let genStart = Date()
        var generated = 0
        var stoppedByLimit = false
        var pendingBytes: [UInt8] = []
        var nPos = prompt.count

        while true {
            if Task.isCancelled { break }
            if generated >= maxNew { stoppedByLimit = true; break }
            if nPos >= nCtx - 1 { stoppedByLimit = true; break }

            let token = llama_sampler_sample(sampler, context, -1)
            if llama_vocab_is_eog(vocab, token) { break }

            pendingBytes.append(contentsOf: piece(for: token))
            if let text = Self.takeValidUTF8(&pendingBytes), !text.isEmpty {
                onToken(text)
            }

            batch.n_tokens = 0
            Self.batchAdd(&batch, token, llama_pos(nPos), logits: true)
            let rc = llama_decode(context, batch)
            if rc != 0 {
                resetCache()
                throw AIError.decodeFailed(rc)
            }
            cachedTokens.append(token)
            nPos += 1
            generated += 1
        }

        if !pendingBytes.isEmpty {
            onToken(String(decoding: pendingBytes, as: UTF8.self))
        }

        return GenerationStats(
            promptTokens: prompt.count,
            reusedPromptTokens: common,
            generatedTokens: generated,
            promptSeconds: promptSeconds,
            generationSeconds: Date().timeIntervalSince(genStart),
            stoppedByLimit: stoppedByLimit
        )
    }

    /// Сбрасывает KV-кэш (например, после ошибки).
    func resetCache() {
        if let context { llama_memory_clear(llama_get_memory(context), true) }
        cachedTokens = []
    }

    // MARK: - Запрет иероглифов

    /// Находит в словаре все токены, содержащие начало символа из диапазона U+3000…U+DFFF
    /// (китайские/японские иероглифы, кана, хангыль, CJK-пунктуация).
    /// В UTF-8 такие символы начинаются с байтов 0xE3…0xED, а кириллица — с 0xD0/0xD1,
    /// поэтому русский текст, латиница и математические символы (0xE2: →, √, —) не затрагиваются.
    private func buildTokenFilters() -> (banned: [llama_logit_bias], latin: [llama_logit_bias]) {
        guard let vocab else { return ([], []) }
        let count = llama_vocab_n_tokens(vocab)
        var banned: [llama_logit_bias] = []
        var latin: [llama_logit_bias] = []
        banned.reserveCapacity(30_000)
        latin.reserveCapacity(60_000)
        for token in 0..<count {
            if llama_vocab_is_control(vocab, token) || llama_vocab_is_eog(vocab, token) { continue }
            let bytes = piece(for: token)
            if bytes.contains(where: { $0 >= 0xE3 && $0 <= 0xED }) {
                banned.append(llama_logit_bias(token: token, bias: -Float.infinity))
                continue
            }
            // Слово (или кусок слова) только из латинских букв, минимум 2 буквы: «synth», " the".
            let letters = bytes.drop(while: { $0 == 0x20 })
            if letters.count >= 2, letters.allSatisfy({ ($0 >= 0x41 && $0 <= 0x5A) || ($0 >= 0x61 && $0 <= 0x7A) }) {
                latin.append(llama_logit_bias(token: token, bias: -6))
            }
        }
        return (banned, latin)
    }

    // MARK: - Промпт

    private func buildPromptTokens(turns: [ChatTurn], budget: Int) throws -> [llama_token] {
        var working = turns
        while true {
            let text = applyChatTemplate(working)
            let tokens = tokenize(text)
            if tokens.count <= budget { return tokens }

            let nonSystem = working.indices.filter { working[$0].role != .system }
            if nonSystem.count > 1 {
                // Удаляем самое старое сообщение диалога.
                working.remove(at: nonSystem[0])
            } else if let last = nonSystem.last {
                // Остался только один (длинный) вопрос — обрезаем его.
                let content = working[last].content
                guard content.count > 200 else { throw AIError.promptTooLong }
                working[last].content = String(content.prefix(content.count * 3 / 4))
            } else {
                throw AIError.promptTooLong
            }
        }
    }

    private func applyChatTemplate(_ turns: [ChatTurn]) -> String {
        guard let model, let template = llama_model_chat_template(model, nil) else {
            return Self.chatMLTemplate(turns)
        }

        let roles = turns.map { strdup($0.role.rawValue) }
        let contents = turns.map { strdup($0.content) }
        defer {
            roles.forEach { free($0) }
            contents.forEach { free($0) }
        }
        var messages: [llama_chat_message] = zip(roles, contents).map { role, content in
            llama_chat_message(role: UnsafePointer(role), content: UnsafePointer(content))
        }

        let estimated = turns.reduce(0) { $0 + $1.content.utf8.count } * 2 + 1024
        var buffer = [CChar](repeating: 0, count: estimated)
        var length = llama_chat_apply_template(template, &messages, messages.count, true, &buffer, Int32(buffer.count))
        if length > Int32(buffer.count) {
            buffer = [CChar](repeating: 0, count: Int(length) + 1)
            length = llama_chat_apply_template(template, &messages, messages.count, true, &buffer, Int32(buffer.count))
        }
        guard length > 0 else { return Self.chatMLTemplate(turns) }

        let bytes = buffer.prefix(Int(length)).map { UInt8(bitPattern: $0) }
        return String(decoding: bytes, as: UTF8.self)
    }

    /// Запасной шаблон ChatML (Qwen и многие другие модели).
    private static func chatMLTemplate(_ turns: [ChatTurn]) -> String {
        var result = ""
        for turn in turns {
            result += "<|im_start|>\(turn.role.rawValue)\n\(turn.content)<|im_end|>\n"
        }
        result += "<|im_start|>assistant\n"
        return result
    }

    // MARK: - Токены

    private func tokenize(_ text: String) -> [llama_token] {
        guard let vocab else { return [] }

        // Добавляем BOS, только если модель его требует и шаблон его ещё не вставил.
        var addSpecial = llama_vocab_get_add_bos(vocab)
        let bos = llama_vocab_bos(vocab)
        if addSpecial, bos >= 0, let bosText = llama_vocab_get_text(vocab, bos) {
            if text.hasPrefix(String(cString: bosText)) { addSpecial = false }
        }

        let byteCount = Int32(text.utf8.count)
        var tokens = [llama_token](repeating: 0, count: Int(byteCount) + 16)
        var count = llama_tokenize(vocab, text, byteCount, &tokens, Int32(tokens.count), addSpecial, true)
        if count < 0 {
            tokens = [llama_token](repeating: 0, count: Int(-count))
            count = llama_tokenize(vocab, text, byteCount, &tokens, Int32(tokens.count), addSpecial, true)
        }
        return Array(tokens.prefix(Int(max(0, count))))
    }

    private func piece(for token: llama_token) -> [UInt8] {
        guard let vocab else { return [] }
        var buffer = [CChar](repeating: 0, count: 64)
        var n = llama_token_to_piece(vocab, token, &buffer, Int32(buffer.count), 0, false)
        if n < 0 {
            buffer = [CChar](repeating: 0, count: Int(-n))
            n = llama_token_to_piece(vocab, token, &buffer, Int32(buffer.count), 0, false)
        }
        return buffer.prefix(Int(max(0, n))).map { UInt8(bitPattern: $0) }
    }

    /// Возвращает максимально длинный корректный UTF-8 префикс.
    /// Русские буквы занимают 2 байта и могут приходить «по половинке».
    static func takeValidUTF8(_ bytes: inout [UInt8]) -> String? {
        guard !bytes.isEmpty else { return nil }
        for cut in 0...min(3, bytes.count - 1) {
            let end = bytes.count - cut
            if let text = String(bytes: bytes[0..<end], encoding: .utf8) {
                bytes.removeFirst(end)
                return text
            }
        }
        if bytes.count > 8 { // мусорные байты — не блокируем поток
            let text = String(decoding: bytes, as: UTF8.self)
            bytes.removeAll()
            return text
        }
        return nil
    }

    private static func batchAdd(_ batch: inout llama_batch, _ token: llama_token, _ pos: llama_pos, logits: Bool) {
        let i = Int(batch.n_tokens)
        batch.token[i] = token
        batch.pos[i] = pos
        batch.n_seq_id[i] = 1
        batch.seq_id[i]![0] = 0
        batch.logits[i] = logits ? 1 : 0
        batch.n_tokens += 1
    }
}

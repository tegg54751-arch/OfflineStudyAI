// JNI-мост между Kotlin и llama.cpp.
// Логика повторяет iOS-версию (LlamaEngine.swift): шаблон чата, обрезка истории,
// повторное использование KV-кэша, запрет иероглифов, штраф латиницы,
// потоковая выдача корректных UTF-8 кусков и отмена генерации.

#include <jni.h>
#include <android/log.h>
#include <unistd.h>

#include <atomic>
#include <deque>
#include <chrono>
#include <cmath>
#include <cstring>
#include <mutex>
#include <string>
#include <vector>

#include "llama.h"
#include "ggml-backend.h"

#define LOG_TAG "OfflineStudyAI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

struct Engine {
    llama_model *model = nullptr;
    llama_context *ctx = nullptr;
    const llama_vocab *vocab = nullptr;
    std::vector<llama_token> cached;
    std::vector<llama_logit_bias> banned;
    std::vector<llama_logit_bias> latin;
    std::atomic<bool> cancel{false};
    std::mutex mutex;
};

std::string g_last_error;
std::atomic<int> g_throttle_us{0};
std::once_flag g_backend_once;

// Последние строки журнала движка — показываются в приложении («Журнал движка»).
std::mutex g_log_mutex;
std::deque<std::string> g_log;
std::string g_log_partial;

void add_log(const std::string &line) {
    std::lock_guard<std::mutex> lock(g_log_mutex);
    g_log.push_back(line);
    while (g_log.size() > 400) g_log.pop_front();
}

void log_callback(ggml_log_level level, const char *text, void *) {
    int prio = level == GGML_LOG_LEVEL_ERROR ? ANDROID_LOG_ERROR
             : level == GGML_LOG_LEVEL_WARN ? ANDROID_LOG_WARN : ANDROID_LOG_DEBUG;
    __android_log_print(prio, "llama", "%s", text);
    if (level == GGML_LOG_LEVEL_DEBUG) return;
    std::lock_guard<std::mutex> lock(g_log_mutex);
    g_log_partial += text ? text : "";
    size_t pos;
    while ((pos = g_log_partial.find('\n')) != std::string::npos) {
        std::string line = g_log_partial.substr(0, pos);
        g_log_partial.erase(0, pos + 1);
        if (!line.empty()) {
            const char *tag = level == GGML_LOG_LEVEL_ERROR ? "E " : level == GGML_LOG_LEVEL_WARN ? "W " : "";
            g_log.push_back(tag + line);
            while (g_log.size() > 400) g_log.pop_front();
        }
    }
}

std::string jstring_to_std(JNIEnv *env, jstring value) {
    if (!value) return {};
    const char *chars = env->GetStringUTFChars(value, nullptr);
    std::string result(chars ? chars : "");
    env->ReleaseStringUTFChars(value, chars);
    return result;
}

std::string jbytes_to_std(JNIEnv *env, jbyteArray value) {
    if (!value) return {};
    const jsize length = env->GetArrayLength(value);
    std::string result(static_cast<size_t>(length), '\0');
    env->GetByteArrayRegion(value, 0, length, reinterpret_cast<jbyte *>(result.data()));
    return result;
}

std::string json_escape(const std::string &s) {
    std::string out;
    out.reserve(s.size() + 8);
    for (unsigned char c : s) {
        switch (c) {
            case '"': out += "\\\""; break;
            case '\\': out += "\\\\"; break;
            case '\n': out += "\\n"; break;
            case '\r': out += "\\r"; break;
            case '\t': out += "\\t"; break;
            default:
                if (c < 0x20) {
                    char buf[8];
                    snprintf(buf, sizeof(buf), "\\u%04x", c);
                    out += buf;
                } else {
                    out += static_cast<char>(c);
                }
        }
    }
    return out;
}

std::string token_piece(const llama_vocab *vocab, llama_token token, bool special = false) {
    char buf[128];
    int n = llama_token_to_piece(vocab, token, buf, sizeof(buf), 0, special);
    if (n < 0) {
        std::string big(static_cast<size_t>(-n), '\0');
        n = llama_token_to_piece(vocab, token, big.data(), static_cast<int32_t>(big.size()), 0, special);
        if (n < 0) return {};
        big.resize(static_cast<size_t>(n));
        return big;
    }
    return std::string(buf, static_cast<size_t>(n));
}

std::vector<llama_token> tokenize(const llama_vocab *vocab, const std::string &text) {
    bool add_special = llama_vocab_get_add_bos(vocab);
    const llama_token bos = llama_vocab_bos(vocab);
    if (add_special && bos >= 0) {
        const char *bos_text = llama_vocab_get_text(vocab, bos);
        if (bos_text && text.rfind(bos_text, 0) == 0) add_special = false;
    }
    std::vector<llama_token> tokens(text.size() + 16);
    int n = llama_tokenize(vocab, text.c_str(), static_cast<int32_t>(text.size()),
                           tokens.data(), static_cast<int32_t>(tokens.size()), add_special, true);
    if (n < 0) {
        tokens.resize(static_cast<size_t>(-n));
        n = llama_tokenize(vocab, text.c_str(), static_cast<int32_t>(text.size()),
                           tokens.data(), static_cast<int32_t>(tokens.size()), add_special, true);
    }
    tokens.resize(static_cast<size_t>(std::max(0, n)));
    return tokens;
}

struct Turn {
    std::string role;
    std::string content;
};

std::string chatml(const std::vector<Turn> &turns) {
    std::string result;
    for (const auto &t : turns) {
        result += "<|im_start|>" + t.role + "\n" + t.content + "<|im_end|>\n";
    }
    result += "<|im_start|>assistant\n";
    return result;
}

std::string apply_template(const Engine &engine, const std::vector<Turn> &turns) {
    const char *tmpl = llama_model_chat_template(engine.model, nullptr);
    if (!tmpl) return chatml(turns);

    std::vector<llama_chat_message> messages;
    messages.reserve(turns.size());
    size_t total = 0;
    for (const auto &t : turns) {
        messages.push_back({t.role.c_str(), t.content.c_str()});
        total += t.content.size();
    }
    std::vector<char> buffer(total * 2 + 1024);
    int n = llama_chat_apply_template(tmpl, messages.data(), messages.size(), true,
                                      buffer.data(), static_cast<int32_t>(buffer.size()));
    if (n > static_cast<int>(buffer.size())) {
        buffer.resize(static_cast<size_t>(n) + 1);
        n = llama_chat_apply_template(tmpl, messages.data(), messages.size(), true,
                                      buffer.data(), static_cast<int32_t>(buffer.size()));
    }
    if (n <= 0) return chatml(turns);
    return std::string(buffer.data(), static_cast<size_t>(n));
}

/// Длина самого длинного префикса, который является корректным UTF-8
/// (незавершённый многобайтовый символ в конце оставляем на потом).
size_t valid_utf8_prefix(const std::string &s) {
    const size_t len = s.size();
    if (len == 0) return 0;
    // Ищем начало последнего символа (не дальше 4 байт с конца).
    size_t i = len;
    int back = 0;
    while (i > 0 && back < 4) {
        --i;
        ++back;
        const unsigned char c = static_cast<unsigned char>(s[i]);
        if ((c & 0xC0) != 0x80) {
            size_t need = 1;
            if ((c & 0xE0) == 0xC0) need = 2;
            else if ((c & 0xF0) == 0xE0) need = 3;
            else if ((c & 0xF8) == 0xF0) need = 4;
            return (len - i >= need) ? len : i;
        }
    }
    return len; // одни байты продолжения — отдаём как есть
}

/// Первые байты UTF-8 для письменностей, которые в школьных ответах на русском не нужны:
/// иероглифы/кана/хангыль (E3–ED), полноширинные формы (EF), эмодзи и редкие символы (F0–F4),
/// армянский/иврит/арабский (D4–DF), индийские, тайский (E0), грузинский/мьянма и т.п. (E1).
/// Кириллица (D0–D3), латиница, греческие буквы (CE–CF), °/²/× (C2–C3) и математика (E2) разрешены.
bool is_foreign_lead_byte(unsigned char c) {
    return (c >= 0xD4 && c <= 0xDF) || c == 0xE0 || c == 0xE1 || (c >= 0xE3 && c <= 0xEF) || (c >= 0xF0 && c <= 0xF4);
}

void build_filters(Engine &engine) {
    engine.banned.clear();
    engine.latin.clear();
    const int32_t n_vocab = llama_vocab_n_tokens(engine.vocab);
    for (llama_token token = 0; token < n_vocab; ++token) {
        if (llama_vocab_is_control(engine.vocab, token) || llama_vocab_is_eog(engine.vocab, token)) continue;
        const std::string piece = token_piece(engine.vocab, token);
        bool cjk = false;
        for (unsigned char c : piece) {
            if (is_foreign_lead_byte(c)) { cjk = true; break; }
        }
        if (cjk) {
            engine.banned.push_back({token, -INFINITY});
            continue;
        }
        size_t start = 0;
        while (start < piece.size() && piece[start] == ' ') ++start;
        const size_t letters = piece.size() - start;
        if (letters >= 2) {
            bool all_latin = true;
            for (size_t k = start; k < piece.size(); ++k) {
                const unsigned char c = static_cast<unsigned char>(piece[k]);
                if (!((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z'))) { all_latin = false; break; }
            }
            if (all_latin) engine.latin.push_back({token, -6.0f});
        }
    }
}

void batch_add(llama_batch &batch, llama_token token, llama_pos pos, bool logits) {
    const int i = batch.n_tokens;
    batch.token[i] = token;
    batch.pos[i] = pos;
    batch.n_seq_id[i] = 1;
    batch.seq_id[i][0] = 0;
    batch.logits[i] = logits ? 1 : 0;
    batch.n_tokens++;
}

void reset_cache(Engine &engine) {
    if (engine.ctx) llama_memory_clear(llama_get_memory(engine.ctx), true);
    engine.cached.clear();
}

Engine *from_handle(jlong handle) {
    return reinterpret_cast<Engine *>(handle);
}

} // namespace

extern "C" {

JNIEXPORT void JNICALL
Java_com_offlinestudy_ai_ai_LlamaBridge_nativeInit(JNIEnv *env, jobject, jstring native_lib_dir) {
    const std::string dir = jstring_to_std(env, native_lib_dir);
    std::call_once(g_backend_once, [&]() {
        llama_log_set(log_callback, nullptr);
        LOGI("Loading ggml backends from %s", dir.c_str());
        add_log("Init: backends dir = " + dir);
        ggml_backend_load_all_from_path(dir.c_str());
        llama_backend_init();
        add_log(std::string("Init: system info = ") + llama_print_system_info());
        for (size_t i = 0; i < ggml_backend_reg_count(); ++i) {
            add_log(std::string("Init: backend ") + ggml_backend_reg_name(ggml_backend_reg_get(i)));
        }
    });
}

JNIEXPORT void JNICALL
Java_com_offlinestudy_ai_ai_LlamaBridge_nativeSetThrottle(JNIEnv *, jobject, jint micros) {
    g_throttle_us = std::max(0, static_cast<int>(micros));
}

JNIEXPORT jstring JNICALL
Java_com_offlinestudy_ai_ai_LlamaBridge_nativeGetLog(JNIEnv *env, jobject) {
    std::string all;
    {
        std::lock_guard<std::mutex> lock(g_log_mutex);
        for (const auto &line : g_log) { all += line; all += '\n'; }
    }
    // NewStringUTF требует корректный (modified) UTF-8: заменяем нулевые байты на всякий случай.
    for (auto &ch : all) if (ch == '\0') ch = ' ';
    return env->NewStringUTF(all.c_str());
}

JNIEXPORT jstring JNICALL
Java_com_offlinestudy_ai_ai_LlamaBridge_nativeLastError(JNIEnv *env, jobject) {
    return env->NewStringUTF(g_last_error.c_str());
}

JNIEXPORT jlong JNICALL
Java_com_offlinestudy_ai_ai_LlamaBridge_nativeLoad(JNIEnv *env, jobject, jstring jpath, jint n_ctx_requested, jint n_threads) {
    g_last_error.clear();
    const std::string path = jstring_to_std(env, jpath);

    llama_model_params mparams = llama_model_default_params();
    mparams.n_gpu_layers = 0; // на Android считаем на CPU — стабильно на любых телефонах

    add_log("Load: " + path + " ctx=" + std::to_string(n_ctx_requested) + " threads=" + std::to_string(n_threads));
    if (ggml_backend_reg_count() == 0) {
        g_last_error = "no_backends";
        add_log("E Load: no ggml backends were loaded");
        return 0;
    }
    llama_model *model = llama_model_load_from_file(path.c_str(), mparams);
    if (!model) {
        g_last_error = "model_load_failed";
        add_log("E Load: llama_model_load_from_file returned null");
        return 0;
    }

    int n_ctx = std::max(512, static_cast<int>(n_ctx_requested));
    const int train_ctx = llama_model_n_ctx_train(model);
    if (train_ctx > 0) n_ctx = std::min(n_ctx, train_ctx);

    llama_context_params cparams = llama_context_default_params();
    cparams.n_ctx = static_cast<uint32_t>(n_ctx);
    // Меньший батч = меньше рабочий буфер. KV-кэш на Android оставляем в f16:
    // на некоторых процессорах сжатый кэш давал «мусорные» ответы.
    cparams.n_batch = 256;
    cparams.n_ubatch = 256;
    cparams.n_threads = std::max(1, static_cast<int>(n_threads));
    cparams.n_threads_batch = cparams.n_threads;

    llama_context *ctx = llama_init_from_model(model, cparams);
    if (!ctx) {
        llama_model_free(model);
        g_last_error = "context_init_failed";
        add_log("E Load: llama_init_from_model returned null");
        return 0;
    }
    add_log("Load: OK, n_ctx=" + std::to_string(llama_n_ctx(ctx)));

    auto *engine = new Engine();
    engine->model = model;
    engine->ctx = ctx;
    engine->vocab = llama_model_get_vocab(model);
    build_filters(*engine);
    LOGI("Model loaded: ctx=%d threads=%d banned=%zu latin=%zu", n_ctx, cparams.n_threads,
         engine->banned.size(), engine->latin.size());
    return reinterpret_cast<jlong>(engine);
}

JNIEXPORT jstring JNICALL
Java_com_offlinestudy_ai_ai_LlamaBridge_nativeInfo(JNIEnv *env, jobject, jlong handle) {
    Engine *engine = from_handle(handle);
    if (!engine) return env->NewStringUTF("{}");
    char desc[256] = {0};
    llama_model_desc(engine->model, desc, sizeof(desc));
    std::string json = "{";
    json += "\"description\":\"" + json_escape(desc) + "\",";
    json += "\"weightsBytes\":" + std::to_string(llama_model_size(engine->model)) + ",";
    json += "\"parameterCount\":" + std::to_string(llama_model_n_params(engine->model)) + ",";
    json += "\"contextSize\":" + std::to_string(llama_n_ctx(engine->ctx)) + ",";
    json += "\"trainContextSize\":" + std::to_string(llama_model_n_ctx_train(engine->model)) + ",";
    json += std::string("\"hasChatTemplate\":") + (llama_model_chat_template(engine->model, nullptr) ? "true" : "false");
    json += "}";
    return env->NewStringUTF(json.c_str());
}

JNIEXPORT void JNICALL
Java_com_offlinestudy_ai_ai_LlamaBridge_nativeFree(JNIEnv *, jobject, jlong handle) {
    Engine *engine = from_handle(handle);
    if (!engine) return;
    engine->cancel = true;
    {
        std::lock_guard<std::mutex> lock(engine->mutex);
        if (engine->ctx) llama_free(engine->ctx);
        if (engine->model) llama_model_free(engine->model);
        engine->ctx = nullptr;
        engine->model = nullptr;
    }
    delete engine;
}

JNIEXPORT void JNICALL
Java_com_offlinestudy_ai_ai_LlamaBridge_nativeCancel(JNIEnv *, jobject, jlong handle) {
    Engine *engine = from_handle(handle);
    if (engine) engine->cancel = true;
}

/// Возвращает JSON со статистикой или строку "ERR:<код>".
JNIEXPORT jstring JNICALL
Java_com_offlinestudy_ai_ai_LlamaBridge_nativeGenerate(
        JNIEnv *env, jobject,
        jlong handle,
        jobjectArray jroles,
        jobjectArray jcontents,
        jint max_tokens,
        jfloat temperature,
        jint top_k,
        jfloat top_p,
        jfloat min_p,
        jfloat repeat_penalty,
        jboolean discourage_latin,
        jobject callback) {
    Engine *engine = from_handle(handle);
    if (!engine || !engine->ctx) return env->NewStringUTF("ERR:model_not_loaded");
    std::lock_guard<std::mutex> lock(engine->mutex);
    engine->cancel = false;

    jclass cb_class = env->GetObjectClass(callback);
    jmethodID on_bytes = env->GetMethodID(cb_class, "onBytes", "([B)V");
    if (!on_bytes) return env->NewStringUTF("ERR:callback");

    auto emit = [&](const std::string &chunk) {
        if (chunk.empty()) return;
        jbyteArray arr = env->NewByteArray(static_cast<jsize>(chunk.size()));
        env->SetByteArrayRegion(arr, 0, static_cast<jsize>(chunk.size()), reinterpret_cast<const jbyte *>(chunk.data()));
        env->CallVoidMethod(callback, on_bytes, arr);
        env->DeleteLocalRef(arr);
    };

    // Собираем диалог.
    std::vector<Turn> turns;
    const jsize count = env->GetArrayLength(jroles);
    for (jsize i = 0; i < count; ++i) {
        auto role = reinterpret_cast<jbyteArray>(env->GetObjectArrayElement(jroles, i));
        auto content = reinterpret_cast<jbyteArray>(env->GetObjectArrayElement(jcontents, i));
        turns.push_back({jbytes_to_std(env, role), jbytes_to_std(env, content)});
        env->DeleteLocalRef(role);
        env->DeleteLocalRef(content);
    }

    llama_context *ctx = engine->ctx;
    const llama_vocab *vocab = engine->vocab;
    const int n_ctx = static_cast<int>(llama_n_ctx(ctx));
    const int max_new = std::max(16, std::min(static_cast<int>(max_tokens), n_ctx / 2));
    const int budget = n_ctx - max_new;

    // 1. Шаблон + обрезка старой истории.
    std::vector<llama_token> prompt;
    while (true) {
        prompt = tokenize(vocab, apply_template(*engine, turns));
        if (static_cast<int>(prompt.size()) <= budget) break;
        std::vector<size_t> non_system;
        for (size_t i = 0; i < turns.size(); ++i) if (turns[i].role != "system") non_system.push_back(i);
        if (non_system.size() > 1) {
            turns.erase(turns.begin() + static_cast<long>(non_system.front()));
        } else if (!non_system.empty()) {
            auto &content = turns[non_system.back()].content;
            if (content.size() <= 400) return env->NewStringUTF("ERR:prompt_too_long");
            size_t cut = content.size() * 3 / 4;
            while (cut > 0 && (static_cast<unsigned char>(content[cut]) & 0xC0) == 0x80) --cut;
            content.resize(cut);
        } else {
            return env->NewStringUTF("ERR:prompt_too_long");
        }
    }

    // 2. Повторное использование общего префикса KV-кэша.
    llama_memory_t memory = llama_get_memory(ctx);
    size_t common = 0;
    while (common < engine->cached.size() && common < prompt.size() && engine->cached[common] == prompt[common]) ++common;
    if (common == prompt.size() && common > 0) --common;
    if (common < engine->cached.size()) {
        if (!llama_memory_seq_rm(memory, 0, static_cast<llama_pos>(common), -1)) {
            llama_memory_clear(memory, true);
            common = 0;
        }
    }
    engine->cached.assign(prompt.begin(), prompt.begin() + static_cast<long>(common));

    // 3. Промпт пачками.
    const int n_batch = std::max(1, static_cast<int>(llama_n_batch(ctx)));
    llama_batch batch = llama_batch_init(n_batch, 0, 1);
    struct BatchGuard { llama_batch &b; ~BatchGuard() { llama_batch_free(b); } } guard{batch};

    const auto prompt_start = std::chrono::steady_clock::now();
    size_t position = common;
    while (position < prompt.size()) {
        if (engine->cancel) return env->NewStringUTF("ERR:cancelled");
        const size_t end = std::min(position + static_cast<size_t>(n_batch), prompt.size());
        batch.n_tokens = 0;
        for (size_t i = position; i < end; ++i) {
            batch_add(batch, prompt[i], static_cast<llama_pos>(i), i == prompt.size() - 1);
        }
        const int rc = llama_decode(ctx, batch);
        if (rc != 0) {
            reset_cache(*engine);
            return env->NewStringUTF(rc == 1 ? "ERR:prompt_too_long" : ("ERR:decode_" + std::to_string(rc)).c_str());
        }
        engine->cached.insert(engine->cached.end(), prompt.begin() + static_cast<long>(position), prompt.begin() + static_cast<long>(end));
        position = end;
    }
    const double prompt_seconds = std::chrono::duration<double>(std::chrono::steady_clock::now() - prompt_start).count();

    // Прогрев: только обработать промпт (системные правила попадут в KV-кэш),
    // чтобы первый настоящий вопрос начинался без долгого ожидания.
    if (max_tokens <= 0) {
        std::string stats = "{\"promptTokens\":" + std::to_string(prompt.size()) +
            ",\"reusedPromptTokens\":" + std::to_string(common) +
            ",\"generatedTokens\":0,\"promptSeconds\":" + std::to_string(prompt_seconds) +
            ",\"generationSeconds\":0,\"stoppedByLimit\":false,\"cancelled\":false}";
        return env->NewStringUTF(stats.c_str());
    }

    // 4. Сэмплер.
    llama_sampler *sampler = llama_sampler_chain_init(llama_sampler_chain_default_params());
    struct SamplerGuard { llama_sampler *s; ~SamplerGuard() { llama_sampler_free(s); } } sguard{sampler};

    const int32_t n_vocab = llama_vocab_n_tokens(vocab);
    std::vector<llama_logit_bias> biases = engine->banned;
    if (discourage_latin) biases.insert(biases.end(), engine->latin.begin(), engine->latin.end());
    if (!biases.empty()) {
        llama_sampler_chain_add(sampler, llama_sampler_init_logit_bias(n_vocab, static_cast<int32_t>(biases.size()), biases.data()));
    }
    llama_sampler_chain_add(sampler, llama_sampler_init_penalties(n_vocab, 64, repeat_penalty, 0.0f, 0.0f));
    if (temperature <= 0.01f) {
        llama_sampler_chain_add(sampler, llama_sampler_init_greedy());
    } else {
        llama_sampler_chain_add(sampler, llama_sampler_init_top_k(top_k));
        llama_sampler_chain_add(sampler, llama_sampler_init_top_p(top_p, 1));
        llama_sampler_chain_add(sampler, llama_sampler_init_min_p(min_p, 1));
        llama_sampler_chain_add(sampler, llama_sampler_init_temp(temperature));
        llama_sampler_chain_add(sampler, llama_sampler_init_dist(static_cast<uint32_t>(std::chrono::system_clock::now().time_since_epoch().count())));
    }

    // 5. Генерация.
    const auto gen_start = std::chrono::steady_clock::now();
    int generated = 0;
    bool stopped_by_limit = false;
    bool cancelled = false;
    std::string pending;
    int n_pos = static_cast<int>(prompt.size());

    while (true) {
        if (engine->cancel) { cancelled = true; break; }
        if (generated >= max_new || n_pos >= n_ctx - 1) { stopped_by_limit = true; break; }

        const llama_token token = llama_sampler_sample(sampler, ctx, -1);
        if (llama_vocab_is_eog(vocab, token)) break;

        pending += token_piece(vocab, token, true); // <think> и т.п. видны — PromptBuilder.cleanAnswer их уберёт
        const size_t ready = valid_utf8_prefix(pending);
        if (ready > 0) {
            emit(pending.substr(0, ready));
            pending.erase(0, ready);
        }

        batch.n_tokens = 0;
        batch_add(batch, token, static_cast<llama_pos>(n_pos), true);
        const int rc = llama_decode(ctx, batch);
        if (rc != 0) {
            reset_cache(*engine);
            return env->NewStringUTF(("ERR:decode_" + std::to_string(rc)).c_str());
        }
        engine->cached.push_back(token);
        ++n_pos;
        ++generated;

        // Телефон перегрелся — притормаживаем.
        if (generated % 16 == 0) {
            const int pause = g_throttle_us.load();
            if (pause > 0) usleep(static_cast<useconds_t>(pause));
        }
    }
    if (!pending.empty()) emit(pending);

    const double gen_seconds = std::chrono::duration<double>(std::chrono::steady_clock::now() - gen_start).count();
    std::string stats = "{";
    stats += "\"promptTokens\":" + std::to_string(prompt.size()) + ",";
    stats += "\"reusedPromptTokens\":" + std::to_string(common) + ",";
    stats += "\"generatedTokens\":" + std::to_string(generated) + ",";
    stats += "\"promptSeconds\":" + std::to_string(prompt_seconds) + ",";
    stats += "\"generationSeconds\":" + std::to_string(gen_seconds) + ",";
    stats += std::string("\"stoppedByLimit\":") + (stopped_by_limit ? "true" : "false") + ",";
    stats += std::string("\"cancelled\":") + (cancelled ? "true" : "false");
    stats += "}";
    return env->NewStringUTF(stats.c_str());
}

} // extern "C"

package com.offlinestudy.ai

import android.os.Build
import android.util.Log
import com.offlinestudy.ai.ai.LlamaBridge
import com.offlinestudy.ai.ui.AppTab
import com.offlinestudy.ai.util.DeviceInfo
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File

/**
 * Автотест для проверки на эмуляторе/телефоне без участия человека.
 * Запуск: adb shell am start -n <пакет>/com.offlinestudy.ai.MainActivity --ez selftest true
 * Результат: Android/data/<пакет>/files/selftest.json и строки с тегом IndexSelfTest в logcat.
 */
object SelfTest {
    private const val TAG = "IndexSelfTest"

    val questions = listOf(
        "Какой тип питания характерен для большинства грибоподобных организмов?\n1) автотрофный\n2) гетеротрофный\n3) хемотрофный",
        "Что такое фотосинтез?",
        "Реши уравнение: 3x + 7 = 22",
        "В каком году началась Великая Отечественная война?",
        "1. Столица Франции?\n2. Сколько будет 12 * 12?\n3. Кто написал «Евгений Онегин»?\n4. Какой газ растения поглощают при фотосинтезе?\n5. Самая длинная река в России?\n6. В каком году отменили крепостное право в России?\n7. Какой орган перекачивает кровь?\n8. Чему равна сумма углов треугольника?"
    )

    /** Сравнение формулировок промпта на одних и тех же фактических вопросах (жадное декодирование). */
    private suspend fun experiments(app: OfflineStudyApp, log: (String) -> Unit) {
        val facts = listOf(
            "Столица Франции?" to "париж",
            "Кто написал «Евгений Онегин»?" to "пушкин",
            "Сколько будет 12 * 12?" to "144",
            "Какой газ растения поглощают при фотосинтезе?" to "углекисл",
            "Самая длинная река в России?" to "",
            "В каком году отменили крепостное право в России?" to "1861",
            "Какой орган перекачивает кровь?" to "сердц",
            "Чему равна сумма углов треугольника?" to "180",
            "Кто открыл закон всемирного тяготения?" to "ньютон",
            "Какая планета ближе всего к Солнцу?" to "меркур",
            "Чему равен квадратный корень из 81?" to "9",
            "Кто был первым космонавтом?" to "гагарин",
            "Как называется самый большой океан?" to "тих",
            "Какой химический символ у золота?" to "au",
            "В каком году была Куликовская битва?" to "1380"
        )
        val core = com.offlinestudy.ai.ai.PromptBuilder.corePrompt
        val fmt = "(Формат: «**Ответ:** …», затем не больше двух коротких предложений пояснения. Если не уверен — напиши «Я не уверен».)"
        data class V(val name: String, val sys: String, val user: (String) -> String)
        val variants = listOf(
            V("expert", "Ты — опытный школьный учитель. Отвечай только на русском языке, точно и кратко.", { "$it /no_think" }),
            V("expert_unsure", "Ты — опытный школьный учитель. Отвечай только на русском языке, точно и кратко. Если не знаешь ответа, скажи «Я не уверен».", { "$it /no_think" }),
            V("expert_index", "Ты — Index AI, опытный школьный учитель. Отвечай только на русском языке, точно и кратко. Не выдумывай факты.", { "$it /no_think" }),
            V("core_plain", core, { "$it /no_think" })
        )
        for (v in variants) {
            var ok = 0; var tokens = 0; var secs = 0.0
            for ((q, key) in facts) {
                val turns = listOf(
                    com.offlinestudy.ai.ai.ChatTurn(com.offlinestudy.ai.ai.ChatTurn.Role.SYSTEM, v.sys),
                    com.offlinestudy.ai.ai.ChatTurn(com.offlinestudy.ai.ai.ChatTurn.Role.USER, v.user(q))
                )
                val sb = StringBuilder()
                runCatching {
                    app.ai.generate(turns, com.offlinestudy.ai.ai.GenerationParams(maxTokens = if (v.name == "think") 600 else 160, temperature = 0f, discourageLatin = true))
                        .collect { e ->
                            when (e) {
                                is com.offlinestudy.ai.ai.AIEvent.Token -> sb.append(e.text)
                                is com.offlinestudy.ai.ai.AIEvent.Finished -> { tokens += e.stats.generatedTokens; secs += e.stats.generationSeconds }
                            }
                        }
                }
                val a = com.offlinestudy.ai.ai.PromptBuilder.cleanAnswer(sb.toString())
                val good = key.isNotEmpty() && a.lowercase().contains(key)
                if (good) ok++
                log("EXP ${v.name} | $q -> ${a.replace("\n", " | ").take(200)}")
            }
            log("EXPSUM ${v.name}: $ok/${facts.count { it.second.isNotEmpty() }} tokens=$tokens sec=${"%.1f".format(secs)}")
        }
    }

    fun run(app: OfflineStudyApp) {
        app.appScope.launch {
            val dir = app.getExternalFilesDir(null) ?: app.filesDir
            val results = mutableListOf<JsonObject>()
            fun log(msg: String) { Log.i(TAG, msg) }
            File(dir, "selftest_done").delete()
            try {
                log("device=${Build.MANUFACTURER} ${Build.MODEL} abi=${Build.SUPPORTED_ABIS.joinToString()} sdk=${Build.VERSION.SDK_INT}")
                log("ram total=${DeviceInfo.totalRam(app)} avail=${DeviceInfo.availableRam(app)} threads=${DeviceInfo.recommendedThreads()}")
                app.models.refresh()
                log("models=${app.models.models.map { it.fileName + ":" + it.sizeBytes }}")
                val t0 = System.currentTimeMillis()
                val info = runCatching { app.ai.ensureLoaded() }
                log("load ok=${info.isSuccess} compat=${app.ai.compatibilityLevel} ms=${System.currentTimeMillis() - t0} err=${info.exceptionOrNull()?.message} info=${info.getOrNull()}")
                if (info.isSuccess) {
                    app.router.tab = AppTab.CHAT
                    delay(3000) // даём прогреву закончиться
                    for (q in questions) {
                        app.session.newChat()
                        app.session.input = q
                        val start = System.currentTimeMillis()
                        app.session.send()
                        delay(300)
                        while (app.session.isGenerating) delay(250)
                        val answer = app.session.conversation.messages.lastOrNull()?.text.orEmpty()
                        val tps = app.session.conversation.messages.lastOrNull()?.tokensPerSecond ?: 0.0
                        val ms = System.currentTimeMillis() - start
                        log("Q: ${q.replace("\n", " | ")}")
                        log("A (${ms}ms, ${"%.1f".format(tps)} tok/s): ${answer.replace("\n", " | ")}")
                        results += JsonObject(mapOf(
                            "question" to JsonPrimitive(q), "answer" to JsonPrimitive(answer),
                            "ms" to JsonPrimitive(ms), "tokensPerSecond" to JsonPrimitive(tps)
                        ))
                    }
                    experiments(app, ::log)
                }
                val report = JsonObject(mapOf(
                    "device" to JsonPrimitive("${Build.MANUFACTURER} ${Build.MODEL}"),
                    "loadOk" to JsonPrimitive(info.isSuccess),
                    "loadError" to JsonPrimitive(info.exceptionOrNull()?.message ?: ""),
                    "results" to JsonArray(results),
                    "engineLog" to JsonPrimitive(LlamaBridge.nativeGetLog())
                ))
                File(dir, "selftest.json").writeText(report.toString())
            } catch (e: Throwable) {
                log("selftest crashed: $e")
                File(dir, "selftest.json").writeText("""{"crash":"${e.toString().replace("\"", "'")}"}""")
            } finally {
                File(dir, "selftest_done").writeText("done")
                log("SELFTEST_DONE")
            }
        }
    }
}

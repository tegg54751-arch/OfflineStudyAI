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
        "1. Столица Франции?\n2. Сколько будет 12 * 12?\n3. Кто написал «Евгений Онегин»?"
    )

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
                log("load ok=${info.isSuccess} ms=${System.currentTimeMillis() - t0} err=${info.exceptionOrNull()?.message} info=${info.getOrNull()}")
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

package com.offlinestudy.ai.ai

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.offlinestudy.ai.util.DeviceInfo
import com.offlinestudy.ai.util.Format
import com.offlinestudy.ai.util.GgufName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

data class LocalModel(val file: File) {
    val id: String get() = file.name
    val fileName: String get() = file.name
    val sizeBytes: Long get() = file.length()
    val displayName: String get() = file.nameWithoutExtension.replace('-', ' ').replace('_', ' ')
}

/**
 * Файлы моделей: поиск, импорт, удаление, выбор активной.
 * Модели лежат во внутренней памяти приложения (filesDir/models).
 * Можно также скопировать .gguf по USB в Android/data/<пакет>/files —
 * при следующем открытии экрана файл будет перенесён автоматически.
 */
class ModelManager(private val context: Context) {
    private val prefs = context.getSharedPreferences("models", Context.MODE_PRIVATE)
    val modelsDir: File = File(context.filesDir, "models").apply { mkdirs() }

    var models by mutableStateOf<List<LocalModel>>(emptyList())
        private set
    var importProgress by mutableStateOf<Float?>(null)
        private set
    var importFileName by mutableStateOf<String?>(null)
        private set
    var lastError by mutableStateOf<String?>(null)
    var activeModelId by mutableStateOf(prefs.getString("active", null))
        private set

    val activeModel: LocalModel? get() = models.firstOrNull { it.id == activeModelId } ?: models.firstOrNull()
    val hasModel: Boolean get() = models.isNotEmpty()

    init {
        refresh()
    }

    fun refresh() {
        // Подхватываем файлы, скопированные по USB в папку приложения.
        context.getExternalFilesDir(null)?.listFiles()?.forEach { file ->
            if (file.isFile && file.extension.equals("gguf", true) && isGguf(file)) {
                val target = File(modelsDir, file.name)
                if (!target.exists()) {
                    if (!file.renameTo(target)) {
                        runCatching { file.copyTo(target); file.delete() }
                    }
                }
            }
        }
        // Файлы с «хэш-именем» (так их сохраняет браузер при скачивании с Hugging Face)
        // переименовываем по названию модели из самого файла.
        modelsDir.listFiles()?.forEach { file ->
            if (file.isFile && file.extension.equals("gguf", true) && GgufName.looksUnhelpful(file.name)) {
                val nice = GgufName.suggestedFileName(file) ?: return@forEach
                val target = File(modelsDir, nice)
                if (!target.exists() && file.renameTo(target)) {
                    if (activeModelId == file.name) select(LocalModel(target))
                }
            }
        }
        models = (modelsDir.listFiles()?.toList() ?: emptyList())
            .filter { it.isFile && it.extension.equals("gguf", true) && isGguf(it) }
            .sortedByDescending { it.lastModified() }
            .map { LocalModel(it) }
        if (activeModelId != null && models.none { it.id == activeModelId }) {
            select(models.firstOrNull())
        }
    }

    fun select(model: LocalModel?) {
        activeModelId = model?.id
        prefs.edit().putString("active", model?.id).apply()
    }

    fun delete(model: LocalModel) {
        if (!model.file.delete()) lastError = "Не удалось удалить файл."
        refresh()
    }

    private fun isGguf(file: File): Boolean = runCatching {
        file.inputStream().use { input ->
            val magic = ByteArray(4)
            input.read(magic) == 4 && String(magic, Charsets.US_ASCII) == "GGUF"
        }
    }.getOrDefault(false)

    /** Копирует выбранный файл блоками по 8 МБ с прогрессом. */
    suspend fun importModel(uri: Uri) {
        lastError = null
        val resolver = context.contentResolver
        var name = "model.gguf"
        var size = -1L
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                cursor.getString(0)?.let { name = it }
                size = cursor.getLong(1)
            }
        }
        if (GgufName.looksUnhelpful(name)) {
            runCatching { resolver.openInputStream(uri)?.use { GgufName.suggestedFileName(it) } }.getOrNull()?.let { name = it }
        }
        if (!name.lowercase().endsWith(".gguf")) name += ".gguf"

        val magicOk = runCatching {
            resolver.openInputStream(uri)?.use { input ->
                val magic = ByteArray(4)
                input.read(magic) == 4 && String(magic, Charsets.US_ASCII) == "GGUF"
            } ?: false
        }.getOrDefault(false)
        if (!magicOk) {
            lastError = AIException.invalidFile().message
            return
        }
        if (size > 0 && size + 200L * 1024 * 1024 > DeviceInfo.freeDisk(context)) {
            lastError = "Недостаточно места: файл ${Format.bytes(size)}, свободно ${Format.bytes(DeviceInfo.freeDisk(context))}."
            return
        }

        val destination = File(modelsDir, name)
        val partial = File(modelsDir, "$name.part")
        importFileName = name
        importProgress = 0f
        try {
            withContext(Dispatchers.IO) {
                resolver.openInputStream(uri)?.use { input ->
                    partial.outputStream().use { output ->
                        val buffer = ByteArray(8 * 1024 * 1024)
                        var copied = 0L
                        var lastReported = 0f
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            output.write(buffer, 0, read)
                            copied += read
                            if (size > 0) {
                                val progress = copied.toFloat() / size
                                if (progress - lastReported >= 0.01f) {
                                    lastReported = progress
                                    withContext(Dispatchers.Main) { importProgress = progress.coerceAtMost(1f) }
                                }
                            }
                        }
                    }
                } ?: error("Не удалось открыть файл")
                if (destination.exists()) destination.delete()
                if (!partial.renameTo(destination)) error("Не удалось сохранить файл")
            }
            refresh()
            models.firstOrNull { it.id == name }?.let { select(it) }
        } catch (e: Exception) {
            partial.delete()
            lastError = "Не удалось импортировать модель: ${e.message}"
        } finally {
            importProgress = null
            importFileName = null
        }
    }
}

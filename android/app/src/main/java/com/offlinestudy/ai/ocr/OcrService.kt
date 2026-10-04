package com.offlinestudy.ai.ocr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.Rect
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import com.googlecode.tesseract.android.TessBaseAPI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import kotlin.math.max
import kotlin.math.min

data class OcrLine(
    val id: String = UUID.randomUUID().toString(),
    val text: String,
    val confidence: Float,
    val isLikelyHandwritten: Boolean,
    val isIncluded: Boolean
)

/**
 * Распознавание текста на фото полностью на устройстве (Tesseract, русский + английский).
 * Встроенный в Android ML Kit не поддерживает кириллицу, поэтому используется Tesseract.
 *
 * Как и в iOS-версии, строки, написанные цветной ручкой (синей, красной, зелёной),
 * помечаются как рукописные и по умолчанию исключаются.
 */
class OcrService(private val context: Context) {
    class OcrException(message: String) : Exception(message)

    private val dataDir = File(context.filesDir, "tesseract")

    private fun prepareData() {
        val tessdata = File(dataDir, "tessdata").apply { mkdirs() }
        for (lang in listOf("rus", "eng")) {
            val target = File(tessdata, "$lang.traineddata")
            if (target.exists() && target.length() > 0) continue
            context.assets.open("tessdata/$lang.traineddata").use { input ->
                target.outputStream().use { input.copyTo(it) }
            }
        }
    }

    suspend fun recognize(uri: Uri): List<OcrLine> = withContext(Dispatchers.Default) {
        val bitmap = loadBitmap(uri) ?: throw OcrException("Не удалось открыть изображение.")
        prepareData()
        val tess = TessBaseAPI()
        try {
            if (!tess.init(dataDir.absolutePath, "rus+eng")) throw OcrException("Не удалось запустить распознавание текста.")
            tess.setPageSegMode(TessBaseAPI.PageSegMode.PSM_AUTO)
            tess.setImage(bitmap)
            tess.getUTF8Text() // запускает распознавание

            val lines = mutableListOf<OcrLine>()
            val iterator = tess.getResultIterator() ?: throw OcrException("Текст на фото не найден.")
            val level = TessBaseAPI.PageIteratorLevel.RIL_TEXTLINE
            iterator.begin()
            do {
                val text = iterator.getUTF8Text(level)?.trim().orEmpty()
                if (text.isEmpty()) continue
                val confidence = iterator.confidence(level) / 100f
                val rect = iterator.getBoundingRect(level)
                val colored = isColoredInk(bitmap, rect)
                val handwritten = colored || confidence < 0.35f
                lines += OcrLine(text = text, confidence = confidence, isLikelyHandwritten = handwritten, isIncluded = !handwritten)
            } while (iterator.next(level))
            iterator.delete()

            if (lines.none { it.text.isNotBlank() }) {
                throw OcrException("Текст на фото не найден. Сфотографируйте ближе и при хорошем освещении.")
            }
            lines
        } finally {
            tess.recycle()
            bitmap.recycle()
        }
    }

    /** Загружает фото с учётом поворота из EXIF и уменьшает до ~2400 px по длинной стороне. */
    private fun loadBitmap(uri: Uri): Bitmap? {
        val resolver = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        val longest = max(bounds.outWidth, bounds.outHeight)
        if (longest <= 0) return null
        var sample = 1
        while (longest / (sample * 2) >= 2400) sample *= 2
        val options = BitmapFactory.Options().apply { inSampleSize = sample; inPreferredConfig = Bitmap.Config.ARGB_8888 }
        val decoded = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) } ?: return null

        val rotation = runCatching {
            resolver.openInputStream(uri)?.use { stream ->
                when (ExifInterface(stream).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                    ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                    ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                    ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                    else -> 0f
                }
            } ?: 0f
        }.getOrDefault(0f)
        if (rotation == 0f) return decoded
        val rotated = Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, Matrix().apply { postRotate(rotation) }, true)
        if (rotated != decoded) decoded.recycle()
        return rotated
    }

    /** Цветные «чернила» (ручка) в прямоугольнике строки — по тем же правилам, что и на iOS. */
    private fun isColoredInk(bitmap: Bitmap, rect: Rect?): Boolean {
        if (rect == null) return false
        val left = max(0, rect.left)
        val top = max(0, rect.top)
        val right = min(bitmap.width, rect.right)
        val bottom = min(bitmap.height, rect.bottom)
        val w = right - left
        val h = bottom - top
        if (w <= 2 || h <= 2) return false

        // Прореживаем пиксели, чтобы проверка была быстрой.
        val step = max(1, (w * h / 40_000.0).let { kotlin.math.sqrt(it).toInt() })
        val lumas = ArrayList<Int>()
        val colors = ArrayList<Int>()
        var y = top
        while (y < bottom) {
            var x = left
            while (x < right) {
                val c = bitmap.getPixel(x, y)
                val r = (c shr 16) and 0xFF
                val g = (c shr 8) and 0xFF
                val b = c and 0xFF
                lumas += (r * 299 + g * 587 + b * 114) / 1000
                colors += c
                x += step
            }
            y += step
        }
        if (lumas.size < 20) return false
        val background = lumas.sorted()[((lumas.size - 1) * 0.85).toInt()]
        val threshold = background - max(40, background / 4)

        var count = 0; var sumR = 0L; var sumG = 0L; var sumB = 0L
        for (i in lumas.indices) {
            if (lumas[i] >= threshold) continue
            val c = colors[i]
            sumR += (c shr 16) and 0xFF; sumG += (c shr 8) and 0xFF; sumB += c and 0xFF
            count++
        }
        if (count < 12) return false
        val r = (sumR / count).toInt(); val g = (sumG / count).toInt(); val b = (sumB / count).toInt()
        val maxC = max(r, max(g, b)); val minC = min(r, min(g, b))
        val saturation = if (maxC > 0) (maxC - minC).toDouble() / maxC else 0.0
        val blueish = b - max(r, g)
        val reddish = r - max(g, b)
        val greenish = g - max(r, b)
        return saturation > 0.28 && (blueish > 18 || reddish > 28 || greenish > 22)
    }

    companion object {
        fun text(lines: List<OcrLine>): String = lines.filter { it.isIncluded }.joinToString("\n") { it.text }
    }
}

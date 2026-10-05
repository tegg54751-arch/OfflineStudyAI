package com.offlinestudy.ai.util

import java.io.DataInputStream
import java.io.File
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Читает из заголовка GGUF название модели (general.name) и тип квантования,
 * чтобы файл, скачанный браузером под «хэш-именем» (aaf2d7…bin), получил понятное имя:
 * «Qwen3-4B-Instruct-2507-Q4_0.gguf».
 */
object GgufName {
    private val fileTypes = mapOf(
        0 to "F32", 1 to "F16", 2 to "Q4_0", 3 to "Q4_1", 7 to "Q8_0", 8 to "Q5_0", 9 to "Q5_1",
        10 to "Q2_K", 11 to "Q3_K_S", 12 to "Q3_K_M", 13 to "Q3_K_L", 14 to "Q4_K_S", 15 to "Q4_K_M",
        16 to "Q5_K_S", 17 to "Q5_K_M", 18 to "Q6_K", 19 to "IQ2_XXS", 20 to "IQ2_XS", 21 to "Q2_K_S",
        22 to "IQ3_XS", 23 to "IQ3_XXS", 24 to "IQ1_S", 25 to "IQ4_NL", 26 to "IQ3_S", 27 to "IQ3_M",
        28 to "IQ2_S", 29 to "IQ2_M", 30 to "IQ4_XS", 31 to "IQ1_M", 32 to "BF16"
    )

    /** Имя файла выглядит бессмысленно: длинный хэш, «.bin», «model», «download». */
    fun looksUnhelpful(fileName: String): Boolean {
        val base = fileName.lowercase().removeSuffix(".gguf").removeSuffix(".bin")
        return Regex("^[0-9a-f]{16,}$").matches(base) || fileName.lowercase().contains(".bin") ||
            base in setOf("model", "download", "file", "resolve") || base.startsWith("download")
    }

    fun suggestedFileName(file: File): String? = runCatching { file.inputStream().use { suggestedFileName(it) } }.getOrNull()

    fun suggestedFileName(input: InputStream): String? {
        val data = DataInputStream(input.buffered(1 shl 16))
        fun u32(): Long { val b = ByteArray(4); data.readFully(b); return ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xffffffffL }
        fun u64(): Long { val b = ByteArray(8); data.readFully(b); return ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).long }
        fun str(): String { val n = u64(); require(n in 0..65536); val b = ByteArray(n.toInt()); data.readFully(b); return String(b, Charsets.UTF_8) }
        fun skip(n: Long) { var left = n; while (left > 0) { val s = data.skip(left); require(s > 0); left -= s } }
        val sizes = mapOf(0 to 1L, 1 to 1L, 2 to 2L, 3 to 2L, 4 to 4L, 5 to 4L, 6 to 4L, 7 to 1L, 10 to 8L, 11 to 8L, 12 to 8L)
        fun skipValue(type: Int) {
            when (type) {
                8 -> { val n = u64(); skip(n) }
                9 -> {
                    val inner = u32().toInt(); val count = u64()
                    if (inner == 8) repeat(count.toInt()) { skip(u64()) } else skip(count * (sizes[inner] ?: error("type")))
                }
                else -> skip(sizes[type] ?: error("type"))
            }
        }

        val magic = ByteArray(4); data.readFully(magic)
        if (String(magic, Charsets.US_ASCII) != "GGUF") return null
        u32() // version
        u64() // tensors
        val kvCount = u64()
        var name: String? = null
        var fileType: Int? = null
        for (i in 0L until kvCount) {
            val key = str()
            val type = u32().toInt()
            when {
                key == "general.name" && type == 8 -> name = str()
                key == "general.file_type" && type == 4 -> fileType = u32().toInt()
                else -> skipValue(type)
            }
            if (name != null && fileType != null) break
        }
        val base = name?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val quant = fileType?.let { fileTypes[it] }
        val full = if (quant != null && !base.contains(quant, ignoreCase = true)) "$base-$quant" else base
        val safe = full.replace(Regex("[^A-Za-z0-9._-]+"), "-").trim('-').take(80)
        return if (safe.isEmpty()) null else "$safe.gguf"
    }
}

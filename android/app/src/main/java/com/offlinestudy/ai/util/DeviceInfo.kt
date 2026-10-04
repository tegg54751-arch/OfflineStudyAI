package com.offlinestudy.ai.util

import android.app.ActivityManager
import android.content.Context
import android.os.Debug
import android.os.StatFs
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object Format {
    fun bytes(value: Long): String {
        val v = value.coerceAtLeast(0)
        return if (v >= 1L shl 30) String.format(Locale.US, "%.2f ГБ", v / (1L shl 30).toDouble())
        else String.format(Locale.US, "%.0f МБ", v / (1L shl 20).toDouble())
    }

    fun params(count: Long): String =
        if (count >= 1_000_000_000) String.format(Locale.US, "%.1f млрд", count / 1e9)
        else String.format(Locale.US, "%.0f млн", count / 1e6)

    fun date(millis: Long): String = SimpleDateFormat("d MMM, HH:mm", Locale("ru")).format(Date(millis))
}

/** Память и диск — всё читается локально через системные API. */
object DeviceInfo {
    private fun memoryInfo(context: Context): ActivityManager.MemoryInfo {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        return ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
    }

    fun totalRam(context: Context): Long = memoryInfo(context).totalMem
    fun availableRam(context: Context): Long = memoryInfo(context).availMem
    fun isLowMemory(context: Context): Boolean = memoryInfo(context).lowMemory

    /** Сколько памяти использует приложение (PSS, включая модель). */
    fun appMemory(): Long {
        val info = Debug.MemoryInfo()
        Debug.getMemoryInfo(info)
        return info.totalPss.toLong() * 1024
    }

    fun freeDisk(context: Context): Long = StatFs(context.filesDir.absolutePath).availableBytes

    /** Грубая оценка памяти для модели: веса + KV-кэш + рабочие буферы. */
    fun estimatedMemory(modelFileSize: Long, contextSize: Int): Long =
        modelFileSize + contextSize.toLong() * 50 * 1024 + 250L * 1024 * 1024

    /** Число «больших» ядер для вычислений: обычно 4 на телефонах среднего класса. */
    fun recommendedThreads(): Int {
        val cores = Runtime.getRuntime().availableProcessors()
        return (cores - 2).coerceIn(2, 4)
    }
}

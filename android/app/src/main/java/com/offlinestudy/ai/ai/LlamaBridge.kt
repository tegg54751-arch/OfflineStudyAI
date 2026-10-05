package com.offlinestudy.ai.ai

/** JNI-функции из offline_ai.cpp. */
object LlamaBridge {
    init {
        System.loadLibrary("offlinestudy")
    }

    fun interface TokenCallback {
        fun onBytes(bytes: ByteArray)
    }

    external fun nativeInit(nativeLibDir: String)
    external fun nativeLastError(): String
    external fun nativeGetLog(): String
    external fun nativeSetThrottle(micros: Int)
    external fun nativeLoad(path: String, contextSize: Int, threads: Int, safeLevel: Int): Long
    external fun nativeInfo(handle: Long): String
    external fun nativeFree(handle: Long)
    external fun nativeCancel(handle: Long)
    external fun nativeGenerate(
        handle: Long,
        roles: Array<ByteArray>,
        contents: Array<ByteArray>,
        maxTokens: Int,
        temperature: Float,
        topK: Int,
        topP: Float,
        minP: Float,
        repeatPenalty: Float,
        discourageLatin: Boolean,
        callback: TokenCallback
    ): String
}

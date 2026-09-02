package com.rimesync.android.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class LogEntry(
    val time: Long,
    val level: String,
    val message: String,
)

/** 进程内日志缓冲，供日志页展示；同时输出到 Logcat。 */
object LogBuffer {

    private const val MAX_ENTRIES = 500

    private val _entries = MutableStateFlow<List<LogEntry>>(emptyList())
    val entries: StateFlow<List<LogEntry>> = _entries.asStateFlow()

    fun append(level: String, message: String) {
        val tag = "RimeSync"
        when (level) {
            "ERROR" -> android.util.Log.e(tag, message)
            "WARN" -> android.util.Log.w(tag, message)
            else -> android.util.Log.i(tag, message)
        }
        _entries.value = (_entries.value + LogEntry(System.currentTimeMillis(), level, message))
            .takeLast(MAX_ENTRIES)
    }

    fun clear() {
        _entries.value = emptyList()
    }
}

fun logInfo(message: String) = LogBuffer.append("INFO", message)
fun logWarn(message: String) = LogBuffer.append("WARN", message)
fun logError(message: String) = LogBuffer.append("ERROR", message)
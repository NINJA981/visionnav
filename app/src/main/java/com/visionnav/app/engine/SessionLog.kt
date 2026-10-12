package com.visionnav.app.engine

import com.visionnav.core.common.TimeSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Text-only, in-memory session log for field testing (PLAN §8). It never contains images or
 * raw sensor streams; only spoken text, state transitions and errors. Exported only when the user
 * explicitly shares it.
 */
@Singleton
class SessionLog @Inject constructor(private val time: TimeSource) {

    private val format = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private val _lines = MutableStateFlow<List<String>>(emptyList())
    val lines: StateFlow<List<String>> = _lines.asStateFlow()

    fun log(tag: String, message: String) {
        val line = "${format.format(Date(time.wallMs()))} [$tag] $message"
        _lines.update { (it + line).takeLast(MAX_LINES) }
    }

    fun exportTo(dir: File): File {
        dir.mkdirs()
        val file = File(dir, "visionnav-session-${System.currentTimeMillis()}.txt")
        file.writeText(_lines.value.joinToString("\n"))
        return file
    }

    fun clear() = _lines.update { emptyList() }

    private companion object { const val MAX_LINES = 2_000 }
}

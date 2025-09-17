package com.globespeak.mobile.logging

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class LogLine(val time: String, val tag: String, val msg: String, val kind: Kind = Kind.ALL) {
    enum class Kind { ALL, DATALAYER, ENGINE }
}

object LogBus {
    private val fmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private const val CAP = 200
    private val buffer = ArrayDeque<LogLine>(CAP)
    private val _events = MutableSharedFlow<List<LogLine>>(replay = 1)
    val events: SharedFlow<List<LogLine>> = _events

    @Synchronized
    fun log(tag: String, msg: String, kind: LogLine.Kind = LogLine.Kind.ALL) {
        val line = LogLine(time = fmt.format(Date()), tag = tag, msg = msg, kind = kind)
        if (buffer.size >= CAP) buffer.removeFirst()
        buffer.addLast(line)
        _events.tryEmit(buffer.toList())
    }
}


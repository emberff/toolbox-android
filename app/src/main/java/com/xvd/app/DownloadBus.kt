package com.xvd.app

import kotlinx.coroutines.flow.MutableStateFlow

object DownloadBus {

    data class State(
        val running: Boolean = false,
        val message: String = "空闲",
        val progress: Int = 0
    )

    val state = MutableStateFlow(State())
    val log = MutableStateFlow<List<String>>(emptyList())

    @Synchronized
    fun addLog(line: String) {
        val time = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date())
        val updated = log.value + "[$time] $line"
        log.value = updated.takeLast(50)
    }

    fun setProgress(progress: Int, message: String) {
        state.value = State(running = true, message = message, progress = progress.coerceIn(0, 100))
    }

    fun setDone(message: String) {
        state.value = State(running = false, message = message, progress = 100)
    }

    fun setError(message: String) {
        state.value = State(running = false, message = message, progress = 0)
    }
}

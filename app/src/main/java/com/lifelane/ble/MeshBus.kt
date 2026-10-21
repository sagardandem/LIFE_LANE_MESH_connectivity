package com.lifelane.ble

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Simple link between the background service and the screen. */
object MeshBus {
    const val LEVEL_RED = 0
    const val LEVEL_AMBER = 1
    const val LEVEL_GREEN = 2

    private val time = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    val logs = ArrayDeque<String>()

    /** Text in the big alert box, or null when nothing to show. */
    var lastAlert: String? = null
        private set
    var lastAlertLevel = LEVEL_RED
        private set

    var running: String? = null   // "AMBULANCE", "RELAY" or null
        private set                 // change it with setRunning() so the screen is notified

    var onLog: ((String) -> Unit)? = null
    var onAlertChanged: (() -> Unit)? = null
    var onStateChanged: (() -> Unit)? = null

    fun log(msg: String) {
        val line = "${time.format(Date())}  $msg"
        logs.addFirst(line)
        while (logs.size > 300) logs.removeLast()
        onLog?.invoke(line)
    }

    fun alert(text: String, level: Int) {
        lastAlert = text
        lastAlertLevel = level
        onAlertChanged?.invoke()
    }

    fun clearAlert() {
        lastAlert = null
        onAlertChanged?.invoke()
    }

    fun setRunning(mode: String?) {
        running = mode
        onStateChanged?.invoke()
    }
}

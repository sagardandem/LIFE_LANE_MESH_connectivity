package com.lifelane.ble

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Simple link between the background service and the screen. */
object MeshBus {
    private val time = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    val logs = ArrayDeque<String>()

    /** Text in the big alert box, or null when nothing to show. */
    var lastAlert: String? = null
        private set
    /** true = red "give way" box, false = green "ambulance passed" box. */
    var lastAlertDanger = true
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

    fun alert(text: String, danger: Boolean = true) {
        lastAlert = text
        lastAlertDanger = danger
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

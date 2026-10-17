package com.lifelane.ble

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var status: TextView
    private lateinit var alert: TextView
    private lateinit var log: TextView
    private lateinit var chkIgnoreDirect: CheckBox
    private lateinit var chkShortRange: CheckBox
    private var pendingAction: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        status = findViewById(R.id.status)
        alert = findViewById(R.id.alert)
        log = findViewById(R.id.log)
        chkIgnoreDirect = findViewById(R.id.chkIgnoreDirect)
        chkShortRange = findViewById(R.id.chkShortRange)

        findViewById<Button>(R.id.btnAmbulance).setOnClickListener { start(MeshService.ACTION_AMBULANCE) }
        findViewById<Button>(R.id.btnRelay).setOnClickListener { start(MeshService.ACTION_RELAY) }
        findViewById<Button>(R.id.btnStop).setOnClickListener {
            stopService(Intent(this, MeshService::class.java))
            alert.visibility = View.GONE
        }
    }

    override fun onResume() {
        super.onResume()
        MeshBus.onLog = { runOnUiThread { refreshLog() } }
        MeshBus.onAlert = { text -> runOnUiThread { showAlert(text) } }
        MeshBus.onStateChanged = { runOnUiThread { refreshStatus() } }
        refreshLog(); refreshStatus()
        MeshBus.lastAlert?.let { if (MeshBus.running == "RELAY") showAlert(it) }
    }

    override fun onPause() {
        MeshBus.onLog = null; MeshBus.onAlert = null; MeshBus.onStateChanged = null
        super.onPause()
    }

    private fun refreshLog() { log.text = MeshBus.logs.joinToString("\n") }

    private fun refreshStatus() {
        status.text = when (MeshBus.running) {
            "AMBULANCE" -> "Running: AMBULANCE (sending alert every 2 s)"
            "RELAY" -> "Running: RECEIVER + RELAY (listening)"
            else -> "Stopped"
        }
    }

    private fun showAlert(text: String) {
        alert.text = text
        alert.visibility = View.VISIBLE
    }

    // ---------- start with permissions ----------

    private fun neededPermissions(): Array<String> {
        val list = mutableListOf<String>()
        if (Build.VERSION.SDK_INT <= 30) {
            list += Manifest.permission.ACCESS_FINE_LOCATION   // old phones only
        } else {
            list += Manifest.permission.BLUETOOTH_SCAN
            list += Manifest.permission.BLUETOOTH_ADVERTISE
            list += Manifest.permission.BLUETOOTH_CONNECT
        }
        if (Build.VERSION.SDK_INT >= 33) list += Manifest.permission.POST_NOTIFICATIONS
        return list.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }.toTypedArray()
    }

    private fun start(action: String, askIfMissing: Boolean = true) {
        val missing = neededPermissions()
        // Notifications are optional; Bluetooth + Location are required.
        val missingRequired = missing.filter { it != Manifest.permission.POST_NOTIFICATIONS }
        if (missing.isNotEmpty() && askIfMissing) {
            pendingAction = action
            ActivityCompat.requestPermissions(this, missing, 1)
            return
        }
        if (missingRequired.isNotEmpty()) {
            Toast.makeText(this, "Bluetooth permissions are needed", Toast.LENGTH_LONG).show()
            return
        }
        alert.visibility = View.GONE
        MeshBus.lastAlert = null
        val i = Intent(this, MeshService::class.java)
            .setAction(action)
            .putExtra(MeshService.EXTRA_IGNORE_DIRECT, chkIgnoreDirect.isChecked)
            .putExtra(MeshService.EXTRA_SHORT_RANGE, chkShortRange.isChecked)
        ContextCompat.startForegroundService(this, i)
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(code, perms, results)
        val action = pendingAction ?: return
        pendingAction = null
        start(action, askIfMissing = false)
    }
}

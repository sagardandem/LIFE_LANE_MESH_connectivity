package com.lifelane.ble

import android.Manifest
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var status: TextView
    private lateinit var alert: TextView
    private lateinit var log: TextView
    private lateinit var chkFiveHops: CheckBox
    private lateinit var chkFake: CheckBox
    private lateinit var spinMinRelays: Spinner
    private lateinit var chkShortRange: CheckBox
    private lateinit var chkLongRange: CheckBox
    private var pendingAction: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        status = findViewById(R.id.status)
        alert = findViewById(R.id.alert)
        log = findViewById(R.id.log)
        chkFiveHops = findViewById(R.id.chkFiveHops)
        chkFake = findViewById(R.id.chkFake)
        spinMinRelays = findViewById(R.id.spinMinRelays)
        chkShortRange = findViewById(R.id.chkShortRange)
        chkLongRange = findViewById(R.id.chkLongRange)

        spinMinRelays.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item,
            listOf(
                "0 times - accept all (normal use)",
                "1 time  (phone C in A→B→C)",
                "2 times (phone D in A→B→C→D)",
                "3 times (phone E)",
                "4 times (phone F, needs 5 hops)",
            )
        )

        // Long range needs Bluetooth 5 "Coded PHY" support
        val bt = (getSystemService(BLUETOOTH_SERVICE) as BluetoothManager).adapter
        val coded = bt != null && bt.isLeCodedPhySupported && bt.isLeExtendedAdvertisingSupported
        if (!coded) {
            chkLongRange.isEnabled = false
            chkLongRange.text = "Long range mode (not supported on this phone)"
        }

        findViewById<Button>(R.id.btnAmbulance).setOnClickListener { start(MeshService.ACTION_AMBULANCE) }
        findViewById<Button>(R.id.btnRelay).setOnClickListener { start(MeshService.ACTION_RELAY) }
        findViewById<Button>(R.id.btnStop).setOnClickListener {
            if (MeshBus.running != null) {
                // Ambulance sends "trip ended" for a few seconds before stopping
                startService(Intent(this, MeshService::class.java).setAction(MeshService.ACTION_STOP))
            }
        }
    }

    override fun onResume() {
        super.onResume()
        MeshBus.onLog = { runOnUiThread { refreshLog() } }
        MeshBus.onAlertChanged = { runOnUiThread { refreshAlert() } }
        MeshBus.onStateChanged = { runOnUiThread { refreshStatus() } }
        refreshLog(); refreshStatus(); refreshAlert()
    }

    override fun onPause() {
        MeshBus.onLog = null; MeshBus.onAlertChanged = null; MeshBus.onStateChanged = null
        super.onPause()
    }

    private fun refreshLog() { log.text = MeshBus.logs.joinToString("\n") }

    private fun refreshStatus() {
        status.text = when (MeshBus.running) {
            "AMBULANCE" -> "Running: AMBULANCE (sending every 1 s)"
            "RELAY" -> "Running: RECEIVER + RELAY (listening)"
            else -> "Stopped"
        }
    }

    private fun refreshAlert() {
        val text = MeshBus.lastAlert
        if (text == null) {
            alert.visibility = View.GONE
            return
        }
        alert.text = text
        alert.setBackgroundColor(Color.parseColor(if (MeshBus.lastAlertDanger) "#D32F2F" else "#2E7D32"))
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
        // Notifications are optional; Bluetooth permissions are required.
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
        val i = Intent(this, MeshService::class.java)
            .setAction(action)
            .putExtra(MeshService.EXTRA_MAX_HOPS, if (chkFiveHops.isChecked) 5 else 3)
            .putExtra(MeshService.EXTRA_FAKE, chkFake.isChecked)
            .putExtra(MeshService.EXTRA_MIN_RELAYS, spinMinRelays.selectedItemPosition)
            .putExtra(MeshService.EXTRA_SHORT_RANGE, chkShortRange.isChecked)
            .putExtra(MeshService.EXTRA_LONG_RANGE, chkLongRange.isChecked)
        ContextCompat.startForegroundService(this, i)
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(code, perms, results)
        val action = pendingAction ?: return
        pendingAction = null
        start(action, askIfMissing = false)
    }
}

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
    private lateinit var spinAmbHeading: Spinner
    private lateinit var spinAmbPos: Spinner
    private lateinit var spinMyHeading: Spinner
    private lateinit var spinMinRelays: Spinner
    private lateinit var chkShortRange: CheckBox
    private lateinit var chkLongRange: CheckBox
    private var pendingAction: String? = null

    /** Direction choices: label -> degrees (-1 = from GPS while moving). */
    private val headings = listOf(
        "Auto (from GPS while moving)" to -1,
        "TEST: North" to 0,
        "TEST: East" to 90,
        "TEST: South" to 180,
        "TEST: West" to 270,
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        status = findViewById(R.id.status)
        alert = findViewById(R.id.alert)
        log = findViewById(R.id.log)
        chkFiveHops = findViewById(R.id.chkFiveHops)
        chkFake = findViewById(R.id.chkFake)
        spinAmbHeading = findViewById(R.id.spinAmbHeading)
        spinAmbPos = findViewById(R.id.spinAmbPos)
        spinMyHeading = findViewById(R.id.spinMyHeading)
        spinMinRelays = findViewById(R.id.spinMinRelays)
        chkShortRange = findViewById(R.id.chkShortRange)
        chkLongRange = findViewById(R.id.chkLongRange)

        spinAmbHeading.adapter = adapter(headings.map { it.first })
        spinMyHeading.adapter = adapter(headings.map { it.first })
        spinAmbPos.adapter = adapter(
            listOf(
                "Real GPS position",
                "TEST: 200 m BEFORE this spot (phones here are AHEAD)",
                "TEST: 200 m AFTER this spot (phones here are BEHIND)",
            )
        )
        spinMinRelays.adapter = adapter(
            listOf(
                "0 times - accept all (normal use)",
                "1 time  (phone C in A→B→C)",
                "2 times (phone D in A→B→C→D)",
                "3 times (phone E)",
                "4 times (phone F, needs 5 hops)",
            )
        )

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
                startService(Intent(this, MeshService::class.java).setAction(MeshService.ACTION_STOP))
            }
        }
    }

    private fun adapter(items: List<String>) =
        ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, items)

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
            "AMBULANCE" -> "Running: SOURCE (sending every 1 s)"
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
        val (bg, fg) = when (MeshBus.lastAlertLevel) {
            MeshBus.LEVEL_AMBER -> "#FFB300" to "#000000"
            MeshBus.LEVEL_GREEN -> "#2E7D32" to "#FFFFFF"
            else -> "#D32F2F" to "#FFFFFF"
        }
        alert.setBackgroundColor(Color.parseColor(bg))
        alert.setTextColor(Color.parseColor(fg))
        alert.visibility = View.VISIBLE
    }

    // ---------- start with permissions ----------

    private fun granted(p: String) = ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    private fun bluetoothPermissions(): List<String> =
        if (Build.VERSION.SDK_INT >= 31) listOf(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_ADVERTISE,
            Manifest.permission.BLUETOOTH_CONNECT,
        ) else listOf(Manifest.permission.ACCESS_FINE_LOCATION)   // old phones need location for Bluetooth scanning

    private fun allWanted(): List<String> {
        val list = bluetoothPermissions().toMutableList()
        list += Manifest.permission.ACCESS_FINE_LOCATION       // for the direction check (optional on receivers)
        list += Manifest.permission.ACCESS_COARSE_LOCATION
        if (Build.VERSION.SDK_INT >= 33) list += Manifest.permission.POST_NOTIFICATIONS
        return list.distinct()
    }

    private fun start(action: String, askIfMissing: Boolean = true) {
        val missing = allWanted().filter { !granted(it) }
        if (missing.isNotEmpty() && askIfMissing) {
            pendingAction = action
            ActivityCompat.requestPermissions(this, missing.toTypedArray(), 1)
            return
        }
        if (bluetoothPermissions().any { !granted(it) }) {
            Toast.makeText(this, "Bluetooth permissions are needed", Toast.LENGTH_LONG).show()
            return
        }
        val hasLocation = granted(Manifest.permission.ACCESS_FINE_LOCATION) || granted(Manifest.permission.ACCESS_COARSE_LOCATION)
        if (action == MeshService.ACTION_AMBULANCE && !hasLocation) {
            Toast.makeText(this, "The source phone needs Location permission (to send the ambulance position)", Toast.LENGTH_LONG).show()
            return
        }
        if (action == MeshService.ACTION_RELAY && !hasLocation) {
            Toast.makeText(this, "No location permission: you will only get soft 'be alert' warnings", Toast.LENGTH_LONG).show()
        }

        val i = Intent(this, MeshService::class.java)
            .setAction(action)
            .putExtra(MeshService.EXTRA_MAX_HOPS, if (chkFiveHops.isChecked) 5 else 3)
            .putExtra(MeshService.EXTRA_FAKE, chkFake.isChecked)
            .putExtra(MeshService.EXTRA_MIN_RELAYS, spinMinRelays.selectedItemPosition)
            .putExtra(MeshService.EXTRA_SHORT_RANGE, chkShortRange.isChecked)
            .putExtra(MeshService.EXTRA_LONG_RANGE, chkLongRange.isChecked)
            .putExtra(MeshService.EXTRA_AMB_HEADING, headings[spinAmbHeading.selectedItemPosition].second)
            .putExtra(MeshService.EXTRA_AMB_POS, spinAmbPos.selectedItemPosition)
            .putExtra(MeshService.EXTRA_MY_HEADING, headings[spinMyHeading.selectedItemPosition].second)
        ContextCompat.startForegroundService(this, i)
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(code, perms, results)
        val action = pendingAction ?: return
        pendingAction = null
        start(action, askIfMissing = false)
    }
}

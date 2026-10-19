package com.lifelane.ble

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.AdvertisingSet
import android.bluetooth.le.AdvertisingSetCallback
import android.bluetooth.le.AdvertisingSetParameters
import android.bluetooth.le.BluetoothLeAdvertiser
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import kotlin.random.Random

/**
 * Runs in the background (even with screen off).
 *
 * AMBULANCE mode: every 1 s sends a signed alert. On Stop, sends "trip ended" for 4 s.
 * RELAY mode:     listens. When a NEW genuine alert arrives it shows it and passes it on, using:
 *   - smart relay:  if 2 other phones already passed this alert on, stay quiet (avoids jamming)
 *   - far-first:    phones with a weaker signal (further away) pass it on sooner
 *   - auto-expire:  no new alert for 15 s -> alert cleared
 *   - battery saver: slow scan when idle, fast scan while an alert is active
 */
@SuppressLint("MissingPermission") // permissions are asked in MainActivity before starting
class MeshService : Service() {

    companion object {
        const val ACTION_AMBULANCE = "com.lifelane.ble.AMBULANCE"
        const val ACTION_RELAY = "com.lifelane.ble.RELAY"
        const val ACTION_STOP = "com.lifelane.ble.STOP"

        const val EXTRA_MAX_HOPS = "maxHops"
        const val EXTRA_FAKE = "fake"
        const val EXTRA_MIN_RELAYS = "minRelays"
        const val EXTRA_SHORT_RANGE = "shortRange"
        const val EXTRA_LONG_RANGE = "longRange"

        const val AMBULANCE_INTERVAL_MS = 1000L
        const val RELAY_ADVERTISE_MS = 4500L   // longer than one slow-scan cycle, so idle phones still catch it
        const val END_ADVERTISE_MS = 4000L
        const val ALERT_EXPIRE_MS = 15_000L
        const val SUPPRESS_AFTER = 2           // stay quiet if this many OTHER phones already relayed

        private const val CH_SERVICE = "service"
        private const val CH_ALERT = "alert"
        private const val ID_SERVICE = 1
        private const val ID_ALERT = 2
    }

    private val handler = Handler(Looper.getMainLooper())
    private var scanner: BluetoothLeScanner? = null
    private var advertiser: BluetoothLeAdvertiser? = null
    private var codedSupported = false

    // Current broadcast (normal + optional long-range copy)
    private var legacyCb: AdvertiseCallback? = null
    private var codedCb: AdvertisingSetCallback? = null
    private var stopAdTask: Runnable? = null

    // Settings
    private var mode = "RELAY"
    private var maxHops = 3
    private var fake = false
    private var minRelays = 0
    private var shortRange = false
    private var longRange = false

    /** Random id of this phone for this session (1..255). Lets us ignore our own copies. */
    private val myId = Random.nextInt(1, 256)

    /** Newest message number seen for each trip. */
    private val latestSeq = HashMap<Int, Int>()

    // Smart relay state for the message we are about to pass on
    private var pendingRelay: Runnable? = null
    private var pendingKey: String? = null
    private var pendingFrom = -1
    private val otherRelayers = HashSet<Int>()

    // Alert state
    private var alertActive = false
    private var lastAlertAt = 0L
    private var fastScan: Boolean? = null
    private var lastFakeLogAt = 0L

    // Ambulance trip
    private val tripId = Random.nextInt(1000, 9999)
    private var seq = 0
    private var stopping = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createChannels()
        if (intent?.action == ACTION_STOP) {
            handleStop(); return START_NOT_STICKY
        }

        mode = if (intent?.action == ACTION_AMBULANCE) "AMBULANCE" else "RELAY"
        ServiceCompat.startForeground(
            this, ID_SERVICE, serviceNotification("Running as $mode"),
            if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE else 0
        )

        maxHops = intent?.getIntExtra(EXTRA_MAX_HOPS, 3) ?: 3
        fake = intent?.getBooleanExtra(EXTRA_FAKE, false) ?: false
        minRelays = intent?.getIntExtra(EXTRA_MIN_RELAYS, 0) ?: 0
        shortRange = intent?.getBooleanExtra(EXTRA_SHORT_RANGE, false) ?: false
        val wantLongRange = intent?.getBooleanExtra(EXTRA_LONG_RANGE, false) ?: false

        val bt = (getSystemService(BLUETOOTH_SERVICE) as BluetoothManager).adapter
        if (bt == null || !bt.isEnabled) {
            MeshBus.log("ERROR: Bluetooth is OFF. Turn it on and try again.")
            stopSelf(); return START_NOT_STICKY
        }
        scanner = bt.bluetoothLeScanner
        advertiser = bt.bluetoothLeAdvertiser
        codedSupported = bt.isLeCodedPhySupported && bt.isLeExtendedAdvertisingSupported
        longRange = wantLongRange && codedSupported
        if (wantLongRange && !codedSupported) MeshBus.log("Long range not supported on this phone - using normal range")
        if (advertiser == null) MeshBus.log("WARNING: this phone cannot send BLE broadcasts (receive only)")

        stopEverything()
        stopping = false
        alertActive = false
        latestSeq.clear()
        MeshBus.clearAlert()
        if (mode == "AMBULANCE") startAmbulance() else startRelay()
        MeshBus.setRunning(mode)
        return START_STICKY
    }

    private fun handleStop() {
        if (MeshBus.running == "AMBULANCE" && !stopping) {
            // Tell everyone the ambulance has passed, then stop.
            stopping = true
            handler.removeCallbacks(ambulanceTick)
            seq = (seq + 1) and 0xFFFF
            val end = AlertMessage(tripId, seq, maxHops, AlertMessage.TYPE_END, maxHops, myId).signed()
            advertise(end, stopAfterMs = 0)
            MeshBus.log("SENT trip ended #$seq - stopping in 4 s")
            handler.postDelayed({ stopSelf() }, END_ADVERTISE_MS)
        } else if (!stopping) {
            stopSelf()
        }
    }

    // ---------------- AMBULANCE ----------------

    private val ambulanceTick = object : Runnable {
        override fun run() {
            seq = (seq + 1) and 0xFFFF
            var msg = AlertMessage(tripId, seq, maxHops, AlertMessage.TYPE_ALERT, maxHops, myId).signed()
            if (fake) msg = msg.withFakeSignature()
            latestSeq[tripId] = seq
            advertise(msg, stopAfterMs = 0)
            MeshBus.log("SENT trip $tripId #$seq hops=$maxHops" + if (fake) " (FAKE signature)" else "")
            handler.postDelayed(this, AMBULANCE_INTERVAL_MS)
        }
    }

    private fun startAmbulance() {
        MeshBus.log(
            "Ambulance started. Trip $tripId, max $maxHops hops" +
                (if (longRange) ", long range ON" else "") + (if (fake) ", FAKE mode" else "")
        )
        handler.post(ambulanceTick)
    }

    // ---------------- RELAY ----------------

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val data = result.scanRecord?.getManufacturerSpecificData(AlertMessage.COMPANY_ID)
            val msg = AlertMessage.fromBytes(data) ?: return
            handler.post { onMessage(msg, result.rssi) }
        }

        override fun onScanFailed(errorCode: Int) {
            MeshBus.log("ERROR: scan failed, code $errorCode")
        }
    }

    private val expiryCheck = object : Runnable {
        override fun run() {
            if (alertActive && SystemClock.elapsedRealtime() - lastAlertAt > ALERT_EXPIRE_MS) {
                clearAlert(passed = false, why = "No signal for 15 s - alert cleared")
            }
            handler.postDelayed(this, 1000)
        }
    }

    private fun startRelay() {
        MeshBus.log(
            "Listening" + (if (minRelays > 0) " (TEST: accept only relayed $minRelays+ times)" else "") +
                (if (codedSupported) ", long-range receive ON" else "")
        )
        setScanFast(false)
        handler.post(expiryCheck)
    }

    /** Battery saver: slow scan when idle, fast scan while an alert is active. */
    private fun setScanFast(fast: Boolean) {
        if (fastScan == fast) return
        runCatching { scanner?.stopScan(scanCallback) }
        val filter = ScanFilter.Builder()
            .setManufacturerData(
                AlertMessage.COMPANY_ID,
                byteArrayOf(AlertMessage.MAGIC),
                byteArrayOf(0xFF.toByte())
            ).build()
        val b = ScanSettings.Builder()
            .setScanMode(if (fast) ScanSettings.SCAN_MODE_LOW_LATENCY else ScanSettings.SCAN_MODE_BALANCED)
        if (codedSupported) {
            b.setLegacy(false)                              // also receive long-range broadcasts
            b.setPhy(ScanSettings.PHY_LE_ALL_SUPPORTED)
        }
        scanner?.startScan(listOf(filter), b.build(), scanCallback)
        fastScan = fast
        MeshBus.log(if (fast) "Scan: FAST (alert active)" else "Scan: SLOW (battery saver)")
    }

    private fun onMessage(msg: AlertMessage, rssi: Int) {
        if (msg.senderId == myId) return                         // our own copy

        if (!msg.isGenuine()) {                                  // fake ambulance protection
            val now = SystemClock.elapsedRealtime()
            if (now - lastFakeLogAt > 5000) {
                lastFakeLogAt = now
                MeshBus.log("REJECTED fake alert (bad signature) trip ${msg.tripId}")
            }
            return
        }

        val key = "${msg.tripId}:${msg.seq}"
        val last = latestSeq[msg.tripId]
        if (last != null && msg.seq == last) {
            // Same message again. Count OTHER phones that already relayed it (smart relay).
            if (key == pendingKey && msg.relayedTimes >= 1 && msg.senderId != pendingFrom) {
                otherRelayers.add(msg.senderId)
            }
            return
        }
        if (last != null && !isNewer(msg.seq, last)) return     // old message
        if (msg.relayedTimes < minRelays) return                 // test setting for longer chains

        latestSeq[msg.tripId] = msg.seq
        val how = if (msg.relayedTimes == 0) "DIRECT from ambulance" else "RELAYED ${msg.relayedTimes} time(s)"

        if (msg.type == AlertMessage.TYPE_END) {
            MeshBus.log("GOT trip ${msg.tripId} ENDED, $how")
            clearAlert(passed = true, why = "Ambulance passed - alert cleared")
            relayLater(msg, rssi)
            return
        }

        lastAlertAt = SystemClock.elapsedRealtime()
        if (!alertActive) {
            alertActive = true
            setScanFast(true)
        }
        MeshBus.log("GOT trip ${msg.tripId} #${msg.seq} $how, rssi $rssi")
        MeshBus.alert("AMBULANCE NEARBY\nGive way\n\nTrip ${msg.tripId} • msg #${msg.seq}\n$how")
        showAlertNotification(how)
        relayLater(msg, rssi)
    }

    /** Wait a little, then pass the message on - unless 2 other phones already did. */
    private fun relayLater(msg: AlertMessage, rssi: Int) {
        if (msg.hopsLeft <= 0) return
        pendingRelay?.let { handler.removeCallbacks(it) }       // newer message replaces older one

        val key = "${msg.tripId}:${msg.seq}"
        pendingKey = key
        pendingFrom = msg.senderId
        otherRelayers.clear()

        val fwd = msg.copy(hopsLeft = msg.hopsLeft - 1, senderId = myId)
        val task = Runnable {
            pendingRelay = null
            if (otherRelayers.size >= SUPPRESS_AFTER) {
                MeshBus.log("SKIPPED relay #${msg.seq}: ${otherRelayers.size} other phones already relayed it")
                return@Runnable
            }
            advertise(fwd, stopAfterMs = RELAY_ADVERTISE_MS)
            MeshBus.log("RELAYED #${fwd.seq} hopsLeft=${fwd.hopsLeft}" + if (fwd.type == AlertMessage.TYPE_END) " (trip ended)" else "")
        }
        pendingRelay = task
        handler.postDelayed(task, relayDelayMs(rssi))
    }

    /**
     * Far-first: weak signal (far from sender) -> short wait, strong signal (close) -> long wait.
     * Far phones push the alert outward first, and close phones usually hear them and stay quiet.
     */
    private fun relayDelayMs(rssi: Int): Long {
        val r = rssi.coerceIn(-95, -35)
        val base = 50 + (r + 95) * 250 / 60        // -95 dBm -> 50 ms, -35 dBm -> 300 ms
        return base + Random.nextLong(0, 50)
    }

    private fun clearAlert(passed: Boolean, why: String) {
        val wasActive = alertActive || MeshBus.lastAlert != null
        alertActive = false
        setScanFast(false)
        runCatching { getSystemService(NotificationManager::class.java).cancel(ID_ALERT) }
        if (!wasActive) return
        MeshBus.log(why)
        if (passed) MeshBus.alert("AMBULANCE PASSED\nAlert cleared", danger = false) else MeshBus.clearAlert()
    }

    /** Message numbers wrap at 65535, so compare with wrap-around. */
    private fun isNewer(a: Int, b: Int): Boolean {
        val diff = (a - b) and 0xFFFF
        return diff in 1..32767
    }

    // ---------------- BLUETOOTH SEND ----------------

    private fun advertise(msg: AlertMessage, stopAfterMs: Long) {
        val adv = advertiser ?: return
        stopAdvertising()

        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .setIncludeTxPowerLevel(false)
            .addManufacturerData(AlertMessage.COMPANY_ID, msg.toBytes())
            .build()

        // 1) Normal broadcast - every phone can hear this
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(
                if (shortRange) AdvertiseSettings.ADVERTISE_TX_POWER_ULTRA_LOW
                else AdvertiseSettings.ADVERTISE_TX_POWER_HIGH
            )
            .setConnectable(false)
            .setTimeout(0)
            .build()
        val cb = object : AdvertiseCallback() {
            override fun onStartFailure(errorCode: Int) {
                MeshBus.log("ERROR: send failed, code $errorCode")
            }
        }
        legacyCb = cb
        adv.startAdvertising(settings, data, cb)

        // 2) Extra long-range copy (Bluetooth 5 "Coded PHY") if enabled and supported
        if (longRange) {
            val params = AdvertisingSetParameters.Builder()
                .setLegacyMode(false)
                .setConnectable(false)
                .setScannable(false)
                .setPrimaryPhy(BluetoothDevice.PHY_LE_CODED)
                .setSecondaryPhy(BluetoothDevice.PHY_LE_CODED)
                .setInterval(AdvertisingSetParameters.INTERVAL_LOW)
                .setTxPowerLevel(
                    if (shortRange) AdvertisingSetParameters.TX_POWER_ULTRA_LOW
                    else AdvertisingSetParameters.TX_POWER_HIGH
                )
                .build()
            val scb = object : AdvertisingSetCallback() {
                override fun onAdvertisingSetStarted(set: AdvertisingSet?, txPower: Int, status: Int) {
                    if (status != AdvertisingSetCallback.ADVERTISE_SUCCESS) MeshBus.log("Long-range send failed, status $status")
                }
            }
            codedCb = scb
            runCatching { adv.startAdvertisingSet(params, data, null, null, null, scb) }
                .onFailure { MeshBus.log("Long-range send error: ${it.message}") }
        }

        if (stopAfterMs > 0) {
            val task = Runnable { stopAdvertising() }
            stopAdTask = task
            handler.postDelayed(task, stopAfterMs)
        }
    }

    private fun stopAdvertising() {
        stopAdTask?.let { handler.removeCallbacks(it) }
        stopAdTask = null
        legacyCb?.let { runCatching { advertiser?.stopAdvertising(it) } }
        legacyCb = null
        codedCb?.let { runCatching { advertiser?.stopAdvertisingSet(it) } }
        codedCb = null
    }

    private fun stopEverything() {
        handler.removeCallbacksAndMessages(null)
        pendingRelay = null
        stopAdvertising()
        runCatching { scanner?.stopScan(scanCallback) }
        fastScan = null
    }

    override fun onDestroy() {
        stopEverything()
        alertActive = false
        runCatching { getSystemService(NotificationManager::class.java).cancel(ID_ALERT) }
        MeshBus.clearAlert()
        MeshBus.log("Stopped")
        MeshBus.setRunning(null)
        super.onDestroy()
    }

    // ---------------- NOTIFICATIONS ----------------

    private fun createChannels() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH_SERVICE, "Running", NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(NotificationChannel(CH_ALERT, "Ambulance alerts", NotificationManager.IMPORTANCE_HIGH))
    }

    private fun openAppIntent(): PendingIntent = PendingIntent.getActivity(
        this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
    )

    private fun serviceNotification(text: String): Notification =
        NotificationCompat.Builder(this, CH_SERVICE)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle("LifeLane BLE")
            .setContentText(text)
            .setContentIntent(openAppIntent())
            .setOngoing(true)
            .build()

    private var lastNotifyAt = 0L

    private fun showAlertNotification(how: String) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastNotifyAt < 10_000) return   // don't buzz every second
        lastNotifyAt = now
        val n = NotificationCompat.Builder(this, CH_ALERT)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("Ambulance nearby - give way")
            .setContentText(how)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(openAppIntent())
            .setAutoCancel(true)
            .build()
        runCatching { getSystemService(NotificationManager::class.java).notify(ID_ALERT, n) }
    }
}

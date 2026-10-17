package com.lifelane.ble

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
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
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import kotlin.random.Random

/**
 * Runs in the background (even with screen off).
 *
 * AMBULANCE mode: every 2 s sends a new alert with hopsLeft = MAX_HOPS.
 * RELAY mode:     listens for alerts. When a NEW one arrives, shows it and,
 *                 if hopsLeft > 0, sends it again with hopsLeft - 1.
 */
@SuppressLint("MissingPermission") // permissions are asked in MainActivity before starting
class MeshService : Service() {

    companion object {
        const val ACTION_AMBULANCE = "com.lifelane.ble.AMBULANCE"
        const val ACTION_RELAY = "com.lifelane.ble.RELAY"
        const val EXTRA_IGNORE_DIRECT = "ignoreDirect"
        const val EXTRA_SHORT_RANGE = "shortRange"

        const val MAX_HOPS = 3
        const val AMBULANCE_INTERVAL_MS = 2000L
        const val RELAY_ADVERTISE_MS = 3000L

        private const val CH_SERVICE = "service"
        private const val CH_ALERT = "alert"
        private const val ID_SERVICE = 1
        private const val ID_ALERT = 2
    }

    private val handler = Handler(Looper.getMainLooper())
    private var scanner: BluetoothLeScanner? = null
    private var advertiser: BluetoothLeAdvertiser? = null
    private var adCallback: AdvertiseCallback? = null
    private var stopAdTask: Runnable? = null

    private var ignoreDirect = false
    private var shortRange = false

    /** Newest message number seen for each trip. Older or same = duplicate, ignore it. */
    private val latestSeq = HashMap<Int, Int>()

    // Simulated ambulance (Hyderabad, moving slowly north-east)
    private val tripId = Random.nextInt(1000, 9999)
    private var seq = 0
    private var lat = 17.4435
    private var lon = 78.3772

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createChannels()
        val mode = if (intent?.action == ACTION_AMBULANCE) "AMBULANCE" else "RELAY"
        ServiceCompat.startForeground(
            this, ID_SERVICE, serviceNotification("Running as $mode"),
            if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE else 0
        )

        ignoreDirect = intent?.getBooleanExtra(EXTRA_IGNORE_DIRECT, false) ?: false
        shortRange = intent?.getBooleanExtra(EXTRA_SHORT_RANGE, false) ?: false

        val bt = (getSystemService(BLUETOOTH_SERVICE) as BluetoothManager).adapter
        if (bt == null || !bt.isEnabled) {
            MeshBus.log("ERROR: Bluetooth is OFF. Turn it on and try again.")
            stopSelf(); return START_NOT_STICKY
        }
        scanner = bt.bluetoothLeScanner
        advertiser = bt.bluetoothLeAdvertiser
        if (advertiser == null) MeshBus.log("WARNING: this phone cannot send BLE broadcasts (receive only).")

        stopEverything()
        if (mode == "AMBULANCE") startAmbulance() else startRelay()
        MeshBus.setRunning(mode)
        return START_STICKY
    }

    // ---------------- AMBULANCE ----------------

    private val ambulanceTick = object : Runnable {
        override fun run() {
            seq = (seq + 1) and 0xFFFF
            lat += 0.00015; lon += 0.00010
            val msg = AlertMessage(tripId, seq, MAX_HOPS, lat, lon)
            latestSeq[tripId] = seq
            advertise(msg, stopAfterMs = 0)
            MeshBus.log("SENT trip $tripId #$seq hops=$MAX_HOPS")
            handler.postDelayed(this, AMBULANCE_INTERVAL_MS)
        }
    }

    private fun startAmbulance() {
        MeshBus.log("Ambulance started. Trip ID = $tripId")
        handler.post(ambulanceTick)
    }

    // ---------------- RELAY ----------------

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val data = result.scanRecord?.getManufacturerSpecificData(AlertMessage.COMPANY_ID)
            val msg = AlertMessage.fromBytes(data) ?: return
            handler.post { onMessage(msg, result.rssi, result.device.address) }
        }

        override fun onScanFailed(errorCode: Int) {
            MeshBus.log("ERROR: scan failed, code $errorCode")
        }
    }

    private fun startRelay() {
        val filter = ScanFilter.Builder()
            .setManufacturerData(
                AlertMessage.COMPANY_ID,
                byteArrayOf(AlertMessage.MAGIC),
                byteArrayOf(0xFF.toByte())
            ).build()
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        scanner?.startScan(listOf(filter), settings, scanCallback)
        MeshBus.log("Listening for alerts" + if (ignoreDirect) " (TEST: ignoring direct ambulance signal)" else "")
    }

    private fun onMessage(msg: AlertMessage, rssi: Int, from: String) {
        val last = latestSeq[msg.tripId]
        if (last != null && !isNewer(msg.seq, last)) return          // duplicate or old
        if (ignoreDirect && msg.hopsLeft == MAX_HOPS) return           // test mode: only accept relayed

        latestSeq[msg.tripId] = msg.seq
        val relayedTimes = MAX_HOPS - msg.hopsLeft
        val how = if (relayedTimes == 0) "DIRECT from ambulance" else "RELAYED $relayedTimes time(s)"
        MeshBus.log("GOT trip ${msg.tripId} #${msg.seq} $how, rssi $rssi, from ${from.takeLast(5)}")

        val text = "AMBULANCE NEARBY\nGive way\n\nTrip ${msg.tripId} • msg #${msg.seq}\n$how\n" +
            "Signal $rssi dBm\nAmbulance at %.5f, %.5f".format(msg.lat, msg.lon)
        MeshBus.alert(text)
        showAlertNotification(how)

        if (msg.hopsLeft > 0) {
            val jitter = Random.nextLong(50, 200)   // small random wait so phones don't all send at once
            val fwd = msg.copy(hopsLeft = msg.hopsLeft - 1)
            handler.postDelayed({
                advertise(fwd, stopAfterMs = RELAY_ADVERTISE_MS)
                MeshBus.log("RELAYED #${fwd.seq} hopsLeft=${fwd.hopsLeft}")
            }, jitter)
        }
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

        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(
                if (shortRange) AdvertiseSettings.ADVERTISE_TX_POWER_ULTRA_LOW
                else AdvertiseSettings.ADVERTISE_TX_POWER_HIGH
            )
            .setConnectable(false)
            .setTimeout(0)
            .build()
        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .setIncludeTxPowerLevel(false)
            .addManufacturerData(AlertMessage.COMPANY_ID, msg.toBytes())
            .build()

        val cb = object : AdvertiseCallback() {
            override fun onStartFailure(errorCode: Int) {
                MeshBus.log("ERROR: send failed, code $errorCode")
            }
        }
        adCallback = cb
        adv.startAdvertising(settings, data, cb)

        if (stopAfterMs > 0) {
            val task = Runnable { stopAdvertising() }
            stopAdTask = task
            handler.postDelayed(task, stopAfterMs)
        }
    }

    private fun stopAdvertising() {
        stopAdTask?.let { handler.removeCallbacks(it) }
        stopAdTask = null
        adCallback?.let { runCatching { advertiser?.stopAdvertising(it) } }
        adCallback = null
    }

    private fun stopEverything() {
        handler.removeCallbacksAndMessages(null)
        stopAdvertising()
        runCatching { scanner?.stopScan(scanCallback) }
    }

    override fun onDestroy() {
        stopEverything()
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
        val now = System.currentTimeMillis()
        if (now - lastNotifyAt < 10_000) return   // don't buzz every 2 seconds
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

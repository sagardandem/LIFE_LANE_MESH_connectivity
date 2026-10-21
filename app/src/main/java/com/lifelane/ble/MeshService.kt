package com.lifelane.ble

import android.Manifest
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
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlin.random.Random

/**
 * Runs in the background (even with screen off).
 *
 * SOURCE mode (ambulance, or an online phone that got the cloud alert):
 *   every 1 s sends a signed alert with the ambulance position + heading. On Stop, sends "trip ended".
 * RELAY mode (every other phone):
 *   receives alerts, decides with ITS OWN GPS whether to show them, and ALWAYS passes them on:
 *     RED   = ahead of the ambulance and same direction
 *     AMBER = ahead but my direction unknown, or my GPS is off
 *     none  = behind, opposite direction, or far away
 *   plus smart relay, far-first, auto-expire, battery saver, long range (from v2).
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
        const val EXTRA_AMB_HEADING = "ambHeading"   // -1 = from GPS, else degrees
        const val EXTRA_AMB_POS = "ambPos"           // 0 = real GPS, 1 = 200 m before this spot, 2 = 200 m after
        const val EXTRA_MY_HEADING = "myHeading"     // -1 = from GPS, else degrees

        const val AMBULANCE_INTERVAL_MS = 1000L
        const val RELAY_ADVERTISE_MS = 4500L
        const val END_ADVERTISE_MS = 4000L
        const val ALERT_EXPIRE_MS = 15_000L
        const val SUPPRESS_AFTER = 2
        const val TEST_OFFSET_M = 200.0
        const val FIX_MAX_AGE_MS = 30_000L
        const val BEARING_MAX_AGE_MS = 20_000L

        private const val CH_SERVICE = "service"
        private const val CH_ALERT = "alert"
        private const val ID_SERVICE = 1
        private const val ID_ALERT = 2
    }

    private val handler = Handler(Looper.getMainLooper())
    private var scanner: BluetoothLeScanner? = null
    private var advertiser: BluetoothLeAdvertiser? = null
    private var codedSupported = false

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
    private var ambHeadingSetting = -1
    private var ambPosMode = 0
    private var myHeadingSetting = -1

    private val myId = Random.nextInt(1, 256)
    private val latestSeq = HashMap<Int, Int>()

    // Smart relay
    private var pendingRelay: Runnable? = null
    private var pendingKey: String? = null
    private var pendingFrom = -1
    private val otherRelayers = HashSet<Int>()

    // Alert state
    private var alertActive = false
    private var lastHeardAt = 0L
    private var fastScan: Boolean? = null
    private var lastFakeLogAt = 0L
    private var lastDecisionLog = ""

    // Location
    private var locationManager: LocationManager? = null
    private var lastFix: Location? = null
    private var lastBearing: Double? = null
    private var lastBearingAt = 0L
    private var lastGpsWaitLogAt = 0L

    // Source trip
    private val tripId = Random.nextInt(1000, 9999)
    private var seq = 0
    private var stopping = false
    private var lastSentLat = 0.0
    private var lastSentLon = 0.0
    private var lastSentHeading = 0.0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createChannels()
        if (intent?.action == ACTION_STOP) {
            handleStop(); return START_NOT_STICKY
        }

        mode = if (intent?.action == ACTION_AMBULANCE) "AMBULANCE" else "RELAY"
        var fgsType = 0
        if (Build.VERSION.SDK_INT >= 29) {
            fgsType = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            if (hasLocationPermission()) fgsType = fgsType or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        }
        ServiceCompat.startForeground(this, ID_SERVICE, serviceNotification("Running as $mode"), fgsType)

        maxHops = intent?.getIntExtra(EXTRA_MAX_HOPS, 3) ?: 3
        fake = intent?.getBooleanExtra(EXTRA_FAKE, false) ?: false
        minRelays = intent?.getIntExtra(EXTRA_MIN_RELAYS, 0) ?: 0
        shortRange = intent?.getBooleanExtra(EXTRA_SHORT_RANGE, false) ?: false
        ambHeadingSetting = intent?.getIntExtra(EXTRA_AMB_HEADING, -1) ?: -1
        ambPosMode = intent?.getIntExtra(EXTRA_AMB_POS, 0) ?: 0
        myHeadingSetting = intent?.getIntExtra(EXTRA_MY_HEADING, -1) ?: -1
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
        lastDecisionLog = ""
        MeshBus.clearAlert()
        startLocation()
        if (mode == "AMBULANCE") startAmbulance() else startRelay()
        MeshBus.setRunning(mode)
        return START_STICKY
    }

    private fun handleStop() {
        if (MeshBus.running == "AMBULANCE" && !stopping) {
            stopping = true
            handler.removeCallbacks(ambulanceTick)
            seq = (seq + 1) and 0xFFFF
            val end = AlertMessage(
                tripId, seq, maxHops, AlertMessage.TYPE_END,
                lastSentLat, lastSentLon, lastSentHeading, maxHops, myId
            ).signed()
            advertise(end, stopAfterMs = 0)
            MeshBus.log("SENT trip ended #$seq - stopping in 4 s")
            handler.postDelayed({ stopSelf() }, END_ADVERTISE_MS)
        } else if (!stopping) {
            stopSelf()
        }
    }

    // ---------------- LOCATION ----------------

    private fun hasLocationPermission() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private val locListener = object : LocationListener {
        override fun onLocationChanged(loc: Location) {
            lastFix = loc
            if (loc.hasBearing() && loc.hasSpeed() && loc.speed > 1.5f) {
                lastBearing = loc.bearing.toDouble()
                lastBearingAt = SystemClock.elapsedRealtime()
            }
        }
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    }

    private fun startLocation() {
        if (!hasLocationPermission()) {
            MeshBus.log("Location permission not given - direction check OFF")
            return
        }
        val lm = getSystemService(LOCATION_SERVICE) as LocationManager
        locationManager = lm
        var any = false
        for (p in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
            if (runCatching { lm.isProviderEnabled(p) }.getOrDefault(false)) {
                any = true
                runCatching { lm.requestLocationUpdates(p, 1000L, 0f, locListener, Looper.getMainLooper()) }
                runCatching { lm.getLastKnownLocation(p) }.getOrNull()?.let { if (lastFix == null) lastFix = it }
            }
        }
        MeshBus.log(if (any) "GPS ON - direction check active" else "Location is OFF - direction check off")
    }

    /** My current fix, or null if GPS is off / too old. */
    private fun myFix(): Location? {
        val f = lastFix ?: return null
        val ageMs = (SystemClock.elapsedRealtimeNanos() - f.elapsedRealtimeNanos) / 1_000_000
        return if (ageMs <= FIX_MAX_AGE_MS) f else null
    }

    /** My direction of travel: manual test setting, or GPS bearing while moving. */
    private fun myHeading(): Double? {
        if (myHeadingSetting >= 0) return myHeadingSetting.toDouble()
        val b = lastBearing ?: return null
        return if (SystemClock.elapsedRealtime() - lastBearingAt <= BEARING_MAX_AGE_MS) b else null
    }

    // ---------------- SOURCE (AMBULANCE / ONLINE PHONE) ----------------

    private val ambulanceTick = object : Runnable {
        override fun run() {
            handler.postDelayed(this, AMBULANCE_INTERVAL_MS)
            val fix = myFix()
            if (fix == null) {
                val now = SystemClock.elapsedRealtime()
                if (now - lastGpsWaitLogAt > 5000) {
                    lastGpsWaitLogAt = now
                    MeshBus.log("Waiting for GPS fix... (go near a window / outdoors)")
                }
                return
            }
            val heading = if (ambHeadingSetting >= 0) ambHeadingSetting.toDouble() else (lastBearing ?: 0.0)
            val (lat, lon) = when (ambPosMode) {
                1 -> Geo.offset(fix.latitude, fix.longitude, heading + 180, TEST_OFFSET_M)  // ambulance behind this spot
                2 -> Geo.offset(fix.latitude, fix.longitude, heading, TEST_OFFSET_M)        // ambulance ahead of this spot
                else -> fix.latitude to fix.longitude
            }
            lastSentLat = lat; lastSentLon = lon; lastSentHeading = heading

            seq = (seq + 1) and 0xFFFF
            var msg = AlertMessage(tripId, seq, maxHops, AlertMessage.TYPE_ALERT, lat, lon, heading, maxHops, myId).signed()
            if (fake) msg = msg.withFakeSignature()
            latestSeq[tripId] = seq
            advertise(msg, stopAfterMs = 0)
            MeshBus.log(
                "SENT #$seq heading ${heading.toInt()}° ${Geo.compass(heading)}" +
                    (if (ambHeadingSetting < 0 && lastBearing == null) " (not moving yet - heading N)" else "") +
                    (if (fake) " FAKE" else "")
            )
        }
    }

    private fun startAmbulance() {
        val pos = when (ambPosMode) { 1 -> ", TEST: 200 m before this spot"; 2 -> ", TEST: 200 m after this spot"; else -> "" }
        MeshBus.log("Source started. Trip $tripId, max $maxHops hops$pos" + (if (longRange) ", long range ON" else ""))
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
            if (fastScan == true && SystemClock.elapsedRealtime() - lastHeardAt > ALERT_EXPIRE_MS) {
                clearAlert(passed = false, why = "No signal for 15 s - alert cleared")
            }
            handler.postDelayed(this, 1000)
        }
    }

    private fun startRelay() {
        MeshBus.log(
            "Listening" + (if (minRelays > 0) " (TEST: accept only relayed $minRelays+ times)" else "") +
                (if (myHeadingSetting >= 0) " (TEST: my direction ${Geo.compass(myHeadingSetting.toDouble())})" else "") +
                (if (codedSupported) ", long-range receive ON" else "")
        )
        setScanFast(false)
        handler.post(expiryCheck)
    }

    private fun setScanFast(fast: Boolean) {
        if (fastScan == fast) return
        runCatching { scanner?.stopScan(scanCallback) }
        val filter = ScanFilter.Builder().setServiceUuid(AlertMessage.SERVICE_UUID).build()
        val b = ScanSettings.Builder()
            .setScanMode(if (fast) ScanSettings.SCAN_MODE_LOW_LATENCY else ScanSettings.SCAN_MODE_BALANCED)
        if (codedSupported) {
            b.setLegacy(false)
            b.setPhy(ScanSettings.PHY_LE_ALL_SUPPORTED)
        }
        scanner?.startScan(listOf(filter), b.build(), scanCallback)
        fastScan = fast
        MeshBus.log(if (fast) "Scan: FAST (ambulance nearby)" else "Scan: SLOW (battery saver)")
    }

    private fun onMessage(msg: AlertMessage, rssi: Int) {
        if (msg.senderId == myId) return

        if (!msg.isGenuine()) {
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
            if (key == pendingKey && msg.relayedTimes >= 1 && msg.senderId != pendingFrom) {
                otherRelayers.add(msg.senderId)
            }
            return
        }
        if (last != null && !isNewer(msg.seq, last)) return
        if (msg.relayedTimes < minRelays) return

        latestSeq[msg.tripId] = msg.seq
        lastHeardAt = SystemClock.elapsedRealtime()
        setScanFast(true)
        val how = if (msg.relayedTimes == 0) "DIRECT" else "RELAYED ${msg.relayedTimes}x"

        if (msg.type == AlertMessage.TYPE_END) {
            MeshBus.log("GOT trip ${msg.tripId} ENDED ($how)")
            clearAlert(passed = true, why = "Ambulance passed - alert cleared")
            relayLater(msg, rssi)
            return
        }

        // Decide with MY OWN GPS whether this driver is in the ambulance's way
        val fix = myFix()
        val d = Geo.decide(msg.lat, msg.lon, msg.heading, fix?.latitude, fix?.longitude, myHeading())

        val logLine = "#${msg.seq} $how rssi $rssi -> ${d.level}: ${d.reason}"
        // Log every message while the decision changes, otherwise every 5th to keep the log readable
        if (d.level.name + d.reason.take(12) != lastDecisionLog || msg.seq % 5 == 0) {
            MeshBus.log(logLine)
            lastDecisionLog = d.level.name + d.reason.take(12)
        }

        when (d.level) {
            Geo.Level.RED -> {
                alertActive = true
                MeshBus.alert("AMBULANCE APPROACHING\nGive way\n\n${d.reason}\nTrip ${msg.tripId} • $how", MeshBus.LEVEL_RED)
                showAlertNotification("Ambulance approaching - give way", d.reason)
            }
            Geo.Level.AMBER -> {
                alertActive = true
                val tip = if (fix == null) "\nTurn on location for accurate alerts" else ""
                MeshBus.alert("AMBULANCE NEARBY\nBe alert\n\n${d.reason}$tip\nTrip ${msg.tripId} • $how", MeshBus.LEVEL_AMBER)
                showAlertNotification("Ambulance nearby - be alert", d.reason)
            }
            Geo.Level.NONE -> {
                if (alertActive) clearAlert(passed = true, why = "No longer in ambulance's way (${d.reason})")
            }
        }

        // Always pass it on, even if this driver isn't alerted - phones further ahead may need it
        relayLater(msg, rssi)
    }

    private fun relayLater(msg: AlertMessage, rssi: Int) {
        if (msg.hopsLeft <= 0) return
        pendingRelay?.let { handler.removeCallbacks(it) }

        pendingKey = "${msg.tripId}:${msg.seq}"
        pendingFrom = msg.senderId
        otherRelayers.clear()

        val fwd = msg.copy(hopsLeft = msg.hopsLeft - 1, senderId = myId)
        val task = Runnable {
            pendingRelay = null
            if (otherRelayers.size >= SUPPRESS_AFTER) {
                MeshBus.log("SKIPPED relay #${msg.seq}: ${otherRelayers.size} others already relayed")
                return@Runnable
            }
            advertise(fwd, stopAfterMs = RELAY_ADVERTISE_MS)
            if (fwd.seq % 5 == 0 || fwd.type == AlertMessage.TYPE_END) {
                MeshBus.log("RELAYED #${fwd.seq} hopsLeft=${fwd.hopsLeft}" + if (fwd.type == AlertMessage.TYPE_END) " (trip ended)" else "")
            }
        }
        pendingRelay = task
        handler.postDelayed(task, relayDelayMs(rssi))
    }

    private fun relayDelayMs(rssi: Int): Long {
        val r = rssi.coerceIn(-95, -35)
        val base = 50 + (r + 95) * 250 / 60
        return base + Random.nextLong(0, 50)
    }

    private fun clearAlert(passed: Boolean, why: String) {
        val wasShown = alertActive || MeshBus.lastAlert != null
        alertActive = false
        if (!passed) setScanFast(false)
        runCatching { getSystemService(NotificationManager::class.java).cancel(ID_ALERT) }
        if (!wasShown) return
        MeshBus.log(why)
        if (passed) MeshBus.alert("AMBULANCE PASSED\nAlert cleared", MeshBus.LEVEL_GREEN) else MeshBus.clearAlert()
    }

    private fun isNewer(a: Int, b: Int): Boolean {
        val diff = (a - b) and 0xFFFF
        return diff in 1..32767
    }

    // ---------------- BLUETOOTH SEND ----------------

    private fun advertise(msg: AlertMessage, stopAfterMs: Long) {
        val adv = advertiser ?: return
        stopAdvertising()

        val mainData = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .setIncludeTxPowerLevel(false)
            .addServiceUuid(AlertMessage.SERVICE_UUID)
            .build()
        val response = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .setIncludeTxPowerLevel(false)
            .addManufacturerData(AlertMessage.COMPANY_ID, msg.toBytes())
            .build()
        val fullData = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .setIncludeTxPowerLevel(false)
            .addServiceUuid(AlertMessage.SERVICE_UUID)
            .addManufacturerData(AlertMessage.COMPANY_ID, msg.toBytes())
            .build()

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
        adv.startAdvertising(settings, mainData, response, cb)

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
            runCatching { adv.startAdvertisingSet(params, fullData, null, null, null, scb) }
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
        runCatching { locationManager?.removeUpdates(locListener) }
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
            .setContentTitle("LIFELANE")
            .setContentText(text)
            .setContentIntent(openAppIntent())
            .setOngoing(true)
            .build()

    private var lastNotifyAt = 0L
    private var lastNotifyTitle = ""

    private fun showAlertNotification(title: String, text: String) {
        val now = SystemClock.elapsedRealtime()
        if (title == lastNotifyTitle && now - lastNotifyAt < 10_000) return   // don't buzz every second
        lastNotifyAt = now
        lastNotifyTitle = title
        val n = NotificationCompat.Builder(this, CH_ALERT)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle(title)
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(openAppIntent())
            .setAutoCancel(true)
            .build()
        runCatching { getSystemService(NotificationManager::class.java).notify(ID_ALERT, n) }
    }
}

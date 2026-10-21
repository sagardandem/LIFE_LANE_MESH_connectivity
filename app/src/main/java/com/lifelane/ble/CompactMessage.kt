package com.lifelane.ble

import android.bluetooth.le.ScanRecord
import android.os.ParcelUuid
import java.nio.ByteBuffer
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Short message sent by iPhones (Source or Relay).
 *
 * Apple only lets iPhone apps broadcast service IDs and a name (28 bytes, app on screen only),
 * so the iPhone hides the alert inside one 128-bit "service ID" (16 bytes) + an 8-character name.
 *
 * Service ID bytes:
 *  0..3   "LIFE" (0x4C 0x49 0x46 0x45) - Android scan filter matches these 4 bytes
 *  4      high 4 bits: format version (1) | bit 3: type (0 alert, 1 trip ended) | bits 0-2: hopsLeft
 *  5..6   tripId (16 bits)
 *  7      seq (low 8 bits)
 *  8..12  position: latitude*10,000 (20 bits) then (longitude-40)*10,000 (20 bits)  (~11 m precision, India)
 *  13     heading (0..255 = 0..360 degrees)
 *  14..15 signature bytes 1-2
 * Name: signature bytes 3-6 as 8 hex characters.
 * Signature = HMAC-SHA256 over bytes 0..13 with the hopsLeft bits set to 0 (relays change hopsLeft).
 *
 * Android turns it into a normal version-4 message, so everything else (direction check, relay,
 * travel buddy) works the same. Android always relays in the normal format.
 */
object CompactMessage {
    val FILTER_UUID: ParcelUuid = ParcelUuid.fromString("4C494645-0000-0000-0000-000000000000")
    val FILTER_MASK: ParcelUuid = ParcelUuid.fromString("FFFFFFFF-0000-0000-0000-000000000000")
    const val MAX_HOPS = 3
    private val KEY = "LifeLane-demo-key-2026".toByteArray()

    class Raw(
        val tripId: Int, val seq8: Int, val type: Int, val hopsLeft: Int,
        val lat: Double, val lon: Double, val heading: Double, val genuine: Boolean,
    )

    private fun bytesOf(u: UUID): ByteArray =
        ByteBuffer.allocate(16).putLong(u.mostSignificantBits).putLong(u.leastSignificantBits).array()

    /** Look for an iPhone short message in a scan result. */
    fun find(record: ScanRecord?): Raw? {
        val uuids = record?.serviceUuids ?: return null
        for (pu in uuids) {
            val b = bytesOf(pu.uuid)
            if (b[0] == 0x4C.toByte() && b[1] == 0x49.toByte() && b[2] == 0x46.toByte() && b[3] == 0x45.toByte()) {
                return decode(b, record.deviceName)
            }
        }
        return null
    }

    private fun decode(b: ByteArray, name: String?): Raw? {
        val ver = (b[4].toInt() shr 4) and 0x0F
        if (ver != 1) return null
        val type = (b[4].toInt() shr 3) and 1
        val hops = (b[4].toInt() and 7).coerceAtMost(MAX_HOPS)
        val trip = ((b[5].toInt() and 0xFF) shl 8) or (b[6].toInt() and 0xFF)
        val seq8 = b[7].toInt() and 0xFF
        var pos = 0L
        for (i in 8..12) pos = (pos shl 8) or (b[i].toLong() and 0xFF)
        val latU = (pos shr 20) and 0xFFFFF
        val lonU = pos and 0xFFFFF
        val lat = latU / 10_000.0
        val lon = lonU / 10_000.0 + 40.0
        val heading = (b[13].toInt() and 0xFF) * 360.0 / 256

        val signed = b.copyOf(14)
        signed[4] = (signed[4].toInt() and 0xF8).toByte()
        val mac = Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(KEY, "HmacSHA256")); doFinal(signed)
        }
        val expectName = (2..5).joinToString("") { "%02x".format(mac[it].toInt() and 0xFF) }
        val genuine = name != null && name.length >= 8 &&
            mac[0] == b[14] && mac[1] == b[15] && name.substring(0, 8).equals(expectName, ignoreCase = true)
        return Raw(trip, seq8, type, hops, lat, lon, heading, genuine)
    }

    /** Rebuild the full 16-bit message number from the low 8 bits, using the last one seen. */
    fun expandSeq(seq8: Int, last: Int?): Int {
        if (last == null) return seq8
        val d = (seq8 - (last and 0xFF)) and 0xFF
        return if (d < 128) (last + d) and 0xFFFF else (last - (256 - d)) and 0xFFFF
    }

    /** Convert to a normal version-4 message (sender id 0 = "an iPhone"). */
    fun toAlert(r: Raw, seq16: Int): AlertMessage {
        val m = AlertMessage(r.tripId, seq16, MAX_HOPS, r.type, r.lat, r.lon, r.heading, r.hopsLeft, 0)
        return if (r.genuine) m.signed() else m.withFakeSignature()
    }
}

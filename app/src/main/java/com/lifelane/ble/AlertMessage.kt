package com.lifelane.ble

import android.os.ParcelUuid
import java.nio.ByteBuffer
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * The message that travels phone to phone over Bluetooth (version 4, 27 bytes).
 * Sent in the scan response (31 bytes max = 4 bytes header + 27).
 *
 * Byte layout:
 *  0       magic 'L' (0x4C)
 *  1       version (4)
 *  2..5    tripId
 *  6..7    seq       - message number, goes up every second
 *  8       maxHops   - hop limit (3 or 5)
 *  9       type      - 0 = ALERT, 1 = TRIP ENDED
 *  10..13  ambulance latitude  * 1,000,000
 *  14..17  ambulance longitude * 1,000,000
 *  18      ambulance heading (0..255 = 0..360 degrees)
 *  19      low 4 bits: hopsLeft, bits 4-5: senderLevel   (not signed - changes at each phone)
 *  20      senderId  - random id of the phone that sent THIS copy (not signed)
 *  21..26  signature - first 6 bytes of HMAC-SHA256 over bytes 0..18
 *
 * senderLevel = the sending phone's OWN decision ("travel buddy" feature):
 *   0 = unknown (source, or sender has no GPS/direction)
 *   1 = RED   (sender is sure it is in the ambulance's way)
 *   2 = AMBER (sender is ahead but not sure of its direction)
 *   3 = CLEAR (sender is sure it is NOT in the ambulance's way)
 */
class AlertMessage(
    val tripId: Int,
    val seq: Int,
    val maxHops: Int,
    val type: Int,
    val lat: Double,
    val lon: Double,
    val heading: Double,
    val hopsLeft: Int,
    val senderId: Int,
    val senderLevel: Int = LVL_UNKNOWN,
    val signature: ByteArray = ByteArray(SIG_SIZE),
) {
    val relayedTimes: Int get() = maxHops - hopsLeft

    fun copy(hopsLeft: Int = this.hopsLeft, senderId: Int = this.senderId, senderLevel: Int = this.senderLevel) =
        AlertMessage(tripId, seq, maxHops, type, lat, lon, heading, hopsLeft, senderId, senderLevel, signature)

    fun signed() =
        AlertMessage(tripId, seq, maxHops, type, lat, lon, heading, hopsLeft, senderId, senderLevel, sign(header()))

    fun withFakeSignature() =
        AlertMessage(tripId, seq, maxHops, type, lat, lon, heading, hopsLeft, senderId, senderLevel, ByteArray(SIG_SIZE) { 0x5A })

    fun isGenuine(): Boolean = MessageDigest.isEqual(signature, sign(header()))

    private fun header(): ByteArray = ByteBuffer.allocate(SIGNED_SIZE)
        .put(MAGIC).put(VERSION)
        .putInt(tripId)
        .putShort(seq.toShort())
        .put(maxHops.toByte())
        .put(type.toByte())
        .putInt(Math.round(lat * 1_000_000).toInt())
        .putInt(Math.round(lon * 1_000_000).toInt())
        .put(headingToByte(heading))
        .array()

    fun toBytes(): ByteArray = ByteBuffer.allocate(SIZE)
        .put(header())
        .put(((hopsLeft and 0x0F) or ((senderLevel and 0x03) shl 4)).toByte())
        .put(senderId.toByte())
        .put(signature)
        .array()

    companion object {
        const val SIZE = 27
        private const val SIGNED_SIZE = 19
        private const val SIG_SIZE = 6
        const val MAGIC: Byte = 0x4C
        const val VERSION: Byte = 4

        const val TYPE_ALERT = 0
        const val TYPE_END = 1

        const val LVL_UNKNOWN = 0
        const val LVL_RED = 1
        const val LVL_AMBER = 2
        const val LVL_CLEAR = 3

        const val COMPANY_ID = 0xFFFF

        /** LIFELANE service ID (free 128-bit UUID). In the main broadcast so iPhones can find us when locked. */
        val SERVICE_UUID: ParcelUuid = ParcelUuid.fromString("C51EB7FB-9202-43B3-B906-DC0E1B471453")

        /** PROTOTYPE ONLY: shared key built into the app. A real system gives keys only to verified sources. */
        private val KEY = "LifeLane-demo-key-2026".toByteArray()

        private fun sign(data: ByteArray): ByteArray {
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(KEY, "HmacSHA256"))
            return mac.doFinal(data).copyOf(SIG_SIZE)
        }

        private fun headingToByte(deg: Double): Byte {
            val norm = ((deg % 360) + 360) % 360
            return (Math.round(norm * 256 / 360).toInt() and 0xFF).toByte()
        }

        fun fromBytes(b: ByteArray?): AlertMessage? {
            if (b == null || b.size < SIZE || b[0] != MAGIC || b[1] != VERSION) return null
            val buf = ByteBuffer.wrap(b)
            buf.position(2)
            val tripId = buf.int
            val seq = buf.short.toInt() and 0xFFFF
            val maxHops = buf.get().toInt()
            val type = buf.get().toInt()
            val lat = buf.int / 1_000_000.0
            val lon = buf.int / 1_000_000.0
            val heading = (buf.get().toInt() and 0xFF) * 360.0 / 256
            val packed = buf.get().toInt() and 0xFF
            val hopsLeft = packed and 0x0F
            val senderLevel = (packed shr 4) and 0x03
            val senderId = buf.get().toInt() and 0xFF
            val sig = ByteArray(SIG_SIZE).also { buf.get(it) }
            if (maxHops !in 1..7 || hopsLeft !in 0..maxHops || type !in 0..1) return null
            if (lat !in -90.0..90.0 || lon !in -180.0..180.0) return null
            return AlertMessage(tripId, seq, maxHops, type, lat, lon, heading, hopsLeft, senderId, senderLevel, sig)
        }
    }
}

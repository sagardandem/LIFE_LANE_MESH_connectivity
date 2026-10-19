package com.lifelane.ble

import android.os.ParcelUuid
import java.nio.ByteBuffer
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * The message that travels phone to phone over Bluetooth (version 2, 20 bytes).
 * Fits in one normal BLE advertisement (31 bytes max).
 *
 * Byte layout:
 *  0       magic 'L' (0x4C) - lets us ignore other Bluetooth devices
 *  1       version (2)
 *  2..5    tripId    - which ambulance trip
 *  6..7    seq       - message number, goes up every second
 *  8       maxHops   - hop limit chosen by the ambulance (3 or 5)
 *  9       type      - 0 = ALERT, 1 = TRIP ENDED (ambulance passed)
 *  10      hopsLeft  - how many more times it may be passed on (changes at each relay)
 *  11      senderId  - random id of the phone that sent THIS copy (changes at each relay)
 *  12..19  signature - first 8 bytes of HMAC-SHA256 over bytes 0..9
 *
 * hopsLeft and senderId are NOT signed, because relaying phones change them.
 */
class AlertMessage(
    val tripId: Int,
    val seq: Int,
    val maxHops: Int,
    val type: Int,
    val hopsLeft: Int,
    val senderId: Int,
    val signature: ByteArray = ByteArray(SIG_SIZE),
) {
    /** How many phones passed this copy on before it reached us. 0 = straight from the ambulance. */
    val relayedTimes: Int get() = maxHops - hopsLeft

    fun copy(hopsLeft: Int = this.hopsLeft, senderId: Int = this.senderId) =
        AlertMessage(tripId, seq, maxHops, type, hopsLeft, senderId, signature)

    /** Ambulance only: add the secret signature. */
    fun signed() = AlertMessage(tripId, seq, maxHops, type, hopsLeft, senderId, sign(header()))

    /** Test only: a message with a wrong signature, like a fake ambulance would send. */
    fun withFakeSignature() = AlertMessage(tripId, seq, maxHops, type, hopsLeft, senderId, ByteArray(SIG_SIZE) { 0x5A })

    fun isGenuine(): Boolean = MessageDigest.isEqual(signature, sign(header()))

    private fun header(): ByteArray = ByteBuffer.allocate(SIGNED_SIZE)
        .put(MAGIC).put(VERSION)
        .putInt(tripId)
        .putShort(seq.toShort())
        .put(maxHops.toByte())
        .put(type.toByte())
        .array()

    fun toBytes(): ByteArray = ByteBuffer.allocate(SIZE)
        .put(header())
        .put(hopsLeft.toByte())
        .put(senderId.toByte())
        .put(signature)
        .array()

    companion object {
        const val SIZE = 20
        private const val SIGNED_SIZE = 10
        private const val SIG_SIZE = 8
        const val MAGIC: Byte = 0x4C
        const val VERSION: Byte = 2

        const val TYPE_ALERT = 0
        const val TYPE_END = 1

        /** Test company ID (0xFFFF is reserved for testing by the Bluetooth SIG). */
        const val COMPANY_ID = 0xFFFF

        /**
         * LifeLane service ID (free 128-bit UUID we generated ourselves).
         * Sent in the main broadcast so iPhones can find us even with the screen locked
         * (Apple only allows background scanning for a specific service ID).
         * The 20-byte message itself goes in the scan response.
         */
        val SERVICE_UUID: ParcelUuid = ParcelUuid.fromString("C51EB7FB-9202-43B3-B906-DC0E1B471453")

        /**
         * PROTOTYPE ONLY: a shared secret built into the app.
         * In a real system only ambulances would hold the key (e.g. public-key signatures
         * issued by 108), so a normal phone could check alerts but not create them.
         */
        private val KEY = "LifeLane-demo-key-2026".toByteArray()

        private fun sign(data: ByteArray): ByteArray {
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(KEY, "HmacSHA256"))
            return mac.doFinal(data).copyOf(SIG_SIZE)
        }

        fun fromBytes(b: ByteArray?): AlertMessage? {
            if (b == null || b.size < SIZE || b[0] != MAGIC || b[1] != VERSION) return null
            val buf = ByteBuffer.wrap(b)
            buf.position(2)
            val tripId = buf.int
            val seq = buf.short.toInt() and 0xFFFF
            val maxHops = buf.get().toInt()
            val type = buf.get().toInt()
            val hopsLeft = buf.get().toInt()
            val senderId = buf.get().toInt() and 0xFF
            val sig = ByteArray(SIG_SIZE).also { buf.get(it) }
            if (maxHops !in 1..7 || hopsLeft !in 0..maxHops || type !in 0..1) return null
            return AlertMessage(tripId, seq, maxHops, type, hopsLeft, senderId, sig)
        }
    }
}

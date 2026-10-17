package com.lifelane.ble

import java.nio.ByteBuffer

/**
 * The alert that travels phone to phone over Bluetooth.
 * Packed into 17 bytes so it fits in one normal BLE advertisement (31 bytes max).
 *
 * Byte layout:
 *  0      magic 'L' (0x4C) - lets us ignore other Bluetooth devices
 *  1      version (1)
 *  2..5   tripId    - which ambulance trip
 *  6..7   seq       - message number, goes up every 2 seconds
 *  8      hopsLeft  - how many more times it may be passed on
 *  9..12  lat * 1,000,000
 *  13..16 lon * 1,000,000
 */
data class AlertMessage(
    val tripId: Int,
    val seq: Int,
    val hopsLeft: Int,
    val lat: Double,
    val lon: Double,
) {
    fun toBytes(): ByteArray = ByteBuffer.allocate(SIZE)
        .put(MAGIC)
        .put(VERSION)
        .putInt(tripId)
        .putShort(seq.toShort())
        .put(hopsLeft.toByte())
        .putInt((lat * 1_000_000).toInt())
        .putInt((lon * 1_000_000).toInt())
        .array()

    companion object {
        const val SIZE = 17
        const val MAGIC: Byte = 0x4C
        const val VERSION: Byte = 1

        /** Test company ID (0xFFFF is reserved for testing by the Bluetooth SIG). */
        const val COMPANY_ID = 0xFFFF

        fun fromBytes(b: ByteArray?): AlertMessage? {
            if (b == null || b.size < SIZE || b[0] != MAGIC || b[1] != VERSION) return null
            val buf = ByteBuffer.wrap(b)
            buf.position(2)
            val tripId = buf.int
            val seq = buf.short.toInt() and 0xFFFF
            val hops = buf.get().toInt()
            val lat = buf.int / 1_000_000.0
            val lon = buf.int / 1_000_000.0
            return AlertMessage(tripId, seq, hops, lat, lon)
        }
    }
}

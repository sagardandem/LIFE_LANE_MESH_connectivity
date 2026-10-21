package com.lifelane.ble

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Small geometry helpers and the "should this driver be alerted?" decision. */
object Geo {
    private const val R = 6_371_000.0

    const val ALERT_DISTANCE_M = 600.0   // ambulance within 600 m
    const val AHEAD_ANGLE = 60.0         // driver is in front of the ambulance (within ±60° of its heading)
    const val SAME_DIR_ANGLE = 45.0      // driver heading within 45° of ambulance heading

    fun distanceM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val p1 = Math.toRadians(lat1); val p2 = Math.toRadians(lat2)
        val dp = p2 - p1; val dl = Math.toRadians(lon2 - lon1)
        val a = sin(dp / 2) * sin(dp / 2) + cos(p1) * cos(p2) * sin(dl / 2) * sin(dl / 2)
        return 2 * R * atan2(sqrt(a), sqrt(1 - a))
    }

    /** Compass bearing from point 1 to point 2, 0..360 degrees. */
    fun bearing(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val p1 = Math.toRadians(lat1); val p2 = Math.toRadians(lat2)
        val dl = Math.toRadians(lon2 - lon1)
        val y = sin(dl) * cos(p2)
        val x = cos(p1) * sin(p2) - sin(p1) * cos(p2) * cos(dl)
        return (Math.toDegrees(atan2(y, x)) + 360) % 360
    }

    /** Move a point `dist` metres in direction `bearingDeg`. */
    fun offset(lat: Double, lon: Double, bearingDeg: Double, dist: Double): Pair<Double, Double> {
        val d = dist / R; val b = Math.toRadians(bearingDeg)
        val p1 = Math.toRadians(lat); val l1 = Math.toRadians(lon)
        val p2 = Math.asin(sin(p1) * cos(d) + cos(p1) * sin(d) * cos(b))
        val l2 = l1 + atan2(sin(b) * sin(d) * cos(p1), cos(d) - sin(p1) * sin(p2))
        return Math.toDegrees(p2) to Math.toDegrees(l2)
    }

    /** Smallest difference between two angles, 0..180. */
    fun angleDiff(a: Double, b: Double): Double {
        val d = abs(((a - b) % 360 + 360) % 360)
        return if (d > 180) 360 - d else d
    }

    enum class Level { RED, AMBER, NONE }
    data class Decision(val level: Level, val reason: String)

    /**
     * myLat/myLon null = phone has no GPS fix. myHeading null = direction unknown (not moving).
     */
    const val PASSED_M = 20.0           // ambulance more than 20 m past me (along its direction) = passed
    const val SIDE_M = 100.0            // more than 100 m to the side of the ambulance's line = other road

    fun decide(
        ambLat: Double, ambLon: Double, ambHeading: Double,
        myLat: Double?, myLon: Double?, myHeading: Double?,
    ): Decision {
        if (myLat == null || myLon == null) {
            return Decision(Level.AMBER, "GPS off - can't check direction")
        }
        val dist = distanceM(ambLat, ambLon, myLat, myLon)
        if (dist > ALERT_DISTANCE_M) return Decision(Level.NONE, "far away (${dist.toInt()} m)")

        // Split the distance into "along the ambulance's direction" and "to the side".
        val theta = Math.toRadians(bearing(ambLat, ambLon, myLat, myLon) - ambHeading)
        val along = dist * cos(theta)          // + = ahead of ambulance, - = behind
        val side = abs(dist * sin(theta))
        if (along < -PASSED_M) return Decision(Level.NONE, "behind ambulance - passed (${(-along).toInt()} m)")
        if (side > SIDE_M) return Decision(Level.NONE, "beside / other road (${side.toInt()} m to the side)")
        if (myHeading == null) {
            return Decision(Level.AMBER, "ahead, ${dist.toInt()} m - my direction unknown")
        }
        val diff = angleDiff(myHeading, ambHeading)
        return if (diff <= SAME_DIR_ANGLE) {
            Decision(Level.RED, "ahead, same direction, ${dist.toInt()} m")
        } else {
            Decision(Level.NONE, "different direction (${diff.toInt()}°)")
        }
    }

    fun compass(deg: Double): String {
        val names = arrayOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")
        return names[(((deg + 22.5) % 360) / 45).toInt()]
    }
}

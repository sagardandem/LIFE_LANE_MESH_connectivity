import CoreBluetooth
import CryptoKit
import Foundation

/// Same 27-byte message as the Android app (version 3).
///
///  0      magic 'L' (0x4C)
///  1      version (3)
///  2..5   tripId (big-endian)
///  6..7   seq (big-endian)
///  8      maxHops
///  9      type: 0 = ALERT, 1 = TRIP ENDED
///  10..13 ambulance latitude  * 1,000,000 (signed, big-endian)
///  14..17 ambulance longitude * 1,000,000 (signed, big-endian)
///  18     ambulance heading (0..255 = 0..360 degrees)
///  19     hopsLeft
///  20     senderId
///  21..26 signature = first 6 bytes of HMAC-SHA256 over bytes 0..18
struct AlertMessage {
    /// LIFELANE service ID - must match the Android app.
    static let serviceUUID = CBUUID(string: "C51EB7FB-9202-43B3-B906-DC0E1B471453")
    static let companyId: UInt16 = 0xFFFF
    static let size = 27
    static let signedSize = 19
    static let typeAlert = 0
    static let typeEnd = 1

    /// PROTOTYPE ONLY: same shared key as the Android app.
    private static let key = SymmetricKey(data: Data("LifeLane-demo-key-2026".utf8))

    let tripId: UInt32
    let seq: UInt16
    let maxHops: Int
    let type: Int
    let lat: Double
    let lon: Double
    let heading: Double
    let hopsLeft: Int
    let genuine: Bool

    var relayedTimes: Int { maxHops - hopsLeft }

    /// `data` is CoreBluetooth's manufacturer data: 2-byte company ID (little-endian) + our 27 bytes.
    static func parse(manufacturerData data: Data) -> AlertMessage? {
        let b = [UInt8](data)
        guard b.count >= 2 + size else { return nil }
        let company = UInt16(b[0]) | (UInt16(b[1]) << 8)
        guard company == companyId else { return nil }

        let p = Array(b[2..<(2 + size)])
        guard p[0] == 0x4C, p[1] == 3 else { return nil }

        func u32(_ i: Int) -> UInt32 {
            (UInt32(p[i]) << 24) | (UInt32(p[i + 1]) << 16) | (UInt32(p[i + 2]) << 8) | UInt32(p[i + 3])
        }
        let tripId = u32(2)
        let seq = (UInt16(p[6]) << 8) | UInt16(p[7])
        let maxHops = Int(p[8])
        let type = Int(p[9])
        let lat = Double(Int32(bitPattern: u32(10))) / 1_000_000
        let lon = Double(Int32(bitPattern: u32(14))) / 1_000_000
        let heading = Double(p[18]) * 360 / 256
        let hopsLeft = Int(p[19])
        guard (1...7).contains(maxHops), (0...maxHops).contains(hopsLeft), (0...1).contains(type) else { return nil }

        let mac = HMAC<SHA256>.authenticationCode(for: Data(p[0..<signedSize]), using: key)
        let expected = Array(Data(mac).prefix(6))
        let genuine = expected == Array(p[21..<27])

        return AlertMessage(tripId: tripId, seq: seq, maxHops: maxHops, type: type,
                            lat: lat, lon: lon, heading: heading, hopsLeft: hopsLeft, genuine: genuine)
    }
}

/// Same decision rules as the Android app (Geo.kt).
enum Geo {
    static let alertDistance = 600.0
    static let aheadAngle = 60.0
    static let sameDirAngle = 45.0

    enum Level { case red, amber, none }

    static func distance(_ lat1: Double, _ lon1: Double, _ lat2: Double, _ lon2: Double) -> Double {
        let r = 6_371_000.0
        let p1 = lat1 * .pi / 180, p2 = lat2 * .pi / 180
        let dp = p2 - p1, dl = (lon2 - lon1) * .pi / 180
        let a = sin(dp / 2) * sin(dp / 2) + cos(p1) * cos(p2) * sin(dl / 2) * sin(dl / 2)
        return 2 * r * atan2(sqrt(a), sqrt(1 - a))
    }

    static func bearing(_ lat1: Double, _ lon1: Double, _ lat2: Double, _ lon2: Double) -> Double {
        let p1 = lat1 * .pi / 180, p2 = lat2 * .pi / 180
        let dl = (lon2 - lon1) * .pi / 180
        let y = sin(dl) * cos(p2)
        let x = cos(p1) * sin(p2) - sin(p1) * cos(p2) * cos(dl)
        let deg = atan2(y, x) * 180 / .pi
        return (deg + 360).truncatingRemainder(dividingBy: 360)
    }

    static func angleDiff(_ a: Double, _ b: Double) -> Double {
        var d = abs((a - b).truncatingRemainder(dividingBy: 360))
        if d > 180 { d = 360 - d }
        return d
    }

    /// myLat/myLon nil = no GPS. myHeading nil = direction unknown (not moving).
    static func decide(ambLat: Double, ambLon: Double, ambHeading: Double,
                       myLat: Double?, myLon: Double?, myHeading: Double?) -> (Level, String) {
        guard let myLat = myLat, let myLon = myLon else {
            return (.amber, "GPS off - can't check direction")
        }
        let dist = distance(ambLat, ambLon, myLat, myLon)
        if dist > alertDistance { return (.none, "far away (\(Int(dist)) m)") }
        if dist > 30 {
            let toMe = bearing(ambLat, ambLon, myLat, myLon)
            if angleDiff(toMe, ambHeading) > aheadAngle {
                return (.none, "behind / beside ambulance (\(Int(dist)) m)")
            }
        }
        guard let myHeading = myHeading else {
            return (.amber, "ahead, \(Int(dist)) m - my direction unknown")
        }
        let diff = angleDiff(myHeading, ambHeading)
        if diff <= sameDirAngle { return (.red, "ahead, same direction, \(Int(dist)) m") }
        return (.none, "different direction (\(Int(diff))°)")
    }
}

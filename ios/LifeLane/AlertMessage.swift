import CoreBluetooth
import CryptoKit
import Foundation

/// Same 27-byte message as the Android app (version 4).
///
///  0      magic 'L' (0x4C)
///  1      version (4)
///  2..5   tripId (big-endian)
///  6..7   seq (big-endian)
///  8      maxHops
///  9      type: 0 = ALERT, 1 = TRIP ENDED
///  10..13 ambulance latitude  * 1,000,000 (signed, big-endian)
///  14..17 ambulance longitude * 1,000,000 (signed, big-endian)
///  18     ambulance heading (0..255 = 0..360 degrees)
///  19     low 4 bits hopsLeft, bits 4-5 senderLevel (0 unknown, 1 RED, 2 AMBER, 3 CLEAR)
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
    static let lvlUnknown = 0, lvlRed = 1, lvlAmber = 2, lvlClear = 3

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
    let senderId: Int
    let senderLevel: Int
    let genuine: Bool

    var relayedTimes: Int { maxHops - hopsLeft }

    /// `data` is CoreBluetooth's manufacturer data: 2-byte company ID (little-endian) + our 27 bytes.
    static func parse(manufacturerData data: Data) -> AlertMessage? {
        let b = [UInt8](data)
        guard b.count >= 2 + size else { return nil }
        let company = UInt16(b[0]) | (UInt16(b[1]) << 8)
        guard company == companyId else { return nil }

        let p = Array(b[2..<(2 + size)])
        guard p[0] == 0x4C, p[1] == 4 else { return nil }

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
        let hopsLeft = Int(p[19] & 0x0F)
        let senderLevel = Int((p[19] >> 4) & 0x03)
        let senderId = Int(p[20])
        guard (1...7).contains(maxHops), (0...maxHops).contains(hopsLeft), (0...1).contains(type) else { return nil }

        let mac = HMAC<SHA256>.authenticationCode(for: Data(p[0..<signedSize]), using: key)
        let expected = Array(Data(mac).prefix(6))
        let genuine = expected == Array(p[21..<27])

        return AlertMessage(tripId: tripId, seq: seq, maxHops: maxHops, type: type,
                            lat: lat, lon: lon, heading: heading, hopsLeft: hopsLeft,
                            senderId: senderId, senderLevel: senderLevel, genuine: genuine)
    }
}

/// Same decision rules as the Android app (Geo.kt).
enum Geo {
    static let alertDistance = 600.0
    static let aheadAngle = 60.0
    static let sameDirAngle = 60.0   // GPS wobble, curves, lane changes

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
        let theta = (bearing(ambLat, ambLon, myLat, myLon) - ambHeading) * .pi / 180
        let along = dist * cos(theta)     // + ahead of ambulance, - behind
        let side = abs(dist * sin(theta))
        if along < -20 { return (.none, "behind ambulance - passed (\(Int(-along)) m)") }
        if side > 100 { return (.none, "beside / other road (\(Int(side)) m to the side)") }
        guard let myHeading = myHeading else {
            return (.amber, "ahead, \(Int(dist)) m - my direction unknown")
        }
        let diff = angleDiff(myHeading, ambHeading)
        if diff <= sameDirAngle { return (.red, "ahead, same direction, \(Int(dist)) m") }
        return (.none, "different direction (\(Int(diff))°)")
    }
}

extension Geo {
    /// Move a point `dist` metres in direction `bearing` (degrees).
    static func offset(_ lat: Double, _ lon: Double, bearing: Double, dist: Double) -> (Double, Double) {
        let r = 6_371_000.0, d = dist / r, b = bearing * .pi / 180
        let p1 = lat * .pi / 180, l1 = lon * .pi / 180
        let p2 = asin(sin(p1) * cos(d) + cos(p1) * sin(d) * cos(b))
        let l2 = l1 + atan2(sin(b) * sin(d) * cos(p1), cos(d) - sin(p1) * sin(p2))
        return (p2 * 180 / .pi, l2 * 180 / .pi)
    }
}

/// Short message an iPhone sends (as Source or Relay). Must match CompactMessage.kt on Android.
///
/// Apple only lets apps broadcast service IDs and a name (28 bytes, app on screen only), so the alert
/// is hidden inside one 128-bit service ID (16 bytes) + an 8-character name.
///  0..3   "LIFE"
///  4      high 4 bits version (1) | bit 3 type (0 alert, 1 ended) | bits 0-2 hopsLeft
///  5..6   tripId (16 bits)
///  7      seq (low 8 bits)
///  8..12  latitude*10,000 (20 bits) then (longitude-40)*10,000 (20 bits)
///  13     heading (0..255)
///  14..15 signature bytes 1-2;  name = signature bytes 3-6 as 8 hex characters
enum Compact {
    static let maxHops = 3
    private static let key = SymmetricKey(data: Data("LifeLane-demo-key-2026".utf8))

    struct Raw {
        let tripId: UInt32
        let seq8: UInt8
        let type: Int
        let hopsLeft: Int
        let lat: Double
        let lon: Double
        let heading: Double
        let genuine: Bool

        func toAlert(seq: UInt16) -> AlertMessage {
            AlertMessage(tripId: tripId, seq: seq, maxHops: Compact.maxHops, type: type,
                         lat: lat, lon: lon, heading: heading, hopsLeft: hopsLeft,
                         senderId: 0, senderLevel: AlertMessage.lvlUnknown, genuine: genuine)
        }
    }

    private static func mac(_ b: [UInt8]) -> [UInt8] {
        var s = Array(b[0..<14])
        s[4] &= 0xF8                       // hopsLeft is not signed (relays change it)
        return Array(Data(HMAC<SHA256>.authenticationCode(for: Data(s), using: key)))
    }

    static func encode(tripId: UInt32, seq: UInt16, type: Int, hopsLeft: Int,
                       lat: Double, lon: Double, heading: Double, fake: Bool) -> (CBUUID, String) {
        var b = [UInt8](repeating: 0, count: 16)
        b[0] = 0x4C; b[1] = 0x49; b[2] = 0x46; b[3] = 0x45
        b[4] = UInt8((1 << 4) | ((type & 1) << 3) | (min(hopsLeft, 7) & 7))
        b[5] = UInt8((tripId >> 8) & 0xFF)
        b[6] = UInt8(tripId & 0xFF)
        b[7] = UInt8(seq & 0xFF)
        let latU = UInt64(min(1_048_575.0, max(0.0, (lat * 10_000).rounded())))
        let lonU = UInt64(min(1_048_575.0, max(0.0, ((lon - 40) * 10_000).rounded())))
        let pos = (latU << 20) | lonU
        for i in 0..<5 { b[8 + i] = UInt8((pos >> UInt64(8 * (4 - i))) & 0xFF) }
        let norm = (heading.truncatingRemainder(dividingBy: 360) + 360).truncatingRemainder(dividingBy: 360)
        b[13] = UInt8(Int((norm * 256 / 360).rounded()) & 0xFF)
        let m = fake ? [UInt8](repeating: 0x5A, count: 32) : mac(b)
        b[14] = m[0]; b[15] = m[1]
        let name = m[2..<6].map { String(format: "%02x", $0) }.joined()
        return (CBUUID(data: Data(b)), name)
    }

    static func find(uuids: [CBUUID], name: String?) -> Raw? {
        for u in uuids {
            let b = [UInt8](u.data)
            if b.count == 16 && b[0] == 0x4C && b[1] == 0x49 && b[2] == 0x46 && b[3] == 0x45 {
                return decode(b, name: name)
            }
        }
        return nil
    }

    private static func decode(_ b: [UInt8], name: String?) -> Raw? {
        guard (b[4] >> 4) & 0x0F == 1 else { return nil }
        let type = Int((b[4] >> 3) & 1)
        let hops = min(Int(b[4] & 7), maxHops)
        let trip = (UInt32(b[5]) << 8) | UInt32(b[6])
        var pos: UInt64 = 0
        for i in 8...12 { pos = (pos << 8) | UInt64(b[i]) }
        let lat = Double((pos >> 20) & 0xFFFFF) / 10_000
        let lon = Double(pos & 0xFFFFF) / 10_000 + 40
        let heading = Double(b[13]) * 360 / 256
        let m = mac(b)
        let expName = m[2..<6].map { String(format: "%02x", $0) }.joined()
        var nameOk = false
        if let n = name, n.count >= 8 { nameOk = n.prefix(8).lowercased() == expName }
        let genuine = nameOk && m[0] == b[14] && m[1] == b[15]
        return Raw(tripId: trip, seq8: b[7], type: type, hopsLeft: hops,
                   lat: lat, lon: lon, heading: heading, genuine: genuine)
    }

    /// Rebuild the full message number from its low 8 bits, using the last one seen.
    static func expandSeq(_ s8: UInt8, last: UInt16?) -> UInt16 {
        guard let last = last else { return UInt16(s8) }
        let d = (Int(s8) - Int(last & 0xFF)) & 0xFF
        let v = d < 128 ? Int(last) + d : Int(last) - (256 - d)
        return UInt16(truncatingIfNeeded: v & 0xFFFF)
    }
}

import CoreBluetooth
import CryptoKit
import Foundation

/// Same 20-byte message as the Android app (version 2).
///
///  0      magic 'L' (0x4C)
///  1      version (2)
///  2..5   tripId (big-endian)
///  6..7   seq (big-endian)
///  8      maxHops
///  9      type: 0 = ALERT, 1 = TRIP ENDED
///  10     hopsLeft
///  11     senderId
///  12..19 signature = first 8 bytes of HMAC-SHA256 over bytes 0..9
struct AlertMessage {
    /// LifeLane service ID - must match the Android app.
    static let serviceUUID = CBUUID(string: "C51EB7FB-9202-43B3-B906-DC0E1B471453")
    static let companyId: UInt16 = 0xFFFF
    static let size = 20
    static let typeAlert = 0
    static let typeEnd = 1

    /// PROTOTYPE ONLY: same shared key as the Android app.
    private static let key = SymmetricKey(data: Data("LifeLane-demo-key-2026".utf8))

    let tripId: UInt32
    let seq: UInt16
    let maxHops: Int
    let type: Int
    let hopsLeft: Int
    let senderId: Int
    let genuine: Bool

    var relayedTimes: Int { maxHops - hopsLeft }

    /// `data` is CoreBluetooth's manufacturer data: 2-byte company ID (little-endian) + our 20 bytes.
    static func parse(manufacturerData data: Data) -> AlertMessage? {
        let b = [UInt8](data)
        guard b.count >= 2 + size else { return nil }
        let company = UInt16(b[0]) | (UInt16(b[1]) << 8)
        guard company == companyId else { return nil }

        let p = Array(b[2..<(2 + size)])
        guard p[0] == 0x4C, p[1] == 2 else { return nil }

        let tripId = (UInt32(p[2]) << 24) | (UInt32(p[3]) << 16) | (UInt32(p[4]) << 8) | UInt32(p[5])
        let seq = (UInt16(p[6]) << 8) | UInt16(p[7])
        let maxHops = Int(p[8])
        let type = Int(p[9])
        let hopsLeft = Int(p[10])
        let senderId = Int(p[11])
        guard (1...7).contains(maxHops), (0...maxHops).contains(hopsLeft), (0...1).contains(type) else { return nil }

        let mac = HMAC<SHA256>.authenticationCode(for: Data(p[0..<10]), using: key)
        let expected = Array(Data(mac).prefix(8))
        let genuine = expected == Array(p[12..<20])

        return AlertMessage(tripId: tripId, seq: seq, maxHops: maxHops, type: type,
                            hopsLeft: hopsLeft, senderId: senderId, genuine: genuine)
    }
}

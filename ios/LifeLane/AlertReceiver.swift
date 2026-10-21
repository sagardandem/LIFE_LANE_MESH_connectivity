import CoreBluetooth
import CoreLocation
import Foundation
import UIKit
import UserNotifications

/// Listens for LIFELANE alerts over Bluetooth. RECEIVE ONLY:
/// Apple does not let iPhone apps broadcast custom data, so iPhones cannot relay.
/// Uses the iPhone's own GPS to decide if this driver is in the ambulance's way.
final class AlertReceiver: NSObject, ObservableObject, CBCentralManagerDelegate,
                           CLLocationManagerDelegate, UNUserNotificationCenterDelegate {

    enum Banner {
        case none
        case red(String)
        case amber(String)
        case passed
    }

    @Published var status = "Starting…"
    @Published var gpsStatus = "GPS: waiting"
    @Published var banner: Banner = .none
    @Published var log: [String] = []

    private var central: CBCentralManager!
    private let location = CLLocationManager()
    private var lastFix: CLLocation?
    private var lastHeading: Double?
    private var lastHeadingAt = Date.distantPast

    private var latestSeq: [UInt32: UInt16] = [:]
    private var alertShown = false
    private var notInWayStreak = 0
    private var dirX = 0.0, dirY = 0.0   // smoothed direction as a vector
    private var lastHeardAt: Date?
    private var passedAt: Date?
    private var lastNotifyAt = Date.distantPast
    private var lastNotifyTitle = ""
    private var lastFakeLogAt = Date.distantPast
    private var lastDecision = ""
    private var timer: Timer?
    private let timeFmt: DateFormatter = {
        let f = DateFormatter()
        f.dateFormat = "HH:mm:ss.SSS"
        return f
    }()

    override init() {
        super.init()
        let nc = UNUserNotificationCenter.current()
        nc.delegate = self
        nc.requestAuthorization(options: [.alert, .sound]) { _, _ in }

        location.delegate = self
        location.desiredAccuracy = kCLLocationAccuracyBest
        location.requestWhenInUseAuthorization()
        location.startUpdatingLocation()

        central = CBCentralManager(delegate: self, queue: .main)
        timer = Timer.scheduledTimer(withTimeInterval: 1, repeats: true) { [weak self] _ in
            self?.checkExpiry()
        }
    }

    // MARK: Location (works without internet)

    func locationManager(_ manager: CLLocationManager, didUpdateLocations locations: [CLLocation]) {
        guard let loc = locations.last else { return }
        lastFix = loc
        if loc.course >= 0 && loc.speed > 0.7 {
            // Smooth the direction so one jumpy reading doesn't flip the decision
            let r = loc.course * .pi / 180
            if lastHeading == nil || Date().timeIntervalSince(lastHeadingAt) > 10 {
                dirX = cos(r); dirY = sin(r)
            } else {
                dirX = 0.7 * dirX + 0.3 * cos(r)
                dirY = 0.7 * dirY + 0.3 * sin(r)
            }
            var deg = atan2(dirY, dirX) * 180 / .pi
            if deg < 0 { deg += 360 }
            lastHeading = deg
            lastHeadingAt = Date()
        }
        gpsStatus = "GPS: ON (±\(Int(loc.horizontalAccuracy)) m)"
    }

    func locationManager(_ manager: CLLocationManager, didFailWithError error: Error) {
        gpsStatus = "GPS: error"
    }

    private func myFix() -> CLLocation? {
        guard let f = lastFix, Date().timeIntervalSince(f.timestamp) < 30 else { return nil }
        return f
    }

    private func myHeading() -> Double? {
        guard let h = lastHeading, Date().timeIntervalSince(lastHeadingAt) < 120 else { return nil }   // remember 2 min
        return h
    }

    // MARK: Bluetooth

    func centralManagerDidUpdateState(_ c: CBCentralManager) {
        switch c.state {
        case .poweredOn:
            // Must scan for our service ID - Apple only allows background scanning for specific services.
            c.scanForPeripherals(withServices: [AlertMessage.serviceUUID],
                                 options: [CBCentralManagerScanOptionAllowDuplicatesKey: true])
            status = "Listening for ambulance alerts"
            addLog("Scanning started")
        case .poweredOff:
            status = "Bluetooth is OFF - turn it on"
        case .unauthorized:
            status = "Bluetooth permission denied - allow it in Settings"
        case .unsupported:
            status = "This device does not support Bluetooth LE"
        default:
            status = "Bluetooth not ready"
        }
    }

    func centralManager(_ c: CBCentralManager, didDiscover peripheral: CBPeripheral,
                        advertisementData ad: [String: Any], rssi RSSI: NSNumber) {
        guard let data = ad[CBAdvertisementDataManufacturerDataKey] as? Data,
              let msg = AlertMessage.parse(manufacturerData: data) else {
            // In the background iOS may give only the service ID, without the message.
            if UIApplication.shared.applicationState != .active {
                lastHeardAt = Date()
                show(.amber("Ambulance signal nearby\n(unverified - app in background)"),
                     title: "Ambulance nearby - be alert", body: "Signal detected nearby")
            }
            return
        }

        if !msg.genuine {
            if Date().timeIntervalSince(lastFakeLogAt) > 5 {
                lastFakeLogAt = Date()
                addLog("REJECTED fake alert (bad signature) trip \(msg.tripId)")
            }
            return
        }

        recordNeighbor(msg, rssi: RSSI.intValue)
        if let last = latestSeq[msg.tripId], !isNewer(msg.seq, than: last) { return }
        latestSeq[msg.tripId] = msg.seq
        lastHeardAt = Date()
        let how = msg.relayedTimes == 0 ? "DIRECT" : "RELAYED \(msg.relayedTimes)x"

        if msg.type == AlertMessage.typeEnd {
            addLog("GOT trip \(msg.tripId) ENDED (\(how))")
            clearAsPassed("Ambulance passed - alert cleared")
            return
        }

        let fix = myFix()
        var (level, reason) = Geo.decide(ambLat: msg.lat, ambLon: msg.lon, ambHeading: msg.heading,
                                         myLat: fix?.coordinate.latitude, myLon: fix?.coordinate.longitude,
                                         myHeading: myHeading())
        // Not sure? Follow my "travel buddy" - a nearby phone that is sure, heard steadily for 10 s.
        if level == .amber, let buddy = findBuddy(trip: msg.tripId) {
            let (id, n) = buddy
            if n.level == AlertMessage.lvlRed {
                level = .red; reason = "travel buddy #\(id) is in the ambulance's way"
            } else {
                level = .none; reason = "travel buddy #\(id) is not in the ambulance's way"
            }
        }
        let key = "\(level)\(reason.prefix(12))"
        if key != lastDecision || msg.seq % 5 == 0 {
            addLog("#\(msg.seq) \(how) rssi \(RSSI.intValue) -> \(level): \(reason)")
            lastDecision = key
        }

        // Don't drop an alert on one jumpy reading: need 3 "not in the way" readings in a row.
        let wobbly = reason.hasPrefix("different direction") || reason.hasPrefix("beside")
        if level == .none && alertShown && wobbly {
            notInWayStreak += 1
            if notInWayStreak < 3 { return }
        }
        if level != .none || !wobbly { notInWayStreak = 0 }

        switch level {
        case .red:
            show(.red("\(reason)\nTrip \(msg.tripId) • \(how)"),
                 title: "🚑 Ambulance approaching - give way", body: reason)
        case .amber:
            let tip = fix == nil ? "\nTurn on location for accurate alerts" : ""
            show(.amber("\(reason)\(tip)\nTrip \(msg.tripId) • \(how)"),
                 title: "🚑 Ambulance nearby - be alert", body: reason)
        case .none:
            if alertShown {
                if reason.contains("passed") {
                    clearAsPassed("Ambulance passed (\(reason))")
                } else {
                    alertShown = false
                    banner = .none
                    addLog("Alert removed - \(reason)")
                    UNUserNotificationCenter.current().removeDeliveredNotifications(withIdentifiers: ["alert"])
                }
            }
        }
    }

    // MARK: Travel buddy

    private struct Neighbor { var firstHeard: Date; var lastHeard: Date; var rssiAvg: Double; var level: Int; var tripId: UInt32 }
    private var neighbors: [Int: Neighbor] = [:]

    private func recordNeighbor(_ msg: AlertMessage, rssi: Int) {
        guard msg.type == AlertMessage.typeAlert, msg.senderLevel != AlertMessage.lvlUnknown else { return }
        let now = Date()
        if var n = neighbors[msg.senderId], now.timeIntervalSince(n.lastHeard) <= 3, n.tripId == msg.tripId {
            n.lastHeard = now
            n.rssiAvg = 0.8 * n.rssiAvg + 0.2 * Double(rssi)
            n.level = msg.senderLevel
            neighbors[msg.senderId] = n
        } else {
            neighbors[msg.senderId] = Neighbor(firstHeard: now, lastHeard: now, rssiAvg: Double(rssi),
                                               level: msg.senderLevel, tripId: msg.tripId)
        }
    }

    private func findBuddy(trip: UInt32) -> (Int, Neighbor)? {
        let now = Date()
        neighbors = neighbors.filter { now.timeIntervalSince($0.value.lastHeard) <= 30 }
        return neighbors
            .filter { (_, n) in
                n.tripId == trip && now.timeIntervalSince(n.lastHeard) <= 3 &&
                    n.lastHeard.timeIntervalSince(n.firstHeard) >= 10 &&
                    (n.level == AlertMessage.lvlRed || n.level == AlertMessage.lvlClear)
            }
            .max { $0.value.rssiAvg < $1.value.rssiAvg }
            .map { ($0.key, $0.value) }
    }

    // MARK: Alert handling

    private func show(_ b: Banner, title: String, body: String) {
        alertShown = true
        passedAt = nil
        banner = b
        notify(title: title, body: body)
    }

    private func clearAsPassed(_ why: String) {
        guard alertShown else { return }
        alertShown = false
        addLog(why)
        passedAt = Date()
        banner = .passed
        UNUserNotificationCenter.current().removeDeliveredNotifications(withIdentifiers: ["alert"])
    }

    private func checkExpiry() {
        if alertShown, let t = lastHeardAt, Date().timeIntervalSince(t) > 15 {
            alertShown = false
            banner = .none
            addLog("No signal for 15 s - alert cleared")
            UNUserNotificationCenter.current().removeDeliveredNotifications(withIdentifiers: ["alert"])
        }
        if let p = passedAt, Date().timeIntervalSince(p) > 10 {
            passedAt = nil
            banner = .none
        }
    }

    private func notify(title: String, body: String) {
        if title == lastNotifyTitle && Date().timeIntervalSince(lastNotifyAt) < 10 { return }
        lastNotifyAt = Date()
        lastNotifyTitle = title
        let content = UNMutableNotificationContent()
        content.title = title
        content.body = body
        content.sound = .default
        let req = UNNotificationRequest(identifier: "alert", content: content, trigger: nil)
        UNUserNotificationCenter.current().add(req)
    }

    func userNotificationCenter(_ center: UNUserNotificationCenter, willPresent notification: UNNotification,
                                withCompletionHandler completionHandler: @escaping (UNNotificationPresentationOptions) -> Void) {
        completionHandler([.banner, .sound])
    }

    private func isNewer(_ a: UInt16, than b: UInt16) -> Bool {
        let diff = a &- b
        return diff != 0 && diff < 0x8000
    }

    private func addLog(_ s: String) {
        log.insert("\(timeFmt.string(from: Date()))  \(s)", at: 0)
        if log.count > 200 { log.removeLast() }
    }
}

import CoreBluetooth
import CoreLocation
import Foundation
import UIKit
import UserNotifications

/// LIFELANE on iPhone - same roles as the Android app:
///  - SOURCE (ambulance): sends the alert every second.            Works only while the app is OPEN on screen.
///  - RECEIVER + RELAY: shows alerts (also when locked) and passes them on.  Passing on works only while OPEN.
/// Apple only lets apps broadcast a service ID + name, and only in the foreground, so the iPhone sends a
/// short message (see Compact in AlertMessage.swift). Android reads it and relays it in the normal format.
final class AlertReceiver: NSObject, ObservableObject, CBCentralManagerDelegate, CBPeripheralManagerDelegate,
                           CLLocationManagerDelegate, UNUserNotificationCenterDelegate {

    enum Mode { case stopped, receiver, source }
    enum Banner {
        case none
        case red(String)
        case amber(String)
        case passed
    }

    // Screen state
    @Published var mode: Mode = .stopped
    @Published var status = "Stopped"
    @Published var gpsStatus = "GPS: waiting"
    @Published var banner: Banner = .none
    @Published var log: [String] = []

    // Settings (same as Android)
    @Published var srcHeading = -1        // -1 = from GPS while moving, else degrees
    @Published var srcPos = 0             // 0 real GPS, 1 = 200 m BEFORE this spot, 2 = 200 m AFTER
    @Published var myHeadingSetting = -1  // -1 = from GPS while moving, else degrees
    @Published var minRelays = 0          // chain test
    @Published var fake = false

    private var central: CBCentralManager!
    private var peripheral: CBPeripheralManager!
    private let location = CLLocationManager()
    private var lastFix: CLLocation?
    private var lastHeading: Double?
    private var lastHeadingAt = Date.distantPast
    private var dirX = 0.0, dirY = 0.0

    private var latestSeq: [UInt32: UInt16] = [:]
    private var alertShown = false
    private var notInWayStreak = 0
    private var lastHeardAt: Date?
    private var passedAt: Date?
    private var lastNotifyAt = Date.distantPast
    private var lastNotifyTitle = ""
    private var lastFakeLogAt = Date.distantPast
    private var lastDecision = ""
    private var timer: Timer?
    private var advStop: DispatchWorkItem?
    private var relayWork: DispatchWorkItem?
    private var warnedBackground = false

    // Source trip
    private var tripId: UInt32 = UInt32.random(in: 1000...9999)
    private var seq: UInt16 = 0
    private var lastSent: (Double, Double, Double) = (0, 0, 0)

    private let timeFmt: DateFormatter = {
        let f = DateFormatter()
        f.dateFormat = "HH:mm:ss.SSS"
        return f
    }()

    private var appActive: Bool { UIApplication.shared.applicationState == .active }

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
        peripheral = CBPeripheralManager(delegate: self, queue: .main)
        timer = Timer.scheduledTimer(withTimeInterval: 1, repeats: true) { [weak self] _ in
            self?.tick()
        }
        let center = NotificationCenter.default
        center.addObserver(forName: UIApplication.didBecomeActiveNotification, object: nil, queue: .main) { [weak self] _ in
            self?.warnedBackground = false
            self?.restartScan()
        }
        center.addObserver(forName: UIApplication.didEnterBackgroundNotification, object: nil, queue: .main) { [weak self] _ in
            self?.restartScan()
            if self?.mode == .source { self?.addLog("App in background: iPhone can't send now (Apple rule). Open the app.") }
        }
        mode = .receiver   // start listening straight away
        status = "Receiver + Relay"
    }

    // MARK: Start / stop

    func startReceiver() {
        stopSending()
        mode = .receiver
        latestSeq = [:]
        clearBanner()
        status = "Running: RECEIVER + RELAY" + (minRelays > 0 ? " (chain test \(minRelays)+)" : "")
        addLog("Listening" + (myHeadingSetting >= 0 ? " (TEST: my direction \(myHeadingSetting)°)" : ""))
        restartScan()
    }

    func startSource() {
        central.stopScan()
        stopSending()
        mode = .source
        tripId = UInt32.random(in: 1000...9999)
        seq = 0
        clearBanner()
        status = "Running: SOURCE (keep the app open)"
        let pos = srcPos == 1 ? ", TEST: 200 m before this spot" : srcPos == 2 ? ", TEST: 200 m after this spot" : ""
        addLog("Source started. Trip \(tripId)\(pos)" + (fake ? ", FAKE mode" : ""))
    }

    func stop() {
        if mode == .source {
            // Tell everyone the ambulance has passed, then stop
            seq &+= 1
            let (u, n) = Compact.encode(tripId: tripId, seq: seq, type: AlertMessage.typeEnd, hopsLeft: Compact.maxHops,
                                        lat: lastSent.0, lon: lastSent.1, heading: lastSent.2, fake: false)
            advertise(u, n, stopAfter: 4)
            addLog("SENT trip ended #\(seq)")
        } else {
            stopSending()
        }
        central.stopScan()
        mode = .stopped
        clearBanner()
        status = "Stopped"
    }

    private func clearBanner() {
        alertShown = false
        banner = .none
        UNUserNotificationCenter.current().removeDeliveredNotifications(withIdentifiers: ["alert"])
    }

    private func tick() {
        checkExpiry()
        if mode == .source { sourceTick() }
    }

    // MARK: Location (works without internet)

    func locationManager(_ manager: CLLocationManager, didUpdateLocations locations: [CLLocation]) {
        guard let loc = locations.last else { return }
        lastFix = loc
        if loc.course >= 0 && loc.speed > 0.7 {
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
        if myHeadingSetting >= 0 { return Double(myHeadingSetting) }
        guard let h = lastHeading, Date().timeIntervalSince(lastHeadingAt) < 120 else { return nil }
        return h
    }

    // MARK: Sending (app must be open)

    func peripheralManagerDidUpdateState(_ p: CBPeripheralManager) {
        if p.state != .poweredOn { addLog("Bluetooth sending not ready (state \(p.state.rawValue))") }
    }

    func peripheralManagerDidStartAdvertising(_ p: CBPeripheralManager, error: Error?) {
        if let e = error { addLog("ERROR: send failed - \(e.localizedDescription)") }
    }

    private func advertise(_ uuid: CBUUID, _ name: String, stopAfter: TimeInterval?) {
        guard peripheral.state == .poweredOn else { return }
        if !appActive {
            if !warnedBackground { addLog("Can't send in background (Apple rule) - open the app"); warnedBackground = true }
            return
        }
        advStop?.cancel()
        peripheral.stopAdvertising()
        peripheral.startAdvertising([CBAdvertisementDataServiceUUIDsKey: [uuid],
                                     CBAdvertisementDataLocalNameKey: name])
        if let t = stopAfter {
            let w = DispatchWorkItem { [weak self] in self?.peripheral.stopAdvertising() }
            advStop = w
            DispatchQueue.main.asyncAfter(deadline: .now() + t, execute: w)
        }
    }

    private func stopSending() {
        advStop?.cancel()
        relayWork?.cancel()
        peripheral?.stopAdvertising()
    }

    private func sourceTick() {
        guard let fix = myFix() else {
            if Int(Date().timeIntervalSince1970) % 5 == 0 { addLog("Waiting for GPS fix... (go outdoors / near a window)") }
            return
        }
        let heading = srcHeading >= 0 ? Double(srcHeading) : (lastHeading ?? 0)
        var lat = fix.coordinate.latitude, lon = fix.coordinate.longitude
        if srcPos == 1 { (lat, lon) = Geo.offset(lat, lon, bearing: heading + 180, dist: 200) }
        if srcPos == 2 { (lat, lon) = Geo.offset(lat, lon, bearing: heading, dist: 200) }
        lastSent = (lat, lon, heading)
        seq &+= 1
        let (u, n) = Compact.encode(tripId: tripId, seq: seq, type: AlertMessage.typeAlert, hopsLeft: Compact.maxHops,
                                    lat: lat, lon: lon, heading: heading, fake: fake)
        advertise(u, n, stopAfter: nil)
        addLog("SENT #\(seq) heading \(Int(heading))°" +
               (srcHeading < 0 && lastHeading == nil ? " (not moving yet - heading N)" : "") + (fake ? " FAKE" : ""))
    }

    /// Pass a message on (iPhone relay - only while the app is open).
    private func relay(_ msg: AlertMessage) {
        guard msg.hopsLeft > 0, appActive, mode == .receiver else { return }
        relayWork?.cancel()
        let w = DispatchWorkItem { [weak self] in
            guard let self = self else { return }
            let (u, n) = Compact.encode(tripId: msg.tripId & 0xFFFF, seq: msg.seq, type: msg.type,
                                        hopsLeft: msg.hopsLeft - 1, lat: msg.lat, lon: msg.lon,
                                        heading: msg.heading, fake: false)
            self.advertise(u, n, stopAfter: 4.5)
            if msg.seq % 5 == 0 { self.addLog("RELAYED #\(msg.seq) hopsLeft=\(msg.hopsLeft - 1)") }
        }
        relayWork = w
        DispatchQueue.main.asyncAfter(deadline: .now() + Double.random(in: 0.05...0.2), execute: w)
    }

    // MARK: Receiving

    func centralManagerDidUpdateState(_ c: CBCentralManager) {
        switch c.state {
        case .poweredOn: restartScan()
        case .poweredOff: status = "Bluetooth is OFF - turn it on"
        case .unauthorized: status = "Bluetooth permission denied - allow it in Settings"
        case .unsupported: status = "This device does not support Bluetooth LE"
        default: status = "Bluetooth not ready"
        }
    }

    /// App open: listen to everything (to hear iPhone short messages too).
    /// App in background: Apple only allows listening for our service ID.
    private func restartScan() {
        guard central.state == .poweredOn, mode == .receiver else { return }
        central.stopScan()
        central.scanForPeripherals(withServices: appActive ? nil : [AlertMessage.serviceUUID],
                                   options: [CBCentralManagerScanOptionAllowDuplicatesKey: true])
        if status.hasPrefix("Bluetooth") || status == "Receiver + Relay" { status = "Running: RECEIVER + RELAY" }
    }

    func centralManager(_ c: CBCentralManager, didDiscover peripheral: CBPeripheral,
                        advertisementData ad: [String: Any], rssi RSSI: NSNumber) {
        guard mode == .receiver else { return }
        let rssi = RSSI.intValue
        if let data = ad[CBAdvertisementDataManufacturerDataKey] as? Data,
           let msg = AlertMessage.parse(manufacturerData: data) {
            handle(msg, rssi: rssi)
            return
        }
        if let uuids = ad[CBAdvertisementDataServiceUUIDsKey] as? [CBUUID],
           let raw = Compact.find(uuids: uuids, name: ad[CBAdvertisementDataLocalNameKey] as? String) {
            let s = Compact.expandSeq(raw.seq8, last: latestSeq[raw.tripId])
            handle(raw.toAlert(seq: s), rssi: rssi)
            return
        }
        // In the background iOS may give only our service ID, without the message.
        if !appActive, let uuids = ad[CBAdvertisementDataServiceUUIDsKey] as? [CBUUID],
           uuids.contains(AlertMessage.serviceUUID) {
            lastHeardAt = Date()
            show(.amber("Ambulance signal nearby\n(unverified - app in background)"),
                 title: "🚑 Ambulance nearby - be alert", body: "Signal detected nearby")
        }
    }

    private func handle(_ msg: AlertMessage, rssi: Int) {
        if !msg.genuine {
            if Date().timeIntervalSince(lastFakeLogAt) > 5 {
                lastFakeLogAt = Date()
                addLog("REJECTED fake alert (bad signature) trip \(msg.tripId)")
            }
            return
        }

        recordNeighbor(msg, rssi: rssi)
        if let last = latestSeq[msg.tripId], !isNewer(msg.seq, than: last) { return }
        if msg.relayedTimes < minRelays { return }
        latestSeq[msg.tripId] = msg.seq
        lastHeardAt = Date()
        let how = msg.relayedTimes == 0 ? "DIRECT" : "RELAYED \(msg.relayedTimes)x"

        if msg.type == AlertMessage.typeEnd {
            addLog("GOT trip \(msg.tripId) ENDED (\(how))")
            clearAsPassed("Ambulance passed - alert cleared")
            relay(msg)
            return
        }

        let fix = myFix()
        var (level, reason) = Geo.decide(ambLat: msg.lat, ambLon: msg.lon, ambHeading: msg.heading,
                                         myLat: fix?.coordinate.latitude, myLon: fix?.coordinate.longitude,
                                         myHeading: myHeading())
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
            addLog("#\(msg.seq) \(how) rssi \(rssi) -> \(level): \(reason)")
            lastDecision = key
        }

        // Always pass it on (while the app is open), even if this driver isn't alerted
        relay(msg)

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

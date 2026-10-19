import CoreBluetooth
import Foundation
import UIKit
import UserNotifications

/// Listens for LifeLane alerts over Bluetooth. RECEIVE ONLY:
/// Apple does not let iPhone apps broadcast custom data, so iPhones cannot relay.
final class AlertReceiver: NSObject, ObservableObject, CBCentralManagerDelegate, UNUserNotificationCenterDelegate {

    enum Banner {
        case none
        case danger(String)
        case passed
    }

    @Published var status = "Starting…"
    @Published var banner: Banner = .none
    @Published var log: [String] = []

    private var central: CBCentralManager!
    private var latestSeq: [UInt32: UInt16] = [:]
    private var lastAlertAt: Date?
    private var passedAt: Date?
    private var lastNotifyAt = Date.distantPast
    private var lastFakeLogAt = Date.distantPast
    private var timer: Timer?
    private let timeFmt: DateFormatter = {
        let f = DateFormatter()
        f.dateFormat = "HH:mm:ss.SSS"
        return f
    }()

    private let expireAfter: TimeInterval = 15

    override init() {
        super.init()
        let nc = UNUserNotificationCenter.current()
        nc.delegate = self
        nc.requestAuthorization(options: [.alert, .sound]) { _, _ in }
        central = CBCentralManager(delegate: self, queue: .main)
        timer = Timer.scheduledTimer(withTimeInterval: 1, repeats: true) { [weak self] _ in
            self?.checkExpiry()
        }
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
        let rssi = RSSI.intValue
        guard let data = ad[CBAdvertisementDataManufacturerDataKey] as? Data,
              let msg = AlertMessage.parse(manufacturerData: data) else {
            // In the background iOS may give us only the service ID, without the message.
            // Then we still warn, but cannot check the signature.
            if UIApplication.shared.applicationState != .active {
                showAlert(text: "Ambulance signal detected nearby", how: "unverified (app in background)", rssi: rssi)
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

        if let last = latestSeq[msg.tripId], !isNewer(msg.seq, than: last) { return }
        latestSeq[msg.tripId] = msg.seq

        let how = msg.relayedTimes == 0 ? "DIRECT from ambulance" : "RELAYED \(msg.relayedTimes) time(s)"

        if msg.type == AlertMessage.typeEnd {
            addLog("GOT trip \(msg.tripId) ENDED, \(how)")
            lastAlertAt = nil
            passedAt = Date()
            banner = .passed
            UNUserNotificationCenter.current().removeDeliveredNotifications(withIdentifiers: ["alert"])
            return
        }

        addLog("GOT trip \(msg.tripId) #\(msg.seq) \(how), rssi \(rssi)")
        showAlert(text: "Trip \(msg.tripId) • msg #\(msg.seq)", how: how, rssi: rssi)
    }

    // MARK: Alert handling

    private func showAlert(text: String, how: String, rssi: Int) {
        lastAlertAt = Date()
        passedAt = nil
        banner = .danger("\(text)\n\(how)")
        notify(body: how)
    }

    private func checkExpiry() {
        if let t = lastAlertAt, Date().timeIntervalSince(t) > expireAfter {
            lastAlertAt = nil
            banner = .none
            addLog("No signal for 15 s - alert cleared")
            UNUserNotificationCenter.current().removeDeliveredNotifications(withIdentifiers: ["alert"])
        }
        if let p = passedAt, Date().timeIntervalSince(p) > 10 {
            passedAt = nil
            banner = .none
        }
    }

    private func notify(body: String) {
        guard Date().timeIntervalSince(lastNotifyAt) > 10 else { return }   // don't buzz every second
        lastNotifyAt = Date()
        let content = UNMutableNotificationContent()
        content.title = "🚑 Ambulance nearby - give way"
        content.body = body
        content.sound = .default
        let req = UNNotificationRequest(identifier: "alert", content: content, trigger: nil)
        UNUserNotificationCenter.current().add(req)
    }

    /// Show notifications even while the app is open.
    func userNotificationCenter(_ center: UNUserNotificationCenter, willPresent notification: UNNotification,
                                withCompletionHandler completionHandler: @escaping (UNNotificationPresentationOptions) -> Void) {
        completionHandler([.banner, .sound])
    }

    /// Message numbers wrap at 65535.
    private func isNewer(_ a: UInt16, than b: UInt16) -> Bool {
        let diff = a &- b
        return diff != 0 && diff < 0x8000
    }

    private func addLog(_ s: String) {
        log.insert("\(timeFmt.string(from: Date()))  \(s)", at: 0)
        if log.count > 200 { log.removeLast() }
    }
}

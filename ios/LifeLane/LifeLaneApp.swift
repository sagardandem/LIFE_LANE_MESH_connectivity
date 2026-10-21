import SwiftUI

@main
struct LifeLaneApp: App {
    @StateObject private var receiver = AlertReceiver()

    var body: some Scene {
        WindowGroup {
            ContentView()
                .environmentObject(receiver)
        }
    }
}

struct Choice: Identifiable {
    let id: Int
    let label: String
}

struct ContentView: View {
    @EnvironmentObject var r: AlertReceiver

    private let headings = [
        Choice(id: -1, label: "Auto (from GPS while moving)"),
        Choice(id: 0, label: "TEST: North"),
        Choice(id: 90, label: "TEST: East"),
        Choice(id: 180, label: "TEST: South"),
        Choice(id: 270, label: "TEST: West"),
    ]
    private let positions = [
        Choice(id: 0, label: "Real GPS position"),
        Choice(id: 1, label: "TEST: 200 m BEFORE this spot (phones here are AHEAD)"),
        Choice(id: 2, label: "TEST: 200 m AFTER this spot (phones here are BEHIND)"),
    ]
    private let chain = [
        Choice(id: 0, label: "0 times - accept all (normal use)"),
        Choice(id: 1, label: "1 time  (phone C in A→B→C)"),
        Choice(id: 2, label: "2 times (phone D in A→B→C→D)"),
        Choice(id: 3, label: "3 times"),
    ]

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            Text("LIFELANE").font(.title).bold()
            Text(r.status).font(.headline)
            Text(r.gpsStatus).font(.caption).foregroundColor(.secondary)

            ScrollView {
                VStack(alignment: .leading, spacing: 8) {
                    Text("SOURCE (ambulance) - keep the app open on screen").font(.caption).bold()
                    Button("Start as SOURCE") { r.startSource() }
                        .buttonStyle(.borderedProminent)
                    picker("Ambulance direction", headings, $r.srcHeading)
                    picker("Ambulance position", positions, $r.srcPos)
                    Toggle("Security test: send FAKE alert", isOn: $r.fake)

                    Text("RECEIVER + RELAY").font(.caption).bold().padding(.top, 6)
                    Button("Start as RECEIVER + RELAY") { r.startReceiver() }
                        .buttonStyle(.borderedProminent)
                    picker("My direction", headings, $r.myHeadingSetting)
                    picker("Chain test: accept only alerts relayed at least", chain, $r.minRelays)

                    Button("Stop") { r.stop() }
                        .buttonStyle(.bordered)
                        .padding(.top, 6)
                }
                .frame(maxWidth: .infinity, alignment: .leading)
            }
            .frame(maxHeight: 320)

            bannerView

            Text("Log").font(.headline)
            ScrollView {
                LazyVStack(alignment: .leading, spacing: 4) {
                    ForEach(Array(r.log.enumerated()), id: \.offset) { _, line in
                        Text(line).font(.system(size: 11, design: .monospaced))
                    }
                }
                .padding(8)
                .frame(maxWidth: .infinity, alignment: .leading)
            }
            .background(Color(.secondarySystemBackground))
            .cornerRadius(8)

            Text("iPhone sends (Source / Relay) only while the app is open on screen - Apple rule. Receiving also works when locked.")
                .font(.footnote)
                .foregroundColor(.secondary)
        }
        .padding()
    }

    private func picker(_ title: String, _ items: [Choice], _ value: Binding<Int>) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(title).font(.caption).foregroundColor(.secondary)
            Picker(title, selection: value) {
                ForEach(items) { Text($0.label).tag($0.id) }
            }
            .pickerStyle(.menu)
        }
    }

    @ViewBuilder private var bannerView: some View {
        switch r.banner {
        case .none:
            EmptyView()
        case .red(let text):
            box("AMBULANCE APPROACHING\nGive way\n\n\(text)", bg: .red, fg: .white)
        case .amber(let text):
            box("AMBULANCE NEARBY\nBe alert\n\n\(text)", bg: .orange, fg: .black)
        case .passed:
            box("AMBULANCE PASSED\nAlert cleared", bg: .green, fg: .white)
        }
    }

    private func box(_ text: String, bg: Color, fg: Color) -> some View {
        Text(text)
            .font(.title3)
            .multilineTextAlignment(.center)
            .foregroundColor(fg)
            .frame(maxWidth: .infinity)
            .padding()
            .background(bg)
            .cornerRadius(8)
    }
}

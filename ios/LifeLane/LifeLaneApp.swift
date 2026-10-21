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

struct ContentView: View {
    @EnvironmentObject var r: AlertReceiver

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("LIFELANE").font(.largeTitle).bold()
            Text(r.status).font(.headline).foregroundColor(.secondary)
            Text(r.gpsStatus).font(.subheadline).foregroundColor(.secondary)

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

            Text("Receive only: iPhones show alerts but cannot pass them on (Apple Bluetooth rules). Keep the app open in the background - don't swipe it away.")
                .font(.footnote)
                .foregroundColor(.secondary)
        }
        .padding()
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

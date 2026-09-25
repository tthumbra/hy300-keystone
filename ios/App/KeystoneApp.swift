import SwiftUI

/// One app for the projector: a phone remote and the keystone calibration, sharing one pairing.
@main
struct HY300App: App {
    @StateObject private var remote = RemoteConnection()
    @Environment(\.scenePhase) private var phase

    var body: some Scene {
        WindowGroup {
            RootView(remote: remote)
                .onAppear { UIApplication.shared.isIdleTimerDisabled = true }
                .onChange(of: phase) { _, p in
                    if p == .active, remote.link != nil, remote.state != .connected { remote.connect() }
                    if p == .background { remote.disconnect() }
                }
        }
    }
}

struct RootView: View {
    @ObservedObject var remote: RemoteConnection
    @State private var tab = Tab.remote

    enum Tab { case remote, keystone }

    var body: some View {
        TabView(selection: $tab) {
            RemoteView(remote: remote)
                .tabItem { Label("Remote", systemImage: "hand.point.up.left") }
                .tag(Tab.remote)
            CalibrateView(remote: remote, active: tab == .keystone)
                .tabItem { Label("Keystone", systemImage: "viewfinder") }
                .tag(Tab.keystone)
        }
        .preferredColorScheme(.dark)
    }
}

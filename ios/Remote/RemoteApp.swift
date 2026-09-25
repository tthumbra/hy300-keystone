import SwiftUI

@main
struct RemoteApp: App {
    @StateObject private var remote = RemoteConnection()
    @Environment(\.scenePhase) private var phase

    var body: some Scene {
        WindowGroup {
            RemoteView(remote: remote)
                .onChange(of: phase) { _, p in
                    if p == .active, remote.link != nil, remote.state != .connected { remote.connect() }
                    if p == .background { remote.disconnect() }
                }
        }
    }
}

import SwiftUI

@main
struct KeystoneApp: App {
    var body: some Scene {
        WindowGroup {
            ContentView()
                .onAppear { UIApplication.shared.isIdleTimerDisabled = true }
        }
    }
}

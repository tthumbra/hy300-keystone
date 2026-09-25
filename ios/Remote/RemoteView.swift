import SwiftUI

struct RemoteView: View {
    @ObservedObject var remote: RemoteConnection
    @State private var scanning = false
    @State private var keyboard = false
    @State private var dpad = false
    @State private var scanError = ""

    // Android key codes.
    enum K {
        static let back = 4, home = 3, recents = 187, up = 19, down = 20, left = 21, right = 22, ok = 23
        static let volUp = 24, volDown = 25, mute = 164, playPause = 85
    }

    var body: some View {
        Group {
            if remote.link == nil || scanning {
                scanScreen
            } else {
                controlScreen
            }
        }
        .preferredColorScheme(.dark)
    }

    // MARK: Pairing

    private var scanScreen: some View {
        VStack(spacing: 16) {
            Text("Pair with the projector").font(.title2.bold())
            Text("Scan the QR code on the projector — it shows in a corner when the projector starts, and in the Keystone Calibrate app.")
                .multilineTextAlignment(.center).foregroundStyle(.secondary)
            if QRScanner.isAvailable {
                QRScanner { text in
                    if remote.pair(with: text) { scanning = false; scanError = "" }
                    else if text.hasPrefix("https://") { scanError = "That QR code is from an older projector app — update it." }
                }
                .clipShape(RoundedRectangle(cornerRadius: 16))
            } else {
                Text("This iPhone can't scan codes here.").foregroundStyle(.red)
            }
            if !scanError.isEmpty { Text(scanError).foregroundStyle(.yellow).font(.footnote) }
            if remote.link != nil { Button("Cancel") { scanning = false } }
        }
        .padding()
    }

    // MARK: Remote

    private var controlScreen: some View {
        VStack(spacing: 12) {
            statusBar
            if dpad { dpadPad } else {
                Trackpad(remote: remote)
                    .clipShape(RoundedRectangle(cornerRadius: 18))
                    .overlay(alignment: .bottom) {
                        Text("tap = click · two fingers = scroll · hold = drag")
                            .font(.caption2).foregroundStyle(.secondary).padding(8).allowsHitTesting(false)
                    }
            }
            HStack(spacing: 10) {
                RemoteButton("arrow.uturn.backward", "Back") { remote.key(K.back) }
                RemoteButton("house", "Home") { remote.key(K.home) }
                RemoteButton("square.stack", "Recents") { remote.key(K.recents) }
                RemoteButton(dpad ? "hand.point.up.left" : "dpad", dpad ? "Trackpad" : "D-pad") { dpad.toggle() }
                RemoteButton("keyboard", "Type") { keyboard.toggle() }
            }
            HStack(spacing: 10) {
                RemoteButton("speaker.minus", "Vol -") { remote.key(K.volDown) }
                RemoteButton("speaker.slash", "Mute") { remote.key(K.mute) }
                RemoteButton("speaker.plus", "Vol +") { remote.key(K.volUp) }
                RemoteButton("playpause", "Play") { remote.key(K.playPause) }
            }
            KeyCatcher(active: $keyboard, remote: remote).frame(width: 1, height: 1).opacity(0.01)
        }
        .padding()
        .onAppear { if remote.state != .connected { remote.connect() } }
    }

    private var statusBar: some View {
        HStack {
            Circle().fill(statusColor).frame(width: 9, height: 9)
            Text(statusText).font(.footnote).foregroundStyle(.secondary).lineLimit(1)
            Spacer()
            Menu {
                Button("Reconnect") { remote.connect() }
                Button("Pair again (scan QR)") { scanning = true }
                Button("Forget this projector", role: .destructive) { remote.forget() }
            } label: { Image(systemName: "ellipsis.circle").font(.title3) }
        }
    }

    private var statusText: String {
        switch remote.state {
        case .unpaired: return "Not paired"
        case .connecting: return "Connecting to \(remote.link?.host ?? "")…"
        case .connected: return "Connected to HY300 at \(remote.link?.host ?? "")"
        case .searching: return "Looking for the projector on the Wi-Fi…"
        case .offline(let why): return why
        }
    }

    private var statusColor: Color {
        switch remote.state {
        case .connected: return .green
        case .connecting, .searching: return .yellow
        default: return .red
        }
    }

    private var dpadPad: some View {
        VStack(spacing: 14) {
            Spacer()
            RemoteButton("chevron.up", nil) { remote.key(K.up) }.frame(width: 90)
            HStack(spacing: 14) {
                RemoteButton("chevron.left", nil) { remote.key(K.left) }.frame(width: 90)
                RemoteButton("circle.fill", "OK") { remote.key(K.ok) }.frame(width: 90)
                RemoteButton("chevron.right", nil) { remote.key(K.right) }.frame(width: 90)
            }
            RemoteButton("chevron.down", nil) { remote.key(K.down) }.frame(width: 90)
            Spacer()
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(Color(uiColor: .secondarySystemBackground), in: RoundedRectangle(cornerRadius: 18))
    }
}

struct RemoteButton: View {
    let icon: String
    let label: String?
    let action: () -> Void

    init(_ icon: String, _ label: String?, action: @escaping () -> Void) {
        self.icon = icon; self.label = label; self.action = action
    }

    var body: some View {
        Button {
            UIImpactFeedbackGenerator(style: .light).impactOccurred()
            action()
        } label: {
            VStack(spacing: 4) {
                Image(systemName: icon).font(.title3)
                if let label { Text(label).font(.caption2) }
            }
            .frame(maxWidth: .infinity, minHeight: 54)
            .background(Color(uiColor: .tertiarySystemBackground), in: RoundedRectangle(cornerRadius: 14))
        }
        .buttonStyle(.plain)
    }
}

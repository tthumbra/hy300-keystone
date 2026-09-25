import ARKit
import KeystoneCore
import SceneKit
import SwiftUI

/// The Keystone tab: AR camera view and the calibration controls.
struct CalibrateView: View {
    @ObservedObject var remote: RemoteConnection
    let active: Bool
    @StateObject private var cal = Calibrator()

    var body: some View {
        ZStack {
            GeometryReader { geo in
                ZStack {
                    ARCameraView(session: cal.session)
                    OutlineShape(points: cal.overlay)
                        .stroke(cal.stage == .calibrating ? Color.blue : Color.green, lineWidth: 3)
                }
                .onAppear { updateViewport(geo.size) }
                .onChange(of: geo.size) { _, s in updateViewport(s) }
            }
            .ignoresSafeArea()

            VStack(spacing: 12) {
                statusCard
                Spacer()
                controls
            }
            .padding()
        }
        .onChange(of: active, initial: true) { _, isActive in
            if isActive {
                cal.onPaired = { remote.pair(with: $0) }
                cal.startAR()
                if let link = remote.link { cal.use(link) }
            } else {
                cal.leave()
            }
        }
    }

    private func updateViewport(_ size: CGSize) {
        cal.viewSize = size
        let scene = UIApplication.shared.connectedScenes.first as? UIWindowScene
        cal.interfaceOrientation = scene?.interfaceOrientation ?? .portrait
    }

    private var statusCard: some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack {
                Text("Keystone").font(.headline)
                Spacer()
                Label(cal.hasLiDAR ? "LiDAR" : "No LiDAR", systemImage: cal.hasLiDAR ? "dot.radiowaves.left.and.right" : "camera")
                    .font(.caption).foregroundStyle(cal.hasLiDAR ? Color.green : Color.secondary)
            }
            Text(cal.status).font(.subheadline)
            if !cal.hint.isEmpty { Text(cal.hint).font(.footnote).foregroundStyle(.yellow) }
            if let q = cal.liveQuality, cal.stage != .scanQR {
                Text(String(format: "On the wall now: corners within %.1f° of square, tilted %.1f°", q.maxAngleError, q.tilt))
                    .font(.caption.monospacedDigit()).foregroundStyle(.secondary)
            }
            if !cal.resultText.isEmpty { Text(cal.resultText).font(.footnote).foregroundStyle(.green) }
            if cal.stage == .calibrating { ProgressView(value: min(1, cal.progress)) }
        }
        .padding(12)
        .background(.ultraThinMaterial, in: RoundedRectangle(cornerRadius: 14))
    }

    @ViewBuilder private var controls: some View {
        VStack(spacing: 8) {
            switch cal.stage {
            case .scanQR, .connecting:
                EmptyView()
            case .ready:
                Button("Calibrate") { cal.startCalibration() }.buttonStyle(Big()).disabled(cal.busy)
                secondaryRow
            case .calibrating:
                Button("Stop") { cal.stopLoop("Stopped.") }.buttonStyle(Big(color: .gray))
            case .done:
                Button("Finish") { cal.finish() }.buttonStyle(Big())
                Button("Calibrate again") { cal.startCalibration() }.buttonStyle(Big(color: .gray)).disabled(cal.busy)
                secondaryRow
            }
        }
    }

    private var secondaryRow: some View {
        HStack {
            Button("Undo all") { cal.undoAll() }
            Spacer()
            Button("No correction") { cal.noCorrection() }
            Spacer()
            Button("Other projector") { cal.rescan() }
        }
        .font(.footnote)
        .padding(10)
        .background(.ultraThinMaterial, in: RoundedRectangle(cornerRadius: 12))
        .disabled(cal.busy)
    }
}

struct Big: ButtonStyle {
    var color: Color = .blue
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(.headline)
            .frame(maxWidth: .infinity)
            .padding(14)
            .background(color.opacity(configuration.isPressed ? 0.7 : 1), in: RoundedRectangle(cornerRadius: 14))
            .foregroundStyle(.white)
    }
}

/// Closed polygon through the given points (the detected picture outline).
struct OutlineShape: Shape {
    var points: [CGPoint]
    func path(in rect: CGRect) -> Path {
        var p = Path()
        guard points.count >= 3 else { return p }
        p.addLines(points)
        p.closeSubpath()
        return p
    }
}

/// Shows the AR session's camera feed.
struct ARCameraView: UIViewRepresentable {
    let session: ARSession
    func makeUIView(context: Context) -> ARSCNView {
        let v = ARSCNView(frame: .zero)
        v.session = session
        v.automaticallyUpdatesLighting = false
        v.rendersCameraGrain = false
        return v
    }
    func updateUIView(_ uiView: ARSCNView, context: Context) {}
}

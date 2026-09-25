import ARKit
import Foundation
import QuartzCore
import KeystoneCore
import simd
import Vision

/// Runs the AR session, finds the projector's QR code and then the green calibration picture, measures
/// the picture's corners on the wall in 3D (LiDAR raycasts), and drives the calibration loop:
/// measure -> test nudge -> measure -> correct -> re-check, like the web page but in wall coordinates.
@MainActor
final class Calibrator: NSObject, ObservableObject, ARSessionDelegate {
    enum Stage { case scanQR, connecting, ready, calibrating, done }
    enum LoopPhase { case idle, measure, apply, settle }

    @Published private(set) var stage = Stage.scanQR
    @Published private(set) var status = "Point the camera at the QR code on the projector."
    @Published private(set) var hint = ""
    @Published private(set) var overlay: [CGPoint] = []          // picture outline in view coordinates
    @Published private(set) var liveQuality: QuadQuality?
    @Published private(set) var progress = 0.0
    @Published private(set) var busy = false
    @Published private(set) var hasLiDAR = false
    @Published private(set) var values: KeystoneValues?
    @Published private(set) var resultText = ""

    /// Set by the camera view so the overlay can be mapped onto the screen.
    var viewSize: CGSize = .zero
    var interfaceOrientation: UIInterfaceOrientation = .portrait

    let session = ARSession()
    /// Called when a QR code is scanned here, so the remote uses the same projector.
    var onPaired: ((ProjectorLink) -> Void)?
    private var client: ProjectorClient?
    private var installmode = 0
    private var layout = Layout(installmode: 0)!
    private var layoutKnown = false
    private var knownLayouts: [Int: (flipX: Bool, flipY: Bool)] = [:]
    private var startValues: KeystoneValues?
    private var aspect = 16.0 / 9.0
    private let work = DispatchQueue(label: "keystone.detect", qos: .userInitiated)
    private var processing = false
    private var lastProcess: TimeInterval = 0
    private var pingTask: Task<Void, Never>?

    // Calibration loop.
    private enum LoopStage { case baseline, probe, correct }
    private var loopPhase = LoopPhase.idle
    private var loopStage = LoopStage.baseline
    private var samples: [[Corner: SIMD3<Float>]] = []
    private var runFrame: WallFrame?                  // wall coordinates for the whole run
    private var before: Quad?
    private var beforeProbe: KeystoneValues?
    private var probe: Probe?
    private var round = 0
    private var verifyLayout = false
    private var previousError: Double?
    private var settleUntil: TimeInterval = 0
    private var lastCameraPosition = SIMD3<Float>(0, 0, 0)

    static let samplesNeeded = 15
    static let steadyMetres: Float = 0.015
    static let settleSeconds = 0.4        // after an apply; frames without the picture are skipped anyway
    static let maxRounds = 5

    // MARK: - Session

    func startAR() {
        let config = ARWorldTrackingConfiguration()
        config.planeDetection = [.vertical]
        if ARWorldTrackingConfiguration.supportsSceneReconstruction(.mesh) {
            config.sceneReconstruction = .mesh
            hasLiDAR = true
        }
        if ARWorldTrackingConfiguration.supportsFrameSemantics(.sceneDepth) {
            config.frameSemantics.insert(.sceneDepth)
            hasLiDAR = true
        }
        session.delegate = self
        session.run(config)
    }

    nonisolated func session(_ session: ARSession, didUpdate frame: ARFrame) {
        MainActor.assumeIsolated { self.handle(frame) }
    }

    nonisolated func session(_ session: ARSession, didFailWithError error: Error) {
        MainActor.assumeIsolated { self.status = "Camera stopped: \(error.localizedDescription)" }
    }

    private func handle(_ frame: ARFrame) {
        let now = frame.timestamp
        guard !processing, now - lastProcess > (stage == .scanQR ? 0.4 : 0.1) else { return }
        switch stage {
        case .scanQR:
            processing = true
            lastProcess = now
            scanQR(frame.capturedImage)
        case .connecting:
            return
        case .ready, .calibrating, .done:
            if loopPhase == .apply { return }
            if loopPhase == .settle {
                if now < settleUntil { return }
                loopPhase = .measure
                samples = []
            }
            if case .limited = frame.camera.trackingState { hint = "Move the phone slowly so it can see the room…" }
            processing = true
            lastProcess = now
            let camera = CameraSnapshot(frame.camera)
            let pixelBuffer = frame.capturedImage
            work.async {
                let img = FrameConverter.rgba(pixelBuffer, longSide: 960)
                let det = QuadDetector.detect(rgba: img.data, width: img.width, height: img.height, roll: camera.roll)
                Task { @MainActor in self.finishDetection(det, scale: img.scale, camera: camera) }
            }
        }
    }

    // MARK: - QR code

    private func scanQR(_ pixelBuffer: CVPixelBuffer) {
        work.async {
            let request = VNDetectBarcodesRequest()
            request.symbologies = [.qr]
            try? VNImageRequestHandler(cvPixelBuffer: pixelBuffer, options: [:]).perform([request])
            let texts = (request.results ?? []).compactMap(\.payloadStringValue)
            Task { @MainActor in
                self.processing = false
                if let link = texts.lazy.compactMap(ProjectorLink.init).first {
                    self.onPaired?(link)
                    self.connect(link)
                }
                else if let t = texts.first, t.hasPrefix("https://"), !t.contains("fp=") {
                    self.status = "This QR code is from an older projector app — update the projector app."
                }
            }
        }
    }

    /// Uses the already-paired projector (from the Remote tab) instead of scanning its QR code.
    func use(_ link: ProjectorLink) {
        guard stage == .scanQR else { return }
        connect(link)
    }

    /// Leaving the Keystone tab: stop the camera and put the projector back to normal.
    func leave() {
        stopLoop(nil)
        pingTask?.cancel()
        if let client { Task { await client.done() } }
        client = nil
        stage = .scanQR
        overlay = []
        liveQuality = nil
        session.pause()
        status = "Point the camera at the QR code on the projector."
    }

    func rescan() {
        stopLoop(nil)
        pingTask?.cancel()
        client = nil
        stage = .scanQR
        overlay = []
        status = "Point the camera at the QR code on the projector."
    }

    private func connect(_ link: ProjectorLink) {
        stage = .connecting
        status = "Connecting to the projector at \(link.host)…"
        let client = ProjectorClient(link: link)
        self.client = client
        Task {
            do {
                let s = try await client.startSession()
                guard s.helperUp, let state = s.state else {
                    self.status = "The projector is still starting its keystone helper… retrying"
                    try? await Task.sleep(nanoseconds: 3_000_000_000)
                    if self.client === client { self.connect(link) }
                    return
                }
                self.aspect = s.aspect
                self.knownLayouts = s.layouts
                self.installmode = -1
                self.setState(state)
                if self.startValues == nil { self.startValues = state.values }
                self.stage = .ready
                self.status = "Connected. Aim at the green picture so all of it is in view, then tap Calibrate."
                self.pingTask?.cancel()
                self.pingTask = Task { [weak self] in
                    while !Task.isCancelled {
                        try? await Task.sleep(nanoseconds: 30_000_000_000)
                        await self?.client?.ping()
                    }
                }
            } catch {
                self.stage = .scanQR
                self.status = "Couldn't connect: \(error.localizedDescription) Point at the QR code again."
            }
        }
    }

    private func setState(_ s: ProjectorState) {
        values = s.values
        if s.installmode != installmode {
            installmode = s.installmode
            let known = knownLayouts[s.installmode]
            layout = Layout(installmode: s.installmode, flipX: known?.flipX ?? false, flipY: known?.flipY ?? false) ?? layout
            layoutKnown = known != nil
        }
    }

    // MARK: - Measuring

    private func finishDetection(_ det: Result<QuadDetector.Detection, QuadDetector.Failure>, scale: Double, camera: CameraSnapshot) {
        processing = false
        guard stage != .scanQR, stage != .connecting, loopPhase != .apply, loopPhase != .settle else { return }
        let d: QuadDetector.Detection
        switch det {
        case .failure(let f):
            overlay = []
            liveQuality = nil
            hint = f.message                    // skipped; a measurement keeps its frames so far
            return
        case .success(let ok):
            d = ok
        }
        // Detector corners -> captured-image pixels, in outline order.
        let image = Corner.allCases.map { c -> CGPoint in
            let p = d.corners[c]!
            return CGPoint(x: p.x / scale, y: p.y / scale)
        }
        overlay = viewPoints(image, camera: camera)

        guard let world = measureOnWall(image, camera: camera), let frame = WallFrame(world, cameraPosition: camera.position) else {
            hint = hasLiDAR ? "Measuring the wall…" : "Measuring the wall — move the phone a little from side to side."
            return
        }
        lastCameraPosition = camera.position
        let labelled = frame.label(world)
        liveQuality = quadQuality(frame.quad(labelled), aspect: aspect)
        hint = ""

        guard loopPhase == .measure else { return }
        if let mean = Self.average(samples),
           Corner.allCases.contains(where: { simd_distance(mean[$0]!, labelled[$0]!) > Self.steadyMetres }) {
            samples = []
        }
        samples.append(labelled)
        progress = Double(samples.count) / Double(Self.samplesNeeded)
        if samples.count >= Self.samplesNeeded, let mean = Self.average(samples) { finishMeasurement(mean) }
    }

    /// Raycasts the corners and edge midpoints onto the wall, fits a plane through the hits, and returns
    /// each corner's ray intersected with that plane (tl, tr, br, bl in outline order).
    private func measureOnWall(_ image: [CGPoint], camera: CameraSnapshot) -> [SIMD3<Float>]? {
        var probePoints: [CGPoint] = []
        let centre = CGPoint(x: image.map(\.x).reduce(0, +) / 4, y: image.map(\.y).reduce(0, +) / 4)
        for i in 0..<4 {
            let a = image[i], b = image[(i + 1) % 4]
            for p in [a, CGPoint(x: (a.x + b.x) / 2, y: (a.y + b.y) / 2)] {
                // 8% towards the centre, so the ray lands on the lit wall rather than exactly on the edge.
                probePoints.append(CGPoint(x: p.x + (centre.x - p.x) * 0.08, y: p.y + (centre.y - p.y) * 0.08))
            }
        }
        let hits = probePoints.compactMap { raycast(camera.ray(through: $0)) }
        guard hits.count >= 6 else { return nil }
        let plane = Plane(fitting: hits)
        let corners = image.compactMap { plane.intersect(camera.ray(through: $0)) }
        return corners.count == 4 ? corners : nil
    }

    private func raycast(_ ray: Ray) -> SIMD3<Float>? {
        for target in [ARRaycastQuery.Target.estimatedPlane, .existingPlaneInfinite] {
            let q = ARRaycastQuery(origin: ray.origin, direction: ray.direction, allowing: target, alignment: .vertical)
            if let hit = session.raycast(q).first {
                let c = hit.worldTransform.columns.3
                return SIMD3(c.x, c.y, c.z)
            }
        }
        return nil
    }

    private func viewPoints(_ image: [CGPoint], camera: CameraSnapshot) -> [CGPoint] {
        guard viewSize.width > 0, let frame = session.currentFrame else { return [] }
        let t = frame.displayTransform(for: interfaceOrientation, viewportSize: viewSize)
        return image.map {
            let n = CGPoint(x: $0.x / camera.resolution.width, y: $0.y / camera.resolution.height).applying(t)
            return CGPoint(x: n.x * viewSize.width, y: n.y * viewSize.height)
        }
    }

    static func average(_ s: [[Corner: SIMD3<Float>]]) -> [Corner: SIMD3<Float>]? {
        guard !s.isEmpty else { return nil }
        var out: [Corner: SIMD3<Float>] = [:]
        for c in Corner.allCases { out[c] = s.map { $0[c]! }.reduce(.zero, +) / Float(s.count) }
        return out
    }

    // MARK: - Calibration loop

    func startCalibration() {
        guard stage == .ready || stage == .done, !busy else { return }
        stage = .calibrating
        // Test nudge first, unless this projection mode's layout is remembered (then verify it on round 1).
        loopStage = layoutKnown ? .correct : .baseline
        verifyLayout = layoutKnown
        previousError = nil
        loopPhase = .measure
        samples = []
        round = 0
        runFrame = nil
        resultText = ""
        status = "Hold the picture in view — measuring…"
    }

    func stopLoop(_ text: String?) {
        let undoProbe = stage == .calibrating && loopStage == .probe ? beforeProbe : nil
        loopPhase = .idle
        progress = 0
        if stage == .calibrating { stage = .ready }
        if let text { status = text }
        beforeProbe = nil
        if let undoProbe { apply(undoProbe, note: "Undoing the test nudge…", then: nil) }
    }

    private func finishMeasurement(_ world: [Corner: SIMD3<Float>]) {
        samples = []
        progress = 0
        if runFrame == nil { runFrame = WallFrame(Corner.allCases.map { world[$0]! }, cameraPosition: lastCameraPosition) }
        guard let frame = runFrame else { return }
        handleMeasurement(frame.quad(world))
    }

    /// One averaged measurement of the picture in wall coordinates: next step of the loop.
    private func handleMeasurement(_ observed: Quad) {
        guard let current = values else { return }
        switch loopStage {
        case .baseline:
            // Test nudge: which way does this projector move a corner? (any installmode, any flip)
            guard let p = makeProbe(current, installmode: installmode) else { stopLoop("Unknown projection mode."); return }
            before = observed
            beforeProbe = current
            probe = p
            status = "Test nudge — checking which way this projector moves the picture…"
            apply(p.values, note: nil) { self.loopStage = .probe }
            return
        case .probe:
            guard let before, let probe else { return }
            switch inferFlips(before: before, after: observed, probe: probe) {
            case .failure(let e):
                stopLoop(e.message)
                return
            case .success(let f):
                layout = Layout(installmode: installmode, flipX: f.flipX, flipY: f.flipY) ?? layout
                layoutKnown = true
                knownLayouts[installmode] = (f.flipX, f.flipY)
                let mode = installmode
                Task { await client?.rememberLayout(installmode: mode, flipX: f.flipX, flipY: f.flipY) }
                loopStage = .correct
                beforeProbe = nil
            }
        case .correct:
            break
        }
        correct(observed, values: current)
    }

    private func correct(_ observed: Quad, values current: KeystoneValues) {
        switch solve(observed: observed, values: current, aspect: aspect, layout: layout) {
        case .failure(let e):
            stopLoop(e.message)
        case .success(let s):
            let q = s.quality
            let describe = String(format: "corners within %.1f° of square, level within %.1f°", q.maxAngleError, abs(q.tilt))
            // A remembered layout that made things worse is wrong (e.g. the projector was physically
            // flipped): forget it and do the test nudge from here.
            if verifyLayout, let prev = previousError, q.maxAngleError > prev + 0.3 {
                let mode = installmode
                knownLayouts[mode] = nil
                layoutKnown = false
                layout = Layout(installmode: mode) ?? layout
                verifyLayout = false
                loopStage = .baseline
                Task { await client?.forgetLayout(installmode: mode) }
                status = "That made it worse — re-checking which way the projector moves…"
                handleMeasurement(observed)
                return
            }
            if previousError != nil { verifyLayout = false }
            previousError = q.maxAngleError
            if (q.maxAngleError < 0.5 && abs(q.tilt) < 0.4 && q.aspectError < 0.012) || s.values.maxChange(from: current) <= 1 {
                loopPhase = .idle
                stage = .done
                resultText = "Rectangular on the wall: \(describe)."
                status = "Done. Tap Finish to save and close the pattern."
                return
            }
            if round >= Self.maxRounds {
                stopLoop("Stopped after \(Self.maxRounds) rounds (\(describe)).")
                return
            }
            round += 1
            status = "Round \(round): \(describe). Adjusting…"
            apply(s.values, note: nil, then: {})
        }
    }

    /// Applies values; while the projector flashes ControlCenter, measuring pauses, then settles.
    private func apply(_ v: KeystoneValues, note: String?, then: (() -> Void)?) {
        guard let client else { return }
        busy = true
        loopPhase = then == nil ? .idle : .apply
        if let note { status = note }
        Task {
            defer { self.busy = false }
            do {
                let s = try await client.apply(v)
                let modeChanged = s.installmode != self.installmode
                self.setState(s)
                if modeChanged { self.stopLoop("The projector's projection mode changed — calibrate again."); return }
                guard let then, self.stage == .calibrating else { return }
                then()
                self.loopPhase = .settle
                self.settleUntil = CACurrentMediaTime() + Self.settleSeconds
            } catch {
                self.stopLoop("Couldn't apply: \(error.localizedDescription)")
            }
        }
    }

    func undoAll() {
        guard let startValues, !busy else { return }
        stopLoop(nil)
        apply(startValues, note: "Restoring the settings from before…", then: nil)
    }

    func noCorrection() {
        guard !busy else { return }
        stopLoop(nil)
        apply(layout.noCorrection(), note: "Removing all correction…", then: nil)
    }

    func finish() {
        guard let client else { return }
        Task { await client.done() }
        pingTask?.cancel()
        stage = .done
        status = "Saved. The projector keeps this correction, even after a restart."
    }
}

// MARK: - Geometry helpers

struct Ray {
    var origin: SIMD3<Float>
    var direction: SIMD3<Float>
}

/// The camera pose and intrinsics of one frame (the frame itself isn't kept).
struct CameraSnapshot {
    let transform: simd_float4x4
    let intrinsics: simd_float3x3
    let resolution: CGSize

    init(_ c: ARCamera) {
        transform = c.transform
        intrinsics = c.intrinsics
        resolution = c.imageResolution
    }

    var position: SIMD3<Float> { SIMD3(transform.columns.3.x, transform.columns.3.y, transform.columns.3.z) }

    /// Rotation of world "up" in the captured image (0 = up is towards the top of the image).
    var roll: Double {
        let up = transform.inverse * SIMD4<Float>(0, 1, 0, 0)   // camera coords: x right, y up (in the image)
        return Double(atan2(up.x, up.y))
    }

    /// World-space ray through a captured-image pixel.
    func ray(through p: CGPoint) -> Ray {
        let fx = intrinsics.columns.0.x, fy = intrinsics.columns.1.y
        let cx = intrinsics.columns.2.x, cy = intrinsics.columns.2.y
        let d = transform * SIMD4<Float>((Float(p.x) - cx) / fx, -(Float(p.y) - cy) / fy, -1, 0)
        return Ray(origin: position, direction: simd_normalize(SIMD3(d.x, d.y, d.z)))
    }
}

struct Plane {
    var point: SIMD3<Float>
    var normal: SIMD3<Float>

    /// Plane through points listed in order around a loop (Newell's method for the normal).
    init(fitting pts: [SIMD3<Float>]) {
        var n = SIMD3<Float>.zero
        for i in 0..<pts.count {
            let a = pts[i], b = pts[(i + 1) % pts.count]
            n += SIMD3((a.y - b.y) * (a.z + b.z), (a.z - b.z) * (a.x + b.x), (a.x - b.x) * (a.y + b.y))
        }
        normal = simd_normalize(n)
        point = pts.reduce(.zero, +) / Float(pts.count)
    }

    func intersect(_ r: Ray) -> SIMD3<Float>? {
        let den = simd_dot(normal, r.direction)
        if abs(den) < 1e-4 { return nil }
        let t = simd_dot(normal, point - r.origin) / den
        return t > 0 ? r.origin + t * r.direction : nil
    }
}

/// 2D coordinates on the wall in metres: x to the right, y down (gravity), for the solver.
struct WallFrame {
    let origin: SIMD3<Float>, right: SIMD3<Float>, up: SIMD3<Float>

    /// From the four corners (any order around the outline), seen from the camera position.
    init?(_ corners: [SIMD3<Float>], cameraPosition cam: SIMD3<Float>) {
        guard corners.count == 4 else { return nil }
        var n = simd_cross(corners[2] - corners[0], corners[3] - corners[1])
        guard simd_length(n) > 1e-6 else { return nil }
        n = simd_normalize(n)
        origin = corners.reduce(.zero, +) / 4
        if simd_dot(n, cam - origin) < 0 { n = -n }                       // normal faces the viewer
        let worldUp = SIMD3<Float>(0, 1, 0)                                // ARKit: y is up (gravity)
        let u = worldUp - simd_dot(worldUp, n) * n
        guard simd_length(u) > 0.3 else { return nil }                    // not a wall (too flat)
        up = simd_normalize(u)
        right = simd_cross(up, n)                                         // viewer's right on the wall
    }

    func point(_ p: SIMD3<Float>) -> P2 {
        let d = p - origin
        return P2(Double(simd_dot(d, right)), -Double(simd_dot(d, up)))
    }

    /// Names the corners by where they are on the wall (top-left = smallest x + y, and so on).
    func label(_ pts: [SIMD3<Float>]) -> [Corner: SIMD3<Float>] {
        let q = pts.map(point)
        func pick(_ score: (P2) -> Double) -> Int { q.indices.max { score(q[$0]) < score(q[$1]) }! }
        return [.tl: pts[pick { -($0.x + $0.y) }], .br: pts[pick { $0.x + $0.y }],
                .tr: pts[pick { $0.x - $0.y }], .bl: pts[pick { $0.y - $0.x }]]
    }

    func quad(_ w: [Corner: SIMD3<Float>]) -> Quad { w.mapValues(point) }
}

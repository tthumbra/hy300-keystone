import Foundation

/// Where each panel corner may go: at most 25% inward on each axis ([uMin, uMax, vMin, vMax]).
let boxes: [Corner: [Double]] = [
    .tl: [0, keystoneLimit, 0, keystoneLimit],
    .tr: [1 - keystoneLimit, 1, 0, keystoneLimit],
    .br: [1 - keystoneLimit, 1, 1 - keystoneLimit, 1],
    .bl: [0, keystoneLimit, 1 - keystoneLimit, 1],
]

public struct Solution: Sendable {
    public var values: KeystoneValues
    /// The target rectangle in observed coordinates.
    public var target: Quad
    /// How rectangular the observed picture already was.
    public var quality: QuadQuality
}

public enum SolveError: Error, Equatable, Sendable {
    case cornersOutOfOrder, degenerate, doesNotFit

    public var message: String {
        switch self {
        case .cornersOutOfOrder: return "The four corners are not in the expected order."
        case .degenerate: return "Could not relate the picture to the projector (corners too close together?)."
        case .doesNotFit: return "No rectangle fits within the keystone range (the projector is at too steep an angle)."
        }
    }
}

/// Model: panel = unit square as seen on the wall; the values place the picture's corners on the panel
/// (via the layout); observed positions relate to panel positions by one homography H. From the current
/// values and the observed corners we get H, pick the largest axis-aligned rectangle of the wanted aspect
/// whose corners stay reachable, and map it back through H^-1 into new values. Same as keystone.js solve().
///
/// observed: the picture's corners in a 2D space where the goal is an axis-aligned rectangle, y down
/// (wall metres from LiDAR, or pixels of a level virtual camera).
public func solve(observed: Quad, values: KeystoneValues, aspect: Double = 16.0 / 9.0, layout L: Layout) -> Result<Solution, SolveError> {
    guard isSaneQuad(observed) else { return .failure(.cornersOutOfOrder) }
    let panel = L.toPanel(values)
    guard let H = Homography.from(panel.ordered, to: observed.ordered), let Hi = H.inverted() else { return .failure(.degenerate) }

    func preimages(_ cx: Double, _ cy: Double, _ w: Double) -> Quad {
        let h = w / aspect
        return [.tl: Hi.apply(P2(cx - w / 2, cy - h / 2)), .tr: Hi.apply(P2(cx + w / 2, cy - h / 2)),
                .br: Hi.apply(P2(cx + w / 2, cy + h / 2)), .bl: Hi.apply(P2(cx - w / 2, cy + h / 2))]
    }
    // Outer bound: corners stay on the panel (grows monotonically with w).
    func withinPanel(_ p: Quad) -> Bool {
        let tl = p[.tl]!, tr = p[.tr]!, br = p[.br]!, bl = p[.bl]!
        return tl.x >= 0 && tl.y >= 0 && tr.x <= 1 && tr.y >= 0 && br.x <= 1 && br.y <= 1 && bl.x >= 0 && bl.y <= 1
    }
    // Inner bound: at most 25% inward.
    func withinLimits(_ p: Quad) -> Bool {
        for (k, bx) in boxes {
            let q = p[k]!
            if q.x < bx[0] - 1e-9 || q.x > bx[1] + 1e-9 || q.y < bx[2] - 1e-9 || q.y > bx[3] + 1e-9 { return false }
        }
        return true
    }
    func largestAt(_ cx: Double, _ cy: Double, _ wMax: Double) -> Double {
        var lo = 0.0, hi = wMax
        if withinPanel(preimages(cx, cy, hi)) {
            lo = hi
        } else {
            for _ in 0..<40 {
                let mid = (lo + hi) / 2
                if withinPanel(preimages(cx, cy, mid)) { lo = mid } else { hi = mid }
            }
        }
        return lo > 0 && withinLimits(preimages(cx, cy, lo)) ? lo : 0
    }

    let full = [P2(0, 0), P2(1, 0), P2(1, 1), P2(0, 1)].map { H.apply($0) }
    let xs = full.map(\.x), ys = full.map(\.y)
    let x0 = xs.min()!, x1 = xs.max()!, y0 = ys.min()!, y1 = ys.max()!
    let wMax = x1 - x0

    var best: (cx: Double, cy: Double, w: Double)? = nil
    var cx0 = (x0 + x1) / 2, cy0 = (y0 + y1) / 2, spanX = (x1 - x0) / 2, spanY = (y1 - y0) / 2
    for _ in 0..<4 {
        let n = 24
        for i in 0...n {
            for j in 0...n {
                let cx = cx0 + spanX * (2 * Double(i) / Double(n) - 1), cy = cy0 + spanY * (2 * Double(j) / Double(n) - 1)
                let w = largestAt(cx, cy, wMax)
                if w > 0 && (best == nil || w > best!.w) { best = (cx, cy, w) }
            }
        }
        guard let b = best else { break }
        cx0 = b.cx; cy0 = b.cy; spanX /= 6; spanY /= 6
    }
    guard let b = best else { return .failure(.doesNotFit) }

    let h = b.w / aspect
    return .success(Solution(
        values: L.fromPanel(preimages(b.cx, b.cy, b.w)),
        target: [.tl: P2(b.cx - b.w / 2, b.cy - h / 2), .tr: P2(b.cx + b.w / 2, b.cy - h / 2),
                 .br: P2(b.cx + b.w / 2, b.cy + h / 2), .bl: P2(b.cx - b.w / 2, b.cy + h / 2)],
        quality: quadQuality(observed, aspect: aspect)))
}

// MARK: - Test nudge

/// Probe nudge: 6% of the frame on each axis.
let probeSize = 0.06

public struct Probe: Sendable {
    public var values: KeystoneValues
    public var signX: Int, signY: Int
}

/// Values for a test nudge: the corner stored under "lt" moved 6% on both axes (inward, or outward if
/// it's already far in). Which wall corner then moves, and which way, reveals the flips.
public func makeProbe(_ values: KeystoneValues, installmode: Int) -> Probe? {
    guard let L = Layout(installmode: installmode) else { return nil }
    var v = values
    var signs: [String: Int] = [:]
    for k in ["ltx", "lty"] {
        let inward = L.inward(values, k)
        signs[k] = inward + probeSize <= keystoneLimit ? 1 : -1
        v[k] = L.encode(k, inward + Double(signs[k]!) * probeSize)
    }
    return Probe(values: v, signX: signs["ltx"]!, signY: signs["lty"]!)
}

public enum ProbeError: Error, Equatable, Sendable {
    case noVisibleMove, phoneMoved, unexpectedDirection

    public var message: String {
        switch self {
        case .noVisibleMove: return "The test nudge didn't visibly move the picture. Keep the picture in view and try again."
        case .phoneMoved: return "Something moved during the test nudge. Try again."
        case .unexpectedDirection: return "The projector moved the picture in an unexpected direction during the test nudge — this keystone setup isn't supported yet."
        }
    }
}

/// From the picture's corners measured before and after a test nudge (y down), works out which wall
/// corner the "lt" values control.
public func inferFlips(before: Quad, after: Quad, probe: Probe) -> Result<(flipX: Bool, flipY: Bool), ProbeError> {
    struct Move { let k: Corner; let dx: Double; let dy: Double; var d: Double { hypot(dx, dy) } }
    let moved = Corner.allCases.map { Move(k: $0, dx: after[$0]!.x - before[$0]!.x, dy: after[$0]!.y - before[$0]!.y) }
        .sorted { $0.d > $1.d }
    let width = hypot(before[.tr]!.x - before[.tl]!.x, before[.tr]!.y - before[.tl]!.y)
    let m = moved[0]
    if m.d < width * 0.01 { return .failure(.noVisibleMove) }
    if moved[1].d > m.d * 0.35 { return .failure(.phoneMoved) }
    let inX = m.k.isLeft ? 1.0 : -1.0, inY = m.k.isTop ? 1.0 : -1.0
    if m.dx * inX * Double(probe.signX) <= 0 || m.dy * inY * Double(probe.signY) <= 0 { return .failure(.unexpectedDirection) }
    return .success((flipX: !m.k.isLeft, flipY: !m.k.isTop))
}

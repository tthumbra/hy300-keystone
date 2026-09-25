import Foundation

/// A 2D point. Image pixels or wall metres, always with y pointing down.
public struct P2: Equatable, Codable, Sendable {
    public var x: Double
    public var y: Double
    public init(_ x: Double, _ y: Double) { self.x = x; self.y = y }
}

/// The picture's corners, as seen on the wall.
public enum Corner: String, CaseIterable, Codable, Sendable {
    case tl, tr, br, bl
    var isLeft: Bool { self == .tl || self == .bl }
    var isTop: Bool { self == .tl || self == .tr }
}

public typealias Quad = [Corner: P2]

extension Dictionary where Key == Corner, Value == P2 {
    /// Corners in tl, tr, br, bl order (clockwise with y down).
    var ordered: [P2] { Corner.allCases.map { self[$0]! } }
}

/// 3x3 projective transform, row-major.
public struct Homography: Sendable {
    public var m: [Double]

    public init(_ m: [Double]) { self.m = m }

    /// The homography mapping four source points to four destination points.
    public static func from(_ src: [P2], to dst: [P2]) -> Homography? {
        var A: [[Double]] = [], b: [Double] = []
        for i in 0..<4 {
            let x = src[i].x, y = src[i].y, X = dst[i].x, Y = dst[i].y
            A.append([x, y, 1, 0, 0, 0, -x * X, -y * X]); b.append(X)
            A.append([0, 0, 0, x, y, 1, -x * Y, -y * Y]); b.append(Y)
        }
        guard let h = solveLinear(A, b) else { return nil }
        return Homography(h + [1])
    }

    public func apply(_ p: P2) -> P2 {
        let w = m[6] * p.x + m[7] * p.y + m[8]
        return P2((m[0] * p.x + m[1] * p.y + m[2]) / w, (m[3] * p.x + m[4] * p.y + m[5]) / w)
    }

    public func inverted() -> Homography? {
        let a = m[0], b = m[1], c = m[2], d = m[3], e = m[4], f = m[5], g = m[6], h = m[7], i = m[8]
        let A = e * i - f * h, B = -(d * i - f * g), C = d * h - e * g
        let det = a * A + b * B + c * C
        if abs(det) < 1e-15 { return nil }
        return Homography([A / det, -(b * i - c * h) / det, (b * f - c * e) / det,
                           B / det, (a * i - c * g) / det, -(a * f - c * d) / det,
                           C / det, -(a * h - b * g) / det, (a * e - b * d) / det])
    }
}

/// Gauss-Jordan elimination with partial pivoting.
func solveLinear(_ A0: [[Double]], _ b: [Double]) -> [Double]? {
    let n = b.count
    var A = A0
    for i in 0..<n { A[i].append(b[i]) }
    for c in 0..<n {
        var piv = c
        for r in (c + 1)..<max(c + 1, n) where abs(A[r][c]) > abs(A[piv][c]) { piv = r }
        if abs(A[piv][c]) < 1e-12 { return nil }
        A.swapAt(c, piv)
        for r in 0..<n where r != c {
            let f = A[r][c] / A[c][c]
            for k in c...n { A[r][k] -= f * A[c][k] }
        }
    }
    return (0..<n).map { A[$0][n] / A[$0][$0] }
}

// MARK: - Quad checks

public struct QuadQuality: Sendable {
    /// Largest difference of a corner angle from 90°.
    public var maxAngleError: Double
    /// Angle of the top edge from horizontal, degrees.
    public var tilt: Double
    public var aspect: Double
    public var aspectError: Double
}

func angleAt(_ prev: P2, _ p: P2, _ next: P2) -> Double {
    let ax = prev.x - p.x, ay = prev.y - p.y, bx = next.x - p.x, by = next.y - p.y
    let cosv = (ax * bx + ay * by) / (hypot(ax, ay) * hypot(bx, by))
    return acos(max(-1, min(1, cosv))) * 180 / .pi
}

/// How far a quad is from an axis-aligned rectangle of the wanted aspect.
public func quadQuality(_ o: Quad, aspect: Double) -> QuadQuality {
    let q = o.ordered
    var maxAngle = 0.0
    for i in 0..<4 { maxAngle = max(maxAngle, abs(angleAt(q[(i + 3) % 4], q[i], q[(i + 1) % 4]) - 90)) }
    let tl = o[.tl]!, tr = o[.tr]!, br = o[.br]!, bl = o[.bl]!
    let top = atan2(tr.y - tl.y, tr.x - tl.x) * 180 / .pi
    let width = (hypot(tr.x - tl.x, tr.y - tl.y) + hypot(br.x - bl.x, br.y - bl.y)) / 2
    let height = (hypot(bl.x - tl.x, bl.y - tl.y) + hypot(br.x - tr.x, br.y - tr.y)) / 2
    return QuadQuality(maxAngleError: maxAngle, tilt: top, aspect: width / height, aspectError: abs(width / height / aspect - 1))
}

/// Corners must be in order tl, tr, br, bl clockwise (y down) and form a convex quad.
public func isSaneQuad(_ o: Quad) -> Bool {
    let q = o.ordered
    for i in 0..<4 {
        let a = q[i], b = q[(i + 1) % 4], c = q[(i + 2) % 4]
        if (b.x - a.x) * (c.y - b.y) - (b.y - a.y) * (c.x - b.x) <= 0 { return false }
    }
    return true
}

func polygonArea(_ q: [P2]) -> Double {
    var a = 0.0
    for i in 0..<q.count {
        let p = q[i], r = q[(i + 1) % q.count]
        a += p.x * r.y - r.x * p.y
    }
    return abs(a) / 2
}

/// JavaScript's Math.round (half rounds up), so results match keystone.js exactly.
@inline(__always) func jsRound(_ x: Double) -> Double { (x + 0.5).rounded(.down) }

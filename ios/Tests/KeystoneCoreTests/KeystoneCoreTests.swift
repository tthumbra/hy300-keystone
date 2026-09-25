import Foundation
import XCTest
@testable import KeystoneCore

/// Checks the Swift port against answers from the web page's keystone.js (Tests/make-fixtures.js).
final class KeystoneCoreTests: XCTestCase {
    func fixture(_ name: String) throws -> Any {
        let url = try XCTUnwrap(Bundle.module.url(forResource: "Fixtures/" + name, withExtension: nil))
        return try JSONSerialization.jsonObject(with: Data(contentsOf: url))
    }

    func point(_ a: Any?) -> P2 { let v = a as! [Double]; return P2(v[0], v[1]) }
    func quad(_ a: Any?) -> Quad {
        let d = a as! [String: Any]
        return Dictionary(uniqueKeysWithValues: Corner.allCases.map { ($0, point(d[$0.rawValue])) })
    }
    func values(_ a: Any?) -> KeystoneValues { KeystoneValues((a as! [String: Any]).mapValues { ($0 as! NSNumber).intValue }) }
    func assertClose(_ a: Quad, _ b: Quad, tol: Double, _ msg: String, file: StaticString = #filePath, line: UInt = #line) {
        for k in Corner.allCases {
            XCTAssertEqual(a[k]!.x, b[k]!.x, accuracy: tol, "\(msg) \(k).x", file: file, line: line)
            XCTAssertEqual(a[k]!.y, b[k]!.y, accuracy: tol, "\(msg) \(k).y", file: file, line: line)
        }
    }

    func testLayoutsMatchJavaScript() throws {
        let cases = try fixture("layout.json") as! [[String: Any]]
        XCTAssertEqual(cases.count, 16)
        for c in cases {
            let L = try XCTUnwrap(Layout(installmode: c["installmode"] as! Int, flipX: c["flipX"] as! Bool, flipY: c["flipY"] as! Bool))
            let name = "mode \(L.installmode) flips \(L.flipX)/\(L.flipY)"
            let v = L.fromPanel(quad(c["panel"]))
            XCTAssertEqual(v, values(c["values"]), name)
            assertClose(L.toPanel(v), quad(c["toPanel"]), tol: 1e-12, name)
            XCTAssertEqual(L.noCorrection(), values(c["noCorrection"]), name)
            for n in c["nudges"] as! [[String: Any]] {
                let wall = Corner(rawValue: n["wall"] as! String)!, dir = Layout.Direction(rawValue: n["dir"] as! String)!
                XCTAssertEqual(L.nudge(v, wall, dir, step: 5), values(n["result"]), "\(name) nudge \(wall) \(dir)")
            }
        }
    }

    func testSolverMatchesJavaScript() throws {
        let cases = try fixture("solve.json") as! [[String: Any]]
        XCTAssertEqual(cases.count, 49)
        for (i, c) in cases.enumerated() {
            let L = try XCTUnwrap(Layout(installmode: c["installmode"] as! Int, flipX: c["flipX"] as! Bool, flipY: c["flipY"] as! Bool))
            let res = solve(observed: quad(c["observed"]), values: values(c["values"]), layout: L)
            let expected = c["result"] as! [String: Any]
            switch res {
            case .success(let s):
                XCTAssertNil(expected["error"], "case \(i): JS failed, Swift didn't")
                XCTAssertEqual(s.values, values(expected["values"]), "case \(i) values")
                assertClose(s.target, quad(expected["target"]), tol: 1e-9, "case \(i) target")
            case .failure(let e):
                XCTAssertEqual(e, .doesNotFit, "case \(i)")
                XCTAssertNotNil(expected["error"], "case \(i): Swift failed (\(e)), JS didn't")
            }
        }
    }

    func testTestNudgeMatchesJavaScript() throws {
        let cases = try fixture("probe.json") as! [[String: Any]]
        XCTAssertEqual(cases.count, 32)
        for (i, c) in cases.enumerated() {
            let probe = try XCTUnwrap(makeProbe(values(c["start"]), installmode: c["installmode"] as! Int))
            let expected = c["probe"] as! [String: Any]
            XCTAssertEqual(probe.values, values(expected["values"]), "case \(i)")
            XCTAssertEqual(probe.signX, expected["signX"] as! Int, "case \(i)")
            XCTAssertEqual(probe.signY, expected["signY"] as! Int, "case \(i)")
            let flips = c["flips"] as? [String: Any]
            switch inferFlips(before: quad(c["before"]), after: quad(c["after"]), probe: probe) {
            case .success(let f):
                XCTAssertEqual(f.flipX, flips?["flipX"] as? Bool, "case \(i)")
                XCTAssertEqual(f.flipY, flips?["flipY"] as? Bool, "case \(i)")
            case .failure(let e):
                XCTFail("case \(i): \(e)")
            }
        }
    }

    func testDetectorMatchesJavaScript() throws {
        let cases = try fixture("detect.json") as! [[String: Any]]
        XCTAssertEqual(cases.count, 4)
        for c in cases {
            let name = c["name"] as! String, W = c["width"] as! Int, H = c["height"] as! Int
            let url = try XCTUnwrap(Bundle.module.url(forResource: "Fixtures/" + name, withExtension: "rgba"))
            let rgba = [UInt8](try Data(contentsOf: url))
            let expected = c["result"] as! [String: Any]
            switch QuadDetector.detect(rgba: rgba, width: W, height: H) {
            case .success(let d):
                XCTAssertNil(expected["error"], "\(name): JS failed, Swift found \(d.corners)")
                XCTAssertEqual(d.cue.rawValue, expected["cue"] as? String, name)
                XCTAssertEqual(d.threshold, expected["threshold"] as? Int, name)
                assertClose(d.corners, quad(expected["corners"]), tol: 1e-6, name)
            case .failure(let f):
                XCTAssertEqual(f.rawValue, expected["error"] as? String, "\(name): Swift failed with \(f)")
            }
        }
    }

    /// End to end in wall coordinates (what the LiDAR app measures): observe, test nudge, correct, and the
    /// picture ends up an axis-aligned 16:9 rectangle on the wall, for every mode and flip.
    func testCalibratesOnTheWallForEveryLayout() throws {
        let raw = [P2(-1.0, -1.25), P2(1.05, -1.1), P2(1.0, -0.05), P2(-0.95, 0.05)]
        let G = try XCTUnwrap(Homography.from([P2(0, 0), P2(1, 0), P2(1, 1), P2(0, 1)], to: raw))
        for mode in 0...3 {
            for fx in [false, true] {
                for fy in [false, true] {
                    let truth = try XCTUnwrap(Layout(installmode: mode, flipX: fx, flipY: fy))
                    func wall(_ v: KeystoneValues) -> Quad { truth.toPanel(v).mapValues { G.apply($0) } }
                    var v = truth.noCorrection()
                    let probe = try XCTUnwrap(makeProbe(v, installmode: mode))
                    let flips = try inferFlips(before: wall(v), after: wall(probe.values), probe: probe).get()
                    XCTAssertEqual(flips.flipX, fx); XCTAssertEqual(flips.flipY, fy)
                    v = probe.values
                    let L = try XCTUnwrap(Layout(installmode: mode, flipX: flips.flipX, flipY: flips.flipY))
                    v = try solve(observed: wall(v), values: v, layout: L).get().values
                    let q = quadQuality(wall(v), aspect: 16.0 / 9.0)
                    XCTAssertLessThan(q.maxAngleError, 0.2, "mode \(mode) flips \(fx)/\(fy)")
                    XCTAssertLessThan(abs(q.tilt), 0.2, "mode \(mode) flips \(fx)/\(fy)")
                    XCTAssertLessThan(q.aspectError, 0.005, "mode \(mode) flips \(fx)/\(fy)")
                }
            }
        }
    }
}

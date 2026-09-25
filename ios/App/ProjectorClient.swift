import Foundation
import KeystoneCore

struct ProjectorError: LocalizedError {
    let message: String
    var errorDescription: String? { message }
}

/// The projector's keystone state as the API reports it.
struct ProjectorState {
    var values: KeystoneValues
    var installmode: Int
}

/// Talks to the projector app's HTTPS API. The projector's certificate is self-signed; it's trusted only
/// if its SHA-256 matches the fingerprint from the QR code.
final class ProjectorClient: NSObject, URLSessionDelegate {
    let link: ProjectorLink
    private lazy var session = URLSession(configuration: .ephemeral, delegate: self, delegateQueue: nil)

    init(link: ProjectorLink) { self.link = link }

    func urlSession(_ session: URLSession, didReceive challenge: URLAuthenticationChallenge,
                    completionHandler: @escaping (URLSession.AuthChallengeDisposition, URLCredential?) -> Void) {
        let (disposition, credential) = PinnedTrust.evaluate(challenge, fingerprint: link.fingerprint)
        completionHandler(disposition, credential)
    }

    // MARK: API

    /// Starts a session: the projector shows the calibration pattern.
    func startSession() async throws -> (state: ProjectorState?, helperUp: Bool, aspect: Double, layouts: [Int: (flipX: Bool, flipY: Bool)]) {
        let j = try await call("POST", "/api/session", body: [:])
        let pattern = j["pattern"] as? [String: Any]
        let w = (pattern?["width"] as? NSNumber)?.doubleValue ?? 16, h = (pattern?["height"] as? NSNumber)?.doubleValue ?? 9
        let helperUp = (j["helperUp"] as? Bool) ?? false
        let state = (j["keystone"] as? [String: Any]).map(Self.state)
        var layouts: [Int: (flipX: Bool, flipY: Bool)] = [:]
        for (k, v) in (j["layouts"] as? [String: Any]) ?? [:] {
            if let mode = Int(k), let d = v as? [String: Any] {
                layouts[mode] = ((d["flipX"] as? Bool) ?? false, (d["flipY"] as? Bool) ?? false)
            }
        }
        return (state, helperUp, w / h, layouts)
    }

    /// Remembers (or forgets) which wall corner each value moves for an installmode, so later
    /// calibrations can skip the test nudge. Shared with the web page.
    func rememberLayout(installmode: Int, flipX: Bool, flipY: Bool) async {
        _ = try? await call("POST", "/api/layout", body: ["installmode": installmode, "flipX": flipX, "flipY": flipY])
    }

    func forgetLayout(installmode: Int) async {
        _ = try? await call("POST", "/api/layout", body: ["installmode": installmode, "forget": true])
    }

    func apply(_ v: KeystoneValues) async throws -> ProjectorState {
        Self.state(try await call("POST", "/api/apply", body: v.dictionary, timeout: 90))
    }

    func ping() async { _ = try? await call("POST", "/api/ping", body: [:]) }
    func done() async { _ = try? await call("POST", "/api/done", body: [:]) }

    static func state(_ j: [String: Any]) -> ProjectorState {
        var d: [String: Int] = [:]
        for k in KeystoneValues.keys { d[k] = (j[k] as? NSNumber)?.intValue ?? 0 }
        return ProjectorState(values: KeystoneValues(d), installmode: (j["installmode"] as? NSNumber)?.intValue ?? 0)
    }

    private func call(_ method: String, _ path: String, body: [String: Any]?, timeout: TimeInterval = 15) async throws -> [String: Any] {
        var req = URLRequest(url: link.baseURL.appendingPathComponent(path), timeoutInterval: timeout)
        req.httpMethod = method
        req.setValue(link.pairCode, forHTTPHeaderField: "X-Pair")
        if let body {
            req.setValue("application/json", forHTTPHeaderField: "Content-Type")
            req.httpBody = try JSONSerialization.data(withJSONObject: body)
        }
        let (data, resp) = try await session.data(for: req)
        let j = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any] ?? [:]
        let code = (resp as? HTTPURLResponse)?.statusCode ?? 0
        if code != 200 || (j["ok"] as? Bool) == false {
            if code == 401 { throw ProjectorError(message: "The pairing code has changed — scan the QR code on the projector again.") }
            throw ProjectorError(message: (j["error"] as? String) ?? "The projector answered with HTTP \(code).")
        }
        return j
    }
}

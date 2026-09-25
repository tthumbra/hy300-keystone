import Foundation
import Network

/// The WebSocket to the projector app (wss://<ip>:8443/api/remote?k=<pairing code>), pinned to the
/// projector's certificate. Remembers the paired projector; if it can't be reached (e.g. its IP
/// changed) it looks for it on the network by its certificate fingerprint (Bonjour _hykeystone._tcp).
@MainActor
final class RemoteConnection: NSObject, ObservableObject, URLSessionWebSocketDelegate {
    enum State: Equatable { case unpaired, connecting, connected, searching, offline(String) }

    @Published private(set) var state = State.unpaired
    @Published private(set) var link: ProjectorLink?

    private var session: URLSession!
    private var task: URLSessionWebSocketTask?
    private var failures = 0
    private var pingTimer: Timer?
    private var flushTimer: Timer?
    private var browser: NWBrowser?
    private var pendingMove = (x: 0.0, y: 0.0)
    private var pendingScroll = (v: 0.0, h: 0.0)

    static let savedKey = "pairedProjector"

    override init() {
        super.init()
        session = URLSession(configuration: .ephemeral, delegate: self, delegateQueue: .main)
        if let data = UserDefaults.standard.data(forKey: Self.savedKey),
           let saved = try? JSONDecoder().decode(ProjectorLink.self, from: data) {
            link = saved
        }
        // Coalesce pointer movement: at most ~120 messages a second.
        flushTimer = Timer.scheduledTimer(withTimeInterval: 1.0 / 120, repeats: true) { [weak self] _ in
            MainActor.assumeIsolated { self?.flush() }
        }
    }

    // MARK: Pairing

    func pair(with text: String) -> Bool {
        guard let l = ProjectorLink(text) else { return false }
        pair(with: l)
        return true
    }

    func pair(with l: ProjectorLink) {
        guard l != link || state != .connected else { return }
        save(l)
        failures = 0
        connect()
    }

    func forget() {
        disconnect()
        link = nil
        UserDefaults.standard.removeObject(forKey: Self.savedKey)
        state = .unpaired
    }

    private func save(_ l: ProjectorLink) {
        link = l
        if let data = try? JSONEncoder().encode(l) { UserDefaults.standard.set(data, forKey: Self.savedKey) }
    }

    // MARK: Connection

    func connect() {
        guard let link else { state = .unpaired; return }
        disconnect()
        state = .connecting
        var c = URLComponents(url: link.baseURL, resolvingAgainstBaseURL: false)!
        c.scheme = "wss"
        c.path = "/api/remote"
        c.queryItems = [URLQueryItem(name: "k", value: link.pairCode)]
        let t = session.webSocketTask(with: c.url!)
        task = t
        t.resume()
        receive(t)
    }

    func disconnect() {
        pingTimer?.invalidate()
        pingTimer = nil
        task?.cancel(with: .goingAway, reason: nil)
        task = nil
        browser?.cancel()
        browser = nil
    }

    /// Reads (and ignores) server messages so a closed socket is noticed.
    private func receive(_ t: URLSessionWebSocketTask) {
        t.receive { [weak self] result in
            Task { @MainActor in
                guard let self, t === self.task else { return }
                if case .failure = result { self.connectionLost() } else { self.receive(t) }
            }
        }
    }

    nonisolated func urlSession(_ session: URLSession, didReceive challenge: URLAuthenticationChallenge,
                                completionHandler: @escaping (URLSession.AuthChallengeDisposition, URLCredential?) -> Void) {
        let fp = MainActor.assumeIsolated { link?.fingerprint ?? "" }
        let (d, c) = PinnedTrust.evaluate(challenge, fingerprint: fp)
        completionHandler(d, c)
    }

    nonisolated func urlSession(_ session: URLSession, webSocketTask: URLSessionWebSocketTask, didOpenWithProtocol protocol: String?) {
        MainActor.assumeIsolated {
            guard webSocketTask === task else { return }
            state = .connected
            failures = 0
            pingTimer?.invalidate()
            pingTimer = Timer.scheduledTimer(withTimeInterval: 15, repeats: true) { [weak self] _ in
                MainActor.assumeIsolated { self?.task?.sendPing { _ in } }
            }
        }
    }

    nonisolated func urlSession(_ session: URLSession, task: URLSessionTask, didCompleteWithError error: Error?) {
        MainActor.assumeIsolated {
            guard task === self.task else { return }
            connectionLost()
        }
    }

    private func connectionLost() {
        guard link != nil else { return }
        task = nil
        pingTimer?.invalidate()
        failures += 1
        if failures >= 2 {
            findOnNetwork()
        } else {
            state = .offline("Reconnecting…")
            Task { try? await Task.sleep(nanoseconds: 1_000_000_000); self.connect() }
        }
    }

    /// Looks for the paired projector by certificate fingerprint and updates its address.
    private func findOnNetwork() {
        guard let link, browser == nil else { return }
        state = .searching
        let b = NWBrowser(for: .bonjourWithTXTRecord(type: "_hykeystone._tcp", domain: nil), using: .tcp)
        browser = b
        b.browseResultsChangedHandler = { [weak self] results, _ in
            for r in results {
                guard case .bonjour(let txt) = r.metadata, txt.dictionary["fp"]?.lowercased() == link.fingerprint else { continue }
                Self.resolveIPv4(r.endpoint) { host in
                    Task { @MainActor in
                        guard let self, let host, self.state == .searching else { return }
                        var l = link
                        l.host = host
                        self.save(l)
                        self.failures = 0
                        self.connect()
                    }
                }
                return
            }
        }
        b.start(queue: .main)
        // Give up searching after a while and retry the last address.
        Task {
            try? await Task.sleep(nanoseconds: 12_000_000_000)
            if self.state == .searching {
                self.browser?.cancel()
                self.browser = nil
                self.failures = 0
                self.state = .offline("Projector not found — is it on and on the same Wi-Fi?")
            }
        }
    }

    /// Connects to a Bonjour endpoint just long enough to learn its IPv4 address.
    nonisolated private static func resolveIPv4(_ endpoint: NWEndpoint, _ done: @escaping (String?) -> Void) {
        let params = NWParameters.tcp
        (params.defaultProtocolStack.internetProtocol as? NWProtocolIP.Options)?.version = .v4
        let c = NWConnection(to: endpoint, using: params)
        c.stateUpdateHandler = { s in
            switch s {
            case .ready:
                if case .hostPort(let host, _) = c.currentPath?.remoteEndpoint {
                    var h = "\(host)"
                    if let pct = h.firstIndex(of: "%") { h = String(h[..<pct]) }
                    done(h)
                } else {
                    done(nil)
                }
                c.cancel()
            case .failed, .cancelled:
                c.cancel()
            default:
                break
            }
        }
        c.start(queue: .global())
    }

    // MARK: Commands (see helper RemoteInput)

    func send(_ line: String) {
        guard state == .connected, let task else { return }
        task.send(.string(line)) { _ in }
    }

    func move(_ dx: Double, _ dy: Double) { pendingMove.x += dx; pendingMove.y += dy }
    func scroll(_ v: Double, _ h: Double) { pendingScroll.v += v; pendingScroll.h += h }
    func buttons(_ b: Int) { flush(); send("b \(b)") }
    func key(_ code: Int) { send("k \(code)") }
    func type(_ text: String) { send("t " + text.replacingOccurrences(of: "\n", with: " ")) }

    private func flush() {
        var lines: [String] = []
        let mx = pendingMove.x.rounded(.towardZero), my = pendingMove.y.rounded(.towardZero)
        if mx != 0 || my != 0 {
            lines.append("m \(Int(mx)) \(Int(my))")
            pendingMove.x -= mx
            pendingMove.y -= my
        }
        let sv = pendingScroll.v.rounded(.towardZero), sh = pendingScroll.h.rounded(.towardZero)
        if sv != 0 || sh != 0 {
            lines.append("w \(Int(sv)) \(Int(sh))")
            pendingScroll.v -= sv
            pendingScroll.h -= sh
        }
        if !lines.isEmpty { send(lines.joined(separator: "\n")) }
    }
}

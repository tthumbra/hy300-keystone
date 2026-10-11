/*
 * Remote control page (what the QR on the projector opens). Talks to the projector app's WebSocket at
 * /api/remote, the same channel the iOS app uses: newline-separated commands "m dx dy" (pointer),
 * "b buttons", "w v h" (scroll), "k keycode" (Android key), "t text".
 */
(function () {
  'use strict';
  var POINTER_GAIN = 1.7;       // pointer px per finger px, before acceleration
  var TAP_MS = 250, TAP_PX = 8; // a touch shorter and smaller than this is a click
  var SCROLL_PX = 22;           // finger px per wheel notch
  var REPEAT_DELAY_MS = 400, REPEAT_MS = 110;
  var KEEPALIVE_MS = 15000;     // the projector drops a silent connection after 60 s

  var pair = new URLSearchParams(location.search).get('k') || '';
  var $ = function (id) { return document.getElementById(id); };
  var ws = null, retryMs = 500, keepalive = 0;

  function setStatus(kind, text) { $('statusDot').className = 'dot ' + kind; $('statusText').textContent = text; }

  // ------------------------------------------------------------------ connection

  function connect() {
    if (!pair) { setStatus('bad', 'Scan the QR code on the projector again'); return; }
    setStatus('', 'Connecting…');
    var sock = new WebSocket((location.protocol === 'https:' ? 'wss://' : 'ws://') + location.host
      + '/api/remote?k=' + encodeURIComponent(pair));
    ws = sock;
    sock.onmessage = function () { retryMs = 500; setStatus('good', 'Connected'); };   // the projector says "hello"
    sock.onclose = function () {
      if (ws !== sock) return;
      ws = null;
      clearInterval(keepalive);
      setStatus('bad', 'Reconnecting…');
      setTimeout(connect, retryMs);
      retryMs = Math.min(retryMs * 2, 5000);
    };
    clearInterval(keepalive);
    keepalive = setInterval(function () { send(''); }, KEEPALIVE_MS);
  }

  function send(cmd) {
    if (ws && ws.readyState === 1) ws.send(cmd + '\n');
  }

  // Coming back to the tab (phone unlocked): reconnect right away instead of waiting out the backoff.
  document.addEventListener('visibilitychange', function () {
    if (!document.hidden && !ws) { retryMs = 500; connect(); }
  });

  function buzz() { if (navigator.vibrate) navigator.vibrate(8); }

  // ------------------------------------------------------------------ touchpad

  var pad = $('pad');
  var touches = {};             // pointerId -> {x, y}
  var moved = 0, downAt = 0, maxTouches = 0;
  var pendX = 0, pendY = 0, scrollAcc = 0, panAcc = 0, flushQueued = false;

  function flush() {
    flushQueued = false;
    var dx = Math.round(pendX), dy = Math.round(pendY);
    if (dx || dy) { send('m ' + dx + ' ' + dy); pendX -= dx; pendY -= dy; }
  }
  function queueMove(dx, dy) {
    // Faster swipes travel further, so slow moves stay precise.
    var speed = Math.sqrt(dx * dx + dy * dy);
    var gain = POINTER_GAIN * (1 + Math.min(speed, 30) / 20);
    pendX += dx * gain; pendY += dy * gain;
    if (!flushQueued) { flushQueued = true; requestAnimationFrame(flush); }
  }

  pad.addEventListener('pointerdown', function (e) {
    e.preventDefault();
    pad.setPointerCapture(e.pointerId);
    touches[e.pointerId] = { x: e.clientX, y: e.clientY };
    var n = Object.keys(touches).length;
    if (n === 1) { moved = 0; downAt = Date.now(); maxTouches = 1; scrollAcc = panAcc = 0; }
    maxTouches = Math.max(maxTouches, n);
    pad.classList.add('active');
  });

  pad.addEventListener('pointermove', function (e) {
    var t = touches[e.pointerId];
    if (!t) return;
    var dx = e.clientX - t.x, dy = e.clientY - t.y;
    t.x = e.clientX; t.y = e.clientY;
    moved += Math.abs(dx) + Math.abs(dy);
    var n = Object.keys(touches).length;
    if (n === 1) {
      queueMove(dx, dy);
    } else {
      // Two fingers: the content follows the fingers (each finger reports half the motion).
      scrollAcc += dy / n; panAcc += dx / n;
      var v = Math.trunc(scrollAcc / SCROLL_PX), h = Math.trunc(panAcc / SCROLL_PX);
      if (v || h) { send('w ' + v + ' ' + (-h)); scrollAcc -= v * SCROLL_PX; panAcc -= h * SCROLL_PX; }
    }
  });

  function up(e) {
    if (!touches[e.pointerId]) return;
    delete touches[e.pointerId];
    if (Object.keys(touches).length) return;
    pad.classList.remove('active');
    if (maxTouches === 1 && moved < TAP_PX && Date.now() - downAt < TAP_MS) {
      send('b 1'); send('b 0');
      buzz();
    }
  }
  pad.addEventListener('pointerup', up);
  pad.addEventListener('pointercancel', up);

  // ------------------------------------------------------------------ keys

  Array.prototype.forEach.call(document.querySelectorAll('button[data-key]'), function (btn) {
    var key = btn.getAttribute('data-key'), repeats = btn.hasAttribute('data-repeat');
    var delay = 0, timer = 0;
    function stop() { clearTimeout(delay); clearInterval(timer); btn.classList.remove('down'); }
    function press() { send('k ' + key); typedKey(key); }
    btn.addEventListener('pointerdown', function (e) {
      e.preventDefault();               // keeps the on-screen keyboard open while pressing ⌫ / ⏎
      btn.classList.add('down');
      press();
      buzz();
      if (repeats) delay = setTimeout(function () { timer = setInterval(press, REPEAT_MS); }, REPEAT_DELAY_MS);
    });
    ['pointerup', 'pointercancel', 'pointerleave'].forEach(function (ev) { btn.addEventListener(ev, stop); });
    btn.addEventListener('contextmenu', function (e) { e.preventDefault(); });
  });

  // ------------------------------------------------------------------ typing

  // Whatever the phone keyboard does to the field (autocorrect, swipe typing, deleting), the projector
  // gets the difference: backspaces for what disappeared from the end, then the new characters.
  var text = $('text'), sent = '';
  text.addEventListener('input', function () {
    var now = text.value, same = 0;
    while (same < sent.length && same < now.length && sent[same] === now[same]) same++;
    for (var i = sent.length; i > same; i--) send('k 67');
    if (now.length > same) send('t ' + now.slice(same));
    sent = now;
  });
  // The ⌫ and ⏎ buttons act on the projector directly; keep the field in step with them.
  function typedKey(key) {
    if (key === '67') text.value = sent = sent.slice(0, -1);
    else if (key === '66') text.value = sent = '';
  }
  text.addEventListener('keydown', function (e) {
    if (e.key === 'Enter') { e.preventDefault(); send('k 66'); text.value = sent = ''; }
  });

  // The calibration page needs the camera, which browsers only allow over HTTPS (the "tls" port).
  var tls = new URLSearchParams(location.search).get('tls') || '8443';
  $('keystoneLink').href = (location.protocol === 'https:' ? '' : 'https://' + location.hostname + ':' + tls + '/')
    + 'keystone.html' + location.search;
  connect();
})();

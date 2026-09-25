/*
 * Live calibration page. Camera frames -> outline of the bright pattern (keystone.js detectQuad) ->
 * corners moved into a level virtual camera using the phone's gravity sensor (and the wall direction,
 * if set) -> averaged over a steady moment -> solve -> apply through the projector app -> repeat.
 */
(function () {
  'use strict';
  var K = window.Keystone;
  var KEYS = K.KEYS;
  var PROC_LONG = 960;          // processing resolution (longer side, px)
  var FOCAL = 0.72;             // focal length / longer image side; phone main cameras are ~0.69-0.75
  var FRAME_MS = 90;            // min time between processed frames
  // Measuring doesn't need a still phone: every frame is levelled with its own gravity reading, so
  // each is a measurement by itself. A measurement is the mean of the frames near the median.
  var SAMPLES = 10;             // frames per measurement
  var MAX_SWING_DEG = 3;        // skip frames taken while the phone swings faster than this per frame
  var OUTLIER_PX = 4;           // drop frames whose corners are further than this from the median
  var SETTLE_MIN_MS = 400;      // after an apply, measure once the picture is back (at least this long)
  var MAX_ROUNDS = 5;
  var STEP = 5;                 // manual fine-tune step, same as the remote

  var pair = new URLSearchParams(location.search).get('k') || '';
  var values = null, startValues = null, aspect = 16 / 9, busy = false;
  // How values map to wall corners: the encoding comes from the projector's installmode; the flips are
  // measured with a test nudge at the start of each calibration (mappingKnown), so any setup works.
  // The projector app remembers measured layouts per installmode, so the nudge is usually skipped.
  var installmode = 2, L = K.layout(2, false, false), mappingKnown = false, knownLayouts = {};
  var $ = function (id) { return document.getElementById(id); };

  // ------------------------------------------------------------------ projector API

  function api(path, body, extraHeaders) {
    var headers = { 'X-Pair': pair };
    var isJson = body !== undefined && !(body instanceof Blob);
    if (isJson) headers['Content-Type'] = 'application/json';
    for (var h in extraHeaders || {}) headers[h] = extraHeaders[h];
    return fetch(path, {
      method: body === undefined ? 'GET' : 'POST', headers: headers,
      body: body === undefined ? undefined : isJson ? JSON.stringify(body) : body,
    }).then(function (r) {
      return r.json().catch(function () { return {}; }).then(function (j) {
        if (!r.ok || j.ok === false) throw new Error(j.error || ('HTTP ' + r.status));
        return j;
      });
    });
  }

  function pick(o) { var v = {}; KEYS.forEach(function (k) { v[k] = o[k]; }); return v; }
  function changeSize(a, b) { return Math.max.apply(null, KEYS.map(function (k) { return Math.abs(a[k] - b[k]); })); }

  function setStatus(kind, text) { $('statusDot').className = 'dot ' + (kind || ''); $('statusText').textContent = text; }
  function message(el, kind, text) {
    $(el).innerHTML = text ? '<div class="msg ' + kind + '"></div>' : '';
    if (text) $(el).firstChild.textContent = text;
  }

  function connect() {
    if (!pair) { setStatus('bad', 'Missing pairing code — scan the QR code on the projector again.'); return; }
    api('/api/session', {}).then(function (s) {
      if (s.pattern) aspect = s.pattern.width / s.pattern.height;
      if (!s.helperUp || !s.keystone) {
        setStatus('bad', 'The projector is still starting its keystone helper… retrying');
        setTimeout(connect, 3000);
        return;
      }
      values = pick(s.keystone);
      knownLayouts = s.layouts || {};
      installmode = null;
      setInstallmode(s.keystone.installmode);
      if (!startValues) startValues = pick(values);
      setStatus('good', 'Connected — the projector is showing the calibration pattern.');
      $('startBtn').disabled = false;
      renderPads();
    }).catch(function (e) {
      setStatus('bad', e.message + ' — retrying');
      setTimeout(connect, 3000);
    });
  }

  setInterval(function () { if (values) api('/api/ping', {}).catch(function () {}); }, 30000);

  function setInstallmode(m) {
    if (m === installmode && L) return;
    installmode = m;
    var known = knownLayouts[String(m)];
    L = K.layout(m, !!(known && known.flipX), !!(known && known.flipY));
    mappingKnown = !!known;
    renderPads();
  }

  function rememberLayout(forget) {
    var body = forget ? { installmode: installmode, forget: true } : { installmode: installmode, flipX: L.flipX, flipY: L.flipY };
    if (forget) delete knownLayouts[String(installmode)];
    else knownLayouts[String(installmode)] = { flipX: L.flipX, flipY: L.flipY };
    api('/api/layout', body).catch(function () {});
  }

  function applyValues(v, note) {
    busy = true;
    updateButtons();
    setStatus('', note || 'Applying… the projector will flash for a few seconds.');
    return api('/api/apply', v).then(function (r) {
      values = pick(r);
      if (r.installmode !== installmode) {
        setInstallmode(r.installmode);
        if (loop.running) stopLoop('The projector\'s projection mode changed — start calibration again.', 'bad');
      }
      setStatus('good', 'Applied and saved on the projector.');
      renderPads();
      return r;
    }).catch(function (e) {
      setStatus('bad', 'Could not apply: ' + e.message);
      throw e;
    }).finally(function () { busy = false; updateButtons(); });
  }

  // ------------------------------------------------------------------ sensors

  var orient = null;            // latest {alpha, beta, gamma}
  var headings = [];            // recent camera headings (for a smoothed value)
  var wallHeading = null;

  function onOrientation(e) {
    if (e.beta == null || e.gamma == null) return;
    orient = { alpha: e.alpha || 0, beta: e.beta, gamma: e.gamma };
    headings.push(K.cameraHeading(orient.alpha, orient.beta, orient.gamma));
    if (headings.length > 10) headings.shift();
  }

  function smoothedHeading() {
    if (!headings.length) return null;
    var sx = 0, sy = 0;
    headings.forEach(function (h) { sx += Math.cos(h); sy += Math.sin(h); });
    return Math.atan2(sy, sx);
  }

  function wrap(a) { while (a > Math.PI) a -= 2 * Math.PI; while (a < -Math.PI) a += 2 * Math.PI; return a; }

  function screenAngle() {
    if (screen.orientation && typeof screen.orientation.angle === 'number') return screen.orientation.angle;
    return typeof window.orientation === 'number' ? window.orientation : 0;
  }

  function currentUp() { return orient ? K.upFromOrientation(orient.beta, orient.gamma) : [0, 1, 0]; }

  function yawToWall() {
    var h = smoothedHeading();
    return wallHeading == null || h == null ? 0 : wrap(h - wallHeading);
  }

  // ------------------------------------------------------------------ camera

  var video = $('video'), overlay = $('overlay');
  var proc = document.createElement('canvas'), procCtx = null;

  $('startBtn').addEventListener('click', function () {
    if (!window.isSecureContext || !navigator.mediaDevices || !navigator.mediaDevices.getUserMedia) {
      message('startMsg', 'bad', 'This browser won\'t allow the camera here. Open the link from the projector\'s QR code (it starts with https://).');
      return;
    }
    // iOS: motion permission must be requested directly inside the tap.
    var motion = (window.DeviceOrientationEvent && typeof DeviceOrientationEvent.requestPermission === 'function')
      ? DeviceOrientationEvent.requestPermission().catch(function () { return 'denied'; })
      : Promise.resolve('granted');
    motion.then(function (state) {
      if (state === 'granted') window.addEventListener('deviceorientation', onOrientation);
      return navigator.mediaDevices.getUserMedia({
        audio: false,
        video: { facingMode: { ideal: 'environment' }, width: { ideal: 1920 }, height: { ideal: 1080 } },
      }).then(function (stream) {
        video.srcObject = stream;
        return video.play();
      }).then(function () {
        $('startCard').classList.add('hidden');
        $('liveCard').classList.remove('hidden');
        if (state !== 'granted') message('loopMsg', 'warn', 'Motion access is off, so the page assumes the phone is upright and level. Hold it as straight as you can.');
        requestAnimationFrame(tick);
      });
    }).catch(function (e) {
      message('startMsg', 'bad', 'Could not start the camera: ' + e.message);
    });
  });

  // ------------------------------------------------------------------ per-frame processing

  var lastFrame = 0, lastUp = null, failingSince = 0, lastDebugUpload = 0, debugUploads = 0;
  var lastDetection = null;

  function tick(now) {
    requestAnimationFrame(tick);
    if (now - lastFrame < FRAME_MS || video.readyState < 2 || !video.videoWidth) return;
    lastFrame = now;

    var vw = video.videoWidth, vh = video.videoHeight, scale = PROC_LONG / Math.max(vw, vh);
    var W = Math.round(vw * scale), H = Math.round(vh * scale);
    if (proc.width !== W || proc.height !== H) { proc.width = W; proc.height = H; procCtx = proc.getContext('2d', { willReadFrequently: true }); }
    procCtx.drawImage(video, 0, 0, W, H);

    var up = currentUp();
    var turned = lastUp ? Math.acos(Math.min(1, up[0] * lastUp[0] + up[1] * lastUp[1] + up[2] * lastUp[2])) * 180 / Math.PI : 0;
    lastUp = up;
    var yaw = yawToWall();
    var rect = K.levelRectifier(up, screenAngle(), W, H, FOCAL, yaw);
    var det = K.detectQuad(procCtx.getImageData(0, 0, W, H), rect.roll);
    lastDetection = { det: det, rect: rect, W: W, H: H };

    drawOverlay(det, W, H);
    updateMeters(rect, yaw);

    var hint;
    if (det.error) hint = det.error;
    else if (Math.abs(rect.pitch) > 35) hint = 'Point the phone more directly at the picture.';
    else if (turned > MAX_SWING_DEG) hint = 'Moving a bit fast…';
    else hint = loop.running ? 'Measuring — keep the picture in view' : 'Picture found. Tap Start calibration.';
    if (loop.phase === 'apply') hint = loop.stage === 'baseline' ? 'Test nudge…' : 'Adjusting the projector…';
    else if (loop.phase === 'settle') hint = 'Checking the result…';
    $('hint').textContent = hint;

    if (det.error) {
      if (!failingSince) failingSince = now;
      if (now - failingSince > 4000 && now - lastDebugUpload > 30000 && debugUploads < 5 && loop.phase !== 'apply') {
        lastDebugUpload = now; debugUploads++;
        sendDebugFrame('auto: ' + det.reason);
      }
    } else failingSince = 0;

    maybeShare(now);
    if (loop.running && loop.phase === 'measure') measure(det, rect, turned);
    // After an apply: start measuring as soon as the picture is back (ControlCenter has gone).
    if (loop.running && loop.phase === 'settle' && now >= loop.settleUntil && !det.error) { loop.phase = 'measure'; loop.samples = []; }
  }

  function drawOverlay(det, W, H) {
    var cw = overlay.clientWidth, ch = overlay.clientHeight;
    if (overlay.width !== cw * devicePixelRatio) { overlay.width = cw * devicePixelRatio; overlay.height = ch * devicePixelRatio; }
    var ctx = overlay.getContext('2d');
    ctx.clearRect(0, 0, overlay.width, overlay.height);
    if (det.error) return;
    var s = overlay.width / W, lw = 2.5 * devicePixelRatio;
    ctx.beginPath();
    K.CORNERS.forEach(function (k, i) {
      var p = det.corners[k];
      if (i === 0) ctx.moveTo(p[0] * s, p[1] * s); else ctx.lineTo(p[0] * s, p[1] * s);
    });
    ctx.closePath();
    ctx.strokeStyle = loop.running ? '#5aa9ff' : '#5fd08f';
    ctx.lineWidth = lw;
    ctx.stroke();
    K.CORNERS.forEach(function (k) {
      ctx.beginPath();
      ctx.arc(det.corners[k][0] * s, det.corners[k][1] * s, lw * 2.2, 0, Math.PI * 2);
      ctx.fillStyle = ctx.strokeStyle;
      ctx.fill();
    });
  }

  function updateMeters(rect, yaw) {
    var roll = rect.roll * 180 / Math.PI;
    setMeter('mPitch', 'tilt', orient ? rect.pitch.toFixed(1) + '°' : 'no sensor', Math.abs(rect.pitch) < 25);
    // Any multiple of 90° is fine (phone sideways, or rotation lock on): the level view corrects it.
    var off90 = Math.abs(roll - 90 * Math.round(roll / 90));
    setMeter('mRoll', 'roll', orient ? roll.toFixed(1) + '°' : 'no sensor', off90 < 10);
    setMeter('mYaw', 'facing wall', wallHeading == null ? 'not set' : (yaw * 180 / Math.PI).toFixed(1) + '°', wallHeading != null && Math.abs(yaw) < 30 * Math.PI / 180);
  }

  function setMeter(id, label, value, ok) {
    $(id).className = ok ? 'ok' : 'off';
    $(id).innerHTML = label + '<b></b>';
    $(id).lastChild.textContent = value;
  }

  // ------------------------------------------------------------------ wall direction

  $('wallBtn').addEventListener('click', function () {
    if (!orient) { message('loopMsg', 'warn', 'Motion access is off, so the wall direction can\'t be measured.'); return; }
    var r = K.levelRectifier(currentUp(), screenAngle(), 100, 100, FOCAL, 0);
    if (Math.abs(r.pitch) > 15) {
      $('wallText').textContent = 'The phone isn\'t upright (tilted ' + r.pitch.toFixed(0) + '°). Hold its back flat against the wall and tap Set again.';
      return;
    }
    wallHeading = smoothedHeading();
    $('wallState').textContent = '(set)';
    $('wallText').textContent = 'Wall direction set. Step back to where you want to aim from — the "facing wall" reading shows how far you\'re turned.';
    $('wallBtn').textContent = 'Set again';
    $('wallClear').classList.remove('hidden');
  });

  $('wallClear').addEventListener('click', function () {
    wallHeading = null;
    $('wallState').textContent = '(optional)';
    $('wallText').textContent = 'Not set: the page assumes you are facing the wall squarely.';
    $('wallBtn').textContent = 'Set wall direction';
    $('wallClear').classList.add('hidden');
  });

  // ------------------------------------------------------------------ calibration loop

  var loop = { running: false, phase: 'idle', round: 0, samples: [], settleUntil: 0 };

  $('calibrateBtn').addEventListener('click', function () {
    if (!values || busy) return;
    // Test nudge first, unless this projection mode's layout is remembered (then verify it on round 1).
    loop = { running: true, phase: 'measure', stage: mappingKnown ? 'correct' : 'baseline', verify: mappingKnown,
      round: 0, samples: [], settleUntil: 0, prevError: null };
    message('loopMsg', '', '');
    $('loopTitle').textContent = 'Calibrating';
    $('finishBtn').classList.add('hidden');
    updateButtons();
  });

  $('stopBtn').addEventListener('click', function () { stopLoop('Stopped.'); });

  function stopLoop(text, kind) {
    // Stopped between the test nudge and the first correction: take the nudge back off.
    var undoProbe = loop.running && loop.stage === 'probe' && loop.beforeProbe;
    loop.running = false;
    loop.phase = 'idle';
    $('progress').style.width = '0';
    $('loopTitle').textContent = 'Calibrate';
    if (text) message('loopMsg', kind || 'warn', text);
    updateButtons();
    if (undoProbe) {
      var restore = loop.beforeProbe;
      loop.beforeProbe = null;
      var run = function () {
        if (busy) { setTimeout(run, 500); return; }
        applyValues(restore, 'Undoing the test nudge…').catch(function () {});
      };
      run();
    }
  }

  function measure(det, rect, turned) {
    // Unusable frames are skipped, not a reason to start over.
    if (det.error || turned > MAX_SWING_DEG || Math.abs(rect.pitch) > 35) return;
    loop.samples.push(K.mapCorners(rect.H, det.corners));
    if (loop.samples.length > SAMPLES * 2) loop.samples.shift();
    $('progress').style.width = Math.min(100, Math.round(100 * loop.samples.length / SAMPLES)) + '%';
    if (loop.samples.length < SAMPLES) return;
    var m = robustMean(loop.samples);
    if (m) finishMeasurement(m);
  }

  /** Mean of the frames whose corners are all near the per-coordinate median; null if too few agree. */
  function robustMean(samples) {
    var med = {};
    K.CORNERS.forEach(function (k) {
      med[k] = [0, 1].map(function (i) {
        var v = samples.map(function (s) { return s[k][i]; }).sort(function (a, b) { return a - b; });
        return v[Math.floor(v.length / 2)];
      });
    });
    var good = samples.filter(function (s) {
      return K.CORNERS.every(function (k) { return Math.hypot(s[k][0] - med[k][0], s[k][1] - med[k][1]) <= OUTLIER_PX; });
    });
    return good.length >= Math.ceil(SAMPLES * 0.6) ? average(good) : null;
  }

  function average(samples) {
    var out = {};
    K.CORNERS.forEach(function (k) {
      var x = 0, y = 0;
      samples.forEach(function (s) { x += s[k][0]; y += s[k][1]; });
      out[k] = [x / samples.length, y / samples.length];
    });
    return out;
  }

  function isGood(q) { return q.maxAngleError < 0.5 && Math.abs(q.tilt) < 0.4 && q.aspectError < 0.012; }

  function finishMeasurement(observed) {
    if (loop.stage === 'baseline') {
      loop.before = observed;
      loop.beforeProbe = pick(values);
      loop.probe = K.makeProbe(values, installmode);
      loop.phase = 'apply';
      $('loopTitle').textContent = 'Calibrating — test nudge';
      message('loopMsg', 'warn', 'Nudging one corner to see which way this projector moves it…');
      applyValues(loop.probe.values, 'Test nudge… the projector will flash for a few seconds.').then(function () {
        if (!loop.running) return;
        loop.stage = 'probe';
        loop.phase = 'settle';
        loop.settleUntil = performance.now() + SETTLE_MIN_MS;
        loop.samples = [];
      }).catch(function (e) { stopLoop('Could not apply: ' + e.message, 'bad'); });
      return;
    }
    if (loop.stage === 'probe') {
      var flips = K.inferFlips(loop.before, observed, loop.probe);
      if (flips.error) { stopLoop(flips.error + ' (Undo all changes restores the settings.)', 'bad'); return; }
      L = K.layout(installmode, flips.flipX, flips.flipY);
      mappingKnown = true;
      rememberLayout(false);
      loop.stage = 'correct';
      renderPads();
    }
    correct(observed);
  }

  function correct(observed) {
    var res = K.solve(observed, values, aspect, L);
    if (res.error) { stopLoop(res.error, 'bad'); return; }
    var q = res.quality, describe = 'corners within ' + q.maxAngleError.toFixed(1) + '° of square, level within ' + Math.abs(q.tilt).toFixed(1) + '°';
    // A remembered layout that made things worse is wrong (e.g. the projector was physically flipped):
    // forget it and do the test nudge from here.
    if (loop.verify && loop.prevError !== null && q.maxAngleError > loop.prevError + 0.3) {
      rememberLayout(true);
      L = K.layout(installmode, false, false);
      mappingKnown = false;
      loop.verify = false;
      loop.stage = 'baseline';
      message('loopMsg', 'warn', 'That made it worse — re-checking which way the projector moves…');
      finishMeasurement(observed);
      return;
    }
    if (loop.prevError !== null) loop.verify = false;
    loop.prevError = q.maxAngleError;
    if (isGood(q) || changeSize(res.values, values) <= 1) {
      stopLoop();
      $('loopTitle').textContent = 'Done';
      message('loopMsg', 'good', 'The picture is rectangular: ' + describe + '. Tap Finish.');
      $('finishBtn').classList.remove('hidden');
      return;
    }
    if (loop.round >= MAX_ROUNDS) {
      stopLoop('Stopped after ' + MAX_ROUNDS + ' rounds (' + describe + '). You can fine-tune by hand below.');
      return;
    }
    loop.round++;
    loop.phase = 'apply';
    $('loopTitle').textContent = 'Calibrating — round ' + loop.round;
    message('loopMsg', 'warn', 'Measured: ' + describe + '. Adjusting…');
    applyValues(res.values).then(function () {
      if (!loop.running) return;
      loop.phase = 'settle';
      loop.settleUntil = performance.now() + SETTLE_MIN_MS;
      loop.samples = [];
    }).catch(function (e) { stopLoop('Could not apply: ' + e.message, 'bad'); });
  }

  $('finishBtn').addEventListener('click', function () {
    api('/api/done', {}).catch(function () {});
    values = null;
    var stream = video.srcObject;
    if (stream) stream.getTracks().forEach(function (t) { t.stop(); });
    $('liveCard').classList.add('hidden');
    setStatus('good', 'Done. The projector keeps these settings, even after a restart. You can close this page.');
  });

  // ------------------------------------------------------------------ troubleshooting frames

  // "Share camera view": send a frame about once a second while ticked, so the developer can see
  // exactly what the page sees. Skips a beat if the previous upload hasn't finished.
  var sharing = false, shareBusy = false, shareLast = 0, shareSent = 0;
  $('shareToggle').addEventListener('change', function () {
    sharing = this.checked;
    $('shareCount').textContent = sharing ? '(sending…)' : '';
  });

  function maybeShare(now) {
    if (!sharing || shareBusy || now - shareLast < 1000) return;
    shareLast = now;
    shareBusy = true;
    sendDebugFrame('share').then(function () {
      shareSent++;
      $('shareCount').textContent = '(' + shareSent + ' sent)';
    }).finally(function () { shareBusy = false; });
  }

  function sendDebugFrame(reason) {
    if (!proc.width) return Promise.resolve();
    var d = lastDetection || {};
    var info = {
      reason: reason, error: d.det && d.det.error, reasonCode: d.det && d.det.reason,
      threshold: d.det && d.det.threshold, area: d.det && d.det.area, corners: d.det && d.det.corners,
      yawToWall: yawToWall() * 180 / Math.PI, loopPhase: loop.phase, loopRound: loop.round, values: values,
      orient: orient, screenAngle: screenAngle(), wallSet: wallHeading != null,
      video: [video.videoWidth, video.videoHeight], proc: [proc.width, proc.height],
      pitch: d.rect && d.rect.pitch, roll: d.rect && d.rect.roll, ua: navigator.userAgent,
    };
    return new Promise(function (res) { proc.toBlob(res, 'image/jpeg', 0.85); }).then(function (blob) {
      return api('/api/debug-frame', blob, { 'Content-Type': 'image/jpeg', 'X-Debug-Info': encodeURIComponent(JSON.stringify(info)) });
    }).catch(function () {});
  }

  $('debugLink').addEventListener('click', function (e) {
    e.preventDefault();
    if (!proc.width) { $('debugLink').textContent = 'Start the camera first'; return; }
    sendDebugFrame('manual').then(function () { $('debugLink').textContent = 'Sent — thanks'; });
  });

  // ------------------------------------------------------------------ manual fine-tune

  // Wall corners; the layout decides which values each arrow changes.
  var PADS = [
    { name: 'Top-left', wall: 'tl' }, { name: 'Top-right', wall: 'tr' },
    { name: 'Bottom-left', wall: 'bl' }, { name: 'Bottom-right', wall: 'br' },
  ];
  var pending = null, pendingTimer = null;

  function renderPads() {
    var shown = pending || values;
    if (!shown) return;
    $('pads').innerHTML = '';
    PADS.forEach(function (p) {
      var el = document.createElement('div');
      el.className = 'pad';
      el.innerHTML = '<div class="name"></div><div class="grid">' +
        '<span></span><button data-d="up" aria-label="up">↑</button><span></span>' +
        '<button data-d="left" aria-label="left">←</button><span></span><button data-d="right" aria-label="right">→</button>' +
        '<span></span><button data-d="down" aria-label="down">↓</button><span></span></div><div class="vals"></div>';
      var x = L.prop(p.wall) + 'x', y = L.prop(p.wall) + 'y';
      el.querySelector('.name').textContent = p.name;
      el.querySelector('.vals').textContent = x + ' ' + shown[x] + ' · ' + y + ' ' + shown[y] + (mappingKnown ? '' : ' ?');
      Array.prototype.forEach.call(el.querySelectorAll('button'), function (b) {
        b.addEventListener('click', function () { nudge(p, b.getAttribute('data-d')); });
      });
      $('pads').appendChild(el);
    });
  }

  function nudge(p, dir) {
    if (!values) return;
    if (loop.running) stopLoop('Calibration stopped for manual adjustment.');
    pending = L.nudge(pending || pick(values), p.wall, dir, STEP);
    renderPads();
    clearTimeout(pendingTimer);
    pendingTimer = setTimeout(flushNudges, 900);
  }

  function flushNudges() {
    if (!pending) return;
    if (busy) { pendingTimer = setTimeout(flushNudges, 500); return; }
    var v = pending;
    pending = null;
    if (changeSize(v, values) === 0) { renderPads(); return; }
    applyValues(v).catch(function () { renderPads(); });
  }

  $('undoBtn').addEventListener('click', function () {
    if (!startValues || busy) return;
    if (loop.running) stopLoop();
    pending = null;
    applyValues(startValues, 'Restoring the settings from before…').catch(function () {});
  });

  $('resetBtn').addEventListener('click', function () {
    if (busy) return;
    if (loop.running) stopLoop();
    pending = null;
    applyValues(L.noCorrection(), 'Removing all correction…').catch(function () {});
  });

  function updateButtons() {
    $('calibrateBtn').classList.toggle('hidden', loop.running);
    $('stopBtn').classList.toggle('hidden', !loop.running);
    $('calibrateBtn').disabled = busy;
    ['undoBtn', 'resetBtn'].forEach(function (id) { $(id).disabled = busy; });
  }

  connect();
})();

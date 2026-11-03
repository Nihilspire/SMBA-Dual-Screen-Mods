/* music.js - launcher music: "Curtain Call", an original tune written for Super Mario Advance Dual. A soft
 * shuffle in B-flat, 4/4: music-box melody, warm pad, gentle bass, brushed shaker and a few twinkling stars, like a
 * little stage waiting for the show. The melody and harmony are new; no notes come from the game's soundtrack.
 * Synthesised in WebAudio and kept low-key: the launcher plays it, never the game or the pause menu. Silent where
 * WebAudio is missing. Same API as Metroid Dual's music.js. */
(function (root) {
  'use strict';
  var AC = root.AudioContext || root.webkitAudioContext;
  var M = { playing: false };
  if (!AC) { M.play = M.stop = M.resume = function () {}; root.Music = M; return; }

  var ctx = null, master = null, noiseBuf = null, timer = null, step = 0, nextT = 0;
  var BPM = 84, STEP = 60 / BPM / 4, LOOK = 0.2, BAR = 16;       // 16th grid, 4/4 = 16 steps a bar
  var LEVEL = 1.7;                                                 // a bit under Metroid Dual's loudness
  var hz = function (m) { return 440 * Math.pow(2, (m - 69) / 12); };

  function noise() {
    if (noiseBuf) return noiseBuf;
    var len = ctx.sampleRate, b = ctx.createBuffer(1, len, ctx.sampleRate), d = b.getChannelData(0);
    for (var i = 0; i < len; i++) d[i] = Math.random() * 2 - 1;
    return (noiseBuf = b);
  }
  function env(g, t, len, vol, att, rel) {
    g.gain.value = 0;
    g.gain.setValueAtTime(0, t); g.gain.linearRampToValueAtTime(vol, t + att);
    g.gain.setValueAtTime(vol, Math.max(t + att, t + len - rel)); g.gain.linearRampToValueAtTime(0, t + len);
  }

  // ---- instruments
  function musicBox(m, t, vol, ring) {         // sine + a bright partial two octaves up, plucked and ringing
    ring = ring || 1.6;
    var g = ctx.createGain(), o1 = ctx.createOscillator(), o2 = ctx.createOscillator(), g2 = ctx.createGain();
    o1.type = 'sine'; o2.type = 'sine'; o1.frequency.value = hz(m); o2.frequency.value = hz(m) * 4.01;
    g2.gain.setValueAtTime(0.22, t); g2.gain.exponentialRampToValueAtTime(0.01, t + 0.25);
    g.gain.value = 0; g.gain.setValueAtTime(0, t); g.gain.linearRampToValueAtTime(vol, t + 0.003);
    g.gain.exponentialRampToValueAtTime(0.0006, t + ring);
    o1.connect(g); o2.connect(g2); g2.connect(g); g.connect(master);
    o1.start(t); o2.start(t); o1.stop(t + ring + 0.05); o2.stop(t + ring + 0.05);
  }
  function pad(midis, t, len, vol) {           // detuned triangles through a soft low-pass, slow swell
    var f = ctx.createBiquadFilter(), g = ctx.createGain();
    f.type = 'lowpass'; f.frequency.value = 900; f.Q.value = 0.2;
    env(g, t, len, vol, 0.9, 0.9); f.connect(g); g.connect(master);
    midis.forEach(function (m) { [-7, 7].forEach(function (dt) {
      var o = ctx.createOscillator(); o.type = 'triangle'; o.frequency.value = hz(m); o.detune.value = dt;
      o.connect(f); o.start(t); o.stop(t + len + 0.05);
    }); });
  }
  function bass(m, t, len, vol) {
    var o = ctx.createOscillator(), g = ctx.createGain();
    o.type = 'sine'; o.frequency.value = hz(m);
    g.gain.value = 0; g.gain.setValueAtTime(0, t); g.gain.linearRampToValueAtTime(vol, t + 0.02);
    g.gain.exponentialRampToValueAtTime(vol * 0.35, t + len * 0.6); g.gain.linearRampToValueAtTime(0, t + len);
    o.connect(g); g.connect(master); o.start(t); o.stop(t + len + 0.02);
  }
  function shaker(t, vol) {
    var n = ctx.createBufferSource(), f = ctx.createBiquadFilter(), g = ctx.createGain();
    n.buffer = noise(); f.type = 'highpass'; f.frequency.value = 6500;
    g.gain.value = 0; g.gain.setValueAtTime(0, t); g.gain.linearRampToValueAtTime(vol, t + 0.01); g.gain.exponentialRampToValueAtTime(0.0005, t + 0.09);
    n.connect(f); f.connect(g); g.connect(master); n.start(t, Math.random() * 0.5); n.stop(t + 0.12);
  }

  // ---- the score: 16 bars of 4/4 with a light shuffle (off-beat 16ths pushed late). Chords as midi notes.
  var CH = { Bbmaj7: [58, 62, 65, 69], Gm9: [55, 58, 62, 69], Ebmaj7: [51, 55, 58, 62], F7sus: [53, 58, 60, 63],
             Dm7: [50, 53, 57, 60], Cm7: [48, 51, 55, 58], F7: [53, 57, 60, 63], Gm7: [55, 58, 62, 65], BbD: [50, 53, 58, 62],
             Ebm6: [51, 54, 58, 60] };
  var ROOT = { Bbmaj7: 46, Gm9: 43, Ebmaj7: 39, F7sus: 41, Dm7: 38, Cm7: 36, F7: 41, Gm7: 43, BbD: 38, Ebm6: 39 };
  var PROG = ['Bbmaj7', 'Gm9', 'Ebmaj7', 'F7sus', 'Bbmaj7', 'Dm7', 'Cm7', 'F7',
              'Gm7', 'Ebmaj7', 'BbD', 'Cm7', 'Ebm6', 'F7sus', 'Bbmaj7', 'Bbmaj7'];
  var MEL = [   // [bar, step, midi]
    [0, 0, 74], [0, 6, 77], [0, 8, 79], [0, 12, 77],
    [1, 0, 74], [1, 4, 72], [1, 8, 70],
    [2, 0, 75], [2, 4, 79], [2, 8, 82], [2, 12, 79],
    [3, 0, 77], [3, 10, 72],
    [4, 0, 74], [4, 6, 77], [4, 8, 81], [4, 12, 79],
    [5, 0, 77], [5, 4, 74], [5, 8, 72], [5, 12, 69],
    [6, 0, 70], [6, 4, 75], [6, 8, 79], [6, 12, 77],
    [7, 0, 75], [7, 8, 72], [7, 12, 69],
    [8, 0, 70], [8, 4, 74], [8, 8, 77], [8, 12, 82],
    [9, 0, 79], [9, 6, 77], [9, 8, 75],
    [10, 0, 74], [10, 4, 77], [10, 8, 74], [10, 12, 70],
    [11, 0, 72], [11, 6, 75], [11, 8, 79],
    [12, 0, 78], [12, 8, 75],
    [13, 0, 77], [13, 6, 72], [13, 8, 74], [13, 12, 75],
    [14, 0, 74], [14, 8, 70],
    [15, 0, 70]];
  var TOTAL = 16 * BAR;
  var SWING = 0.28;                      // off-beat 16ths come this fraction of a step late

  function schedule(s, t) {
    var bar = Math.floor(s / BAR) % 16, st = s % BAR, ch = PROG[bar], tones = CH[ch];
    if (st % 2 === 1) t += STEP * SWING;
    if (st === 0) pad(tones, t, STEP * BAR * 1.05, 0.010);
    if (st === 0) bass(ROOT[ch], t, STEP * 7, 0.08);
    if (st === 8) bass(ROOT[ch] + (bar % 2 ? 7 : 12), t, STEP * 6, 0.06);
    // soft comping on the "and" of 2 and 4, an octave up
    if (st === 6 || st === 14) musicBox(tones[st === 6 ? 1 : 2] + 12, t, 0.014, 0.9);
    if ((st === 4 || st === 12) && bar % 8 !== 7) shaker(t, 0.013);
    if ((st === 6 || st === 14) && bar % 4 === 3) shaker(t, 0.008);
    // a few stars: high twinkles at the end of every other bar
    if (st === 15 && bar % 2 === 1) musicBox(tones[3] + 24, t, 0.008, 0.6);
    MEL.forEach(function (n) { if (n[0] === bar && n[1] === st) musicBox(n[2], t, 0.05, bar === 15 ? 3.2 : 1.7); });
  }
  function pump() {
    while (nextT < ctx.currentTime + LOOK) { schedule(step, nextT); step = (step + 1) % TOTAL; nextT += STEP; }
  }
  // master -> gentle low-pass -> dry + soft room reverb
  function bus(c) {
    var input = c.createGain(), lp = c.createBiquadFilter(), dry = c.createGain(), rv = c.createConvolver(), wet = c.createGain();
    var len = Math.round(c.sampleRate * 2.2), ir = c.createBuffer(2, len, c.sampleRate);
    for (var k = 0; k < 2; k++) { var d = ir.getChannelData(k); for (var i = 0; i < len; i++) d[i] = (Math.random() * 2 - 1) * Math.pow(1 - i / len, 3); }
    rv.buffer = ir; lp.type = 'lowpass'; lp.frequency.value = 3200; lp.Q.value = 0.3;
    dry.gain.value = 0.8; wet.gain.value = 0.3;
    input.connect(lp); lp.connect(dry); dry.connect(c.destination); lp.connect(rv); rv.connect(wet); wet.connect(c.destination);
    return input;
  }

  M.play = function () {
    if (M.playing) return;
    try {
      if (!ctx) { ctx = new AC(); master = ctx.createGain(); master.connect(bus(ctx)); }
      if (ctx.state === 'suspended') ctx.resume();
      M.playing = true;
      master.gain.cancelScheduledValues(ctx.currentTime);
      master.gain.setValueAtTime(0, ctx.currentTime); master.gain.linearRampToValueAtTime(LEVEL, ctx.currentTime + 3);
      step = 0; nextT = ctx.currentTime + 0.08;
      pump(); timer = setInterval(pump, 40);
    } catch (e) { M.playing = false; }
  };
  M.stop = function () {
    if (!M.playing) return;
    M.playing = false;
    clearInterval(timer); timer = null;
    try {
      var t = ctx.currentTime;
      master.gain.cancelScheduledValues(t); master.gain.setValueAtTime(master.gain.value, t); master.gain.linearRampToValueAtTime(0, t + 0.4);
      setTimeout(function () { if (!M.playing && ctx) ctx.suspend(); }, 600);
    } catch (e) {}
  };
  M.resume = function () { if (M.playing && ctx && ctx.state === 'suspended') ctx.resume(); };
  /** Renders the song without playing it (tests / preview): resolves to an AudioBuffer. */
  M.render = function (seconds) {
    var Off = root.OfflineAudioContext; if (!Off) return Promise.reject('no OfflineAudioContext');
    var keep = [ctx, master, noiseBuf];
    ctx = new Off(1, Math.round(44100 * seconds), 44100); master = ctx.createGain(); master.gain.value = LEVEL; master.connect(bus(ctx));
    noiseBuf = null;
    for (var s = 0, t = 0.05; t < seconds; s++, t += STEP) schedule(s % TOTAL, t);
    var p = ctx.startRendering();
    ctx = keep[0]; master = keep[1]; noiseBuf = keep[2];
    return p;
  };
  root.Music = M;
})(this);

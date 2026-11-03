/* menu.js - launcher and pause menu for Super Mario Advance Dual. Talks to MainActivity.Host (window.Host).
 * Drawn at GBA-like resolution in the game's own font. Once the first-start capture is ready (gbakit.js) the backdrop
 * is the title screen's sky and ground, the card is the game's pause panel and the cursor its arrow, all from the
 * player's ROM. Until then (or if it failed, or MENU GRAPHICS is set to DRAWN) a look-alike drawn here is used. */
(function () {
  'use strict';
  var G = GbaGfx;
  var H0 = window.Host || {   // browser preview stub
    info: function () { return JSON.stringify({ version: 'dev', mode: 'launcher', rom: { present: false }, auto: false, settings: {}, slots: [], screens: {} }); },
    font: function () { return ''; }, binds: function () { return '[]'; }
  };
  var cv = document.getElementById('c'), ctx = cv.getContext('2d');
  var W = 240, H = 160, info = {}, page = 'main', sel = 0, scroll = 0, rows = [], tick = 0, toastMsg = '', toastT = 0;
  var thumbs = {}, binds = [], waiting = -1, confirm = null, devTime = 30, warpTo = 0;
  var LV = ['1-1', '1-2', '1-3', '2-1', '2-2', '2-3', '3-1', '3-2', '3-3', '4-1', '4-2', '4-3', '5-1', '5-2', '5-3', '6-1', '6-2', '6-3', '7-1', '7-2'];
  var C = { white: '#f8f8f8', cream: '#fbf4dc', parch: '#efe0b8', orange: '#c86410', brown: '#5a3818', dbrown: '#3a2008',
            red: '#e03828', dred: '#902010', green: '#40b030', dgreen: '#187018', blue: '#3070e0', sky: '#88d0f8', sky2: '#d0f0f8',
            grey: '#988870', lgrey: '#b8a888', navy: '#283870', yellow: '#f8c800', pink: '#f0a8c0' };
  // 0.8 look: calm and readable. Dark ink on cream, a soft gold bar for the selection, red titles, values as plain
  // coloured words (no pills), no text shadows.
  var BAR = '#f4d27a', BAR_EDGE = '#b8862e', INK = '#4a2c12', INK_SEL = '#2a1404', TITLE = '#a82a1a';
  var C0 = JSON.parse(JSON.stringify(C)), BAR0 = BAR, K = window.GbaKit || { ok: false }, kitTried = false;
  function useKit() { return K.ok && (info.settings || {}).romMenu !== false; }
  function kitColors() {                // panel colours from the ROM when its pieces are in use
    for (var k in C0) C[k] = C0[k];
    BAR = BAR0;
    if (!useKit()) return;
    var k2 = K.colors; C.cream = k2.cream; C.parch = k2.parch; C.orange = k2.orange; C.dred = k2.dark;
  }

  // launcher music (music.js): only on the launcher, never over the game or the pause menu
  var Mu = window.Music || { play: function () {}, stop: function () {}, resume: function () {} };
  function music() { if (info.mode === 'launcher' && (info.settings || {}).music !== false) Mu.play(); else Mu.stop(); }
  window.musicStop = function () { Mu.stop(); };
  window.musicStart = function () { refreshInfo(); music(); };

  function refreshInfo() {
    try { info = JSON.parse(H0.info()); } catch (e) { info = {}; }
    if (!G.hasFont() && !window.STANDIN) { var f = G.b64(H0.font()); if (f.length === G.FONT_BYTES) G.setFont(f); }
    if (!K.ok && !kitTried && info.kit === 'ready' && H0.kit) { kitTried = true; try { K.load(G.b64(H0.kit())); } catch (e) { K.ok = false; } }
    if (info.kit === 'working') kitTried = false;          // a rebuild: load the new one when it is ready
    kitColors();
  }
  window.refresh = function () { refreshInfo(); build(); draw(); music(); };
  window.show = function (mode) { refreshInfo(); page = 'main'; sel = 0; scroll = 0; confirm = null; thumbs = {}; build(); draw(); music(); };
  window.toast = function (m) { toastMsg = m; toastT = tick + 40; draw(); };
  window.onRebind = function () { waiting = -1; loadBinds(); build(); draw(); };

  function resize() {
    var r = innerWidth / Math.max(1, innerHeight);
    if (r >= 1.5) { H = 160; W = Math.max(240, Math.round(160 * r)); } else { W = 240; H = Math.round(240 / r); }
    cv.width = W; cv.height = H; ctx.imageSmoothingEnabled = false;
    draw();
  }
  addEventListener('resize', resize);

  // ------------------------------------------------------------------ pages
  function set(k, v) { H0.set(k, String(v)); info.settings[k] = v; }
  function cycle(k, list, dir) {
    var i = list.indexOf(String(info.settings[k] == null ? list[0] : info.settings[k])); i = (i + (dir || 1) + list.length) % list.length; set(k, list[i]);
  }
  function onoff(v) { return v ? 'ON' : 'OFF'; }
  function slotTime(t) {
    if (!t) return '- EMPTY -';
    var d = new Date(t), p = function (n) { return n < 10 ? '0' + n : '' + n; };
    return (d.getMonth() + 1) + '/' + d.getDate() + ' ' + p(d.getHours()) + ':' + p(d.getMinutes());
  }

  function build() {
    var s = info.settings || {}, rom = info.rom || {};
    rows = [];
    var opt = function (label, value, fn) { rows.push({ label: label, value: value, fn: fn }); };
    var sub = function (label, p) { rows.push({ label: label, value: '>', fn: function () { go(p); } }); };
    var backRow = function () { rows.push({ label: 'BACK', fn: back }); };
    if (page === 'main') {
      var pause = info.mode === 'pause';
      if (pause) {
        rows.push({ label: 'RESUME', fn: function () { H0.resume(); } });
        rows.push({ label: 'SAVE STATE', fn: function () { go('save'); } });
        rows.push({ label: 'LOAD STATE', fn: function () { go('load'); } });
        if (!info.linkShown) rows.push({ label: 'LINK SCREEN', fn: function () { H0.showLink(true); } });
        rows.push({ label: 'SETTINGS', fn: function () { go('settings'); } });
        rows.push({ label: 'RESET', fn: function () { ask('RESET THE GAME?', function () { H0.reset(); }); } });
        rows.push({ label: 'QUIT TO TITLE', fn: function () { H0.quit(); } });
      } else {
        if (rom.present && info.auto) rows.push({ label: 'CONTINUE', fn: function () { H0.start(true); } });
        if (rom.present) rows.push({ label: info.auto ? 'NEW GAME' : 'START', fn: function () {
          if (info.auto) ask('START OVER? CONTINUE DATA STAYS', function () { H0.start(false); }); else H0.start(false);
        } });
        if (rom.present) rows.push({ label: 'LOAD STATE', fn: function () { go('load'); } });
        else rows.push({ label: 'SELECT ROM', fn: function () { H0.pickRom(); } });
        rows.push({ label: 'SETTINGS', fn: function () { go('settings'); } });
        rows.push({ label: 'EXIT', fn: function () { H0.exit(); } });
      }
    } else if (page === 'save' || page === 'load') {
      (info.slots || []).forEach(function (sl) {
        rows.push({ label: 'SLOT ' + sl.n, value: slotTime(sl.time), slot: sl.n, time: sl.time, fn: function () {
          if (page === 'save') {
            var doSave = function () { var ok = H0.save(sl.n); delete thumbs[sl.n]; refreshInfo(); build(); toast(ok ? 'SAVED TO SLOT ' + sl.n : 'SAVE FAILED'); };
            if (sl.time) ask('OVERWRITE SLOT ' + sl.n + '?', doSave); else doSave();
          } else if (sl.time) { if (!H0.load(sl.n)) toast('LOAD FAILED'); }
        } });
      });
      backRow();
    } else if (page === 'settings') {             // categories
      sub('DISPLAY', 'display');
      sub('SCREENS', 'screens');
      if (!info.no3d) sub('3D VIEW', '3d');
      sub('CONTROLS', 'controls');
      sub('SOUND', 'sound');
      sub('GAMEPLAY', 'gameplay');
      sub('SYSTEM', 'system');
      backRow();
    } else if (page === 'display') {
      opt('SHAPE', { '3:2': '3:2', stretch: 'STRETCH' }[s.aspect] || '3:2', function (d) { cycle('aspect', ['3:2', 'stretch'], d); });
      opt('INTEGER SCALE', onoff(s.integer), function () { set('integer', !s.integer); });
      opt('GBA LCD COLORS', onoff(s.colorfix), function () { set('colorfix', !s.colorfix); });
      opt('FRAME BLEND', onoff(s.blend), function () { set('blend', !s.blend); });
      if (!info.linux) opt('BORDERS', onoff(s.bezels), function () { set('bezels', !s.bezels); });
      opt('SHOW FPS', onoff(s.stats), function () { set('stats', !s.stats); });
      backRow();
    } else if (page === 'screens') {
      if (!info.linux) opt('SPLIT', { auto: 'AUTO', off: 'OFF', lr: 'SIDE BY SIDE', tb: 'TOP + BOTTOM' }[s.split] || 'AUTO', function (d) { cycle('split', ['auto', 'off', 'lr', 'tb'], d); });
      if (!info.linux && (s.split === 'lr' || s.split === 'tb')) opt('HINGE GAP', (s.gap || 0) + 'DP', function (d) { cycle('gap', ['0', '16', '26', '34', '40'], d); });
      opt('SWAP SCREENS', onoff(s.swap), function () { set('swap', !s.swap); });
      if (!info.linux) opt('LINK SIDE PANEL', onoff(s.side), function () { set('side', !s.side); });
      if (!info.linux) opt('SECOND DISPLAY', onoff(s.useExternal), function () { set('useExternal', !s.useExternal); });
      backRow();
    } else if (page === '3d') {
      opt('3D VIEW', onoff(s.dev3d), function () { set('dev3d', !s.dev3d); });
      opt('CAMERA', String(s.cam3d || 'tilt').toUpperCase(), function (d) { cycle('cam3d', ['low', 'tilt', 'far'], d); });
      opt('BLOCK DEPTH', String(s.depth3d || 16), function (d) {
        var v = (s.depth3d || 16) + 4 * (d || 1); if (v > 48) v = 4; if (v < 4) v = 48; set('depth3d', v); });
      opt('BACK WALL', { near: 'NEAR', far: 'DEEP', off: 'OFF' }[s.back3d] || 'NEAR', function (d) { cycle('back3d', ['near', 'far', 'off'], d); });
      opt('BACKGROUND', onoff(s.cave3d), function () { set('cave3d', !s.cave3d); });
      opt('PLAYER LIGHT', onoff(s.light3d), function () { set('light3d', !s.light3d); });
      opt('THICK SPRITES', onoff(s.thick3d !== false), function () { set('thick3d', !(s.thick3d !== false)); });
      if (!info.linux) opt('3D BORDERS', onoff(s.border3d !== false), function () { set('border3d', !(s.border3d !== false)); });
      backRow();
    } else if (page === 'controls') {
      if (!info.linux) opt('TOUCH PAD', String(s.touch || 'auto').toUpperCase(), function (d) { cycle('touch', ['auto', 'on', 'off'], d); });
      if (!info.linux) opt('PAD OPACITY', (s.padOpacity || 70) + '%', function (d) {
        var v = (s.padOpacity || 70) + 10 * (d || 1); if (v > 100) v = 20; if (v < 20) v = 100; set('padOpacity', v); });
      rows.push({ label: 'CONTROLLER BUTTONS', value: '>', fn: function () { loadBinds(); go('buttons'); } });
      backRow();
    } else if (page === 'sound') {
      opt('GAME SOUND', onoff(s.audio), function () { set('audio', !s.audio); });
      if (window.Music && window.AudioContext) opt('MENU MUSIC', onoff(s.music !== false), function () { set('music', !(s.music !== false)); music(); });
      backRow();
    } else if (page === 'gameplay') {
      opt('NO SLOWDOWN', onoff(s.overclock !== false), function () { set('overclock', !(s.overclock !== false)); });
      if (s.devUnlock) sub('PLAYTEST CODES', 'dev');
      backRow();
    } else if (page === 'system') {
      rows.push({ label: rom.present ? 'CHANGE ROM' : 'SELECT ROM', value: '>', fn: function () { H0.pickRom(); } });
      opt('SKIP LAUNCHER', onoff(s.skipLauncher), function () { set('skipLauncher', !s.skipLauncher); });
      if (rom.ok) opt('MENU GRAPHICS', s.romMenu === false ? 'DRAWN' : info.kit === 'ready' ? 'FROM ROM' : info.kit === 'working' ? 'WORKING' : 'FROM ROM*',
        function () { set('romMenu', s.romMenu === false); kitColors(); });
      if (rom.ok && info.mode !== 'pause' && H0.rebuildKit) rows.push({ label: 'REBUILD MENU GRAPHICS', value: info.kit === 'working' ? '...' : 'GO', fn: function () {
        if (info.kit === 'working') return; K.ok = false; kitTried = false; H0.rebuildKit(); toast('CAPTURING FROM THE ROM'); } });
      rows.push({ head: 'SMA DUAL ' + (info.version || ''), note: true });
      rows.push({ head: 'EMULATION: MGBA (MPL 2.0)', note: true });
      rows.push({ head: 'MPL TEXT: ASSETS/LICENSES', note: true });
      backRow();
    } else if (page === 'dev') {
      rows.push({ head: 'CHEATS FOR TESTING', note: true });
      opt('INFINITE LIFE', onoff(s.codeHealth), function () { set('codeHealth', !s.codeHealth); }); rows[rows.length - 1].icon = 'heartFull';
      opt('INFINITE LIVES', onoff(s.codeLives), function () { set('codeLives', !s.codeLives); }); rows[rows.length - 1].icon = 'oneup';
      var act = function (label, what, ic) { rows.push({ label: label, value: 'GO', icon: ic, fn: function () { toast(H0.dev(what)); } }); };
      act('REFILL LIFE', 'refill', 'heart');
      act('STARMAN', 'star', 'star');
      act('STOPWATCH', 'stopwatch', 'watch');
      act('4 CHERRIES', 'cherries', 'cherry');
      act('+5 LIVES', 'lives', 'oneup');
      opt('WARP LEVEL', LV[warpTo], function (d) { warpTo = (warpTo + (d < 0 ? 19 : 1)) % 20; });
      rows.push({ label: 'WARP', value: 'GO', fn: function () { toast(H0.dev('warp:' + warpTo)); } });
      opt('SET PLAY TIME', (devTime / 60 | 0) + ':' + ('0' + devTime % 60).slice(-2), function (d) {
        var l = [30, 60, 90, 120, 180, 600], i = l.indexOf(devTime); devTime = l[(i + (d < 0 ? l.length - 1 : 1)) % l.length];
        toast(H0.dev('time:' + devTime)); });
      backRow();
    } else if (page === 'buttons') {
      binds.forEach(function (b, i) {
        rows.push({ label: b.name, value: waiting === i ? 'PRESS A BUTTON' : (b.keys.length ? b.keys.join(' ') : '-'), fn: function () {
          waiting = i; H0.rebind(i); build();
        } });
      });
      rows.push({ label: 'RESET TO DEFAULT', fn: function () { H0.resetBinds(); loadBinds(); build(); toast('DEFAULTS RESTORED'); } });
      rows.push({ label: 'BACK', fn: back });
    }
    var first = firstSelectable(); if (sel < first || sel >= rows.length || rows[sel].head) sel = first;
  }
  function loadBinds() { try { binds = JSON.parse(H0.binds()); } catch (e) { binds = []; } }
  function firstSelectable() { for (var i = 0; i < rows.length; i++) if (!rows[i].head) return i; return 0; }
  var stack = [];
  function go(p) { stack.push([page, sel]); page = p; sel = 0; scroll = 0; refreshInfo(); build(); }
  function back() {
    if (waiting >= 0) { waiting = -1; H0.cancelRebind(); build(); return; }
    if (confirm) { confirm = null; return; }
    if (!stack.length) { if (info.mode === 'pause') H0.resume(); return; }
    var s = stack.pop(); page = s[0]; sel = s[1]; refreshInfo(); build();
  }
  function ask(q, fn) { confirm = { q: q, fn: fn, sel: 1 }; }

  // ------------------------------------------------------------------ input
  // Konami code on the launcher's main screen (up up down down left right left right B A) shows or hides the playtest
  // codes. Hidden, they are also switched off.
  var KONAMI = ['up', 'up', 'down', 'down', 'left', 'right', 'left', 'right', 'back', 'ok'], kon = 0;
  function konami(k) {
    if (page !== 'main' || info.mode === 'pause' || confirm) { kon = 0; return false; }
    if (k === KONAMI[kon]) kon++; else kon = k === 'up' ? (kon === 2 ? 2 : 1) : 0;
    if (kon < KONAMI.length) return false;
    kon = 0;
    var on = !(info.settings || {}).devUnlock;
    set('devUnlock', on);
    if (!on) { set('codeHealth', false); set('codeLives', false); }
    toast(on ? 'PLAYTEST CODES UNLOCKED' : 'PLAYTEST CODES HIDDEN');
    return true;
  }
  window.pad = function (k) {
    Mu.resume();
    if (konami(k)) { refreshInfo(); build(); draw(); return; }      // the final A doesn't also pick a menu item
    if (confirm) {
      if (k === 'left' || k === 'right' || k === 'up' || k === 'down') confirm.sel ^= 1;
      else if (k === 'ok') { var c = confirm; confirm = null; if (c.sel === 0) c.fn(); }
      else if (k === 'back') confirm = null;
      draw(); return;
    }
    if (waiting >= 0) { if (k === 'back') back(); draw(); return; }
    if ((page === 'save' || page === 'load') && (k === 'up' || k === 'down' || k === 'left' || k === 'right')) {
      var n = rows.length - 1;   // slots, then BACK
      if (sel >= n) { if (k === 'up') sel = n - 1; else if (k === 'down') sel = 0; }
      else if (k === 'left' || k === 'right') sel = (sel & ~1) | ((sel & 1) ^ 1);
      else if (k === 'down') sel = sel + 2 >= n ? n : sel + 2;
      else sel = sel - 2 < 0 ? n : sel - 2;
    } else if (k === 'up' || k === 'down') {
      var d = k === 'up' ? -1 : 1, i = sel;
      do { i = (i + d + rows.length) % rows.length; } while (rows[i].head && i !== sel);
      sel = i;
    } else if (k === 'ok') { var r = rows[sel]; if (r && r.fn) r.fn(1); }
    else if (k === 'left' || k === 'right') { var r2 = rows[sel]; if (r2 && r2.value && r2.value !== '>' && r2.value !== 'GO' && r2.fn && page !== 'main') r2.fn(k === 'left' ? -1 : 1); }
    else if (k === 'back') back();
    refreshInfo(); build(); draw();
  };

  var hit = [];
  cv.addEventListener('pointerdown', function (e) {
    Mu.resume();
    var x = e.clientX * W / innerWidth, y = e.clientY * H / innerHeight;
    for (var i = hit.length - 1; i >= 0; i--) {
      var b = hit[i];
      if (x >= b.x && x < b.x + b.w && y >= b.y && y < b.y + b.h) { b.fn(x - b.x < b.w / 3 ? -1 : 1); refreshInfo(); build(); draw(); return; }
    }
  });
  var startY = null;
  cv.addEventListener('touchstart', function (e) { startY = e.touches[0].clientY; }, { passive: true });
  cv.addEventListener('touchmove', function (e) {
    if (startY == null) return;
    var dy = (e.touches[0].clientY - startY) * H / innerHeight;
    if (Math.abs(dy) > 12) { scroll = Math.max(0, scroll - Math.sign(dy)); startY = e.touches[0].clientY; draw(); }
  }, { passive: true });

  // ------------------------------------------------------------------ drawing
  // Sky with drifting clouds and wavy hills (drawn here; nothing from the game but its font).
  var clouds = [];
  for (var s = 0; s < 7; s++) clouds.push([Math.random(), 0.06 + Math.random() * 0.55, 0.6 + Math.random() * 0.8, 0.02 + Math.random() * 0.03]);
  function cloud(x, y, k) {
    var r = Math.round(7 * k);
    ctx.fillStyle = '#f0b0c8';
    [[0, 3], [r, 0], [2 * r, 3]].forEach(function (p) { ctx.beginPath(); ctx.arc(x + p[0], y + p[1] + 2, r, 0, 7); ctx.fill(); });
    ctx.fillStyle = C.white;
    [[0, 3], [r, 0], [2 * r, 3]].forEach(function (p) { ctx.beginPath(); ctx.arc(x + p[0], y + p[1], r, 0, 7); ctx.fill(); });
  }
  function sky() {
    if (useKit()) { K.backdrop(ctx, W, H); return; }
    var g = ctx.createLinearGradient(0, 0, 0, H); g.addColorStop(0, C.sky); g.addColorStop(1, C.sky2);
    ctx.fillStyle = g; ctx.fillRect(0, 0, W, H);
    clouds.forEach(function (c) { var x = ((c[0] * (W + 80) + tick * c[3] * 6) % (W + 80)) - 40; cloud(Math.round(x), Math.round(c[1] * H), c[2]); });
    var hy = H - 18;                                  // wavy green hills
    for (var x = 0; x < W; x++) {
      var w1 = Math.round(Math.sin((x + tick * 0.5) / 6) * 2), top = hy + w1;
      ctx.fillStyle = C.dgreen; ctx.fillRect(x, top, 1, H - top);
      ctx.fillStyle = C.green; ctx.fillRect(x, top + 1, 1, H - top - 1);
      for (var yy = top + 5; yy < H; yy += 5) if (((x + yy + (tick >> 1)) >> 2) % 3 === 0) { ctx.fillStyle = C.dgreen; ctx.fillRect(x, yy, 1, 1); }
    }
  }
  function card(x, y, w, h) {
    if (useKit()) {                     // the pause panel, grown to whole 16px pieces around the same centre
      var kw = Math.round((w + 8) / 16) * 16, kh = Math.round((h + 4) / 16) * 16;
      K.card(ctx, Math.round(x + w / 2 - kw / 2), Math.round(y + h / 2 - kh / 2), kw, kh); return;
    }
    ctx.fillStyle = C.brown; ctx.fillRect(x - 2, y - 2, w + 4, h + 4);
    ctx.fillStyle = C.orange; ctx.fillRect(x - 1, y - 1, w + 2, h + 2);
    ctx.fillStyle = C.cream; ctx.fillRect(x, y, w, h);
    ctx.fillStyle = C.parch; ctx.fillRect(x, y + h - 2, w, 2); ctx.fillRect(x + w - 2, y, 2, h);
    [[x - 2, y - 2], [x + w - 1, y - 2], [x - 2, y + h - 1], [x + w - 1, y + h - 1]].forEach(function (p) { ctx.fillStyle = C.yellow; ctx.fillRect(p[0], p[1], 3, 3); });
  }

  function cursor(x, y) {               // the game's arrow when we have it, else the font's '>'
    if (useKit()) ctx.drawImage(K.cursor, x - 1, y); else G.text(ctx, '>', x, y, C.orange);
  }
  function shadowC(t, x, y, c, sc) { G.textC(ctx, t, x + 1, y + 1, '#282828', sc); G.textC(ctx, t, x, y, c, sc); }
  // 0.11: words over the backdrop (ROM line, version, screen mode) sit on a dark plate; a shadow alone got lost in the
  // water and hills
  function plate(x, y, w) { ctx.fillStyle = 'rgba(30,18,6,0.62)'; ctx.fillRect(x + 1, y - 2, w - 2, 12); ctx.fillRect(x, y - 1, w, 10); }
  // rounded box (1 px corners cut), optional darker outline
  function rbox(x, y, w, h, col, edge) {
    if (edge) { ctx.fillStyle = edge; ctx.fillRect(x + 1, y - 1, w - 2, h + 2); ctx.fillRect(x - 1, y + 1, w + 2, h - 2); ctx.fillRect(x, y, w, h); }
    ctx.fillStyle = col; ctx.fillRect(x + 1, y, w - 2, h); ctx.fillRect(x, y + 1, w, h - 2);
  }
  // value shown on the right of a row: ON / OFF as pills, sub-pages as an arrow, the rest as text
  function value(v, rx, y, waitingNow, sc) {
    sc = sc || 1;
    if (waitingNow) { G.textR(ctx, v, rx, y, (tick >> 3) % 2 ? C.orange : C.dred, sc); return; }
    if (v === 'ON') { G.textR(ctx, v, rx, y, '#24742a', sc); return; }
    if (v === 'OFF') { G.textR(ctx, v, rx, y, '#76664e', sc); return; }
    if (v === '>') { G.textR(ctx, '>', rx, y, '#8a6a40'); return; }
    G.textR(ctx, v, rx, y, '#1d4f9a', sc);
  }
  var CAT = { DISPLAY: '#5a82c0', SCREENS: '#5a9a5a', '3D VIEW': '#8a6ab0', CONTROLS: '#c06050', SOUND: '#c8a040', GAMEPLAY: '#c88040', SYSTEM: '#8a8a90' };

  function draw() {
    hit = [];
    var launcher = page === 'main' && info.mode !== 'pause';
    sky();
    var cx = W / 2, y = 8;
    if (launcher) {
      G.textC(ctx, 'SUPER MARIO', cx + 1, y + 1, C.dbrown, 2);
      G.textC(ctx, 'SUPER MARIO', cx, y, C.red, 2);
      G.textC(ctx, 'ADVANCE', cx + 1, y + 19, C.dbrown, 2);
      G.textC(ctx, 'ADVANCE', cx, y + 18, C.yellow, 2);
      G.textC(ctx, 'DUAL', cx, y + 35, C.blue);
      if (useKit()) { ctx.drawImage(K.coin, Math.round(cx - 40), y + 31); ctx.drawImage(K.coin, Math.round(cx + 24), y + 31); }
      y += 46;
    }
    // the card holding the rows: big text on the main pages when it fits; the pause card only as tall as it needs
    var big = page === 'main' && (launcher ? H - y - 40 >= rows.length * 18 : H - 40 >= rows.length * 18) && W >= 240;
    var cardW = Math.min(W - 16, launcher ? (big ? 200 : 150) : 228), cardX = Math.round(cx - cardW / 2);
    var cardY = launcher ? y : 6, cardH = H - cardY - (launcher ? 34 : 8);
    var subRow = H >= 200 ? 15 : page === 'settings' ? 12 : 11;
    if (!launcher && page !== 'save' && page !== 'load') {
      var need = 30 + rows.length * (page === 'main' ? (big ? 18 : 13) : subRow) + 10;
      if (need < cardH) { cardY = Math.round((H - need) / 2); cardH = need; }
    }
    card(cardX, cardY, cardW, cardH);
    y = cardY + (launcher ? 3 : 6);
    if (launcher) {
      var rom = info.rom || {};
      var romLine = !rom.present ? 'NO ROM SELECTED' : rom.ok ? 'SMA (USA, EUROPE) OK' : 'UNKNOWN ROM ' + (rom.crc || '').toUpperCase();
      if (info.kit === 'working') romLine = 'PREPARING MENU GRAPHICS' + '...'.substr(0, (tick >> 3) % 4);
      if (useKit()) { plate(Math.round(cx - romLine.length * 4 - 4), H - 31, romLine.length * 8 + 8); G.textC(ctx, romLine, cx, H - 31, !rom.present || !rom.ok ? C0.pink : C.white); }
      else G.textC(ctx, romLine, cx, H - 31, !rom.present ? C.red : rom.ok ? C.navy : C.dred);
    } else {
      var title = { main: 'PAUSE', save: 'SAVE STATE', load: 'LOAD STATE', settings: 'SETTINGS', display: 'DISPLAY', screens: 'SCREENS',
                    '3d': '3D VIEW', controls: 'CONTROLS', buttons: 'CONTROLLER', sound: 'SOUND', gameplay: 'GAMEPLAY', system: 'SYSTEM',
                    dev: 'PLAYTEST CODES' }[page] || '';
      var tsc = title.length * 16 <= cardW - 8 ? 2 : 1;
      y = cardY + 6;
      G.textC(ctx, title, cx, y + (tsc === 1 ? 4 : 0), TITLE, tsc);
      ctx.fillStyle = '#d8b878'; ctx.fillRect(cardX + 10, y + 19, cardW - 20, 1);
      y += 24;
    }
    if (page === 'save' || page === 'load') { slotGrid(y); finish(); return; }
    // rows with an item icon (captured at first start) get room for it: 16x16 at 1:1
    var icons = useKit() && K.icons && rows.some(function (r) { return r.icon && K.icons[r.icon]; });
    var centered = page === 'main', rowH = big ? 18 : launcher ? 12 : centered ? 13 : icons ? 18 : subRow, ts = big ? 2 : 1;
    var bottom = cardY + cardH - 4;
    var visible = Math.max(1, Math.floor((bottom - y) / rowH));
    if (centered || page === 'settings') y = Math.max(y, Math.round(y + (bottom - y - rows.length * rowH) / 2));
    if (sel < scroll) scroll = sel;
    if (sel >= scroll + visible) scroll = sel - visible + 1;
    scroll = Math.max(0, Math.min(scroll, Math.max(0, rows.length - visible)));
    var listW = cardW - 12, x0 = cardX + 6;
    for (var i = scroll; i < rows.length && i < scroll + visible; i++) {
      var r = rows[i], yy = y + (i - scroll) * rowH, on = i === sel;
      if (r.note) { G.textC(ctx, r.head, cx, yy + 2, '#75644a'); continue; }
      if (r.head) { G.text(ctx, r.head, x0, yy + 2, '#1d4f9a'); continue; }
      if (centered) {
        var ty = yy + (big ? 1 : 2);
        if (on) { rbox(x0, yy - 1, listW, rowH - 2, BAR, BAR_EDGE); }
        G.textC(ctx, r.label, cx, ty, on ? INK_SEL : INK, ts);
        if (on) cursor(x0 + 3, yy + Math.floor((rowH - 10) / 2));
      } else {
        var ry = yy + Math.floor((rowH - 9) / 2);
        if (on) rbox(x0 - 2, yy, listW + 4, rowH - 1, BAR, BAR_EDGE);
        var chip = page === 'settings' && CAT[r.label], ix = icons && r.icon && K.icon(ctx, r.icon, x0 + 8, yy + 1);
        if (chip) { rbox(x0 + 8, ry, 7, 7, chip, G.shade(chip, 0.7)); }
        G.text(ctx, r.label, x0 + (ix ? 27 : icons ? 27 : chip ? 19 : 8), ry, on ? INK_SEL : INK);
        if (on) cursor(x0, ry);
        if (r.value) {
          var v = r.value, maxc = Math.floor((listW - r.label.length * 8 - (icons ? 35 : 16)) / 8);
          if (v.length > maxc) v = v.substr(0, Math.max(1, maxc));
          value(v, x0 + listW, ry, waiting === i);
        }
      }
      (function (idx, rr) { hit.push({ x: x0 - 4, y: yy, w: listW + 8, h: rowH, fn: function (d) { sel = idx; if (rr.fn) rr.fn(d); } }); })(i, r);
    }
    if (scroll > 0) G.textC(ctx, '^', cx, y - 9, '#75644a');
    if (scroll + visible < rows.length) G.textC(ctx, 'v', cx, bottom - 6, '#75644a');
    finish();
  }
  function finish() {
    var cx = W / 2;
    if (page === 'main' && info.mode !== 'pause') {
      var sc = info.screens || {};
      var ver = 'V' + (info.version || '');
      plate(1, H - 10, ver.length * 8 + 6); G.text(ctx, ver, 4, H - 10, C.white);
      var how = { hinge: 'DUAL SCREEN', split: 'SPLIT', external: '2ND DISPLAY', side: 'SIDE PANEL', single: 'ONE SCREEN' }[sc.how] || '';
      if (how) { plate(W - how.length * 8 - 7, H - 10, how.length * 8 + 6); G.textR(ctx, how, W - 4, H - 10, C.white); }
    }
    if (confirm) drawConfirm();
    if (toastT > tick) {
      var tw = toastMsg.length * 8 + 12;
      ctx.fillStyle = C.navy; ctx.fillRect(cx - tw / 2, H - 30, tw, 14);
      G.textC(ctx, toastMsg, cx, H - 27, C.white);
    }
  }

  // SAVE / LOAD: 2x2 cards with the slot's screenshot, then BACK. Every card is one big tap target.
  function slotGrid(y) {
    var n = rows.length - 1, gap = 4, backH = 14;
    var gw = Math.min(W - 24, 300), cw = Math.floor((gw - gap) / 2);
    var ch = Math.floor((H - y - backH - 14 - gap) / 2);
    var th = Math.min(ch - 13, Math.round((cw - 6) * 2 / 3)), tw = Math.round(th * 3 / 2);
    ch = th + 13;
    var x0 = Math.round((W - (cw * 2 + gap)) / 2);
    for (var i = 0; i < n; i++) {
      var r = rows[i], on = i === sel, x = x0 + (i % 2) * (cw + gap), yy = y + Math.floor(i / 2) * (ch + gap);
      ctx.fillStyle = on ? BAR : C.parch; ctx.fillRect(x, yy, cw, ch);
      ctx.fillStyle = on ? C.orange : C.brown;
      ctx.fillRect(x, yy, cw, 1); ctx.fillRect(x, yy + ch - 1, cw, 1); ctx.fillRect(x, yy, 1, ch); ctx.fillRect(x + cw - 1, yy, 1, ch);
      if (on) { ctx.fillRect(x + 1, yy + 1, cw - 2, 1); ctx.fillRect(x + 1, yy + ch - 2, cw - 2, 1); ctx.fillRect(x + 1, yy, 1, ch); ctx.fillRect(x + cw - 2, yy, 1, ch); }
      var tx = x + Math.round((cw - tw) / 2), ty = yy + 3;
      var im = r.time ? thumbs[r.slot] : null;
      if (r.time && im === undefined) {
        thumbs[r.slot] = null;
        var url = H0.thumb ? H0.thumb(r.slot) : '';
        if (url) (function (k) { var img = new Image(); img.onload = function () { thumbs[k] = img; draw(); }; img.src = url; })(r.slot);
      }
      if (im) ctx.drawImage(im, tx, ty, tw, th);
      else { ctx.fillStyle = C.sky; ctx.fillRect(tx, ty, tw, th); G.textC(ctx, r.time ? '...' : 'EMPTY', x + cw / 2, ty + th / 2 - 4, C.white); }
      G.text(ctx, String(r.slot), x + 4, yy + ch - 10, on ? INK_SEL : INK);
      if (r.time) G.textR(ctx, r.value, x + cw - 4, yy + ch - 10, on ? INK_SEL : '#1d4f9a');
      (function (idx, rr) { hit.push({ x: x, y: yy, w: cw, h: ch, fn: function () { sel = idx; if (rr.fn) rr.fn(1); } }); })(i, r);
    }
    var by = y + 2 * (ch + gap) + 1, bw = 80, bx = Math.round((W - bw) / 2), bon = sel === n;
    ctx.fillStyle = bon ? BAR : C.parch; ctx.fillRect(bx, by, bw, backH);
    ctx.fillStyle = bon ? C.orange : C.brown; ctx.fillRect(bx, by, bw, 1); ctx.fillRect(bx, by + backH - 1, bw, 1);
    G.textC(ctx, 'BACK', W / 2, by + 4, bon ? INK_SEL : INK);
    hit.push({ x: bx, y: by, w: bw, h: backH, fn: function () { sel = n; back(); } });
  }

  function drawConfirm() {
    hit.push({ x: 0, y: 0, w: W, h: H, fn: function () {} });
    var w = Math.min(W - 16, Math.max(confirm.q.length * 8 + 16, 120)), h = 44, x = Math.round((W - w) / 2), y = Math.round((H - h) / 2);
    card(x, y, w, h);
    var q = confirm.q; if (q.length * 8 > w - 8) q = q.substr(0, Math.floor((w - 8) / 8));
    G.textC(ctx, q, W / 2, y + 8, C.dred);
    var yes = confirm.sel === 0;
    G.text(ctx, (yes ? '>' : ' ') + 'YES', x + w / 2 - 52, y + 26, yes ? C.orange : C.brown);
    G.text(ctx, (!yes ? '>' : ' ') + 'NO', x + w / 2 + 12, y + 26, !yes ? C.orange : C.brown);
    hit.push({ x: x, y: y + 20, w: w / 2, h: 20, fn: function () { var c = confirm; confirm = null; c.fn(); } });
    hit.push({ x: x + w / 2, y: y + 20, w: w / 2, h: 20, fn: function () { confirm = null; } });
  }

  refreshInfo(); build(); resize(); music();
  setInterval(function () { tick++; if (page === 'main' && info.mode !== 'pause' || tick % 4 === 0 || toastT >= tick) draw(); }, 50);
})();

/* link.js - Super Mario Advance Dual's Link screen: status, a live map of the area drawn in the game's own tiles
 * (X-ray grass, cherries, doors, mushroom spots, you) at four zoom levels (tap) that follow the player, a level
 * chart with Ace Coins, the Mario Bros. Classic scoreboard and options with save slots. Reads memory through
 * window.Native (MainActivity.LinkBridge), draws at low resolution and lets CSS scale it up with nearest-neighbour.
 * Graphics come from the player's ROM and live VRAM; item icons (potion, mushroom, hearts, ...) from the first-start
 * capture (menu/gbakit.js), with the old drawn look whenever that isn't there. */
(function () {
  'use strict';
  var G = GbaGfx, S = SMA;
  var DUAL = !!(window.Native && Native.dual);
  var cv = document.getElementById('c'), ctx = cv.getContext('2d');
  var W = 240, H = 176, layout = {}, tabs = [], tab = 'map', buttons = [];
  var romOk = false, meta = null, ram = null, lvl = null, video = null, trk = null, tickN = 0;
  var areaCv = null, areaKey = '';
  var lastLvl = -99, lastVid = -99, lastTrk = -99, lastPos = '', still = 0;
  var maxHearts = 2, heartsLevel = -1;
  var store = window.localStorage || { getItem: function () { return null; }, setItem: function () {} };
  var opts = load('sma.opts', { xray: true, markers: true, zoom: 'near' });
  // 0.14: map zoom = one of four levels, tapped through (WIDE, FIT, NEAR, 1:1); FIT shows the area's whole height.
  // (0.13's screen styles are gone; the cream cards are the one look.)
  if (!(opts.zl >= 0 && opts.zl <= 3)) opts.zl = 1;
  var C = { white: '#f8f8f8', cream: '#fbf4dc', parch: '#efe0b8', orange: '#c86410', brown: '#5a3818', dbrown: '#3a2008',
            red: '#e03828', dred: '#902010', green: '#40b030', dgreen: '#187018', blue: '#3070e0', lblue: '#88c8f8',
            grey: '#988870', navy: '#182850', night: '#0c1430', yellow: '#f8c800', pink: '#f0a8c0', black: '#000' };

  // Text on the cream cards: every colour at least 4.5:1 against cream (0.10 used the bright map colours for words too:
  // green 2.6, grey 3.1, Peach's pink 2.3).
  var T = { red: '#b42a18', blue: '#2458b0', green: '#1f7424', muted: '#6e5c42', gold: '#8a5c00' };
  var CHAR_TEXT = ['#b82818', '#b83a76', '#2458c0', '#1f7a28'];
  function load(k, d) { try { var v = JSON.parse(store.getItem(k)); return v == null ? d : v; } catch (e) { return d; } }
  function save(k, v) { try { store.setItem(k, JSON.stringify(v)); } catch (e) {} }
  function pad(n, w) { n = String(n); while (n.length < w) n = '0' + n; return n; }
  function hms(sec) { sec = Math.max(0, Math.floor(sec)); return Math.floor(sec / 3600) + ':' + pad(Math.floor(sec / 60) % 60, 2) + ':' + pad(sec % 60, 2); }

  // ------------------------------------------------------------------ ROM: font + metatile tables
  function u32(b, o) { return (b[o] | b[o + 1] << 8 | b[o + 2] << 16 | b[o + 3] << 24) >>> 0; }
  function loadRom() {
    if (!window.Native) return;
    var font = G.b64(Native.rom(S.FONT, G.FONT_BYTES));
    if (font.length === G.FONT_BYTES && !window.STANDIN) G.setFont(font);
    var ptrs = G.b64(Native.rom(S.ROM_METATILE_PTRS, 12));
    if (ptrs.length !== 12) return;
    meta = [];
    for (var g = 0; g < 3; g++) {
      var off = u32(ptrs, g * 4) & 0x1FFFFFF, n = S.METATILE_COUNTS[g];
      var t = G.b64(Native.rom(off, n * 8));
      if (t.length !== n * 8) { meta = null; return; }
      meta.push(t);
    }
    classTable = G.b64(Native.rom(S.CLASS_TABLE, 0x80));
    var tb = G.b64(Native.rom(S.OBJ_TABLE, 20 * 11 * 4));
    objTable = tb.length === 20 * 11 * 4 ? tb : null; objLists = {};
    var nm = G.b64(Native.rom(S.YOSHI_NO_MUSH, 60)); S.noMush = nm.length === 60 ? nm : null;
    romOk = true; areaKey = '';
  }
  // the current area's object list from the ROM (cached per level/area)
  var objTable = null, objLists = {}, classTable = null;
  function objList() {
    if (!objTable || !ram) return [];
    var lv = S.level(ram), an = S.areaNo(ram);
    if (lv > 19 || an > 10) return [];
    var k = lv * 11 + an;
    if (objLists[k]) return objLists[k];
    var p = u32(objTable, k * 4) & 0x1FFFFFF, list = [];
    if (p) list = S.parseObjList(G.b64(Native.rom(p, 1200)));
    return (objLists[k] = list);
  }
  // Ace Coins placed in this area: [{x, y, bit, got}]
  function aceHere() {
    var lv = S.level(ram); if (lv > 19) return [];
    var have = S.aceSaved(ram, lv) | S.aceLevel(ram);
    return objList().filter(function (o) { return o.type >= S.ACE_FIRST && o.type < S.ACE_FIRST + 5; })
      .map(function (o) { var b = o.type - S.ACE_FIRST; return { x: o.x * 16 + 8, y: o.y * 16 + 8, bit: b, got: (have >> b) & 1 }; });
  }
  function quad(id) {      // 4 BG map entries of a metatile
    var g = id >> 8, i = id & 255;
    if (!meta || g > 2 || i >= S.METATILE_COUNTS[g]) return null;
    var t = meta[g], o = i * 8;
    return [t[o] | t[o + 1] << 8, t[o + 2] | t[o + 3] << 8, t[o + 4] | t[o + 5] << 8, t[o + 6] | t[o + 7] << 8];
  }
  window.onRom = function () { romOk = false; meta = null; loadRom(); draw(); };
  window.onSession = function () { areaKey = ''; };
  window.onLayout = function () { resize(); };
  window.onDev = function () { draw(); };

  // ------------------------------------------------------------------ layout
  function resize() {
    var r = innerWidth / Math.max(1, innerHeight);
    if (r >= 1) { H = 176; W = Math.max(240, Math.round(176 * r)); } else { W = 240; H = Math.round(240 / r); }
    cv.width = W; cv.height = H;
    ctx.imageSmoothingEnabled = false;
    var tabH = 13;
    layout = { tabY: H - tabH };
    if (W >= 300) {                       // landscape: status column on the left
      layout.a = { x: 3, y: 3 }; layout.b = { x: 3, y: 3 + A_H + 3 };
      layout.pane = { x: 134, y: 2, w: W - 136, h: H - tabH - 4 };
      tabs = ['map', 'chart', 'options'];
    } else if (H >= 300) {                // portrait: status across the top
      layout.a = { x: 2, y: 3 }; layout.b = { x: 121, y: 3 };
      layout.pane = { x: 1, y: A_H + 6, w: W - 2, h: H - tabH - A_H - 8 };
      tabs = ['map', 'chart', 'options'];
    } else {                              // small: everything behind tabs
      layout.a = { x: 2, y: 4 }; layout.b = { x: 121, y: 4 };
      layout.pane = { x: 1, y: 2, w: W - 2, h: H - tabH - 4 };
      tabs = ['status', 'map', 'chart', 'options'];
      layout.small = true;
    }
    if (tabs.indexOf(tab) < 0) tab = tabs[0];
    draw();
  }
  addEventListener('resize', resize);
  var A_H = 89, B_H = 63;                 // status cards: rows sized for the 16x16 item icons

  // ------------------------------------------------------------------ polling
  function poll() {
    tickN++;
    if (!romOk && window.Native && tickN % 15 === 1) loadRom();
    var hex = romOk && window.Native && Native.running() ? Native.mem(S.spec) : '';
    ram = hex ? S.parse(hex) : null;
    if (ram && S.inLevel(ram)) {
      var lv = S.level(ram), h = S.health(ram), pk = S.px(ram) + ',' + S.py(ram) + ',' + S.character(ram);
      still = pk === lastPos ? still + 1 : 0; lastPos = pk;
      maxHearts = S.maxHearts(ram);
      if (tickN - lastLvl >= 5) { lastLvl = tickN; var lh = Native.mem('2000000:4800'); if (lh.length === 9600) lvl = S.hexBytes(lh); }
      if (tickN - lastVid >= 10 && S.playing(ram)) {
        lastVid = tickN;
        var v = Native.video ? Native.video() : '';
        if (v) { var nv = G.b64(v); if (nv.length >= 0x18860) video = nv; }
        grabPortrait(); grabLive();
      }
    }
    if (ram && tickN - lastTrk >= 5 && Native.tracker) {
      lastTrk = tickN;
      var t = G.b64(Native.tracker());
      if (t.length >= 28) trk = { time: u32(t, 0), deaths: u32(t, 4), cleared: u32(t, 8), whoLo: u32(t, 12), whoHi: u32(t, 16), warped: u32(t, 20) };
    }
    draw();
  }
  function cleared(L) { return trk && ((trk.cleared >>> L) & 1); }
  function clearedBy(L) { var b = L * 2, w = b < 32 ? trk.whoLo : trk.whoHi; return (w >>> (b & 31)) & 3; }
  function popcount(v) { var n = 0; while (v) { n += v & 1; v >>>= 1; } return n; }

  // ------------------------------------------------------------------ video helpers (live VRAM / palette / OAM / IO)
  var V_PAL = 0x18000, V_OAM = 0x18400, V_IO = 0x18800;
  function vu16(o) { return video[o] | video[o + 1] << 8; }
  function palCss(i) { return G.bgr(vu16(V_PAL + i * 2)); }
  /** Map background: the backdrop colour, or a light sky when the game uses white there (its sky is a BG layer). */
  // The sky as seen (0.18). Palette colour 0 can sit behind a light far layer (7-1: black colour 0 under a pale sky), so
  // when colour 0 is dark, take the far layers' (BG1/BG2) most common colour along the top of the screen if they cover
  // most of it, as the 3D view does. Cached per video update.
  var skyMemo = { v: null, rgb: null };
  function skyRgb() {
    var c = palRgb(0);
    if (c[0] + c[1] + c[2] > 690) return [184, 228, 248];
    if (c[0] + c[1] + c[2] >= 150 || !video) return c;
    if (skyMemo.v !== video) { skyMemo.v = video; skyMemo.rgb = farSky(); }
    return skyMemo.rgb || c;
  }
  function farSky() {
    var disp = vu16(V_IO), hist = {}, top = null, topN = 0, opaque = 0, n = 0;
    var bgs = [1, 2].filter(function (b) { return disp & (0x100 << b); }).map(function (b) {
      var cnt = vu16(V_IO + 8 + b * 2);
      return { b: b, pri: cnt & 3, cb: (cnt >> 2) & 3, sb: (cnt >> 8) & 31, w: cnt & 0x4000 ? 512 : 256, h: cnt & 0x8000 ? 512 : 256,
               hx: vu16(V_IO + 0x10 + b * 4) & 511, vy: vu16(V_IO + 0x12 + b * 4) & 511 };
    }).sort(function (a, c) { return a.pri - c.pri || a.b - c.b; });
    for (var y = 2; y < 34; y += 4) for (var x = 4; x < 240; x += 8) {
      n++;
      for (var k = 0; k < bgs.length; k++) {
        var g = bgs[k], wx = (x + g.hx) % g.w, wy = (y + g.vy) % g.h, tx = wx >> 3, ty = wy >> 3, blk = g.sb;
        if (tx >= 32) { blk += 1; tx -= 32; }
        if (ty >= 32) { blk += g.w === 512 ? 2 : 1; ty -= 32; }
        var e = vu16(blk * 0x800 + (ty * 32 + tx) * 2), px = wx & 7, py = wy & 7;
        if (e & 0x400) px = 7 - px;
        if (e & 0x800) py = 7 - py;
        var bo = g.cb * 0x4000 + (e & 0x3FF) * 32 + py * 4 + (px >> 1);
        if (bo >= 0x10000) continue;
        var v = (video[bo] >> ((px & 1) * 4)) & 15;
        if (!v) continue;
        var col = palRgb((e >> 12) * 16 + v).join(',');
        hist[col] = (hist[col] || 0) + 1; opaque++;
        if (hist[col] > topN) { topN = hist[col]; top = col; }
        break;
      }
    }
    return opaque * 2 > n && top ? top.split(',').map(Number) : null;
  }
  function palRgb(i) { var c = vu16(V_PAL + i * 2), r = c & 31, g = (c >> 5) & 31, b = (c >> 10) & 31; return [r << 3 | r >> 2, g << 3 | g >> 2, b << 3 | b >> 2]; }

  // The player's own sprite from OAM: the objects overlapping the player's screen box, cached so it stays while paused.
  var OBJ_SIZES = [[8, 8], [16, 16], [32, 32], [64, 64], [16, 8], [32, 8], [32, 16], [64, 32], [8, 16], [8, 32], [16, 32], [32, 64]];
  function camera() {
    var bg0 = vu16(V_IO + 8), w = (bg0 >> 14) & 1 ? 512 : 256, h = (bg0 >> 14) & 2 ? 512 : 256;
    var cx = vu16(V_IO + 0x10) & 511, cy = vu16(V_IO + 0x12) & 511, px = S.px(ram), py = S.py(ram);
    while (cx + w <= px - 40) cx += w;
    while (cy + h <= py - 40) cy += h;
    return [cx, cy];
  }
  // Portraits (0.13): one fixed picture per character and size (big / small = one heart left), taken once from the
  // player's OAM sprites while standing still on the ground (not carrying, not on a vine), facing the sprite's own way,
  // and kept in localStorage. 0.12 showed whatever frame was on screen each second (walking, jumping, carrying, half off
  // screen), so the status picture kept changing.
  var portraits = {};
  function portraitKey() { return S.character(ram) + (S.health(ram) <= 1 ? 's' : 'b'); }
  function portraitFor() { return ram ? portraits[portraitKey()] || null : null; }
  (function loadPortraits() {
    if (window.STANDIN) return;
    for (var ch = 0; ch < 4; ch++) ['b', 's'].forEach(function (z) {
      var url = store.getItem('sma.portrait.' + ch + z); if (!url) return;
      var im = new Image(); im.onload = function () {
        var c = document.createElement('canvas'); c.width = im.width; c.height = im.height; c.getContext('2d').drawImage(im, 0, 0);
        portraits[ch + z] = c; draw();
      }; im.src = url;
    });
  })();
  function onGround() {
    if (!lvl) return false;
    var c = Math.floor((S.px(ram) + 8) / 16), r = Math.floor((S.py(ram) + 32) / 16), feet = cellId(c, r), body = cellId(c, r - 1);
    return feet >= 0x100 && (body >> 8) === 0;               // feet on a stand-on/solid cell (a vine is neither)
  }
  function grabPortrait() {
    if (!video || !ram || window.STANDIN) return;
    var key = portraitKey();
    if (portraits[key] || still < 6 || S.holding(ram) || !S.playing(ram) || S.subspace(ram) || !onGround()) return;
    var cam = camera(), sx = S.px(ram) - cam[0], sy = S.py(ram) - cam[1];
    if (sx < 0 || sx > 224 || sy < 0 || sy > 128) return;               // fully on screen
    var map1d = (vu16(V_IO) & 0x40) !== 0, list = [], flips = 0;
    for (var s = 0; s < 128; s++) {
      var a0 = vu16(V_OAM + s * 8), a1 = vu16(V_OAM + s * 8 + 2), a2 = vu16(V_OAM + s * 8 + 4);
      if ((a0 & 0x300) === 0x200 || (a0 >> 14) === 3 || (a0 & 0x100)) continue;
      var sz = OBJ_SIZES[(a0 >> 14) * 4 + (a1 >> 14)], y = a0 & 255, x = a1 & 511;
      if (y >= 160) y -= 256; if (x >= 240) x -= 512;
      if (x + sz[0] <= sx || x >= sx + 16 || y + sz[1] <= sy || y >= sy + 32) continue;
      if (sz[0] > 16 || sz[1] > 32) continue;
      var o = { x: x - sx, y: y - sy, w: sz[0], h: sz[1], tile: a2 & 0x3FF, pal: a2 >> 12, hf: (a1 & 0x1000) !== 0, vf: (a1 & 0x2000) !== 0 };
      list.push(o); if (o.hf) flips++;
    }
    if (!list.length) return;
    var top = Math.min.apply(null, list.map(function (o) { return o.y; })), bot = Math.max.apply(null, list.map(function (o) { return o.y + o.h; }));
    if (bot - top < (key[1] === 'b' ? 28 : 14)) return;                  // ducking or a partial sprite
    var c = document.createElement('canvas'); c.width = 32; c.height = 40;
    var x2 = c.getContext('2d');
    if (flips * 2 > list.length) { x2.translate(32, 0); x2.scale(-1, 1); }   // always face the sprite's own way
    list.reverse().forEach(function (o) {
      var cols = [null]; for (var i = 1; i < 16; i++) cols.push(palCss(256 + o.pal * 16 + i));
      for (var ty = 0; ty < o.h / 8; ty++) for (var tx = 0; tx < o.w / 8; tx++) {
        var stx = o.hf ? o.w / 8 - 1 - tx : tx, sty = o.vf ? o.h / 8 - 1 - ty : ty;
        var t = map1d ? o.tile + sty * (o.w / 8) + stx : o.tile + sty * 32 + stx;
        G.drawTile(x2, G.decode(video, 0x10000 + (t & 0x3FF) * 32), 8 + o.x + tx * 8, 4 + o.y + ty * 8, cols, o.hf, o.vf);
      }
    });
    portraits[key] = c;
    try { store.setItem('sma.portrait.' + key, c.toDataURL()); } catch (e) {}
  }

  // ------------------------------------------------------------------ area map (the decoded level in the game's tiles)
  function cellId(c, r) {
    if (!lvl) return -1;
    var idx, pages = S.pages(ram);
    if (S.vertical(ram)) { if (c < 0 || c > 15 || r < 0 || r >= pages * 15) return -1; idx = r * 16 + c; }
    else { if (c < 0 || r < 0 || r > 14 || c >= pages * 16) return -1; idx = (c >> 4) * 240 + r * 16 + (c & 15); }
    return lvl[idx * 2] | lvl[idx * 2 + 1] << 8;
  }
  function areaSize() { var p = S.pages(ram); return S.vertical(ram) ? [16, p * 15] : [p * 16, 15]; }
  function buildArea() {
    if (!lvl || !video || !meta) return;
    var sz = areaSize(), sum = 0;
    for (var i = 0; i < sz[0] * sz[1] * 2 && i < lvl.length; i += 3) sum = (sum * 31 + lvl[i]) | 0;
    var qm = S.quadMode(ram);
    var key = S.level(ram) + '/' + S.area(ram) + '/' + sz + '/' + sum + '/' + lastVidKey() + '/' + qm;
    if (key === areaKey && areaCv) return;
    areaKey = key;
    var w = sz[0] * 16, h = sz[1] * 16;
    areaCv = document.createElement('canvas'); areaCv.width = w; areaCv.height = h;
    var x2 = areaCv.getContext('2d'), img = x2.createImageData(w, h), d = img.data;
    var cb = (vu16(V_IO + 8) >> 2) & 3, bgc = skyRgb();
    for (var k = 0; k < w * h; k++) { d[k * 4] = bgc[0]; d[k * 4 + 1] = bgc[1]; d[k * 4 + 2] = bgc[2]; d[k * 4 + 3] = 255; }
    var palCache = {};
    for (var r = 0; r < sz[1]; r++) for (var c = 0; c < sz[0]; c++) {
      if (window.STANDIN) { standinCell(d, w, c, r, cellId(c, r)); continue; }    // test previews without game art
      var cid = cellId(c, r), q = S.retile(cid, qm, quad(cid)); if (!q) continue;
      for (var i2 = 0; i2 < 4; i2++) {
        var e = q[i2], t = e & 0x3FF, p = e >> 12, hf = e & 0x400, vf = e & 0x800, base = cb * 0x4000 + t * 32;
        if (base + 32 > 0x10000) continue;
        for (var y = 0; y < 8; y++) for (var x = 0; x < 8; x++) {
          var sx = hf ? 7 - x : x, sy = vf ? 7 - y : y, b = video[base + sy * 4 + (sx >> 1)], v = (sx & 1) ? b >> 4 : b & 15;
          if (!v) continue;
          var pk = p * 16 + v, rgb = palCache[pk] || (palCache[pk] = palRgb(pk));
          var px = c * 16 + (i2 & 1) * 8 + x, py = r * 16 + (i2 >> 1) * 8 + y, o = (py * w + px) * 4;
          d[o] = rgb[0]; d[o + 1] = rgb[1]; d[o + 2] = rgb[2];
        }
      }
    }
    x2.putImageData(img, 0, 0);
  }
  function standinCell(d, w, c, r, id) {
    var g = id >> 8, col = id < 0 || S.MARK[id] && (id === 0x23 || id === 0x24) || id === 0x22 || S.isMushSpot(id) || S.isEggSpot(id) ? null
      : g === 2 ? [150, 100, 60] : g === 1 ? [90, 170, 70] : S.isGrass(id) ? [220, 60, 50] : id === 0x30 ? [230, 40, 60] : [170, 210, 150];
    if (!col) return;
    for (var y = 0; y < 16; y++) for (var x = 0; x < 16; x++) {
      var o = ((r * 16 + y) * w + c * 16 + x) * 4, e = x === 0 || y === 0 ? 0.85 : 1;
      d[o] = col[0] * e; d[o + 1] = col[1] * e; d[o + 2] = col[2] * e;
    }
  }
  var vidKey = 0;
  function lastVidKey() { if (!video) return 0; var s = 0; for (var i = 0; i < 0x10000; i += 97) s = (s * 31 + video[i]) | 0; for (var j = 0; j < 0x200; j++) s = (s * 31 + video[V_PAL + j]) | 0; return s; }

  // ------------------------------------------------------------------ drawing
  function draw() {
    loadKit();
    if (!ram || !romOk) { ctx.fillStyle = C.night; ctx.fillRect(0, 0, W, H); } else backdrop();
    buttons = []; mapBox = null; optPane = null;
    if (!ram || !romOk) { idle(); return; }
    if (S.gameMode(ram) === 4) { marioBros(); drawTabs(); return; }
    if (!S.inLevel(ram) && !trk) { idle('TITLE SCREEN', 'PICK A FILE AND PLAY'); return; }
    if (!layout.small || tab === 'status') { blockA(layout.a.x, layout.a.y); blockB(layout.b.x, layout.b.y); }
    if (tab !== 'status') {
      var p = layout.pane;
      if (tab === 'map') drawMap(p);
      else if (tab === 'chart') drawChart(p);
      else if (tab === 'options') drawOptions(p);
    }
    drawTabs();
  }

  var stars = [];
  for (var s = 0; s < 50; s++) stars.push([Math.random(), Math.random(), Math.floor(Math.random() * 3)]);
  function starfield() {
    stars.forEach(function (st, i) {
      if ((tickN + i * 7) % 40 < 26) { ctx.fillStyle = [C.white, C.yellow, C.lblue][st[2]]; ctx.fillRect(Math.floor(st[0] * W), Math.floor(st[1] * H), 1, 1); }
    });
  }
  function idle(msgOverride, subOverride) {
    starfield();
    var cy = Math.floor(H / 2) - 30;
    G.textC(ctx, 'SUPER MARIO', W / 2 + 1, cy + 1, C.dbrown, 2);
    G.textC(ctx, 'SUPER MARIO', W / 2, cy, C.red, 2);
    G.textC(ctx, 'ADVANCE', W / 2 + 1, cy + 19, C.dbrown, 2);
    G.textC(ctx, 'ADVANCE', W / 2, cy + 18, C.yellow, 2);
    G.textC(ctx, 'LINK SCREEN', W / 2, cy + 36, C.lblue);
    var msg = msgOverride || (!romOk ? 'INSERT GAME PAK' : 'NO SIGNAL');
    if ((tickN >> 3) % 2 === 0) G.textC(ctx, msg, W / 2, cy + 56, C.white);
    G.textC(ctx, subOverride || (!romOk ? 'CHOOSE THE ROM IN THE MENU' : 'START THE GAME'), W / 2, cy + 70, C.grey);
  }

  // Cards in the style of SMA's pause panel: dark outline, orange ring, cream body, soft shadow, rounded corners.
  function rrect(x, y, w, h, col) { ctx.fillStyle = col; ctx.fillRect(x + 1, y, w - 2, h); ctx.fillRect(x, y + 1, w, h - 2); }
  // 0.8: calm cards — cream body, one thin warm-brown outline, a faint shadow; dark ink for text.
  function card(x, y, w, h) {
    rrect(x, y + 1, w + 2, h + 2, 'rgba(40,24,8,0.18)');
    rrect(x - 1, y - 1, w + 2, h + 2, '#9a7048');
    rrect(x, y, w, h, '#fbf4dc');
  }
  // section header: red words with a thin line under them
  function ribbon(t, x, y, col) {
    G.text(ctx, t, x, y, '#a82a1a');
    ctx.fillStyle = '#d8b878'; ctx.fillRect(x, y + 9, t.length * 8, 1);
  }
  // blend a colour toward cream (k = how much of the colour stays)
  function soft(col, k) {
    var n = parseInt(col.slice(1), 16), c = [n >> 16, (n >> 8) & 255, n & 255], b = [251, 244, 220];
    return '#' + ((1 << 24) | c.map(function (v, i) { return Math.round(b[i] + (v - b[i]) * k); }).reduce(function (a, v) { return a * 256 + v; }, 0)).toString(16).slice(1);
  }
  // Background: the title screen's sky and ground from the first-start capture (menu/gbakit.js) when there is one,
  // else a drawn sky with wavy green hills.
  var K = window.GbaKit || { ok: false }, kitTried = 0;
  function loadKit() {
    if (K.ok || !window.Native || !Native.kit || kitTried > 3) return;
    if (tickN % 60 !== 1) return;
    kitTried++;
    try { var b = Native.kit(); if (b) K.load(G.b64(b)); } catch (e) {}
  }
  function backdrop() {
    if (K.ok) { K.backdrop(ctx, W, H); ctx.fillStyle = 'rgba(255,255,255,0.35)'; ctx.fillRect(0, 0, W, H); return; }
    var g = ctx.createLinearGradient(0, 0, 0, H); g.addColorStop(0, '#9cc8ec'); g.addColorStop(1, '#e2f2f8');
    ctx.fillStyle = g; ctx.fillRect(0, 0, W, H);
    var hy = H - 26;
    for (var x = 0; x < W; x++) {
      var top = hy + Math.round(Math.sin(x / 9) * 3);
      ctx.fillStyle = C.dgreen; ctx.fillRect(x, top, 1, H - top);
      ctx.fillStyle = C.green; ctx.fillRect(x, top + 1, 1, H - top - 1);
      if (((x >> 2) + (top >> 1)) % 4 === 0) { ctx.fillStyle = C.dgreen; ctx.fillRect(x, top + 6, 1, 1); }
    }
  }
  function heart(x, y, full) {
    var rows = [0x66, 0xFF, 0xFF, 0xFF, 0x7E, 0x3C, 0x18];
    rows.forEach(function (b, r) { for (var c = 0; c < 8; c++) if ((b >> (7 - c)) & 1) {
      var edge = r === 0 || c === 0 || c === 7 || !((rows[r + 1] || 0) >> (7 - c) & 1) || !((b >> (8 - c)) & 1) || !((b >> (6 - c)) & 1);
      ctx.fillStyle = edge ? C.dbrown : full ? C.red : C.lblue; ctx.fillRect(x + c, y + r, 1, 1); } });
  }
  function coin(x, y, on, big) {
    var s = big ? 7 : 5;
    ctx.fillStyle = on ? C.dbrown : '#c8b888'; ctx.fillRect(x, y, s, s);
    ctx.fillStyle = on ? C.red : '#e8dcb0'; ctx.fillRect(x + 1, y + 1, s - 2, s - 2);
    if (on && big) { ctx.fillStyle = C.yellow; ctx.fillRect(x + 3, y + 2, 1, 3); }
  }
  function cherry(x, y, on) {
    ctx.fillStyle = on ? C.red : '#d8c8a0'; ctx.fillRect(x, y + 2, 3, 3); ctx.fillRect(x + 3, y + 3, 3, 3);
    ctx.fillStyle = on ? C.dgreen : '#c8b888'; ctx.fillRect(x + 2, y, 3, 1); ctx.fillRect(x + 1, y + 1, 1, 1); ctx.fillRect(x + 4, y + 1, 1, 2);
  }
  function bar(x, y, w, v, col) { ctx.fillStyle = C.parch; ctx.fillRect(x, y + 1, w, 5); ctx.fillStyle = col; ctx.fillRect(x, y + 1, Math.max(1, Math.round(w * Math.min(1, v))), 5); }

  // An item icon from the first-start capture (16x16, drawn 1:1) or, when there is none, the drawn fallback.
  // alpha < 1 = an empty slot (cherry / Ace Coin not taken yet): the icon's silhouette in a soft tan, so the slot keeps
  // a clear shape without competing with the ones you have.
  function icon(name, x, y, alpha, fallback) {
    if (alpha != null && alpha < 1) { var c = iconAt(name, 16); if (c) { ctx.drawImage(shadowOf(c, '#d6c49c'), x, y); return true; } }
    if (!drawIcon(name, x, y, 16)) { if (fallback) fallback(); return false; } return true;
  }
  function blockA(x, y) {
    card(x, y, 116, A_H);
    var lv = S.level(ram), name = lv <= 19 ? S.LEVELS[lv] : '-';
    var ch = S.character(ram), band = soft(S.CHAR_COL[ch], 0.3);
    rrect(x + 1, y + 1, 114, 11, band);
    G.text(ctx, 'WORLD', x + 4, y + 3, '#4a2c12'); G.text(ctx, name, x + 46, y + 3, '#a82a1a');
    var where = S.subspace(ram) ? 'SUB' : 'A' + (S.areaNo(ram) + 1);
    G.textR(ctx, where, x + 113, y + 3, '#4a2c12');
    var pic = portraitFor();
    if (pic) ctx.drawImage(pic, x + 2, y + 14); else portraitHolder(x + 6, y + 18);
    G.text(ctx, S.CHARS[ch], x + 38, y + 15, CHAR_TEXT[ch]);
    G.textR(ctx, 'x' + S.lives(ram), x + 113, y + 15, C.brown);
    var hp = S.health(ram);                                                  // the life meter's own hearts
    for (var i = 0; i < maxHearts; i++) (function (i) {
      icon(i < hp ? 'heartFull' : 'heartEmpty', x + 35 + i * 12, y + 24, 1, function () { heart(x + 38 + i * 10, y + 28, i < hp); });
    })(i);
    G.text(ctx, pad(S.score(ram), 8), x + 38, y + 42, C.dbrown);
    var ch2 = Math.min(5, S.cherries(ram));                                  // cherries toward the star: 5 slots
    for (var k = 0; k < 5; k++) (function (k) {
      icon('cherry', x + 1 + k * 13, y + 53, k < ch2 ? 1 : 0.28, function () { cherry(x + 4 + k * 10, y + 58, k < ch2); });
    })(k);
    if (icon('coin', x + 68, y + 53)) G.text(ctx, 'x' + S.coins(ram), x + 84, y + 58, C.brown);
    else G.text(ctx, 'C' + S.coins(ram), x + 70, y + 58, C.brown);
    var ace = lv <= 19 ? S.aceLevel(ram) | S.aceSaved(ram, lv) : 0;      // this visit, or already on the file
    for (var a = 0; a < 5; a++) (function (a) {
      var got = (ace >> a) & 1;
      icon('ace', x + 1 + a * 15, y + 71, got ? 1 : 0.28, function () { coin(x + 4 + a * 10, y + 75, got, true); });
    })(a);
    if (S.yoshi(ram) && lv <= 19) {                                            // Yoshi Challenge: the level's two eggs
      var ev = S.eggsVisit(ram), ef = S.eggsFile(ram, lv);
      for (var e = 0; e < 2; e++) (function (e) {
        var got = ((ev | ef) >> e) & 1;                                         // this visit, or already on the file
        icon('egg', x + 80 + e * 17, y + 71, got ? 1 : 0.28, function () {
          ctx.fillStyle = got ? C.green : '#d8c8a0'; ctx.fillRect(x + 84 + e * 12, y + 72, 7, 9);
        });
      })(e);
    }
  }
  function blockB(x, y) {
    card(x, y, 116, B_H);
    var timers = [['STAR', S.star(ram), '#d8a000', 'star', T.gold], ['WATCH', S.stopwatch(ram), C.blue, 'watch', T.blue], ['SUB', S.subspace(ram), C.dred, 'potion', C.dred]];
    var active = timers.filter(function (t) { return t[1] > 0; });
    if (active.length) active.slice(0, 2).forEach(function (a, i) {
      var tx = x + 2 + i * 57;
      if (!icon(a[3], tx, y + 2)) G.text(ctx, a[0][0], tx + 4, y + 6, a[4]);
      bar(tx + 18, y + 6, 36, a[1] / 255, a[2]);
    });
    else G.text(ctx, S.holding(ram) ? 'CARRYING' : 'READY', x + 4, y + 6, T.muted);
    var yy = y + 22, secs = trk ? trk.time / 59.7275 : 0;
    G.text(ctx, 'TIME', x + 4, yy, T.blue); G.textR(ctx, hms(secs), x + 113, yy, C.dbrown);
    G.text(ctx, 'DEATHS', x + 4, yy + 10, T.red); G.textR(ctx, String(trk ? trk.deaths : 0), x + 113, yy + 10, C.dbrown);
    if (S.yoshi(ram)) { G.text(ctx, 'EGGS', x + 4, yy + 20, C.dgreen); G.textR(ctx, S.eggTotal(ram) + '/40', x + 113, yy + 20, C.dbrown); }
    else { G.text(ctx, 'LEVELS', x + 4, yy + 20, C.dgreen); G.textR(ctx, S.clearedCount(ram) + '/20', x + 113, yy + 20, C.dbrown); }
    G.text(ctx, 'ACE COINS', x + 4, yy + 30, T.red); G.textR(ctx, S.aceTotal(ram) + '%', x + 113, yy + 30, C.dbrown);
  }

  // ------------------------------------------------------------------ map tab
  var mapBox = null, mapPan = { x: 0, y: 0 }, mapDrag = null, mapScale = 1;
  function hasIcons() { return K.ok && K.icons && K.icons.potion; }

  // ------------------------------------------------------------------ icons
  // Item icons come from the first-start capture (menu/gbakit.js) and, for sprites the game only loads while they're on
  // screen (rocket, giant vegetable, giant Shy Guys), from live VRAM the first time one shows up (kept in localStorage).
  // Smaller sizes are area-averaged copies (even pixels instead of nearest-neighbour's dropped rows), cached per size.
  var live = {}, sized = {}, FALLBACK = { giantVeg: 'bigveg', giantShyBlue: 'giantShy' };
  function iconSrc(name) {
    for (var n = name; n; n = FALLBACK[n]) { var c = (K.ok && K.icons && K.icons[n]) || live[n]; if (c) return c; }
    return null;
  }
  function iconAt(name, size) {
    var src = iconSrc(name); if (!src) return null;
    var k = name + '/' + size + '/' + (src.__id || (src.__id = ++iconIds));
    if (sized[k]) return sized[k];
    return (sized[k] = src.width === size ? src : resample(src, size / src.width));
  }
  var iconIds = 0;
  function drawIcon(name, x, y, size, alpha) {
    var c = iconAt(name, size || 16); if (!c) return false;
    if (alpha != null && alpha < 1) { ctx.save(); ctx.globalAlpha = alpha; ctx.drawImage(c, x, y); ctx.restore(); }
    else ctx.drawImage(c, x, y);
    return true;
  }
  // Area-average resampling (separable box filter with partial-pixel weights): a scaled copy where every source pixel
  // counts, so outlines thin rather than vanish and every output pixel is the same size.
  function resample(src, sc) {
    var w = src.width, h = src.height, W2 = Math.max(1, Math.round(w * sc)), H2 = Math.max(1, Math.round(h * sc));
    var sd = src.getContext('2d').getImageData(0, 0, w, h).data;
    function taps(n, n2) {
      var k = n / n2, out = [];
      for (var i = 0; i < n2; i++) {
        var a = i * k, b = a + k, t = [];
        for (var j = Math.floor(a); j < Math.min(n, Math.ceil(b)); j++) { var wt = Math.min(b, j + 1) - Math.max(a, j); if (wt > 1e-6) t.push(j, wt / k); }
        out.push(t);
      }
      return out;
    }
    var tx = taps(w, W2), ty = taps(h, H2), mid = new Float32Array(W2 * h * 4);
    for (var y = 0; y < h; y++) for (var x = 0; x < W2; x++) {
      var t = tx[x], r = 0, g = 0, b = 0, a = 0;
      for (var i = 0; i < t.length; i += 2) { var o = (y * w + t[i]) * 4, wa = t[i + 1] * sd[o + 3] / 255; r += sd[o] * wa; g += sd[o + 1] * wa; b += sd[o + 2] * wa; a += wa; }
      var q = (y * W2 + x) * 4; mid[q] = r; mid[q + 1] = g; mid[q + 2] = b; mid[q + 3] = a;
    }
    var c = document.createElement('canvas'); c.width = W2; c.height = H2;
    var cx2 = c.getContext('2d'), img = cx2.createImageData(W2, H2), d = img.data;
    for (var y2 = 0; y2 < H2; y2++) for (var x2 = 0; x2 < W2; x2++) {
      var t2 = ty[y2], r2 = 0, g2 = 0, b2 = 0, a2 = 0;
      for (var j = 0; j < t2.length; j += 2) { var o2 = (t2[j] * W2 + x2) * 4, wy = t2[j + 1]; r2 += mid[o2] * wy; g2 += mid[o2 + 1] * wy; b2 += mid[o2 + 2] * wy; a2 += mid[o2 + 3] * wy; }
      var q2 = (y2 * W2 + x2) * 4;
      if (a2 > 0.01) { d[q2] = r2 / a2; d[q2 + 1] = g2 / a2; d[q2 + 2] = b2 / a2; d[q2 + 3] = Math.min(255, a2 * 255); }
    }
    cx2.putImageData(img, 0, 0);
    return c;
  }
  // Sprites captured from live VRAM: OAM must show the sprite (tile, palette, size) at that moment, so the tiles in VRAM
  // are the right ones. Parts are (tile, w, h, OBJ palette, y offset) in 2D mapping.
  var LIVE = [
    { name: 'rocket', w: 32, h: 32, parts: [[400, 32, 16, 3, 0], [404, 32, 16, 3, 16]] },
    { name: 'giantVeg', w: 32, h: 32, parts: [[908, 32, 32, 4, 0]] },
    { name: 'giantShy', w: 32, h: 32, parts: [[920, 32, 32, 1, 0]] },
    { name: 'giantShyBlue', w: 32, h: 32, parts: [[920, 32, 32, 2, 0]] }
  ];
  function loadLive() {
    if (window.STANDIN) return;
    LIVE.forEach(function (e) {
      var url = store.getItem('sma.live.' + e.name); if (!url || live[e.name]) return;
      var im = new Image(); im.onload = function () {
        var c = document.createElement('canvas'); c.width = im.width; c.height = im.height; c.getContext('2d').drawImage(im, 0, 0);
        live[e.name] = c; sized = {}; draw();
      }; im.src = url;
    });
  }
  function grabLive() {
    if (!video || window.STANDIN) return;
    var oam = [];
    for (var s2 = 0; s2 < 128; s2++) {
      var a0 = vu16(V_OAM + s2 * 8), a1 = vu16(V_OAM + s2 * 8 + 2), a2 = vu16(V_OAM + s2 * 8 + 4);
      if ((a0 & 0x300) === 0x200 || (a0 >> 14) === 3) continue;
      var sz = OBJ_SIZES[(a0 >> 14) * 4 + (a1 >> 14)];
      oam.push(sz[0] + 'x' + sz[1] + ':' + (a2 & 0x3FF) + ':' + (a2 >> 12));
    }
    var map1d = (vu16(V_IO) & 0x40) !== 0; if (map1d) return;
    LIVE.forEach(function (e) {
      if (live[e.name]) return;
      if (!e.parts.every(function (p) { return oam.indexOf(p[1] + 'x' + p[2] + ':' + p[0] + ':' + p[3]) >= 0; })) return;
      var c = document.createElement('canvas'); c.width = e.w; c.height = e.h;
      var x2 = c.getContext('2d'), img = x2.createImageData(e.w, e.h), d = img.data, n = 0;
      e.parts.forEach(function (p) {
        for (var y = 0; y < p[2]; y++) for (var x = 0; x < p[1]; x++) {
          var t = p[0] + (y >> 3) * 32 + (x >> 3), o = 0x10000 + t * 32 + (y & 7) * 4 + ((x & 7) >> 1);
          var v = (video[o] >> ((x & 1) * 4)) & 15; if (!v) continue;
          var rgb = palRgb(256 + p[3] * 16 + v), q = ((y + p[4]) * e.w + x) * 4;
          d[q] = rgb[0]; d[q + 1] = rgb[1]; d[q + 2] = rgb[2]; d[q + 3] = 255; n++;
        }
      });
      if (n < 60) return;
      x2.putImageData(img, 0, 0);
      live[e.name] = c; sized = {};
      try { store.setItem('sma.live.' + e.name, c.toDataURL()); } catch (err) {}
    });
  }
  loadLive();
  // Keep a view still while the player is in its middle 40%, ease after them otherwise (like a game camera).
  function follow(v, pp, span, len) {
    var tgt = v;
    if (pp < v + span * 0.3) tgt = pp - span * 0.3; else if (pp > v + span * 0.7) tgt = pp - span * 0.7;
    v += (tgt - v) * 0.5;
    return len <= span ? -(span - len) / 2 : Math.max(0, Math.min(v, len - span));
  }
  var view = { key: '', x: null, y: null }, stripCache = { key: '', sc: 0, c: null };
  function drawMap(p) {
    card(p.x + 1, p.y + 1, p.w - 2, p.h - 2);
    var gh = hasIcons() ? 18 : 12;                                     // guide line under the map
    var inner = { x: p.x + 3, y: p.y + 3, w: p.w - 6, h: p.h - 6 - gh };
    mapView(inner, {});
    guide(p, gh);
  }
  /** The live map in a rectangle (clipped). */
  function mapView(inner, o) {
    buildArea();
    mapBox = inner;
    if (!areaCv || !S.inLevel(ram)) { G.textC(ctx, 'NO MAP HERE', inner.x + inner.w / 2, inner.y + inner.h / 2 - 4, T.muted); return; }
    ctx.save(); ctx.beginPath(); ctx.rect(inner.x, inner.y, inner.w, inner.h); ctx.clip();
    var sk = skyRgb(); ctx.fillStyle = 'rgb(' + sk.join(',') + ')'; ctx.fillRect(inner.x, inner.y, inner.w, inner.h);
    var aw = areaCv.width, ah = areaCv.height, vert = S.vertical(ram), bar = null;
    var px = S.px(ram) + 8, py = S.py(ram) + 16;
    // Zoom levels (0.14): FIT = the area's whole height (whole width for vertical areas) fills the pane; WIDE shows 1.5x
    // more; NEAR is halfway to 1:1. Scales snap to whole pixels per 16 px cell; below 1 the map is an area-averaged copy.
    var fit = Math.min(1, vert ? inner.w / aw : inner.h / ah);
    // when FIT is already close to 1:1 (vertical areas are about one screen wide) the steps spread further out instead
    var L = fit > 0.7 ? [fit / 2.25, fit / 1.5, (fit / 1.5 + 1) / 2, 1] : [fit / 1.5, fit, (fit + 1) / 2, 1];
    var sc = Math.max(2, Math.floor(L[opts.zl] * 16)) / 16, fitSc = Math.max(2, Math.floor(fit * 16)) / 16;
    zoomName = sc === 1 ? '1:1' : sc === fitSc ? 'FIT' : Math.round(sc * 100) + '%';
    var spanX = inner.w / sc, spanY = inner.h / sc;
    var key = S.level(ram) + '/' + S.area(ram) + '/' + vert + '/' + opts.zl;
    if (view.key !== key || view.x == null) { view.key = key; view.x = px - spanX / 2; view.y = py - spanY / 2; }
    view.x = follow(view.x, px, spanX, aw); view.y = follow(view.y, py, spanY, ah);
    // after a drag the view stays where you put it for ~4 s, then eases back to following you
    if (!mapDrag && tickN - dragTick > 40) { mapPan.x *= 0.7; mapPan.y *= 0.7; if (Math.abs(mapPan.x) + Math.abs(mapPan.y) < 1) mapPan.x = mapPan.y = 0; }
    var cx = view.x + mapPan.x, cy = view.y + mapPan.y;
    cx = aw > spanX ? Math.max(0, Math.min(cx, aw - spanX)) : -(spanX - aw) / 2;
    // an area shorter than the pane sits on the pane's bottom edge (no empty band under the ground)
    cy = ah > spanY ? Math.max(0, Math.min(cy, ah - spanY)) : (vert ? -(spanY - ah) / 2 : ah - spanY);
    var img = areaCv;
    if (sc !== 1) {
      if (stripCache.key !== areaKey || stripCache.sc !== sc) stripCache = { key: areaKey, sc: sc, c: resample(areaCv, sc) };
      img = stripCache.c;
    }
    var ox = Math.round(inner.x - cx * sc), oy = Math.round(inner.y - cy * sc);
    mapScale = sc;
    ctx.drawImage(img, ox, oy);
    var toPane = function (wx, wy) { return [ox + wx * sc, oy + wy * sc, sc]; };
    if (vert ? ah > spanY : aw > spanX) bar = vert ? { vert: true, f0: cy / ah, f1: (cy + spanY) / ah, fp: py / ah } : { vert: false, f0: cx / aw, f1: (cx + spanX) / aw, fp: px / aw };
    markers(toPane);
    you(toPane(px, py + 15), sc);
    if (bar) {                                // where the strip is in the area: a thin track, the view, the player
      ctx.fillStyle = 'rgba(40,24,8,0.45)';
      if (bar.vert) {
        var bx = inner.x + inner.w - 3; ctx.fillRect(bx, inner.y, 3, inner.h);
        ctx.fillStyle = 'rgba(251,244,220,0.9)'; ctx.fillRect(bx, inner.y + Math.round(bar.f0 * inner.h), 3, Math.max(3, Math.round((bar.f1 - bar.f0) * inner.h)));
        ctx.fillStyle = S.CHAR_COL[S.character(ram)]; ctx.fillRect(bx, inner.y + Math.round(bar.fp * inner.h) - 1, 3, 3);
      } else {
        var by = inner.y + inner.h - 3; ctx.fillRect(inner.x, by, inner.w, 3);
        ctx.fillStyle = 'rgba(251,244,220,0.9)'; ctx.fillRect(inner.x + Math.round(bar.f0 * inner.w), by, Math.max(3, Math.round((bar.f1 - bar.f0) * inner.w)), 3);
        ctx.fillStyle = S.CHAR_COL[S.character(ram)]; ctx.fillRect(inner.x + Math.round(bar.fp * inner.w) - 1, by, 3, 3);
      }
    }
    // zoom: four dots in the corner (the current one filled), and its name for 2 s after a change
    var dx = inner.x + inner.w - 34, dy = inner.y + 4;
    ctx.fillStyle = 'rgba(30,16,4,0.55)'; ctx.fillRect(dx - 3, dy - 2, 34, 9);
    for (var zi = 0; zi < 4; zi++) { ctx.fillStyle = zi === opts.zl ? C.white : 'rgba(255,255,255,0.4)'; ctx.fillRect(dx + zi * 7, dy, 5, 5); }
    if (tickN - zoomShown < 20) { var zn = zoomName, zw = zn.length * 8 + 8; ctx.fillStyle = 'rgba(30,16,4,0.75)'; ctx.fillRect(inner.x + inner.w / 2 - zw / 2, inner.y + 14, zw, 12); G.textC(ctx, zn, inner.x + inner.w / 2, inner.y + 16, C.white); }
    ctx.restore();
  }
  // you: a pink square with a white outline over your body, one pixel of dark round it so it holds up on white sky and
  // snow. (0.11's upright rectangle read as a door; 0.12-0.21 used a pin in the character's colour.)
  var YOU_PINK = '#ff4fa3';
  function you(pt) {
    var x = Math.round(pt[0]), y = Math.round(pt[1]) - 8;                                // pt is your feet
    ctx.fillStyle = '#2a1404'; ctx.fillRect(x - 6, y - 6, 12, 12);
    ctx.fillStyle = C.white; ctx.fillRect(x - 5, y - 5, 10, 10);
    ctx.fillStyle = YOU_PINK; ctx.fillRect(x - 3, y - 3, 6, 6);
  }
  function tag(pt, letter, col) {
    var x = Math.round(pt[0]), y = Math.round(pt[1]);
    ctx.fillStyle = 'rgba(0,0,0,0.65)'; ctx.fillRect(x - 5, y - 5, 10, 10);
    G.text(ctx, letter, x - 4, y - 4, col);
  }
  // an item icon over a map cell (cell size, at least 11 px so it stays readable in the strip), else the letter tag
  function mark(pt, name, letter, col, alpha) {
    var sz = Math.max(10, Math.round(16 * pt[2] * 1.5)), c = name && iconAt(name, sz);
    if (c) {
      var x = Math.round(pt[0] - sz / 2), y = Math.round(pt[1] - sz / 2);
      ctx.save(); if (alpha != null) ctx.globalAlpha = alpha;
      ctx.drawImage(shadowOf(c), x + 1, y + 1); ctx.drawImage(c, x, y); ctx.restore();
      return;
    }
    if (letter) tag(pt, letter, col);
  }
  // a dark silhouette of an icon (drop shadow on the map, "not yet" slots on the status card), cached on the canvas
  function shadowOf(c, col) {
    var k = '__sh' + (col || '');
    if (c[k]) return c[k];
    var d = document.createElement('canvas'); d.width = c.width; d.height = c.height;
    var x = d.getContext('2d'); x.drawImage(c, 0, 0); x.globalCompositeOperation = 'source-in';
    x.fillStyle = col || 'rgba(30,16,4,0.55)'; x.fillRect(0, 0, d.width, d.height);
    return (c[k] = d);
  }
  function label(pt, text, col) {          // a word on the map (BOSS names, EXIT): dark plate, coloured text
    var w = text.length * 8 + 4, x = Math.round(pt[0] - w / 2), y = Math.round(pt[1]);
    ctx.fillStyle = 'rgba(30,16,4,0.8)'; ctx.fillRect(x, y, w, 11);
    G.text(ctx, text, x + 2, y + 2, col);
  }
  // Key, crystal ball, boss and exit from the area's object list in the ROM (positions are where they spawn).
  function objMarkers(toPane) {
    var list = objList(); if (!list.length) return;
    list.forEach(function (o) {
      var pt = toPane(o.x * 16 + 8, o.y * 16 + 8);
      if (o.type === S.OBJ_KEY) mark(pt, 'key', 'K', C.yellow);
      else if (o.type === S.OBJ_CRYSTAL) mark(pt, 'crystal', 'O', C.lblue);
      else if (S.OBJ_EXIT.indexOf(o.type) >= 0) label([pt[0], pt[1] - 6], 'EXIT', '#f8e080');
      else if (S.BOSS[o.type] || (classTable && classTable[o.type] === 0x0A && o.type < 0x60)) label([pt[0], pt[1] - 22], S.BOSS[o.type] || 'BOSS', '#ff9078');
    });
  }
  function markers(toPane) {
    var sz = areaSize();
    for (var r = 0; r < sz[1]; r++) for (var c = 0; c < sz[0]; c++) {
      var t = cellId(c, r), pt;
      if (!S.stillThere(ram, t)) continue;
      if (opts.xray && S.GRASS[t] && S.GRASS[t][0]) { pt = toPane(c * 16 + 8, r * 16 + 8); mark(pt, S.GRASS_ICON[t], S.GRASS[t][0], t === 0x2C ? C.green : t === 0x2B ? C.pink : C.yellow); }
      else if (opts.markers && S.MARK[t]) {
        pt = toPane(c * 16 + 8, r * 16 + 8);
        if (t === 0x30) { ctx.fillStyle = C.red; ctx.fillRect(Math.round(pt[0]) - 1, Math.round(pt[1]) - 1, 3, 3); }
        else if (S.isMushSpot(t)) mark(pt, 'mushroom', 'M', C.orange);
      }
      else if (opts.markers && S.isEggSpot(t)) {      // Yoshi Challenge eggs (stillThere: only in the challenge, not taken)
        pt = toPane(c * 16 + 8, r * 16 + 8);
        mark(pt, 'egg', 'E', C.green, (S.eggsFile(ram, S.level(ram)) >> S.EGG_SPOT[t]) & 1 ? 0.45 : 1);
      }
    }
    // (0.12: no live enemy / item dots — the map tracks only you)
    if (opts.markers && !S.subspace(ram)) objMarkers(toPane);
    if (opts.markers && !S.subspace(ram)) aceHere().forEach(function (a) {      // Ace Coins from the ROM's object list
      var pt3 = toPane(a.x, a.y);
      if (hasIcons()) mark(pt3, 'ace', '', null, a.got ? 0.35 : 1);
      else if (!a.got || (tickN >> 3) % 4) coin(Math.round(pt3[0]) - 3, Math.round(pt3[1]) - 3, !a.got, true);
    });
  }
  // What's left in this area, under the map: icons with counts when there are icons, else words.
  function guide(p, gh) {
    var y = p.y + p.h - gh - 1;
    if (!S.inLevel(ram) || !lvl) return p.list ? [] : undefined;
    if (p.list && (S.subspace(ram) || !hasIcons())) return [];
    if (S.subspace(ram)) { G.text(ctx, 'SUBSPACE: PULL GRASS FOR COINS', p.x + 4, y + (gh - 8) / 2 + 1, C.brown); return; }
    var sz = areaSize(), byIcon = {}, byName = {}, order = [], cherries = 0, spots = 0;
    for (var r = 0; r < sz[1]; r++) for (var c = 0; c < sz[0]; c++) {
      var t = cellId(c, r);
      if (!S.stillThere(ram, t)) continue;
      if (S.GRASS[t] && S.GRASS[t][0]) {
        var ic = S.GRASS_ICON[t], nm = S.GRASS[t][1];
        if (hasIcons() && ic && iconSrc(ic)) { if (!byIcon[ic]) order.push(ic); byIcon[ic] = (byIcon[ic] || 0) + 1; }
        else byName[nm] = (byName[nm] || 0) + 1;
      }
      if (t === 0x30) cherries++;
      if (S.isMushSpot(t)) spots++;
      if (S.isEggSpot(t)) { if (hasIcons() && iconSrc('egg')) { if (!byIcon.egg) order.push('egg'); byIcon.egg = (byIcon.egg || 0) + 1; } else byName['YOSHI EGG'] = (byName['YOSHI EGG'] || 0) + 1; }
    }
    var aceLeft = aceHere().filter(function (a) { return !a.got; }).length;
    if (hasIcons()) {
      var items = [];
      if (aceLeft) items.push(['ace', aceLeft]);
      var ol = objList(), nk = ol.filter(function (o) { return o.type === S.OBJ_KEY; }).length, ncr = ol.filter(function (o) { return o.type === S.OBJ_CRYSTAL; }).length;
      if (nk) items.push(['key', nk]);
      if (ncr) items.push(['crystal', ncr]);
      ['potion', 'oneup', 'mushroom', 'heart', 'pow', 'bomb', 'bobomb', 'shell', 'rocket', 'bigveg', 'giantVeg', 'giantShy', 'giantShyBlue', 'egg', 'ninji', 'coin'].forEach(function (k) {
        if (k === 'mushroom' ? spots : byIcon[k]) items.push([k, k === 'mushroom' ? spots : byIcon[k]]);
      });
      if (cherries) items.push(['cherry', cherries]);
      Object.keys(byName).forEach(function (n) { items.push([null, byName[n], n]); });
      if (p.list) return items;
      if (!items.length) { G.text(ctx, 'NOTHING HIDDEN IN THIS AREA', p.x + 4, y + 6, C.brown); return; }
      var x = p.x + 4, xe = p.x + p.w - 4;
      for (var i = 0; i < items.length; i++) {
        var it = items[i], label = it[0] ? 'x' + it[1] : it[1] + ' ' + it[2], w = (it[0] ? 17 : 0) + label.length * 8 + 6;
        if (x + w > xe) break;
        if (it[0]) { drawIcon(it[0], x, y + 1); x += 17; }
        G.text(ctx, label, x, y + 6, C.brown); x += label.length * 8 + 6;
      }
      return;
    }
    var parts = [];
    ['POTION', '1UP', 'POW', 'BOMB', 'ROCKET', 'SHELL', 'BIG VEGETABLE', 'HEART', 'COIN'].forEach(function (k) { if (byName[k]) parts.push(byName[k] + ' ' + k); });
    if (cherries) parts.push(cherries + ' CHERRY');
    if (aceLeft) parts.unshift(aceLeft + ' ACE COIN' + (aceLeft > 1 ? 'S' : ''));
    var msg = parts.length ? 'HERE: ' + parts.slice(0, 3).join(', ') : 'NOTHING HIDDEN IN THIS AREA';
    var maxc = Math.floor((p.w - 8) / 8);
    G.text(ctx, msg.length > maxc ? msg.substr(0, maxc) : msg, p.x + 4, y + 2, C.brown);
  }

  // ------------------------------------------------------------------ chart tab
  // a colour per world, after its look (grass, desert, sky, ice, night, desert, clouds)
  var WORLD_COL = ['#30a030', '#d09030', '#2890e0', '#40b8d8', '#6048b0', '#c07028', '#e05880'];
  function drawChart(p) {
    card(p.x + 1, p.y + 1, p.w - 2, p.h - 2);
    var cur = S.level(ram), x0 = p.x + 6, y0 = p.y + 6, rowH = Math.max(14, Math.min(20, Math.floor((p.h - 28) / 7)));
    var colW = Math.floor((p.w - 32) / 3), eggMode = S.yoshi(ram) || S.eggTotal(ram) > 0;
    for (var w = 0; w < 7; w++) {
      var y = y0 + w * rowH;
      var wc = soft(WORLD_COL[w], 0.45); rrect(x0 - 2, y, 19, rowH - 3, wc); G.text(ctx, 'W' + (w + 1), x0, y + 2, '#3a2008');
      var n = w === 6 ? 2 : 3;
      for (var i = 0; i < n; i++) {
        var L = S.WORLD_START[w] + i, x = x0 + 22 + i * colW, on = L === cur && S.inLevel(ram);
        if (on) { rrect(x - 3, y - 1, colW - 1, rowH - 1, '#b8862e'); rrect(x - 2, y, colW - 3, rowH - 3, '#f4d27a'); }   // steady, not blinking
        G.text(ctx, S.LEVELS[L], x, y + 2, on ? C.dred : C.brown);
        var got = S.aceSaved(ram, L) | (on ? S.aceLevel(ram) : 0);           // every level's coins from the file's bits
        if (colW >= 58) for (var a = 0; a < 5; a++) coin(x + 28 + a * 6, y + 3, (got >> a) & 1, false);
        else if (got) G.text(ctx, String(popcount(got)), x + 26, y + 2, T.red);
        if (eggMode && colW >= 62) {                                            // the level's two Yoshi eggs (file + this visit), stacked
          var eg = S.eggsFile(ram, L) | (on ? S.eggsVisit(ram) : 0);
          for (var e = 0; e < 2; e++) { ctx.fillStyle = (eg >> e) & 1 ? C.green : '#d8c8a0'; ctx.fillRect(x + 59, y + 2 + e * 4, 3, 3); }
        }
        if (S.cleared(ram, L)) { ctx.fillStyle = trk && cleared(L) ? S.CHAR_COL[clearedBy(L)] : C.dbrown; ctx.fillRect(x, y + 11, 22, 2); }
        else if (trk && ((trk.warped >>> L) & 1)) { ctx.fillStyle = C.grey; ctx.fillRect(x, y + 11, 22, 1); }
      }
    }
    var fy = y0 + 7 * rowH + 2;
    G.text(ctx, 'ACE COINS ' + S.aceTotal(ram) + '/100', x0, fy, T.red);
    G.textR(ctx, S.clearedCount(ram) + '/20', p.x + p.w - 6, fy, C.dbrown);
    var lx = x0;
    S.CHARS.forEach(function (n, i) { ctx.fillStyle = S.CHAR_COL[i]; ctx.fillRect(lx, fy + 13, 8, 2); G.text(ctx, n[0], lx + 10, fy + 10, C.brown); lx += 24; });
    if (eggMode) G.textR(ctx, 'EGGS ' + S.eggTotal(ram) + '/40', p.x + p.w - 6, fy + 10, C.dgreen);
  }

  // ------------------------------------------------------------------ Mario Bros. Classic
  function marioBros() {
    var m = S.mb(ram), cx = W / 2, y = Math.max(8, H / 2 - 50);
    card(cx - 90, y, 180, 92);
    G.textC(ctx, 'MARIO BROS.', cx, y + 6, T.red, 2);
    G.text(ctx, 'PHASE', cx - 80, y + 32, C.brown); G.textR(ctx, String(m.round), cx + 80, y + 32, C.dbrown);
    G.text(ctx, 'ENEMIES LEFT', cx - 80, y + 44, C.brown); G.textR(ctx, String(m.left), cx + 80, y + 44, C.dbrown);
    G.text(ctx, 'SCORE', cx - 80, y + 56, C.brown); G.textR(ctx, pad(m.score, 6), cx + 80, y + 56, C.dbrown);
    G.text(ctx, 'LIVES', cx - 80, y + 68, C.brown); G.textR(ctx, String(m.lives), cx + 80, y + 68, C.dbrown);
  }

  // ------------------------------------------------------------------ options tab
  var optPane = null, optScroll = 0, optDrag = null, slotArm = null, optMsg = '', optMsgT = 0, thumbCache = {};
  function drawOptions(p) {
    card(p.x + 1, p.y + 1, p.w - 2, p.h - 2);
    optPane = p;
    ctx.save(); ctx.beginPath(); ctx.rect(p.x + 2, p.y + 2, p.w - 4, p.h - 4); ctx.clip();
    var x = p.x + 6, y = p.y + 6 - optScroll, cw = p.w - 12;
    var slots = [];
    if (DUAL && Native.slots) { try { slots = JSON.parse(Native.slots()); } catch (e) {} }
    ribbon('SAVE STATES', x, y, C.red); y += 13;
    var cardW = Math.floor((cw - 4) / 2), th = Math.round((cardW - 4) * 2 / 3), ch = th + 30;
    slots.forEach(function (sl, i) {
      var cx = x + (i % 2) * (cardW + 4), cy = y + Math.floor(i / 2) * (ch + 4);
      ctx.fillStyle = C.parch; ctx.fillRect(cx, cy, cardW, ch);
      var im = slotThumb(sl);
      if (im) ctx.drawImage(im, cx + 2, cy + 2, cardW - 4, th); else { ctx.fillStyle = C.lblue; ctx.fillRect(cx + 2, cy + 2, cardW - 4, th); G.textC(ctx, 'EMPTY', cx + cardW / 2, cy + th / 2 - 2, C.white); }
      G.text(ctx, String(sl.n), cx + 3, cy + th + 4, C.brown);
      var armed = function (act) { return slotArm && slotArm.n === sl.n && slotArm.act === act && slotArm.until > tickN; };
      var bw = Math.floor((cardW - 8) / 2), by = cy + th + 14;
      button(armed('save') ? 'SURE?' : 'SAVE', cx + 2, by, bw, 13, armed('save'), function () {
        if (!armed('save') && sl.time) { slotArm = { n: sl.n, act: 'save', until: tickN + 40 }; return; }
        slotArm = null; var ok = Native.saveSlot(sl.n); delete thumbCache[sl.n + ':' + sl.time]; optMsg = ok ? 'SAVED ' + sl.n : 'FAILED'; optMsgT = tickN + 40;
      });
      if (sl.time) button(armed('load') ? 'SURE?' : 'LOAD', cx + cardW - 2 - bw, by, bw, 13, armed('load'), function () {
        if (!armed('load')) { slotArm = { n: sl.n, act: 'load', until: tickN + 40 }; return; }
        slotArm = null; var ok = Native.loadSlot(sl.n); optMsg = ok ? 'LOADED ' + sl.n : 'FAILED'; optMsgT = tickN + 40;
      });
    });
    y += Math.ceil(slots.length / 2) * (ch + 4) + 6;
    ribbon('MAP', x, y, '#30a030'); y += 13;
    option('X-RAY GRASS', opts.xray ? 'ON' : 'OFF', x, y, cw, function () { opts.xray = !opts.xray; save('sma.opts', opts); }); y += 13;
    option('MARKERS', opts.markers ? 'ON' : 'OFF', x, y, cw, function () { opts.markers = !opts.markers; save('sma.opts', opts); }); y += 13;
    option('MAP ZOOM', (opts.zl + 1) + ' OF 4', x, y, cw, function () { toggleZoom(); }); y += 18;
    ribbon('MARKERS', x, y, '#2870e0'); y += 13;
    var legend = [['C', 'COIN', 'coin'], ['V', 'BIG VEG', 'bigveg'], ['R', 'ROCKET', 'rocket'], ['S', 'SHELL', 'shell'], ['B', 'BOMB', 'bomb'],
                  ['K', 'KEY', 'key'], ['O', 'CRYSTAL', 'crystal'], ['P', 'POTION', 'potion'], ['1', '1UP', 'oneup'], ['W', 'POW', 'pow'], ['H', 'HEART', 'heart'], ['N', 'NINJI', 'ninji'],
                  ['G', 'GIANT', 'giantShy'], ['E', 'YOSHI EGG', 'egg'], ['M', 'MUSHROOM SPOT', 'mushroom']], rowH = hasIcons() ? 18 : 10;
    legend.forEach(function (k, i) {
      var xx = x + (i % 2) * Math.floor(cw / 2), yy = y + Math.floor(i / 2) * rowH;
      if (hasIcons()) { if (!(k[2] && drawIcon(k[2], xx, yy))) G.text(ctx, k[0], xx + 4, yy + 4, C.dred); G.text(ctx, k[1], xx + 19, yy + 4, C.brown); }
      else { G.text(ctx, k[0], xx, yy, C.dred); G.text(ctx, k[1], xx + 12, yy, C.brown); }
    });
    y += Math.ceil(legend.length / 2) * rowH + 4;
    if (DUAL) { option('PAUSE GAME', '', x, y, cw, function () { Native.menu(); }); y += 13; }
    if (DUAL && Native.overlay && Native.overlay()) { option('BACK TO GAME', '', x, y, cw, function () { Native.close(); }); y += 13; }
    G.text(ctx, 'SMA DUAL ' + (DUAL ? Native.version() : ''), x, y + 4, T.muted);
    optMax = Math.max(0, y + optScroll + 16 - (p.y + p.h));
    ctx.restore();
    if (optMsgT > tickN) { var tw = optMsg.length * 8 + 10; ctx.fillStyle = C.navy; ctx.fillRect(p.x + p.w / 2 - tw / 2, p.y + p.h - 18, tw, 13); G.textC(ctx, optMsg, p.x + p.w / 2, p.y + p.h - 15, C.white); }
  }
  var optMax = 0;
  function option(label, value, x, y, w, fn) {
    G.text(ctx, label, x, y, C.brown);
    if (value) G.textR(ctx, value, x + w, y, T.blue);
    buttons.push({ x: x - 2, y: y - 2, w: w + 4, h: 12, fn: fn, scroll: true });
  }
  function button(label, x, y, w, h, hot, fn) {
    ctx.fillStyle = hot ? C.orange : C.brown; ctx.fillRect(x, y, w, h);
    G.textC(ctx, label, x + w / 2, y + Math.floor((h - 8) / 2), C.white);
    buttons.push({ x: x, y: y, w: w, h: h, fn: fn, scroll: true });
  }
  function slotThumb(sl) {
    if (!sl.time) return null;
    var k = sl.n + ':' + sl.time;
    if (thumbCache[k] !== undefined) return thumbCache[k];
    thumbCache[k] = null;
    var url = DUAL && Native.thumb ? Native.thumb(sl.n) : '';
    if (url) { var img = new Image(); img.onload = function () { thumbCache[k] = img; draw(); }; img.src = url; }
    return null;
  }
  function toggleZoom() { opts.zl = (opts.zl + 1) % 4; mapPan.x = mapPan.y = 0; view.key = ''; zoomShown = tickN; save('sma.opts', opts); }
  var zoomShown = -99, dragTick = -99, zoomName = 'FIT';

  // ------------------------------------------------------------------ small shared pieces
  function rr(x, y, w, h, r, fill) {          // rounded rectangle
    ctx.beginPath(); ctx.moveTo(x + r, y); ctx.arcTo(x + w, y, x + w, y + h, r); ctx.arcTo(x + w, y + h, x, y + h, r);
    ctx.arcTo(x, y + h, x, y, r); ctx.arcTo(x, y, x + w, y, r); ctx.closePath(); ctx.fillStyle = fill; ctx.fill();
  }
  function portraitHolder(x, y) {             // before a portrait has been taken: the character's initial in a disc
    var ch = S.character(ram);
    ctx.fillStyle = soft(S.CHAR_COL[ch], 0.35); ctx.beginPath(); ctx.arc(x + 12, y + 14, 11, 0, 7); ctx.fill();
    G.textC(ctx, S.CHARS[ch][0], x + 12, y + 10, CHAR_TEXT[ch]);
  }
  // ------------------------------------------------------------------ tabs + input
  var TAB_COL = { status: '#e03828', map: '#30a030', chart: '#2870e0', options: '#e89000' };
  function drawTabs() {
    var y = layout.tabY, n = tabs.length, w = Math.floor(W / n);
    ctx.fillStyle = '#9a7048'; ctx.fillRect(0, y - 1, W, H - y + 1);
    tabs.forEach(function (t, i) {
      var x = i * w, on = t === tab, col = TAB_COL[t] || C.blue;
      rrect(x + 1, y, w - 2, H - y, on ? '#fbf4dc' : '#e6d8b8');
      if (on) { ctx.fillStyle = soft(col, 0.8); ctx.fillRect(x + 2, y, w - 4, 2); }
      G.textC(ctx, t.toUpperCase(), x + w / 2, y + 3, on ? '#a82a1a' : '#6a5030');
      buttons.push({ x: x, y: y, w: w, h: H - y, fn: function () { tab = t; } });
    });
  }
  function inOptions(x, y) { var p = optPane; return tab === 'options' && p && x >= p.x && x < p.x + p.w && y >= p.y && y < p.y + p.h; }
  function inMap(x, y) { var b = mapBox; return b && x >= b.x && x < b.x + b.w && y >= b.y && y < b.y + b.h; }
  function pos(e) { return [e.clientX * W / innerWidth, e.clientY * H / innerHeight]; }
  // Tap the map: next zoom level. Drag the map: look around (it eases back to you ~4 s after you let go).
  // Drag the options: scroll. Map drags move with the finger at any zoom.
  var down = null;
  cv.addEventListener('pointerdown', function (e) {
    var p = pos(e); down = { x: p[0], y: p[1], moved: false };
    if (inMap(p[0], p[1])) mapDrag = { x: p[0], y: p[1], px: mapPan.x, py: mapPan.y };
    if (inOptions(p[0], p[1])) optDrag = { y: p[1], s: optScroll };
  });
  cv.addEventListener('pointermove', function (e) {
    if (!down) return;
    var p = pos(e);
    if (Math.abs(p[0] - down.x) + Math.abs(p[1] - down.y) > 4) down.moved = true;
    if (mapDrag && down.moved) { mapPan.x = mapDrag.px - (p[0] - mapDrag.x) / mapScale; mapPan.y = mapDrag.py - (p[1] - mapDrag.y) / mapScale; dragTick = tickN; draw(); }
    if (optDrag && down.moved) { optScroll = Math.max(0, Math.min(optMax, optDrag.s - (p[1] - optDrag.y))); draw(); }
  });
  cv.addEventListener('pointerup', function (e) {
    var p = pos(e), d = down; down = null; mapDrag = null; optDrag = null;
    if (!d || d.moved) return;
    for (var i = buttons.length - 1; i >= 0; i--) {
      var b = buttons[i];
      if (p[0] >= b.x && p[0] < b.x + b.w && p[1] >= b.y && p[1] < b.y + b.h) { b.fn(); draw(); return; }
    }
    if (inMap(p[0], p[1])) { toggleZoom(); draw(); }
  });
  cv.addEventListener('pointercancel', function () { down = null; mapDrag = null; optDrag = null; });

  resize();
  setInterval(poll, 100);
})();

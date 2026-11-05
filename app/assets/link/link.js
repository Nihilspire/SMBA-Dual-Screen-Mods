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
  var LINK = !!(window.Native && Native.link);    // Super Mario Advance Link: the game runs in RetroArch (linkapp/); its Native answers Dual's calls
  var cv = document.getElementById('c'), ctx = cv.getContext('2d');
  var W = 240, H = 176, layout = {}, tabs = [], tab = 'map', buttons = [];
  var romOk = false, meta = null, ram = null, lvl = null, video = null, trk = null, tickN = 0;
  var areaCv = null, areaKey = '';
  var lastLvl = -99, lastVid = -99, lastTrk = -99, lastPos = '', still = 0;
  var maxHearts = 2, heartsLevel = -1;
  var store = window.localStorage || { getItem: function () { return null; }, setItem: function () {} };
  var opts = load('sma.opts', { xray: true, markers: true, zoom: 'near', tab3d: false });
  // Link 0.2: the 3D tab starts off, also for an install that had switched it on in 0.1
  if (LINK && !opts.tabOff02) { opts.tab3d = false; opts.tabOff02 = true; save('sma.opts', opts); }
  // 0.14: map zoom = one of four levels, tapped through (WIDE, FIT, NEAR, 1:1); FIT shows the area's whole height.
  // (0.13's screen styles are gone; the game's own pause-panel windows are the one look.)
  if (!(opts.zl >= 0 && opts.zl <= 3)) opts.zl = 1;
  var C = { white: '#f8f8f8', cream: '#fbf4dc', parch: '#efe0b8', orange: '#c86410', brown: '#5a3818', dbrown: '#3a2008',
            red: '#e03828', dred: '#902010', green: '#40b030', dgreen: '#187018', blue: '#3070e0', lblue: '#88c8f8',
            grey: '#988870', navy: '#182850', night: '#0c1430', yellow: '#f8c800', pink: '#f0a8c0', black: '#000',
            edge: '#9a7048', line: '#d8b878', title: '#a82a1a', big: '#b42a18' };
  G.solid = true;      // each letter one colour, as in the game's windows

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
  // 0.35: which way the area runs, from its object list: objects are placed in world tiles, so one past row 14 means it runs
  // down and one past column 15 means it runs across. Right from the first frame, where the screen can't tell (the two
  // readings of the first page are the same, and the game's own flag 0300116F stays clear until the camera first moves down).
  // No area of more than one page has both. Null if none of its objects is past the first page (3 of the 100 such areas).
  function areaRuns() {
    var list = objList();
    for (var i = 0; i < list.length; i++) { if (list[i].y >= 15) return true; if (list[i].x >= 16) return false; }
    return null;
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
  window.onRom = function () { romOk = false; meta = null; charSpr = {}; logoCv = null; kitTried = 0; loadRom(); draw(); };
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
      layout.a = { x: 2, y: 2 }; layout.b = { x: 2, y: 2 + A_H + 1 }; layout.bh = B_H;
      layout.pane = { x: 121, y: 1, w: W - 122, h: H - tabH - 2 };
      tabs = ['map', 'chart', 'options'];
    } else if (H >= 300) {                // portrait: status across the top, two cards of one height
      layout.a = { x: 1, y: 2 }; layout.b = { x: 121, y: 2 }; layout.bh = A_H;
      layout.pane = { x: 0, y: A_H + 3, w: W, h: H - tabH - A_H - 4 };
      tabs = ['map', 'chart', 'options'];
    } else {                              // small: everything behind tabs
      layout.a = { x: 1, y: 2 }; layout.b = { x: 121, y: 2 }; layout.bh = A_H;
      layout.pane = { x: 0, y: 1, w: W, h: H - tabH - 2 };
      tabs = ['status', 'map', 'chart', 'options'];
      layout.small = true;
    }
    if (LINK && opts.tab3d) tabs.splice(tabs.length - 1, 0, '3d');
    if (tabs.indexOf(tab) < 0) tab = tabs[0];
    draw();
  }
  addEventListener('resize', resize);
  var A_W = 118, A_H = 92, B_H = 66;      // status cards: rows sized for the 16x16 item icons

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
      // 11 pages: the area's 10 at most, then subspace's
      if (tickN - lastLvl >= 5) { lastLvl = tickN; var lh = Native.mem('2000000:5280'); if (lh.length === 10560) lvl = S.hexBytes(lh); }
      if (tickN - lastVid >= 10 && (S.playing(ram) || S.extraPage(ram))) {   // 0.34: not the Subspace timer: it is 0 in a pot, which left no map
        lastVid = tickN;
        var v = Native.video ? Native.video() : '';
        if (v) { var nv = G.b64(v); if (nv.length >= 0x18860) video = nv; }
        grabPortrait(); grabLive();
        updateGeo(); markFar();
      }
      updateGeo();
    }
    if (ram && tickN - lastTrk >= 5 && Native.tracker) {
      lastTrk = tickN;
      var t = G.b64(Native.tracker());
      if (t.length >= 28) trk = { time: u32(t, 0), deaths: u32(t, 4), cleared: u32(t, 8), whoLo: u32(t, 12), whoHi: u32(t, 16), warped: u32(t, 20) };
    }
    readEnding();
    draw();
  }
  function cleared(L) { return trk && ((trk.cleared >>> L) & 1); }
  function clearedBy(L) { var b = L * 2, w = b < 32 ? trk.whoLo : trk.whoHi; return (w >>> (b & 31)) & 3; }
  // 0.40: levels the file calls cleared but the tracker can't name (before 0.40 a resume or a state load between a level's end
  // and the next level lost the name). The file's own count per character can still tell, if only one character has more
  // clears than the tracker can place and enough to cover them all. -1 if not.
  function unnamedBy() {
    var tally = [0, 0, 0, 0], n = 0, who = -1;
    for (var L = 0; L < 20; L++) if (S.cleared(ram, L)) { if (cleared(L)) tally[clearedBy(L)]++; else n++; }
    for (var c = 0; c < 4; c++) if (S.charClears(ram, c) > tally[c]) { if (who >= 0) return -1; who = c; }
    return who >= 0 && S.charClears(ram, who) - tally[who] >= n ? who : -1;
  }
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
    var c = palRgb(0), pot = ram && S.pot(ram);          // 0.34: in a pot colour 0 is left over from outside; its picture is the sky
    if (!pot && c[0] + c[1] + c[2] > 690) return [184, 228, 248];
    if ((!pot && c[0] + c[1] + c[2] >= 150) || !video) return c;
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
  function portraitFor() {
    if (!ram) return null;
    if (S.charSelect(ram)) return portraits[S.cursorChar(ram) + 'b'] || null;
    return portraits[portraitKey()] || null;
  }
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
  // Which way the area runs and where it sits in the level buffer (0.22). The game's vertical flag (0300116F) is set
  // late after entering an area (1-1 opens with it clear while you fall down a vertical area), so, as in the 3D view,
  // the layout is the one whose level data matches BG0's tile map; until a picture settles it, a player below the first
  // 15 rows means vertical and one right of the first screen means horizontal. Subspace, and a pot with no area of its own,
  // are on the level buffer's last page, page 10 (the player is at x >= pages * 256 there), so the map shows just that page.
  var geo = { vert: false, pages: 1, page0: 0 }, layoutMemo = { key: '', vid: null, vert: null };
  function updateGeo() {
    var pages = S.pages(ram), px = S.px(ram), py = S.py(ram), flag = S.vertical(ram);
    if (px >> 8 >= pages && px >> 8 <= 10) { geo = { vert: false, pages: 1, page0: px >> 8 }; return; }
    var runs = areaRuns();
    if (runs != null) { geo = { vert: runs, pages: pages, page0: 0 }; return; }
    var key = S.level(ram) + '/' + S.area(ram) + '/' + pages;
    if (layoutMemo.key !== key) layoutMemo = { key: key, vid: null, vert: null };
    if (video && lvl && meta && layoutMemo.vid !== video && S.playing(ram)) {
      layoutMemo.vid = video;
      var m = matchLayout(pages); if (m != null) layoutMemo.vert = m;
    }
    var vert = layoutMemo.vert != null ? layoutMemo.vert : px >= 256 ? false : py >= 256 ? true : flag;
    geo = { vert: vert, pages: pages, page0: 0 };
  }
  /** true / false when one layout clearly matches BG0 (90% of sampled cells, and better than the other), else null. */
  function matchLayout(pages) {
    var cnt = vu16(V_IO + 8), w = cnt & 0x4000 ? 512 : 256, h = cnt & 0x8000 ? 512 : 256;
    var hx = vu16(V_IO + 0x10) & 511, vy = vu16(V_IO + 0x12) & 511, qm = S.quadMode(ram), best = [0, 0];
    for (var v = 0; v < 2; v++) {
      var maxX = v ? 256 : pages * 256, maxY = v ? pages * 240 : 240;
      for (var cx = hx % w - w; cx < maxX; cx += w) for (var cy = vy % h - h; cy < maxY; cy += h) {
        if (cx + 240 <= 0 || cy + 160 <= 0) continue;
        var ok = 0, n = 0;
        for (var sy = 12; sy < 160; sy += 20) for (var sx = 4; sx < 240; sx += 18) {
          var wx = cx + sx, wy = cy + sy, t = cellAt(!!v, pages, 0, wx >> 4, wy >> 4), q = t < 0 ? null : S.retile(t, qm, quad(t));
          if (!q) continue;
          n++;
          if ((bgEntry(cnt, w, h, hx + sx, vy + sy) & 0x3FF) === (q[((wy >> 3) & 1) * 2 + ((wx >> 3) & 1)] & 0x3FF)) ok++;
        }
        if (n >= 20) best[v] = Math.max(best[v], ok / n);
      }
    }
    var b = best[1] > best[0] ? 1 : 0;
    return best[b] >= 0.9 && best[b] - best[1 - b] > 0.02 ? b === 1 : null;
  }
  /** A text BG's map entry at a scrolled pixel (cnt = its BGxCNT). */
  function bgEntry(cnt, w, h, x, y) {
    var wx = x % w, wy = y % h, tx = wx >> 3, ty = wy >> 3, blk = (cnt >> 8) & 31;
    if (tx >= 32) { blk += 1; tx -= 32; }
    if (ty >= 32) { blk += w === 512 ? 2 : 1; ty -= 32; }
    return vu16(blk * 0x800 + (ty * 32 + tx) * 2);
  }
  function cellAt(vert, pages, page0, c, r) {
    if (!lvl || c < 0 || r < 0) return -1;
    var idx;
    if (vert) { if (c > 15 || r >= pages * 15) return -1; idx = r * 16 + c; }
    else { if (r > 14 || c >= pages * 16) return -1; idx = (page0 + (c >> 4)) * 240 + r * 16 + (c & 15); }
    return idx * 2 + 1 < lvl.length ? lvl[idx * 2] | lvl[idx * 2 + 1] << 8 : -1;
  }
  function cellId(c, r) { return cellAt(geo.vert, geo.pages, geo.page0, c, r); }
  function areaSize() { return geo.vert ? [16, geo.pages * 15] : [geo.pages * 16, 15]; }
  // 0.37: the game's own background behind the map: its far layers (BG2 under BG1) over the sky, at the same scale as the level.
  // The layers scroll slower than the level, so there is no one place for them in the area: they tile at the level's scale. Their
  // maps are a ring the game streams into as the camera moves, so, as in the 3D view (Scene3D.farCol), only columns that have been
  // on screen are good and the rest take the column half a ring over; a sparse layer (the clouds) leaves those empty rather than
  // cut shapes off. In a vertical area it is the rows that stream. A pot's BG1 is one picture lying under the pot's outline, so it
  // keeps its place, with black round it. With no far layers on (Subspace) the map keeps the plain sky.
  var farMem = { key: '', n: 0 }, farBg = { key: '', data: null };
  function farLayers() {
    var disp = vu16(V_IO), out = [];
    [1, 2].forEach(function (b) {
      if (!(disp & (0x100 << b))) return;
      var cnt = vu16(V_IO + 8 + b * 2);
      if (cnt & 0x80) return;
      out.push({ b: b, cnt: cnt, pri: cnt & 3, cb: (cnt >> 2) & 3, w: cnt & 0x4000 ? 512 : 256, h: cnt & 0x8000 ? 512 : 256,
                 hx: vu16(V_IO + 0x10 + b * 4) & 511, vy: vu16(V_IO + 0x12 + b * 4) & 511 });
    });
    return out.sort(function (a, c) { return c.pri - a.pri || c.b - a.b; });   // back first
  }
  function markFar() {
    var cam = camera(), rx = cam[0] - geo.page0 * 256, ry = cam[1];
    var key = S.level(ram) + '/' + S.areaNo(ram) + '/' + S.area(ram) + '/' + geo.vert + '/' + geo.page0;
    if (farMem.key !== key) farMem = { key: key, n: 0, cols: {}, rows: {}, dx: {}, dy: {}, ry: ry };
    farLayers().forEach(function (l) {
      var nc = l.w >> 3, nr = l.h >> 3, cols = farMem.cols[l.b] || (farMem.cols[l.b] = new Uint8Array(nc)), rows = farMem.rows[l.b] || (farMem.rows[l.b] = new Uint8Array(nr)), k;
      for (k = l.hx >> 3; k <= (l.hx + 239 + (geo.vert ? 0 : 16)) >> 3; k++) if (!cols[k & (nc - 1)]) { cols[k & (nc - 1)] = 1; farMem.n++; }
      for (k = l.vy >> 3; k <= (l.vy + 159) >> 3; k++) if (!rows[k & (nr - 1)]) { rows[k & (nr - 1)] = 1; farMem.n++; }
      // A scrolling layer keeps the place it had when first seen. A pot's picture lies under the outline, so it follows the scroll
      // every time: the first look can come while the screen is still changing over, which left it a page off with black beside it.
      if (farMem.dy[l.b] == null || (S.pot(ram) && (farMem.dy[l.b] !== l.vy - ry || farMem.dx[l.b] !== l.hx - rx))) {
        farMem.dy[l.b] = l.vy - ry; farMem.dx[l.b] = l.hx - rx; farMem.n++;
      }
    });
  }
  function tileEmpty(cb, t) {
    var o = cb * 0x4000 + t * 32; if (o + 32 > 0x10000) return true;
    for (var i = 0; i < 32; i++) if (video[o + i]) return false;
    return true;
  }
  function farSparse(l) {                                 // BG2 across a horizontal area, unless it fills the screen (7-1's sky)
    if (l.b !== 2 || geo.vert) return false;
    var n = 0, full = 0;
    for (var sy = 4; sy < 160; sy += 16) for (var sx = 4; sx < 240; sx += 16) { n++; if (!tileEmpty(l.cb, bgEntry(l.cnt, l.w, l.h, l.hx + sx, l.vy + sy) & 0x3FF)) full++; }
    return full * 10 < n * 9;
  }
  /** The game's sky gradient (palette colour 0, rewritten by scanline from a table), as 160 rows, or null if it's flat. */
  var SKY_ROWS = [0, 8, 16, 24, 32, 38, 44, 50, 56, 64, 68, 72, 76, 80, 84, 87];
  function skyGradient() {
    var hex = Native.mem(S.SKY_TABLE.toString(16) + ':32'); if (hex.length !== 64) return null;
    var t = S.hexBytes(hex), e = function (i) { return t[i * 2] | t[i * 2 + 1] << 8; }, flat = true;
    for (var i = 1; i < 16; i++) if (e(i) !== e(0)) flat = false;
    if (flat || e(15) !== vu16(V_PAL)) return null;
    var out = [];
    for (var k = 0; k < 16; k++) {
      var c = e(k), r = c & 31, g = (c >> 5) & 31, b = (c >> 10) & 31, rgb = [r << 3 | r >> 2, g << 3 | g >> 2, b << 3 | b >> 2];
      for (var y = SKY_ROWS[k]; y < (k < 15 ? SKY_ROWS[k + 1] : 160); y++) out[y] = rgb;
    }
    return out;
  }
  function farData(w, h) {
    if (!farMem.cols) return null;
    var layers = farLayers(); if (!layers.length) return null;
    var pal = 0, j; for (j = 0; j < 0x200; j++) pal = (pal * 31 + video[V_PAL + j]) | 0;
    var key = farMem.key + '/' + w + 'x' + h + '/' + farMem.n + '/' + pal;
    if (farBg.key === key) return farBg.data;
    var d = new Uint8ClampedArray(w * h * 4), pot = S.pot(ram), c0 = palRgb(0), base = pot ? [0, 0, 0] : c0[0] + c0[1] + c0[2] > 690 ? c0 : skyRgb(), grad = pot ? null : skyGradient();
    // the gradient is locked to the screen: across an area it can stand where the sky is, but down a tall one it has no place
    var flat = grad && geo.vert ? grad[80] : null;
    for (var y = 0; y < h; y++) {
      var rgb = flat || (grad ? grad[Math.max(0, Math.min(159, y - farMem.ry))] : base);
      for (var o = y * w * 4, e4 = o + w * 4; o < e4; o += 4) { d[o] = rgb[0]; d[o + 1] = rgb[1]; d[o + 2] = rgb[2]; d[o + 3] = 255; }
    }
    var cols256 = []; for (j = 0; j < 256; j++) cols256.push(palRgb(j));
    layers.forEach(function (l) {
      var nc = l.w >> 3, nr = l.h >> 3, seenC = farMem.cols[l.b], seenR = farMem.rows[l.b]; if (!seenC) return;
      var dy = farMem.dy[l.b], dx = pot ? farMem.dx[l.b] : 0, sparse = farSparse(l), last = Math.max(27, (l.vy + 159) >> 3), vert = geo.vert;
      var scr0 = l.vy >> 3, scr1 = (l.vy + 159) >> 3;
      var colOf = function (tx) {
        if (pot) return tx;
        var c = tx & (nc - 1), f = vert ? 30 : 32, o;
        if (seenC[c]) return c;
        if (sparse) return -1;
        o = (c + f) & (nc - 1); if (seenC[o]) return o;
        o = (c - f) & (nc - 1); if (seenC[o]) return o;
        o = (c + 30) & (nc - 1); if (seenC[o]) return o;
        o = (c - 30) & (nc - 1); return seenC[o] ? o : -1;
      };
      var rowOf = function (tr, col) {
        if (pot) return tr;
        if (vert) {
          var r = tr & (nr - 1);
          for (var k = 0; k < 9; k++) { var q = (r + k * 20) & (nr - 1); if (seenR[q]) return q; }
          return -1;
        }
        if (tr > last) return last - 3 + (tr - last - 1) % 4;
        if (tr >= 0) return tr;
        return tileEmpty(l.cb, bgEntry(l.cnt, l.w, l.h, col * 8, 0) & 0x3FF) ? -1 : ((tr % 8) + 8) % 8;
      };
      var offY = pot || !vert ? dy : 0;
      var tr0 = pot ? 0 : vert ? 0 : Math.floor(offY / 8), tr1 = pot ? 27 : Math.floor((h - 1 + offY) / 8);
      var tx1 = pot ? 31 : vert ? (w - 1) >> 3 : Math.min((w - 1) >> 3, 63);            // across an area it repeats: copied below
      for (var tr = tr0; tr <= tr1; tr++) for (var tx = 0; tx <= tx1; tx++) {
        var col = colOf(tx), row = col < 0 ? -1 : rowOf(tr, col); if (row < 0) continue;
        var e = bgEntry(l.cnt, l.w, l.h, col * 8, row * 8), bo = l.cb * 0x4000 + (e & 0x3FF) * 32;
        if (bo + 32 > 0x10000) continue;
        if (!pot && (e === 0xA0 || e === 0xA1) && (row < scr0 || row > scr1)) continue;   // the game's fill: never written
        var mx = tx * 8 - dx, my = tr * 8 - offY, hf = e & 0x400, vf = e & 0x800, pl = (e >> 12) * 16;
        for (var yy = 0; yy < 8; yy++) {
          var py = my + yy; if (py < 0 || py >= h) continue;
          var sy = vf ? 7 - yy : yy;
          for (var xx = 0; xx < 8; xx++) {
            var px = mx + xx; if (px < 0 || px >= w) continue;
            var sx = hf ? 7 - xx : xx, b = video[bo + sy * 4 + (sx >> 1)], v = (sx & 1) ? b >> 4 : b & 15;
            if (!v) continue;
            var c = cols256[pl + v], q = (py * w + px) * 4;
            d[q] = c[0]; d[q + 1] = c[1]; d[q + 2] = c[2];
          }
        }
      }
    });
    if (!pot && !geo.vert && w > 512) for (var ry = 0; ry < h; ry++) {
      var r0 = ry * w * 4;
      for (var rx = 512; rx < w; rx += 512) d.copyWithin(r0 + rx * 4, r0, r0 + Math.min(512, w - rx) * 4);
    }
    farBg = { key: key, data: d, w: w, h: h, top: flat || (grad ? grad[0] : base), c: null, sc: 0 };
    return d;
  }
  /** The background alone at a map scale, to carry on past the area's sides when the pane is wider than it (cached per build). */
  function farStrip(sc) {
    if (!farBg.data) return null;
    if (farBg.c && farBg.sc === sc) return farBg.c;
    var c = document.createElement('canvas'); c.width = farBg.w; c.height = farBg.h;
    c.getContext('2d').putImageData(new ImageData(farBg.data, farBg.w, farBg.h), 0, 0);
    farBg.c = sc === 1 ? c : resample(c, sc); farBg.sc = sc;
    return farBg.c;
  }
  function buildArea() {
    if (!lvl || !video || !meta) return;
    var sz = areaSize(), sum = 0;
    for (var i = 0; i < sz[0] * sz[1] * 2 && i < lvl.length; i += 3) sum = (sum * 31 + lvl[i]) | 0;
    var qm = S.quadMode(ram);
    var key = S.level(ram) + '/' + S.area(ram) + '/' + sz + '/' + geo.page0 + '/' + sum + '/' + lastVidKey() + '/' + qm + '/' + farMem.n;
    if (key === areaKey && areaCv) return;
    areaKey = key;
    var w = sz[0] * 16, h = sz[1] * 16;
    areaCv = document.createElement('canvas'); areaCv.width = w; areaCv.height = h;
    var x2 = areaCv.getContext('2d'), img = x2.createImageData(w, h), d = img.data;
    var cb = (vu16(V_IO + 8) >> 2) & 3, bgc = skyRgb(), far = window.STANDIN ? null : farData(w, h);
    if (far) d.set(far);
    else for (var k = 0; k < w * h; k++) { d[k * 4] = bgc[0]; d[k * 4 + 1] = bgc[1]; d[k * 4 + 2] = bgc[2]; d[k * 4 + 3] = 255; }
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
    gl3d = null;
    drawPage();
    if (LINK) sync3d();
  }
  function drawPage() {
    loadKit(); applyTheme();
    if (!ram || !romOk) { ctx.fillStyle = C.night; ctx.fillRect(0, 0, W, H); } else backdrop();
    buttons = []; mapBox = null; optPane = null;
    if (!ram || !romOk) { if (LINK) linkIdle(); else idle(); return; }
    if (endPreview || S.ending(ram)) { endingCard(); return; }
    var menus = S.gameMode(ram) !== 3 && S.gameMode(ram) !== 4, quiet = quietNow();
    if (quietOpen && (!quiet || (menus && tab !== 'options'))) {   // the game moved on, or on the menus another tab was picked: back to the logo
      quietOpen = false;
      if (!quiet && tab === 'options') tab = quietTab;
    }
    if (quiet && !quietOpen) { quietScreen(); return; }
    if (S.gameMode(ram) === 4) { marioBros(); drawTabs(); return; }
    if (S.bonusChance(ram)) { bonusChance(); drawTabs(); return; }
    if (!S.inLevel(ram) && !trk) { idle('TITLE SCREEN', 'PICK A FILE AND PLAY'); return; }
    var wide3d = tab === '3d' && W >= 300;                 // beside the status column there'd be little left: the view takes the width
    if ((!layout.small && !wide3d) || tab === 'status') { blockA(layout.a.x, layout.a.y); blockB(layout.b.x, layout.b.y, layout.bh); }
    if (tab !== 'status') {
      var p = layout.pane;
      if (tab === 'map') drawMap(p);
      else if (tab === 'chart') drawChart(p);
      else if (tab === 'options') drawOptions(p);
      else if (tab === '3d') draw3dTab();
    }
    drawTabs();
  }

  // ------------------------------------------------------------------ Link only: the setup screen and the 3D tab
  // Dual draws the 3D view on the game's own screen. Link's game screen is RetroArch's, which it can't draw on, so the view
  // comes here: a tab, off until it's switched on in OPTIONS. The picture is a GL view the app lays over this part of the
  // page (LinkActivity.Bridge.view3d, Dual's Scene3D and Renderer3D fed from RetroArch's memory), with the cameras under it.
  // Tapping the picture steps the camera, as the map's zoom does. Away from play (a door, a pause) the last frame stays up.
  var gl3d = null, glShown = '', s3d = null, CAMS = ['low', 'tilt', 'diorama', 'far'];
  function settings3d() { if (!s3d) { try { s3d = JSON.parse(Native.settings3d()); } catch (e) { s3d = { cam3d: 'low', depth3d: 20, back3d: 'near' }; } } return s3d; }
  function set3d(k, v) { settings3d()[k] = v; Native.set3d(k, String(v)); }
  function nextCam() { set3d('cam3d', CAMS[(CAMS.indexOf(settings3d().cam3d) + 1) % CAMS.length]); }
  window.on3dTap = function () { nextCam(); draw(); };
  function draw3dTab() {
    var r = H >= 300 ? layout.pane : { x: 0, y: 0, w: W, h: layout.tabY - 1 }, barH = 14, s = settings3d();
    if (!S.inLevel(ram)) {                                 // character select with the tabs opened from the logo
      card(r.x + 4, r.y + 4, r.w - 8, r.h - 8);
      G.textC(ctx, '3D VIEW', r.x + r.w / 2, r.y + r.h / 2 - 12, C.title);
      G.textC(ctx, 'SHOWS DURING A LEVEL', r.x + r.w / 2, r.y + r.h / 2 + 2, T.muted);
      return;
    }
    gl3d = { x: r.x, y: r.y, w: r.w, h: r.h - barH };
    var by = r.y + r.h - barH, bw = Math.floor(r.w / CAMS.length);
    ctx.fillStyle = C.edge; ctx.fillRect(r.x, by, r.w, barH);
    CAMS.forEach(function (c, i) {
      var x = r.x + i * bw, on = s.cam3d === c;
      rrect(x + 1, by + 1, bw - 2, barH - 1, on ? C.cream : C.parch);
      if (on) { ctx.fillStyle = themed ? C.orange : C.blue; ctx.fillRect(x + 2, by + 1, bw - 4, 2); }
      G.textC(ctx, c.toUpperCase(), x + bw / 2, by + 4, on ? C.dbrown : C.brown);
      buttons.push({ x: x, y: by, w: bw, h: barH, fn: function () { set3d('cam3d', c); } });
    });
  }
  function sync3d() {
    var k = gl3d ? [gl3d.x / W, gl3d.y / H, gl3d.w / W, gl3d.h / H].join() : '';
    if (k === glShown) return;
    glShown = k;
    if (gl3d) Native.view3d(true, gl3d.x / W, gl3d.y / H, gl3d.w / W, gl3d.h / H); else Native.view3d(false, 0, 0, 0, 0);
  }
  var RA_WHY = { norom: ['CHOOSE YOUR ROM', 'FOR THE MAP AND THE ART'], badrom: ['THAT ROM IS NOT THIS GAME', 'NEEDS THE USA, EUROPE ROM'],
                 noreply: ['NO REPLY FROM RETROARCH', ''], nogame: ['RETROARCH: NO GAME RUNNING', 'LOAD SUPER MARIO ADVANCE'],
                 wrong: ['RETROARCH RUNS ANOTHER GAME', 'LOAD SUPER MARIO ADVANCE'], nomap: ['THIS CORE HAS NO MEMORY MAP', 'USE THE MGBA CORE'],
                 '': ['WAITING FOR THE GAME', ''] };
  function linkIdle() {
    var p = window.Native && Native.problem ? Native.problem() : '', why = RA_WHY[p] || RA_WHY[''], cy = Math.max(3, Math.floor(H / 2) - 86);
    starfield();
    G.textC(ctx, 'SUPER MARIO ADVANCE', W / 2, cy, C.yellow);
    G.textC(ctx, 'LINK FOR RETROARCH', W / 2, cy + 10, C.lblue);
    G.textC(ctx, why[0], W / 2, cy + 26, p ? C.red : C.white);
    if (why[1]) G.textC(ctx, why[1], W / 2, cy + 36, C.grey);
    var x = Math.max(2, Math.floor((W - 27 * 8) / 2)), y = cy + 50;
    [['USE RETROARCH NIGHTLY', C.yellow], ['1 SETTINGS > NETWORK >', C.white], ['  NETWORK COMMANDS: ON', C.white], ['2 COMMAND PORT: 55355', C.white],
     ['3 CORE: MGBA', C.white], ['4 LOAD SUPER MARIO ADVANCE', C.white]].forEach(function (l) { G.text(ctx, l[0], x, y, l[1]); y += 10; });
    var bw = Math.min(150, W - 16), bh = 18, rom = !!(Native.romName && Native.romName());
    button(rom ? 'CHOOSE ANOTHER ROM' : 'CHOOSE ROM', Math.floor((W - bw) / 2), Math.min(H - bh - 12, y + 3), bw, bh, !rom, function () { Native.pickRom(); });
    var v = Native.raVersion ? Native.raVersion() : '';
    if (v) G.textC(ctx, 'RETROARCH ' + v.substr(0, 16), W / 2, H - 9, C.grey);
  }

  // 0.34: while the game is on its own menus (the title, Choose a Game, the file screens) there is nothing to show here, so
  // the screen goes quiet: the game's logo, dimmed and centred, as Xenogears Dual does in cutscenes. Tapping it opens the
  // OPTIONS tab there too (the save states), which is how Xenogears Dual's logo opens its settings. The logo comes from the
  // ROM, so it is there from the first start.
  // 0.42: on the app's own look rather than black, as Xenogears keeps its background: the backdrop and a window, dimmed. No tabs,
  // as Xenogears drops its bars here.
  // 0.41: character select and the screens from there into a level too (the Yoshi Challenge's and the level's card, then the
  // level's first frames, sub-mode 1 state 0). A door or a lost life runs state 0 as well, so that only counts straight after
  // these screens. There's a file loaded here, so after the tap every tab works, until the level starts.
  var logoCv = null, quietOpen = false, quietTab = null, quietLoad = false;
  function quietNow() {
    var mode = S.gameMode(ram), sub = S.subMode(ram);
    if (mode !== 3) { quietLoad = false; return mode !== 4; }
    if (sub === 1) { if (S.mainState(ram) !== 0) quietLoad = false; return quietLoad; }
    if (sub === 2 || sub === 4) { quietLoad = false; return false; }    // Bonus Chance and the ending have their own screens
    return quietLoad = true;
  }
  function logoImage() {
    if (logoCv || !romOk || window.STANDIN) return logoCv;
    var tiles = G.b64(Native.rom(S.LOGO_TILES, S.LOGO_TILES_LEN)), map = G.b64(Native.rom(S.LOGO_MAP, S.LOGO_MAP_LEN)), pal = G.b64(Native.rom(S.LOGO_PAL, 512));
    if (tiles.length !== S.LOGO_TILES_LEN || map.length !== S.LOGO_MAP_LEN || pal.length !== 512) return null;
    var box = S.LOGO_BOX, c = document.createElement('canvas'); c.width = box[2]; c.height = box[3];
    var x2 = c.getContext('2d'), img = x2.createImageData(box[2], box[3]), p = img.data;
    for (var y = 0; y < box[3]; y++) for (var x = 0; x < box[2]; x++) {
      var mx = x + box[0], my = y + box[1], o = ((my >> 3) * 32 + (mx >> 3)) * 2, e = map[o] | map[o + 1] << 8;
      var ix = e & 0x400 ? 7 - (mx & 7) : mx & 7, iy = e & 0x800 ? 7 - (my & 7) : my & 7, v = tiles[(e & 0x3FF) * 64 + iy * 8 + ix];
      if (!v) continue;
      var col = pal[v * 2] | pal[v * 2 + 1] << 8, q = (y * box[2] + x) * 4, r = col & 31, g = (col >> 5) & 31, b = (col >> 10) & 31;
      p[q] = r << 3 | r >> 2; p[q + 1] = g << 3 | g >> 2; p[q + 2] = b << 3 | b >> 2; p[q + 3] = 255;
    }
    x2.putImageData(img, 0, 0);
    return logoCv = c;
  }
  function quietScreen() {
    card(4, 4, W - 8, H - 8);                             // on the backdrop draw() has just painted
    var im = logoImage();
    if (im) ctx.drawImage(im, Math.floor((W - im.width) / 2), Math.floor((H - im.height) / 2));
    else {                                                // a stand-in preview: no game art
      G.textC(ctx, 'SUPER MARIO', W / 2, H / 2 - 18, C.red, 2);
      G.textC(ctx, 'ADVANCE', W / 2, H / 2, C.yellow, 2);
    }
    ctx.fillStyle = 'rgba(0,0,0,0.6)'; ctx.fillRect(0, 0, W, H);   // the logo isn't see-through: the page dims as one
    buttons.push({ x: 0, y: 0, w: W, h: H, fn: function () { quietOpen = true; quietTab = tab; tab = 'options'; optScroll = 0; } });
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

  function rrect(x, y, w, h, col) { ctx.fillStyle = col; ctx.fillRect(x + 1, y, w - 2, h); ctx.fillRect(x, y + 1, w, h - 2); }
  // 0.31: the windows are the game's own: the pause panel's pieces (scalloped edge, corner pins) from the first-start
  // capture, as the pause menu uses, and its palette for the words (see applyTheme). They were flat cream cards with a brown
  // line, close to the game but not it. x, y, w, h is the panel's body; the pins stick out 3 px, and the scallops reach
  // 6 px in, so content keeps inside that. Until the capture is there: the drawn card in the same place.
  var panels = {};
  function card(x, y, w, h) {
    if (K.ok && K.piece) {
      var key = w + 'x' + h, c = panels[key];
      if (!c) {
        c = panels[key] = document.createElement('canvas'); c.width = w + 6; c.height = h + 6;
        K.card(c.getContext('2d'), 0, 0, w + 6, h + 6);
      }
      ctx.drawImage(c, x - 3, y - 3);
      return;
    }
    rrect(x, y + 1, w + 2, h + 2, 'rgba(40,24,8,0.18)');
    rrect(x - 1, y - 1, w + 2, h + 2, C.edge);
    rrect(x, y, w, h, C.cream);
  }
  // section header: words with a thin line under them
  function ribbon(t, x, y) {
    G.text(ctx, t, x, y, C.title);
    ctx.fillStyle = C.line; ctx.fillRect(x, y + 9, t.length * 8, 1);
  }
  // blend a colour toward the card's cream (k = how much of the colour stays)
  function soft(col, k) {
    var n = parseInt(col.slice(1), 16), c = [n >> 16, (n >> 8) & 255, n & 255], m = parseInt(C.cream.slice(1), 16), b = [m >> 16, (m >> 8) & 255, m & 255];
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
  // The pause panel's own colours for the words and the bits around them. Its words are one solid orange (ef9452, tiles
  // 779-810); that is too faint for small print, so labels take the panel's deep orange (ad5210, 5:1 on its cream) and values
  // its darkest (943900, 7:1), with the plain orange for big titles. They were red, blue and green labels with a darker
  // second tone in each letter (gbagfx's shade), which the game's windows never have: see G.solid.
  var themed = false;
  function applyTheme() {
    if (themed || !K.ok || !K.colors) return;
    themed = true;
    var k = K.colors;
    C.cream = k.cream; C.parch = k.parch; C.orange = k.orange; C.brown = k.brown; C.dbrown = k.dark;
    C.edge = k.brown; C.line = k.light; C.title = k.brown; C.big = k.orange;
    T.muted = k.brown; T.blue = k.dark; T.gold = k.dark;
  }
  function backdrop() {
    if (K.ok) { K.backdrop(ctx, W, H); return; }
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
  // alpha < 1 = an empty slot (cherry / Ace Coin / egg not taken yet): the real icon, dimmed. 0.31; a tan silhouette before
  // that lost what made the cherry a cherry.
  function icon(name, x, y, alpha, fallback) {
    if (!drawIcon(name, x, y, 16, alpha)) { if (fallback) fallback(); return false; } return true;
  }
  function blockA(x, y) {
    card(x, y, A_W, A_H);
    var lv = S.level(ram), name = lv <= 19 ? S.LEVELS[lv] : '-', rx = x + A_W - 8;
    var ch = S.charSelect(ram) ? S.cursorChar(ram) : S.character(ram), band = soft(S.CHAR_COL[ch], 0.3);   // 0.23: follows the select cursor
    rrect(x + 5, y + 5, A_W - 10, 11, band);
    G.text(ctx, 'WORLD', x + 8, y + 7, C.brown); G.text(ctx, name, x + 50, y + 7, C.dbrown);
    var where = S.subspace(ram) ? 'SUB' : 'A' + (S.areaNo(ram) + 1);
    G.textR(ctx, where, rx, y + 7, C.brown);
    var pic = portraitFor();
    if (pic) ctx.drawImage(pic, x + 3, y + 16); else portraitHolder(x + 7, y + 20);
    G.text(ctx, S.CHARS[ch], x + 40, y + 18, CHAR_TEXT[ch]);
    G.textR(ctx, 'x' + S.lives(ram), rx, y + 18, C.brown);
    var hp = S.health(ram);                                                  // the life meter's own hearts
    for (var i = 0; i < maxHearts; i++) (function (i) {
      icon(i < hp ? 'heartFull' : 'heartEmpty', x + 37 + i * 12, y + 27, 1, function () { heart(x + 40 + i * 10, y + 31, i < hp); });
    })(i);
    G.text(ctx, pad(S.score(ram), 8), x + 40, y + 45, C.dbrown);
    var ch2 = Math.min(5, S.cherries(ram));                                  // cherries toward the star: 5 slots
    for (var k = 0; k < 5; k++) (function (k) {
      icon('cherry', x + 5 + k * 13, y + 55, k < ch2 ? 1 : DIM, function () { cherry(x + 8 + k * 10, y + 60, k < ch2); });
    })(k);
    if (icon('coin', x + 71, y + 55)) G.text(ctx, 'x' + S.coins(ram), x + 87, y + 60, C.brown);
    else G.text(ctx, 'C' + S.coins(ram), x + 73, y + 60, C.brown);
    var ace = lv <= 19 ? S.aceLevel(ram) | S.aceSaved(ram, lv) : 0;      // this visit, or already on the file
    for (var a = 0; a < 5; a++) (function (a) {
      var got = (ace >> a) & 1;
      icon('ace', x + 5 + a * 15, y + 72, got ? 1 : DIM, function () { coin(x + 8 + a * 10, y + 76, got, true); });
    })(a);
    if (S.yoshi(ram) && lv <= 19) {                                            // Yoshi Challenge: the level's two eggs
      var ev = S.eggsVisit(ram), ef = S.eggsFile(ram, lv);
      for (var e = 0; e < 2; e++) (function (e) {
        var got = ((ev | ef) >> e) & 1;                                         // this visit, or already on the file
        icon('egg', x + 81 + e * 16, y + 72, got ? 1 : DIM, function () {
          ctx.fillStyle = got ? C.green : '#d8c8a0'; ctx.fillRect(x + 85 + e * 12, y + 73, 7, 9);
        });
      })(e);
    }
  }
  var DIM = 0.32;                         // an empty slot's icon
  function blockB(x, y, h) {
    card(x, y, A_W, h);
    var lx = x + 7, rx = x + A_W - 7, top = y + Math.floor((h - B_H) / 2);
    var timers = [['STAR', S.star(ram), '#d8a000', 'star', T.gold], ['WATCH', S.stopwatch(ram), C.blue, 'watch', T.blue], ['SUB', S.subspace(ram), C.dred, 'potion', C.dred]];
    var active = timers.filter(function (t) { return t[1] > 0; });
    if (active.length) active.slice(0, 2).forEach(function (a, i) {
      var tx = x + 5 + i * 55;
      if (!icon(a[3], tx, top + 5)) G.text(ctx, a[0][0], tx + 4, top + 9, a[4]);
      bar(tx + 18, top + 9, 34, a[1] / 255, a[2]);
    });
    else {                                                                   // 0.23: the player's state when no timer runs
      var what = S.gameOver(ram) ? ['GAME OVER', T.red] : S.dying(ram) ? ['OUCH', T.red] : S.charge(ram) >= 60 ? ['POWER JUMP', T.gold]
        : S.holding(ram) ? ['CARRYING', T.muted] : ['READY', T.muted];
      G.text(ctx, what[0], lx, top + 9, what[1]);
    }
    var yy = top + 24, secs = trk ? trk.time / 59.7275 : 0;
    G.text(ctx, 'TIME', lx, yy, C.brown); G.textR(ctx, hms(secs), rx, yy, C.dbrown);
    G.text(ctx, 'DEATHS', lx, yy + 10, C.brown); G.textR(ctx, String(trk ? trk.deaths : 0), rx, yy + 10, C.dbrown);
    if (S.yoshi(ram)) { G.text(ctx, 'EGGS', lx, yy + 20, C.brown); G.textR(ctx, S.eggTotal(ram) + '/40', rx, yy + 20, C.dbrown); }
    else { G.text(ctx, 'LEVELS', lx, yy + 20, C.brown); G.textR(ctx, S.clearedCount(ram) + '/20', rx, yy + 20, C.dbrown); }
    G.text(ctx, S.aceTotal(ram) >= 100 ? 'ACES' : 'ACE COINS', lx, yy + 30, C.brown); G.textR(ctx, S.aceTotal(ram) + '%', rx, yy + 30, C.dbrown);
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
    var inner = { x: p.x + 7, y: p.y + 7, w: p.w - 14, h: p.h - 14 - gh };
    ctx.fillStyle = C.line;                                            // a thin frame, so the map sits in the window
    ctx.fillRect(inner.x - 1, inner.y - 1, inner.w + 2, 1); ctx.fillRect(inner.x - 1, inner.y + inner.h, inner.w + 2, 1);
    ctx.fillRect(inner.x - 1, inner.y, 1, inner.h); ctx.fillRect(inner.x + inner.w, inner.y, 1, inner.h);
    mapView(inner, {});
    guide(p, gh);
  }
  /** The live map in a rectangle (clipped). */
  function mapView(inner, o) {
    buildArea();
    mapBox = inner;
    if (!areaCv || !S.inLevel(ram)) { G.textC(ctx, 'NO MAP HERE', inner.x + inner.w / 2, inner.y + inner.h / 2 - 4, T.muted); return; }
    ctx.save(); ctx.beginPath(); ctx.rect(inner.x, inner.y, inner.w, inner.h); ctx.clip();
    var potHere = S.pot(ram), farHere = !window.STANDIN && farBg.top && farMem.cols && farBg.key.indexOf(farMem.key + '/') === 0 && farLayers().length > 0;
    var sk = potHere ? [0, 0, 0] : farHere ? farBg.top : skyRgb(); ctx.fillStyle = 'rgb(' + sk.join(',') + ')'; ctx.fillRect(inner.x, inner.y, inner.w, inner.h);
    var aw = areaCv.width, ah = areaCv.height, vert = geo.vert, bar = null;
    var px = S.px(ram) - geo.page0 * 256 + 8, py = S.py(ram) + 16;
    // Zoom levels (0.14): FIT = the area's whole height (whole width for vertical areas) fills the pane; WIDE shows 1.5x
    // more; NEAR is halfway to 1:1. Scales snap to whole pixels per 16 px cell; below 1 the map is an area-averaged copy.
    var fit = Math.min(1, vert ? inner.w / aw : inner.h / ah);
    // when FIT is already close to 1:1 (vertical areas are about one screen wide) the steps spread further out instead
    var L = fit > 0.7 ? [fit / 2.25, fit / 1.5, (fit / 1.5 + 1) / 2, 1] : [fit / 1.5, fit, (fit + 1) / 2, 1];
    var sc = Math.max(2, Math.floor(L[opts.zl] * 16)) / 16, fitSc = Math.max(2, Math.floor(fit * 16)) / 16;
    zoomName = sc === 1 ? '1:1' : sc === fitSc ? 'FIT' : Math.round(sc * 100) + '%';
    var spanX = inner.w / sc, spanY = inner.h / sc;
    var key = S.level(ram) + '/' + S.area(ram) + '/' + vert + '/' + geo.page0 + '/' + opts.zl;
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
    if (farHere && !potHere && aw * sc < inner.w) {       // past the area's sides the background carries on (a vertical area's repeats every 240 px)
      var strip = farStrip(sc), per = Math.round((vert ? 240 : 256) * sc);
      if (strip) {
        for (var bl = ox - per; bl + strip.width > inner.x; bl -= per) ctx.drawImage(strip, bl, oy);
        for (var br = ox + per; br < inner.x + inner.w; br += per) ctx.drawImage(strip, br, oy);
      }
    }
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
    var y = p.y + p.h - gh - 5;
    if (!S.inLevel(ram) || !lvl) return p.list ? [] : undefined;
    if (p.list && (S.subspace(ram) || !hasIcons())) return [];
    if (S.subspace(ram)) { G.text(ctx, 'SUBSPACE: PULL GRASS FOR COINS', p.x + 8, y + (gh - 8) / 2 + 1, C.brown); return; }
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
      if (!items.length) { G.text(ctx, 'NOTHING HIDDEN IN THIS AREA', p.x + 8, y + 6, C.brown); return; }
      var x = p.x + 8, xe = p.x + p.w - 8;
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
    var maxc = Math.floor((p.w - 16) / 8);
    G.text(ctx, msg.length > maxc ? msg.substr(0, maxc) : msg, p.x + 8, y + 2, C.brown);
  }

  // ------------------------------------------------------------------ chart tab
  // a colour per world, after its look (grass, desert, sky, ice, night, desert, clouds)
  var WORLD_COL = ['#30a030', '#d09030', '#2890e0', '#40b8d8', '#6048b0', '#c07028', '#e05880'];
  function drawChart(p) {
    card(p.x + 1, p.y + 1, p.w - 2, p.h - 2);
    // 0.35: who cleared a level is the character's own sprite, its head and shoulders cut to the row, instead of a coloured
    // line and a legend. The coins go under the level's number to make the room, and the legend's row goes to the sprites.
    var eggMode = S.yoshi(ram) || S.eggTotal(ram) > 0;
    var cur = S.level(ram), x0 = p.x + 9, y0 = p.y + 8, rowH = Math.max(14, Math.min(20, Math.floor((p.h - (eggMode ? 34 : 24)) / 7)));
    var colW = Math.floor((p.w - 40) / 3), sx = colW >= 58 ? 31 : 25, unnamed = unnamedBy();
    for (var w = 0; w < 7; w++) {
      var y = y0 + w * rowH;
      var wc = soft(WORLD_COL[w], 0.45); rrect(x0 - 2, y, 19, rowH - 3, wc); G.text(ctx, 'W' + (w + 1), x0, y + Math.floor((rowH - 11) / 2), C.dbrown);
      var n = w === 6 ? 2 : 3;
      for (var i = 0; i < n; i++) {
        var L = S.WORLD_START[w] + i, x = x0 + 22 + i * colW, on = L === cur && S.inLevel(ram);
        if (on) { rrect(x - 3, y - 1, colW - 1, rowH - 1, '#b8862e'); rrect(x - 2, y, colW - 3, rowH - 3, '#f4d27a'); }   // steady, not blinking
        G.text(ctx, S.LEVELS[L], x, y + 1, on ? C.dred : C.brown);
        var got = S.aceSaved(ram, L) | (on ? S.aceLevel(ram) : 0);           // every level's coins from the file's bits
        if (colW >= 58) for (var a = 0; a < 5; a++) coin(x + a * 6, y + 10, (got >> a) & 1, false);
        else for (var a2 = 0; a2 < 5; a2++) { ctx.fillStyle = (got >> a2) & 1 ? C.red : C.line; ctx.fillRect(x + a2 * 4, y + 11, 3, 3); }
        if (eggMode && colW >= 62) {                                            // the level's two Yoshi eggs (file + this visit), stacked
          var eg = S.eggsFile(ram, L) | (on ? S.eggsVisit(ram) : 0);
          for (var e = 0; e < 2; e++) { ctx.fillStyle = (eg >> e) & 1 ? C.green : '#d8c8a0'; ctx.fillRect(x + 50, y + 3 + e * 5, 4, 4); }
        }
        if (S.cleared(ram, L)) {
          var who = cleared(L) ? clearedBy(L) : unnamed, spr = who >= 0 ? charSprite(who, true, true) : null;
          if (spr) { var bh = Math.min(rowH - 2, 32 - BUST_TOP[who]); ctx.drawImage(spr, 0, BUST_TOP[who], 16, bh, x + sx, y - 1, 16, bh); }
          else { ctx.fillStyle = who >= 0 ? S.CHAR_COL[who] : C.dbrown; ctx.fillRect(x + sx + 1, y + 11, 13, 2); }   // cleared before the tracker knew who; no ROM yet
        }
        else if (trk && ((trk.warped >>> L) & 1)) { ctx.fillStyle = C.grey; ctx.fillRect(x + sx + 1, y + 11, 13, 1); }
      }
    }
    var fy = y0 + 7 * rowH + 1;
    G.text(ctx, (p.w >= 200 ? 'ACE COINS ' : 'ACES ') + S.aceTotal(ram) + '/100', x0, fy, C.brown);
    G.textR(ctx, S.clearedCount(ram) + '/20', p.x + p.w - 9, fy, C.dbrown);
    if (eggMode) G.textR(ctx, 'EGGS ' + S.eggTotal(ram) + '/40', p.x + p.w - 9, fy + 10, C.brown);
  }
  var BUST_TOP = [5, 0, 5, 1];                            // where each big frame's ink starts (Mario, Peach, Toad, Luigi)

  // ------------------------------------------------------------------ Mario Bros. Classic
  function marioBros() {
    var m = S.mb(ram), cx = W / 2, y = Math.max(8, H / 2 - 50);
    card(cx - 96, y, 192, 92);
    G.textC(ctx, 'MARIO BROS.', cx, y + 9, C.big, 2);
    G.text(ctx, 'PHASE', cx - 80, y + 32, C.brown); G.textR(ctx, String(m.round), cx + 80, y + 32, C.dbrown);
    G.text(ctx, 'ENEMIES LEFT', cx - 80, y + 44, C.brown); G.textR(ctx, String(m.left), cx + 80, y + 44, C.dbrown);
    G.text(ctx, 'SCORE', cx - 80, y + 56, C.brown); G.textR(ctx, pad(m.score, 6), cx + 80, y + 56, C.dbrown);
    G.text(ctx, 'LIVES', cx - 80, y + 68, C.brown); G.textR(ctx, String(m.lives), cx + 80, y + 68, C.dbrown);
  }

  // ------------------------------------------------------------------ Bonus Chance and the ending (0.23)
  function bonusChance() {
    var cx = W / 2, y = Math.max(8, H / 2 - 50), spins = S.coins(ram);
    card(cx - 108, y, 216, 80);
    G.textC(ctx, 'BONUS CHANCE', cx, y + 9, C.big, 2);
    G.text(ctx, 'SPINS LEFT', cx - 80, y + 32, C.brown); G.textR(ctx, String(spins), cx + 80, y + 32, C.dbrown);
    G.text(ctx, 'LIVES', cx - 80, y + 44, C.brown); G.textR(ctx, String(S.lives(ram)), cx + 80, y + 44, C.dbrown);
    var msg = S.mainState(ram) === 2 ? 'SPINNING' : spins ? 'PRESS A TO SPIN' : 'NO SPINS LEFT';
    G.textC(ctx, msg, cx, y + 62, T.muted);
  }
  // 0.28: the ending card (as Metroid Dual's mission report and Shantae Dual's QUEST COMPLETE). All through the ending
  // and credits (sub-mode 4, up to the wait on THE END) the whole Link screen shows the real date and time, the run's
  // stats and, for most of the screen, a field you sign with a finger. The stats are taken once, after the ending's init (stage 1) has set 7-2's
  // cleared bit. SAVE keeps the run with the signature as a small PNG in localStorage (sma.hall, last 20; nothing shows
  // the list yet). The playtest action opens the card without the ending (toggleEnding), leaving the game alone.
  var report = null, hall = load('sma.hall', []), sigBox = null, drawing = null, endPreview = false;
  window.toggleEnding = function () { endPreview = !endPreview; if (!endPreview) report = null; draw(); };
  function readEnding() {
    if (!ram) return;                                    // the game paused or stopping isn't the end of the ending
    if (!endPreview && !S.ending(ram)) { report = null; drawing = null; return; }
    if (report || !(endPreview || S.endStage(ram) >= 2) || (!trk && Native.tracker)) return;
    var now = new Date(), clears = [0, 1, 2, 3].map(function (c) { return S.charClears(ram, c); });
    report = { date: now.getFullYear() + '-' + pad(now.getMonth() + 1, 2) + '-' + pad(now.getDate(), 2),
               clock: pad(now.getHours(), 2) + ':' + pad(now.getMinutes(), 2), t: now.getTime(),
               secs: trk ? trk.time / 59.7275 : 0, deaths: trk ? trk.deaths : 0, levels: S.clearedCount(ram), ace: S.aceTotal(ram),
               eggs: S.eggTotal(ram), score: S.score(ram), clears: clears, dev: !!(window.Native && Native.devUsed && Native.devUsed()),
               warps: trk ? popcount(trk.warped) : 0, saved: false, sig: null, sw: 0, sh: 0 };
  }
  // A character's standing frame facing right, or left (the sprite's own way), from the ROM, so all four are there even if you
  // never played one. Without the ROM yet, or in stand-in previews: null, and the caller draws something plain instead.
  var charSpr = {};
  function charSprite(c, big, left) {
    var key = c + (big ? 'b' : 's') + (left ? 'l' : 'r');
    if (charSpr[key] !== undefined) return charSpr[key];
    if (!romOk || window.STANDIN) return null;
    charSpr[key] = null;
    var sheet = G.b64(Native.rom(S.CHAR_SHEET[c], S.SHEET_LEN)), pal = G.b64(Native.rom(S.CHAR_PAL[c], 32));
    if (sheet.length !== S.SHEET_LEN || pal.length !== 32) return null;
    var cols = [null]; for (var i = 1; i < 16; i++) cols.push(G.bgr(pal[i * 2] | pal[i * 2 + 1] << 8));
    var s = document.createElement('canvas'); s.width = 16; s.height = 32;
    var x2 = s.getContext('2d');
    if (!left) { x2.translate(16, 0); x2.scale(-1, 1); }
    (big ? S.STAND_BIG : S.STAND_SMALL).forEach(function (t, j) {
      [[0, 0, 0], [1, 8, 0], [32, 0, 8], [33, 8, 8]].forEach(function (e) {
        G.drawTile(x2, G.decode(sheet, (t + e[0] - S.SHEET_FIRST) * 32), e[1], j * 16 + e[2], cols);
      });
    });
    return charSpr[key] = s;
  }
  function endingCard() {
    var r = report; sigBox = null; if (!r) return;
    var x = 4, y0 = 4, w = W - 8, h = H - 8, cx = W / 2, px = x + 8, pw = w - 16, y = y0 + 6, rh = H >= 240 ? 11 : 9;
    card(x, y0, w, h);
    // 0.32: the stats fold into a small block (date, 2 columns, one row of characters) and the rest is for signing
    G.text(ctx, r.date + ' ' + r.clock, px, y, C.dbrown);
    if (r.dev) G.textR(ctx, 'DEV CODES', px + pw, y, T.red);
    y += rh + 1;
    var whole = function (n, of) { return [n + '/' + of, n >= of ? T.green : C.dbrown]; };
    var rows = [['PLAY TIME', hms(r.secs), C.dbrown], ['LEVELS'].concat(whole(r.levels, 20)), ['DEATHS', String(r.deaths), C.dbrown],
                ['ACE COINS'].concat(whole(r.ace, 100)), ['SCORE', String(r.score), C.dbrown]];
    var eggs = ['YOSHI EGGS'].concat(whole(r.eggs, 40));
    if (r.warps || !r.eggs) rows.push(['WARPS', String(r.warps), C.dbrown]);   // eggs mean the post-game: its slot, unless there were warps too
    if (r.eggs) rows.push(eggs);
    var gap = 8, colW = Math.floor((pw - gap) / 2);
    rows.forEach(function (row, i) {
      var x0 = px + (i % 2) * (colW + gap), yy = y + Math.floor(i / 2) * rh;
      var lab = row[0].length * 8 + 8 + row[1].length * 8 > colW ? row[0].replace('PLAY TIME', 'TIME').replace('ACE COINS', 'ACES').replace('YOSHI EGGS', 'EGGS').replace('SCORE', 'PTS') : row[0];
      G.text(ctx, lab, x0, yy, T.muted); G.textR(ctx, row[1], x0 + colW, yy, row[2]);
    });
    y += Math.ceil(rows.length / 2) * rh;
    // 0.33: each character's sprite and levels cleared with the MVP in gold, the MVP stage's order and numbers. Short
    // screens get the game's small frames (23 px at most) so the signing field stays big, tall ones the big frames.
    var top = Math.max.apply(null, r.clears), cw = Math.floor(pw / 4), big = H >= 240, feet = y + (big ? 32 : 23);
    [0, 3, 2, 1].forEach(function (c, i) {
      var x0 = px + i * cw, mvp = top && r.clears[c] === top, spr = charSprite(c, big);
      if (spr) ctx.drawImage(spr, x0, feet - 32);
      else { rrect(x0 + 4, feet - 9, 9, 9, S.CHAR_COL[c]); G.text(ctx, S.CHARS[c][0], x0 + 5, feet - 8, C.white); }
      G.text(ctx, String(r.clears[c]), x0 + 18, feet - 8, mvp ? T.gold : C.dbrown);
      if (mvp) G.text(ctx, 'MVP', x0 + 18, feet - 18, T.gold);
    });
    y = feet + 1;
    // signature field: the same size once saved, so the drawing stays; a new screen shape stretches it to fit
    var foot = 18, bw = pw, by = y, bh = y0 + h - 6 - foot - 3 - by, ty = by + bh + 3;
    if (!r.sig || r.sw !== bw || r.sh !== bh) {
      var old = r.sig, ow = r.sw, oh = r.sh; r.sig = new Uint8Array(bw * bh); r.sw = bw; r.sh = bh;
      if (old) {                                          // both ways round: shrinking keeps thin lines, growing leaves no gaps
        for (var k = 0; k < old.length; k++) if (old[k]) r.sig[Math.floor(Math.floor(k / ow) * bh / oh) * bw + Math.floor((k % ow) * bw / ow)] = 1;
        for (var dy = 0; dy < bh; dy++) for (var dx = 0; dx < bw; dx++) if (old[Math.floor(dy * oh / bh) * ow + Math.floor(dx * ow / bw)]) r.sig[dy * bw + dx] = 1;
      }
    }
    sigBox = r.saved ? null : { x: px, y: by, w: bw, h: bh };
    ctx.fillStyle = C.edge; ctx.fillRect(px, by, bw, bh);
    ctx.fillStyle = C.parch; ctx.fillRect(px + 1, by + 1, bw - 2, bh - 2);
    var line = by + bh - Math.max(10, bh >> 2);
    ctx.fillStyle = C.line; ctx.fillRect(px + 8, line, bw - 16, 1);          // signing line
    ctx.fillStyle = C.dbrown;
    var ink = false;
    for (var q = 0; q < r.sig.length; q++) if (r.sig[q]) { ink = true; ctx.fillRect(px + (q % bw), by + Math.floor(q / bw), 1, 1); }
    if (!ink) G.textC(ctx, 'SIGN YOUR NAME', cx, line - 12, T.muted);
    if (r.saved) { G.textC(ctx, 'SIGNED', cx, ty + 5, T.green); return; }
    // finger-sized: half the width each
    var half = (pw - 4) >> 1;
    button('CLEAR', px, ty, half, foot, false, function () { r.sig.fill(0); });
    button('SAVE', px + pw - half, ty, half, foot, false, function () {
      if (!r.sig.some(function (v) { return v; })) return;
      var oc = document.createElement('canvas'); oc.width = bw; oc.height = bh;
      var ox = oc.getContext('2d'); ox.fillStyle = C.dbrown;
      for (var n = 0; n < r.sig.length; n++) if (r.sig[n]) ox.fillRect(n % bw, Math.floor(n / bw), 1, 1);
      hall.push({ sig: oc.toDataURL('image/png'), date: r.date, clock: r.clock, t: r.t, secs: r.secs, deaths: r.deaths, levels: r.levels,
                  ace: r.ace, eggs: r.eggs, score: r.score, clears: r.clears, dev: r.dev, warps: r.warps });
      hall = hall.slice(-20); save('sma.hall', hall); r.saved = true; sigBox = null;
    });
  }
  // 0.32: the pen, for a fingertip. The contact point wobbles and comes in jumps, so every sample is eased and they are
  // joined by curves through their midpoints, with a 2 px pen. Still the page's logical pixels, so it looks like the game's.
  var SIG_EASE = 0.6;
  function sigDot(x, y) {
    var r = report, b = sigBox; if (!r || !b) return;
    x = Math.round(x) - b.x; y = Math.round(y) - b.y;
    for (var j = -1; j <= 0; j++) for (var i = -1; i <= 0; i++) {
      var qx = x + i, qy = y + j;
      if (qx >= 1 && qy >= 1 && qx < b.w - 1 && qy < b.h - 1) r.sig[qy * b.w + qx] = 1;
    }
  }
  function sigCurve(x0, y0, cx, cy, x1, y1) {  // a quadratic from x0,y0 to x1,y1 in steps under a pixel
    var n = Math.max(1, Math.ceil(Math.abs(cx - x0) + Math.abs(cy - y0) + Math.abs(x1 - cx) + Math.abs(y1 - cy)));
    for (var i = 1; i <= n; i++) {
      var t = i / n, u = 1 - t;
      sigDot(u * u * x0 + 2 * u * t * cx + t * t * x1, u * u * y0 + 2 * u * t * cy + t * t * y1);
    }
  }

  // ------------------------------------------------------------------ options tab
  var optPane = null, optScroll = 0, optDrag = null, slotArm = null, optMsg = '', optMsgT = 0, thumbCache = {};
  function drawOptions(p) {
    card(p.x + 1, p.y + 1, p.w - 2, p.h - 2);
    optPane = p;
    ctx.save(); ctx.beginPath(); ctx.rect(p.x + 6, p.y + 7, p.w - 12, p.h - 14); ctx.clip();
    var x = p.x + 9, y = p.y + 9 - optScroll, cw = p.w - 18;
    var slots = [];
    if (DUAL && Native.slots) { try { slots = JSON.parse(Native.slots()); } catch (e) {} }
    if (LINK) {                                            // only Link has this
      var s3 = settings3d(), onoff = function (v) { return v ? 'ON' : 'OFF'; }, flip = function (k) { return function () { set3d(k, !s3[k]); }; };
      ribbon('3D VIEW', x, y); y += 13;
      option('3D TAB', onoff(opts.tab3d), x, y, cw, function () { opts.tab3d = !opts.tab3d; save('sma.opts', opts); resize(); }); y += 13;
      if (opts.tab3d) {
        option('CAMERA', s3.cam3d.toUpperCase(), x, y, cw, nextCam); y += 13;
        option('BLOCK DEPTH', String(s3.depth3d), x, y, cw, function () { var v = s3.depth3d + 4; set3d('depth3d', v > 48 ? 4 : v); }); y += 13;
        option('BACK WALL', { near: 'NEAR', far: 'DEEP', off: 'OFF' }[s3.back3d] || 'NEAR', x, y, cw, function () { var b = ['near', 'far', 'off']; set3d('back3d', b[(b.indexOf(s3.back3d) + 1) % 3]); }); y += 13;
        option('BACKGROUND', onoff(s3.cave3d), x, y, cw, flip('cave3d')); y += 13;
        option('PLAYER LIGHT', onoff(s3.light3d), x, y, cw, flip('light3d')); y += 13;
        option('THICK SPRITES', onoff(s3.thick3d), x, y, cw, flip('thick3d')); y += 13;
        option('ROUND SPRITES', onoff(s3.round3d), x, y, cw, flip('round3d')); y += 13;
      }
      y += 5;
    }
    ribbon(LINK ? 'RETROARCH STATES' : 'SAVE STATES', x, y); y += 13;
    // Link: RetroArch's slots, which can't be listed or give a picture, so no thumbnails and LOAD is always offered
    var cardW = Math.floor((cw - 4) / 2), th = LINK ? 0 : Math.round((cardW - 4) * 2 / 3), ch = th + 30;
    slots.forEach(function (sl, i) {
      var cx = x + (i % 2) * (cardW + 4), cy = y + Math.floor(i / 2) * (ch + 4);
      ctx.fillStyle = C.parch; ctx.fillRect(cx, cy, cardW, ch);
      if (!LINK) {
        var im = slotThumb(sl);
        if (im) ctx.drawImage(im, cx + 2, cy + 2, cardW - 4, th); else { ctx.fillStyle = C.lblue; ctx.fillRect(cx + 2, cy + 2, cardW - 4, th); G.textC(ctx, 'EMPTY', cx + cardW / 2, cy + th / 2 - 2, C.white); }
      }
      G.text(ctx, (LINK ? 'SLOT ' : '') + sl.n, cx + 3, cy + th + 4, C.brown);
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
    ribbon('MAP', x, y); y += 13;
    option('X-RAY GRASS', opts.xray ? 'ON' : 'OFF', x, y, cw, function () { opts.xray = !opts.xray; save('sma.opts', opts); }); y += 13;
    option('MARKERS', opts.markers ? 'ON' : 'OFF', x, y, cw, function () { opts.markers = !opts.markers; save('sma.opts', opts); }); y += 13;
    option('MAP ZOOM', (opts.zl + 1) + ' OF 4', x, y, cw, function () { toggleZoom(); }); y += 18;
    ribbon('MARKERS', x, y); y += 13;
    var legend = [['C', 'COIN', 'coin'], ['V', 'BIG VEG', 'bigveg'], ['R', 'ROCKET', 'rocket'], ['S', 'SHELL', 'shell'], ['B', 'BOMB', 'bomb'],
                  ['K', 'KEY', 'key'], ['O', 'CRYSTAL', 'crystal'], ['P', 'POTION', 'potion'], ['1', '1UP', 'oneup'], ['W', 'POW', 'pow'], ['H', 'HEART', 'heart'], ['N', 'NINJI', 'ninji'],
                  ['G', 'GIANT', 'giantShy'], ['E', 'YOSHI EGG', 'egg'], ['M', 'MUSHROOM SPOT', 'mushroom']], rowH = hasIcons() ? 18 : 10;
    legend.forEach(function (k, i) {
      var xx = x + (i % 2) * Math.floor(cw / 2), yy = y + Math.floor(i / 2) * rowH;
      if (hasIcons()) { if (!(k[2] && drawIcon(k[2], xx, yy))) G.text(ctx, k[0], xx + 4, yy + 4, C.dred); G.text(ctx, k[1], xx + 19, yy + 4, C.brown); }
      else { G.text(ctx, k[0], xx, yy, C.dred); G.text(ctx, k[1], xx + 12, yy, C.brown); }
    });
    y += Math.ceil(legend.length / 2) * rowH + 4;
    if (DUAL) { option(LINK ? 'RETROARCH MENU' : 'PAUSE GAME', '', x, y, cw, function () { Native.menu(); }); y += 13; }
    if (DUAL && Native.overlay && Native.overlay()) { option('BACK TO GAME', '', x, y, cw, function () { Native.close(); }); y += 13; }
    if (LINK) {
      option('CHOOSE ANOTHER ROM', '', x, y, cw, function () { Native.pickRom(); }); y += 13;
      var armedR = statsArm > tickN;                       // play time, deaths and who cleared what: kept on this device, not in RetroArch's states
      option(armedR ? 'RESET STATS: SURE?' : 'RESET STATS', '', x, y, cw, function () { if (statsArm > tickN) { statsArm = 0; Native.resetStats(); optMsg = 'STATS RESET'; optMsgT = tickN + 40; } else statsArm = tickN + 40; }); y += 13;
    }
    G.text(ctx, (LINK ? 'SMA LINK ' : 'SMA DUAL ') + (DUAL ? Native.version() : ''), x, y + 4, T.muted);
    optMax = Math.max(0, y + optScroll + 20 - (p.y + p.h));
    ctx.restore();
    if (optMsgT > tickN) { var tw = optMsg.length * 8 + 10; ctx.fillStyle = C.dbrown; ctx.fillRect(p.x + p.w / 2 - tw / 2, p.y + p.h - 22, tw, 13); G.textC(ctx, optMsg, p.x + p.w / 2, p.y + p.h - 19, C.white); }
  }
  var optMax = 0, statsArm = 0;
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
  var TAB_COL = { status: '#e03828', map: '#30a030', chart: '#2870e0', options: '#e89000', '3d': '#8a6ab0' };
  function drawTabs() {
    var y = layout.tabY, n = tabs.length, w = Math.floor(W / n);
    ctx.fillStyle = C.edge; ctx.fillRect(0, y - 1, W, H - y + 1);
    // Link's fifth tab on the small screen: OPTIONS wouldn't fit in a fifth of it, so each takes room by its name
    var fit = n > 4, chars = tabs.join('').length, spare = (W - chars * 8) / n, tx = 0;
    tabs.forEach(function (t, i) {
      var tw = fit ? (i === n - 1 ? W - tx : Math.round(t.length * 8 + spare)) : w, x = fit ? tx : i * w, on = t === tab, col = themed ? C.orange : soft(TAB_COL[t] || C.blue, 0.8);
      rrect(x + 1, y, tw - 2, H - y, on ? C.cream : C.parch);
      if (on) { ctx.fillStyle = col; ctx.fillRect(x + 2, y, tw - 4, 2); }
      G.textC(ctx, t.toUpperCase(), x + tw / 2, y + 3, on ? C.dbrown : C.brown);
      buttons.push({ x: x, y: y, w: tw, h: H - y, fn: function () { tab = t; } });
      tx += tw;
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
    if (Math.abs(p[0] - down.x) + Math.abs(p[1] - down.y) > (report ? 12 : 4)) down.moved = true;   // nothing scrolls on the ending card, and a fingertip rolls
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
  // signing the ending card (the pen is sigDot / sigCurve). A stroke may start just outside the field, and a second
  // touch (a thumb on the grip side) is ignored while one is going.
  cv.addEventListener('pointerdown', function (e) {
    var p = pos(e), b = sigBox, m = 4;
    if (report && b && p[0] >= b.x - m && p[0] < b.x + b.w + m && p[1] >= b.y - m && p[1] < b.y + b.h) {
      e.stopImmediatePropagation();
      if (drawing) return;
      drawing = { id: e.pointerId, x: p[0], y: p[1], mx: p[0], my: p[1] }; sigDot(p[0], p[1]);
      try { cv.setPointerCapture(e.pointerId); } catch (err) {}
      draw();
    }
  }, true);
  cv.addEventListener('pointermove', function (e) {
    var d = drawing; if (!d || e.pointerId !== d.id) return;
    var evs = e.getCoalescedEvents ? e.getCoalescedEvents() : [];
    (evs.length ? evs : [e]).forEach(function (ev) {
      var p = pos(ev), nx = d.x + (p[0] - d.x) * SIG_EASE, ny = d.y + (p[1] - d.y) * SIG_EASE, mx = (d.x + nx) / 2, my = (d.y + ny) / 2;
      sigCurve(d.mx, d.my, d.x, d.y, mx, my);
      d.x = nx; d.y = ny; d.mx = mx; d.my = my;
    });
    draw();
  });
  ['pointerup', 'pointercancel', 'lostpointercapture'].forEach(function (ev) {
    cv.addEventListener(ev, function (e) {
      var d = drawing; if (!d || e.pointerId !== d.id) return;
      sigCurve(d.mx, d.my, d.x, d.y, d.x, d.y);   // the eased point lags: finish the stroke where the finger left
      if (ev === 'pointerup') { var p = pos(e); sigCurve(d.x, d.y, p[0], p[1], p[0], p[1]); }
      drawing = null; draw();
    });
  });

  resize();
  setInterval(poll, 100);
})();

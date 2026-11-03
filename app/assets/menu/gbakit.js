/* gbakit.js - menu graphics captured from the player's own ROM on first start (native/extract.c, "SMAX" file).
 * Shot 0 is the title screen, shot 1 the in-game pause screen; each is IO 0x60 | PAL 0x400 | VRAM 0x18000 | OAM 0x400.
 * From them this builds, as canvases:
 *   sky     title BG2 window (sky gradient), 256 x 160
 *   ground  title BG0 rows on screen (palms, log, water, hills), 512 x 160, transparent above
 *   piece   pause panel pieces (16x16 OBJs, 2D mapping, OBJ palette 2): corner 768, top 769, side 800, fill 801
 *   cursor  the pause menu's arrow (OBJ 814, 8x8)     coin  Ace Coin icon (OBJ 862, 16x16, OBJ palette 1)
 *   colors  the panel palette (cream, parchment, orange, browns)
 *   icons   (version 2, shot 2 = 1-1 in play) item sprites from the level's sprite sheet, 16x16 canvases by name:
 *           potion mushroom oneup pow shell bomb bobomb heart bigveg ninji cherry key watch star egg coin crystal,
 *           the life meter's heartFull / heartEmpty, and ace (the pause screen's Ace Coin). Tile + OBJ palette per
 *           item were read from OAM while each item was on screen (RAM-MAP.md, "Item sprites").
 * Every piece is checked against the OAM / BG registers it came from; any mismatch leaves GbaKit.ok false and the
 * menu keeps its drawn look. window.STANDIN swaps the pieces for plain stand-ins (preview images carry no game art). */
(function (root) {
  var K = { ok: false };
  var SHOT_IO = 0x60, SHOT = SHOT_IO + 0x400 + 0x18000 + 0x400;

  function shot(d, n) {
    var o = 8 + n * SHOT;
    return { io: d.subarray(o, o + SHOT_IO), pal: d.subarray(o + SHOT_IO, o + SHOT_IO + 0x400),
             vram: d.subarray(o + SHOT_IO + 0x400, o + SHOT_IO + 0x400 + 0x18000),
             oam: d.subarray(o + SHOT_IO + 0x400 + 0x18000, o + SHOT) };
  }
  function u16(a, o) { return a[o] | a[o + 1] << 8; }
  function rgb(pal, i) {
    var c = u16(pal, i * 2), r = c & 31, g = (c >> 5) & 31, b = (c >> 10) & 31;
    return [r << 3 | r >> 2, g << 3 | g >> 2, b << 3 | b >> 2];
  }
  function canvas(w, h) { var c = document.createElement('canvas'); c.width = w; c.height = h; return c; }

  // A text-mode 4bpp BG: rows [y0, y0+h) of the map starting at map column x0, w wide (wrapping like the hardware).
  function bg(s, n, x0, y0, w, h) {
    var cnt = u16(s.io, 8 + 2 * n), cb = (cnt >> 2) & 3, sb = (cnt >> 8) & 31, size = cnt >> 14;
    var MW = size & 1 ? 512 : 256, MH = size & 2 ? 512 : 256;
    var c = canvas(w, h), x = c.getContext('2d'), img = x.createImageData(w, h), p = img.data;
    for (var y = 0; y < h; y++) for (var xx = 0; xx < w; xx++) {
      var px = (xx + x0) % MW, py = (y + y0) % MH, tx = px >> 3, ty = py >> 3;
      var blk = sb + (tx >> 5) + (ty >> 5) * (MW === 512 ? 2 : 1);
      var e = u16(s.vram, blk * 0x800 + ((ty & 31) * 32 + (tx & 31)) * 2), t = e & 0x3FF, pl = e >> 12;
      var ix = px & 7, iy = py & 7; if (e & 0x400) ix = 7 - ix; if (e & 0x800) iy = 7 - iy;
      var o = cb * 0x4000 + t * 32 + iy * 4 + (ix >> 1); if (o >= 0x10000) continue;
      var v = (s.vram[o] >> ((ix & 1) * 4)) & 15; if (!v) continue;
      var col = rgb(s.pal, pl * 16 + v), q = (y * w + xx) * 4;
      p[q] = col[0]; p[q + 1] = col[1]; p[q + 2] = col[2]; p[q + 3] = 255;
    }
    x.putImageData(img, 0, 0);
    return c;
  }
  // An OBJ (2D mapping: rows of tiles are 32 tiles apart), 4bpp, OBJ palette pl.
  function obj(s, t, w, h, pl) {
    var c = canvas(w, h), x = c.getContext('2d'), img = x.createImageData(w, h), p = img.data;
    for (var y = 0; y < h; y++) for (var xx = 0; xx < w; xx++) {
      var tt = t + (y >> 3) * 32 + (xx >> 3), o = 0x10000 + tt * 32 + (y & 7) * 4 + ((xx & 7) >> 1);
      var v = (s.vram[o] >> ((xx & 1) * 4)) & 15; if (!v) continue;
      var col = rgb(s.pal, 256 + pl * 16 + v), q = (y * w + xx) * 4;
      p[q] = col[0]; p[q + 1] = col[1]; p[q + 2] = col[2]; p[q + 3] = 255;
    }
    x.putImageData(img, 0, 0);
    return c;
  }
  // name, top-left OBJ tile (2D mapping), OBJ palette — from OAM with each item on screen in 1-1
  var ICONS = [['potion', 8, 1], ['pow', 6, 1], ['crystal', 4, 1], ['shell', 16, 1], ['bomb', 158, 1], ['oneup', 156, 1],
               ['heart', 80, 1], ['mushroom', 280, 1], ['bigveg', 334, 4], ['bobomb', 344, 7], ['ninji', 274, 1],
               ['cherry', 0, 1], ['key', 142, 1], ['watch', 154, 1], ['star', 282, 9], ['egg', 94, 14], ['coin', 90, 1],
               ['heartFull', 130, 1], ['heartEmpty', 128, 2]];
  // A copy with the backing square removed: pixels of the corner colour connected to the edge become transparent
  // (the pause screen's Ace Coin sits on a filled square).
  function unfill(src) {
    var w = src.width, h = src.height, c = canvas(w, h), x = c.getContext('2d'); x.drawImage(src, 0, 0);
    var img = x.getImageData(0, 0, w, h), p = img.data, key = p[0] << 16 | p[1] << 8 | p[2], seen = new Uint8Array(w * h), st = [];
    if (!p[3]) return c;
    for (var i = 0; i < w; i++) { st.push(i, (h - 1) * w + i); } for (var j = 0; j < h; j++) { st.push(j * w, j * w + w - 1); }
    while (st.length) {
      var k = st.pop(); if (seen[k]) continue; seen[k] = 1;
      if (!p[k * 4 + 3] || (p[k * 4] << 16 | p[k * 4 + 1] << 8 | p[k * 4 + 2]) !== key) continue;
      p[k * 4 + 3] = 0;
      var kx = k % w, ky = (k / w) | 0;
      if (kx > 0) st.push(k - 1); if (kx < w - 1) st.push(k + 1); if (ky > 0) st.push(k - w); if (ky < h - 1) st.push(k + w);
    }
    x.putImageData(img, 0, 0);
    return c;
  }
  function opaque(c) { var p = c.getContext('2d').getImageData(0, 0, c.width, c.height).data, n = 0; for (var i = 3; i < p.length; i += 4) if (p[i]) n++; return n; }
  function oamTile(s, i) { return u16(s.oam, i * 8 + 4) & 0x3FF; }
  function hex(c) { return '#' + ((1 << 24) | (c[0] << 16) | (c[1] << 8) | c[2]).toString(16).slice(1); }

  K.load = function (d) {
    K.ok = false;
    if (!d || d.length < 8 + 2 * SHOT || String.fromCharCode(d[0], d[1], d[2], d[3]) !== 'SMAX' || d[4] < 1 || d[4] > 2) return false;
    var t = shot(d, 0), p = shot(d, 1);
    if (u16(t.io, 8) !== 0x5E01 || u16(t.io, 0x0C) !== 0x1B03) return false;          // title: BG0 ground, BG2 sky
    if (oamTile(p, 42) !== 768 || oamTile(p, 43) !== 769 || oamTile(p, 53) !== 800 || oamTile(p, 54) !== 801 || oamTile(p, 6) !== 814) return false;
    var sy0 = u16(t.io, 0x12) & 511, gy0 = u16(t.io, 0x1A) & 511;   // BG0 / BG2 vertical scroll
    K.ground = bg(t, 0, u16(t.io, 0x10) & 511, sy0, 512, 160);
    K.sky = bg(t, 2, 0, gy0, 256, 160);
    K.piece = { corner: obj(p, 768, 16, 16, 2), top: obj(p, 769, 16, 16, 2), side: obj(p, 800, 16, 16, 2), fill: obj(p, 801, 16, 16, 2) };
    K.cursor = obj(p, 814, 8, 8, 2);
    K.coin = obj(p, 862, 16, 16, 1);
    var c = function (i) { return hex(rgb(p.pal, 256 + 32 + i)); };
    K.colors = { cream: c(7), parch: c(13), light: c(12), orange: c(14), brown: c(10), dark: c(15), ink: c(8) };
    var sk = rgb(t.pal, 0); K.colors.sky = hex(sk);
    K.icons = { ace: unfill(K.coin) };
    if (d[4] >= 2 && d.length >= 8 + 3 * SHOT) {
      var g = shot(d, 2), h0 = oamTile(g, 0), h1 = oamTile(g, 1);
      if ((h0 === 128 || h0 === 130) && (h1 === 128 || h1 === 130)) ICONS.forEach(function (e) {
        var c = obj(g, e[1], 16, 16, e[2]);
        if (opaque(c) >= 24) K.icons[e[0]] = c;              // a missing or blank sprite falls back to the drawn look
      });
    }
    if (root.STANDIN) standin();
    K.ok = true;
    return true;
  };

  // Plain stand-ins with the same sizes, for preview images.
  function standin() {
    var fillc = function (w, h, fn) { var c = canvas(w, h), x = c.getContext('2d'); fn(x, w, h); return c; };
    K.sky = fillc(256, 160, function (x, w, h) { var g = x.createLinearGradient(0, 0, 0, h); g.addColorStop(0, '#7890b0'); g.addColorStop(1, '#c8d0dc'); x.fillStyle = g; x.fillRect(0, 0, w, h); });
    K.ground = fillc(512, 160, function (x) {
      x.fillStyle = '#6a7a6a'; x.fillRect(0, 112, 176, 48); x.fillRect(224, 112, 288, 48);
      x.fillStyle = '#8899aa'; x.fillRect(176, 124, 48, 36);
      x.fillStyle = '#556655'; for (var i = 0; i < 6; i++) x.fillRect(40 + i * 80, 40 + (i % 3) * 12, 4, 72 - (i % 3) * 12);
    });
    var box = function (fn) { return fillc(16, 16, function (x) { x.fillStyle = '#e8e4d8'; x.fillRect(0, 0, 16, 16); x.fillStyle = '#8a8070'; fn(x); }); };
    K.piece = { corner: box(function (x) { x.fillRect(0, 0, 16, 3); x.fillRect(0, 0, 3, 16); }), top: box(function (x) { x.fillRect(0, 0, 16, 3); }),
                side: box(function (x) { x.fillRect(0, 0, 3, 16); }), fill: box(function () {}) };
    K.cursor = fillc(8, 8, function (x) { x.fillStyle = '#8a8070'; for (var i = 0; i < 4; i++) x.fillRect(i + 1, i, 1, 8 - 2 * i); });
    K.coin = fillc(16, 16, function (x) { x.fillStyle = '#a09888'; x.fillRect(4, 1, 8, 14); });
    var ic = {};
    ICONS.concat([['ace']]).forEach(function (e, i) {
      var hue = (i * 47) % 360;
      ic[e[0]] = fillc(16, 16, function (x) {
        x.fillStyle = 'hsl(' + hue + ',22%,48%)'; x.beginPath(); x.arc(8, 8, 6.5, 0, 7); x.fill();
        x.fillStyle = 'hsl(' + hue + ',22%,78%)'; x.fillRect(5, 4, 3, 3);
      });
    });
    K.icons = ic;
    K.colors = { cream: '#e8e4d8', parch: '#d0cab8', light: '#c8c0b0', orange: '#606878', brown: '#585050', dark: '#383030', ink: '#202020', sky: '#7890b0' };
  }

  /** Draw an icon by name at x,y (size px, default 16); false if there is none so the caller can draw its own. */
  K.icon = function (ctx, name, x, y, size, alpha) {
    var im = K.ok && K.icons && K.icons[name]; if (!im) return false;
    size = size || 16;
    if (alpha != null && alpha < 1) { ctx.save(); ctx.globalAlpha = alpha; ctx.drawImage(im, x, y, size, size); ctx.restore(); }
    else ctx.drawImage(im, x, y, size, size);
    return true;
  };

  // Backdrop: sky stretched to the height, the title's ground strip on the bottom edge (tiled if wider than 512).
  K.backdrop = function (ctx, W, H) {
    for (var x = 0; x < W; x += 256) ctx.drawImage(K.sky, x, 0, 256, H);
    var gy = H - 160;
    for (var x2 = 0; x2 < W; x2 += 512) ctx.drawImage(K.ground, x2, gy);
  };
  function piece(ctx, im, x, y, hf, vf, cw, ch) {     // one 16x16 piece, flipped, clipped to cw x ch
    ctx.save(); ctx.beginPath(); ctx.rect(x, y, cw, ch); ctx.clip();
    ctx.translate(x + (hf ? 16 : 0), y + (vf ? 16 : 0)); ctx.scale(hf ? -1 : 1, vf ? -1 : 1);
    ctx.drawImage(im, 0, 0); ctx.restore();
  }
  // The pause panel, 9-sliced to any size >= 32x32 (pieces repeat; the last one on a row is cut at the edge it meets,
  // never the corner). Bottom and right pieces are the flipped top and left, as the game does.
  K.card = function (ctx, x, y, w, h) {
    var P = K.piece, i, j;
    w = Math.max(32, w); h = Math.max(32, h);
    for (j = y + 16; j < y + h - 16; j += 16) for (i = x + 16; i < x + w - 16; i += 16)
      piece(ctx, P.fill, i, j, 0, 0, Math.min(16, x + w - 16 - i), Math.min(16, y + h - 16 - j));
    for (i = x + 16; i < x + w - 16; i += 16) {
      var cw = Math.min(16, x + w - 16 - i);
      piece(ctx, P.top, i, y, 0, 0, cw, 16); piece(ctx, P.top, i, y + h - 16, 0, 1, cw, 16);
    }
    for (j = y + 16; j < y + h - 16; j += 16) {
      var ch = Math.min(16, y + h - 16 - j);
      piece(ctx, P.side, x, j, 0, 0, 16, ch); piece(ctx, P.side, x + w - 16, j, 1, 0, 16, ch);
    }
    piece(ctx, P.corner, x, y, 0, 0, 16, 16); piece(ctx, P.corner, x + w - 16, y, 1, 0, 16, 16);
    piece(ctx, P.corner, x, y + h - 16, 0, 1, 16, 16); piece(ctx, P.corner, x + w - 16, y + h - 16, 1, 1, 16, 16);
  };
  root.GbaKit = K;
})(window);

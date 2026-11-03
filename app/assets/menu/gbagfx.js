/* gbagfx.js - draws GBA tiles, the game's font and sprites onto a canvas (Super Mario Advance Dual: menu and Link
 * page). All graphics come from the player's ROM (or live VRAM) at runtime; the page ships none. */
(function (root) {
  var G = {};
  G.b64 = function (s) { var b = atob(s || ''), a = new Uint8Array(b.length); for (var i = 0; i < b.length; i++) a[i] = b.charCodeAt(i); return a; };
  /** GBA BGR555 -> '#rrggbb' */
  G.bgr = function (c) {
    var r = c & 31, g = (c >> 5) & 31, b = (c >> 10) & 31;
    r = r << 3 | r >> 2; g = g << 3 | g >> 2; b = b << 3 | b >> 2;
    return '#' + ((1 << 24) | (r << 16) | (g << 8) | b).toString(16).slice(1);
  };
  G.shade = function (css, k) {          // darker (k < 1) or lighter (k > 1) version of '#rrggbb'
    var n = parseInt(css.slice(1), 16), r = n >> 16, g = (n >> 8) & 255, b = n & 255;
    var f = function (v) { v = k < 1 ? v * k : v + (255 - v) * (k - 1); return Math.max(0, Math.min(255, Math.round(v))); };
    return '#' + ((1 << 24) | (f(r) << 16) | (f(g) << 8) | f(b)).toString(16).slice(1);
  };

  /** 4bpp tile (32 bytes) -> 64 pixel values */
  G.decode = function (data, off) {
    var px = new Uint8Array(64);
    for (var i = 0; i < 32; i++) { var b = data[off + i] || 0; px[i * 2] = b & 15; px[i * 2 + 1] = b >> 4; }
    return px;
  };
  /** colors: array of 16 css colors (index 0 = transparent) */
  G.drawTile = function (ctx, px, x, y, colors, hflip, vflip) {
    for (var yy = 0; yy < 8; yy++) for (var xx = 0; xx < 8; xx++) {
      var v = px[(vflip ? 7 - yy : yy) * 8 + (hflip ? 7 - xx : xx)];
      if (v && colors[v]) { ctx.fillStyle = colors[v]; ctx.fillRect(x + xx, y + yy, 1, 1); }
    }
  };

  // ---- font: SMA's 8x8 font from the player's ROM (0-9 A-P x R-Z - ? . , ; there is no Q, drawn as O).
  // Pixel 15 = ink, 13/14 = its shading. A few extra glyphs drawn here.
  var glyphs = null, cache = {};
  var EXTRA = { ':': [0, 24, 24, 0, 24, 24, 0, 0], '/': [2, 6, 12, 24, 48, 96, 64, 0],
                '!': [24, 24, 24, 24, 24, 0, 24, 0], '%': [98, 102, 12, 24, 48, 102, 70, 0], '+': [0, 24, 24, 126, 24, 24, 0, 0],
                '>': [32, 48, 56, 60, 56, 48, 32, 0], '<': [4, 12, 28, 60, 28, 12, 4, 0], "'": [24, 24, 48, 0, 0, 0, 0, 0],
                '(': [12, 24, 48, 48, 48, 24, 12, 0], ')': [48, 24, 12, 12, 12, 24, 48, 0],
                '#': [0, 0, 0, 0, 0, 0, 0, 0], '^': [24, 60, 126, 24, 24, 24, 24, 0], 'v': [24, 24, 24, 24, 126, 60, 24, 0],
                '*': [0, 108, 254, 254, 124, 56, 16, 0], '=': [0, 0, 126, 0, 126, 0, 0, 0] };
  G.FONT_MAP = '0123456789ABCDEFGHIJKLMNOPxRSTUVWXYZ-?.,';
  G.FONT_BYTES = 48 * 32;
  G.setFont = function (data) {
    glyphs = {};
    for (var t = 0; t < G.FONT_MAP.length; t++) {
      var px = G.decode(data, t * 32), m = new Uint8Array(64);
      for (var i = 0; i < 64; i++) m[i] = px[i] === 15 ? 1 : px[i] ? 2 : 0;
      glyphs[G.FONT_MAP[t]] = m;
    }
    glyphs.Q = glyphs.O;
    for (var k in EXTRA) { var mm = new Uint8Array(64); for (var y = 0; y < 8; y++) for (var x = 0; x < 8; x++) mm[y * 8 + x] = (EXTRA[k][y] >> (7 - x)) & 1; glyphs[k] = mm; }
    cache = {};
  };
  G.hasFont = function () { return !!glyphs; };
  function glyphCanvas(ch, color) {
    var key = ch + color;
    if (cache[key]) return cache[key];
    var g = glyphs && glyphs[ch];
    if (!g) return null;
    var c = document.createElement('canvas'); c.width = 8; c.height = 8;
    var x = c.getContext('2d'), dark = G.shade(color, 0.6);
    for (var i = 0; i < 64; i++) if (g[i]) { x.fillStyle = g[i] === 1 ? color : dark; x.fillRect(i & 7, i >> 3, 1, 1); }
    return (cache[key] = c);
  }
  /** Text at 8 px per character. Falls back to a system font before a ROM is chosen. */
  G.text = function (ctx, s, x, y, color, scale) {
    scale = scale || 1;
    s = String(s);
    if (!glyphs) {
      ctx.fillStyle = color; ctx.font = 'bold ' + (8 * scale) + 'px monospace'; ctx.textBaseline = 'top';
      for (var j = 0; j < s.length; j++) ctx.fillText(s[j], x + j * 8 * scale, y);
      return;
    }
    for (var i = 0; i < s.length; i++) {
      var ch = s[i], gc = glyphCanvas(ch, color);
      if (!gc && ch >= 'a' && ch <= 'z') gc = glyphCanvas(ch.toUpperCase(), color);
      if (gc) ctx.drawImage(gc, x + i * 8 * scale, y, 8 * scale, 8 * scale);
    }
  };
  G.textC = function (ctx, s, cx, y, color, scale) { scale = scale || 1; G.text(ctx, s, Math.round(cx - String(s).length * 4 * scale), y, color, scale); };
  G.textR = function (ctx, s, rx, y, color, scale) { scale = scale || 1; G.text(ctx, s, rx - String(s).length * 8 * scale, y, color, scale); };
  root.GbaGfx = G;
})(this);

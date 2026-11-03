/* sma.js - Super Mario Advance (USA, Europe) memory map for the Link screen (see RAM-MAP.md). Reads go through
 * window.Native.mem("addr:len,...") (hex GBA addresses) and come back as one hex string. */
(function (root) {
  var S = {};
  // ranges read every poll, in this order
  S.RANGES = [['file', 0x03001140, 0x30], ['st', 0x03001990, 0xA0], ['pl', 0x03001C30, 0xA0], ['vert', 0x0300116F, 1],
              ['obj', 0x03001CF0, 0x70 * 16], ['mb', 0x03001DE0, 0x60], ['got', 0x03001A3E, 0x26]];
  S.spec = S.RANGES.map(function (r) { return r[1].toString(16) + ':' + r[2]; }).join(',');
  S.hexBytes = function (h) { var a = new Uint8Array(h.length >> 1); for (var i = 0; i < a.length; i++) a[i] = parseInt(h.substr(i * 2, 2), 16); return a; };
  S.parse = function (hex) {
    var b = S.hexBytes(hex), o = 0, m = {};
    S.RANGES.forEach(function (r) { m[r[0]] = b.subarray(o, o + r[2]); o += r[2]; });
    return m;
  };
  function u8(m, k, a) { var r = S.RANGES.filter(function (x) { return x[0] === k; })[0]; return m[k][a - r[1]] || 0; }
  function u16(m, k, a) { return u8(m, k, a) | u8(m, k, a + 1) << 8; }
  S.u8 = u8; S.u16 = u16;

  // Game state is three bytes: mode 19B0 (2 title/file menus, 3 SMB2, 4 Mario Bros. Classic), sub-mode 1991 (in mode 3:
  // 0 character select / chart, 1 in a level; more below) and 1992 (in a level: 1 playing, 0x0C/0x0D pause, 0x0F unpausing;
  // at character select: 1 choosing, 2 leaving, 5/6 chart). 1992 alone is 1 at character select too.
  S.mainState = function (m) { return u8(m, 'st', 0x03001992); };
  S.gameMode = function (m) { return u8(m, 'st', 0x030019B0); };
  S.subMode = function (m) { return u8(m, 'st', 0x03001991); };
  S.inLevel = function (m) { return S.gameMode(m) === 3 && S.subMode(m) === 1; };
  S.playing = function (m) { return S.inLevel(m) && S.mainState(m) === 1; };
  S.charSelect = function (m) { return S.gameMode(m) === 3 && S.subMode(m) === 0 && S.mainState(m) === 1; };
  // 0.23: the rest of mode 3's sub-modes (handlers at 0833D590): 2 Bonus Chance, 4 the ending, 5 / 6 the Yoshi
  // Challenge screens. In Bonus Chance 03001A07 (the coin count) is the spins left and 1992 is 1 waiting, 2 spinning.
  S.bonusChance = function (m) { return S.gameMode(m) === 3 && S.subMode(m) === 2; };
  S.yoshiMenu = function (m) { return S.gameMode(m) === 3 && (S.subMode(m) === 5 || S.subMode(m) === 6); };
  // The ending: 1992 steps through its own table (08349588): 1-6 Wart's room, 7 the parade, 8 the MVP stage, 0x0C
  // Mario's dream, 0x10 the cast roll then THE END (waits for Start), 0x11 / 0x12 fade back to the title.
  S.ending = function (m) { return S.gameMode(m) === 3 && S.subMode(m) === 4; };
  S.endStage = function (m) { return S.mainState(m); };
  // Levels cleared per character (the MVP stage's numbers, kept on the file): 03001148 + character index.
  S.charClears = function (m, c) { return u8(m, 'file', 0x03001148 + c); };
  S.beaten = function (m) { return u8(m, 'file', 0x03001162) !== 0; };
  // In a level 1992 also runs: 3 life lost, 4-6 GAME OVER, 7/8 the menu after it, 9 door, 0x0A Subspace, 0x10 TRY AGAIN.
  S.gameOver = function (m) { var s = S.mainState(m); return S.inLevel(m) && s >= 4 && s <= 8 && s !== 5; };
  S.playerState = function (m) { return u8(m, 'pl', 0x03001C6F); };      // like SMB2's PlayerState: 7 dying, 0x0B at a door
  S.dying = function (m) { return S.playerState(m) === 7; };
  S.charge = function (m) { return u8(m, 'pl', 0x03001C7F); };           // power jump: +1 a frame while ducking, full at 60
  S.cursorChar = function (m) { return u8(m, 'mb', 0x03001E01) & 3; };  // character select: the one under the cursor
  S.menuCursor = function (m) { return u8(m, 'got', 0x03001A63); };      // pause / game-over menu: 0 CONTINUE ... 3 TRY AGAIN
  S.level = function (m) { return u8(m, 'file', 0x03001160); };
  S.lives = function (m) { return u8(m, 'file', 0x03001161); };
  S.score = function (m) { return u16(m, 'file', 0x0300114C) + u16(m, 'file', 0x0300114E) * 65536; };
  S.aceBits = function (m) { var a = []; for (var i = 0; i < 13; i++) a.push(u8(m, 'file', 0x03001150 + i)); return a; };
  // level L owns bits L*5 .. L*5+4, LSB first from 03001150 (read at 0803182C, ORed in at 080347D8)
  S.aceSaved = function (m, L) { var b = S.aceBits(m), v = 0; for (var i = 0; i < 5; i++) { var k = L * 5 + i; v |= ((b[k >> 3] >> (k & 7)) & 1) << i; } return v; };
  // levels cleared on this file: bit L of 0300115D..0300115F (set at 080317D2)
  S.cleared = function (m, L) { return (u8(m, 'file', 0x0300115D + (L >> 3)) >> (L & 7)) & 1; };
  S.clearedCount = function (m) { var n = 0; for (var L = 0; L < 20; L++) n += S.cleared(m, L); return n; };
  S.aceTotal = function (m) { var b = S.aceBits(m), n = 0; for (var i = 0; i < 100; i++) if ((b[i >> 3] >> (i & 7)) & 1) n++; return n; };
  S.area = function (m) { return u8(m, 'st', 0x030019EC); };
  // 030019FE: the SMB2-style area number (0..10) = which ROM object list the game spawns from (see objectList)
  S.areaNo = function (m) { return u8(m, 'st', 0x030019FE); };
  S.pages = function (m) { return Math.min(10, u8(m, 'st', 0x03001A06) + 1); };
  S.vertical = function (m) { return m.vert[0] !== 0; };
  S.coins = function (m) { return u8(m, 'st', 0x03001A07); };
  S.cherries = function (m) { return u8(m, 'st', 0x03001A26); };
  S.aceLevel = function (m) { return u8(m, 'pl', 0x03001C35) & 31; };   // bitmask: Ace Coins taken this visit (same bit order)
  // Yoshi Challenge (after the game is beaten; 0.16, from the user's 100% saves): the game's own test (HUD egg slots
  // at 0805AFC2, egg spawner 0809B8E4) is 03001162 != 0 and the byte at 030019CE + [030019CC] == 0x14.
  S.yoshi = function (m) {
    if (!u8(m, 'file', 0x03001162)) return false;
    var i = u8(m, 'st', 0x030019CC);
    return i < 0x60 && u8(m, 'st', 0x030019CE + i) === 0x14;
  };
  // Two eggs per level. On the file: 40 bits from 03001140, level L owns bit 2L (egg 1) and 2L+1 (egg 2) — the
  // Yoshi Challenge screen's top / bottom rows. This visit: bits 0/1 of 03001C37 (the HUD's egg slots); the level's
  // file bits are copied to 03001C38 at level start (0802F4CC).
  S.eggsVisit = function (m) { return u8(m, 'pl', 0x03001C37) & 3; };
  S.eggsFile = function (m, L) { var k = L * 2; return (u8(m, 'file', 0x03001140 + (k >> 3)) >> (k & 7)) & 3; };
  S.eggTotal = function (m) { var n = 0; for (var i = 0; i < 40; i++) n += (u8(m, 'file', 0x03001140 + (i >> 3)) >> (i & 7)) & 1; return n; };
  S.eggs = function (m) { var v = S.eggsVisit(m); return (v & 1) + (v >> 1); };
  // Spot metatiles (all drawn as sky; read when Subspace is built, 0802CCE4): 0x23 / 0x24 mushroom 1 / 2 (SMB2's),
  // 0x93 and 0x9D SMA's added mushroom 3, 0x94 / 0x9E Yoshi egg 1 / 2 (spawned only in the Yoshi Challenge and only
  // if not taken on this visit; an egg already on the file comes back).
  S.MUSH_SPOT = { 0x23: 0, 0x24: 1, 0x93: 2, 0x9D: 2 };
  S.EGG_SPOT = { 0x94: 0, 0x9E: 1 };
  // 0.17: how the level renderer redraws wall metatiles 0x26E..0x2C5 (08029EC2): 03001A4B = 1 -> (quad & 0x1FF) | 0x6000;
  // else world graphics set 030019FC = 3 (ice) and 03001A29 = 0 -> (quad & 0xFF) | 0x2200. Same rule as Gba.retile.
  S.quadMode = function (m) {
    if (u8(m, 'got', 0x03001A4B) === 1) return 2;
    return u16(m, 'st', 0x030019FC) === 3 && u8(m, 'st', 0x03001A29) === 0 ? 1 : 0;
  };
  S.retile = function (id, mode, q) {
    if (!q || !mode || id <= 0x26D || id > 0x2C5) return q;
    return q.map(function (e) { return mode === 2 ? (e & 0x1FF) | 0x6000 : (e & 0xFF) | 0x2200; });
  };
  S.isMushSpot = function (t) { return S.MUSH_SPOT[t] !== undefined; };
  S.isEggSpot = function (t) { return S.EGG_SPOT[t] !== undefined; };
  // ROM table: 3 bytes per level, mushroom 1 / 2 / 3; nonzero = that mushroom is not there in the Yoshi Challenge
  // (read at 080905A2). link.js loads it into S.noMush.
  S.YOSHI_NO_MUSH = 0x3E75D4; S.noMush = null;
  S.px = function (m) { return u16(m, 'pl', 0x03001C4A); };
  S.py = function (m) { return u16(m, 'pl', 0x03001C4E); };
  S.character = function (m) { return u8(m, 'pl', 0x03001C70) & 3; };
  S.holding = function (m) { return u8(m, 'pl', 0x03001C74); };
  S.health = function (m) { var h = u8(m, 'pl', 0x03001C76); return h >= 0xF0 ? 0 : (h >> 4) + 1; };
  // 03001C77: mushrooms taken in this level (0..2): the life meter shows 2 + this many hearts (0.1 guessed it was a copy)
  S.maxHearts = function (m) { return Math.min(4, 2 + u8(m, 'pl', 0x03001C77)); };
  // taken-this-level flags (SMB2's order; set when taken, kept through areas, deaths and state loads, cleared for the
  // next level): 03001A3E the 1UP grass was pulled, 03001A3F mushroom 1 (spot tile 0x23), 03001A40 mushroom 2 (0x24),
  // 03001A41 mushroom 3 (0x93 / 0x9D; 0.16)
  S.oneUpTaken = function (m) { return u8(m, 'got', 0x03001A3E) !== 0; };
  S.mushTaken = function (m, t) { return u8(m, 'got', 0x03001A3F + (S.MUSH_SPOT[t] || 0)) !== 0; };
  /** Grass / spot cells that still hold something (taken ones drop off the map). */
  S.stillThere = function (m, t) {
    if (t === 0x2C) return !S.oneUpTaken(m);
    if (S.isMushSpot(t)) {
      if (S.mushTaken(m, t)) return false;
      var L = S.level(m);
      return !(S.yoshi(m) && S.noMush && L < 20 && S.noMush[L * 3 + S.MUSH_SPOT[t]]);
    }
    if (S.isEggSpot(t)) return S.yoshi(m) && !((S.eggsVisit(m) >> S.EGG_SPOT[t]) & 1);
    return true;
  };
  S.star = function (m) { return u8(m, 'pl', 0x03001C88); };
  S.stopwatch = function (m) { return u8(m, 'pl', 0x03001CAC); };
  S.subspace = function (m) { return u8(m, 'pl', 0x03001CCF); };
  // object slots: 0x70 bytes each from 03001CF0; +0x65 = 1 for a live object (other values: effects), +0x66 class
  // (08 item, 0B enemy, 0C heart, 0D sprite platform; from the per-type table at 08362A47), +0x30 / +0x32 world X / Y
  S.objects = function (m) {
    var out = [];
    for (var i = 0; i < 16; i++) {
      var o = i * 0x70, a = m.obj;
      if (a[o + 0x65] !== 1) continue;
      out.push({ x: a[o + 0x30] | a[o + 0x31] << 8, y: a[o + 0x32] | a[o + 0x33] << 8, cls: a[o + 0x66] });
    }
    return out;
  };
  // ---- object lists in the ROM: u32 table at 0x364D84, 20 levels x 11 areas (index level*11 + areaNo); each list is
  // records (u16 type, u16 tile x, u16 tile y) ended by a lone u16 0x00FF. Types are SMB2's enemy ids (01 Shy Guy ...
  // 1C Birdo) plus SMA's own: 0x6F..0x73 = the level's Ace Coins 1..5 (bit type-0x6F of its 5 save bits).
  S.OBJ_TABLE = 0x364D84;
  S.ACE_FIRST = 0x6F;
  S.parseObjList = function (b) {
    var out = [];
    for (var o = 0; o + 6 <= b.length; o += 6) {
      var t = b[o] | b[o + 1] << 8; if (t === 0xFF) break;
      out.push({ type: t, x: b[o + 2] | b[o + 3] << 8, y: b[o + 4] | b[o + 5] << 8 });
    }
    return out;
  };
  // Mario Bros. Classic (game mode 4)
  S.mb = function (m) { return { left: u8(m, 'mb', 0x03001DEE), round: u8(m, 'mb', 0x03001DFC), score: u16(m, 'mb', 0x03001E10) + u16(m, 'mb', 0x03001E12) * 65536,
                                 lives: u8(m, 'mb', 0x03001E35) }; };

  S.LEVELS = ['1-1', '1-2', '1-3', '2-1', '2-2', '2-3', '3-1', '3-2', '3-3', '4-1', '4-2', '4-3', '5-1', '5-2', '5-3', '6-1', '6-2', '6-3', '7-1', '7-2'];
  S.WORLD_START = [0, 3, 6, 9, 12, 15, 18];
  S.CHARS = ['MARIO', 'PEACH', 'TOAD', 'LUIGI'];
  S.CHAR_COL = ['#e03828', '#f878b0', '#3878f8', '#30b030'];

  // ---- metatiles (u16 ids; high byte = collision group: 0 none, 1 stand on top, 2 solid)
  S.ROM_METATILE_PTRS = 0x3400E0;
  S.METATILE_COUNTS = [159, 47, 192];   // the last table runs on past its known ids
  S.FONT = 0x2F3D7C;
  // grass: the 11 SMB2 kinds in SMB2's order, plus 8 more SMA grass ids (contents not mapped yet)
  S.GRASS = { 0x25: ['C', 'COIN'], 0x26: ['V', 'BIG VEGETABLE'], 0x27: ['', 'VEGETABLE'], 0x28: ['R', 'ROCKET'], 0x29: ['S', 'SHELL'],
              0x2A: ['B', 'BOMB'], 0x2B: ['P', 'POTION'], 0x2C: ['1', '1UP'], 0x2D: ['W', 'POW'], 0x2E: ['B', 'BOB-OMB'], 0x2F: ['', 'PULLED'],
              // SMA's own grass (pulled in the emulator): giant things take a few seconds to come out
              0x95: ['E', 'YOSHI EGG'], 0x96: ['H', 'HEART'], 0x97: ['G', 'GIANT VEGETABLE'], 0x98: ['W', 'GIANT POW'],
              0x99: ['G', 'GIANT SHY GUY'], 0x9A: ['G', 'GIANT ITEM'], 0x9B: ['N', 'NINJI'], 0x9C: ['G', 'GIANT SHY GUY'] };
  S.isGrass = function (t) { return (t >= 0x25 && t <= 0x2F) || (t >= 0x95 && t <= 0x9C); };
  // grass contents -> item icon name (menu/gbakit.js K.icons); no entry = no icon (letter tag instead)
  S.GRASS_ICON = { 0x25: 'coin', 0x26: 'bigveg', 0x28: 'rocket', 0x29: 'shell', 0x2A: 'bomb', 0x2B: 'potion', 0x2C: 'oneup',
                   0x2D: 'pow', 0x2E: 'bobomb', 0x95: 'egg', 0x96: 'heart', 0x97: 'giantVeg', 0x98: 'pow', 0x99: 'giantShy',
                   0x9B: 'ninji', 0x9C: 'giantShyBlue' };
  // ---- object types worth a map marker (0.11). SMA keeps SMB2's enemy ids up to 0x31, then its item block is SMB2's
  // + 6 (falling logs 0x41 in 1-1 / 1-3 / 5-1, key 0x43 in exactly the levels with a Phanto). The class table at
  // 0x362A47 marks bosses as 0x0A. Hawkmouth: 0x49 (facing left) sits beside the crystal ball = the level's exit;
  // 0x48 (facing right) is in the first area of most levels (where you come in), so it gets no marker.
  S.CLASS_TABLE = 0x362A47;
  S.OBJ_KEY = 0x43; S.OBJ_CRYSTAL = 0x4A; S.OBJ_EXIT = [0x49];
  S.BOSS = { 0x1C: 'BIRDO', 0x1D: 'MOUSER', 0x1F: 'TRYCLYDE', 0x21: 'CLAWGRIP', 0x2A: 'FRYGUY', 0x2C: 'WART', 0x2D: 'HAWKMOUTH',
             0x34: 'ROBIRDO' };
  S.MARK = { 0x30: ['*', 'CHERRY'], 0x31: ['D', 'DOOR'], 0x23: ['M', 'MUSHROOM SPOT'], 0x24: ['M', 'MUSHROOM SPOT'], 0x93: ['M', 'MUSHROOM SPOT'], 0x9D: ['M', 'MUSHROOM SPOT'] };
  root.SMA = S;
})(this);

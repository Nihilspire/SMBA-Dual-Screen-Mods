package com.jack.smadual;

import java.util.Arrays;
import java.util.HashMap;

/**
 * Builds the experimental 3D view of Super Mario Advance from one frame's memory snapshot. Pure Java (no Android),
 * so tests/scene3d runs the same code on the desktop against memory dumps.
 *
 * SMA keeps the whole area decoded in EWRAM ($02000000, u16 metatile ids, 15x16 per page, like SMB2's $6000), and the
 * metatile id's high byte is its collision group: 0xx = no collision, 1xx = stand on top (clouds, hill tops,
 * logs), 2xx = solid (checked in the emulator for every id, see RAM-MAP.md). Each 8x8 cell of the main layer (BG0) is
 * textured from the live tile map and classified by the metatile under it:
 *   1xx / 2xx -> blocks through the full depth
 *   0xx with transparent parts (grass, cherries, vines, ladders...) -> flat cut-outs in the middle of the depth
 *   0xx fully opaque (hill bodies, trunks, walls) -> the back wall
 *   blank tiles -> nothing
 * Backdrop: the game's own parallax layers (BG2, then BG1) at their own scroll, on a far plane. HUD (BG3) drawn flat.
 * Screen -> world: world = camera + screen; camera = BG0 scroll + whole wraps. The wrap (and whether the area is laid
 * out as vertical pages) is the candidate whose level data best matches BG0's tile map: SMA's vertical flag (0300116F)
 * is set late after entering an area, and the player can be above the screen, so neither can be trusted alone.
 * A good match (>= 90% of sampled cells) turns the 3D on; it stays on through partial matches (>= 60%) for up to 30
 * frames in the same level/area (the game streams map rows/columns in as it scrolls). Otherwise mapped is false and the
 * renderer shows the flat picture.
 */
final class Scene3D {
    // snapshot layout
    static final int IO = 0, PAL = 0x60, VRAM = 0x460, OAM = 0x18460, LVL = 0x18860, LVL_LEN = 0x14A0, VARS = 0x19D00,
            VARS_ADDR = 0x03001100, VARS_LEN = 0x1400, SKY_TAB = VARS + VARS_LEN, SKY_ADDR = 0x02002B20, SKY_LEN = 32, SNAP = SKY_TAB + SKY_LEN;
    static final int W = 240, H = 160;
    static final int ATLAS_W = 512, ATLAS_H = 512, SLOTS = (ATLAS_W / 8) * (ATLAS_H / 8);
    static final int BD_W = 512, BD_H = 512;

    // What the options start with, Dual and Link alike (their preferences hold what has been changed), and those preferences' names
    static final String DEF_CAM = "low";
    static final int DEF_DEPTH = 20;
    static final String[] PREF_KEYS = { "cam3d", "depth3d", "back3d", "cave3d", "light3d", "thick3d", "round3d", "border3d" };

    // options
    volatile String back = "near";
    volatile int depth = DEF_DEPTH;
    volatile boolean cave = true, light = false, thick = true;
    volatile byte[] rom;

    // outputs (read by the renderer after build())
    float[] verts = new float[1 << 17];
    int nv, caveStart, caveCount, spriteStart, hudStart;
    final byte[] atlas = new byte[ATLAS_W * ATLAS_H * 4];
    boolean atlasChanged;
    final byte[] mask = new byte[256 * 256];
    boolean maskChanged;
    final byte[] backdrop = new byte[BD_W * BD_H * 4];
    int bdW = BD_W, bdH = BD_H, groundRgb = -1;
    boolean bdChanged;
    final byte[] hud = new byte[256 * 256 * 4];
    boolean hudChanged;
    float lightX = 120, lightY = -80, lightAmt;
    int skyRgb;
    boolean mapped;
    int camX, camY;

    static int u8(byte b) { return b & 255; }
    static int u16(byte[] f, int o) { return u8(f[o]) | u8(f[o + 1]) << 8; }
    private static int var(byte[] f, int addr) { return u8(f[VARS + addr - VARS_ADDR]); }
    private static int var16(byte[] f, int addr) { return u16(f, VARS + addr - VARS_ADDR); }
    private static int bgr(byte[] f, int i) {   // palette index -> 0xRRGGBB
        int c = u16(f, PAL + i * 2), r = c & 31, g = (c >> 5) & 31, b = (c >> 10) & 31;
        return (r << 3 | r >> 2) << 16 | (g << 3 | g >> 2) << 8 | (b << 3 | b >> 2);
    }

    // ------------------------------------------------------------------ BG layers
    private static final class Bg { int cnt, cb, sb, size, hofs, vofs, w, h; boolean on; int prio() { return cnt & 3; } }
    private final Bg[] bg = { new Bg(), new Bg(), new Bg(), new Bg() };
    private void readBgs(byte[] f) {
        int disp = u16(f, IO);
        for (int i = 0; i < 4; i++) {
            Bg b = bg[i];
            b.cnt = u16(f, IO + 8 + i * 2); b.cb = (b.cnt >> 2) & 3; b.sb = (b.cnt >> 8) & 31; b.size = b.cnt >> 14;
            b.hofs = u16(f, IO + 0x10 + i * 4) & 511; b.vofs = u16(f, IO + 0x12 + i * 4) & 511;
            b.w = (b.size & 1) != 0 ? 512 : 256; b.h = (b.size & 2) != 0 ? 512 : 256;
            b.on = (disp & (0x100 << i)) != 0;
        }
    }
    /** Map entry at a layer pixel (wraps). */
    private int entry(byte[] f, Bg b, int x, int y) {
        x = ((x % b.w) + b.w) % b.w; y = ((y % b.h) + b.h) % b.h;
        int tx = x >> 3, ty = y >> 3, blk = b.sb;
        if (tx >= 32) { blk += 1; tx -= 32; }
        if (ty >= 32) { blk += (b.w == 512 ? 2 : 1); ty -= 32; }
        int o = VRAM + blk * 0x800 + (ty * 32 + tx) * 2;
        return o + 1 < VRAM + 0x10000 ? u16(f, o) : 0;
    }
    /** 4bpp pixel value of a BG tile. */
    private static int bgPixel(byte[] f, int cb, int tile, int x, int y) {
        int o = VRAM + cb * 0x4000 + tile * 32 + y * 4 + (x >> 1);
        if (o >= VRAM + 0x10000) return 0;
        return (u8(f[o]) >> ((x & 1) * 4)) & 15;
    }
    // ------------------------------------------------------------------ stacked hills
    // SMB2/SMA hills are rectangles of body metatiles (060 left, 061 middle, 062 right; doors 031/033 sit in them) under
    // a cap row (11D left, 11F middle, 121 right; 123 / 124 = left / right cap drawn over another hill's body). A hill
    // that starts inside another one (a left edge or a cap cutting into its body) was drawn over it: it is in front.
    // Each hill is a connected piece; nest = how many hills it stands in front of, in a chain. Blocks of a hill come
    // forward by nest * HILL_STEP of the depth.
    static final float HILL_STEP = 0.75f;
    // 0.14: SMA has more cap variants than SMB2 (counted over every level dump: the metatiles directly above a body):
    // left 11B / 11D / 123 / 125, right 11C / 121 / 124 / 126. 0.13 knew only 11D-124, so a hill whose corner cap was
    // 125 / 126 / 11B / 11C left that corner out of the hill: it stayed at the back and the corner looked cut off.
    private static boolean hillL(int t) { return t == 0x060 || t == 0x11B || t == 0x11D || t == 0x123 || t == 0x125; }
    private static boolean hillR(int t) { return t == 0x062 || t == 0x11C || t == 0x121 || t == 0x124 || t == 0x126; }
    private static boolean hillCap(int t) { return t >= 0x11B && t <= 0x126; }
    private static boolean hill(int t) { return t == 0x060 || t == 0x061 || t == 0x062 || t == 0x031 || t == 0x033 || hillCap(t); }
    // 0.22: cloud platforms (101 left, 102 middle, 103 right; 1-1's climb and 7-1) are cut-outs. As solid blocks their
    // clear scallops took the nearest opaque colour (the dark outline), so each cloud was a slab with a thick black rim
    // and dark sides; now their faces drop the clear texels like the mid-depth props.
    private static boolean cloud(int t) { return t >= 0x101 && t <= 0x103; }
    private int hx0, hy0, hw, hh;
    private int[] hillNest = new int[0], par = new int[0];
    private int find(int a) { while (par[a] != a) { par[a] = par[par[a]]; a = par[a]; } return a; }
    private void hills(byte[] f) {
        hx0 = (camX >> 4) - 20; hy0 = (camY >> 4) - 12; hw = 56; hh = 36;
        int n = hw * hh;
        if (hillNest.length != n) { hillNest = new int[n]; par = new int[n]; }
        int[] t = new int[n];
        for (int y = 0; y < hh; y++) for (int x = 0; x < hw; x++) {
            int id = meta(f, (hx0 + x) * 16 + 8, (hy0 + y) * 16 + 8);
            t[y * hw + x] = id >= 0 && hill(id) ? id : -1;
        }
        for (int i = 0; i < n; i++) par[i] = i;
        // same hill: across a row unless a left edge starts / a right edge ended; down a column unless a cap starts
        for (int y = 0; y < hh; y++) for (int x = 0; x < hw; x++) {
            int i = y * hw + x, a = t[i];
            if (a < 0) continue;
            if (x > 0 && t[i - 1] >= 0 && !hillL(a) && !hillR(t[i - 1])) par[find(i)] = find(i - 1);
            if (y > 0 && t[i - hw] >= 0 && !hillCap(a)) par[find(i)] = find(i - hw);
        }
        // in front of: who cut into whom
        int[] front = new int[n * 2], back = new int[n * 2]; int ne = 0;
        for (int y = 0; y < hh; y++) for (int x = 0; x < hw; x++) {
            int i = y * hw + x, a = t[i];
            if (a < 0) continue;
            if (x > 0 && t[i - 1] >= 0) {
                if (hillL(a) && !hillR(t[i - 1])) { front[ne] = find(i); back[ne++] = find(i - 1); }
                else if (hillR(t[i - 1]) && !hillL(a)) { front[ne] = find(i - 1); back[ne++] = find(i); }
            }
            if (y > 0 && t[i - hw] >= 0 && hillCap(a) && !hillCap(t[i - hw])) { front[ne] = find(i); back[ne++] = find(i - hw); }
        }
        java.util.Arrays.fill(hillNest, 0);
        for (int it = 0; it < 4; it++) for (int e = 0; e < ne; e++)
            if (front[e] != back[e] && hillNest[front[e]] < hillNest[back[e]] + 1) hillNest[front[e]] = Math.min(4, hillNest[back[e]] + 1);
        for (int i = 0; i < n; i++) if (t[i] < 0) par[i] = -1;
    }
    /** How far forward (in depth units) the block at this world pixel comes: 0 unless it is part of a front hill. */
    private int nestAt(int wx, int wy) {
        int x = (wx >> 4) - hx0, y = (wy >> 4) - hy0;
        if (x < 0 || y < 0 || x >= hw || y >= hh) return 0;
        int i = y * hw + x;
        if (par[i] < 0) return 0;
        return hillNest[find(i)];
    }

    private int opaquePixels(byte[] f, int cb, int tile) {
        int o = VRAM + cb * 0x4000 + tile * 32, n = 0;
        if (o + 32 > VRAM + 0x10000) return 0;
        for (int i = 0; i < 32; i++) { int v = f[o + i]; if ((v & 15) != 0) n++; if ((v & 0xF0) != 0) n++; }
        return n;
    }
    // ------------------------------------------------------------------ pyramids (0.19)
    // 2-1's pyramids are scenery in the level layer: a triangle of metatiles, apex 042 045, each row one cell wider on
    // both sides (042 left slope, 046 / 043 light fill, 044 / 047 dark fill, 045 right slope), base row 048 049 04A..
    // 04B.. 04C 04D, sometimes a door (04E / 04F) in the dark half. Light left half + dark right half = a pyramid seen
    // corner-on. 0.18 split it by tile coverage: slopes as mid-depth cut-outs, the fill on the back wall, so it broke
    // into a stepped outline floating in front of a recessed middle. Now each half is one sloped face of a real
    // pyramid: the outline sits on the back plane, the corner facing the camera comes forward to the ridge line.
    private static boolean pyrId(int t) { return t >= 0x042 && t <= 0x04F; }
    private static boolean pyrL(int t) { return t == 0x042 || t == 0x048; }
    static final float PYR_DEPTH = 0.85f;    // front corner, as a fraction of the block depth in front of the back plane
    /** Pyramid holding metatile cell (mx, my), as { ridge x, apex y, base y } in world pixels and its row count; null if
     *  the cell isn't in a pyramid of the expected shape (ids 042-04F mean other things in other worlds). */
    private int[] pyramid(byte[] f, int mx, int my) {
        if (!pyrId(mt(f, mx, my))) return null;
        int lx = mx;
        while (!pyrL(mt(f, lx, my))) { lx--; if (lx < mx - 16 || !pyrId(mt(f, lx, my))) return null; }
        int ax = lx, ay = my;                                  // up the left slope to the apex
        while (mt(f, ax + 1, ay - 1) == 0x042) { ax++; ay--; }
        if (mt(f, ax, ay) != 0x042 || mt(f, ax + 1, ay) != 0x045 || pyrId(mt(f, ax, ay - 1))) return null;
        int bx = lx, by = my;                                  // down the left slope to the base row
        while (pyrL(mt(f, bx - 1, by + 1))) { bx--; by++; }
        if (mt(f, bx, by) != 0x048) return null;
        int n = by - ay + 1;
        if (mt(f, ax + n, by) != 0x04D) return null;           // and the right slope ends where it should
        return new int[] { (ax + 1) * 16, ay * 16, (by + 1) * 16, n };
    }
    private final HashMap<Integer, int[]> pyrMemo = new HashMap<Integer, int[]>();
    private int mt(byte[] f, int mx, int my) { return mx < 0 || my < 0 ? -1 : meta(f, mx * 16 + 8, my * 16 + 8); }
    /** Depth of a pyramid face at screen point (sx, sy) (sy down); p = pyramid with its world coords moved to screen. */
    private static float pyrZ(float[] p, boolean left, float sx, float sy, float zBack, float d) {
        float half = p[3] * 16f, t = (p[2] - sy) / (p[2] - p[1]);
        float s = left ? (sx - (p[0] - half)) / half : ((p[0] + half) - sx) / half;
        return zBack + d * (s - t);
    }

    private boolean emptyBgTile(byte[] f, int cb, int tile) {
        int o = VRAM + cb * 0x4000 + tile * 32;
        if (o + 32 > VRAM + 0x10000) return true;
        for (int i = 0; i < 32; i++) if (f[o + i] != 0) return false;
        return true;
    }

    // ------------------------------------------------------------------ level data
    private boolean vertical;
    private int pages;
    /** Metatile id at a world pixel, or -1 outside the area. */
    private int meta(byte[] f, int wx, int wy) {
        if (wx < 0 || wy < 0) return -1;
        int idx;
        if (vertical) { int r = wy >> 4; if (r >= pages * 15 || wx >= 256) return -1; idx = r * 16 + (wx >> 4); }
        else { int p = wx >> 8, r = wy >> 4; if (p >= pages || r > 14) return -1; idx = p * 240 + r * 16 + ((wx >> 4) & 15); }
        if (idx * 2 + 1 >= LVL_LEN) return -1;
        return u16(f, LVL + idx * 2);
    }
    /** meta, and past the area's edges what runs on there (0.29), so the far cameras don't show the world ending in mid-air:
     *  solid ground and water go on in every direction, hill bodies downwards. Pits stay open. */
    private int metaOut(byte[] f, int wx, int wy, int aw, int ah, int ws) {
        int t = meta(f, wx, wy);
        if (wx >= areaX0 && wy >= 0 && wx < aw && wy < ah) return t;
        t = meta(f, Math.max(areaX0, Math.min(aw - 1, wx)), Math.max(0, Math.min(ah - 1, wy)));
        if (t < 0) return -1;
        int sh = shapeOf(t, ws);
        if (sh == SH_WATER || (sh == SH_NONE && t >> 8 == 2 && t != 0x202)) return t;
        return wy >= ah && wx >= areaX0 && wx < aw && t >= 0x060 && t <= 0x062 ? t : -1;
    }
    private final int[] q = new int[4];
    private boolean quad(int id) {
        byte[] r = rom;
        if (r == null || !BezelTheme.quad(r, id, q)) return false;
        Gba.retile(id, quadMode, q);
        if (mirrored) {         // the game draws Subspace mirrored: each metatile's left and right tiles swap and flip
            int a = q[0]; q[0] = q[1] ^ 0x400; q[1] = a ^ 0x400;
            a = q[2]; q[2] = q[3] ^ 0x400; q[3] = a ^ 0x400;
        }
        return true;
    }
    /** How the game redraws the wall metatiles 0x26E..0x2C5 this frame (see Gba.retile). */
    private int quadMode;

    static final int SKY = 0, BACK = 1, PROP = 2, BLOCK = 3, PYR = 4;
    private final HashMap<Integer, Integer> propCache = new HashMap<Integer, Integer>();
    /** 0.35: the metatiles drawn with the ladder's tiles: 03E / 03F, the same look over scenery, 115 / 116 where you can stand on it. */
    private static boolean ladder(int id) { return id == 0x03E || id == 0x03F || id == 0x0B4 || id == 0x0B5 || id == 0x115 || id == 0x116; }
    private int classOf(byte[] f, int id) {
        if (id < 0) return SKY;
        if (ladder(id)) return PROP;                // always see-through, even where the game lets you stand on it
        int g = id >> 8;
        if (g == 1 || (g == 2 && id != 0x202)) return BLOCK;
        Integer c = propCache.get(id);
        if (c != null) return c;
        int k = SKY;
        if (quad(id)) {                       // coverage of its 4 tiles: opaque -> back wall, partly clear -> cut-out
            int n = 0;
            for (int i = 0; i < 4; i++) for (int y = 0; y < 8; y++) for (int x = 0; x < 8; x++)
                if (bgPixel(f, bg[0].cb, q[i] & 0x3FF, x, y) != 0) n++;
            k = n == 0 ? SKY : n >= 240 ? BACK : PROP;
        }
        propCache.put(id, k);
        return k;
    }

    private int lastGood = -1000, frameNo, lastKey = -1;
    /** Fraction (0..1000) of sampled screen cells whose BG0 tile matches the level data under camera (cx, cy).
     *  0.24: a cell the layout puts outside the area counts as a miss when BG0 draws something there. Near the top of a
     *  vertical area the horizontal reading (page 0's 15 rows) matched every cell it had and left the rest out, so it
     *  tied with the right one; with the vertical flag wrong (1-1's vine climb) it won, rows 15 and down had no level
     *  data, and the clouds there vanished from the 3D view as the camera reached the top. */
    private int score(byte[] f, int cx, int cy) {
        int ok = 0, n = 0;
        for (int sy = 12; sy < H; sy += 20) for (int sx = 4; sx < W; sx += 18) {
            int wx = cx + sx, wy = cy + sy, t = meta(f, wx, wy);
            if (t < 0) { if (!emptyBgTile(f, bg[0].cb, entry(f, bg[0], bg[0].hofs + sx, bg[0].vofs + sy) & 0x3FF)) n++; continue; }
            if (!quad(t)) continue;
            n++;
            int want = q[((wy >> 3) & 1) * 2 + ((wx >> 3) & 1)];
            if ((entry(f, bg[0], bg[0].hofs + sx, bg[0].vofs + sy) & 0x3FF) == (want & 0x3FF)) ok++;
        }
        return n >= 20 ? ok * 1000 / n : 0;
    }
    private int runsKey = -1, runsVal = -1;
    private byte[] runsRom;
    /** 0.35: which way an area runs, from its object list in the ROM (placed in world tiles): 1 down (one past row 14), 0 across
     *  (one past column 15), -1 if none is past the first page. The game's flag stays clear until the camera first moves down,
     *  and at the top of an area the screen matches both layouts, so what lay below the first page came in only then. */
    private int areaRuns(int level, int area) {
        byte[] r = rom;
        int key = level * 16 + area;
        if (key == runsKey && r == runsRom) return runsVal;
        runsKey = key; runsRom = r; runsVal = -1;
        if (r == null || level > 19 || area > 10) return -1;
        int t = Gba.ROM_OBJ_LISTS + (level * 11 + area) * 4;
        if (t + 4 > r.length) return -1;
        int p = (u16(r, t) | u16(r, t + 2) << 16) & 0x1FFFFFF;
        for (int n = 0; p != 0 && n < 400 && p + 6 <= r.length; n++, p += 6) {
            if (u16(r, p) == 0xFF) break;
            if (u16(r, p + 4) >= 15) { runsVal = 1; break; }
            if (u16(r, p + 2) >= 16) { runsVal = 0; break; }
        }
        return runsVal;
    }
    /** Picks layout + camera (best match), sets mapped (with the hold described above). */
    private void view(byte[] f) {
        frameNo++;
        quadMode = Gba.quadMode(var16(f, Gba.TILESET), var(f, Gba.TILE_FLAG_A), var(f, Gba.TILE_FLAG_B));
        // 0.29: Subspace is 3D too (it fell back to the flat picture): the extra page, laid out like a horizontal page, with
        // only BG0 and the HUD on. 0.34: the same state is also a pot with no area of its own. That page is always page 10:
        // 0.29 took it to be the one after the area's last, which only held in an area of 10 pages, so these fell back to
        // the flat, unmapped view.
        subspace = var(f, Gba.MAIN_STATE) == Gba.STATE_SUBSPACE;
        mirrored = subspace && var(f, Gba.EXTRA_KIND) == 2;
        pot = var(f, Gba.TILE_FLAG_A) != 0;
        pages = subspace ? Gba.EXTRA_PAGE + 1 : Math.min(10, var(f, Gba.PAGES) + 1);
        areaX0 = subspace ? Gba.EXTRA_PAGE * 256 : 0;
        int px = var16(f, Gba.PLAYER_X), py = var16(f, Gba.PLAYER_Y);
        Bg b = bg[0];
        int runs = subspace ? -1 : areaRuns(var(f, Gba.CURRENT_LEVEL), var(f, Gba.AREA_NO));
        boolean flag = !subspace && (runs >= 0 ? runs == 1 : var(f, Gba.IS_VERTICAL) != 0);
        int best = -1, bx = b.hofs, by = b.vofs; boolean bv = flag;
        boolean play = rom != null && var(f, Gba.MODE) == 3 && var(f, Gba.SUB_MODE) == 1 && (var(f, Gba.MAIN_STATE) == 1 || subspace);
        long bestDist = Long.MAX_VALUE;
        if (play) for (int v = 0; v < (subspace ? 1 : 2); v++) {
            vertical = v == 0 ? flag : !flag;
            // every wrap of the scroll inside the area (the player's position can't be trusted: falling into a pit, dying)
            int maxX = vertical ? 256 : pages * 256, maxY = vertical ? pages * 240 : 240;
            for (int cx = b.hofs % b.w - b.w; cx < maxX; cx += b.w) {
                if (cx + W <= areaX0) continue;
                for (int cy = b.vofs % b.h - b.h; cy < maxY; cy += b.h) {
                    if (cy + H <= 0) continue;
                    int sc = score(f, cx, cy);
                    long dist = Math.abs((long) px - (cx + W / 2)) + Math.abs((long) py - (cy + H / 2)) + (v == 0 ? 0 : 1);
                    if (sc > best || (sc == best && dist < bestDist)) { best = sc; bestDist = dist; bx = cx; by = cy; bv = vertical; }
                }
            }
        }
        vertical = bv; camX = bx; camY = by;
        int key = var(f, Gba.CURRENT_LEVEL) * 16 + var(f, Gba.AREA_NO);
        if (key != lastKey) { lastKey = key; lastGood = -1000; }
        if (best >= 900) lastGood = frameNo;
        mapped = play && (best >= 900 || (best >= 600 && frameNo - lastGood <= 30));
        quality = best;
    }
    /** Last match score, 0..1000 (for tests). */
    int quality;
    private boolean subspace, mirrored, pot;
    private int areaX0;
    // BG0's colour effect when it covers the whole screen (Subspace darkens BG0 by 8/16; with a window it only applies inside)
    private int bgDark, bgLight;

    // ------------------------------------------------------------------ atlas (rebuilt from what's on screen)
    private final HashMap<Long, Integer> slots = new HashMap<Long, Integer>();
    private int nextSlot;
    private final int[] lastSum = new int[1];
    /** Atlas slot of one 8x8 tile in a palette: obj = sprite tiles (char base 4). */
    private int slot(byte[] f, boolean obj, int cb, int tile, int pal) { return slot(f, obj, cb, tile, pal, false); }
    /** solid: for block faces — clear pixels take the colour of the nearest opaque pixel in the tile, so rounded hill
     *  corners and outline gaps don't open holes into the block (0.13 showed them as sky-coloured notches). */
    private int slot(byte[] f, boolean obj, int cb, int tile, int pal, boolean solid) {
        long key = (solid ? 1L << 41 : 0) | (obj ? 1L << 40 : 0) | (long) cb << 32 | (long) tile << 8 | pal;
        Integer s = slots.get(key);
        if (s != null) return s;
        if (nextSlot >= SLOTS) return 0;
        int sl = nextSlot++;
        slots.put(key, sl);
        int ax = (sl % (ATLAS_W / 8)) * 8, ay = (sl / (ATLAS_W / 8)) * 8;
        int base = obj ? VRAM + 0x10000 + tile * 32 : VRAM + cb * 0x4000 + tile * 32;
        for (int y = 0; y < 8; y++) for (int x = 0; x < 8; x++) {
            int o = base + y * 4 + (x >> 1);
            int v = o < VRAM + 0x18000 ? (u8(f[o]) >> ((x & 1) * 4)) & 15 : 0;
            int k = ((ay + y) * ATLAS_W + ax + x) * 4;
            int col = bgr(f, (obj ? 256 : 0) + pal * 16 + v);
            if (!obj && (bgDark | bgLight) != 0) {
                int cr = col >> 16 & 255, cg = col >> 8 & 255, cb2 = col & 255;
                cr += ((bgLight != 0 ? 255 - cr : -cr) * (bgLight + bgDark)) / 16; cg += ((bgLight != 0 ? 255 - cg : -cg) * (bgLight + bgDark)) / 16; cb2 += ((bgLight != 0 ? 255 - cb2 : -cb2) * (bgLight + bgDark)) / 16;
                col = cr << 16 | cg << 8 | cb2;
            }
            atlas[k] = (byte) (col >> 16); atlas[k + 1] = (byte) (col >> 8); atlas[k + 2] = (byte) col;
            atlas[k + 3] = (byte) (v != 0 ? 255 : 0);
        }
        if (solid) fillClear(ax, ay);
        return sl;
    }
    /** 0.34: a block tile tinted for a pot wall's side faces: the outline's cream only went grey under the faces' shade. */
    private int potSideSlot(byte[] f, int cb, int tile, int pal) {
        long key = 1L << 42 | (long) cb << 32 | (long) tile << 8 | pal;
        Integer s = slots.get(key);
        if (s != null) return s;
        int src = slot(f, false, cb, tile, pal, true);
        if (nextSlot >= SLOTS) return src;
        int sl = nextSlot++;
        slots.put(key, sl);
        int ax = (sl % (ATLAS_W / 8)) * 8, ay = (sl / (ATLAS_W / 8)) * 8, bx = (src % (ATLAS_W / 8)) * 8, by = (src / (ATLAS_W / 8)) * 8;
        for (int y = 0; y < 8; y++) for (int x = 0; x < 8; x++) {
            int k = ((ay + y) * ATLAS_W + ax + x) * 4, o = ((by + y) * ATLAS_W + bx + x) * 4;
            atlas[k] = atlas[o];
            atlas[k + 1] = (byte) ((atlas[o + 1] & 255) * 86 / 100);
            atlas[k + 2] = (byte) ((atlas[o + 2] & 255) * 70 / 100);
            atlas[k + 3] = atlas[o + 3];
        }
        return sl;
    }
    private final int[] fq = new int[64];
    private void fillClear(int ax, int ay) {
        int head = 0, tail = 0, opaque = 0;
        boolean[] done = new boolean[64];
        for (int i = 0; i < 64; i++) if (atlas[(((ay + (i >> 3)) * ATLAS_W) + ax + (i & 7)) * 4 + 3] != 0) { done[i] = true; fq[tail++] = i; opaque++; }
        if (opaque == 0 || opaque == 64) return;
        while (head < tail) {
            int i = fq[head++], x = i & 7, y = i >> 3, src = (((ay + y) * ATLAS_W) + ax + x) * 4;
            int[] nb = { x > 0 ? i - 1 : -1, x < 7 ? i + 1 : -1, y > 0 ? i - 8 : -1, y < 7 ? i + 8 : -1 };
            for (int j : nb) if (j >= 0 && !done[j]) {
                done[j] = true; fq[tail++] = j;
                int dst = (((ay + (j >> 3)) * ATLAS_W) + ax + (j & 7)) * 4;
                atlas[dst] = atlas[src]; atlas[dst + 1] = atlas[src + 1]; atlas[dst + 2] = atlas[src + 2]; atlas[dst + 3] = (byte) 255;
            }
        }
    }

    // ------------------------------------------------------------------ what the camera sees (0.29)
    // The terrain was built for the screen + 32 px and the far layers for the screen alone, so FAR and DIORAMA showed the
    // level as a slab cut off in mid-air against an empty sky. setCamera works out how far past each screen edge the view
    // reaches, at the blocks and at the far plane; build fills exactly that. Each frame, as the camera eases between presets.
    static final float VIEW_SLACK = 12, VIEW_MAX = 208;
    private float marL = 32, marR = 32, marT = 32, marB = 32, bdL = 0, bdR = 0, bdT = 0, bdB = 0;
    /** Same camera as Renderer3D.mvp. Returns the distance to the z = 0 plane. */
    float setCamera(int w, int h, float pitch, float yaw, float zoom) {
        double tanY = Math.tan(Math.toRadians(16)), asp = w / (double) h, tanX = tanY * asp;
        double dist = Math.max(80, 120 / asp) / zoom / tanY + depth, p = Math.toRadians(pitch), y = Math.toRadians(yaw);
        double ex = dist * Math.sin(y) * Math.cos(p), ey = dist * Math.sin(p), ez = dist * Math.cos(y) * Math.cos(p);   // from (120, -80, 0)
        double fl = Math.sqrt(ex * ex + ey * ey + ez * ez), fx = -ex / fl, fy = -ey / fl, fz = -ez / fl;
        double rl = Math.sqrt(fz * fz + fx * fx), rx = -fz / rl, rz = fx / rl;                                          // right = f x up
        double ux = -rz * fy, uy = rz * fx - rx * fz, uz = rx * fy;                                                     // up = r x f
        float[] nearBack = new float[4], plane = new float[4];
        Arrays.fill(nearBack, Float.NaN); Arrays.fill(plane, Float.NaN);
        float zPlane = -depth * 3 - 48, sc = (float) ((dist - zPlane) / dist);
        for (int sx = -1; sx <= 1; sx += 2) for (int sy = -1; sy <= 1; sy += 2) {
            double dx = fx + rx * sx * tanX + ux * sy * tanY, dy = fy + uy * sy * tanY, dz = fz + rz * sx * tanX + uz * sy * tanY;
            for (float z : new float[] { -depth / 2f, depth * 2f }) {       // the blocks' back plane, and hills that come forward
                double t = (z - ez) / dz;
                reach(nearBack, (float) (ex + t * dx), (float) (ey + t * dy));
            }
            double t = (zPlane - ez) / dz;
            reach(plane, (float) (ex + t * dx) / sc, (float) (ey + t * dy) / sc);
        }
        marL = viewMargin(-nearBack[0] - 120); marR = viewMargin(nearBack[1] - 120); marB = viewMargin(-nearBack[2] - 80); marT = viewMargin(nearBack[3] - 80);
        bdL = viewMargin(-plane[0] - 120) + 16; bdR = viewMargin(plane[1] - 120) + 16; bdB = viewMargin(-plane[2] - 80) + 16; bdT = viewMargin(plane[3] - 80) + 16;
        return (float) dist;
    }
    private static void reach(float[] b, float x, float y) {   // min x, max x, min y, max y
        if (Float.isNaN(b[0]) || x < b[0]) b[0] = x;
        if (Float.isNaN(b[1]) || x > b[1]) b[1] = x;
        if (Float.isNaN(b[2]) || y < b[2]) b[2] = y;
        if (Float.isNaN(b[3]) || y > b[3]) b[3] = y;
    }
    private static float viewMargin(float v) { return Math.max(0, Math.min(VIEW_MAX, v + VIEW_SLACK)); }

    // ------------------------------------------------------------------ sky (0.29)
    // Levels with a sky have no sky picture: the game rewrites palette colour 0 down the screen from a 16-colour table it
    // keeps at 02002B20 (copied from the ROM, 2F2BFE day / 2F2C1E night / 2F2C5E 1-1's clouds), and the 3D view only saw
    // what colour 0 ends the frame as: white, or navy in 2-1. SKY_START is the screen row each entry starts on, read off the
    // 2D picture (4-1, 2-1, 1-1). Caves leave the table at 0; the last entry is colour 0 whenever the effect runs.
    private static final int[] SKY_START = { 0, 8, 16, 24, 32, 38, 44, 50, 56, 64, 68, 72, 76, 80, 84, 87 };
    private final int[] skyRow = new int[H];
    private boolean readSky(byte[] f) {
        int first = u16(f, SKY_TAB);
        boolean flat = true;
        for (int i = 2; i < SKY_LEN; i += 2) if (u16(f, SKY_TAB + i) != first) flat = false;
        if (flat || u16(f, SKY_TAB + SKY_LEN - 2) != u16(f, PAL)) return false;
        for (int e = 0; e < 16; e++) {
            int c = u16(f, SKY_TAB + e * 2), r = c & 31, g = (c >> 5) & 31, b = (c >> 10) & 31, rgb = (r << 3 | r >> 2) << 16 | (g << 3 | g >> 2) << 8 | (b << 3 | b >> 2);
            for (int y = SKY_START[e]; y < (e < 15 ? SKY_START[e + 1] : H); y++) skyRow[y] = rgb;
        }
        return true;
    }

    // ------------------------------------------------------------------ backdrop and HUD layers
    // The far layers' maps are good well past the screen (0.29): up and down 28 rows (past that, leftovers of other screens,
    // 7-1's status bar), and across every column the game has written since the area began. It streams columns in as the
    // camera moves, so the ones ahead hold what was there before: the fill entries 00A0 / 00A1, or a menu's tiles. farSeen
    // remembers which have been on screen or just past it (the game writes 24 px ahead), and the rest take the column one
    // 256 px period over. That is only a fair guess: the layers have no real period (measured over maps the camera has
    // passed whole). It is fine for dense scenery, where a seam hardly shows; a sparse layer (the clouds) shows it as shapes
    // cut off, so it just fades out past its last known column instead. In vertical areas the rows stream instead and each
    // row holds a screen's width: only the screen's rows are used, and columns repeat every 240 px.
    private final long[] farSeen = new long[4];
    private int farKey = -1;
    private final int[] farPal = new int[256];
    private void markSeen(int li) {
        Bg l = bg[li];
        if (l.w != 512 || !mapped) { farSeen[li] = -1L; return; }
        for (int k = l.hofs >> 3; k <= (l.hofs + W - 1 + (vertical ? 0 : 16)) >> 3; k++) farSeen[li] |= 1L << (k & 63);
    }
    /** A sparse layer whose unknown columns stay empty: BG2 in a horizontal area, unless it fills the screen (7-1's sky). */
    private boolean farSparse(byte[] f, Bg l, int li) {
        if (li != 2 || !mapped || vertical) return false;
        int n = 0, full = 0;
        for (int sy = 4; sy < H; sy += 16) for (int sx = 4; sx < W; sx += 16) {
            n++;
            if (!emptyBgTile(f, l.cb, entry(f, l, l.hofs + sx, l.vofs + sy) & 0x3FF)) full++;
        }
        return full * 10 < n * 9;
    }
    private boolean colSeen(Bg l, int li, int tx) { return (farSeen[li] >>> (tx & ((l.w >> 3) - 1)) & 1) != 0; }
    private int farCol(Bg l, int li, int tx, boolean sparse) {
        int n = l.w >> 3, c = tx & (n - 1);
        if ((farSeen[li] >>> c & 1) != 0) return c;
        if (sparse) return -1;
        boolean ahead = tx >= (l.hofs >> 3);
        int o = (ahead ? c - (vertical ? 30 : 32) : c + (vertical ? 30 : 32)) & (n - 1);
        if ((farSeen[li] >>> o & 1) != 0) return o;
        o = (ahead ? c - 30 : c + 30) & (n - 1);       // only one screen's columns known yet (just came through a door)
        return (farSeen[li] >>> o & 1) != 0 ? o : -1;
    }
    /** The map row for a layer tile row, unwrapped; -1 for none. Past the bottom the last 4 rows repeat (more would repeat
     *  6-1's band of grass); above the top, the first 8 if the layer reaches row 0 in this column (a cave's rock), otherwise
     *  there is only sky. */
    private int farRow(byte[] f, Bg l, int ty, int col) {
        if (!mapped || vertical) return ty & ((l.h >> 3) - 1);
        int last = Math.max(27, (l.vofs + H - 1) >> 3);
        if (ty > last) return last - 3 + (ty - last - 1) % 4;
        if (ty >= 0) return ty;
        return emptyBgTile(f, l.cb, entry(f, l, col * 8, 0) & 0x3FF) ? -1 : ((ty % 8) + 8) % 8;
    }
    /** Draws a far layer over the backdrop, inside the rectangle xa..xb, ya..yb (backdrop texels, screen at ox, oy). */
    private void farLayer(byte[] f, Bg l, int li, int ox, int oy, int xa, int xb, int ya, int yb) {
        if (!l.on) return;
        boolean sparse = farSparse(f, l, li);
        for (int ty = fdiv(ya - oy + l.vofs, 8); ty * 8 - l.vofs + oy < yb; ty++) for (int tx = fdiv(xa - ox + l.hofs, 8); tx * 8 - l.hofs + ox < xb; tx++) {
            int col = farCol(l, li, tx, sparse), row = col < 0 ? -1 : farRow(f, l, ty, col);
            if (row < 0) continue;
            int e = entry(f, l, col * 8, row * 8), p = (e >> 12) * 16, base = VRAM + l.cb * 0x4000 + (e & 0x3FF) * 32;
            if (base + 32 > VRAM + 0x10000) continue;
            int dx = tx * 8 - l.hofs + ox, dy = ty * 8 - l.vofs + oy;
            boolean off = dx + 8 <= ox || dx >= ox + W || dy + 8 <= oy || dy >= oy + H;
            if (off && (e == 0x00A0 || e == 0x00A1)) continue;     // never written
            // a sparse layer off the screen: px to its last known column, each side, for the fade out
            int toR = 99, toL = 99;
            if (sparse && dx >= ox + W) { if (!colSeen(l, li, tx + 1)) toR = 8; else if (!colSeen(l, li, tx + 2)) toR = 16; }
            if (sparse && dx + 8 <= ox) { if (!colSeen(l, li, tx - 1)) toL = 1; else if (!colSeen(l, li, tx - 2)) toL = 9; }
            for (int j = 0; j < 8; j++) {
                int y = dy + j, sj = (e & 0x800) != 0 ? 7 - j : j;
                if (y < ya || y >= yb) continue;
                for (int i = 0; i < 8; i++) {
                    int x = dx + i, si = (e & 0x400) != 0 ? 7 - i : i;
                    if (x < xa || x >= xb) continue;
                    int v = (u8(f[base + sj * 4 + (si >> 1)]) >> ((si & 1) * 4)) & 15;
                    if (v == 0) continue;
                    int c = farPal[p + v], k = (y * BD_W + x) * 4, d = Math.min(toR - i, toL + i);
                    if (d < 16) {        // over what is under it; alpha too, so it ends as that exactly
                        if (backdrop[k + 3] == 0) continue;
                        for (int b = 0; b < 3; b++) { int u = backdrop[k + b] & 255, w = (c >> (16 - b * 8)) & 255; backdrop[k + b] = (byte) (u + (w - u) * d / 16); }
                        backdrop[k + 3] = (byte) ((backdrop[k + 3] & 255) + (255 - (backdrop[k + 3] & 255)) * d / 16);
                        continue;
                    }
                    backdrop[k] = (byte) (c >> 16); backdrop[k + 1] = (byte) (c >> 8); backdrop[k + 2] = (byte) c; backdrop[k + 3] = (byte) 255;
                }
            }
        }
    }
    // 0.34: inside a pot BG1 is one picture, 32 x 28 tiles down to the pot's floor, the pot's inside with black all round it,
    // not scenery that goes on: the rest of its map is other screens' tiles. It is the pot's back wall: BG0's pot outline lies
    // exactly over the edge of the picture, so it sits right behind the outline (see build) and is drawn as it is, with black
    // past its edges.
    private static final int POT_COLS = 32, POT_ROWS = 28;
    private void potPicture(byte[] f, Bg l, int ox, int oy) {
        for (int i = 3; i < backdrop.length; i += 4) { backdrop[i - 3] = 0; backdrop[i - 2] = 0; backdrop[i - 1] = 0; backdrop[i] = (byte) 128; }
        if (!l.on) return;
        for (int ty = 0; ty < POT_ROWS; ty++) for (int tx = 0; tx < POT_COLS; tx++) {
            int e = entry(f, l, tx * 8, ty * 8), p = (e >> 12) * 16, base = VRAM + l.cb * 0x4000 + (e & 0x3FF) * 32;
            if (base + 32 > VRAM + 0x10000) continue;
            int dx = tx * 8 - l.hofs + ox, dy = ty * 8 - l.vofs + oy;
            for (int j = 0; j < 8; j++) for (int i = 0; i < 8; i++) {
                int x = dx + i, y = dy + j, sj = (e & 0x800) != 0 ? 7 - j : j, si = (e & 0x400) != 0 ? 7 - i : i;
                if (x < 0 || y < 0 || x >= BD_W || y >= BD_H) continue;
                int v = (u8(f[base + sj * 4 + (si >> 1)]) >> ((si & 1) * 4)) & 15;
                if (v == 0) continue;
                int c = bgr(f, p + v), k = (y * BD_W + x) * 4;
                backdrop[k] = (byte) (c >> 16); backdrop[k + 1] = (byte) (c >> 8); backdrop[k + 2] = (byte) c;
            }
        }
    }
    /** Draws a BG layer (at its own scroll) into out; only rows ya..yb and columns xa..xb are filled. */
    private void drawLayer(byte[] f, Bg b, byte[] out, int ow, int oh, int x0, int y0, boolean clear, int xa, int xb, int ya, int yb) {
        if (clear) Arrays.fill(out, (byte) 0);
        if (!b.on) return;
        for (int y = Math.max(0, ya); y < Math.min(oh, yb); y++) for (int x = Math.max(0, xa); x < Math.min(ow, xb); x++) {
            int lx = b.hofs + x0 + x, ly = b.vofs + y0 + y;
            int e = entry(f, b, lx, ly), t = e & 0x3FF, p = e >> 12;
            int tx = ((lx % 8) + 8) % 8, ty = ((ly % 8) + 8) % 8;
            if ((e & 0x400) != 0) tx = 7 - tx;
            if ((e & 0x800) != 0) ty = 7 - ty;
            int v = bgPixel(f, b.cb, t, tx, ty);
            if (v == 0) continue;
            int col = bgr(f, p * 16 + v), k = (y * ow + x) * 4;
            out[k] = (byte) (col >> 16); out[k + 1] = (byte) (col >> 8); out[k + 2] = (byte) col; out[k + 3] = (byte) 255;
        }
    }

    // ------------------------------------------------------------------ geometry
    private float u0, v0, u1, v1;
    private void uvSlot(int sl) {
        float e = 0.02f; int ax = (sl % (ATLAS_W / 8)) * 8, ay = (sl / (ATLAS_W / 8)) * 8;
        u0 = (ax + e) / ATLAS_W; v0 = (ay + e) / ATLAS_H; u1 = (ax + 8 - e) / ATLAS_W; v1 = (ay + 8 - e) / ATLAS_H;
    }
    private void push(float x, float y, float z, float u, float v, float shade, float kind) {
        if (nv * 7 + 7 > verts.length) verts = Arrays.copyOf(verts, verts.length * 2);
        int o = nv * 7;
        verts[o] = x; verts[o + 1] = y; verts[o + 2] = z; verts[o + 3] = u; verts[o + 4] = v; verts[o + 5] = shade; verts[o + 6] = kind;
        nv++;
    }
    private void quad(float ax, float ay, float az, float bx, float by, float bz, float cx, float cy, float cz, float dx, float dy, float dz,
                      float ua, float va, float ub, float vb, float uc, float vc, float ud, float vd, float shade, float kind) {
        push(ax, ay, az, ua, va, shade, kind); push(bx, by, bz, ub, vb, shade, kind); push(cx, cy, cz, uc, vc, shade, kind);
        push(ax, ay, az, ua, va, shade, kind); push(cx, cy, cz, uc, vc, shade, kind); push(dx, dy, dz, ud, vd, shade, kind);
    }
    // 0.20: ground and other solid blocks stand out GROUND_LIP of the block depth in front of the hill scenery at the
    // same nest. Flush with the cliff behind them (0.6-0.19), the grass under a sprite was the cliff's own front plane,
    // so characters looked pasted on the wall; now they stand on a strip of ground in front of it. Less than HILL_STEP,
    // so a hill that steps forward still covers the ground behind it.
    static final float GROUND_LIP = 0.375f;
    /** Front z of the block in cell (r, c) of the build grid; -inf when the cell isn't a block. */
    private static float blockFront(int[][] cls, int[][] nst, boolean[][] hil, int r, int c, float zf, int D) {
        if (cls[r][c] != BLOCK) return Float.NEGATIVE_INFINITY;
        return zf + nst[r][c] * D * HILL_STEP + (hil[r][c] ? 0 : GROUND_LIP * D);
    }
    // ------------------------------------------------------------------ rounded shapes (0.25)
    /** A rounded-edge height field over a pixel grid of labels (0 = outside). Per pixel, the chamfer (3-4) distance to
     *  the nearest pixel with another label; per pixel corner, a quarter-round profile 0..1: 0 where labels meet (or
     *  touch outside), 1 from r px in. Outside the grid counts as more of the same (the margin is off screen). */
    static final class Field {
        int w, h; float r;
        int[] lab = new int[0], d = new int[0];
        float[] v = new float[0];
        void size(int w, int h) {
            this.w = w; this.h = h;
            if (lab.length < w * h) { lab = new int[w * h]; d = new int[w * h]; }
            if (v.length < (w + 1) * (h + 1)) v = new float[(w + 1) * (h + 1)];
            Arrays.fill(lab, 0, w * h, 0);
            Arrays.fill(v, 0, (w + 1) * (h + 1), 0f);
        }
        private int nb(int i, int j) { return lab[j] == lab[i] ? d[j] : 0; }
        void solve(float r) { solve(r, 0, 0, h - 1, w - 1); }
        /** Over the rows y0..y1 and columns x0..x1 that hold labels (the rest stays 0: clouds are small, the grid isn't). */
        void solve(float r, int y0, int x0, int y1, int x1) {
            this.r = r;
            final int INF = 1 << 20;
            for (int py = y0; py <= y1; py++) for (int px = x0; px <= x1; px++) { int i = py * w + px; d[i] = lab[i] == 0 ? 0 : INF; }
            for (int py = y0; py <= y1; py++) for (int px = x0; px <= x1; px++) {
                int i = py * w + px, e = d[i];
                if (e == 0) continue;
                if (px > 0) e = Math.min(e, nb(i, i - 1) + 3);
                if (py > 0) {
                    e = Math.min(e, nb(i, i - w) + 3);
                    if (px > 0) e = Math.min(e, nb(i, i - w - 1) + 4);
                    if (px < w - 1) e = Math.min(e, nb(i, i - w + 1) + 4);
                }
                d[i] = e;
            }
            for (int py = y1; py >= y0; py--) for (int px = x1; px >= x0; px--) {
                int i = py * w + px, e = d[i];
                if (e == 0) continue;
                if (px < w - 1) e = Math.min(e, nb(i, i + 1) + 3);
                if (py < h - 1) {
                    e = Math.min(e, nb(i, i + w) + 3);
                    if (px < w - 1) e = Math.min(e, nb(i, i + w + 1) + 4);
                    if (px > 0) e = Math.min(e, nb(i, i + w - 1) + 4);
                }
                d[i] = e;
            }
            for (int vy = y0; vy <= y1 + 1; vy++) for (int vx = x0; vx <= x1 + 1; vx++) {
                float s = 0; int n = 0, l = -1; boolean edge = false;
                for (int j = vy - 1; j <= vy; j++) for (int i = vx - 1; i <= vx; i++) {
                    if (i < 0 || j < 0 || i >= w || j >= h) continue;
                    int k = j * w + i;
                    if (lab[k] == 0 || (l >= 0 && lab[k] != l)) { edge = true; continue; }
                    l = lab[k];
                    float t = Math.min(d[k] / 3f - 0.5f, r) / r, u = 1 - t;
                    s += (float) Math.sqrt(Math.max(0, 1 - u * u)); n++;
                }
                v[vy * (w + 1) + vx] = edge || n == 0 ? 0 : s / n;
            }
        }
        float at(int vx, int vy) {
            vx = Math.max(0, Math.min(w, vx)); vy = Math.max(0, Math.min(h, vy));
            return v[vy * (w + 1) + vx];
        }
        boolean in(int px, int py) { return px >= 0 && py >= 0 && px < w && py < h && lab[py * w + px] != 0; }
        /** Shade of the surface z = base + hz * at() (back: base - hz * at(), facing away) at a vertex. */
        float shade(int vx, int vy, float hz, boolean back) {
            float dx = hz * (at(vx + 1, vy) - at(vx - 1, vy)) / 2, dy = hz * (at(vx, vy + 1) - at(vx, vy - 1)) / 2;
            return back ? shadeN(dx, -dy, -1) : shadeN(-dx, dy, 1);     // grid y runs down, world y up
        }
    }
    private static final float LX = -0.3f, LY = 0.55f, LZ = 0.78f;
    /** Lambert shade for a surface normal (lit from the upper left), 1 for a face turned to the camera. */
    static float shadeN(float nx, float ny, float nz) {
        float l = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
        float d = Math.max(0, (nx * LX + ny * LY + nz * LZ) / l);
        return Math.max(0.4f, (0.42f + 0.58f * d) / (0.42f + 0.58f * LZ));
    }
    // one quad with its own per-corner position, texture and shade: corners bottom-left, bottom-right, top-right,
    // top-left as seen from the front; back = wound the other way (seen from behind)
    private final float[] cq = new float[24];
    private void corner(int k, float x, float y, float z, float u, float v, float s) {
        cq[k * 6] = x; cq[k * 6 + 1] = y; cq[k * 6 + 2] = z; cq[k * 6 + 3] = u; cq[k * 6 + 4] = v; cq[k * 6 + 5] = s;
    }
    private static final int[] FRONT = { 0, 1, 2, 0, 2, 3 }, BACKW = { 0, 2, 1, 0, 3, 2 };
    private void emit(float kind, boolean back) {
        for (int k : back ? BACKW : FRONT) push(cq[k * 6], cq[k * 6 + 1], cq[k * 6 + 2], cq[k * 6 + 3], cq[k * 6 + 4], cq[k * 6 + 5], kind);
    }
    private void tri(float ax, float ay, float az, float bx, float by, float bz, float cx, float cy, float cz, float u, float v, float s, float kind) {
        push(ax, ay, az, u, v, s, kind); push(bx, by, bz, u, v, s, kind); push(cx, cy, cz, u, v, s, kind);
    }

    // Clouds were a box per tile, so they read as slabs. Now each cloud is inflated from its own outline: the field over
    // its opaque pixels sets a rounded height, 0 at the outline and full PILLOW_R px in, and the front and back surfaces
    // bulge that far either side of the block's middle plane, meeting at the outline. Shading follows the surface; the
    // game's art stays the texture, drawn as kind 4: cut out like kind 2, and the shader turns the black of the
    // checkered top pink (harsh on the curves).
    static final float PILLOW_R = 6f;
    private final Field cloudF = new Field();
    private void pillow(byte[] f, int[][] cls, int[][] ent, boolean[][] cut, int rows, int cols) {
        int w = cols * 8, h = rows * 8;
        cloudF.size(w, h);
        int y0 = h, x0 = w, y1 = -1, x1 = -1;
        for (int r = 1; r <= rows; r++) for (int c = 1; c <= cols; c++) {
            int e = ent[r][c];
            if (cls[r][c] != BLOCK || !cut[r][c] || e < 0) continue;
            y0 = Math.min(y0, (r - 1) * 8); y1 = Math.max(y1, r * 8 - 1); x0 = Math.min(x0, (c - 1) * 8); x1 = Math.max(x1, c * 8 - 1);
            for (int ty = 0; ty < 8; ty++) for (int tx = 0; tx < 8; tx++) {
                int sx = (e & 0x400) != 0 ? 7 - tx : tx, sy = (e & 0x800) != 0 ? 7 - ty : ty;
                if (bgPixel(f, bg[0].cb, e & 0x3FF, sx, sy) != 0) cloudF.lab[((r - 1) * 8 + ty) * w + (c - 1) * 8 + tx] = 1;
            }
        }
        if (y1 >= 0) cloudF.solve(PILLOW_R, y0, x0, y1, x1);
    }
    /** One 8x8 cloud cell as pillow surfaces, in 2 px steps; (gx, gy) = its pixel in the grid. */
    private void pillowCell(int gx, int gy, float x0, float y0, float zm, float hz, float ua, float ub, float va, float vb) {
        Field p = cloudF;
        for (int sy = 0; sy < 8; sy += 2) for (int sx = 0; sx < 8; sx += 2) {
            int x = gx + sx, y = gy + sy;
            if (!p.in(x, y) && !p.in(x + 1, y) && !p.in(x, y + 1) && !p.in(x + 1, y + 1)) continue;
            int[] lx = { sx, sx + 2, sx + 2, sx }, ly = { sy + 2, sy + 2, sy, sy };
            for (int side = 0; side < 2; side++) {
                for (int k = 0; k < 4; k++) {
                    int vx = gx + lx[k], vy = gy + ly[k];
                    float hgt = hz * p.at(vx, vy);
                    corner(k, x0 + lx[k], y0 - ly[k], side == 0 ? zm + hgt : zm - hgt,
                           ua + (ub - ua) * lx[k] / 8f, va + (vb - va) * ly[k] / 8f, p.shade(vx, vy, hz, side == 1));
                }
                emit(4, side == 1);
            }
        }
    }

    // Hills (1-1, 3-1, 5-1 ...) and natural ground keep their flat fronts but round off where they end: a quarter round
    // HILL_R px wide (and as deep, at most HILL_RZ of the block depth) along every edge of the front that is exposed, the
    // same test as the side faces. 0.29 works it out from the neighbouring cells: a field over the hill cells (0.26) cost
    // a pass over every pixel of the grid each frame, which the far cameras' bigger grid would have made heavy.
    static final float HILL_R = 5f, HILL_RZ = 0.3f;
    private static final int E_L = 1, E_R = 2, E_T = 4, E_B = 8, E_TL = 16, E_TR = 32, E_BL = 64, E_BR = 128;
    /** Ground and rock (not bricks, jars, ice ...): the grass-topped ground, its edges, sand, world 3-5's rock. */
    private static boolean natural(int t) {
        return t == 0x24A || t == 0x231 || (t >= 0x20B && t <= 0x20E) || (t >= 0x26E && t <= 0x28A);
    }
    /** 0..1 at (i, j) px in a cell: 0 on an exposed edge, 1 from HILL_R in. Outer corners round; inner ones too. */
    private static float edgeProfile(int fl, float i, float j) {
        i = Math.max(0, Math.min(8, i)); j = Math.max(0, Math.min(8, j));
        float R = HILL_R, x = Math.min((fl & E_L) != 0 ? i : R, (fl & E_R) != 0 ? 8 - i : R), y = Math.min((fl & E_T) != 0 ? j : R, (fl & E_B) != 0 ? 8 - j : R), d;
        if (x < R && y < R) d = Math.max(0, R - (float) Math.sqrt((R - x) * (R - x) + (R - y) * (R - y)));
        else d = Math.min(x, y);
        if ((fl & E_TL) != 0) d = Math.min(d, (float) Math.sqrt(i * i + j * j));
        if ((fl & E_TR) != 0) d = Math.min(d, (float) Math.sqrt((8 - i) * (8 - i) + j * j));
        if ((fl & E_BL) != 0) d = Math.min(d, (float) Math.sqrt(i * i + (8 - j) * (8 - j)));
        if ((fl & E_BR) != 0) d = Math.min(d, (float) Math.sqrt((8 - i) * (8 - i) + (8 - j) * (8 - j)));
        float t = 1 - d / R;
        return (float) Math.sqrt(Math.max(0, 1 - t * t));
    }
    /** Front of one cell at zt, dipping by rz toward its exposed edges (flags fl), in 2 px steps across them. */
    private void roundFront(int fl, float x0, float y0, float zt, float rz, float ua, float ub, float va, float vb) {
        int stepX = (fl & (E_L | E_R | 0xF0)) != 0 ? 2 : 8, stepY = (fl & (E_T | E_B | 0xF0)) != 0 ? 2 : 8;
        for (int sy = 0; sy < 8; sy += stepY) for (int sx = 0; sx < 8; sx += stepX) {
            int rx = sx + stepX, by = sy + stepY;
            roundCorner(0, fl, sx, by, x0, y0, zt, rz, ua, ub, va, vb); roundCorner(1, fl, rx, by, x0, y0, zt, rz, ua, ub, va, vb);
            roundCorner(2, fl, rx, sy, x0, y0, zt, rz, ua, ub, va, vb); roundCorner(3, fl, sx, sy, x0, y0, zt, rz, ua, ub, va, vb);
            emit(0, false);
        }
    }
    private void roundCorner(int k, int fl, int i, int j, float x0, float y0, float zt, float rz, float ua, float ub, float va, float vb) {
        float dx = rz * (edgeProfile(fl, i + 1, j) - edgeProfile(fl, i - 1, j)) / 2, dy = rz * (edgeProfile(fl, i, j + 1) - edgeProfile(fl, i, j - 1)) / 2;
        corner(k, x0 + i, y0 - j, zt - rz * (1 - edgeProfile(fl, i, j)), ua + (ub - ua) * i / 8f, va + (vb - va) * j / 8f, shadeN(-dx, dy, 1));
    }

    // Jars (21A-21F) are upright cylinders: half in front of the block's middle plane and half behind, radius 8 across
    // the metatile and rz in depth. Logs (220-222 outside the desert) lie along x and fill the block's depth like the box
    // they were (banks line up with them and the player stands on them): round at the front and the back (radius rz
    // about zf1 and zb1) with a flat top and bottom between. The art wraps round by angle (projected straight on, the
    // outline columns stretched over the curve's sides as dark bands from an angle). A jar's top gets a lid, a log's ends
    // their cut face.
    static final int CYL_SEG = 4;
    /** The cylinder through one 8x8 cell (corner x0, y0 in world units); axis = screen x of a jar's axis or screen y of
     *  a log's; the front half curves about z = zf1, the back half about zb1 (the same for a jar). */
    private void cylCell(boolean jar, float x0, float y0, float axis, float zf1, float zb1, float rz, float ua, float ub, float va, float vb) {
        float a = jar ? x0 - axis : -y0 - axis, q = (float) Math.PI / 16;
        float t0 = a * q, t1 = (a + 8) * q;
        for (int i = 0; i < CYL_SEG; i++) {
            float ta = t0 + (t1 - t0) * i / CYL_SEG, tb = t0 + (t1 - t0) * (i + 1) / CYL_SEG, fa = i / (float) CYL_SEG, fb = (i + 1) / (float) CYL_SEG;
            float sa = 8 * (float) Math.sin(ta), sb = 8 * (float) Math.sin(tb), ca = (float) Math.cos(ta), cb = (float) Math.cos(tb);
            // 0.39: an upright one has no back half: where its art is clear (a jar's narrow foot, a cactus's spines) the back's
            // outline showed through as dark steps down one side
            for (int side = 0; side < (jar ? 1 : 2); side++) {
                float g = side == 0 ? 1 : -1, zc = side == 0 ? zf1 : zb1, za = zc + g * rz * ca, zb = zc + g * rz * cb;
                if (jar) {
                    float xa = axis + sa, xb = axis + sb, uA = ua + (ub - ua) * fa, uB = ua + (ub - ua) * fb;
                    float shA = shadeN((float) Math.sin(ta), 0, g * ca), shB = shadeN((float) Math.sin(tb), 0, g * cb);
                    corner(0, xa, y0 - 8, za, uA, vb, shA); corner(1, xb, y0 - 8, zb, uB, vb, shB);
                    corner(2, xb, y0, zb, uB, va, shB); corner(3, xa, y0, za, uA, va, shA);
                } else {
                    float ya = axis + sa, yb = axis + sb, vA = va + (vb - va) * fa, vB = va + (vb - va) * fb;
                    float shA = shadeN(0, -(float) Math.sin(ta), g * ca), shB = shadeN(0, -(float) Math.sin(tb), g * cb);
                    corner(0, x0, -yb, zb, ua, vB, shB); corner(1, x0 + 8, -yb, zb, ub, vB, shB);
                    corner(2, x0 + 8, -ya, za, ub, vA, shA); corner(3, x0, -ya, za, ua, vA, shA);
                }
                emit(2, side == 1);
            }
        }
        if (jar || zf1 <= zb1) return;
        // a log's flat top (the cell holding the top of the round) and bottom, from the back round to the front one,
        // textured with the wood inside the outline (its rows 2-6 across the depth; row 0 is the dark outline)
        float wa = va + (vb - va) * 0.25f, wb = va + (vb - va) * 0.75f;
        if (a <= -8) { float y = -(axis - 8); quad(x0, y, zf1, x0 + 8, y, zf1, x0 + 8, y, zb1, x0, y, zb1, ua, wa, ub, wa, ub, wb, ua, wb, 0.86f, 2); }
        if (a + 8 >= 8) { float y = -(axis + 8); quad(x0, y, zb1, x0 + 8, y, zb1, x0 + 8, y, zf1, x0, y, zf1, ua, wb, ub, wb, ub, wa, ua, wa, 0.45f, 2); }
    }
    /** A jar's lid (at world y = y0) or a log's end (at x0 or x0 + 8; end = -1 left, 1 right) across one cell's slice. */
    private void cylCap(boolean jar, float x0, float y0, float axis, float zf1, float zb1, float rz, float u, float v, int end) {
        float a = jar ? x0 - axis : -y0 - axis, q = (float) Math.PI / 16;
        float t0 = a * q, t1 = (a + 8) * q;
        float s = jar ? 0.92f : 0.66f;
        for (int i = 0; i < CYL_SEG; i++) {
            float ta = t0 + (t1 - t0) * i / CYL_SEG, tb = t0 + (t1 - t0) * (i + 1) / CYL_SEG;
            float sa = 8 * (float) Math.sin(ta), sb = 8 * (float) Math.sin(tb), ca = rz * (float) Math.cos(ta), cb = rz * (float) Math.cos(tb);
            if (jar) {   // facing up: (centre, a, b) in front of the axis, (centre, b, a) behind
                float yy = y0, xa = axis + sa, xb = axis + sb;
                tri(axis, yy, zf1, xa, yy, zf1 + ca, xb, yy, zf1 + cb, u, v, s, 0);
                tri(axis, yy, zb1, xb, yy, zb1 - cb, xa, yy, zb1 - ca, u, v, s, 0);
            } else {     // facing -x (left end) or +x (right end): the two half rounds and the band between them
                float xx = end < 0 ? x0 : x0 + 8, ya = -(axis + sa), yb = -(axis + sb), yc = -axis;
                if (end > 0) { tri(xx, yc, zf1, xx, ya, zf1 + ca, xx, yb, zf1 + cb, u, v, s, 0); tri(xx, yc, zb1, xx, yb, zb1 - cb, xx, ya, zb1 - ca, u, v, s, 0); }
                else { tri(xx, yc, zf1, xx, yb, zf1 + cb, xx, ya, zf1 + ca, u, v, s, 0); tri(xx, yc, zb1, xx, ya, zb1 - ca, xx, yb, zb1 - cb, u, v, s, 0); }
            }
        }
        if (jar || zf1 <= zb1) return;
        float xx = end < 0 ? x0 : x0 + 8, yt = -(axis + 8 * (float) Math.sin(t0)), yb = -(axis + 8 * (float) Math.sin(t1));
        // band A (top, back) B (top, front) C (bottom, front) D (bottom, back); A B C faces +x
        if (end > 0) { tri(xx, yt, zb1, xx, yt, zf1, xx, yb, zf1, u, v, s, 0); tri(xx, yt, zb1, xx, yb, zf1, xx, yb, zb1, u, v, s, 0); }
        else { tri(xx, yt, zb1, xx, yb, zf1, xx, yt, zf1, u, v, s, 0); tri(xx, yt, zb1, xx, yb, zb1, xx, yb, zf1, u, v, s, 0); }
    }

    // Ice (4-x), bone (desert) and sand blocks are separate blocks in the art: each metatile's front gets a BEVEL px
    // chamfer on its four edges, mitred at the corners, so neighbours meet in a groove.
    static final float BEVEL = 2f;
    /** One cell of a bevelled block: its metatile's edges are on the left or right (left) and top or bottom (top). */
    private void bevelCell(float x0, float y0, float zt, boolean left, boolean top, float ua, float ub, float va, float vb) {
        float b = BEVEL, x1 = x0 + 8, y1 = y0 - 8, zo = zt - b;
        float ixl = left ? x0 + b : x0, ixr = left ? x1 : x1 - b, iyt = top ? y0 - b : y0, iyb = top ? y1 : y1 + b;
        bv(0, ixl, iyb, zt); bv(1, ixr, iyb, zt); bv(2, ixr, iyt, zt); bv(3, ixl, iyt, zt); bvEmit(x0, y0, ua, ub, va, vb, 1f);
        if (left) { bv(0, x0, y1, zo); bv(1, ixl, iyb, zt); bv(2, ixl, iyt, zt); bv(3, x0, y0, zo); bvEmit(x0, y0, ua, ub, va, vb, 0.8f); }
        else { bv(0, ixr, iyb, zt); bv(1, x1, y1, zo); bv(2, x1, y0, zo); bv(3, ixr, iyt, zt); bvEmit(x0, y0, ua, ub, va, vb, 0.72f); }
        if (top) { bv(0, ixl, iyt, zt); bv(1, ixr, iyt, zt); bv(2, x1, y0, zo); bv(3, x0, y0, zo); bvEmit(x0, y0, ua, ub, va, vb, 0.95f); }
        else { bv(0, x0, y1, zo); bv(1, x1, y1, zo); bv(2, ixr, iyb, zt); bv(3, ixl, iyb, zt); bvEmit(x0, y0, ua, ub, va, vb, 0.55f); }
    }
    private void bv(int k, float x, float y, float z) { cq[k * 6] = x; cq[k * 6 + 1] = y; cq[k * 6 + 2] = z; }
    /** Texture by straight projection onto the cell (corner x0, y0), then emit. */
    private void bvEmit(float x0, float y0, float ua, float ub, float va, float vb, float s) {
        for (int k = 0; k < 4; k++) {
            cq[k * 6 + 3] = ua + (ub - ua) * (cq[k * 6] - x0) / 8; cq[k * 6 + 4] = va + (vb - va) * (y0 - cq[k * 6 + 1]) / 8; cq[k * 6 + 5] = s;
        }
        emit(0, false);
    }

    // Grass, vines and palm tops are flat cut-outs, paper-thin from the diorama and low cameras. A second copy turned
    // 90 degrees about the metatile's vertical centre line crosses the first, so they have body from any side.
    private void crossCell(float x0, float y0, float mcx, float ua, float ub, float va, float vb) {
        float za = x0 - mcx, zb = za + 8;
        for (int side = 0; side < 2; side++) {
            corner(0, mcx, y0 - 8, za, ua, vb, 0.85f); corner(1, mcx, y0 - 8, zb, ub, vb, 0.85f);
            corner(2, mcx, y0, zb, ub, va, 0.85f); corner(3, mcx, y0, za, ua, va, 0.85f);
            emit(2, side == 1);
        }
    }

    // Water (1-x / 3-x pools 004 / 005, 4-x sea 008 / 00A) stood upright as a wall of waves. Now it is a volume: its front
    // (the game's art, waves on top) a little behind the ground's front edge, and a level surface from there back,
    // textured with the water body, so blocks and logs sit in it.
    static final float WATER_BACK = 0.25f;   // front, as a fraction of the block depth behind the ground's front plane
    private void water(byte[] f, int[][] shp, int[][] cls, int[][] nst, boolean[][] hil, int[][] ent, int r, int c,
                       float x0, float y0, float zf, float zb, int D, float ua, float ub, float va, float vb) {
        int id = meta(f, camX + Math.round(x0) + 4, camY - Math.round(y0) + 4);
        boolean sand = id == 0x050 || id == 0x051;      // the desert's floor is walked in, not on: level with the sprites
        float x1 = x0 + 8, y1 = y0 - 8, zw = zf + nst[r][c] * D * HILL_STEP - (sand ? 0 : WATER_BACK * D);
        quad(x0, y1, zw, x1, y1, zw, x1, y0, zw, x0, y0, zw, ua, vb, ub, vb, ub, va, ua, va, 1f, 2);
        float sa = (ua + ub) / 2, mv = (va + vb) / 2;
        if (shp[r][c - 1] != SH_WATER && blockFront(cls, nst, hil, r, c - 1, zf, D) < zw)
            quad(x0, y1, zb, x0, y1, zw, x0, y0, zw, x0, y0, zb, sa, vb, sa, vb, sa, va, sa, va, 0.66f, 2);
        if (shp[r][c + 1] != SH_WATER && blockFront(cls, nst, hil, r, c + 1, zf, D) < zw)
            quad(x1, y1, zw, x1, y1, zb, x1, y0, zb, x1, y0, zw, sa, vb, sa, vb, sa, va, sa, va, 0.66f, 2);
        if (shp[r - 1][c] == SH_WATER) return;
        // the surface: level with the top of the waves in this column of cells, textured with the body below
        int e = ent[r][c], top = 8;
        for (int ty = 0; ty < 8 && top == 8; ty++) for (int tx = 0; tx < 8; tx++) {
            int px = (e & 0x400) != 0 ? 7 - tx : tx, py = (e & 0x800) != 0 ? 7 - ty : ty;
            if (bgPixel(f, bg[0].cb, e & 0x3FF, px, py) != 0) { top = ty; break; }
        }
        if (top == 8) return;
        float ys = y0 - top;
        int eb = shp[r + 1][c] == SH_WATER ? ent[r + 1][c] : e;
        uvSlot(slot(f, false, bg[0].cb, eb & 0x3FF, eb >> 12, true));
        float ba = (eb & 0x400) != 0 ? u1 : u0, bb = (eb & 0x400) != 0 ? u0 : u1, bva = (eb & 0x800) != 0 ? v1 : v0, bvb = (eb & 0x800) != 0 ? v0 : v1;
        for (float z = zw; z > zb + 0.01f; z -= 8) {
            float z2 = Math.max(zb, z - 8), vz = bva + (bvb - bva) * (z - z2) / 8;
            quad(x0, ys, z, x1, ys, z, x1, ys, z2, x0, ys, z2, ba, bva, bb, bva, bb, vz, ba, vz, 0.95f, 0);
        }
    }
    static final int SH_NONE = 0, SH_JAR = 1, SH_LOG = 2, SH_WATER = 3, SH_CROSS = 4, SH_BEVEL = 5, SH_SILH = 6;
    private static int shapeOf(int t, int ws) {
        if (t < 0) return SH_NONE;
        // 0.39: the jar's art is shared by 149-14E, 1F5 and 2C6 too; a jar topped with 2C6 (5-2's, the rows in 6-1) had a cube for a top
        if ((t >= 0x21A && t <= 0x21F) || t == 0x2C6 || t == 0x1F5 || (t >= 0x149 && t <= 0x14E)) return SH_JAR;
        if ((t == 0x205 || t == 0x206) && (ws == 1 || ws == 5)) return SH_JAR;     // the desert's cacti: round too, no lid
        if ((t == 0x208 || t == 0x209) && ws == 3) return SH_JAR;                  // world 4's sand pillars: as boxes their clear sides filled with the dark outline
        if ((t == 0x242 || t == 0x243) && ws == 6) return SH_JAR;                  // world 7's columns
        if (t == 0x127 || (t == 0x105 && ws == 6)) return SH_CROSS;                // a vine's top you can stand on, 7-2's chain
        if ((t == 0x00C || t == 0x011 || t == 0x016 || t == 0x017) && ws == 3) return SH_WATER;   // the sea where a whale sits in it
        if (t >= 0x220 && t <= 0x222 && ws != 1 && ws != 5) return SH_LOG;         // in the deserts these are bones (see silhouette)
        if (t == 0x104 || t == 0x117) return SH_BEVEL;
        if ((t == 0x004 || t == 0x005) && ws != 1 && ws != 5 && ws != 6) return SH_WATER;
        if ((t == 0x008 || t == 0x00A) && ws == 3) return SH_WATER;
        if ((t == 0x050 || t == 0x051) && (ws == 1 || ws == 5)) return SH_WATER;    // the desert's floor (no collision: it's soft)
        if ((t >= 0x025 && t <= 0x02F) || (t >= 0x095 && t <= 0x09C) || (t >= 0x059 && t <= 0x05C)) return SH_CROSS;
        return SH_NONE;
    }

    // 0.38: a solid metatile that is largely clear (a whale's round back, half-height bars and platforms, mushroom blocks, a
    // boulder). As a block, its clear texels took the nearest colour, usually the dark outline, so it was a square slab with
    // wedges of outline in its corners. Now its front drops the clear texels and walls run back from each edge of the real
    // outline, coloured from just inside the outline. World 4's whales are all listed: only some of their cells are clear enough
    // to pass the count, and they have to meet. Ground and hill edges round in their own way (natural, hill); the walls of 26E
    // and on are keyed by level state and only clip a corner.
    private final HashMap<Integer, Boolean> silhCache = new HashMap<Integer, Boolean>();
    private boolean silhouette(byte[] f, int t, int ws) {
        if (t < 0) return false;
        if (ws == 3 && ((t >= 0x24E && t <= 0x25A) || t == 0x10A || t == 0x03B || t == 0x03C)) return true;
        if (t == 0x113) return true;                                                  // 0.39: a log's round end, facing you: a stack of them in a pot
        if ((ws == 1 || ws == 5) && t >= 0x220 && t <= 0x222) return true;            // 0.39: the deserts' bones, see-through between the ribs
        if (t >> 8 == 0 || t >= 0x26E || t == 0x202 || natural(t) || hill(t) || cloud(t) || ladder(t)) return false;
        Boolean b = silhCache.get(t);
        if (b != null) return b;
        boolean s = false;
        if (quad(t)) {
            int n = 0;
            for (int i = 0; i < 4; i++) for (int y = 0; y < 8; y++) for (int x = 0; x < 8; x++)
                if (bgPixel(f, bg[0].cb, q[i] & 0x3FF, x, y) != 0) n++;
            s = n > 0 && 256 - n >= 24;
        }
        silhCache.put(t, s);
        return s;
    }
    /** Is the pixel at i, j (0..7, or one past, into the next cell) of a cell solid? Solid blocks count whole, others not at all. */
    private boolean silhSolid(byte[] f, int[][] cls, int[][] ent, int[][] shp, int R, int C, int i, int j) {
        if (i < 0) { C--; i += 8; } else if (i > 7) { C++; i -= 8; }
        if (j < 0) { R--; j += 8; } else if (j > 7) { R++; j -= 8; }
        if (R < 0 || C < 0 || R >= cls.length || C >= cls[0].length || cls[R][C] != BLOCK) return false;
        int sh = shp[R][C];
        if (sh != SH_SILH) return sh == SH_NONE || sh == SH_BEVEL;
        int e = ent[R][C];
        return e >= 0 && bgPixel(f, bg[0].cb, e & 0x3FF, (e & 0x400) != 0 ? 7 - i : i, (e & 0x800) != 0 ? 7 - j : j) != 0;
    }
    private float silhU, silhV;
    /** A wall's colour: the texel k px in from the edge at i, j, past the outline if it can be. */
    private void silhColour(byte[] f, int[][] ent, int R, int C, int i, int j, int di, int dj) {
        for (int k = 2; k >= 0; k--) {
            int a = i + di * k, b = j + dj * k, rr = R, cc = C;
            if (a < 0) { cc--; a += 8; } else if (a > 7) { cc++; a -= 8; }
            if (b < 0) { rr--; b += 8; } else if (b > 7) { rr++; b -= 8; }
            if (rr < 0 || cc < 0 || rr >= ent.length || cc >= ent[0].length || ent[rr][cc] < 0) continue;
            int e = ent[rr][cc], px = (e & 0x400) != 0 ? 7 - a : a, py = (e & 0x800) != 0 ? 7 - b : b;
            if (bgPixel(f, bg[0].cb, e & 0x3FF, px, py) == 0) continue;
            uvSlot(slot(f, false, bg[0].cb, e & 0x3FF, e >> 12));
            silhU = u0 + (u1 - u0) * (px + 0.5f) / 8; silhV = v0 + (v1 - v0) * (py + 0.5f) / 8;
            return;
        }
    }
    private void silhCell(byte[] f, int[][] cls, int[][] ent, int[][] shp, int R, int C, float x0, float y0, float zt, float zb, float ua, float ub, float va, float vb) {
        quad(x0, y0 - 8, zt, x0 + 8, y0 - 8, zt, x0 + 8, y0, zt, x0, y0, zt, ua, vb, ub, vb, ub, va, ua, va, 1f, 2);
        int e = ent[R][C];
        if (e < 0) return;
        for (int j = 0; j < 8; j++) for (int i = 0; i < 8; i++) {
            if (bgPixel(f, bg[0].cb, e & 0x3FF, (e & 0x400) != 0 ? 7 - i : i, (e & 0x800) != 0 ? 7 - j : j) == 0) continue;
            float xa = x0 + i, xb = xa + 1, ya = y0 - j, yb = ya - 1;
            if (!silhSolid(f, cls, ent, shp, R, C, i, j - 1)) { silhColour(f, ent, R, C, i, j, 0, 1); quad(xa, ya, zt, xb, ya, zt, xb, ya, zb, xa, ya, zb, silhU, silhV, silhU, silhV, silhU, silhV, silhU, silhV, 0.86f, 0); }
            if (!silhSolid(f, cls, ent, shp, R, C, i, j + 1)) { silhColour(f, ent, R, C, i, j, 0, -1); quad(xa, yb, zb, xb, yb, zb, xb, yb, zt, xa, yb, zt, silhU, silhV, silhU, silhV, silhU, silhV, silhU, silhV, 0.45f, 0); }
            if (!silhSolid(f, cls, ent, shp, R, C, i - 1, j)) { silhColour(f, ent, R, C, i, j, 1, 0); quad(xa, yb, zb, xa, yb, zt, xa, ya, zt, xa, ya, zb, silhU, silhV, silhU, silhV, silhU, silhV, silhU, silhV, 0.66f, 0); }
            if (!silhSolid(f, cls, ent, shp, R, C, i + 1, j)) { silhColour(f, ent, R, C, i, j, -1, 0); quad(xb, yb, zt, xb, yb, zb, xb, ya, zb, xb, ya, zt, silhU, silhV, silhU, silhV, silhU, silhV, silhU, silhV, 0.66f, 0); }
        }
    }

    // Round sprites (option, 0.25): instead of thick walls, each sprite is inflated like the clouds from its outline, in a
    // field over the screen where every object (its OAM group) has its own label, so a character made of several sprites
    // rounds as one. The front bulges ROUND_T forward of its plane and the back as far behind.
    static final float ROUND_R = 4f, ROUND_T = 3f;
    volatile boolean round = false;
    private final Field spriteF = new Field();
    private boolean roundable(int s, int a0, int a1) {
        if ((a0 & 0x300) == 0x200 || ((a0 >> 10) & 3) == 2 || (a0 & 0x2000) != 0 || (a0 >> 14) == 3 || (a0 & 0x100) != 0) return false;
        int x = a1 & 511;
        return thick && !member[s] && !(s < 8 && x == 0);
    }
    private void spriteField(byte[] f, boolean map1d) {
        spriteF.size(W, H);
        for (int s = 127; s >= 0; s--) {
            int a0 = u16(f, OAM + s * 8), a1 = u16(f, OAM + s * 8 + 2), a2 = u16(f, OAM + s * 8 + 4);
            if (!roundable(s, a0, a1)) continue;
            int w = OBJ_SIZES[(a0 >> 14) * 4 + (a1 >> 14)][0], h = OBJ_SIZES[(a0 >> 14) * 4 + (a1 >> 14)][1];
            int y = a0 & 255, x = a1 & 511, tile = a2 & 0x3FF, l = (box[s] != null ? gRoot[s] : s) + 1;
            if (y >= 160) y -= 256;
            if (x >= 240) x -= 512;
            for (int sy = Math.max(0, y); sy < Math.min(H, y + h); sy++) for (int sx = Math.max(0, x); sx < Math.min(W, x + w); sx++) {
                int tx = sx - x, ty = sy - y;
                if ((a1 & 0x1000) != 0) tx = w - 1 - tx;
                if ((a1 & 0x2000) != 0) ty = h - 1 - ty;
                int t = (tile + (ty >> 3) * (map1d ? w / 8 : 32) + (tx >> 3)) & 0x3FF;
                int o = VRAM + 0x10000 + t * 32 + (ty & 7) * 4 + ((tx & 7) >> 1);
                if (o < VRAM + 0x18000 && ((u8(f[o]) >> ((tx & 1) * 4)) & 15) != 0) spriteF.lab[sy * W + sx] = l;
            }
        }
        spriteF.solve(ROUND_R);
    }
    /** One 8x8 sprite tile (slot sl, screen corner qx, qy) as a rounded front and back, in 2 px steps. */
    private void roundTile(int sl, float qx, float qy, boolean hf, boolean vf, float zs, float kind) {
        int ax = (sl % (ATLAS_W / 8)) * 8, ay = (sl / (ATLAS_W / 8)) * 8;
        uvSlot(sl);
        float ua = hf ? u1 : u0, ub = hf ? u0 : u1, va = vf ? v1 : v0, vb = vf ? v0 : v1;
        int gx = Math.round(qx), gy = Math.round(qy);
        for (int sy = 0; sy < 8; sy += 2) for (int sx = 0; sx < 8; sx += 2) {
            boolean any = false;
            for (int j = 0; j < 2 && !any; j++) for (int i = 0; i < 2; i++)
                if (opaqueTexel(ax, ay, hf ? 7 - sx - i : sx + i, vf ? 7 - sy - j : sy + j)) { any = true; break; }
            if (!any) continue;
            int[] lx = { sx, sx + 2, sx + 2, sx }, ly = { sy + 2, sy + 2, sy, sy };
            for (int side = 0; side < 2; side++) {
                for (int k = 0; k < 4; k++) {
                    int vx = gx + lx[k], vy = gy + ly[k];
                    float hgt = ROUND_T * spriteF.at(vx, vy);
                    corner(k, qx + lx[k], -qy - ly[k], side == 0 ? zs + hgt : zs - hgt,
                           ua + (ub - ua) * lx[k] / 8f, va + (vb - va) * ly[k] / 8f, sShade * spriteF.shade(vx, vy, ROUND_T, side == 1));
                }
                emit(kind, side == 1);
            }
        }
    }
    /** blockFront for hiding a neighbour's side face: a jar or log doesn't fill its cell, so the face next to it stays. */
    private static float solidFront(int[][] cls, int[][] nst, boolean[][] hil, int[][] shp, int r, int c, float zf, int D) {
        return shp[r][c] == SH_JAR || shp[r][c] == SH_LOG || shp[r][c] == SH_SILH ? Float.NEGATIVE_INFINITY : blockFront(cls, nst, hil, r, c, zf, D);
    }
    private static int fdiv(int a, int b) { int r = a / b; if ((a % b != 0) && ((a < 0) != (b < 0))) r--; return r; }

    /** camDist: distance from the camera to the z = 0 plane (for the backdrop scale). */
    void build(byte[] f, float camDist) {
        if (rom != quadRom) { quadRom = rom; propCache.clear(); silhCache.clear(); }
        readBgs(f);
        view(f);
        skyRgb = bgr(f, 0);
        if (!mapped) { propCache.clear(); silhCache.clear(); }   // tile graphics change between areas
        slots.clear(); nextSlot = 0;
        nv = 0;
        int bld = u16(f, IO + 0x50), bfx = (bld >> 6) & 3, evy = Math.min(16, u16(f, IO + 0x54) & 31);
        boolean whole = mapped && (u16(f, IO) & 0xE000) == 0 && (bld & 1) != 0;
        bgDark = whole && bfx == 3 ? evy : 0; bgLight = whole && bfx == 2 ? evy : 0;
        int D = depth;
        float zf = D / 2f, zb = -D / 2f;
        float backZ = back.equals("far") ? -D * 2.5f : zb - 0.01f;
        boolean backOff = back.equals("off");
        Bg b = bg[0];
        // sprite priority mask: opaque BG0 pixels
        Arrays.fill(mask, (byte) 0);
        if (b.on) for (int sy = 0; sy < H; sy++) for (int sx = 0; sx < W; sx++) {
            int lx = b.hofs + sx, ly = b.vofs + sy, e = entry(f, b, lx, ly);
            int tx = lx & 7, ty = ly & 7;
            if ((e & 0x400) != 0) tx = 7 - tx;
            if ((e & 0x800) != 0) ty = 7 - ty;
            if (bgPixel(f, b.cb, e & 0x3FF, tx, ty) != 0) mask[sy * 256 + sx] = (byte) 255;
        }
        maskChanged = true;
        // BG0 cells on screen, aligned to its tile grid
        // the screen's cells plus a margin of M cells all round (low / turned cameras see past the screen edges). Outside
        // the screen the BG map may not be streamed in yet, so margin cells take their tiles from the level data + ROM
        // quads (the same map entries the game would write), which is only possible while mapped.
        final int ML = mapped ? (int) Math.ceil(marL / 8) : 0, MR = mapped ? (int) Math.ceil(marR / 8) : 0, MT = mapped ? (int) Math.ceil(marT / 8) : 0, MB = mapped ? (int) Math.ceil(marB / 8) : 0;
        int tx0 = fdiv(b.hofs, 8) - ML, ty0 = fdiv(b.vofs, 8) - MT;
        int cols = fdiv(b.hofs + W - 1, 8) + MR - tx0 + 1, rows = fdiv(b.vofs + H - 1, 8) + MB - ty0 + 1;
        int[][] cls = new int[rows + 2][cols + 2], ent = new int[rows + 2][cols + 2], nst = new int[rows + 2][cols + 2];
        boolean[][] hil = new boolean[rows + 2][cols + 2], cut = new boolean[rows + 2][cols + 2], rnd = new boolean[rows + 2][cols + 2], lad = new boolean[rows + 2][cols + 2];
        int[][] shp = new int[rows + 2][cols + 2];
        int ws = mapped ? var16(f, 0x030019FC) : -1;       // world graphics set (RAM-MAP)
        int[][][] pyr = new int[rows + 2][cols + 2][];
        pyrMemo.clear();
        if (mapped) hills(f);
        int aw = vertical ? 256 : pages * 256, ah = vertical ? pages * 240 : 240;
        for (int r = -1; r <= rows; r++) for (int c = -1; c <= cols; c++) {
            int sx = (tx0 + c) * 8 - b.hofs, sy = (ty0 + r) * 8 - b.vofs;
            boolean on = sx > -8 && sx < W && sy > -8 && sy < H;
            int wx = camX + sx, wy = camY + sy, mt = mapped ? metaOut(f, wx + 4, wy + 4, aw, ah, ws) : -1;
            int e, k;
            if (on) e = entry(f, b, (tx0 + c) * 8, (ty0 + r) * 8);
            else e = mt >= 0 && quad(mt) ? q[((wy >> 3) & 1) * 2 + ((wx >> 3) & 1)] : -1;
            if (e < 0 || !b.on) k = SKY;
            else {
                boolean empty = emptyBgTile(f, b.cb, e & 0x3FF);
                if (!mapped) k = empty ? SKY : BACK;
                else {
                    k = classOf(f, mt);
                    if (k == BLOCK && (mt == 0x127 || (mt == 0x105 && ws == 6))) k = PROP;   // see-through, solid or not
                    if (empty && k != BLOCK) k = SKY;
                }
                if (mapped && k != SKY && k != BLOCK && pyrId(mt)) {
                    int key = (camX + sx + 4) >> 4 << 8 | ((camY + sy + 4) >> 4 & 255);
                    int[] p = pyrMemo.get(key);
                    if (p == null && !pyrMemo.containsKey(key)) { p = pyramid(f, (camX + sx + 4) >> 4, (camY + sy + 4) >> 4); pyrMemo.put(key, p); }
                    if (p != null) { k = PYR; pyr[r + 1][c + 1] = p; }
                }
            }
            cls[r + 1][c + 1] = k; ent[r + 1][c + 1] = e;
            nst[r + 1][c + 1] = mapped ? nestAt(Math.max(areaX0, Math.min(aw - 1, wx + 4)), Math.max(0, Math.min(ah - 1, wy + 4))) : 0;
            hil[r + 1][c + 1] = mt >= 0 && hill(mt);
            cut[r + 1][c + 1] = cloud(mt);
            rnd[r + 1][c + 1] = mt >= 0 && (hill(mt) || natural(mt));
            lad[r + 1][c + 1] = ladder(mt);
            int sh = mapped && k != SKY ? shapeOf(mt, ws) : SH_NONE;
            if (sh == SH_CROSS && k != PROP) sh = SH_NONE;
            if (sh == SH_NONE && mapped && k != SKY && silhouette(f, mt, ws)) sh = SH_SILH;
            if (sh == SH_WATER) cls[r + 1][c + 1] = PROP;      // not a block: its neighbours keep their side faces
            shp[r + 1][c + 1] = sh;
        }
        // Bodies under a ledge (hills, houses, trunks under their caps): SMA's hill tops are stand-on-top metatiles
        // (blocks) but the bodies are plain opaque scenery (back wall), so the cap stuck out as a separate slab over a
        // recessed body. Whatever opaque scenery hangs directly under a block, down to the first gap, is block too
        // (mostly-opaque edge tiles included; their clear pixels take the sky colour on the faces).
        // 0.35: not a ladder: it is mostly opaque too, but what shows between its rungs is the far layer, and as a block (the
        // stand-on kind were blocks anyway, see classOf) the gaps took the colour next to them, the rails' dark green, on a solid
        // slab sticking out of the wall.
        if (mapped) for (int c = 0; c < cols + 2; c++) for (int r = 1; r < rows + 2; r++) {
            if (cls[r - 1][c] != BLOCK) continue;
            int k = cls[r][c], e = ent[r][c];
            if (shp[r][c] == SH_WATER || shp[r][c] == SH_CROSS || lad[r][c]) continue;
            if (k == BACK || (k == PROP && e >= 0 && opaquePixels(f, b.cb, e & 0x3FF) >= 40)) cls[r][c] = BLOCK;
        }
        // The ground under a hill that comes forward comes forward with it (0.14): otherwise the hill's front face stood
        // in front of the ground's front edge and the hill hung over it. Down each column, a non-hill block directly
        // under a forward hill (or under ground already pulled forward) takes its nest.
        if (mapped) for (int c = 0; c < cols + 2; c++) {
            int carry = 0;
            for (int r = 0; r < rows + 2; r++) {
                if (cls[r][c] != BLOCK) { carry = 0; continue; }
                if (hil[r][c]) carry = nst[r][c];
                else if (carry > nst[r][c]) nst[r][c] = carry;
            }
        }
        // 0.20: a bridge comes forward with its banks. A run of non-hill blocks (1-1's logs over the waterfalls) between
        // two blocks that sit further forward (the ground under forward hills, pulled forward above) takes the nearer
        // of the two. It stayed at the back, so the log sank into a notch between the banks and, from the low camera,
        // its end ran into the hill's face.
        if (mapped) for (int r = 0; r < rows + 2; r++) for (int c = 1; c < cols + 1; ) {
            if (cls[r][c] != BLOCK || hil[r][c] || cls[r][c - 1] != BLOCK) { c++; continue; }
            int n = nst[r][c], e = c;
            while (e + 1 < cols + 2 && cls[r][e + 1] == BLOCK && !hil[r][e + 1] && nst[r][e + 1] == n) e++;
            if (e + 1 < cols + 2 && cls[r][e + 1] == BLOCK) {
                int m = Math.min(nst[r][c - 1], nst[r][e + 1]);
                if (m > n) for (int k = c; k <= e; k++) nst[r][k] = m;
            }
            c = e + 1;
        }
        boolean clouds = false;
        for (int r = 1; r <= rows && !clouds; r++) for (int c = 1; c <= cols; c++) if (cut[r][c] && cls[r][c] == BLOCK) { clouds = true; break; }
        if (clouds) pillow(f, cls, ent, cut, rows, cols);
        for (int r = 0; r < rows; r++) for (int c = 0; c < cols; c++) {
            int k = cls[r + 1][c + 1];
            if (k == SKY) continue;
            int e = ent[r + 1][c + 1], sh = shp[r + 1][c + 1];
            boolean cl = cut[r + 1][c + 1];
            boolean clear = cl || sh == SH_JAR || sh == SH_LOG || sh == SH_WATER || sh == SH_SILH;   // shapes with their own outline
            // 0.39: a cloud's shape comes from its own field, so its texels are all solid: its clear ones were holes on its top, the
            // sky through them, which only showed against a night sky (5-2's black squares)
            uvSlot(slot(f, false, b.cb, e & 0x3FF, e >> 12, k == BLOCK && (!clear || cl)));
            float ua = (e & 0x400) != 0 ? u1 : u0, ub = (e & 0x400) != 0 ? u0 : u1, va = (e & 0x800) != 0 ? v1 : v0, vb = (e & 0x800) != 0 ? v0 : v1;
            float x0 = (tx0 + c) * 8 - b.hofs, x1 = x0 + 8, y0 = -((ty0 + r) * 8 - b.vofs), y1 = y0 - 8;
            int wx = camX + Math.round(x0), wy = camY - Math.round(y0);          // the cell's world pixel
            if (sh == SH_WATER) { water(f, shp, cls, nst, hil, ent, r + 1, c + 1, x0, y0, zf, zb, D, ua, ub, va, vb); continue; }
            if (k == BACK) {
                if (!backOff) quad(x0, y1, backZ, x1, y1, backZ, x1, y0, backZ, x0, y0, backZ, ua, vb, ub, vb, ub, va, ua, va, 0.8f, 1);
                continue;
            }
            if (k == PROP) {
                quad(x0, y1, 0, x1, y1, 0, x1, y0, 0, x0, y0, 0, ua, vb, ub, vb, ub, va, ua, va, 1f, 2);
                if (sh == SH_CROSS) crossCell(x0, y0, (wx & ~15) + 8 - camX, ua, ub, va, vb);
                continue;
            }
            if (k == PYR) {
                // the cell lies on one face (the ridge is on a 16 px boundary); the face is a plane, so the quad is flat.
                // Clear texels of the slope tiles drop out (kind 2), leaving the triangle's own outline.
                int[] p = pyr[r + 1][c + 1];
                float[] ps = { p[0] - camX, p[1] - camY, p[2] - camY, p[3] };
                boolean left = x0 + 4 < ps[0];
                float d = D * PYR_DEPTH, sy0 = -y0, sy1 = -y1;
                quad(x0, y1, pyrZ(ps, left, x0, sy1, zb, d), x1, y1, pyrZ(ps, left, x1, sy1, zb, d),
                     x1, y0, pyrZ(ps, left, x1, sy0, zb, d), x0, y0, pyrZ(ps, left, x0, sy0, zb, d),
                     ua, vb, ub, vb, ub, va, ua, va, 1f, 2);
                continue;
            }
            // front hills come forward (thicker toward the camera); a face is drawn where the neighbour isn't a block or
            // sits further back
            float zt = blockFront(cls, nst, hil, r + 1, c + 1, zf, D), fk = 0;
            if (cl) { pillowCell(c * 8, r * 8, x0, y0, (zt + zb) / 2, (zt - zb) / 2, ua, ub, va, vb); continue; }
            if (sh == SH_SILH) { silhCell(f, cls, ent, shp, r + 1, c + 1, x0, y0, zt, zb, ua, ub, va, vb); continue; }
            if (sh == SH_JAR || sh == SH_LOG) {
                boolean jar = sh == SH_JAR;
                float axis = jar ? (wx & ~15) + 8 - camX : (wy & ~15) + 8 - camY, zm = (zt + zb) / 2, rz = Math.min(8, (zt - zb) / 2);   // round, not deeper than wide
                float z1 = jar ? zm : zt - rz, z2 = jar ? zm : zb + rz;
                cylCell(jar, x0, y0, axis, z1, z2, rz, ua, ub, va, vb);
                int mt = meta(f, wx + 4, wy + 4);
                if (jar && (mt >= 0x21A || mt == 0x1F5 || (mt >= 0x149 && mt <= 0x14E)) && shp[r][c + 1] != SH_JAR) cylCap(true, x0, y0, axis, z1, z2, rz, (ua + ub) / 2, va + (vb - va) * 0.2f, 0);
                if (!jar && mt == 0x220 && (wx & 15) == 0) cylCap(false, x0, y0, axis, z1, z2, rz, ua + (ub - ua) * 0.3f, (va + vb) / 2, -1);
                if (!jar && mt == 0x222 && (wx & 15) == 8) cylCap(false, x0, y0, axis, z1, z2, rz, ua + (ub - ua) * 0.7f, (va + vb) / 2, 1);
                continue;
            }
            // bevelled blocks and rounded hills bring their side faces in to where the front's edge now is
            float zs = zt;
            if (sh == SH_BEVEL) { bevelCell(x0, y0, zt, (wx & 15) == 0, (wy & 15) == 0, ua, ub, va, vb); zs = zt - BEVEL; }
            else if (mapped && rnd[r + 1][c + 1]) {
                int R0 = r + 1, C0 = c + 1, fl = 0;
                boolean eL = solidFront(cls, nst, hil, shp, R0, C0 - 1, zf, D) < zt, eR = solidFront(cls, nst, hil, shp, R0, C0 + 1, zf, D) < zt;
                boolean eT = solidFront(cls, nst, hil, shp, R0 - 1, C0, zf, D) < zt, eB = solidFront(cls, nst, hil, shp, R0 + 1, C0, zf, D) < zt;
                if (eL) fl |= E_L;
                if (eR) fl |= E_R;
                if (eT) fl |= E_T;
                if (eB) fl |= E_B;
                if (!eL && !eT && solidFront(cls, nst, hil, shp, R0 - 1, C0 - 1, zf, D) < zt) fl |= E_TL;
                if (!eR && !eT && solidFront(cls, nst, hil, shp, R0 - 1, C0 + 1, zf, D) < zt) fl |= E_TR;
                if (!eL && !eB && solidFront(cls, nst, hil, shp, R0 + 1, C0 - 1, zf, D) < zt) fl |= E_BL;
                if (!eR && !eB && solidFront(cls, nst, hil, shp, R0 + 1, C0 + 1, zf, D) < zt) fl |= E_BR;
                float rz = Math.min(HILL_R, (zt - zb) * HILL_RZ);
                if (fl == 0) quad(x0, y1, zt, x1, y1, zt, x1, y0, zt, x0, y0, zt, ua, vb, ub, vb, ub, va, ua, va, 1f, fk);
                else roundFront(fl, x0, y0, zt, rz, ua, ub, va, vb);
                zs = zt - rz;
            }
            else quad(x0, y1, zt, x1, y1, zt, x1, y0, zt, x0, y0, zt, ua, vb, ub, vb, ub, va, ua, va, 1f, fk);
            if (pot && mapped) {
                int mw = meta(f, wx + 4, wy + 4);
                if (mw >= 0x296 && mw <= 0x2B0) {            // the pot's outline
                    uvSlot(potSideSlot(f, b.cb, e & 0x3FF, e >> 12));
                    ua = (e & 0x400) != 0 ? u1 : u0; ub = (e & 0x400) != 0 ? u0 : u1; va = (e & 0x800) != 0 ? v1 : v0; vb = (e & 0x800) != 0 ? v0 : v1;
                }
            }
            float ta = va, tb = vb, sa = ua, sb = ub;
            if (solidFront(cls, nst, hil, shp, r, c + 1, zf, D) < zt) quad(x0, y0, zs, x1, y0, zs, x1, y0, zb, x0, y0, zb, ua, ta, ub, ta, ub, tb, ua, tb, 0.86f, fk);
            if (solidFront(cls, nst, hil, shp, r + 2, c + 1, zf, D) < zt) quad(x0, y1, zb, x1, y1, zb, x1, y1, zs, x0, y1, zs, ua, tb, ub, tb, ub, ta, ua, ta, 0.45f, fk);
            if (solidFront(cls, nst, hil, shp, r + 1, c, zf, D) < zt) quad(x0, y1, zb, x0, y1, zs, x0, y0, zs, x0, y0, zb, sa, vb, sb, vb, sb, va, sa, va, 0.66f, fk);
            if (solidFront(cls, nst, hil, shp, r + 1, c + 2, zf, D) < zt) quad(x1, y1, zs, x1, y1, zb, x1, y0, zb, x1, y0, zs, sa, vb, sb, vb, sb, va, sa, va, 0.66f, fk);
        }
        // BG3 is the HUD (drawn flat on top) only while it is the frontmost layer, as in the levels (priority 0). On the
        // character select it is priority 3: the purple backdrop and the stage, which as a HUD covered the characters
        // and the text (0.19-0.20); there it is the furthest of the far layers instead.
        boolean hudTop = true;
        for (int i = 0; i < 3; i++) if (bg[i].on && bg[i].prio() < bg[3].prio()) hudTop = false;
        // backdrop: the game's own far layers (BG2 under BG1, BG3 under both when it isn't the HUD) around the screen, on
        // a far plane
        caveStart = nv; caveCount = 0;
        if (cave) {
            int ox = (BD_W - W) / 2, oy = (BD_H - H) / 2;
            int[] far = hudTop ? new int[] { 2, 1 } : new int[] { 3, 2, 1 };
            for (int i = 1; i < far.length; i++)   // back first (higher priority number); ties keep BG3 back
                for (int j = i; j > 0 && bg[far[j]].prio() > bg[far[j - 1]].prio(); j--) { int t = far[j]; far[j] = far[j - 1]; far[j - 1] = t; }
            int fk = (mapped ? 1 << 12 : 0) | (vertical ? 1 << 13 : 0) | (subspace ? 1 << 14 : 0) | var(f, Gba.CURRENT_LEVEL) << 4 | var(f, Gba.AREA_NO);
            if (fk != farKey) { farKey = fk; Arrays.fill(farSeen, 0); }
            final boolean potWall = pot && mapped;
            if (potWall) {
                long key = 29 + layerKey(f, bg[1]);
                if (key != bdKey) { bdKey = key; potPicture(f, bg[1], ox, oy); bdChanged = true; }
                bdSky = 0; skyRgb = 0;
                // no parallax: a texel is a world unit, so the picture stays under the outline from every camera
                float z = zb - 0.5f, cxp = W / 2f, cyp = -H / 2f, hwp = BD_W * 1.5f, hhp = BD_H * 1.5f;
                quad(cxp - hwp, cyp - hhp, z, cxp + hwp, cyp - hhp, z, cxp + hwp, cyp + hhp, z, cxp - hwp, cyp + hhp, z, -1, 2, 2, 2, 2, -1, -1, -1, 1, 3);
                caveCount = nv - caveStart;
            }
            else {
            for (int li : far) markSeen(li);
            // as far as the camera sees, in steps so that a moving camera doesn't redraw every frame for a pixel
            int xa = Math.max(0, ox - ((int) bdL + 15 & ~15)), xb = Math.min(BD_W, ox + W + ((int) bdR + 15 & ~15));
            int ya = Math.max(0, oy - ((int) bdT + 15 & ~15)), yb = Math.min(BD_H, oy + H + ((int) bdB + 15 & ~15));
            if (!mapped) { xa = ox; xb = ox + W; }
            if (!mapped || vertical) { ya = oy; yb = oy + H; }
            long key = hudTop ? 0 : 17;
            for (int li : far) key = key * 31 + layerKey(f, bg[li]) + farSeen[li];
            for (int i = 0; i < SKY_LEN; i++) key = key * 31 + f[SKY_TAB + i];
            key = key * 31 + xa + (xb << 10) + ((long) ya << 20) + ((long) yb << 30);
            if (key != bdKey) {          // only when a far layer scrolled or changed
                bdKey = key;
                boolean grad = mapped && readSky(f);
                if (grad) for (int y = 0; y < BD_H; y++) {   // alpha 128: sky, drawn as it is (the shader tints the far layers)
                    int c = skyRow[Math.max(0, Math.min(H - 1, y - oy))], o = y * BD_W * 4;
                    if (y > 0 && c == skyRow[Math.max(0, Math.min(H - 1, y - 1 - oy))]) { System.arraycopy(backdrop, o - BD_W * 4, backdrop, o, BD_W * 4); continue; }
                    for (int i = o; i < o + BD_W * 4; i += 4) { backdrop[i] = (byte) (c >> 16); backdrop[i + 1] = (byte) (c >> 8); backdrop[i + 2] = (byte) c; backdrop[i + 3] = (byte) 128; }
                }
                else Arrays.fill(backdrop, (byte) 0);
                for (int i = 0; i < 256; i++) farPal[i] = bgr(f, i);
                for (int li : far) farLayer(f, bg[li], li, ox, oy, xa, xb, ya, yb);
                // the sky as seen: the far layers' most common colour along the top of the screen when they cover it
                // (7-1's palette colour 0 is black behind a light sky layer), else palette colour 0
                java.util.HashMap<Integer, Integer> hist = new java.util.HashMap<Integer, Integer>();
                int opaque = 0, samples = 0, top = -1, topN = 0;
                for (int y = oy; y < oy + 32; y += 2) for (int x = ox; x < ox + W; x += 4) {   // top of the screen: where sky is
                    int i = (y * BD_W + x) * 4; samples++;
                    if (backdrop[i + 3] != (byte) 255) continue;
                    opaque++;
                    int c = (backdrop[i] & 255) << 16 | (backdrop[i + 1] & 255) << 8 | (backdrop[i + 2] & 255);
                    Integer n = hist.get(c); int nn = n == null ? 1 : n + 1; hist.put(c, nn);
                    if (nn > topN) { topN = nn; top = c; }
                }
                bdSky = opaque * 2 > samples ? top : -1;
                // what lies under the far layers' last row, where it is mostly solid (2-1's dunes, a cave's rock): its mean
                // colour, down to the texture's edge and on past it, so a turned camera doesn't look under the scenery
                int below = -1;
                if (mapped && !vertical && yb > oy + H) {
                    int y = yb - 1, n = 0, sr = 0, sg = 0, sbl = 0;
                    for (int x = ox; x < ox + W; x++) {
                        int i = (y * BD_W + x) * 4;
                        if (backdrop[i + 3] != (byte) 255) continue;
                        n++; sr += backdrop[i] & 255; sg += backdrop[i + 1] & 255; sbl += backdrop[i + 2] & 255;
                    }
                    if (n * 2 > W) {
                        below = (sr / n) << 16 | (sg / n) << 8 | (sbl / n);
                        for (int i = yb * BD_W * 4; i < BD_W * BD_H * 4; i += 4) { backdrop[i] = (byte) (below >> 16); backdrop[i + 1] = (byte) (below >> 8); backdrop[i + 2] = (byte) below; backdrop[i + 3] = (byte) 255; }
                    }
                }
                // fade the last 16 px so the far layers don't end in a hard cut: toward the sky at the top, what lies under
                // them at the bottom, and at the sides toward the row's own mean colour, which runs on past the texture's
                // edge (a turned camera sees further than the texture reaches). Alpha goes with it where it ends in sky.
                int sky = bdSky >= 0 ? bdSky : bgr(f, 0);
                for (int y = ya; y < yb; y++) {
                    boolean edgeRow = y - ya < 16 || yb - 1 - y < 16;
                    int rs = grad ? skyRow[Math.max(0, Math.min(H - 1, y - oy))] : sky, mn = 0, mr = 0, mg = 0, mb = 0;
                    if (mapped) for (int i = (y * BD_W + xa) * 4; i < (y * BD_W + xb) * 4; i += 4) {
                        if (backdrop[i + 3] == 0) continue;
                        mn++; mr += backdrop[i] & 255; mg += backdrop[i + 1] & 255; mb += backdrop[i + 2] & 255;
                    }
                    int rm = rs;
                    if (mn * 2 > xb - xa) {
                        rm = (mr / mn) << 16 | (mg / mn) << 8 | (mb / mn);
                        for (int x = 0; x < BD_W; x = x == xa - 1 ? xb : x + 1) {
                            if (x >= xa && x < xb) { x = xb - 1; continue; }
                            int i = (y * BD_W + x) * 4;
                            backdrop[i] = (byte) (rm >> 16); backdrop[i + 1] = (byte) (rm >> 8); backdrop[i + 2] = (byte) rm; backdrop[i + 3] = (byte) 128;
                        }
                    }
                    for (int x = xa; x < xb; x++) {
                        if (!edgeRow && x == xa + 16) x = Math.max(x, xb - 16);
                        int dx = Math.min(x - xa, xb - 1 - x), dy = Math.min(y - ya, yb - 1 - y), dmin = Math.min(dx, dy);
                        if (dmin >= 16) continue;
                        int i = (y * BD_W + x) * 4; if (backdrop[i + 3] != (byte) 255) continue;
                        boolean toBelow = below >= 0 && dy < dx && yb - 1 - y < 16;
                        int rc = toBelow ? below : dx <= dy ? rm : rs, sr = rc >> 16 & 255, sg = rc >> 8 & 255, sbl = rc & 255;
                        float t = dmin / 16f;
                        backdrop[i] = (byte) (sr + ((backdrop[i] & 255) - sr) * t);
                        backdrop[i + 1] = (byte) (sg + ((backdrop[i + 1] & 255) - sg) * t);
                        backdrop[i + 2] = (byte) (sbl + ((backdrop[i + 2] & 255) - sbl) * t);
                        if (grad && !toBelow) backdrop[i + 3] = (byte) (128 + 127 * t);
                    }
                }
                bdChanged = true;
            }
            if (bdSky >= 0) skyRgb = bdSky;
            float z = -D * 3 - 48, sc = (camDist - z) / camDist;
            // a texel = one screen pixel seen from the camera: extent = BD size * sc, centred on the screen. The quad runs on
            // past the texture (its edge rows and columns clamp, and there it is only sky), for the turned cameras.
            float cx = W / 2f, cy = -H / 2f, hw = BD_W / 2f * sc * 3, hh = BD_H / 2f * sc * 3;
            quad(cx - hw, cy - hh, z, cx + hw, cy - hh, z, cx + hw, cy + hh, z, cx - hw, cy + hh, z, -1, 2, 2, 2, 2, -1, -1, -1, 1, 3);
            caveCount = nv - caveStart;
            }
        }
        // 0.21: the sky eases to its new colour instead of jumping. Taken from whatever far layer covers the top of the
        // screen, it flipped every few frames as the camera moved (5-3's cave between black, brown and olive rock, 5-1
        // between the clouds and the bushes), and the whole space round the level flashed. A new area or screen snaps.
        int sk = (mapped ? 1 << 12 : 0) | (subspace ? 1 << 13 : 0) | var(f, Gba.CURRENT_LEVEL) << 4 | var(f, Gba.AREA_NO);
        int tr = skyRgb >> 16 & 255, tg = skyRgb >> 8 & 255, tb = skyRgb & 255;
        if (sk != skyKey) { skyKey = sk; skyR = tr; skyG = tg; skyB = tb; }
        else { skyR += (tr - skyR) * 0.08f; skyG += (tg - skyG) * 0.08f; skyB += (tb - skyB) * 0.08f; }
        skyRgb = Math.round(skyR) << 16 | Math.round(skyG) << 8 | Math.round(skyB);
        // light on the player
        spriteStart = nv;
        int px = var16(f, Gba.PLAYER_X) - camX, py = var16(f, Gba.PLAYER_Y) - camY;
        boolean found = mapped && px > -16 && px < W + 16 && py > -32 && py < H + 32;
        float tx = found ? px + 8 : lightX, ty = found ? -(py + 16) : lightY;
        float kk = lightAmt < 0.05f ? 1f : 0.35f;
        lightX += (tx - lightX) * kk; lightY += (ty - lightY) * kk;
        lightAmt += ((found ? 1f : 0f) - lightAmt) * 0.08f;
        // sprites (OBJ): cut-outs in the middle of the depth, back to front (OAM 127 first), no depth test
        boolean map1d = (u16(f, IO) & 0x40) != 0;
        oamBoxes(f);
        spriteDepths(f);
        platforms(f);
        if (round) spriteField(f, map1d);
        boolean drawn = false;
        for (int s = 127; s >= 0; s--) {
            if (!member[s]) { sprite(f, s, map1d, b); continue; }
            if (drawn) continue;
            drawn = true;
            // A sprite-built platform (object class 0D: SMA's big striped blocks, Hawkmouth, ...) gets the blocks' depth:
            // its sprites are drawn as slices from the back face to the front face, back first (sprites have no depth
            // test), darker toward the back so the slices read as sides. 0.10 drew them flat on the front plane.
            for (int k = 0; k <= PLAT_SLICES; k++) {
                zOff = -depth * (1f - k / (float) PLAT_SLICES);
                sShade = k == PLAT_SLICES ? 1f : 0.58f + 0.3f * k / PLAT_SLICES;
                for (int m = 127; m >= 0; m--) if (member[m]) sprite(f, m, map1d, b);
            }
            zOff = 0; sShade = 1;
        }
        glow(f);
        // HUD (BG3) drawn flat by the renderer; empty while BG3 is a far layer
        hudStart = nv;
        long hk = hudTop ? layerKey(f, bg[3]) : 3;
        if (hk != hudKey) {
            hudKey = hk;
            if (hudTop) drawLayer(f, bg[3], hud, 256, 256, 0, 0, true, 0, W, 0, H); else Arrays.fill(hud, (byte) 0);
            hudChanged = true;
        }
        atlasChanged = true;
    }
    private int bdSky = -1, skyKey = -1;
    private float skyR, skyG, skyB;
    private long bdKey = 1, hudKey = 1;
    /** Cheap fingerprint of a layer: control, scroll, its map, a sample of its tiles, the palette. */
    private long layerKey(byte[] f, Bg b) {
        long k = b.on ? b.cnt * 1000003L + b.hofs * 1009L + b.vofs : 7;
        int m = VRAM + b.sb * 0x800;
        for (int i = 0; i < 0x1000 && m + i < VRAM + 0x10000; i += 2) k = k * 31 + f[m + i];
        int c = VRAM + b.cb * 0x4000;
        for (int i = 0; i < 0x4000; i += 61) k = k * 31 + f[c + i];
        for (int i = 0; i < 0x200; i += 2) k = k * 31 + f[PAL + i];
        return k;
    }
    private byte[] quadRom;

    static final int PLAT_SLICES = 8;
    private final boolean[] member = new boolean[128];
    private float zOff = 0, sShade = 1;
    /** Marks the OAM entries that make up live class-0D objects (sprite-built platforms): the entries whose box holds the
     *  object's top-left corner, grown over touching entries with the same palette. */
    private final int[][] box = new int[128][];
    private final int[] feetX = new int[128], feetY = new int[128], gx0 = new int[128], gy0 = new int[128], gx1 = new int[128];
    /** Screen boxes of the visible OAM entries {x0, y0, x1, y1, palette} (double-size affine boxes doubled), and for
     *  each entry the feet of the sprite group it belongs to: entries whose boxes touch form one group (a character's
     *  head and body, a carried item), feet = the group's bottom centre. The whole group then stands at one depth.
     *  0.11 took each 16x16 half's own bottom edge, so on a stepped hill Peach's head and body got different depths
     *  and drew apart. */
    private void oamBoxes(byte[] f) {
        for (int s = 0; s < 128; s++) {
            box[s] = null;
            int a0 = u16(f, OAM + s * 8), a1 = u16(f, OAM + s * 8 + 2), a2 = u16(f, OAM + s * 8 + 4);
            if ((a0 & 0x300) == 0x200 || (a0 >> 14) == 3 || ((a0 >> 10) & 3) == 2) continue;
            int w = OBJ_SIZES[(a0 >> 14) * 4 + (a1 >> 14)][0], h = OBJ_SIZES[(a0 >> 14) * 4 + (a1 >> 14)][1];
            if ((a0 & 0x300) == 0x300) { w *= 2; h *= 2; }
            int y = a0 & 255, x = a1 & 511;
            if (y >= 160) y -= 256;
            if (x >= 240) x -= 512;
            box[s] = new int[] { x, y, x + w, y + h, a2 >> 12 };
        }
        int[] grp = new int[128];
        for (int s = 0; s < 128; s++) grp[s] = s;
        for (int s = 0; s < 128; s++) if (box[s] != null) for (int t = s + 1; t < 128; t++) if (box[t] != null
                && box[s][0] <= box[t][2] && box[t][0] <= box[s][2] && box[s][1] <= box[t][3] && box[t][1] <= box[s][3]) {
            int a = root(grp, s), b = root(grp, t); if (a != b) grp[b] = a;
        }
        int[] x0 = new int[128], x1 = new int[128], y0 = new int[128], y1 = new int[128];
        Arrays.fill(x0, Integer.MAX_VALUE); Arrays.fill(x1, Integer.MIN_VALUE); Arrays.fill(y0, Integer.MAX_VALUE); Arrays.fill(y1, Integer.MIN_VALUE);
        for (int s = 0; s < 128; s++) if (box[s] != null) {
            int r = root(grp, s);
            x0[r] = Math.min(x0[r], box[s][0]); x1[r] = Math.max(x1[r], box[s][2]);
            y0[r] = Math.min(y0[r], box[s][1]); y1[r] = Math.max(y1[r], box[s][3]);
        }
        for (int s = 0; s < 128; s++) if (box[s] != null) {
            int r = root(grp, s); feetX[s] = (x0[r] + x1[r]) / 2; feetY[s] = y1[r];
            gx0[s] = x0[r]; gy0[s] = y0[r]; gx1[s] = x1[r]; gRoot[s] = r;
        }
    }

    // 0.20: sprite depth eases instead of jumping. The nest a group stands at (its feet, or every hill its box overlaps,
    // 0.15) changes in whole steps of 0.75 of the block depth, so an enemy walking past the edge of a hill in front of
    // another hill snapped forward / back by 12 units in one frame, which the perspective shows as a jump on screen. Each
    // group is matched to last frame's group with the nearest feet (world pixels, within 24) and moves a fifth of the way
    // to its target per frame; a new group starts at its target.
    private final float[] gNest = new float[128];
    private final int[] gRoot = new int[128];
    private float[] prevFx = new float[0], prevFy = new float[0], prevNest = new float[0];
    private int prevKey = -1;
    private void spriteDepths(byte[] f) {
        Arrays.fill(gNest, 0);
        if (!mapped) { prevFx = new float[0]; return; }
        int bgPrio = bg[0].cnt & 3;
        float[] tgt = new float[128]; boolean[] has = new boolean[128];
        for (int s = 0; s < 128; s++) if (box[s] != null) {
            int r = gRoot[s];
            int n = nestAt(camX + feetX[s], camY + feetY[s] + 4);
            int prio = (u16(f, OAM + s * 8 + 4) >> 10) & 3;
            if (prio <= bgPrio)   // drawn over BG0: in front of every hill it overlaps (0.15)
                for (int sy = gy0[s] + 4; sy < feetY[s] - 2; sy += 8) for (int sx = gx0[s] + 4; sx < gx1[s] - 2; sx += 8)
                    n = Math.max(n, nestAt(camX + sx, camY + sy));
            if (!has[r] || n > tgt[r]) tgt[r] = n;
            has[r] = true;
        }
        int key = var(f, Gba.CURRENT_LEVEL) * 16 + var(f, Gba.AREA_NO);
        boolean same = key == prevKey; prevKey = key;
        int cnt = 0; for (int r = 0; r < 128; r++) if (has[r]) cnt++;
        float[] fx = new float[cnt], fy = new float[cnt], nz = new float[cnt];
        int k = 0;
        for (int r = 0; r < 128; r++) if (has[r]) {
            float wx = camX + feetX[r], wy = camY + feetY[r], z = tgt[r];
            int best = -1; float bd = 24 * 24;
            if (same) for (int i = 0; i < prevFx.length; i++) {
                float dx = prevFx[i] - wx, dy = prevFy[i] - wy, d = dx * dx + dy * dy;
                if (d < bd) { bd = d; best = i; }
            }
            if (best >= 0) { z = prevNest[best] + (tgt[r] - prevNest[best]) * 0.2f; if (Math.abs(z - tgt[r]) < 0.01f) z = tgt[r]; }
            gNest[r] = z; fx[k] = wx; fy[k] = wy; nz[k++] = z;
        }
        prevFx = fx; prevFy = fy; prevNest = nz;
    }
    private static int root(int[] g, int i) { while (g[i] != i) i = g[i] = g[g[i]]; return i; }
    private void platforms(byte[] f) {
        Arrays.fill(member, false);
        if (!mapped) return;
        for (int n = 0; n < 16; n++) {
            int base = Gba.OBJ_BASE + n * 0x70;
            if (base + 0x70 > VARS_ADDR + VARS_LEN || var(f, base + 0x65) != 1 || var(f, base + 0x66) != 0x0D) continue;
            int ox = var16(f, base + 0x30) - camX + 2, oy = var16(f, base + 0x32) - camY + 2;
            int pal = -1; boolean[] in = new boolean[128];
            for (int s = 0; s < 128; s++) if (box[s] != null && ox >= box[s][0] && ox < box[s][2] && oy >= box[s][1] && oy < box[s][3]) {
                if (pal < 0) pal = box[s][4];
                if (box[s][4] == pal) in[s] = true;
            }
            if (pal < 0) continue;
            for (boolean grew = true; grew; ) {
                grew = false;
                for (int s = 0; s < 128; s++) if (!in[s] && box[s] != null && box[s][4] == pal)
                    for (int t = 0; t < 128; t++) if (in[t] && box[s][0] <= box[t][2] && box[t][0] <= box[s][2] && box[s][1] <= box[t][3] && box[t][1] <= box[s][3]) { in[s] = true; grew = true; break; }
            }
            for (int s = 0; s < 128; s++) if (in[s]) member[s] = true;
        }
    }
    private static final int[][] OBJ_SIZES = { { 8, 8 }, { 16, 16 }, { 32, 32 }, { 64, 64 }, { 16, 8 }, { 32, 8 }, { 32, 16 }, { 64, 32 },
            { 8, 16 }, { 8, 32 }, { 16, 32 }, { 32, 64 } };
    private void sprite(byte[] f, int s, boolean map1d, Bg b) {
        int a0 = u16(f, OAM + s * 8), a1 = u16(f, OAM + s * 8 + 2), a2 = u16(f, OAM + s * 8 + 4);
        if ((a0 & 0x300) == 0x200) return;               // hidden
        // OBJ-window sprites (mode 2) are never drawn by the GBA: they only mark where window effects apply (SMA's light
        // through an open door is a brightened OBJ window). Drawn as sprites (0.13) they came out as solid red/white shapes.
        if (((a0 >> 10) & 3) == 2) return;
        if ((a0 & 0x2000) != 0) return;                  // 256-colour sprites: not used by SMA
        int shape = a0 >> 14, size = a1 >> 14;
        if (shape == 3) return;
        int w = OBJ_SIZES[shape * 4 + size][0], h = OBJ_SIZES[shape * 4 + size][1];
        int y = a0 & 255, x = a1 & 511;
        if (y >= 160) y -= 256;
        if (x >= 240) x -= 512;
        boolean affine = (a0 & 0x100) != 0, hf = !affine && (a1 & 0x1000) != 0, vf = !affine && (a1 & 0x2000) != 0;
        int tile = a2 & 0x3FF, prio = (a2 >> 10) & 3, pal = a2 >> 12;
        float kind = prio > (b.cnt & 3) ? 5 : 2;
        // Sprites stand on the front face of the blocks, not in the middle of their depth: at z = 0 their feet showed
        // well above the ground's front edge (enemies looked like they were floating). On a hill that comes forward,
        // the plane comes forward with it (nest of the block under the sprite's feet).
        // Affine sprites (SMA's giant Shy Guys / vegetables are 32x32 shown at 2x in a double-size box): the OAM matrix
        // maps screen offsets from the box centre to texture offsets; its inverse places each tile. Drawn unscaled at
        // the box's corner (0.8 and before), a giant Shy Guy floated half a box above the platform it stood on.
        float m00 = 1, m01 = 0, m10 = 0, m11 = 1, ccx = x + w / 2f, ccy = y + h / 2f, boxH = h;
        if (affine) {
            int pi = (a1 >> 9) & 31, po = OAM + pi * 32;
            float pa = (short) u16(f, po + 6) / 256f, pb = (short) u16(f, po + 14) / 256f;
            float pc = (short) u16(f, po + 22) / 256f, pd = (short) u16(f, po + 30) / 256f;
            float det = pa * pd - pb * pc;
            if (Math.abs(det) < 1e-4f) return;
            m00 = pd / det; m01 = -pb / det; m10 = -pc / det; m11 = pa / det;
            boolean dbl = (a0 & 0x200) != 0;
            ccx = x + (dbl ? w : w / 2f); ccy = y + (dbl ? h : h / 2f);
            boxH = dbl ? 2 * h : h;
        }
        float zs = depth / 2f + 0.05f + zOff;
        // 0.15: a sprite drawn over BG0 stays in front of every hill it overlaps on screen. SMB2/SMA hill bodies are
        // scenery you walk and fly in front of; at the depth under its feet, Peach floating past a hill that steps
        // forward (or anyone on the ground in front of one) went behind the hill's face in the diorama / low views.
        // The whole group shares one eased nest (spriteDepths, 0.20).
        if (mapped) zs += (box[s] != null ? gNest[gRoot[s]] : nestAt(camX + Math.round(ccx), camY + Math.round(ccy + boxH / 2f) + 4)) * depth * HILL_STEP;
        // not the life meter (OAM 0.. at x 0, see extract.c): it's HUD, and its dotted empty hearts extruded into rings
        boolean ext = thick && !member[s] && !(s < 8 && x == 0);
        if (affine) {
            for (int ty = 0; ty < h / 8; ty++) for (int tx = 0; tx < w / 8; tx++) {
                int t = map1d ? tile + ty * (w / 8) + tx : tile + ty * 32 + tx;
                int sl = slot(f, true, 4, t & 0x3FF, pal);
                if (ext) {   // texel (i, j) of this tile sits at centre + M * (tile offset + (i, j))
                    float ox = tx * 8 - w / 2f, oy = ty * 8 - h / 2f;
                    extrude(sl, ccx + m00 * ox + m01 * oy, ccy + m10 * ox + m11 * oy, m00, m10, m01, m11, zs, kind);
                }
                uvSlot(sl);
                float lx = tx * 8 - w / 2f, ly = ty * 8 - h / 2f, rx = lx + 8, by = ly + 8;
                // screen = centre + M * texture offset (y down); world y = -screen y
                float blx = ccx + m00 * lx + m01 * by, bly = ccy + m10 * lx + m11 * by;
                float brx = ccx + m00 * rx + m01 * by, bry = ccy + m10 * rx + m11 * by;
                float trx = ccx + m00 * rx + m01 * ly, try_ = ccy + m10 * rx + m11 * ly;
                float tlx = ccx + m00 * lx + m01 * ly, tly = ccy + m10 * lx + m11 * ly;
                if (m00 * m11 - m01 * m10 >= 0)
                    quad(blx, -bly, zs, brx, -bry, zs, trx, -try_, zs, tlx, -tly, zs, u0, v1, u1, v1, u1, v0, u0, v0, sShade, kind);
                else   // mirrored by the matrix (SMA flips giant sprites this way): reverse the winding, or culling drops it
                    quad(brx, -bry, zs, blx, -bly, zs, tlx, -tly, zs, trx, -try_, zs, u1, v1, u0, v1, u0, v0, u1, v0, sShade, kind);
            }
            return;
        }
        boolean rnd = round && roundable(s, a0, a1);
        for (int ty = 0; ty < h / 8; ty++) for (int tx = 0; tx < w / 8; tx++) {
            int stx = hf ? w / 8 - 1 - tx : tx, sty = vf ? h / 8 - 1 - ty : ty;
            int t = map1d ? tile + sty * (w / 8) + stx : tile + sty * 32 + stx;
            int sl = slot(f, true, 4, t & 0x3FF, pal);
            float qx = x + tx * 8, qy = y + ty * 8;
            if (rnd) { roundTile(sl, qx, qy, hf, vf, zs, kind); continue; }
            if (ext) extrude(sl, hf ? qx + 8 : qx, vf ? qy + 8 : qy, hf ? -1 : 1, 0, 0, vf ? -1 : 1, zs, kind);
            uvSlot(sl);
            float ua = hf ? u1 : u0, ub = hf ? u0 : u1, va = vf ? v1 : v0, vb = vf ? v0 : v1;
            quad(qx, -qy - 8, zs, qx + 8, -qy - 8, zs, qx + 8, -qy, zs, qx, -qy, zs, ua, vb, ub, vb, ub, va, ua, va, sShade, kind);
        }
    }

    // 0.21: the window effect. SMA's light through an open door (3-3) is an OBJ window (mode-2 sprites, never drawn
    // themselves) inside which BLDCNT brightens everything by BLDY/16. Without it the doorway showed as a grey slab and
    // the shaft of light was missing. The window (the union of the mode-2 sprites' opaque pixels) goes out as one quad
    // per run of pixels on each row, so overlapping window sprites don't brighten twice; kind 6 (brighten) / 7 (darken)
    // quads draw as white / black at alpha BLDY/16, last in the sprite pass, on the plane a sprite there would stand on.
    private final boolean[] win = new boolean[W * H];
    private void glow(byte[] f) {
        int disp = u16(f, IO), winout = u16(f, IO + 0x4A), bld = u16(f, IO + 0x50), evy = Math.min(16, u16(f, IO + 0x54) & 31);
        int fx = (bld >> 6) & 3;
        if ((disp & 0x8000) == 0 || (winout & 0x2000) == 0 || fx < 2 || evy == 0) return;
        boolean map1d = (disp & 0x40) != 0, any = false;
        Arrays.fill(win, false);
        for (int s = 0; s < 128; s++) {
            int a0 = u16(f, OAM + s * 8), a1 = u16(f, OAM + s * 8 + 2), a2 = u16(f, OAM + s * 8 + 4);
            if ((a0 & 0x300) == 0x200 || ((a0 >> 10) & 3) != 2 || (a0 & 0x2000) != 0 || (a0 >> 14) == 3) continue;
            int w = OBJ_SIZES[(a0 >> 14) * 4 + (a1 >> 14)][0], h = OBJ_SIZES[(a0 >> 14) * 4 + (a1 >> 14)][1];
            boolean affine = (a0 & 0x100) != 0, dbl = affine && (a0 & 0x200) != 0;
            int bw = dbl ? 2 * w : w, bh = dbl ? 2 * h : h, y = a0 & 255, x = a1 & 511, tile = a2 & 0x3FF;
            if (y >= 160) y -= 256;
            if (x >= 240) x -= 512;
            float pa = 1, pb = 0, pc = 0, pd = 1;
            if (affine) {
                int po = OAM + ((a1 >> 9) & 31) * 32;
                pa = (short) u16(f, po + 6) / 256f; pb = (short) u16(f, po + 14) / 256f;
                pc = (short) u16(f, po + 22) / 256f; pd = (short) u16(f, po + 30) / 256f;
            }
            for (int sy = Math.max(0, y); sy < Math.min(H, y + bh); sy++) for (int sx = Math.max(0, x); sx < Math.min(W, x + bw); sx++) {
                int tx, ty;
                if (affine) {   // texture offset = matrix * screen offset from the box centre
                    float dx = sx - x - bw / 2f + 0.5f, dy = sy - y - bh / 2f + 0.5f;
                    tx = (int) Math.floor(pa * dx + pb * dy + w / 2f); ty = (int) Math.floor(pc * dx + pd * dy + h / 2f);
                    if (tx < 0 || ty < 0 || tx >= w || ty >= h) continue;
                } else {
                    tx = sx - x; ty = sy - y;
                    if ((a1 & 0x1000) != 0) tx = w - 1 - tx;
                    if ((a1 & 0x2000) != 0) ty = h - 1 - ty;
                }
                int t = (tile + (ty >> 3) * (map1d ? w / 8 : 32) + (tx >> 3)) & 0x3FF;
                int o = VRAM + 0x10000 + t * 32 + (ty & 7) * 4 + ((tx & 7) >> 1);
                if (o < VRAM + 0x18000 && ((u8(f[o]) >> ((tx & 1) * 4)) & 15) != 0) { win[sy * W + sx] = true; any = true; }
            }
        }
        if (!any) return;
        float a = evy / 16f, kind = fx == 2 ? 6 : 7;
        for (int sy = 0; sy < H; sy++) for (int sx = 0; sx < W; ) {
            if (!win[sy * W + sx]) { sx++; continue; }
            int e = sx; while (e < W && win[sy * W + e]) e++;
            float z = depth / 2f + 0.05f + (mapped ? nestAt(camX + (sx + e) / 2, camY + sy) * depth * HILL_STEP : 0);
            quad(sx, -sy - 1, z, e, -sy - 1, z, e, -sy, z, sx, -sy, z, 0, 0, 0, 0, 0, 0, 0, 0, a, kind);
            sx = e;
        }
    }

    // 0.20: sprites have thickness. Each sprite tile keeps its flat front face and gets walls SPRITE_THICK deep going back
    // from it, along every texel edge where an opaque texel meets a clear one (or the tile's edge): pixel art extruded
    // like a stack of little blocks. A wall takes its texel's colour, shaded like the block faces (top 0.86, sides 0.66,
    // bottom 0.45). Walls go backwards, so feet still meet the ground's front edge. The renderer draws the sprite pass
    // with its own depth buffer so a sprite hides its own walls but still stays in front of the terrain.
    static final float SPRITE_THICK = 4f;
    /** Walls of one 8x8 sprite tile in atlas slot sl. Texel (i, j) covers the screen parallelogram from
     *  (ox, oy) + i * (ix, iy) + j * (jx, jy) to one step further in i and j (flips and affine matrices included). */
    private void extrude(int sl, float ox, float oy, float ix, float iy, float jx, float jy, float zs, float kind) {
        int ax = (sl % (ATLAS_W / 8)) * 8, ay = (sl / (ATLAS_W / 8)) * 8;
        float zb = zs - Math.min(SPRITE_THICK, depth * 0.5f);
        for (int j = 0; j < 8; j++) for (int i = 0; i < 8; i++) {
            if (!opaqueTexel(ax, ay, i, j)) continue;
            float u = (ax + i + 0.5f) / ATLAS_W, v = (ay + j + 0.5f) / ATLAS_H;
            // edge towards each clear neighbour: start corner (a, b) in texel steps, edge direction, outward normal
            for (int e = 0; e < 4; e++) {
                int ni = i + (e == 0 ? 1 : e == 1 ? -1 : 0), nj = j + (e == 2 ? -1 : e == 3 ? 1 : 0);
                if (opaqueTexel(ax, ay, ni, nj)) continue;
                int a = e == 0 ? i + 1 : i, b = e == 3 ? j + 1 : j;          // first corner of the edge
                int da = e >= 2 ? 1 : 0, db = e < 2 ? 1 : 0;                 // along the edge
                float px = ox + ix * a + jx * b, py = oy + iy * a + jy * b;
                float qx = px + ix * da + jx * db, qy = py + iy * da + jy * db;
                float nx = (ni - i) * ix + (nj - j) * jx, ny = (ni - i) * iy + (nj - j) * jy;   // screen y down
                float sh = Math.abs(nx) >= Math.abs(ny) ? 0.66f : ny < 0 ? 0.86f : 0.45f;
                quad(px, -py, zs, qx, -qy, zs, qx, -qy, zb, px, -py, zb, u, v, u, v, u, v, u, v, sShade * sh, kind);
            }
        }
    }
    private boolean opaqueTexel(int ax, int ay, int i, int j) {
        return i >= 0 && j >= 0 && i < 8 && j < 8 && atlas[((ay + j) * ATLAS_W + ax + i) * 4 + 3] != 0;
    }
}

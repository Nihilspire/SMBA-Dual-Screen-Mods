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
    static final int IO = 0, PAL = 0x60, VRAM = 0x460, OAM = 0x18460, LVL = 0x18860, LVL_LEN = 0x12C0, VARS = 0x19B20,
            VARS_ADDR = 0x03001100, VARS_LEN = 0x1400, SNAP = VARS + VARS_LEN;
    static final int W = 240, H = 160;
    static final int ATLAS_W = 512, ATLAS_H = 512, SLOTS = (ATLAS_W / 8) * (ATLAS_H / 8);
    static final int BD_W = 512, BD_H = 512;

    // options
    volatile String back = "near";
    volatile int depth = 16;
    volatile boolean cave = true, light = false;
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
    private static final class Bg { int cnt, cb, sb, size, hofs, vofs, w, h; boolean on; }
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
    private final int[] q = new int[4];
    private boolean quad(int id) {
        byte[] r = rom;
        if (r == null || !BezelTheme.quad(r, id, q)) return false;
        Gba.retile(id, quadMode, q);
        return true;
    }
    /** How the game redraws the wall metatiles 0x26E..0x2C5 this frame (see Gba.retile). */
    private int quadMode;

    static final int SKY = 0, BACK = 1, PROP = 2, BLOCK = 3, PYR = 4;
    private final HashMap<Integer, Integer> propCache = new HashMap<Integer, Integer>();
    private int classOf(byte[] f, int id) {
        if (id < 0) return SKY;
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
    /** Fraction (0..1000) of sampled screen cells whose BG0 tile matches the level data under camera (cx, cy). */
    private int score(byte[] f, int cx, int cy) {
        int ok = 0, n = 0;
        for (int sy = 12; sy < H; sy += 20) for (int sx = 4; sx < W; sx += 18) {
            int wx = cx + sx, wy = cy + sy, t = meta(f, wx, wy);
            if (t < 0 || !quad(t)) continue;
            n++;
            int want = q[((wy >> 3) & 1) * 2 + ((wx >> 3) & 1)];
            if ((entry(f, bg[0], bg[0].hofs + sx, bg[0].vofs + sy) & 0x3FF) == (want & 0x3FF)) ok++;
        }
        return n >= 20 ? ok * 1000 / n : 0;
    }
    /** Picks layout + camera (best match), sets mapped (with the hold described above). */
    private void view(byte[] f) {
        frameNo++;
        quadMode = Gba.quadMode(var16(f, Gba.TILESET), var(f, Gba.TILE_FLAG_A), var(f, Gba.TILE_FLAG_B));
        pages = Math.min(10, var(f, Gba.PAGES) + 1);
        int px = var16(f, Gba.PLAYER_X), py = var16(f, Gba.PLAYER_Y);
        Bg b = bg[0];
        boolean flag = var(f, Gba.IS_VERTICAL) != 0;
        int best = -1, bx = b.hofs, by = b.vofs; boolean bv = flag;
        boolean play = rom != null && var(f, Gba.MODE) == 3 && var(f, Gba.SUB_MODE) == 1 && var(f, Gba.MAIN_STATE) == 1;
        long bestDist = Long.MAX_VALUE;
        if (play) for (int v = 0; v < 2; v++) {
            vertical = v == 0 ? flag : !flag;
            // every wrap of the scroll inside the area (the player's position can't be trusted: falling into a pit, dying)
            int maxX = vertical ? 256 : pages * 256, maxY = vertical ? pages * 240 : 240;
            for (int cx = b.hofs % b.w - b.w; cx < maxX; cx += b.w) {
                if (cx + W <= 0) continue;
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
            atlas[k] = (byte) (col >> 16); atlas[k + 1] = (byte) (col >> 8); atlas[k + 2] = (byte) col;
            atlas[k + 3] = (byte) (v != 0 ? 255 : 0);
        }
        if (solid) fillClear(ax, ay);
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

    // ------------------------------------------------------------------ backdrop and HUD layers
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
    private static int fdiv(int a, int b) { int r = a / b; if ((a % b != 0) && ((a < 0) != (b < 0))) r--; return r; }

    /** camDist: distance from the camera to the z = 0 plane (for the backdrop scale). */
    void build(byte[] f, float camDist) {
        if (rom != quadRom) { quadRom = rom; propCache.clear(); }
        readBgs(f);
        view(f);
        skyRgb = bgr(f, 0);
        if (!mapped) propCache.clear();       // tile graphics change between areas
        slots.clear(); nextSlot = 0;
        nv = 0;
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
        final int M = mapped ? 4 : 0;
        int tx0 = fdiv(b.hofs, 8) - M, ty0 = fdiv(b.vofs, 8) - M;
        int cols = fdiv(b.hofs + W - 1, 8) + M - tx0 + 1, rows = fdiv(b.vofs + H - 1, 8) + M - ty0 + 1;
        int[][] cls = new int[rows + 2][cols + 2], ent = new int[rows + 2][cols + 2], nst = new int[rows + 2][cols + 2];
        boolean[][] hil = new boolean[rows + 2][cols + 2];
        int[][][] pyr = new int[rows + 2][cols + 2][];
        pyrMemo.clear();
        if (mapped) hills(f);
        for (int r = -1; r <= rows; r++) for (int c = -1; c <= cols; c++) {
            int sx = (tx0 + c) * 8 - b.hofs, sy = (ty0 + r) * 8 - b.vofs;
            boolean on = sx > -8 && sx < W && sy > -8 && sy < H;
            int e, k;
            if (on) e = entry(f, b, (tx0 + c) * 8, (ty0 + r) * 8);
            else {
                int wx = camX + sx, wy = camY + sy, t = meta(f, wx + 4, wy + 4);
                e = t >= 0 && quad(t) ? q[((wy >> 3) & 1) * 2 + ((wx >> 3) & 1)] : -1;
            }
            if (e < 0 || !b.on) k = SKY;
            else {
                boolean empty = emptyBgTile(f, b.cb, e & 0x3FF);
                if (!mapped) k = empty ? SKY : BACK;
                else { k = classOf(f, meta(f, camX + sx + 4, camY + sy + 4)); if (empty && k != BLOCK) k = SKY; }
                if (mapped && k != SKY && k != BLOCK && pyrId(meta(f, camX + sx + 4, camY + sy + 4))) {
                    int key = (camX + sx + 4) >> 4 << 8 | ((camY + sy + 4) >> 4 & 255);
                    int[] p = pyrMemo.get(key);
                    if (p == null && !pyrMemo.containsKey(key)) { p = pyramid(f, (camX + sx + 4) >> 4, (camY + sy + 4) >> 4); pyrMemo.put(key, p); }
                    if (p != null) { k = PYR; pyr[r + 1][c + 1] = p; }
                }
            }
            cls[r + 1][c + 1] = k; ent[r + 1][c + 1] = e;
            nst[r + 1][c + 1] = mapped ? nestAt(camX + sx + 4, camY + sy + 4) : 0;
            int mt = mapped ? meta(f, camX + sx + 4, camY + sy + 4) : -1;
            hil[r + 1][c + 1] = mt >= 0 && hill(mt);
        }
        // Bodies under a ledge (hills, houses, trunks under their caps): SMA's hill tops are stand-on-top metatiles
        // (blocks) but the bodies are plain opaque scenery (back wall), so the cap stuck out as a separate slab over a
        // recessed body. Whatever opaque scenery hangs directly under a block, down to the first gap, is block too
        // (mostly-opaque edge tiles included; their clear pixels take the sky colour on the faces).
        if (mapped) for (int c = 0; c < cols + 2; c++) for (int r = 1; r < rows + 2; r++) {
            if (cls[r - 1][c] != BLOCK) continue;
            int k = cls[r][c], e = ent[r][c];
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
        for (int r = 0; r < rows; r++) for (int c = 0; c < cols; c++) {
            int k = cls[r + 1][c + 1];
            if (k == SKY) continue;
            int e = ent[r + 1][c + 1];
            uvSlot(slot(f, false, b.cb, e & 0x3FF, e >> 12, k == BLOCK));
            float ua = (e & 0x400) != 0 ? u1 : u0, ub = (e & 0x400) != 0 ? u0 : u1, va = (e & 0x800) != 0 ? v1 : v0, vb = (e & 0x800) != 0 ? v0 : v1;
            float x0 = (tx0 + c) * 8 - b.hofs, x1 = x0 + 8, y0 = -((ty0 + r) * 8 - b.vofs), y1 = y0 - 8;
            if (k == BACK) {
                if (!backOff) quad(x0, y1, backZ, x1, y1, backZ, x1, y0, backZ, x0, y0, backZ, ua, vb, ub, vb, ub, va, ua, va, 0.8f, 1);
                continue;
            }
            if (k == PROP) { quad(x0, y1, 0, x1, y1, 0, x1, y0, 0, x0, y0, 0, ua, vb, ub, vb, ub, va, ua, va, 1f, 2); continue; }
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
            int nn = nst[r + 1][c + 1];
            float zt = zf + nn * D * HILL_STEP;
            quad(x0, y1, zt, x1, y1, zt, x1, y0, zt, x0, y0, zt, ua, vb, ub, vb, ub, va, ua, va, 1f, 0);
            if (cls[r][c + 1] != BLOCK || nst[r][c + 1] < nn) quad(x0, y0, zt, x1, y0, zt, x1, y0, zb, x0, y0, zb, ua, va, ub, va, ub, vb, ua, vb, 0.86f, 0);
            if (cls[r + 2][c + 1] != BLOCK || nst[r + 2][c + 1] < nn) quad(x0, y1, zb, x1, y1, zb, x1, y1, zt, x0, y1, zt, ua, vb, ub, vb, ub, va, ua, va, 0.45f, 0);
            if (cls[r + 1][c] != BLOCK || nst[r + 1][c] < nn) quad(x0, y1, zb, x0, y1, zt, x0, y0, zt, x0, y0, zb, ua, vb, ub, vb, ub, va, ua, va, 0.66f, 0);
            if (cls[r + 1][c + 2] != BLOCK || nst[r + 1][c + 2] < nn) quad(x1, y1, zt, x1, y1, zb, x1, y0, zb, x1, y0, zt, ua, vb, ub, vb, ub, va, ua, va, 0.66f, 0);
        }
        // backdrop: the game's own far layers (BG2 under BG1) around the screen, on a far plane
        caveStart = nv; caveCount = 0;
        if (cave) {
            int ox = (BD_W - W) / 2, oy = (BD_H - H) / 2;
            long key = layerKey(f, bg[1]) * 31 + layerKey(f, bg[2]);
            if (key != bdKey) {          // only when a far layer scrolled or changed
                bdKey = key;
                // rows near the screen only: further up/down the layers wrap into unrelated map data
                // (and columns: games stream map columns in as the camera moves, so the rest of the map is stale)
                int ya = oy, yb = oy + H, xa = ox, xb = ox + W;   // only what is on screen: the rest of the map is stale
                drawLayer(f, bg[2], backdrop, BD_W, BD_H, -ox, -oy, true, xa, xb, ya, yb);
                byte[] tmp = scratch;
                drawLayer(f, bg[1], tmp, BD_W, BD_H, -ox, -oy, true, xa, xb, ya, yb);
                for (int y = ya; y < yb; y++) for (int i = y * BD_W + xa; i < y * BD_W + xb; i++) if (tmp[i * 4 + 3] != 0) System.arraycopy(tmp, i * 4, backdrop, i * 4, 4);
                // the sky as seen: the far layers' most common colour along the top of the screen when they cover it
                // (7-1's palette colour 0 is black behind a light sky layer), else palette colour 0
                java.util.HashMap<Integer, Integer> hist = new java.util.HashMap<Integer, Integer>();
                int opaque = 0, samples = 0, top = -1, topN = 0;
                for (int y = ya; y < ya + 32; y += 2) for (int x = xa; x < xb; x += 4) {   // top of the screen: where sky is
                    int i = (y * BD_W + x) * 4; samples++;
                    if (backdrop[i + 3] == 0) continue;
                    opaque++;
                    int c = (backdrop[i] & 255) << 16 | (backdrop[i + 1] & 255) << 8 | (backdrop[i + 2] & 255);
                    Integer n = hist.get(c); int nn = n == null ? 1 : n + 1; hist.put(c, nn);
                    if (nn > topN) { topN = nn; top = c; }
                }
                bdSky = opaque * 2 > samples ? top : -1;
                // fade the last 16 px toward the sky so the far layers don't end in a hard cut past the screen edges
                int sky = bdSky >= 0 ? bdSky : bgr(f, 0), sr = sky >> 16 & 255, sg = sky >> 8 & 255, sbl = sky & 255;
                for (int y = ya; y < yb; y++) for (int x = xa; x < xb; x++) {
                    int dmin = Math.min(Math.min(x - xa, xb - 1 - x), Math.min(y - ya, yb - 1 - y));
                    if (dmin >= 16) continue;
                    int i = (y * BD_W + x) * 4; if (backdrop[i + 3] == 0) continue;
                    float t = dmin / 16f;
                    backdrop[i] = (byte) (sr + ((backdrop[i] & 255) - sr) * t);
                    backdrop[i + 1] = (byte) (sg + ((backdrop[i + 1] & 255) - sg) * t);
                    backdrop[i + 2] = (byte) (sbl + ((backdrop[i + 2] & 255) - sbl) * t);
                }
                bdChanged = true;
            }
            if (bdSky >= 0) skyRgb = bdSky;
            float z = -D * 3 - 48, sc = (camDist - z) / camDist;
            // a texel = one screen pixel seen from the camera: plane extent = BD size * sc, centred on the screen
            float cx = W / 2f, cy = -H / 2f, hw = BD_W / 2f * sc, hh = BD_H / 2f * sc;
            quad(cx - hw, cy - hh, z, cx + hw, cy - hh, z, cx + hw, cy + hh, z, cx - hw, cy + hh, z, 0, 1, 1, 1, 1, 0, 0, 0, 1, 3);
            caveCount = nv - caveStart;
        }
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
        platforms(f);
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
        // HUD (BG3) drawn flat by the renderer
        hudStart = nv;
        long hk = layerKey(f, bg[3]);
        if (hk != hudKey) { hudKey = hk; drawLayer(f, bg[3], hud, 256, 256, 0, 0, true, 0, W, 0, H); hudChanged = true; }
        atlasChanged = true;
    }
    private final byte[] scratch = new byte[BD_W * BD_H * 4];
    private int bdSky = -1;
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
            gx0[s] = x0[r]; gy0[s] = y0[r]; gx1[s] = x1[r];
        }
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
        if (mapped) {
            int fx = camX + (box[s] != null ? feetX[s] : Math.round(ccx)), fy = camY + (box[s] != null ? feetY[s] : Math.round(ccy + boxH / 2f)) + 4;
            int nest = nestAt(fx, fy);
            // 0.15: a sprite drawn over BG0 stays in front of every hill it overlaps on screen. SMB2/SMA hill bodies are
            // scenery you walk and fly in front of; at the depth under its feet, Peach floating past a hill that steps
            // forward (or anyone on the ground in front of one) went behind the hill's face in the diorama / low views.
            if (kind == 2 && box[s] != null)
                for (int sy = gy0[s] + 4; sy < feetY[s] - 2; sy += 8) for (int sx = gx0[s] + 4; sx < gx1[s] - 2; sx += 8)
                    nest = Math.max(nest, nestAt(camX + sx, camY + sy));
            zs += nest * depth * HILL_STEP;
        }
        if (affine) {
            for (int ty = 0; ty < h / 8; ty++) for (int tx = 0; tx < w / 8; tx++) {
                int t = map1d ? tile + ty * (w / 8) + tx : tile + ty * 32 + tx;
                uvSlot(slot(f, true, 4, t & 0x3FF, pal));
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
        for (int ty = 0; ty < h / 8; ty++) for (int tx = 0; tx < w / 8; tx++) {
            int stx = hf ? w / 8 - 1 - tx : tx, sty = vf ? h / 8 - 1 - ty : ty;
            int t = map1d ? tile + sty * (w / 8) + stx : tile + sty * 32 + stx;
            uvSlot(slot(f, true, 4, t & 0x3FF, pal));
            float ua = hf ? u1 : u0, ub = hf ? u0 : u1, va = vf ? v1 : v0, vb = vf ? v0 : v1;
            float qx = x + tx * 8, qy = y + ty * 8;
            quad(qx, -qy - 8, zs, qx + 8, -qy - 8, zs, qx + 8, -qy, zs, qx, -qy, zs, ua, vb, ub, vb, ub, va, ua, va, sShade, kind);
        }
    }
}

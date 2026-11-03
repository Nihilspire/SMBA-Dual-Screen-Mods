package com.jack.smadual;

import android.graphics.Bitmap;

/**
 * Builds the side-border texture from what the game is drawing right now: the most common solid 16x16 block on
 * screen (from the decoded level data), drawn with the game's live BG tiles and palette. Grass hills in World 1,
 * sand in World 2, ice in World 4 and so on, with no art shipped in the APK. Keyed per level + area.
 */
final class BezelTheme {
    Bitmap block;          // 16x16 metatile
    int accent = 0xFF58D8F8, shade = 0xFF1838A8, key, area = -1;

    /** Live data for one sample (MainActivity.Emu.sampleTheme). */
    static final class Snap {
        final byte[] io = new byte[0x60], pal = new byte[0x200], vram = new byte[0x10000], level = new byte[0x960 * 2 * 4];
        int px, py, levelRows, quadMode;   // quadMode: Gba.quadMode (how the game redraws some wall metatiles)
        boolean vertical;
        int id(int col, int row) {   // metatile id at a world cell (pages of 15 rows x 16 columns)
            int idx;
            if (vertical) { if (col < 0 || col > 15 || row < 0) return -1; idx = row * 16 + col; }
            else { if (col < 0 || row < 0 || row > 14) return -1; idx = (col >> 4) * 240 + row * 16 + (col & 15); }
            if (idx * 2 + 1 >= level.length) return -1;
            return (level[idx * 2] & 255) | (level[idx * 2 + 1] & 255) << 8;
        }
    }

    static BezelTheme build(Snap s, byte[] rom, int area) {
        if (rom == null) return null;
        int[] counts = new int[0x300];
        int best = -1, bestN = 0;
        int c0 = (s.px >> 4) - 7, r0 = (s.py >> 4) - 4;
        int[] q = new int[4];
        for (int r = r0; r < r0 + 10; r++) for (int c = c0; c < c0 + 15; c++) {
            int id = s.id(c, r);
            if (id < 0 || id >= 0x300) continue;
            if (!quad(rom, id, q)) continue;
            Gba.retile(id, s.quadMode, q);
            if (coverage(s, q) < 200) continue;
            if (++counts[id] > bestN) { bestN = counts[id]; best = id; }
        }
        if (best < 0 || bestN < 4) return null;
        quad(rom, best, q);
        Gba.retile(best, s.quadMode, q);
        BezelTheme th = new BezelTheme();
        th.block = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888);
        int cb = ((s.io[8] & 255) >> 2) & 3;
        int[] seen = new int[16]; int seenN = 0;
        for (int i = 0; i < 4; i++) {
            int e = q[i], t = e & 0x3FF, p = e >> 12;
            boolean hf = (e & 0x400) != 0, vf = (e & 0x800) != 0;
            for (int y = 0; y < 8; y++) for (int x = 0; x < 8; x++) {
                int sx = hf ? 7 - x : x, sy = vf ? 7 - y : y;
                int o = cb * 0x4000 + t * 32 + sy * 4 + (sx >> 1);
                int v = o < s.vram.length ? (s.vram[o] >> ((sx & 1) * 4)) & 15 : 0;
                int ci = v == 0 ? 0 : p * 16 + v;
                int col = Gba.color((s.pal[ci * 2] & 255) | (s.pal[ci * 2 + 1] & 255) << 8);
                th.block.setPixel((i & 1) * 8 + x, (i >> 1) * 8 + y, col);
                if (v != 0 && seenN < 16) seen[seenN++] = col;
            }
        }
        th.accent = brightest(seen, seenN);
        th.shade = darkest(seen, seenN);
        th.key = best * 64 + (area & 63);
        th.area = area;
        return th;
    }

    /** The 4 BG map entries (TL, TR, BL, BR) of a metatile, from the ROM's tables. */
    static boolean quad(byte[] rom, int id, int[] out) {
        int grp = id >> 8;
        if (grp > 2) return false;
        int pa = Gba.ROM_METATILE_PTRS + grp * 4;
        int p = (rom[pa] & 255) | (rom[pa + 1] & 255) << 8 | (rom[pa + 2] & 255) << 16 | (rom[pa + 3] & 255) << 24;
        int off = (p & 0x1FFFFFF) + (id & 255) * 8;
        if (off < 0 || off + 8 > rom.length) return false;
        for (int i = 0; i < 4; i++) out[i] = (rom[off + i * 2] & 255) | (rom[off + i * 2 + 1] & 255) << 8;
        return true;
    }

    private static int coverage(Snap s, int[] q) {
        int cb = ((s.io[8] & 255) >> 2) & 3, n = 0;
        for (int i = 0; i < 4; i++) {
            int t = q[i] & 0x3FF;
            for (int k = 0; k < 32; k++) {
                int o = cb * 0x4000 + t * 32 + k;
                if (o >= s.vram.length) break;
                int b = s.vram[o] & 255;
                if ((b & 15) != 0) n++;
                if ((b >> 4) != 0) n++;
            }
        }
        return n;
    }

    private static int lum(int c) { return ((c >> 16) & 255) * 3 + ((c >> 8) & 255) * 6 + (c & 255); }
    private static int brightest(int[] c, int n) {
        int best = 0xFF58D8F8, bl = -1;
        for (int i = 0; i < n; i++) if (lum(c[i]) > bl) { bl = lum(c[i]); best = c[i]; }
        return best;
    }
    private static int darkest(int[] c, int n) {
        int best = 0xFF1838A8, bl = 1 << 30;
        for (int i = 0; i < n; i++) if (lum(c[i]) < bl) { bl = lum(c[i]); best = c[i]; }
        return best;
    }
}

/* border.c - the top screen around the game: BezelTheme.java and GameView.java. The most common solid 16x16 block on screen (from the
 * decoded level data), drawn with the game's live BG tiles and palette, darkened, with a frame in that block's darkest and brightest
 * colours. One theme per area: the first ~4 seconds are sampled, the most frequent pick is locked in and reused on coming back. When
 * the borders are wide enough (INTEGER SCALE) the HUD goes in them too: hearts, lives, cherries, Ace Coins, level, character, play time. */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include "dual3ds.h"
#include "sma.h"

typedef struct { uint16_t block[256]; uint32_t accent, shade; int key, valid; } Theme;
static Theme locked[20 * 16], cand[8], current;
static int candN[8], ncand, samples, lastArea = -1, gen;

static const uint8_t *ROMP;
static int quadRom(int id, int qm, int q[4]) {
    int g = id >> 8;
    if (id < 0 || g > 2) return 0;
    uint32_t pa = ROM_METATILE_PTRS + g * 4;
    uint32_t p = (ROMP[pa] | ROMP[pa + 1] << 8 | ROMP[pa + 2] << 16 | (uint32_t)ROMP[pa + 3] << 24) & 0x1FFFFFF;
    uint32_t off = p + (id & 255) * 8;
    for (int i = 0; i < 4; i++) q[i] = S_retile(id, qm, ROMP[off + i * 2] | ROMP[off + i * 2 + 1] << 8);
    return 1;
}
static int lum(uint32_t c) { return (int)((c >> 16) & 255) * 3 + (int)((c >> 8) & 255) * 6 + (int)(c & 255); }
/* One sample: the most common block with enough ink in its tiles around the player. */
static int build(Theme *t) {
    static uint8_t vram[0x10000], pal[0x200], level[0x4B00], io[0x10];
    static short counts[0x300];
    gba_read(0x06000000, vram, sizeof vram); gba_read(0x05000000, pal, sizeof pal); gba_read(0x02000000, level, sizeof level); gba_read(0x04000000, io, sizeof io);
    int px = S_px(), py = S_py(), vert = S_vertical(), qm = S_quadMode(), best = -1, bestN = 0, q[4], cb = (io[8] >> 2) & 3;
    memset(counts, 0, sizeof counts);
    for (int r = (py >> 4) - 4; r < (py >> 4) + 6; r++) for (int c = (px >> 4) - 7; c < (px >> 4) + 8; c++) {
        int idx;
        if (vert) { if (c < 0 || c > 15 || r < 0) continue; idx = r * 16 + c; }
        else { if (c < 0 || r < 0 || r > 14) continue; idx = (c >> 4) * 240 + r * 16 + (c & 15); }
        if (idx * 2 + 1 >= (int)sizeof level) continue;
        int id = level[idx * 2] | level[idx * 2 + 1] << 8;
        if (id < 0 || id >= 0x300 || !quadRom(id, qm, q)) continue;
        int n = 0;
        for (int i = 0; i < 4; i++) {
            int tn = q[i] & 0x3FF;
            for (int k = 0; k < 32; k++) { int o = cb * 0x4000 + tn * 32 + k; if (o >= (int)sizeof vram) break; int b = vram[o]; if (b & 15) n++; if (b >> 4) n++; }
        }
        if (n < 200) continue;
        if (++counts[id] > bestN) { bestN = counts[id]; best = id; }
    }
    if (best < 0 || bestN < 4) return 0;
    quadRom(best, qm, q);
    uint32_t acc = 0x58D8F8, sha = 0x1838A8; int al = -1, sl = 1 << 30, seen = 0;
    for (int i = 0; i < 4; i++) {
        int e = q[i], tn = e & 0x3FF, p = e >> 12, hf = e & 0x400, vf = e & 0x800;
        for (int y = 0; y < 8; y++) for (int x = 0; x < 8; x++) {
            int sx = hf ? 7 - x : x, sy = vf ? 7 - y : y, o = cb * 0x4000 + tn * 32 + sy * 4 + (sx >> 1);
            int v = o < (int)sizeof vram ? (vram[o] >> ((sx & 1) * 4)) & 15 : 0, ci = v ? p * 16 + v : 0;
            uint32_t col = bgr555to24((unsigned)(pal[ci * 2] | pal[ci * 2 + 1] << 8));
            t->block[((i >> 1) * 8 + y) * 16 + (i & 1) * 8 + x] = rgb565(col);
            if (v && seen < 16) { seen++; int l = lum(col); if (l > al) { al = l; acc = col; } if (l < sl) { sl = l; sha = col; } }
        }
    }
    t->accent = acc; t->shade = sha; t->key = best; t->valid = 1;
    return 1;
}
static void show(const Theme *t) { if (!current.valid || current.key != t->key || memcmp(current.block, t->block, sizeof t->block)) { current = *t; gen++; } }
void border_reset(void) { memset(locked, 0, sizeof locked); current.valid = 0; lastArea = -1; ncand = 0; samples = 0; gen++; }
void border_sample(const uint8_t *rom) {
    ROMP = rom;
    if (!S_playing()) return;
    int lv = S_level();
    if (lv > 19) return;
    int area = lv * 16 + (S_area() & 15);
    if (locked[area].valid) { show(&locked[area]); return; }
    if (area != lastArea) { lastArea = area; ncand = 0; samples = 0; }
    Theme t; memset(&t, 0, sizeof t);
    if (!build(&t)) return;
    int i;
    for (i = 0; i < ncand; i++) if (cand[i].key == t.key) break;
    if (i == ncand && ncand < 8) { cand[ncand] = t; candN[ncand] = 0; ncand++; }
    if (i < ncand) candN[i]++;
    if (!current.valid || current.key != t.key) { if (samples == 0) show(&t); }
    if (++samples >= 12) {
        int b = 0;
        for (int k = 1; k < ncand; k++) if (candN[k] > candN[b]) b = k;
        locked[area] = cand[b];
        show(&locked[area]);
    }
}

/* The HUD's state, read a few times a second; the picture is only redrawn when it changed. */
typedef struct { int inGame, hearts, maxH, lives, cherries, ace, level, ch; unsigned sec; } Hud;
static Hud hudNow;
static void readHud(Hud *h) {
    memset(h, 0, sizeof *h);
    h->inGame = S_inLevel();
    if (!h->inGame) return;
    h->hearts = S_health(); h->maxH = S_maxHearts(); h->lives = S_lives(); h->cherries = S_cherries() > 5 ? 5 : S_cherries();
    int lv = S_level(); h->level = lv; h->ace = S_aceLevel() | (lv <= 19 ? S_aceSaved(lv) : 0); h->ch = S_character();
    h->sec = (unsigned)(sma_t[0] / GBA_FPS);
}
static int lastGen = -1, lastG[5];
int border_build(uint16_t *b, int gx, int gy, int gw, int gh, int on) {
    Hud h; readHud(&h);
    float px = gh / 160.0f;
    int hud = on && gx > 50 * px;
    int G[5] = { gx, gy, gw, gh, on };
    if (gen == lastGen && !memcmp(lastG, G, sizeof G) && (!hud || !memcmp(&h, &hudNow, sizeof h))) return 0;
    lastGen = gen; memcpy(lastG, G, sizeof G); hudNow = h;
    cv_target(b, 400, 240);
    font_solid = 1;
    memset(b, 0, 400 * 240 * 2);
    int space = gx > 2 * px || gy > 2 * px;
    if (!on || !space) return 1;
    if (current.valid) {
        int ix = (int)(gx * 256 / px / 256), iy = (int)(gy / px);
        for (int y = 0; y < 240; y++) {
            int sy = ((int)(y / px) + 1600 - iy) & 15;
            for (int x = 0; x < 400; x++) b[y * 400 + x] = current.block[sy * 16 + (((int)(x / px) + 1600 - ix) & 15)];
        }
        rectA(0, 0, 400, 240, 0, 156);
    }
    uint32_t accent = current.valid ? current.accent : 0x3CBCFC, sh = current.valid ? current.shade : 0x0000A8;
    int p4 = (int)(4 * px + 0.5f), p3 = (int)(3 * px + 0.5f), p1 = (int)(px + 0.5f);
    rect(gx - p4, gy - p4, gw + 2 * p4, gh + 2 * p4, sh);
    rect(gx - p3, gy - p3, gw + 2 * p3, gh + 2 * p3, accent);
    rect(gx - p1, gy - p1, gw + 2 * p1, gh + 2 * p1, 0);
    if (!hud || !h.inGame) return 1;
    int lx = gx / 2 - 24, y = gy + 24;
    for (int i = 0; i < h.maxH; i++) {                   /* life meter: filled red / empty */
        int tx = lx + i * 10, ty = y - 12;
        rect(tx, ty, 8, 8, 0xF8F8F8); rect(tx + 1, ty + 1, 6, 6, i < h.hearts ? 0xE03828 : 0x283048);
    }
    char t[24]; snprintf(t, sizeof t, "x%d", h.lives); text(t, lx, y, 0xF8F8F8);
    for (int i = 0; i < 5; i++) rect(lx + i * 8 + 2, y + 16, 5, 5, i < h.cherries ? 0xE03828 : 0x503020);   /* cherries toward the next Starman */
    for (int i = 0; i < 5; i++) rect(lx + i * 8 + 2, y + 26, 5, 5, (h.ace >> i) & 1 ? 0xF8B800 : 0x503020);  /* Ace Coins this level */
    int rx = gx + gw + (400 - gx - gw) / 2;
    static const char *const LV[20] = { "1-1", "1-2", "1-3", "2-1", "2-2", "2-3", "3-1", "3-2", "3-3", "4-1", "4-2", "4-3", "5-1", "5-2", "5-3", "6-1", "6-2", "6-3", "7-1", "7-2" };
    static const char *const CH[4] = { "MARIO", "PEACH", "TOAD", "LUIGI" };
    if (h.level <= 19) textC(LV[h.level], rx, y, accent);
    textC(CH[h.ch], rx, y + 12, 0xF8F8F8);
    fmt_hms(t, h.sec); textC(t, rx, y + 28, 0xC0C0C0);
    return 1;
}

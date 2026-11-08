/* kit.c - the menu graphics captured from the player's own ROM on first start (../extract.c, "SMAX" file); menu/gbakit.js in C.
 * Shot 0 is the title screen, shot 1 the pause screen, shot 2 is 1-1 in play; each is IO 0x60 | PAL 0x400 | VRAM 0x18000 | OAM 0x400.
 * Every piece is checked against the OAM and BG registers it came from; a mismatch leaves K.ok clear and the drawn look stays. */
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include "dual3ds.h"

Kit K;
#define SHOT_IO 0x60
#define SHOT (SHOT_IO + 0x400 + 0x18000 + 0x400)
typedef struct { const uint8_t *io, *pal, *vram, *oam; } Shot;

static Shot shot(const uint8_t *d, int n) {
    const uint8_t *o = d + 8 + n * SHOT; Shot s;
    s.io = o; s.pal = o + SHOT_IO; s.vram = o + SHOT_IO + 0x400; s.oam = o + SHOT_IO + 0x400 + 0x18000;
    return s;
}
static inline int u16at(const uint8_t *a, int o) { return a[o] | a[o + 1] << 8; }
static uint32_t rgbOf(const uint8_t *pal, int i) { return bgr555to24((unsigned)u16at(pal, i * 2)); }

static Img *bg(const Shot *s, int n, int x0, int y0, int w, int h) {
    int cnt = u16at(s->io, 8 + 2 * n), cb = (cnt >> 2) & 3, sb = (cnt >> 8) & 31, size = cnt >> 14;
    int MW = size & 1 ? 512 : 256, MH = size & 2 ? 512 : 256;
    Img *c = img_new(w, h, 1);
    for (int y = 0; y < h; y++) for (int xx = 0; xx < w; xx++) {
        int px = (xx + x0) % MW, py = (y + y0) % MH, tx = px >> 3, ty = py >> 3;
        int blk = sb + (tx >> 5) + (ty >> 5) * (MW == 512 ? 2 : 1);
        int e = u16at(s->vram, blk * 0x800 + ((ty & 31) * 32 + (tx & 31)) * 2), t = e & 0x3FF, pl = e >> 12;
        int ix = px & 7, iy = py & 7;
        if (e & 0x400) ix = 7 - ix;
        if (e & 0x800) iy = 7 - iy;
        int o = cb * 0x4000 + t * 32 + iy * 4 + (ix >> 1);
        if (o >= 0x10000) continue;
        int v = (s->vram[o] >> ((ix & 1) * 4)) & 15;
        if (v) img_pixel(c, xx, y, rgbOf(s->pal, pl * 16 + v));
    }
    return c;
}
static Img *obj(const Shot *s, int t, int w, int h, int pl) {
    Img *c = img_new(w, h, 1);
    for (int y = 0; y < h; y++) for (int xx = 0; xx < w; xx++) {
        int tt = t + (y >> 3) * 32 + (xx >> 3), o = 0x10000 + tt * 32 + (y & 7) * 4 + ((xx & 7) >> 1);
        int v = (s->vram[o] >> ((xx & 1) * 4)) & 15;
        if (v) img_pixel(c, xx, y, rgbOf(s->pal, 256 + pl * 16 + v));
    }
    return c;
}
/* A copy with the backing square removed: pixels of the corner colour connected to the edge go clear (the pause screen's Ace Coin). */
static Img *unfill(const Img *src) {
    int w = src->w, h = src->h, top = 0;
    Img *c = img_new(w, h, 1);
    memcpy(c->px, src->px, (size_t)w * h * 2); memcpy(c->a, src->a, (size_t)w * h);
    if (!c->a[0]) return c;
    uint16_t key = c->px[0];
    int *st = malloc(sizeof(int) * (size_t)(w * h * 6)); uint8_t *seen = calloc((size_t)w, h);
    for (int i = 0; i < w; i++) { st[top++] = i; st[top++] = (h - 1) * w + i; }
    for (int j = 0; j < h; j++) { st[top++] = j * w; st[top++] = j * w + w - 1; }
    while (top) {
        int k = st[--top];
        if (seen[k]) continue;
        seen[k] = 1;
        if (!c->a[k] || c->px[k] != key) continue;
        c->a[k] = 0;
        int kx = k % w, ky = k / w;
        if (kx > 0) st[top++] = k - 1;
        if (kx < w - 1) st[top++] = k + 1;
        if (ky > 0) st[top++] = k - w;
        if (ky < h - 1) st[top++] = k + w;
    }
    free(st); free(seen);
    return c;
}
static int opaque(const Img *c) { int n = 0; for (int i = 0; i < c->w * c->h; i++) if (c->a[i]) n++; return n; }
static int oamTile(const Shot *s, int i) { return u16at(s->oam, i * 8 + 4) & 0x3FF; }

/* name, top-left OBJ tile (2D mapping), OBJ palette: from OAM with each item on screen in 1-1 */
static const short ICONS[][3] = {
    { IC_potion, 8, 1 }, { IC_pow, 6, 1 }, { IC_crystal, 4, 1 }, { IC_shell, 16, 1 }, { IC_bomb, 158, 1 }, { IC_oneup, 156, 1 },
    { IC_heart, 80, 1 }, { IC_mushroom, 280, 1 }, { IC_bigveg, 334, 4 }, { IC_bobomb, 344, 7 }, { IC_ninji, 274, 1 },
    { IC_cherry, 0, 1 }, { IC_key, 142, 1 }, { IC_watch, 154, 1 }, { IC_star, 282, 9 }, { IC_egg, 94, 14 }, { IC_coin, 90, 1 },
    { IC_heartFull, 130, 1 }, { IC_heartEmpty, 128, 2 } };

static struct { int w, h; Img *im; } cards[16]; static int ncards;

/* a piece of the panel, flipped, clipped to cw x ch */
static void piece(Img *o, const Img *p, int x, int y, int hf, int vf, int cw, int ch) {
    for (int yy = 0; yy < ch; yy++) for (int xx = 0; xx < cw; xx++) {
        int sx = hf ? 15 - xx : xx, sy = vf ? 15 - yy : yy, dx = x + xx, dy = y + yy;
        if (dx < 0 || dy < 0 || dx >= o->w || dy >= o->h) continue;
        int a = p->a[sy * 16 + sx]; if (!a) continue;
        o->px[dy * o->w + dx] = p->px[sy * 16 + sx]; o->a[dy * o->w + dx] = 255;
    }
}
/* The pause panel, 9-sliced to any size >= 32x32 (pieces repeat; the last one on a row is cut at the edge it meets, never the corner).
 * Bottom and right pieces are the flipped top and left, as the game does. */
const Img *kit_card(int w, int h) {
    if (w < 32) w = 32;
    if (h < 32) h = 32;
    for (int i = 0; i < ncards; i++) if (cards[i].w == w && cards[i].h == h) return cards[i].im;
    Img *o = img_new(w, h, 1); int i, j;
#define MIN16(v) ((v) < 16 ? (v) : 16)
    for (j = 16; j < h - 16; j += 16) for (i = 16; i < w - 16; i += 16) piece(o, K.fill, i, j, 0, 0, MIN16(w - 16 - i), MIN16(h - 16 - j));
    for (i = 16; i < w - 16; i += 16) { int cw = MIN16(w - 16 - i); piece(o, K.top, i, 0, 0, 0, cw, 16); piece(o, K.top, i, h - 16, 0, 1, cw, 16); }
    for (j = 16; j < h - 16; j += 16) { int ch = MIN16(h - 16 - j); piece(o, K.side, 0, j, 0, 0, 16, ch); piece(o, K.side, w - 16, j, 1, 0, 16, ch); }
    piece(o, K.corner, 0, 0, 0, 0, 16, 16); piece(o, K.corner, w - 16, 0, 1, 0, 16, 16);
    piece(o, K.corner, 0, h - 16, 0, 1, 16, 16); piece(o, K.corner, w - 16, h - 16, 1, 1, 16, 16);
    if (ncards == 16) { img_free(cards[0].im); memmove(cards, cards + 1, sizeof cards[0] * 15); ncards = 15; }
    cards[ncards].w = w; cards[ncards].h = h; cards[ncards++].im = o;
    return o;
}

/* Backdrop: the sky stretched to the height, the title's ground strip on the bottom edge (tiled if wider than 512). */
static uint16_t *backdrop(const Img *sky, const Img *gr, int W, int H) {
    uint16_t *b = malloc((size_t)W * H * 2);
    for (int y = 0; y < H; y++) {
        const uint16_t *s = sky->px + (y * 160 / H) * 256; const uint8_t *sa = sky->a + (y * 160 / H) * 256; uint16_t *d = b + y * W;
        for (int x = 0; x < W; x++) d[x] = sa[x & 255] ? s[x & 255] : 0;
        int gy = y - (H - 160);
        if (gy >= 0) { const uint16_t *g = gr->px + gy * 512; const uint8_t *ga = gr->a + gy * 512; for (int x = 0; x < W; x++) if (ga[x & 511]) d[x] = g[x & 511]; }
    }
    return b;
}
/* The look without a capture: link.js's drawn sky and wavy hills. */
static uint16_t *drawnBackdrop(int W, int H) {
    uint16_t *b = malloc((size_t)W * H * 2);
    uint32_t c0 = 0x9CC8EC, c1 = 0xE2F2F8, green = 0x40B030, dg = 0x187018;
    for (int y = 0; y < H; y++) {
        float t = (float)y / (H - 1);
        uint32_t c = (uint32_t)(((c0 >> 16) & 255) + (((c1 >> 16) & 255) - ((c0 >> 16) & 255)) * t) << 16
                   | (uint32_t)(((c0 >> 8) & 255) + (((c1 >> 8) & 255) - ((c0 >> 8) & 255)) * t) << 8
                   | (uint32_t)((c0 & 255) + ((c1 & 255) - (c0 & 255)) * t);
        for (int x = 0; x < W; x++) b[y * W + x] = rgb565(c);
    }
    int hy = H - 26;
    for (int x = 0; x < W; x++) {
        int top = hy + (int)lroundf(sinf(x / 9.0f) * 3);
        for (int y = top; y < H; y++) b[y * W + x] = rgb565(y == top ? dg : green);
        if (((x >> 2) + (top >> 1)) % 4 == 0 && top + 6 < H) b[(top + 6) * W + x] = rgb565(dg);
    }
    return b;
}
void kit_make_drawn_backdrops(void) {
    if (!K.bd320) K.bd320 = drawnBackdrop(320, 240);
    if (!K.bd400) K.bd400 = drawnBackdrop(400, 240);
}
const uint16_t *kit_backdrop(int w) { kit_make_drawn_backdrops(); return w == 400 ? K.bd400 : K.bd320; }

void kit_reset(void) {
    for (int i = 0; i < ncards; i++) img_free(cards[i].im);
    ncards = 0;
    img_free(K.corner); img_free(K.top); img_free(K.side); img_free(K.fill); img_free(K.cursor); img_free(K.coin);
    for (int i = 0; i < IC_N; i++) img_free(K.icons[i]);
    free(K.bd320); free(K.bd400);
    memset(&K, 0, sizeof K);
}

int kit_load(const uint8_t *d, size_t n) {
    kit_reset();
    if (!d || n < 8 + 2 * SHOT || memcmp(d, "SMAX", 4) || d[4] < 1 || d[4] > 2) return 0;
    Shot t = shot(d, 0), p = shot(d, 1);
    if (u16at(t.io, 8) != 0x5E01 || u16at(t.io, 0x0C) != 0x1B03) return 0;          /* title: BG0 ground, BG2 sky */
    if (oamTile(&p, 42) != 768 || oamTile(&p, 43) != 769 || oamTile(&p, 53) != 800 || oamTile(&p, 54) != 801 || oamTile(&p, 6) != 814) return 0;
    int sy0 = u16at(t.io, 0x12) & 511, gy0 = u16at(t.io, 0x1A) & 511;              /* BG0 / BG2 vertical scroll */
    Img *ground = bg(&t, 0, u16at(t.io, 0x10) & 511, sy0, 512, 160), *sky = bg(&t, 2, 0, gy0, 256, 160);
    K.bd320 = backdrop(sky, ground, 320, 240); K.bd400 = backdrop(sky, ground, 400, 240);
    img_free(ground); img_free(sky);
    K.corner = obj(&p, 768, 16, 16, 2); K.top = obj(&p, 769, 16, 16, 2); K.side = obj(&p, 800, 16, 16, 2); K.fill = obj(&p, 801, 16, 16, 2);
    K.cursor = obj(&p, 814, 8, 8, 2);
    K.coin = obj(&p, 862, 16, 16, 1);
#define PC(i) rgbOf(p.pal, 256 + 32 + (i))
    K.cream = PC(7); K.parch = PC(13); K.light = PC(12); K.orange = PC(14); K.brown = PC(10); K.dark = PC(15); K.ink = PC(8);
    K.icons[IC_ace] = unfill(K.coin);
    if (d[4] >= 2 && n >= 8 + 3 * SHOT) {
        Shot g = shot(d, 2); int h0 = oamTile(&g, 0), h1 = oamTile(&g, 1);
        if ((h0 == 128 || h0 == 130) && (h1 == 128 || h1 == 130))
            for (size_t i = 0; i < sizeof ICONS / sizeof ICONS[0]; i++) {
                Img *c = obj(&g, ICONS[i][1], 16, 16, ICONS[i][2]);
                if (opaque(c) >= 24) K.icons[ICONS[i][0]] = c; else img_free(c);   /* a blank sprite falls back to the drawn look */
            }
    }
    K.ok = 1;
    return 1;
}

/* linkmap.c - the MAP tab: the whole area from the decoded level at 02000000 in the game's own tiles, over the game's own far layers
 * and sky, at four zoom levels that follow the player (link.js drawMap, mapView, farData, markers).
 *
 * What differs from the page is how the picture is built. The page rebuilds a canvas of the whole area whenever anything changes and
 * scales all of it. An ARM11 can't do that between two frames, so here each zoom's picture is a buffer where every metatile cell is
 * exactly k x k pixels (the page's scale is k/16 for a whole k), and cells are drawn a few a frame: the cells on screen at once when
 * the area or the zoom changes, then the rest, then round again slowly so animated tiles and new far columns come in. */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include "linkint.h"

uint8_t video[0x18860]; int haveVideo;
uint8_t lvl[5280]; int haveLvl;
Geo geo = { 0, 1, 0 };
Box mapBox;

/* ------------------------------------------------------------------ ROM tables */
static const uint8_t *meta[3];
static const int METATILE_COUNTS[3] = { 159, 47, 192 };
static inline uint32_t romU32(uint32_t o) { return rom[o] | rom[o + 1] << 8 | rom[o + 2] << 16 | (uint32_t)rom[o + 3] << 24; }
void map_init(void) { for (int g = 0; g < 3; g++) meta[g] = rom + (romU32(ROM_METATILE_PTRS + g * 4) & 0x1FFFFFF); }
/* the 4 BG map entries of a metatile, retiled as the game draws it; 0 when it has none */
static inline int quad(int id, int qm, int q[4]) {
    int g = id >> 8, i = id & 255;
    if (id < 0 || g > 2 || i >= METATILE_COUNTS[g]) return 0;
    const uint8_t *t = meta[g] + i * 8;
    for (int k = 0; k < 4; k++) q[k] = S_retile(id, qm, t[k * 2] | t[k * 2 + 1] << 8);
    return 1;
}

/* the current area's object list from the ROM: records (u16 type, x, y), ended by a lone 0x00FF */
static struct { int key, n; uint16_t r[200][3]; } objs = { -1, 0 };
static void objs_load(void) {
    int lv = S_level(), an = S_areaNo();
    if (lv > 19 || an > 10) { objs.key = -1; objs.n = 0; return; }
    int k = lv * 11 + an;
    if (objs.key == k) return;
    objs.key = k; objs.n = 0;
    uint32_t p = romU32(ROM_OBJ_TABLE + k * 4) & 0x1FFFFFF;
    if (!p || p + 1200 > romLen) return;
    const uint8_t *b = rom + p;
    for (int o = 0; o + 6 <= 1200 && objs.n < 200; o += 6) {
        int t = b[o] | b[o + 1] << 8;
        if (t == 0xFF) break;
        objs.r[objs.n][0] = (uint16_t)t; objs.r[objs.n][1] = (uint16_t)(b[o + 2] | b[o + 3] << 8); objs.r[objs.n][2] = (uint16_t)(b[o + 4] | b[o + 5] << 8);
        objs.n++;
    }
}
int obj_count(void) { objs_load(); return objs.n; }
void obj_get(int i, int *type, int *x, int *y) { *type = objs.r[i][0]; *x = objs.r[i][1]; *y = objs.r[i][2]; }
/* 0.35: which way the area runs, from its object list. -1 if none of its objects is past the first page. */
static int areaRuns(void) {
    int n = obj_count();
    for (int i = 0; i < n; i++) { if (objs.r[i][2] >= 15) return 1; if (objs.r[i][1] >= 16) return 0; }
    return -1;
}
/* Ace Coins left in this area (the count); the markers go through ace_mark below */
int ace_here(int *left) {
    int lv = S_level(), n = obj_count(), tot = 0, l = 0;
    if (lv > 19) { *left = 0; return 0; }
    int have = S_aceSaved(lv) | S_aceLevel();
    for (int i = 0; i < n; i++) { int t = objs.r[i][0]; if (t >= ACE_FIRST && t < ACE_FIRST + 5) { tot++; if (!((have >> (t - ACE_FIRST)) & 1)) l++; } }
    *left = l;
    return tot;
}

/* ------------------------------------------------------------------ grass */
int is_grass(int t) { return (t >= 0x25 && t <= 0x2F) || (t >= 0x95 && t <= 0x9C); }
const char *grass_letter(int t) {
    switch (t) {
    case 0x25: return "C"; case 0x26: return "V"; case 0x28: return "R"; case 0x29: return "S"; case 0x2A: case 0x2E: return "B"; case 0x2B: return "P";
    case 0x2C: return "1"; case 0x2D: case 0x98: return "W"; case 0x95: return "E"; case 0x96: return "H"; case 0x97: case 0x99: case 0x9A: case 0x9C: return "G"; case 0x9B: return "N";
    default: return "";
    }
}
const char *grass_name(int t) {
    switch (t) {
    case 0x25: return "COIN"; case 0x26: return "BIG VEGETABLE"; case 0x27: return "VEGETABLE"; case 0x28: return "ROCKET"; case 0x29: return "SHELL"; case 0x2A: return "BOMB";
    case 0x2B: return "POTION"; case 0x2C: return "1UP"; case 0x2D: return "POW"; case 0x2E: return "BOB-OMB"; case 0x2F: return "PULLED"; case 0x95: return "YOSHI EGG";
    case 0x96: return "HEART"; case 0x97: return "GIANT VEGETABLE"; case 0x98: return "GIANT POW"; case 0x99: case 0x9C: return "GIANT SHY GUY"; case 0x9A: return "GIANT ITEM"; case 0x9B: return "NINJI";
    default: return "";
    }
}
int grass_icon(int t) {
    switch (t) {
    case 0x25: return IC_coin; case 0x26: return IC_bigveg; case 0x28: return IC_rocket; case 0x29: return IC_shell; case 0x2A: return IC_bomb; case 0x2B: return IC_potion;
    case 0x2C: return IC_oneup; case 0x2D: case 0x98: return IC_pow; case 0x2E: return IC_bobomb; case 0x95: return IC_egg; case 0x96: return IC_heart; case 0x97: return IC_giantVeg;
    case 0x99: return IC_giantShy; case 0x9B: return IC_ninji; case 0x9C: return IC_giantShyBlue;
    default: return IC_NONE;
    }
}
int still_there(int t) {
    if (t == 0x2C) return !S_oneUpTaken();
    int mi = S_mushIndex(t);
    if (mi >= 0) {
        if (P(0x03001A3F + mi)) return 0;
        int L = S_level();
        return !(S_yoshi() && L < 20 && rom[ROM_NO_MUSH + L * 3 + mi]);
    }
    int ei = S_eggIndex(t);
    if (ei >= 0) return S_yoshi() && !((S_eggsVisit() >> ei) & 1);
    return 1;
}

/* ------------------------------------------------------------------ icons
 * From the first-start capture and, for sprites the game only loads while they're on screen (rocket, giant vegetable, giant Shy
 * Guys), from live VRAM the first time one shows up (kept on the SD card). Smaller sizes are area-averaged copies, cached per size. */
static Img *live[IC_N];
static Img *sized[IC_N][41];
static const Img *sizedSrc[IC_N];
static inline int iconFallback(int n) { return n == IC_giantVeg ? IC_bigveg : n == IC_giantShyBlue ? IC_giantShy : IC_NONE; }
const Img *icon_src(int name) {
    for (int n = name; n != IC_NONE; n = iconFallback(n)) {
        if (K.ok && K.icons[n]) return K.icons[n];
        if (live[n]) return live[n];
    }
    return NULL;
}
const Img *icon_at(int name, int size) {
    const Img *src = icon_src(name);
    if (!src) return NULL;
    if (src->w == size) return src;
    if (size < 1 || size > 40) return src;
    if (sizedSrc[name] != src) {
        for (int i = 0; i <= 40; i++) { img_free(sized[name][i]); sized[name][i] = NULL; }
        sizedSrc[name] = src;
    }
    if (!sized[name][size]) sized[name][size] = img_resample(src, size, size);
    return sized[name][size];
}
int draw_icon(int name, int x, int y, int size, int alpha) {
    const Img *c = icon_at(name, size ? size : 16);
    if (!c) return 0;
    blitA(c, x, y, alpha);
    return 1;
}
int has_icons(void) { return K.ok && K.icons[IC_potion]; }

/* Sprites captured from live VRAM: OAM must show the sprite (tile, palette, size) at that moment, so the tiles in VRAM are the right
 * ones. Parts are (tile, w, h, OBJ palette, y offset) in 2D mapping. */
static const struct { int name, w, h, nparts; short parts[2][5]; } LIVE[] = {
    { IC_rocket, 32, 32, 2, { { 400, 32, 16, 3, 0 }, { 404, 32, 16, 3, 16 } } },
    { IC_giantVeg, 32, 32, 1, { { 908, 32, 32, 4, 0 } } },
    { IC_giantShy, 32, 32, 1, { { 920, 32, 32, 1, 0 } } },
    { IC_giantShyBlue, 32, 32, 1, { { 920, 32, 32, 2, 0 } } } };
static const short OBJ_SIZES[12][2] = { {8,8},{16,16},{32,32},{64,64},{16,8},{32,8},{32,16},{64,32},{8,16},{8,32},{16,32},{32,64} };
void live_load(const char *dir) {
    char p[300]; snprintf(p, sizeof p, "%s/sma-live.bin", dir);
    FILE *f = fopen(p, "rb"); if (!f) return;
    for (size_t i = 0; i < 4; i++) {
        int has = fgetc(f); if (has != 1) { if (has == EOF) break; continue; }
        Img *im = img_new(32, 32, 1);
        if (fread(im->px, 2, 1024, f) != 1024 || fread(im->a, 1, 1024, f) != 1024) { img_free(im); break; }
        live[LIVE[i].name] = im;
    }
    fclose(f);
}
void live_grab(const char *dir) {
    if (!haveVideo || (vu16(V_IO) & 0x40)) return;
    int changed = 0;
    for (size_t e = 0; e < 4; e++) {
        if (live[LIVE[e].name]) continue;
        int all = 1;
        for (int k = 0; k < LIVE[e].nparts && all; k++) {
            const short *p = LIVE[e].parts[k]; int found = 0;
            for (int s = 0; s < 128 && !found; s++) {
                int a0 = vu16(V_OAM + s * 8), a1 = vu16(V_OAM + s * 8 + 2), a2 = vu16(V_OAM + s * 8 + 4);
                if ((a0 & 0x300) == 0x200 || (a0 >> 14) == 3) continue;
                const short *sz = OBJ_SIZES[(a0 >> 14) * 4 + (a1 >> 14)];
                if (sz[0] == p[1] && sz[1] == p[2] && (a2 & 0x3FF) == p[0] && (a2 >> 12) == p[3]) found = 1;
            }
            all = found;
        }
        if (!all) continue;
        Img *im = img_new(LIVE[e].w, LIVE[e].h, 1); int n = 0;
        for (int k = 0; k < LIVE[e].nparts; k++) {
            const short *p = LIVE[e].parts[k];
            for (int y = 0; y < p[2]; y++) for (int x = 0; x < p[1]; x++) {
                int t = p[0] + (y >> 3) * 32 + (x >> 3), o = 0x10000 + t * 32 + (y & 7) * 4 + ((x & 7) >> 1);
                int v = (video[o] >> ((x & 1) * 4)) & 15; if (!v) continue;
                img_pixel(im, x, y + p[4], bgr555to24((unsigned)vu16(V_PAL + (256 + p[3] * 16 + v) * 2))); n++;
            }
        }
        if (n < 60) { img_free(im); continue; }
        live[LIVE[e].name] = im; changed = 1;
    }
    if (!changed) return;
    char p[300]; snprintf(p, sizeof p, "%s/sma-live.bin", dir);
    FILE *f = fopen(p, "wb"); if (!f) return;
    for (size_t i = 0; i < 4; i++) {
        Img *im = live[LIVE[i].name];
        fputc(im ? 1 : 0, f);
        if (im) { fwrite(im->px, 2, 1024, f); fwrite(im->a, 1, 1024, f); }
    }
    fclose(f);
}

/* ------------------------------------------------------------------ layout of the area */
static inline int bgEntry(int cnt, int w, int h, int x, int y) {
    int wx = x % w, wy = y % h, tx = wx >> 3, ty = wy >> 3, blk = (cnt >> 8) & 31;
    if (tx >= 32) { blk += 1; tx -= 32; }
    if (ty >= 32) { blk += w == 512 ? 2 : 1; ty -= 32; }
    return vu16(blk * 0x800 + (ty * 32 + tx) * 2);
}
static inline int cellAt(int vert, int pages, int page0, int c, int r) {
    if (!haveLvl || c < 0 || r < 0) return -1;
    int idx;
    if (vert) { if (c > 15 || r >= pages * 15) return -1; idx = r * 16 + c; }
    else { if (r > 14 || c >= pages * 16) return -1; idx = (page0 + (c >> 4)) * 240 + r * 16 + (c & 15); }
    return idx * 2 + 1 < (int)sizeof lvl ? lvl[idx * 2] | lvl[idx * 2 + 1] << 8 : -1;
}
int map_cell(int c, int r) { return cellAt(geo.vert, geo.pages, geo.page0, c, r); }
void map_area_size(int *cols, int *rows) { *cols = geo.vert ? 16 : geo.pages * 16; *rows = geo.vert ? geo.pages * 15 : 15; }
/* 1 / 0 when one layout clearly matches BG0 (90% of sampled cells, and better than the other), else -1 */
static int matchLayout(int pages) {
    int cnt = vu16(V_IO + 8), w = cnt & 0x4000 ? 512 : 256, h = cnt & 0x8000 ? 512 : 256;
    int hx = vu16(V_IO + 0x10) & 511, vy = vu16(V_IO + 0x12) & 511, qm = S_quadMode(), q[4];
    float best[2] = { 0, 0 };
    for (int v = 0; v < 2; v++) {
        int maxX = v ? 256 : pages * 256, maxY = v ? pages * 240 : 240;
        for (int cx = hx % w - w; cx < maxX; cx += w) for (int cy = vy % h - h; cy < maxY; cy += h) {
            if (cx + 240 <= 0 || cy + 160 <= 0) continue;
            int ok = 0, n = 0;
            for (int sy = 12; sy < 160; sy += 20) for (int sx = 4; sx < 240; sx += 18) {
                int wx = cx + sx, wy = cy + sy, t = cellAt(v, pages, 0, wx >> 4, wy >> 4);
                if (t < 0 || !quad(t, qm, q)) continue;
                n++;
                if ((bgEntry(cnt, w, h, hx + sx, vy + sy) & 0x3FF) == (q[((wy >> 3) & 1) * 2 + ((wx >> 3) & 1)] & 0x3FF)) ok++;
            }
            if (n >= 20 && (float)ok / n > best[v]) best[v] = (float)ok / n;
        }
    }
    int b = best[1] > best[0] ? 1 : 0;
    return best[b] >= 0.9f && best[b] - best[1 - b] > 0.02f ? b : -1;
}
static struct { int level, area, pages, vert, vidAt; } layoutMemo = { -1, -1, -1, -1, -1 };
static int vidCount;
void map_update_geo(void) {
    int pages = S_pages(), px = S_px(), py = S_py(), flag = S_vertical();
    if (px >> 8 >= pages && px >> 8 <= 10) { geo.vert = 0; geo.pages = 1; geo.page0 = px >> 8; return; }
    int runs = areaRuns();
    if (runs >= 0) { geo.vert = runs; geo.pages = pages; geo.page0 = 0; return; }
    int lv = S_level(), ar = S_area();
    if (layoutMemo.level != lv || layoutMemo.area != ar || layoutMemo.pages != pages) { layoutMemo.level = lv; layoutMemo.area = ar; layoutMemo.pages = pages; layoutMemo.vert = -1; layoutMemo.vidAt = -1; }
    if (haveVideo && haveLvl && layoutMemo.vidAt != vidCount && S_playing()) {
        layoutMemo.vidAt = vidCount;
        int m = matchLayout(pages);
        if (m >= 0) layoutMemo.vert = m;
    }
    geo.vert = layoutMemo.vert >= 0 ? layoutMemo.vert : px >= 256 ? 0 : py >= 256 ? 1 : flag;
    geo.pages = pages; geo.page0 = 0;
}
void map_camera(int *ox, int *oy) {
    int bg0 = vu16(V_IO + 8), w = (bg0 >> 14) & 1 ? 512 : 256, h = (bg0 >> 14) & 2 ? 512 : 256;
    int cx = vu16(V_IO + 0x10) & 511, cy = vu16(V_IO + 0x12) & 511, px = S_px(), py = S_py();
    while (cx + w <= px - 40) cx += w;
    while (cy + h <= py - 40) cy += h;
    *ox = cx; *oy = cy;
}

/* ------------------------------------------------------------------ the sky and the far layers (0.18, 0.37) */
static uint16_t pal565[512];
static inline uint32_t palRgb(int i) { return bgr555to24((unsigned)vu16(V_PAL + i * 2)); }
static inline int sum24(uint32_t c) { return (int)((c >> 16) & 255) + (int)((c >> 8) & 255) + (int)(c & 255); }
/* the far layers' most common colour along the top of the screen, if they cover most of it; 0 and *ok clear if not */
static uint32_t farSky(int *ok) {
    static uint32_t cols[64]; static int nums[64]; int ncols = 0, opaque = 0, n = 0, disp = vu16(V_IO);
    struct { int pri, cb, sb, w, h, hx, vy; } bgs[2]; int nb = 0;
    for (int b = 1; b <= 2; b++) if (disp & (0x100 << b)) {
        int cnt = vu16(V_IO + 8 + b * 2), k = nb++;
        bgs[k].pri = cnt & 3; bgs[k].cb = (cnt >> 2) & 3; bgs[k].sb = (cnt >> 8) & 31; bgs[k].w = cnt & 0x4000 ? 512 : 256; bgs[k].h = cnt & 0x8000 ? 512 : 256;
        bgs[k].hx = vu16(V_IO + 0x10 + b * 4) & 511; bgs[k].vy = vu16(V_IO + 0x12 + b * 4) & 511;
    }
    if (nb == 2 && bgs[1].pri < bgs[0].pri) { __typeof__(bgs[0]) t = bgs[0]; bgs[0] = bgs[1]; bgs[1] = t; }
    for (int y = 2; y < 34; y += 4) for (int x = 4; x < 240; x += 8) {
        n++;
        for (int k = 0; k < nb; k++) {
            int wx = (x + bgs[k].hx) % bgs[k].w, wy = (y + bgs[k].vy) % bgs[k].h, tx = wx >> 3, ty = wy >> 3, blk = bgs[k].sb;
            if (tx >= 32) { blk += 1; tx -= 32; }
            if (ty >= 32) { blk += bgs[k].w == 512 ? 2 : 1; ty -= 32; }
            int e = vu16(blk * 0x800 + (ty * 32 + tx) * 2), px = wx & 7, py = wy & 7;
            if (e & 0x400) px = 7 - px;
            if (e & 0x800) py = 7 - py;
            int bo = bgs[k].cb * 0x4000 + (e & 0x3FF) * 32 + py * 4 + (px >> 1);
            if (bo >= 0x10000) continue;
            int v = (video[bo] >> ((px & 1) * 4)) & 15;
            if (!v) continue;
            uint32_t col = palRgb((e >> 12) * 16 + v); int i;
            for (i = 0; i < ncols; i++) if (cols[i] == col) break;
            if (i == ncols && ncols < 64) { cols[ncols] = col; nums[ncols++] = 0; }
            if (i < ncols) nums[i]++;
            opaque++;
            break;
        }
    }
    int best = -1;
    for (int i = 0; i < ncols; i++) if (best < 0 || nums[i] > nums[best]) best = i;
    *ok = opaque * 2 > n && best >= 0;
    return *ok ? cols[best] : 0;
}
static uint32_t skyMemo; static int skyMemoAt = -1, skyMemoOk;
static uint32_t skyRgb(void) {
    uint32_t c = palRgb(0); int pot = S_pot();
    if (!pot && sum24(c) > 690) return 0xB8E4F8;
    if ((!pot && sum24(c) >= 150) || !haveVideo) return c;
    if (skyMemoAt != vidCount) { skyMemoAt = vidCount; skyMemo = farSky(&skyMemoOk); }
    return skyMemoOk ? skyMemo : c;
}

/* columns and rows of each far layer that have been on screen: the maps are rings the game streams into as the camera moves */
static struct { int key[5]; int n; uint8_t cols[3][64], rows[3][64]; int dx[3], dy[3], has[3]; int ry; } farMem = { { -1 } };
typedef struct { int b, cnt, pri, cb, w, h, hx, vy; } FarL;
static int farLayers(FarL *out) {
    int disp = vu16(V_IO), n = 0;
    for (int b = 1; b <= 2; b++) {
        if (!(disp & (0x100 << b))) continue;
        int cnt = vu16(V_IO + 8 + b * 2);
        if (cnt & 0x80) continue;
        FarL *l = &out[n++];
        l->b = b; l->cnt = cnt; l->pri = cnt & 3; l->cb = (cnt >> 2) & 3; l->w = cnt & 0x4000 ? 512 : 256; l->h = cnt & 0x8000 ? 512 : 256;
        l->hx = vu16(V_IO + 0x10 + b * 4) & 511; l->vy = vu16(V_IO + 0x12 + b * 4) & 511;
    }
    if (n == 2 && (out[1].pri > out[0].pri || (out[1].pri == out[0].pri && out[1].b > out[0].b))) { FarL t = out[0]; out[0] = out[1]; out[1] = t; }   /* back first */
    return n;
}
void map_mark_far(void) {
    int cx, cy; map_camera(&cx, &cy);
    int rx = cx - geo.page0 * 256, ry = cy;
    int key[5] = { S_level(), S_areaNo(), S_area(), geo.vert, geo.page0 };
    if (memcmp(farMem.key, key, sizeof key)) { memset(&farMem, 0, sizeof farMem); memcpy(farMem.key, key, sizeof key); farMem.ry = ry; }
    FarL ls[2]; int nl = farLayers(ls), pot = S_pot();
    for (int i = 0; i < nl; i++) {
        FarL *l = &ls[i]; int nc = l->w >> 3, nr = l->h >> 3;
        for (int k = l->hx >> 3; k <= (l->hx + 239 + (geo.vert ? 0 : 16)) >> 3; k++) if (!farMem.cols[l->b][k & (nc - 1)]) { farMem.cols[l->b][k & (nc - 1)] = 1; farMem.n++; }
        for (int k = l->vy >> 3; k <= (l->vy + 159) >> 3; k++) if (!farMem.rows[l->b][k & (nr - 1)]) { farMem.rows[l->b][k & (nr - 1)] = 1; farMem.n++; }
        /* A scrolling layer keeps the place it had when first seen. A pot's picture lies under the outline, so it follows the scroll. */
        if (!farMem.has[l->b] || (pot && (farMem.dy[l->b] != l->vy - ry || farMem.dx[l->b] != l->hx - rx))) {
            farMem.has[l->b] = 1; farMem.dy[l->b] = l->vy - ry; farMem.dx[l->b] = l->hx - rx; farMem.n++;
        }
    }
}
static int tileEmpty(int cb, int t) {
    int o = cb * 0x4000 + t * 32;
    if (o + 32 > 0x10000) return 1;
    for (int i = 0; i < 32; i++) if (video[o + i]) return 0;
    return 1;
}
static int farSparse(const FarL *l) {     /* BG2 across a horizontal area, unless it fills the screen (7-1's sky) */
    if (l->b != 2 || geo.vert) return 0;
    int n = 0, full = 0;
    for (int sy = 4; sy < 160; sy += 16) for (int sx = 4; sx < 240; sx += 16) { n++; if (!tileEmpty(l->cb, bgEntry(l->cnt, l->w, l->h, l->hx + sx, l->vy + sy) & 0x3FF)) full++; }
    return full * 10 < n * 9;
}

/* what the cells need to know about the far layers, worked out once per picture of the game */
typedef struct { FarL l; int nc, nr, sparse, last, scr0, scr1, dx, offY, tr0, tr1, tx1; } FarP;
static FarP fp[2]; static int nfp, potNow;
static uint16_t gradRow[160], baseCol, flatCol, topCol; static int hasGrad, hasFlat;
static const uint8_t SKY_ROWS[16] = { 0, 8, 16, 24, 32, 38, 44, 50, 56, 64, 68, 72, 76, 80, 84, 87 };
static int floorDiv8(int v) { return v >> 3; }          /* arithmetic shift: rounds down */
static void farPrepare(int aw, int ah) {
    potNow = S_pot();
    uint32_t c0 = palRgb(0), base = potNow ? 0 : sum24(c0) > 690 ? c0 : skyRgb();
    baseCol = rgb565(base); hasGrad = hasFlat = 0;
    if (!potNow) {                                       /* the game's sky: palette colour 0 rewritten by scanline from a table */
        uint8_t t[32]; int flat = 1;
        gba_read(SKY_TABLE, t, 32);
#define SKYE(i) (t[(i) * 2] | t[(i) * 2 + 1] << 8)
        for (int i = 1; i < 16; i++) if (SKYE(i) != SKYE(0)) flat = 0;
        if (!flat && SKYE(15) == vu16(V_PAL)) {
            hasGrad = 1;
            for (int k = 0; k < 16; k++) { uint16_t c = bgr555to565((unsigned)SKYE(k)); for (int y = SKY_ROWS[k]; y < (k < 15 ? SKY_ROWS[k + 1] : 160); y++) gradRow[y] = c; }
        }
    }
    if (hasGrad && geo.vert) { hasFlat = 1; flatCol = gradRow[80]; }
    topCol = hasFlat ? flatCol : hasGrad ? gradRow[0] : baseCol;
    FarL ls[2]; nfp = 0;
    int nl = farLayers(ls);
    for (int i = 0; i < nl; i++) {
        const FarL *l = &ls[i];
        if (!farMem.has[l->b]) continue;
        FarP *p = &fp[nfp++];
        p->l = *l; p->nc = l->w >> 3; p->nr = l->h >> 3;
        p->sparse = farSparse(l);
        p->last = (l->vy + 159) >> 3; if (p->last < 27) p->last = 27;
        p->scr0 = l->vy >> 3; p->scr1 = (l->vy + 159) >> 3;
        p->dx = potNow ? farMem.dx[l->b] : 0;
        p->offY = potNow || !geo.vert ? farMem.dy[l->b] : 0;
        p->tr0 = potNow ? 0 : geo.vert ? 0 : floorDiv8(p->offY);
        p->tr1 = potNow ? 27 : floorDiv8(ah - 1 + p->offY);
        p->tx1 = potNow ? 31 : geo.vert ? (aw - 1) >> 3 : ((aw - 1) >> 3 < 63 ? (aw - 1) >> 3 : 63);
    }
}
static inline int colOf(const FarP *p, int tx) {
    if (potNow) return tx;
    const uint8_t *seen = farMem.cols[p->l.b]; int nc = p->nc, c = tx & (nc - 1), f = geo.vert ? 30 : 32, o;
    if (seen[c]) return c;
    if (p->sparse) return -1;
    o = (c + f) & (nc - 1); if (seen[o]) return o;
    o = (c - f) & (nc - 1); if (seen[o]) return o;
    o = (c + 30) & (nc - 1); if (seen[o]) return o;
    o = (c - 30) & (nc - 1); return seen[o] ? o : -1;
}
static inline int rowOf(const FarP *p, int tr, int col) {
    if (potNow) return tr;
    if (geo.vert) {
        const uint8_t *seen = farMem.rows[p->l.b]; int nr = p->nr, r = tr & (nr - 1);
        for (int k = 0; k < 9; k++) { int q = (r + k * 20) & (nr - 1); if (seen[q]) return q; }
        return -1;
    }
    if (tr > p->last) return p->last - 3 + (tr - p->last - 1) % 4;
    if (tr >= 0) return tr;
    return tileEmpty(p->l.cb, bgEntry(p->l.cnt, p->l.w, p->l.h, col * 8, 0) & 0x3FF) ? -1 : ((tr % 8) + 8) % 8;
}

/* ------------------------------------------------------------------ the picture: every cell k x k */
#define MAXPX (2560 * 240)
static uint16_t *scaled, *scaledFar;             /* the map, and its far layers alone (to carry on past a narrow area's sides) */
static struct { int valid, level, area, cols, rows, page0, vert, qm, k, needFar; } mb;
static int sweepC, sweepR, fast, prioC0, prioC1, prioR0, prioR1, prioC, prioR, prioOn, freshLeft;
static int palHash, workHash, settled;
static short tapFrom[17][64]; static uint8_t tapWt[17][64]; static uint8_t tapStart[17][18]; static uint8_t tapsReady[17];

static void tapsFor(int k) {                     /* the box filter for 16 pixels down to k, weights in 1/256 */
    if (tapsReady[k]) return;
    int c = 0;
    for (int i = 0; i < k; i++) {
        tapStart[k][i] = (uint8_t)c;
        int a = i * 256 * 16 / k, b = (i + 1) * 256 * 16 / k, sum = 0, first = c;
        for (int j = a >> 8; j < 16 && j * 256 < b; j++) {
            int lo = a > j * 256 ? a : j * 256, hi = b < (j + 1) * 256 ? b : (j + 1) * 256, w = (hi - lo) * k / 16;
            if (w <= 0) continue;
            if (w > 255) w = 255;
            tapFrom[k][c] = (short)j; tapWt[k][c++] = (uint8_t)w; sum += w;
        }
        if (c > first) { int t = tapWt[k][c - 1] + 255 - sum; tapWt[k][c - 1] = (uint8_t)(t < 0 ? 0 : t > 255 ? 255 : t); }
    }
    tapStart[k][k] = (uint8_t)c; tapsReady[k] = 1;
}
static void scaleCell(const uint16_t *t, uint16_t *out, int stride, int k) {
    if (k == 16) { for (int y = 0; y < 16; y++) memcpy(out + y * stride, t + y * 16, 32); return; }
    tapsFor(k);
    const short *from = tapFrom[k]; const uint8_t *wt = tapWt[k], *st = tapStart[k];
    static int mid[16][16][3];
    for (int y = 0; y < 16; y++) for (int x = 0; x < k; x++) {
        int r = 0, g = 0, b = 0;
        for (int q = st[x]; q < st[x + 1]; q++) { unsigned c = t[y * 16 + from[q]]; int w = wt[q]; r += (c >> 11) * w; g += ((c >> 5) & 63) * w; b += (c & 31) * w; }
        mid[y][x][0] = r; mid[y][x][1] = g; mid[y][x][2] = b;
    }
    for (int y = 0; y < k; y++) for (int x = 0; x < k; x++) {
        int r = 0, g = 0, b = 0;
        for (int q = st[y]; q < st[y + 1]; q++) { const int *m = mid[from[q]][x]; int w = wt[q]; r += m[0] * w; g += m[1] * w; b += m[2] * w; }
        r = (r + 32767) >> 16; g = (g + 32767) >> 16; b = (b + 32767) >> 16;
        out[y * stride + x] = (uint16_t)((r > 31 ? 31 : r) << 11 | (g > 63 ? 63 : g) << 5 | (b > 31 ? 31 : b));
    }
}
/* One cell: the sky, the far layers back to front, then the level's own tiles (link.js farData + buildArea, a cell at a time). */
static void drawCell(int c, int r) {
    uint16_t t[256];
    int ax = c * 16, ay = r * 16, k = mb.k, stride = mb.cols * k;
    for (int y = 0; y < 16; y++) {
        uint16_t col;
        if (hasFlat) col = flatCol;
        else if (hasGrad) { int g = ay + y - farMem.ry; col = gradRow[g < 0 ? 0 : g > 159 ? 159 : g]; }
        else col = baseCol;
        for (int x = 0; x < 16; x++) t[y * 16 + x] = col;
    }
    for (int i = 0; i < nfp; i++) {
        const FarP *p = &fp[i]; const FarL *l = &p->l;
        int X = (!potNow && !geo.vert) ? ax % 512 : ax, px0 = X + p->dx, py0 = ay + p->offY;
        for (int tr = floorDiv8(py0); tr <= floorDiv8(py0 + 15); tr++) {
            if (tr < p->tr0 || tr > p->tr1) continue;
            for (int tx = floorDiv8(px0); tx <= floorDiv8(px0 + 15); tx++) {
                if (tx < 0 || tx > p->tx1) continue;
                int col = colOf(p, tx), row = col < 0 ? -1 : rowOf(p, tr, col);
                if (row < 0) continue;
                int e = bgEntry(l->cnt, l->w, l->h, col * 8, row * 8), bo = l->cb * 0x4000 + (e & 0x3FF) * 32;
                if (bo + 32 > 0x10000) continue;
                if (!potNow && (e == 0xA0 || e == 0xA1) && (row < p->scr0 || row > p->scr1)) continue;   /* the game's fill: never written */
                int ox = tx * 8 - px0, oy = tr * 8 - py0, hf = e & 0x400, vf = e & 0x800, pl = (e >> 12) * 16;
                for (int yy = 0; yy < 8; yy++) {
                    int py = oy + yy; if (py < 0 || py >= 16) continue;
                    int sy = vf ? 7 - yy : yy;
                    for (int xx = 0; xx < 8; xx++) {
                        int qx = ox + xx; if (qx < 0 || qx >= 16) continue;
                        int sx = hf ? 7 - xx : xx, b = video[bo + sy * 4 + (sx >> 1)], v = (sx & 1) ? b >> 4 : b & 15;
                        if (v) t[py * 16 + qx] = pal565[pl + v];
                    }
                }
            }
        }
    }
    if (mb.needFar) scaleCell(t, scaledFar + r * k * stride + c * k, stride, k);
    int cid = cellAt(mb.vert, geo.pages, mb.page0, c, r), q[4], cb = (vu16(V_IO + 8) >> 2) & 3;
    if (quad(cid, mb.qm, q)) for (int i2 = 0; i2 < 4; i2++) {
        int e = q[i2], tl = e & 0x3FF, pl = (e >> 12) * 16, hf = e & 0x400, vf = e & 0x800, base = cb * 0x4000 + tl * 32;
        if (base + 32 > 0x10000) continue;
        int ox = (i2 & 1) * 8, oy = (i2 >> 1) * 8;
        for (int y = 0; y < 8; y++) {
            int sy = vf ? 7 - y : y; uint16_t *d = t + (oy + y) * 16 + ox;
            for (int x = 0; x < 8; x++) {
                int sx = hf ? 7 - x : x, b = video[base + sy * 4 + (sx >> 1)], v = (sx & 1) ? b >> 4 : b & 15;
                if (v) d[x] = pal565[pl + v];
            }
        }
    }
    scaleCell(t, scaled + r * k * stride + c * k, stride, k);
}
void map_snapshot(void) {
    gba_read(0x06000000, video, 0x18000);
    gba_read(0x05000000, video + V_PAL, 0x400);
    gba_read(0x07000000, video + V_OAM, 0x400);
    gba_read(0x04000000, video + V_IO, 0x60);
    haveVideo = 1; vidCount++;
    int h = 0;
    for (int i = 0; i < 512; i++) { int c = vu16(V_PAL + i * 2); pal565[i] = bgr555to565((unsigned)c); h = h * 31 + c; }
    if (h != palHash) { palHash = h; fast = 1; settled = 0; }     /* a fade, a palette swap: redraw it soon */
    if (freshLeft > 0) { freshLeft--; fast = 1; settled = 0; sweepC = sweepR = 0; }
    /* Redrawing cells was most of the Link screen's cost, so going round again waits for something to have changed: the tiles (a sample of them, as link.js's key), the level, or new far columns. */
    int s = farMem.n;
    for (int i = 0; i < 0x10000; i += 97) s = s * 31 + video[i];
    if (haveLvl) for (size_t i = 0; i < sizeof lvl; i += 3) s = s * 31 + lvl[i];
    if (s != workHash) { workHash = s; settled = 0; }
}
void map_session(void) { mb.valid = 0; haveVideo = 0; haveLvl = 0; objs.key = -1; memset(&farMem, 0, sizeof farMem); farMem.key[0] = -1; }

/* ready the picture for this area and zoom; 0 when it can't be drawn yet (changing areas while the game isn't in play) */
static int mapSync(int k, int needFar, int vc0, int vc1, int vr0, int vr1) {
    int cols, rows, lv = S_level(), ar = S_area(), qm = S_quadMode();
    map_area_size(&cols, &rows);
    int same = mb.valid && mb.level == lv && mb.area == ar && mb.cols == cols && mb.rows == rows && mb.page0 == geo.page0 && mb.vert == geo.vert && mb.qm == qm;
    if (same && mb.k == k && (mb.needFar || !needFar)) return 1;
    if (!same && !S_playing() && !S_extraPage()) return 0;
    if (!scaled) { scaled = malloc(MAXPX * 2); scaledFar = malloc(MAXPX * 2); }
    mb.valid = 1; mb.level = lv; mb.area = ar; mb.cols = cols; mb.rows = rows; mb.page0 = geo.page0; mb.vert = geo.vert; mb.qm = qm; mb.k = k; mb.needFar = needFar;
    if (!same) freshLeft = 2;
    farPrepare(cols * 16, rows * 16);
    int n = cols * k * rows * k;
    for (int i = 0; i < n; i++) scaled[i] = topCol;
    if (needFar) for (int i = 0; i < n; i++) scaledFar[i] = topCol;
    sweepC = sweepR = 0; fast = 1; settled = 0;
    prioC0 = vc0 < 0 ? 0 : vc0; prioC1 = vc1 >= cols ? cols - 1 : vc1; prioR0 = vr0 < 0 ? 0 : vr0; prioR1 = vr1 >= rows ? rows - 1 : vr1;
    prioC = prioC0; prioR = prioR0; prioOn = 1;
    for (int b = 150; b > 0 && prioOn; b--) {
        drawCell(prioC, prioR);
        if (++prioC > prioC1) { prioC = prioC0; if (++prioR > prioR1) prioOn = 0; }
    }
    return 1;
}
static int mapVisible;
void map_work(void) {
    if (mapVisible <= 0) return;                         /* set while the MAP tab is being drawn (10 times a second) */
    mapVisible--;
    if (!mb.valid || !haveVideo || !(S_playing() || S_extraPage())) return;
    int n, cols, rows; map_area_size(&cols, &rows);
    if (cols != mb.cols || rows != mb.rows || mb.page0 != geo.page0 || mb.vert != geo.vert) return;
    if (prioOn) {
        for (n = 150; n > 0 && prioOn; n--) { drawCell(prioC, prioR); if (++prioC > prioC1) { prioC = prioC0; if (++prioR > prioR1) prioOn = 0; } }
        return;
    }
    if (settled) return;
    if (sweepC == 0 && sweepR == 0) farPrepare(cols * 16, rows * 16);
    for (n = fast ? 150 : 5; n > 0; n--) {   /* round again slowly: new far columns, animated tiles */
        drawCell(sweepC, sweepR);
        if (++sweepC >= mb.cols) { sweepC = 0; if (++sweepR >= mb.rows) { sweepR = 0; fast = 0; settled = 1; return; } }
    }
}

/* ------------------------------------------------------------------ the view */
static struct { int level, area, vert, page0, zl; int valid; float x, y; } view;
static float panX, panY, panX0, panY0, mapScale = 1; static int dragging, dragTick = -99, zoomShown = -99;
int mapMoved;
void map_zoom_next(void) { pf.zoom = (pf.zoom + 1) % 4; panX = panY = 0; view.valid = 0; zoomShown = tickN; app_settings_changed(); }
void map_pan_start(void) { dragging = 1; panX0 = panX; panY0 = panY; }
void map_pan_move(float dx, float dy) { panX = panX0 - dx / mapScale; panY = panY0 - dy / mapScale; dragTick = tickN; }
void map_pan_end(void) { dragging = 0; }
/* Keep a view still while the player is in its middle 40%, ease after them otherwise (like a game camera). */
static float follow(float v, float pp, float span, float len) {
    float tgt = v;
    if (pp < v + span * 0.3f) tgt = pp - span * 0.3f; else if (pp > v + span * 0.7f) tgt = pp - span * 0.7f;
    v += (tgt - v) * 0.5f;
    if (len <= span) return -(span - len) / 2;
    return v < 0 ? 0 : v > len - span ? len - span : v;
}
static void tagAt(int x, int y, const char *letter, uint32_t col) { rectA(x - 5, y - 5, 10, 10, 0, 166); text(letter, x - 4, y - 4, col); }
/* an item icon over a map cell (at least 10 px so it stays readable), else the letter tag */
static void mark(float px, float py, float sc, int name, const char *letter, uint32_t col, int alpha) {
    int sz = (int)lroundf(16 * sc * 1.5f); if (sz < 10) sz = 10;
    const Img *c = name != IC_NONE ? icon_at(name, sz) : NULL;
    if (c) {
        int x = (int)lroundf(px - sz / 2.0f), y = (int)lroundf(py - sz / 2.0f);
        blitShadow(c, x + 1, y + 1, 0x1E1004, 140 * alpha / 255);
        blitA(c, x, y, alpha);
        return;
    }
    if (letter && *letter) tagAt((int)lroundf(px), (int)lroundf(py), letter, col);
}
static void labelAt(float px, float py, const char *s, uint32_t col) {     /* a word on the map (boss names, EXIT) */
    int w = (int)strlen(s) * 8 + 4, x = (int)lroundf(px - w / 2.0f), y = (int)lroundf(py);
    rectA(x, y, w, 11, 0x1E1004, 204); text(s, x + 2, y + 2, col);
}
static const char *bossName(int t) {
    switch (t) { case 0x1C: return "BIRDO"; case 0x1D: return "MOUSER"; case 0x1F: return "TRYCLYDE"; case 0x21: return "CLAWGRIP"; case 0x2A: return "FRYGUY"; case 0x2C: return "WART"; case 0x2D: return "HAWKMOUTH"; case 0x34: return "ROBIRDO"; default: return NULL; }
}
static void coinDrawn(int x, int y, int on) {
    rect(x, y, 7, 7, on ? C.dbrown : 0xC8B888); rect(x + 1, y + 1, 5, 5, on ? C.red : 0xE8DCB0);
    if (on) rect(x + 3, y + 2, 1, 3, C.yellow);
}
static void markers(int ox, int oy, float sc, int vc0, int vc1, int vr0, int vr1, int subspace) {
    int cols, rows; map_area_size(&cols, &rows);
    if (vc0 < 0) vc0 = 0;
    if (vr0 < 0) vr0 = 0;
    if (vc1 >= cols) vc1 = cols - 1;
    if (vr1 >= rows) vr1 = rows - 1;
    for (int r = vr0; r <= vr1; r++) for (int c = vc0; c <= vc1; c++) {
        int t = map_cell(c, r);
        if (t < 0x23 || t > 0x9E) continue;
        if (!still_there(t)) continue;
        float px = ox + (c * 16 + 8) * sc, py = oy + (r * 16 + 8) * sc;
        if (pf.xray && is_grass(t) && *grass_letter(t)) mark(px, py, sc, grass_icon(t), grass_letter(t), t == 0x2C ? C.green : t == 0x2B ? C.pink : C.yellow, 255);
        else if (pf.markers && (t == 0x30 || t == 0x31 || S_isMushSpot(t))) {
            if (t == 0x30) rect((int)lroundf(px) - 1, (int)lroundf(py) - 1, 3, 3, C.red);
            else if (S_isMushSpot(t)) mark(px, py, sc, IC_mushroom, "M", C.orange, 255);
        }
        else if (pf.markers && S_isEggSpot(t))        /* Yoshi Challenge eggs (still_there: only in the challenge, not taken) */
            mark(px, py, sc, IC_egg, "E", C.green, (S_eggsFile(S_level()) >> S_eggIndex(t)) & 1 ? 115 : 255);
    }
    if (!pf.markers || subspace) return;
    int n = obj_count(), lv = S_level(), have = lv <= 19 ? S_aceSaved(lv) | S_aceLevel() : 0;
    for (int i = 0; i < n; i++) {                      /* key, crystal ball, boss and exit, where they spawn */
        int ty = objs.r[i][0]; float px = ox + (objs.r[i][1] * 16 + 8) * sc, py = oy + (objs.r[i][2] * 16 + 8) * sc;
        if (ty == OBJ_KEY) mark(px, py, sc, IC_key, "K", C.yellow, 255);
        else if (ty == OBJ_CRYSTAL) mark(px, py, sc, IC_crystal, "O", C.lblue, 255);
        else if (ty == OBJ_EXIT) labelAt(px, py - 6, "EXIT", 0xF8E080);
        else if (bossName(ty) || (ty < 0x60 && rom[ROM_CLASS_TABLE + ty] == 0x0A)) labelAt(px, py - 22, bossName(ty) ? bossName(ty) : "BOSS", 0xFF9078);
    }
    for (int i = 0; i < n && lv <= 19; i++) {           /* Ace Coins from the object list */
        int ty = objs.r[i][0]; if (ty < ACE_FIRST || ty >= ACE_FIRST + 5) continue;
        int got = (have >> (ty - ACE_FIRST)) & 1; float px = ox + (objs.r[i][1] * 16 + 8) * sc, py = oy + (objs.r[i][2] * 16 + 8) * sc;
        if (has_icons()) mark(px, py, sc, IC_ace, "", 0, got ? 89 : 255);
        else if (!got || (tickN >> 3) % 4) coinDrawn((int)lroundf(px) - 3, (int)lroundf(py) - 3, !got);
    }
}
void map_draw(Box inner) {
    mapBox = inner;
    if (!S_inLevel() || !haveLvl || !haveVideo) { textC("NO MAP HERE", inner.x + inner.w / 2, inner.y + inner.h / 2 - 4, T.muted); return; }
    int cols, rows; map_area_size(&cols, &rows);
    float aw = cols * 16.0f, ah = rows * 16.0f; int vert = geo.vert;
    float px = S_px() - geo.page0 * 256 + 8.0f, py = S_py() + 16.0f;
    /* Zoom levels (0.14): FIT = the area's whole height (whole width for vertical areas) fills the pane; WIDE shows 1.5x more; NEAR is
     * halfway to 1:1. Scales snap to whole pixels per 16 px cell. When FIT is already close to 1:1 the steps spread further out. */
    float fit = vert ? inner.w / aw : inner.h / ah; if (fit > 1) fit = 1;
    float L[4];
    if (fit > 0.7f) { L[0] = fit / 2.25f; L[1] = fit / 1.5f; L[2] = (fit / 1.5f + 1) / 2; L[3] = 1; }
    else { L[0] = fit / 1.5f; L[1] = fit; L[2] = (fit + 1) / 2; L[3] = 1; }
    int k = (int)floorf(L[pf.zoom] * 16), fitK = (int)floorf(fit * 16);
    if (k < 2) k = 2;
    if (fitK < 2) fitK = 2;
    float sc = k / 16.0f, spanX = inner.w / sc, spanY = inner.h / sc;
    int lv = S_level(), ar = S_area();
    if (!view.valid || view.level != lv || view.area != ar || view.vert != vert || view.page0 != geo.page0 || view.zl != pf.zoom) {
        view.valid = 1; view.level = lv; view.area = ar; view.vert = vert; view.page0 = geo.page0; view.zl = pf.zoom;
        view.x = px - spanX / 2; view.y = py - spanY / 2;
    }
    view.x = follow(view.x, px, spanX, aw); view.y = follow(view.y, py, spanY, ah);
    /* after a drag the view stays where you put it for ~4 s, then eases back to following you */
    if (!dragging && tickN - dragTick > 40) { panX *= 0.7f; panY *= 0.7f; if (fabsf(panX) + fabsf(panY) < 1) panX = panY = 0; }
    float cx = view.x + panX, cy = view.y + panY;
    cx = aw > spanX ? (cx < 0 ? 0 : cx > aw - spanX ? aw - spanX : cx) : -(spanX - aw) / 2;
    /* an area shorter than the pane sits on the pane's bottom edge (no empty band under the ground) */
    cy = ah > spanY ? (cy < 0 ? 0 : cy > ah - spanY ? ah - spanY : cy) : (vert ? -(spanY - ah) / 2 : ah - spanY);
    int ox = (int)lroundf(inner.x - cx * sc), oy = (int)lroundf(inner.y - cy * sc);
    mapScale = sc;
    int vc0 = (int)floorf(cx / 16) - 1, vc1 = (int)floorf((cx + spanX) / 16) + 1, vr0 = (int)floorf(cy / 16) - 1, vr1 = (int)floorf((cy + spanY) / 16) + 1;
    FarL ls[2]; int potHere = S_pot(), farHere = farMem.key[0] >= 0 && farLayers(ls) > 0;
    int needFar = farHere && !potHere && (int)(aw * sc) < inner.w;
    int ok = mapSync(k, needFar, vc0, vc1, vr0, vr1);
    cv_clip(inner.x, inner.y, inner.w, inner.h);
    rect(inner.x, inner.y, inner.w, inner.h, 0);
    if (!ok) { cv_noclip(); return; }
    mapVisible = 8;
    uint32_t sky = skyRgb();
    { unsigned tc = topCol; uint32_t tr = (tc >> 11) * 255 / 31, tg = ((tc >> 5) & 63) * 255 / 63, tb = (tc & 31) * 255 / 31;
      uint32_t sk = potHere ? 0 : farHere ? tr << 16 | tg << 8 | tb : sky; rect(inner.x, inner.y, inner.w, inner.h, sk); }
    int pw = mb.cols * mb.k, ph = mb.rows * mb.k;
    if (needFar && mb.needFar) {                       /* past the area's sides the background carries on (a vertical area's repeats every 240 px) */
        int per = (int)lroundf((vert ? 240 : 256) * sc);
        for (int bl = ox - per; bl + pw > inner.x; bl -= per) blitRaw(scaledFar, pw, pw, ph, bl, oy);
        for (int br = ox + per; br < inner.x + inner.w; br += per) blitRaw(scaledFar, pw, pw, ph, br, oy);
    }
    blitRaw(scaled, pw, pw, ph, ox, oy);
    markers(ox, oy, sc, vc0, vc1, vr0, vr1, S_subspace());
    {   /* you: a pink square with a white outline over your body, one pixel of dark round it */
        int x = (int)lroundf(ox + px * sc), y = (int)lroundf(oy + (py + 15) * sc) - 8;
        rect(x - 6, y - 6, 12, 12, 0x2A1404); rect(x - 5, y - 5, 10, 10, C.white); rect(x - 3, y - 3, 6, 6, 0xFF4FA3);
    }
    /* where the view is in the area: a thin track, the view, the player */
    if (vert ? ah > spanY : aw > spanX) {
        uint32_t me = CHAR_COL[S_character()];
        if (vert) {
            int bx = inner.x + inner.w - 3;
            rectA(bx, inner.y, 3, inner.h, 0x281808, 115);
            int bh = (int)lroundf(spanY / ah * inner.h); if (bh < 3) bh = 3;
            rectA(bx, inner.y + (int)lroundf(cy / ah * inner.h), 3, bh, 0xFBF4DC, 230);
            rect(bx, inner.y + (int)lroundf(py / ah * inner.h) - 1, 3, 3, me);
        } else {
            int by = inner.y + inner.h - 3;
            rectA(inner.x, by, inner.w, 3, 0x281808, 115);
            int bw = (int)lroundf(spanX / aw * inner.w); if (bw < 3) bw = 3;
            rectA(inner.x + (int)lroundf(cx / aw * inner.w), by, bw, 3, 0xFBF4DC, 230);
            rect(inner.x + (int)lroundf(px / aw * inner.w) - 1, by, 3, 3, me);
        }
    }
    /* zoom: four dots in the corner (the current one filled), and its name for 2 s after a change */
    int dx = inner.x + inner.w - 34, dy = inner.y + 4;
    rectA(dx - 3, dy - 2, 34, 9, 0x1E1004, 140);
    for (int zi = 0; zi < 4; zi++) { if (zi == pf.zoom) rect(dx + zi * 7, dy, 5, 5, C.white); else rectA(dx + zi * 7, dy, 5, 5, 0xFFFFFF, 102); }
    if (tickN - zoomShown < 20) {
        char zn[8];
        if (k == 16) strcpy(zn, "1:1"); else if (k == fitK) strcpy(zn, "FIT"); else snprintf(zn, sizeof zn, "%d%%", (int)lroundf(sc * 100));
        int zw = (int)strlen(zn) * 8 + 8;
        rectA(inner.x + inner.w / 2 - zw / 2, inner.y + 14, zw, 12, 0x1E1004, 191);
        textC(zn, inner.x + inner.w / 2, inner.y + 16, C.white);
    }
    cv_noclip();
}

/* linkscr.c - Super Mario Advance Dual's Link screen on the 3DS's touch screen: assets/link/link.js in C, at 320x240 with no scaling.
 * The same pages from the same RAM: status, MAP (linkmap.c), CHART, OPTIONS with the save slots, the dimmed logo on the menus and at
 * character select, Bonus Chance, Mario Bros. and the signed ending card. link.js's landscape layout (status column + one pane), plus a
 * third small card for what's hidden in the area, which under the map would take height the map can use here. */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <time.h>
#include "linkint.h"

static uint16_t buf[LINK_W * LINK_H];
#define W LINK_W
#define H LINK_H
const uint8_t *rom; size_t romLen;
int tickN;

Cols C = { 0xF8F8F8, 0xFBF4DC, 0xEFE0B8, 0xC86410, 0x5A3818, 0x3A2008, 0xE03828, 0x902010, 0x40B030, 0x187018, 0x3070E0, 0x88C8F8,
           0x988870, 0x182850, 0x0C1430, 0xF8C800, 0xF0A8C0, 0x000000, 0x9A7048, 0xD8B878, 0xA82A1A, 0xB42A18 };
TCols T = { 0xB42A18, 0x2458B0, 0x1F7424, 0x6E5C42, 0x8A5C00 };
const char *const CHARS[4] = { "MARIO", "PEACH", "TOAD", "LUIGI" };
const uint32_t CHAR_COL[4] = { 0xE03828, 0xF878B0, 0x3878F8, 0x30B030 };
static const uint32_t CHAR_TEXT[4] = { 0xB82818, 0xB83A76, 0x2458C0, 0x1F7A28 };
static const char *const LEVELS[20] = { "1-1", "1-2", "1-3", "2-1", "2-2", "2-3", "3-1", "3-2", "3-3", "4-1", "4-2", "4-3", "5-1", "5-2", "5-3", "6-1", "6-2", "6-3", "7-1", "7-2" };
static const int WORLD_START[7] = { 0, 3, 6, 9, 12, 15, 18 };
static const uint32_t CHAR_SHEET[4] = { 0x2EAB5C, 0x2EEB5C, 0x2F0B5C, 0x2ECB5C }, CHAR_PAL[4] = { 0x305A84, 0x305AC4, 0x305AE4, 0x305AA4 };
static const int STAND_BIG[2] = { 512, 516 }, STAND_SMALL[2] = { 576, 580 };

/* ------------------------------------------------------------------ layout */
#define TAB_H 16
#define A_W 118
#define A_H 92
#define B_H 66
static const Box LA = { 2, 2, 0, 0 }, LB = { 2, 2 + A_H + 1, 0, 0 };
static const Box PANE = { 121, 1, W - 122, H - TAB_H - 2 };
enum { TAB_MAP, TAB_CHART, TAB_OPTIONS, TAB_COUNT };
static const char *const TAB_NAME[TAB_COUNT] = { "MAP", "CHART", "OPTIONS" };

enum { B_QUIET, B_TAB, B_SLOT_SAVE, B_SLOT_LOAD, B_XRAY, B_MARKERS, B_ZOOM, B_PAUSE, B_SIG_CLEAR, B_SIG_SAVE };
typedef struct { short x, y, w, h; short act, arg; } Btn;
static Btn buttons[64]; static int nbtn;
static void addBtn(int x, int y, int w, int h, int act, int arg) {
    if (nbtn < 64) { Btn *b = &buttons[nbtn++]; b->x = (short)x; b->y = (short)y; b->w = (short)w; b->h = (short)h; b->act = (short)act; b->arg = (short)arg; }
}

static int themed;
static uint32_t soft(uint32_t col, float k) {
    uint32_t o = 0;
    for (int s = 16; s >= 0; s -= 8) { int v = (col >> s) & 255, b = (C.cream >> s) & 255; o = o << 8 | (uint32_t)lroundf(b + (v - b) * k); }
    return o;
}
static void pad0(char *o, uint64_t v, int w) {
    char t[24]; int n = 0;
    if (!v) t[n++] = '0';
    while (v) { t[n++] = (char)('0' + v % 10); v /= 10; }
    while (n < w) t[n++] = '0';
    while (n) *o++ = t[--n];
    *o = 0;
}
static void applyTheme(void) {       /* the pause panel's own colours for the words and the bits around them */
    if (themed || !K.ok) return;
    themed = 1;
    C.cream = K.cream; C.parch = K.parch; C.orange = K.orange; C.brown = K.brown; C.dbrown = K.dark;
    C.edge = K.brown; C.line = K.light; C.title = K.brown; C.big = K.orange;
    T.muted = K.brown; T.blue = K.dark; T.gold = K.dark;
}
/* The windows are the game's own: the pause panel's pieces from the first-start capture. x, y, w, h is the panel's body; the pins stick
 * out 3 px and the scallops reach 6 px in. Until the capture is there: the drawn card in the same place. */
static int cardsInBase;                                  /* the page's usual cards are already in the buffer: see draw() */
static void card(int x, int y, int w, int h) {
    if (cardsInBase) return;
    if (K.ok && K.corner) { blit(kit_card(w + 6, h + 6), x - 3, y - 3); return; }
    rectA(x + 1, y + 1, w, h + 2, 0x281808, 46);
    rrect(x - 1, y - 1, w + 2, h + 2, C.edge);
    rrect(x, y, w, h, C.cream);
}
static void backdrop(void) { memcpy(buf, kit_backdrop(320), sizeof buf); }
static void heartDrawn(int x, int y, int full) {
    static const uint8_t rows[7] = { 0x66, 0xFF, 0xFF, 0xFF, 0x7E, 0x3C, 0x18 };
    for (int r = 0; r < 7; r++) for (int c = 0; c < 8; c++) if ((rows[r] >> (7 - c)) & 1) {
        int below = r < 6 ? rows[r + 1] : 0, b = rows[r];
        int edge = r == 0 || c == 0 || c == 7 || !((below >> (7 - c)) & 1) || !((b >> (8 - c)) & 1) || !((b >> (6 - c)) & 1);
        rect(x + c, y + r, 1, 1, edge ? C.dbrown : full ? C.red : C.lblue);
    }
}
static void coinDrawn(int x, int y, int on, int big) {
    int s = big ? 7 : 5;
    rect(x, y, s, s, on ? C.dbrown : 0xC8B888); rect(x + 1, y + 1, s - 2, s - 2, on ? C.red : 0xE8DCB0);
    if (on && big) rect(x + 3, y + 2, 1, 3, C.yellow);
}
static void cherryDrawn(int x, int y, int on) {
    uint32_t c = on ? C.red : 0xD8C8A0, g = on ? C.dgreen : 0xC8B888;
    rect(x, y + 2, 3, 3, c); rect(x + 3, y + 3, 3, 3, c);
    rect(x + 2, y, 3, 1, g); rect(x + 1, y + 1, 1, 1, g); rect(x + 4, y + 1, 1, 2, g);
}
static void barAt(int x, int y, int w, float v, uint32_t col) {
    rect(x, y + 1, w, 5, C.parch);
    int n = (int)lroundf(w * (v > 1 ? 1 : v)); rect(x, y + 1, n < 1 ? 1 : n, 5, col);
}
#define DIM 82                        /* an empty slot's icon: the real icon, dimmed */

/* ------------------------------------------------------------------ portraits: one fixed picture per character and size, taken once
 * from the player's OAM sprites while standing still on the ground, and kept */
static Img *portraits[4][2]; static int still; static int lastPos = -1;
static const short OBJ_SIZES[12][2] = { {8,8},{16,16},{32,32},{64,64},{16,8},{32,8},{32,16},{64,32},{8,16},{8,32},{16,32},{32,64} };
static const Img *portraitFor(void) {
    if (S_charSelect()) return portraits[S_cursorChar()][0];
    return portraits[S_character()][S_health() <= 1 ? 1 : 0];
}
static void portraitsPath(char *p, size_t n) { snprintf(p, n, "%s/sma-portraits.bin", app_data_dir()); }
static void loadPortraits(void) {
    char p[300]; portraitsPath(p, sizeof p);
    FILE *f = fopen(p, "rb"); if (!f) return;
    for (int c = 0; c < 4; c++) for (int s = 0; s < 2; s++) {
        int has = fgetc(f); if (has == EOF) { fclose(f); return; }
        if (has != 1) continue;
        Img *im = img_new(32, 40, 1);
        if (fread(im->px, 2, 32 * 40, f) != 32 * 40 || fread(im->a, 1, 32 * 40, f) != 32 * 40) { img_free(im); fclose(f); return; }
        portraits[c][s] = im;
    }
    fclose(f);
}
static void savePortraits(void) {
    char p[300]; portraitsPath(p, sizeof p);
    FILE *f = fopen(p, "wb"); if (!f) return;
    for (int c = 0; c < 4; c++) for (int s = 0; s < 2; s++) {
        Img *im = portraits[c][s]; fputc(im ? 1 : 0, f);
        if (im) { fwrite(im->px, 2, 32 * 40, f); fwrite(im->a, 1, 32 * 40, f); }
    }
    fclose(f);
}
static int onGround(void) {
    if (!haveLvl) return 0;
    int c = (S_px() + 8) >> 4, r = (S_py() + 32) >> 4, feet = map_cell(c, r), body = map_cell(c, r - 1);
    return feet >= 0x100 && (body >> 8) == 0;             /* feet on a stand-on/solid cell (a vine is neither) */
}
static void grabPortrait(void) {
    if (!haveVideo) return;
    int ch = S_character(), small = S_health() <= 1 ? 1 : 0;
    if (portraits[ch][small] || still < 6 || S_holding() || !S_playing() || S_subspace() || !onGround()) return;
    int cx, cy; map_camera(&cx, &cy);
    int sx = S_px() - cx, sy = S_py() - cy;
    if (sx < 0 || sx > 224 || sy < 0 || sy > 128) return;  /* fully on screen */
    int map1d = (vu16(V_IO) & 0x40) != 0, n = 0, flips = 0, top = 999, bot = -999;
    struct { short x, y, w, h, tile, pal, hf, vf; } list[32];
    for (int s = 0; s < 128 && n < 32; s++) {
        int a0 = vu16(V_OAM + s * 8), a1 = vu16(V_OAM + s * 8 + 2), a2 = vu16(V_OAM + s * 8 + 4);
        if ((a0 & 0x300) == 0x200 || (a0 >> 14) == 3 || (a0 & 0x100)) continue;
        const short *sz = OBJ_SIZES[(a0 >> 14) * 4 + (a1 >> 14)]; int y = a0 & 255, x = a1 & 511;
        if (y >= 160) y -= 256;
        if (x >= 240) x -= 512;
        if (x + sz[0] <= sx || x >= sx + 16 || y + sz[1] <= sy || y >= sy + 32) continue;
        if (sz[0] > 16 || sz[1] > 32) continue;
        list[n].x = (short)(x - sx); list[n].y = (short)(y - sy); list[n].w = sz[0]; list[n].h = sz[1]; list[n].tile = (short)(a2 & 0x3FF); list[n].pal = (short)(a2 >> 12);
        list[n].hf = (a1 & 0x1000) != 0; list[n].vf = (a1 & 0x2000) != 0;
        if (list[n].hf) flips++;
        if (list[n].y < top) top = list[n].y;
        if (list[n].y + list[n].h > bot) bot = list[n].y + list[n].h;
        n++;
    }
    if (!n || bot - top < (small ? 14 : 28)) return;       /* ducking or a partial sprite */
    Img *im = img_new(32, 40, 1); int mirror = flips * 2 > n;  /* always face the sprite's own way */
    for (int i = n - 1; i >= 0; i--) {
        for (int ty = 0; ty < list[i].h / 8; ty++) for (int tx = 0; tx < list[i].w / 8; tx++) {
            int stx = list[i].hf ? list[i].w / 8 - 1 - tx : tx, sty = list[i].vf ? list[i].h / 8 - 1 - ty : ty;
            int t = map1d ? list[i].tile + sty * (list[i].w / 8) + stx : list[i].tile + sty * 32 + stx;
            int bo = 0x10000 + (t & 0x3FF) * 32;
            for (int yy = 0; yy < 8; yy++) for (int xx = 0; xx < 8; xx++) {
                int sxx = list[i].hf ? 7 - xx : xx, syy = list[i].vf ? 7 - yy : yy, b = video[bo + syy * 4 + (sxx >> 1)], v = (sxx & 1) ? b >> 4 : b & 15;
                if (!v) continue;
                int dx = 8 + list[i].x + tx * 8 + xx, dy = 4 + list[i].y + ty * 8 + yy;
                img_pixel(im, mirror ? 31 - dx : dx, dy, bgr555to24((unsigned)vu16(V_PAL + (256 + list[i].pal * 16 + v) * 2)));
            }
        }
    }
    portraits[ch][small] = im;
    savePortraits();
}
static void portraitHolder(int x, int y) {               /* before a portrait has been taken: the character's initial in a disc */
    int ch = S_character(); uint32_t c = soft(CHAR_COL[ch], 0.35f);
    for (int dy = -11; dy <= 11; dy++) { int w = (int)sqrtf(121.0f - dy * dy); rect(x + 12 - w, y + 14 + dy, w * 2 + 1, 1, c); }
    char s[2] = { CHARS[ch][0], 0 }; textC(s, x + 12, y + 10, CHAR_TEXT[ch]);
}

/* ------------------------------------------------------------------ the status cards */
static void icon(int name, int x, int y, int alpha, int fb, int fx, int fy, int on) {
    if (draw_icon(name, x, y, 16, alpha)) return;
    if (fb == 1) heartDrawn(fx, fy, on);
    else if (fb == 2) cherryDrawn(fx, fy, on);
    else if (fb == 3) coinDrawn(fx, fy, on, 1);
    else if (fb == 4) rect(fx, fy, 7, 9, on ? C.green : 0xD8C8A0);
}
static void blockA(int x, int y) {
    card(x, y, A_W, A_H);
    int lv = S_level(), rx = x + A_W - 8, ch = S_charSelect() ? S_cursorChar() : S_character();
    rrect(x + 5, y + 5, A_W - 10, 11, soft(CHAR_COL[ch], 0.3f));
    text("WORLD", x + 8, y + 7, C.brown); text(lv <= 19 ? LEVELS[lv] : "-", x + 50, y + 7, C.dbrown);
    char t[24];
    if (S_subspace()) strcpy(t, "SUB"); else snprintf(t, sizeof t, "A%d", S_areaNo() + 1);
    textR(t, rx, y + 7, C.brown);
    const Img *pic = portraitFor();
    if (pic) blit(pic, x + 3, y + 16); else portraitHolder(x + 7, y + 20);
    text(CHARS[ch], x + 40, y + 18, CHAR_TEXT[ch]);
    snprintf(t, sizeof t, "x%d", S_lives()); textR(t, rx, y + 18, C.brown);
    int hp = S_health(), mh = S_maxHearts();
    for (int i = 0; i < mh; i++) icon(i < hp ? IC_heartFull : IC_heartEmpty, x + 37 + i * 12, y + 27, 255, 1, x + 40 + i * 10, y + 31, i < hp);
    pad0(t, S_score(), 8); text(t, x + 40, y + 45, C.dbrown);
    int ch2 = S_cherries(); if (ch2 > 5) ch2 = 5;
    for (int k = 0; k < 5; k++) icon(IC_cherry, x + 5 + k * 13, y + 55, k < ch2 ? 255 : DIM, 2, x + 8 + k * 10, y + 60, k < ch2);
    if (draw_icon(IC_coin, x + 71, y + 55, 16, 255)) { snprintf(t, sizeof t, "x%d", S_coins()); text(t, x + 87, y + 60, C.brown); }
    else { snprintf(t, sizeof t, "C%d", S_coins()); text(t, x + 73, y + 60, C.brown); }
    int ace = lv <= 19 ? S_aceLevel() | S_aceSaved(lv) : 0;   /* this visit, or already on the file */
    for (int a = 0; a < 5; a++) { int got = (ace >> a) & 1; icon(IC_ace, x + 5 + a * 15, y + 72, got ? 255 : DIM, 3, x + 8 + a * 10, y + 76, got); }
    if (S_yoshi() && lv <= 19) {                             /* Yoshi Challenge: the level's two eggs */
        int ev = S_eggsVisit(), ef = S_eggsFile(lv);
        for (int e = 0; e < 2; e++) { int got = ((ev | ef) >> e) & 1; icon(IC_egg, x + 81 + e * 16, y + 72, got ? 255 : DIM, 4, x + 85 + e * 12, y + 73, got); }
    }
}
static void blockB(int x, int y, int h) {
    card(x, y, A_W, h);
    int lx = x + 7, rx = x + A_W - 7, top = y + (h - B_H) / 2, nact = 0;
    struct { const char *n; int v; uint32_t bar; int ic; uint32_t tc; } tm[3] = {
        { "STAR", S_star(), 0xD8A000, IC_star, T.gold }, { "WATCH", S_stopwatch(), C.blue, IC_watch, T.blue }, { "SUB", S_subspace(), C.dred, IC_potion, C.dred } };
    for (int i = 0; i < 3 && nact < 2; i++) if (tm[i].v > 0) {
        int tx = x + 5 + nact * 55; nact++;
        if (!draw_icon(tm[i].ic, tx, top + 5, 16, 255)) { char s[2] = { tm[i].n[0], 0 }; text(s, tx + 4, top + 9, tm[i].tc); }
        barAt(tx + 18, top + 9, 34, tm[i].v / 255.0f, tm[i].bar);
    }
    if (!nact) {                                             /* the player's state when no timer runs */
        const char *w = "READY"; uint32_t c = T.muted;
        if (S_gameOver()) { w = "GAME OVER"; c = T.red; }
        else if (S_dying()) { w = "OUCH"; c = T.red; }
        else if (S_charge() >= 60) { w = "POWER JUMP"; c = T.gold; }
        else if (S_holding()) w = "CARRYING";
        text(w, lx, top + 9, c);
    }
    int yy = top + 24; char t[24];
    text("TIME", lx, yy, C.brown); fmt_hms(t, (unsigned)(sma_t[0] / GBA_FPS)); textR(t, rx, yy, C.dbrown);
    text("DEATHS", lx, yy + 10, C.brown); snprintf(t, sizeof t, "%u", (unsigned)sma_t[1]); textR(t, rx, yy + 10, C.dbrown);
    if (S_yoshi()) { text("EGGS", lx, yy + 20, C.brown); snprintf(t, sizeof t, "%d/40", S_eggTotal()); textR(t, rx, yy + 20, C.dbrown); }
    else { text("LEVELS", lx, yy + 20, C.brown); snprintf(t, sizeof t, "%d/20", S_clearedCount()); textR(t, rx, yy + 20, C.dbrown); }
    int at = S_aceTotal();
    text(at >= 100 ? "ACES" : "ACE COINS", lx, yy + 30, C.brown); snprintf(t, sizeof t, "%d%%", at); textR(t, rx, yy + 30, C.dbrown);
}
/* What's left in this area: icons with counts (the line under the map in link.js). */
static const int ITEM_ORDER[] = { IC_potion, IC_oneup, IC_mushroom, IC_heart, IC_pow, IC_bomb, IC_bobomb, IC_shell, IC_rocket, IC_bigveg, IC_giantVeg, IC_giantShy, IC_giantShyBlue, IC_egg, IC_ninji, IC_coin };
static void blockC(int x, int y, int h) {
    card(x, y, A_W, h);
    int ix = x + 6, iy = y + 5, ixe = x + A_W - 6, rowH = 16, rows = 3;
    if (!S_inLevel() || !haveLvl) return;
    if (S_subspace()) { text("SUBSPACE:", ix, iy + 2, C.brown); text("PULL GRASS", ix, iy + 13, C.brown); text("FOR COINS", ix, iy + 24, C.brown); return; }
    int byIcon[IC_N]; memset(byIcon, 0, sizeof byIcon);
    struct { const char *name; int n; } names[8]; int nnames = 0, cherries = 0, spots = 0;
    int cols, rws; map_area_size(&cols, &rws);
    for (int r = 0; r < rws; r++) for (int c = 0; c < cols; c++) {
        int t = map_cell(c, r);
        if (t < 0x23 || t > 0x9E || !still_there(t)) continue;
        if (is_grass(t) && *grass_letter(t)) {
            int ic = grass_icon(t);
            if (has_icons() && ic != IC_NONE && icon_src(ic)) byIcon[ic]++;
            else {
                const char *nm = grass_name(t); int k;
                for (k = 0; k < nnames; k++) if (!strcmp(names[k].name, nm)) break;
                if (k == nnames && nnames < 8) { names[nnames].name = nm; names[nnames].n = 0; nnames++; }
                if (k < nnames) names[k].n++;
            }
        }
        if (t == 0x30) cherries++;
        if (S_isMushSpot(t)) spots++;
        if (S_isEggSpot(t)) { if (has_icons() && icon_src(IC_egg)) byIcon[IC_egg]++; }
    }
    int aceLeft, nk = 0, ncr = 0, n = obj_count(); ace_here(&aceLeft);
    for (int i = 0; i < n; i++) { int ty, ox, oy; obj_get(i, &ty, &ox, &oy); if (ty == OBJ_KEY) nk++; if (ty == OBJ_CRYSTAL) ncr++; }
    struct { int ic, n; const char *name; } items[40]; int ni = 0;
#define ADD(I, N, NM) do { if (ni < 40) { items[ni].ic = (I); items[ni].n = (N); items[ni].name = (NM); ni++; } } while (0)
    if (has_icons()) {
        if (aceLeft) ADD(IC_ace, aceLeft, NULL);
        if (nk) ADD(IC_key, nk, NULL);
        if (ncr) ADD(IC_crystal, ncr, NULL);
        for (size_t i = 0; i < sizeof ITEM_ORDER / sizeof ITEM_ORDER[0]; i++) {
            int k = ITEM_ORDER[i], v = k == IC_mushroom ? spots : byIcon[k];
            if (v) ADD(k, v, NULL);
        }
        if (cherries) ADD(IC_cherry, cherries, NULL);
    } else {
        if (aceLeft) ADD(IC_NONE, aceLeft, "ACE");
        if (cherries) ADD(IC_NONE, cherries, "CHERRY");
    }
    for (int i = 0; i < nnames; i++) ADD(IC_NONE, names[i].n, names[i].name);
    if (!ni) { text("NOTHING", ix, iy + 8, C.brown); text("HIDDEN HERE", ix, iy + 19, C.brown); return; }
    int cx = ix, row = 0;
    for (int i = 0; i < ni; i++) {
        char t[24];
        if (items[i].ic != IC_NONE) snprintf(t, sizeof t, "x%d", items[i].n); else snprintf(t, sizeof t, "%d %s", items[i].n, items[i].name);
        int w = (items[i].ic != IC_NONE ? 16 : 0) + (int)strlen(t) * 8 + 2;
        if (cx + w > ixe) { cx = ix; if (++row >= rows) break; }
        if (w > ixe - ix) continue;
        int ry = iy + row * rowH;
        if (items[i].ic != IC_NONE) { draw_icon(items[i].ic, cx, ry, 16, 255); cx += 16; }
        text(t, cx, ry + 5, C.brown); cx += (int)strlen(t) * 8 + 2;
    }
}

/* ------------------------------------------------------------------ MAP */
static void drawMap(Box p) {
    card(p.x + 1, p.y + 1, p.w - 2, p.h - 2);
    Box in = { p.x + 7, p.y + 7, p.w - 14, p.h - 14 };
    rect(in.x - 1, in.y - 1, in.w + 2, 1, C.line); rect(in.x - 1, in.y + in.h, in.w + 2, 1, C.line);
    rect(in.x - 1, in.y, 1, in.h, C.line); rect(in.x + in.w, in.y, 1, in.h, C.line);
    map_draw(in);
}

/* ------------------------------------------------------------------ the characters' sprites, from the ROM */
static Img *charSpr[4][2][2];
static const uint8_t BUST_TOP[4] = { 5, 0, 5, 1 };       /* where each big frame's ink starts (Mario, Peach, Toad, Luigi) */
static const Img *charSprite(int c, int big, int left) {
    Img **slot = &charSpr[c][big ? 1 : 0][left ? 1 : 0];
    if (*slot) return *slot;
    const uint8_t *sheet = rom + CHAR_SHEET[c], *pal = rom + CHAR_PAL[c];
    Img *s = img_new(16, 32, 1);
    const int *st = big ? STAND_BIG : STAND_SMALL;
    static const short E[4][3] = { { 0, 0, 0 }, { 1, 8, 0 }, { 32, 0, 8 }, { 33, 8, 8 } };
    for (int j = 0; j < 2; j++) for (int q = 0; q < 4; q++) {
        int bo = (st[j] + E[q][0] - SHEET_FIRST) * 32;
        for (int y = 0; y < 8; y++) for (int x = 0; x < 8; x++) {
            int b = sheet[bo + y * 4 + (x >> 1)], v = (x & 1) ? b >> 4 : b & 15;
            if (!v) continue;
            int dx = E[q][1] + x, dy = j * 16 + E[q][2] + y;
            img_pixel(s, left ? dx : 15 - dx, dy, bgr555to24((unsigned)(pal[v * 2] | pal[v * 2 + 1] << 8)));
        }
    }
    return *slot = s;
}

/* ------------------------------------------------------------------ CHART */
static int clearedByTracker(int L) { return (sma_t[2] >> L) & 1; }
static int clearedBy(int L) { int b = L * 2; uint32_t w = b < 32 ? sma_t[3] : sma_t[4]; return (w >> (b & 31)) & 3; }
/* 0.40: levels the file calls cleared but the tracker can't name. The file's own count per character can still tell, if only one
 * character has more clears than the tracker can place and enough to cover them all. -1 if not. */
static int unnamedBy(void) {
    int tally[4] = { 0, 0, 0, 0 }, n = 0, who = -1;
    for (int L = 0; L < 20; L++) if (S_cleared(L)) { if (clearedByTracker(L)) tally[clearedBy(L)]++; else n++; }
    for (int c = 0; c < 4; c++) if (S_charClears(c) > tally[c]) { if (who >= 0) return -1; who = c; }
    return who >= 0 && S_charClears(who) - tally[who] >= n ? who : -1;
}
static const uint32_t WORLD_COL[7] = { 0x30A030, 0xD09030, 0x2890E0, 0x40B8D8, 0x6048B0, 0xC07028, 0xE05880 };
static void drawChart(Box p) {
    card(p.x + 1, p.y + 1, p.w - 2, p.h - 2);
    int eggMode = S_yoshi() || S_eggTotal() > 0, cur = S_level(), inLv = S_inLevel(), x0 = p.x + 9, y0 = p.y + 8;
    int rowH = (p.h - (eggMode ? 34 : 24)) / 7; rowH = rowH > 28 ? 28 : rowH < 14 ? 14 : rowH;   /* a taller screen than the page's: the rows fill it */
    int colW = (p.w - 40) / 3, sx = colW >= 58 ? 31 : 25, unnamed = unnamedBy(); char t[32];
    for (int w = 0; w < 7; w++) {
        int y = y0 + w * rowH, n = w == 6 ? 2 : 3;
        rrect(x0 - 2, y, 19, rowH - 3, soft(WORLD_COL[w], 0.45f));
        snprintf(t, sizeof t, "W%d", w + 1); text(t, x0, y + (rowH - 11) / 2, C.dbrown);
        for (int i = 0; i < n; i++) {
            int L = WORLD_START[w] + i, x = x0 + 22 + i * colW, on = L == cur && inLv;
            if (on) { rrect(x - 3, y - 1, colW - 1, rowH - 1, 0xB8862E); rrect(x - 2, y, colW - 3, rowH - 3, 0xF4D27A); }
            text(LEVELS[L], x, y + 1, on ? C.dred : C.brown);
            int got = S_aceSaved(L) | (on ? S_aceLevel() : 0);
            if (colW >= 58) for (int a = 0; a < 5; a++) coinDrawn(x + a * 6, y + 10, (got >> a) & 1, 0);
            else for (int a = 0; a < 5; a++) rect(x + a * 4, y + 11, 3, 3, (got >> a) & 1 ? C.red : C.line);
            if (eggMode && colW >= 62) {
                int eg = S_eggsFile(L) | (on ? S_eggsVisit() : 0);
                for (int e = 0; e < 2; e++) rect(x + 50, y + 3 + e * 5, 4, 4, (eg >> e) & 1 ? C.green : 0xD8C8A0);
            }
            if (S_cleared(L)) {
                int who = clearedByTracker(L) ? clearedBy(L) : unnamed;
                if (who >= 0) { int bh = rowH - 2 < 32 - BUST_TOP[who] ? rowH - 2 : 32 - BUST_TOP[who]; blitSub(charSprite(who, 1, 1), 0, BUST_TOP[who], 16, bh, x + sx, y - 1); }
                else rect(x + sx + 1, y + 11, 13, 2, C.dbrown);
            }
            else if ((sma_t[5] >> L) & 1) rect(x + sx + 1, y + 11, 13, 1, C.grey);
        }
    }
    int fy = y0 + 7 * rowH + 1;
    snprintf(t, sizeof t, "%s%d/100", p.w >= 200 ? "ACE COINS " : "ACES ", S_aceTotal()); text(t, x0, fy, C.brown);
    snprintf(t, sizeof t, "%d/20", S_clearedCount()); textR(t, p.x + p.w - 9, fy, C.dbrown);
    if (eggMode) { snprintf(t, sizeof t, "EGGS %d/40", S_eggTotal()); textR(t, p.x + p.w - 9, fy + 10, C.brown); }
}

/* ------------------------------------------------------------------ OPTIONS */
static int optScroll, optMax, optMsgT; static char optMsg[24];
static struct { int n, act, until; } slotArm;
static Box optPane; static int optShown;
static void option(const char *label, const char *value, int x, int y, int w, int act) {
    text(label, x, y, C.brown);
    if (value && *value) textR(value, x + w, y, T.blue);
    addBtn(x - 2, y - 3, w + 4, 14, act, 0);
}
static void button(const char *label, int x, int y, int w, int h, int hot, int act, int arg) {
    rect(x, y, w, h, hot ? C.orange : C.brown);
    textC(label, x + w / 2, y + (h - 8) / 2, C.white);
    addBtn(x, y, w, h, act, arg);
}
static void ribbon(const char *t, int x, int y) { text(t, x, y, C.title); rect(x, y + 9, (int)strlen(t) * 8, 1, C.line); }
static int armed(int n, int act) { return slotArm.n == n && slotArm.act == act && slotArm.until > tickN; }
static const struct { const char *l, *name; int ic; } LEGEND[] = {
    { "C", "COIN", IC_coin }, { "V", "BIG VEG", IC_bigveg }, { "R", "ROCKET", IC_rocket }, { "S", "SHELL", IC_shell }, { "B", "BOMB", IC_bomb },
    { "K", "KEY", IC_key }, { "O", "CRYSTAL", IC_crystal }, { "P", "POTION", IC_potion }, { "1", "1UP", IC_oneup }, { "W", "POW", IC_pow }, { "H", "HEART", IC_heart },
    { "N", "NINJI", IC_ninji }, { "G", "GIANT", IC_giantShy }, { "E", "YOSHI EGG", IC_egg }, { "M", "MUSHROOM", IC_mushroom } };
static void drawOptions(Box p) {
    card(p.x + 1, p.y + 1, p.w - 2, p.h - 2);
    optPane = p; optShown = 1;
    cv_clip(p.x + 6, p.y + 7, p.w - 12, p.h - 14);
    int firstBtn = nbtn;
    int x = p.x + 9, y = p.y + 9 - optScroll, cw = p.w - 18;
    ribbon("SAVE STATES", x, y); y += 13;
    int cardW = (cw - 4) / 2, th = (int)lroundf((cardW - 4) * 2 / 3.0f), ch = th + 30;
    for (int i = 0; i < APP_SLOTS; i++) {
        const AppSlot *sl = app_slot(i + 1); int n = i + 1, cx = x + (i % 2) * (cardW + 4), cy = y + (i / 2) * (ch + 4); char s[4];
        rect(cx, cy, cardW, ch, C.parch);
        if (sl->time && sl->thumb) blitScaled(sl->thumb, THUMB_W, THUMB_H, cx + 2, cy + 2, cardW - 4, th);
        else { rect(cx + 2, cy + 2, cardW - 4, th, C.lblue); textC("EMPTY", cx + cardW / 2, cy + th / 2 - 2, C.white); }
        snprintf(s, sizeof s, "%d", n); text(s, cx + 3, cy + th + 4, C.brown);
        int bw = (cardW - 8) / 2, by = cy + th + 14;
        button(armed(n, B_SLOT_SAVE) ? "SURE?" : "SAVE", cx + 2, by, bw, 13, armed(n, B_SLOT_SAVE), B_SLOT_SAVE, n);
        if (sl->time) button(armed(n, B_SLOT_LOAD) ? "SURE?" : "LOAD", cx + cardW - 2 - bw, by, bw, 13, armed(n, B_SLOT_LOAD), B_SLOT_LOAD, n);
    }
    y += (APP_SLOTS + 1) / 2 * (ch + 4) + 6;
    ribbon("MAP", x, y); y += 13;
    option("X-RAY GRASS", pf.xray ? "ON" : "OFF", x, y, cw, B_XRAY); y += 13;
    option("MARKERS", pf.markers ? "ON" : "OFF", x, y, cw, B_MARKERS); y += 13;
    { char z[12]; snprintf(z, sizeof z, "%d OF 4", pf.zoom + 1); option("MAP ZOOM", z, x, y, cw, B_ZOOM); } y += 18;
    ribbon("MARKERS", x, y); y += 13;
    int nleg = (int)(sizeof LEGEND / sizeof LEGEND[0]), rowH = has_icons() ? 18 : 10;
    for (int i = 0; i < nleg; i++) {
        int xx = x + (i % 2) * (cw / 2), yy = y + (i / 2) * rowH;
        if (has_icons()) { if (!draw_icon(LEGEND[i].ic, xx, yy, 16, 255)) text(LEGEND[i].l, xx + 4, yy + 4, C.dred); text(LEGEND[i].name, xx + 19, yy + 4, C.brown); }
        else { text(LEGEND[i].l, xx, yy, C.dred); text(LEGEND[i].name, xx + 12, yy, C.brown); }
    }
    y += (nleg + 1) / 2 * rowH + 4;
    option("PAUSE GAME", "", x, y, cw, B_PAUSE); y += 13;
    { char v[32]; snprintf(v, sizeof v, "SMA DUAL 3DS %s", VERSION_3DS); text(v, x, y + 4, T.muted); }
    optMax = y + optScroll + 20 - (p.y + p.h); if (optMax < 0) optMax = 0;
    cv_noclip();
    /* buttons scrolled out of the pane can't be tapped */
    for (int i = firstBtn; i < nbtn; ) {
        Btn *b = &buttons[i];
        if (b->y + b->h <= p.y + 7 || b->y >= p.y + p.h - 7) { memmove(b, b + 1, sizeof *b * (size_t)(nbtn - i - 1)); nbtn--; }
        else i++;
    }
    if (optMsgT > tickN) {
        int tw = (int)strlen(optMsg) * 8 + 10;
        rect(p.x + p.w / 2 - tw / 2, p.y + p.h - 22, tw, 13, C.dbrown); textC(optMsg, p.x + p.w / 2, p.y + p.h - 19, C.white);
    }
}
static void drawTabs(void) {
    int y = H - TAB_H, w = W / TAB_COUNT;
    rect(0, y - 1, W, H - y + 1, C.edge);
    for (int i = 0; i < TAB_COUNT; i++) {
        int x = i * w, on = i == pf.tab, tw = i == TAB_COUNT - 1 ? W - x : w;
        rrect(x + 1, y, tw - 2, H - y, on ? C.cream : C.parch);
        if (on) rect(x + 2, y, tw - 4, 2, themed ? C.orange : soft(i == TAB_MAP ? 0x30A030 : i == TAB_CHART ? 0x2870E0 : 0xE89000, 0.8f));
        textC(TAB_NAME[i], x + tw / 2, y + 4, on ? C.dbrown : C.brown);
        addBtn(x, y, tw, H - y, B_TAB, i);
    }
}

/* ------------------------------------------------------------------ Mario Bros., Bonus Chance */
static void marioBros(void) {
    int cx = W / 2, y = H / 2 - 50; char t[16];
    card(cx - 96, y, 192, 92);
    textCS("MARIO BROS.", cx, y + 9, C.big, 2);
    text("PHASE", cx - 80, y + 32, C.brown); snprintf(t, sizeof t, "%d", S_mbRound()); textR(t, cx + 80, y + 32, C.dbrown);
    text("ENEMIES LEFT", cx - 80, y + 44, C.brown); snprintf(t, sizeof t, "%d", S_mbLeft()); textR(t, cx + 80, y + 44, C.dbrown);
    text("SCORE", cx - 80, y + 56, C.brown); pad0(t, S_mbScore(), 6); textR(t, cx + 80, y + 56, C.dbrown);
    text("LIVES", cx - 80, y + 68, C.brown); snprintf(t, sizeof t, "%d", S_mbLives()); textR(t, cx + 80, y + 68, C.dbrown);
}
static void bonusChance(void) {
    int cx = W / 2, y = H / 2 - 50, spins = S_coins(); char t[16];
    card(cx - 108, y, 216, 80);
    textCS("BONUS CHANCE", cx, y + 9, C.big, 2);
    text("SPINS LEFT", cx - 80, y + 32, C.brown); snprintf(t, sizeof t, "%d", spins); textR(t, cx + 80, y + 32, C.dbrown);
    text("LIVES", cx - 80, y + 44, C.brown); snprintf(t, sizeof t, "%d", S_lives()); textR(t, cx + 80, y + 44, C.dbrown);
    textC(S_mainState() == 2 ? "SPINNING" : spins ? "PRESS A TO SPIN" : "NO SPINS LEFT", cx, y + 62, T.muted);
}

/* ------------------------------------------------------------------ the logo screen (0.34, 0.41, 0.42): on the menus, character select
 * and from there into a level. A door or a lost life runs state 0 as well, so that only counts straight after these screens. */
static int quietOpen, quietTab, quietLoad;
static Img *logoImg;
const Img *link_logo(void) {
    if (logoImg) return logoImg;
    const uint8_t *tiles = rom + LOGO_TILES, *map = rom + LOGO_MAP, *pal = rom + LOGO_PAL;
    static const int box[4] = { 20, 2, 202, 125 };
    Img *c = img_new(box[2], box[3], 1);
    for (int y = 0; y < box[3]; y++) for (int x = 0; x < box[2]; x++) {
        int mx = x + box[0], my = y + box[1], o = ((my >> 3) * 32 + (mx >> 3)) * 2, e = map[o] | map[o + 1] << 8;
        int ix = e & 0x400 ? 7 - (mx & 7) : mx & 7, iy = e & 0x800 ? 7 - (my & 7) : my & 7, v = tiles[(e & 0x3FF) * 64 + iy * 8 + ix];
        if (v) img_pixel(c, x, y, bgr555to24((unsigned)(pal[v * 2] | pal[v * 2 + 1] << 8)));
    }
    return logoImg = c;
}
static int quietNow(void) {
    int mode = S_gameMode(), sub = S_subMode();
    if (mode != 3) { quietLoad = 0; return mode != 4; }
    if (sub == 1) { if (S_mainState() != 0) quietLoad = 0; return quietLoad; }
    if (sub == 2 || sub == 4) { quietLoad = 0; return 0; }   /* Bonus Chance and the ending have their own screens */
    return quietLoad = 1;
}
static void quietScreen(void) {
    static uint16_t *cache; static int cacheKit = -1;
    if (!cache) cache = malloc(sizeof buf);
    if (cacheKit != K.ok) {
        cacheKit = K.ok;
        card(4, 4, W - 8, H - 8);
        const Img *im = link_logo();
        blit(im, (W - im->w) / 2, (H - im->h) / 2);
        rectA(0, 0, W, H, 0, 153);                            /* the logo isn't see-through: the page dims as one */
        memcpy(cache, buf, sizeof buf);
    } else memcpy(buf, cache, sizeof buf);
    addBtn(0, 0, W, H, B_QUIET, 0);
}

/* ------------------------------------------------------------------ the ending card (0.28, 0.32, 0.33): all through the ending and credits,
 * with the run's stats and a field to sign. Taken once, after the ending's init has counted 7-2. */
static struct {
    int valid, saved, secs, deaths, levels, ace, eggs, clears[4], dev, warps, sw, sh; uint64_t score; char date[12], clock[8]; time_t t; uint8_t *sig;
} report;
static int endPreview;
static Box sigBox; static int sigOn;
static struct { int on; float x, y, mx, my; } drawing;
void link_toggle_ending(void) { endPreview = !endPreview; if (!endPreview) report.valid = 0; }
static void readEnding(void) {
    if (!endPreview && !S_ending()) { report.valid = 0; drawing.on = 0; return; }
    if (report.valid || !(endPreview || S_mainState() >= 2)) return;
    time_t now = time(NULL); struct tm *tm = gmtime(&now);     /* the 3DS's clock runs on local time */
    free(report.sig); memset(&report, 0, sizeof report);
    snprintf(report.date, sizeof report.date, "%04d-%02d-%02d", tm->tm_year + 1900, tm->tm_mon + 1, tm->tm_mday);
    snprintf(report.clock, sizeof report.clock, "%02d:%02d", tm->tm_hour, tm->tm_min);
    report.t = now; report.secs = (int)(sma_t[0] / GBA_FPS); report.deaths = (int)sma_t[1]; report.levels = S_clearedCount(); report.ace = S_aceTotal();
    report.eggs = S_eggTotal(); report.score = S_score(); for (int c = 0; c < 4; c++) report.clears[c] = S_charClears(c);
    report.dev = app_dev_used();
    for (uint32_t v = sma_t[5]; v; v >>= 1) report.warps += v & 1;
    report.valid = 1;
}
static void endingCard(void) {
    sigOn = 0;
    if (!report.valid) return;
    int x = 4, y0 = 4, w = W - 8, h = H - 8, cx = W / 2, px = x + 8, pw = w - 16, y = y0 + 6, rh = 11; char t[32], t2[32];
    card(x, y0, w, h);
    snprintf(t, sizeof t, "%s %s", report.date, report.clock); text(t, px, y, C.dbrown);
    if (report.dev) textR("DEV CODES", px + pw, y, T.red);
    y += rh + 1;
    struct { const char *l, *s; char v[24]; uint32_t c; } rows[7]; int n = 0;
#define ROW(L, S, COL) do { rows[n].l = (L); rows[n].s = (S); snprintf(rows[n].v, 24, "%s", t2); rows[n].c = (COL); n++; } while (0)
    fmt_hms(t2, (unsigned)report.secs); ROW("PLAY TIME", "TIME", C.dbrown);
    snprintf(t2, sizeof t2, "%d/20", report.levels); ROW("LEVELS", "LEVELS", report.levels >= 20 ? T.green : C.dbrown);
    snprintf(t2, sizeof t2, "%d", report.deaths); ROW("DEATHS", "DEATHS", C.dbrown);
    snprintf(t2, sizeof t2, "%d/100", report.ace); ROW("ACE COINS", "ACES", report.ace >= 100 ? T.green : C.dbrown);
    pad0(t2, report.score, 1); ROW("SCORE", "PTS", C.dbrown);
    if (report.warps || !report.eggs) { snprintf(t2, sizeof t2, "%d", report.warps); ROW("WARPS", "WARPS", C.dbrown); }
    if (report.eggs) { snprintf(t2, sizeof t2, "%d/40", report.eggs); ROW("YOSHI EGGS", "EGGS", report.eggs >= 40 ? T.green : C.dbrown); }
    int gap = 8, colW = (pw - gap) / 2;
    for (int i = 0; i < n; i++) {
        int xx = px + (i % 2) * (colW + gap), yy = y + (i / 2) * rh;
        const char *lab = (int)strlen(rows[i].l) * 8 + 8 + (int)strlen(rows[i].v) * 8 > colW ? rows[i].s : rows[i].l;
        text(lab, xx, yy, T.muted); textR(rows[i].v, xx + colW, yy, rows[i].c);
    }
    y += (n + 1) / 2 * rh;
    int top = 0; for (int c = 0; c < 4; c++) if (report.clears[c] > top) top = report.clears[c];
    int cwid = pw / 4, feet = y + 32; static const int ORDER[4] = { 0, 3, 2, 1 };
    for (int i = 0; i < 4; i++) {
        int c = ORDER[i], xx = px + i * cwid, mvp = top && report.clears[c] == top;
        blit(charSprite(c, 1, 0), xx, feet - 32);
        snprintf(t, sizeof t, "%d", report.clears[c]); text(t, xx + 18, feet - 8, mvp ? T.gold : C.dbrown);
        if (mvp) text("MVP", xx + 18, feet - 18, T.gold);
    }
    y = feet + 1;
    int foot = 18, bw = pw, by = y, bh = y0 + h - 6 - foot - 3 - by, ty = by + bh + 3;
    if (!report.sig || report.sw != bw || report.sh != bh) { free(report.sig); report.sig = calloc((size_t)bw, bh); report.sw = bw; report.sh = bh; }
    sigOn = !report.saved; sigBox.x = px; sigBox.y = by; sigBox.w = bw; sigBox.h = bh;
    rect(px, by, bw, bh, C.edge); rect(px + 1, by + 1, bw - 2, bh - 2, C.parch);
    int line = by + bh - (bh >> 2 > 10 ? bh >> 2 : 10), ink = 0;
    rect(px + 8, line, bw - 16, 1, C.line);
    uint32_t dk = C.dbrown;
    for (int q = 0; q < bw * bh; q++) if (report.sig[q]) { ink = 1; rect(px + q % bw, by + q / bw, 1, 1, dk); }
    if (!ink) textC("SIGN YOUR NAME", cx, line - 12, T.muted);
    if (report.saved) { textC("SIGNED", cx, ty + 5, T.green); return; }
    int half = (pw - 4) >> 1;
    button("CLEAR", px, ty, half, foot, 0, B_SIG_CLEAR, 0);
    button("SAVE", px + pw - half, ty, half, foot, 0, B_SIG_SAVE, 0);
}
static void sigDot(float fx, float fy) {
    if (!report.sig || !sigOn) return;
    int x = (int)lroundf(fx) - sigBox.x, y = (int)lroundf(fy) - sigBox.y;
    for (int j = -1; j <= 0; j++) for (int i = -1; i <= 0; i++) {
        int qx = x + i, qy = y + j;
        if (qx >= 1 && qy >= 1 && qx < sigBox.w - 1 && qy < sigBox.h - 1) report.sig[qy * sigBox.w + qx] = 1;
    }
}
static void sigCurve(float x0, float y0, float cx, float cy, float x1, float y1) {
    int n = (int)ceilf(fabsf(cx - x0) + fabsf(cy - y0) + fabsf(x1 - cx) + fabsf(y1 - cy)); if (n < 1) n = 1;
    for (int i = 1; i <= n; i++) {
        float t = (float)i / n, u = 1 - t;
        sigDot(u * u * x0 + 2 * u * t * cx + t * t * x1, u * u * y0 + 2 * u * t * cy + t * t * y1);
    }
}
/* SAVE keeps the run with the signature, appended to sma-endings.bin */
static void saveEnding(void) {
    int any = 0;
    for (int q = 0; q < report.sw * report.sh; q++) if (report.sig[q]) { any = 1; break; }
    if (!any) return;
    char p[300]; snprintf(p, sizeof p, "%s/sma-endings.bin", app_data_dir());
    FILE *f = fopen(p, "ab");
    if (f) {
        uint32_t h[16] = { 0x45414D53, 1, (uint32_t)report.t, (uint32_t)report.secs, (uint32_t)report.deaths, (uint32_t)report.levels, (uint32_t)report.ace, (uint32_t)report.eggs,
                           (uint32_t)report.score, (uint32_t)(report.score >> 32), (uint32_t)report.clears[0] | (uint32_t)report.clears[1] << 8 | (uint32_t)report.clears[2] << 16 | (uint32_t)report.clears[3] << 24,
                           (uint32_t)report.dev, (uint32_t)report.warps, (uint32_t)report.sw, (uint32_t)report.sh, 0 };
        fwrite(h, 4, 16, f);
        int nb = (report.sw * report.sh + 7) / 8; uint8_t *bits = calloc(1, (size_t)nb);
        for (int q = 0; q < report.sw * report.sh; q++) if (report.sig[q]) bits[q >> 3] |= (uint8_t)(1 << (q & 7));
        fwrite(bits, 1, (size_t)nb, f); free(bits); fclose(f);
    }
    report.saved = 1; sigOn = 0;
}

/* ------------------------------------------------------------------ the page */
static void draw(void) {
    cv_target(buf, W, H);
    applyTheme();
    backdrop();
    nbtn = 0; mapBox.w = 0; optShown = 0; font_solid = 1; cardsInBase = 0;
    if (endPreview || S_ending()) { endingCard(); return; }
    int menus = S_gameMode() != 3 && S_gameMode() != 4, quiet = quietNow();
    if (quietOpen && (!quiet || (menus && pf.tab != TAB_OPTIONS))) {   /* the game moved on, or on the menus another tab was picked: back to the logo */
        quietOpen = 0;
        if (!quiet && pf.tab == TAB_OPTIONS) pf.tab = (uint8_t)quietTab;
    }
    if (quiet && !quietOpen) { quietScreen(); return; }
    if (S_gameMode() == 4) { marioBros(); drawTabs(); return; }
    if (S_bonusChance()) { bonusChance(); drawTabs(); return; }
    /* The backdrop and the four cards never change, and blending them is most of a page's cost: keep them as one picture. */
    { static uint16_t *base; static int baseKit = -1; int cy = LB.y + B_H + 1;
      if (!base) base = malloc(sizeof buf);
      if (baseKit != K.ok) {
          baseKit = K.ok;
          card(LA.x, LA.y, A_W, A_H); card(LB.x, LB.y, A_W, B_H); card(LA.x, cy, A_W, H - TAB_H - 3 - cy); card(PANE.x + 1, PANE.y + 1, PANE.w - 2, PANE.h - 2);
          memcpy(base, buf, sizeof buf);
      } else memcpy(buf, base, sizeof buf);
      cardsInBase = 1;
      blockA(LA.x, LA.y);
      blockB(LB.x, LB.y, B_H);
      blockC(LA.x, cy, H - TAB_H - 3 - cy);
    }
    if (pf.tab == TAB_MAP) drawMap(PANE);
    else if (pf.tab == TAB_CHART) drawChart(PANE);
    else drawOptions(PANE);
    cardsInBase = 0;
    drawTabs();
}
const uint16_t *link_buffer(void) { return buf; }
void link_redraw(void) { draw(); }
void link_kit_changed(void) { themed = 0; }
void link_session(void) { map_session(); lastPos = -1; still = 0; report.valid = 0; endPreview = 0; quietLoad = 0; quietOpen = 0; }
void link_init(const uint8_t *r, size_t n) {
    rom = r; romLen = n;
    font_load(rom + ROM_FONT);
    map_init();
    loadPortraits();
    live_load(app_data_dir());
    kit_make_drawn_backdrops();
    if (pf.tab >= TAB_COUNT) pf.tab = TAB_MAP;
    if (pf.zoom > 3) pf.zoom = 1;
}
static int lastLvl = -99, lastVid = -99;
void link_tick(void) {
    tickN++;
    if (S_inLevel()) {
        int pk = S_px() * 4096 + S_py() * 8 + S_character();
        still = pk == lastPos ? still + 1 : 0; lastPos = pk;
        if (tickN - lastLvl >= 5) { lastLvl = tickN; gba_read(0x02000000, lvl, sizeof lvl); haveLvl = 1; }  /* 11 pages: the area's 10 at most, then subspace's */
        if (tickN - lastVid >= 10 && (S_playing() || S_extraPage())) {   /* 0.34: not the Subspace timer: it is 0 in a pot */
            lastVid = tickN;
            map_snapshot();
            grabPortrait(); live_grab(app_data_dir());
            map_update_geo(); map_mark_far();
        }
        map_update_geo();
    }
    readEnding();
    draw();
}
void link_work(void) { map_work(); }

/* ------------------------------------------------------------------ touch: tap a button, tap the map for the next zoom, drag the map to
 * look around (it eases back ~4 s after you let go), drag the options to scroll, sign on the ending card */
static struct { int on, x, y, moved, map, opt, optS, lx, ly; } dn;
void link_touch_cancel(void) { if (dn.map) map_pan_end(); dn.on = 0; dn.map = 0; dn.opt = 0; drawing.on = 0; }
static void tapped(const Btn *b) {
    switch (b->act) {
    case B_QUIET: quietOpen = 1; quietTab = pf.tab; pf.tab = TAB_OPTIONS; optScroll = 0; break;
    case B_TAB: pf.tab = (uint8_t)b->arg; app_settings_changed(); break;
    case B_SLOT_SAVE: {
        int n = b->arg;
        if (!armed(n, B_SLOT_SAVE) && app_slot(n)->time) { slotArm.n = n; slotArm.act = B_SLOT_SAVE; slotArm.until = tickN + 40; break; }
        slotArm.n = 0; int ok = app_save_slot(n);
        if (ok) snprintf(optMsg, sizeof optMsg, "SAVED %d", n); else strcpy(optMsg, "FAILED");
        optMsgT = tickN + 40; break;
    }
    case B_SLOT_LOAD: {
        int n = b->arg;
        if (!armed(n, B_SLOT_LOAD)) { slotArm.n = n; slotArm.act = B_SLOT_LOAD; slotArm.until = tickN + 40; break; }
        slotArm.n = 0; int ok = app_load_slot(n);
        if (ok) snprintf(optMsg, sizeof optMsg, "LOADED %d", n); else strcpy(optMsg, "FAILED");
        optMsgT = tickN + 40; break;
    }
    case B_XRAY: pf.xray = !pf.xray; app_settings_changed(); break;
    case B_MARKERS: pf.markers = !pf.markers; app_settings_changed(); break;
    case B_ZOOM: map_zoom_next(); break;
    case B_PAUSE: app_pause(); break;
    case B_SIG_CLEAR: if (report.sig) memset(report.sig, 0, (size_t)report.sw * report.sh); break;
    case B_SIG_SAVE: saveEnding(); break;
    }
}
int link_touch(int d, int x, int y) {
    int m = 4;
    if (d && !dn.on) {
        memset(&dn, 0, sizeof dn); dn.on = 1; dn.x = dn.lx = x; dn.y = dn.ly = y;
        if (report.valid && sigOn && x >= sigBox.x - m && x < sigBox.x + sigBox.w + m && y >= sigBox.y - m && y < sigBox.y + sigBox.h) {
            drawing.on = 1; drawing.x = drawing.mx = (float)x; drawing.y = drawing.my = (float)y; sigDot((float)x, (float)y);
            return 1;
        }
        if (mapBox.w && x >= mapBox.x && x < mapBox.x + mapBox.w && y >= mapBox.y && y < mapBox.y + mapBox.h) { dn.map = 1; map_pan_start(); }
        if (optShown && x >= optPane.x && x < optPane.x + optPane.w && y >= optPane.y && y < optPane.y + optPane.h) { dn.opt = 1; dn.optS = optScroll; }
        return 0;
    }
    if (d) {
        if (drawing.on) {
            if (x == dn.lx && y == dn.ly) return 0;
            dn.lx = x; dn.ly = y;
            float nx = drawing.x + (x - drawing.x) * 0.6f, ny = drawing.y + (y - drawing.y) * 0.6f, mx = (drawing.x + nx) / 2, my = (drawing.y + ny) / 2;
            sigCurve(drawing.mx, drawing.my, drawing.x, drawing.y, mx, my);
            drawing.x = nx; drawing.y = ny; drawing.mx = mx; drawing.my = my;
            return 1;
        }
        dn.lx = x; dn.ly = y;
        if (abs(x - dn.x) + abs(y - dn.y) > (report.valid ? 12 : 4)) dn.moved = 1;
        if (dn.map && dn.moved) { map_pan_move((float)(x - dn.x), (float)(y - dn.y)); return 1; }
        if (dn.opt && dn.moved) { int s = dn.optS - (y - dn.y); optScroll = s < 0 ? 0 : s > optMax ? optMax : s; return 1; }
        return 0;
    }
    if (dn.on) {
        dn.on = 0;
        if (dn.map) map_pan_end();
        if (drawing.on) {
            sigCurve(drawing.mx, drawing.my, drawing.x, drawing.y, drawing.x, drawing.y);
            sigCurve(drawing.x, drawing.y, (float)dn.lx, (float)dn.ly, (float)dn.lx, (float)dn.ly);
            drawing.on = 0;
            return 1;
        }
        if (dn.moved) return 0;
        for (int i = nbtn - 1; i >= 0; i--) {
            const Btn *b = &buttons[i];
            if (dn.lx >= b->x && dn.lx < b->x + b->w && dn.ly >= b->y && dn.ly < b->y + b->h) { tapped(b); return 1; }
        }
        if (mapBox.w && dn.lx >= mapBox.x && dn.lx < mapBox.x + mapBox.w && dn.ly >= mapBox.y && dn.ly < mapBox.y + mapBox.h) { map_zoom_next(); return 1; }
    }
    return 0;
}

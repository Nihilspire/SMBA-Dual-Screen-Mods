/* menuscr.c - the launcher, pause menu and settings on the touch screen: assets/menu/menu.js in C, at 320x240. D-pad and A / B or the
 * stylus. Android's SCREENS, TOUCH PAD and 3D VIEW pages have nothing to act on here, and the SYSTEM page gains the 3DS's own settings
 * (FRAME SKIP and the timing line, as Shantae Dual 3DS has). */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include "dual3ds.h"

#define W LINK_W
#define H LINK_H
static const char *const LV[20] = { "1-1", "1-2", "1-3", "2-1", "2-2", "2-3", "3-1", "3-2", "3-3", "4-1", "4-2", "4-3", "5-1", "5-2", "5-3", "6-1", "6-2", "6-3", "7-1", "7-2" };
enum { P_MAIN, P_SAVE, P_LOAD, P_SETTINGS, P_DISPLAY, P_CONTROLS, P_SOUND, P_GAMEPLAY, P_SYSTEM, P_DEV, P_BUTTONS };
enum { R_RESUME, R_GO, R_RESET, R_QUIT, R_CONT, R_START, R_EXIT, R_SLOT, R_BACK, R_SHAPE, R_INTEGER, R_SMOOTH, R_COLOR, R_BEZEL, R_STATS, R_AUDIO, R_MUSIC, R_OC, R_SKIPL, R_ROMMENU,
       R_REKIT, R_FSKIP, R_THREAD, R_CLOUDS, R_CODEH, R_CODEL, R_DEVACT, R_WARPL, R_WARP, R_TIME, R_BIND, R_BINDRESET, R_NOTE, R_ROMINFO };
typedef struct { char label[38], value[24]; short act, arg, icon; uint8_t note, head; } Row;
static Row rows[32]; static int nrows;
static int page, sel, scrollPos, tick, pauseMode, toastT, waiting = -1, devTime = 30, warpTo, big;
static char toastMsg[40];
static struct { char q[36]; int act, arg, sel; } confirm; static int confirmOn;
static struct { int page, sel; } stack[8]; static int nstack;
typedef struct { short x, y, w, h, row; } Hit;
static Hit hit[40]; static int nhit, dragY, dragOn; static int redraw = 1;
static const uint32_t C_white = 0xF8F8F8, C_red = 0xE03828, C_yellow = 0xF8C800, C_sky = 0x88D0F8, C_navy = 0x283870;
static uint32_t C_cream = 0xFBF4DC, C_parch = 0xEFE0B8, C_orange = 0xC86410, C_brown = 0x5A3818, C_dred = 0x902010;
static const uint32_t BAR = 0xF4D27A, BAR_EDGE = 0xB8862E, INK = 0x4A2C12, INK_SEL = 0x2A1404, TITLE = 0xA82A1A;

static int useKit(void) { return K.ok && pf.romMenu; }
static void kitColors(void) {
    if (useKit()) { C_cream = K.cream; C_parch = K.parch; C_orange = K.orange; C_dred = K.dark; }
    else { C_cream = 0xFBF4DC; C_parch = 0xEFE0B8; C_orange = 0xC86410; C_dred = 0x902010; }
}
static void opt(const char *l, const char *v, int act) {
    Row *r = &rows[nrows++]; memset(r, 0, sizeof *r); r->icon = IC_NONE;
    snprintf(r->label, sizeof r->label, "%s", l); if (v) snprintf(r->value, sizeof r->value, "%s", v); r->act = (short)act;
}
static void optA(const char *l, const char *v, int act, int arg) { opt(l, v, act); rows[nrows - 1].arg = (short)arg; }
static void sub(const char *l, int p) { optA(l, ">", R_GO, p); }
static void note(const char *t) { opt(t, NULL, R_NOTE); rows[nrows - 1].note = 1; rows[nrows - 1].head = 1; }
#define ONOFF(v) ((v) ? "ON" : "OFF")
static void slotTime(char *o, size_t n, uint32_t t) {
    if (!t) { snprintf(o, n, "- EMPTY -"); return; }
    time_t tt = t; struct tm *m = gmtime(&tt);
    snprintf(o, n, "%d/%d %02d:%02d", m->tm_mon + 1, m->tm_mday, m->tm_hour, m->tm_min);
}
static void build(void) {
    char v[32];
    nrows = 0;
    switch (page) {
    case P_MAIN:
        if (pauseMode) {
            opt("RESUME", NULL, R_RESUME); optA("SAVE STATE", NULL, R_GO, P_SAVE); optA("LOAD STATE", NULL, R_GO, P_LOAD); optA("SETTINGS", NULL, R_GO, P_SETTINGS);
            opt("RESET", NULL, R_RESET); opt("QUIT TO TITLE", NULL, R_QUIT);
        } else {
            if (app_have_auto()) opt("CONTINUE", NULL, R_CONT);
            opt(app_have_auto() ? "NEW GAME" : "START", NULL, R_START);
            optA("LOAD STATE", NULL, R_GO, P_LOAD); optA("SETTINGS", NULL, R_GO, P_SETTINGS); opt("EXIT", NULL, R_EXIT);
        }
        break;
    case P_SAVE: case P_LOAD:
        for (int n = 1; n <= APP_SLOTS; n++) { snprintf(v, sizeof v, "SLOT %d", n); char t[24]; slotTime(t, sizeof t, app_slot(n)->time); optA(v, t, R_SLOT, n); }
        opt("BACK", NULL, R_BACK);
        break;
    case P_SETTINGS:
        sub("DISPLAY", P_DISPLAY); sub("CONTROLS", P_CONTROLS); sub("SOUND", P_SOUND); sub("GAMEPLAY", P_GAMEPLAY); sub("SYSTEM", P_SYSTEM); opt("BACK", NULL, R_BACK);
        break;
    case P_DISPLAY:
        opt("SHAPE", pf.stretch ? "STRETCH" : "3:2", R_SHAPE); opt("INTEGER SCALE", ONOFF(pf.integer), R_INTEGER); opt("SMOOTH SCALING", ONOFF(pf.smooth), R_SMOOTH);
        opt("GBA LCD COLORS", ONOFF(pf.colorfix), R_COLOR); opt("BORDERS", ONOFF(pf.bezels), R_BEZEL); opt("FAR CLOUDS", ONOFF(!pf.noClouds), R_CLOUDS); opt("SHOW FPS", ONOFF(pf.stats), R_STATS);
        opt("BACK", NULL, R_BACK);
        break;
    case P_CONTROLS:
        sub("BUTTONS", P_BUTTONS);
        note("D-PAD AND CIRCLE PAD MOVE"); note("SELECT+START: THIS MENU"); note("L+X SAVE, L+Y LOAD");
        opt("BACK", NULL, R_BACK);
        break;
    case P_SOUND:
        opt("GAME SOUND", ONOFF(pf.audio), R_AUDIO); opt("MENU MUSIC", ONOFF(pf.music), R_MUSIC); opt("BACK", NULL, R_BACK);
        break;
    case P_GAMEPLAY:
        opt("NO SLOWDOWN", ONOFF(pf.overclock), R_OC);
        if (pf.devUnlock) sub("PLAYTEST CODES", P_DEV);
        opt("BACK", NULL, R_BACK);
        break;
    case P_SYSTEM:
        note(app_rom_line());
        opt("SKIP LAUNCHER", ONOFF(pf.skipLauncher), R_SKIPL);
        if (strcmp(app_kit_state(), "working")) opt("MENU GRAPHICS", !pf.romMenu ? "DRAWN" : !strcmp(app_kit_state(), "ready") ? "FROM ROM" : "FROM ROM*", R_ROMMENU);
        if (!pauseMode) opt("REBUILD MENU GRAPHICS", "GO", R_REKIT);
        opt("FRAME SKIP", ONOFF(pf.frameskip), R_FSKIP);
        opt("DRAW ON 3RD CORE", app_core2() ? ONOFF(pf.thread) : "REFUSED", R_THREAD);
        if (*app_timing()) { note(app_timing()); note(app_timing2()); }
        snprintf(v, sizeof v, "SMA DUAL 3DS %s", VERSION_3DS); note(v);
        note("EMULATION: MGBA (MPL 2.0)");
        note("MPL TEXT: SEE THE README");
        opt("BACK", NULL, R_BACK);
        break;
    case P_DEV:
        note("CHEATS FOR TESTING");
        opt("INFINITE LIFE", ONOFF(pf.codeHealth), R_CODEH); rows[nrows - 1].icon = IC_heartFull;
        opt("INFINITE LIVES", ONOFF(pf.codeLives), R_CODEL); rows[nrows - 1].icon = IC_oneup;
        optA("REFILL LIFE", "GO", R_DEVACT, 0); rows[nrows - 1].icon = IC_heart;
        optA("STARMAN", "GO", R_DEVACT, 1); rows[nrows - 1].icon = IC_star;
        optA("STOPWATCH", "GO", R_DEVACT, 2); rows[nrows - 1].icon = IC_watch;
        optA("4 CHERRIES", "GO", R_DEVACT, 3); rows[nrows - 1].icon = IC_cherry;
        optA("+5 LIVES", "GO", R_DEVACT, 4); rows[nrows - 1].icon = IC_oneup;
        optA("ENDING CARD ON/OFF", "GO", R_DEVACT, 5);
        opt("WARP LEVEL", LV[warpTo], R_WARPL);
        opt("WARP", "GO", R_WARP);
        snprintf(v, sizeof v, "%d:%02d", devTime / 60, devTime % 60); opt("SET PLAY TIME", v, R_TIME);
        opt("BACK", NULL, R_BACK);
        break;
    case P_BUTTONS:
        for (int a = 0; a < ACT_N; a++) {
            char keys[32] = ""; size_t o = 0;
            for (int k = 0; k < KEY3_N; k++) if (pf.key[k] == a) o += (size_t)snprintf(keys + o, sizeof keys - o, "%s%s", o ? " " : "", KEY3_NAME[k]);
            optA(ACT_NAME[a], waiting == a ? "PRESS A BUTTON" : o ? keys : "-", R_BIND, a);
        }
        opt("RESET TO DEFAULT", NULL, R_BINDRESET);
        opt("BACK", NULL, R_BACK);
        break;
    }
    int first = 0; while (first < nrows && rows[first].head) first++;
    if (sel < first || sel >= nrows || rows[sel].head) sel = first;
    redraw = 1;
}
static void go(int p) { if (nstack < 8) { stack[nstack].page = page; stack[nstack].sel = sel; nstack++; } page = p; sel = 0; scrollPos = 0; build(); }
static void back(void) {
    if (waiting >= 0) { waiting = -1; build(); return; }
    if (confirmOn) { confirmOn = 0; redraw = 1; return; }
    if (!nstack) { if (pauseMode) app_resume(); return; }
    nstack--; page = stack[nstack].page; sel = stack[nstack].sel; build();
}
static void ask(const char *q, int act, int arg) { snprintf(confirm.q, sizeof confirm.q, "%s", q); confirm.act = act; confirm.arg = arg; confirm.sel = 1; confirmOn = 1; redraw = 1; }
void menu_toast(const char *m) { snprintf(toastMsg, sizeof toastMsg, "%s", m); toastT = tick + 40; redraw = 1; }
static void doSlot(int n) {
    char t[32];
    if (page == P_SAVE) {
        int ok = app_save_slot(n);
        if (ok) snprintf(t, sizeof t, "SAVED TO SLOT %d", n); else strcpy(t, "SAVE FAILED");
        build(); menu_toast(t);
    } else if (app_slot(n)->time) { if (!app_load_slot(n)) menu_toast("LOAD FAILED"); }
}
static void confirmed(int act, int arg) {
    switch (act) {
    case R_RESET: app_reset(); break;
    case R_START: app_start(0); break;
    case R_SLOT: doSlot(arg); break;
    }
}
static void act(const Row *r, int d) {
    switch (r->act) {
    case R_RESUME: app_resume(); return;
    case R_GO: go(r->arg); return;
    case R_RESET: ask("RESET THE GAME?", R_RESET, 0); return;
    case R_QUIT: app_quit(); return;
    case R_CONT: app_start(1); return;
    case R_START: if (app_have_auto()) ask("START OVER? CONTINUE DATA STAYS", R_START, 0); else app_start(0); return;
    case R_EXIT: app_exit(); return;
    case R_SLOT:
        if (page == P_SAVE && app_slot(r->arg)->time) { char q[32]; snprintf(q, sizeof q, "OVERWRITE SLOT %d?", r->arg); ask(q, R_SLOT, r->arg); }
        else doSlot(r->arg);
        return;
    case R_BACK: back(); return;
    case R_SHAPE: pf.stretch = !pf.stretch; break;
    case R_INTEGER: pf.integer = !pf.integer; break;
    case R_SMOOTH: pf.smooth = !pf.smooth; break;
    case R_COLOR: pf.colorfix = !pf.colorfix; break;
    case R_BEZEL: pf.bezels = !pf.bezels; break;
    case R_STATS: pf.stats = !pf.stats; break;
    case R_CLOUDS: pf.noClouds = !pf.noClouds; break;
    case R_AUDIO: pf.audio = !pf.audio; break;
    case R_MUSIC: pf.music = !pf.music; break;
    case R_OC: pf.overclock = !pf.overclock; break;
    case R_SKIPL: pf.skipLauncher = !pf.skipLauncher; break;
    case R_ROMMENU: pf.romMenu = !pf.romMenu; kitColors(); break;
    case R_REKIT: app_rekit(); menu_toast("CAPTURING FROM THE ROM"); kitColors(); break;
    case R_FSKIP: pf.frameskip = !pf.frameskip; break;
    case R_THREAD: if (app_core2()) { pf.thread = !pf.thread; if (pauseMode) menu_toast("AT THE NEXT GAME START"); } break;
    case R_CODEH: pf.codeHealth = !pf.codeHealth; break;
    case R_CODEL: pf.codeLives = !pf.codeLives; break;
    case R_DEVACT: { static const char *const W_[6] = { "refill", "star", "stopwatch", "cherries", "lives", "report" }; menu_toast(app_dev(W_[r->arg])); return; }
    case R_WARPL: warpTo = (warpTo + (d < 0 ? 19 : 1)) % 20; build(); return;
    case R_WARP: { char w[12]; snprintf(w, sizeof w, "warp:%d", warpTo); menu_toast(app_dev(w)); return; }
    case R_TIME: {
        static const short L[6] = { 30, 60, 90, 120, 180, 600 }; int i = 0;
        for (int k = 0; k < 6; k++) if (L[k] == devTime) i = k;
        devTime = L[(i + (d < 0 ? 5 : 1)) % 6];
        char w[16]; snprintf(w, sizeof w, "time:%d", devTime); menu_toast(app_dev(w)); build(); return;
    }
    case R_BIND: waiting = r->arg; build(); return;
    case R_BINDRESET: { Prefs keep = pf; prefs_defaults(); memcpy(keep.key, pf.key, sizeof keep.key); pf = keep; menu_toast("DEFAULTS RESTORED"); break; }
    default: return;
    }
    app_settings_changed();
    build();
}
int menu_waiting(void) { return waiting; }
void menu_bound(int key3) { if (waiting >= 0) { binds_assign(key3, waiting); waiting = -1; app_settings_changed(); build(); } }

/* Konami code on the launcher's main screen shows or hides the playtest codes. Hidden, they are also switched off. */
static const uint8_t KONAMI[10] = { MP_UP, MP_UP, MP_DOWN, MP_DOWN, MP_LEFT, MP_RIGHT, MP_LEFT, MP_RIGHT, MP_BACK, MP_OK };
static int kon;
static int konami(int k) {
    if (page != P_MAIN || pauseMode || confirmOn) { kon = 0; return 0; }
    if (k == KONAMI[kon]) kon++; else kon = k == MP_UP ? (kon == 2 ? 2 : 1) : 0;
    if (kon < 10) return 0;
    kon = 0;
    pf.devUnlock = !pf.devUnlock;
    if (!pf.devUnlock) { pf.codeHealth = 0; pf.codeLives = 0; }
    app_settings_changed();
    menu_toast(pf.devUnlock ? "PLAYTEST CODES UNLOCKED" : "PLAYTEST CODES HIDDEN");
    return 1;
}
void menu_pad(int k) {
    redraw = 1;
    if (konami(k)) { build(); return; }                  /* the final A doesn't also pick a menu item */
    if (confirmOn) {
        if (k == MP_LEFT || k == MP_RIGHT || k == MP_UP || k == MP_DOWN) confirm.sel ^= 1;
        else if (k == MP_OK) { confirmOn = 0; if (confirm.sel == 0) confirmed(confirm.act, confirm.arg); }
        else if (k == MP_BACK) confirmOn = 0;
        return;
    }
    if (waiting >= 0) { if (k == MP_BACK) back(); return; }
    if ((page == P_SAVE || page == P_LOAD) && k <= MP_RIGHT) {
        int n = nrows - 1;                                /* slots, then BACK */
        if (sel >= n) { if (k == MP_UP) sel = n - 1; else if (k == MP_DOWN) sel = 0; }
        else if (k == MP_LEFT || k == MP_RIGHT) sel = (sel & ~1) | ((sel & 1) ^ 1);
        else if (k == MP_DOWN) sel = sel + 2 >= n ? n : sel + 2;
        else sel = sel - 2 < 0 ? n : sel - 2;
    } else if (k == MP_UP || k == MP_DOWN) {
        int d = k == MP_UP ? -1 : 1, i = sel;
        do { i = (i + d + nrows) % nrows; } while (rows[i].head && i != sel);
        sel = i;
    } else if (k == MP_OK) { if (sel < nrows) { Row r = rows[sel]; act(&r, 1); } return; }
    else if (k == MP_LEFT || k == MP_RIGHT) {
        if (sel < nrows && rows[sel].value[0] && strcmp(rows[sel].value, ">") && strcmp(rows[sel].value, "GO") && page != P_MAIN) { Row r = rows[sel]; act(&r, k == MP_LEFT ? -1 : 1); }
        return;
    } else if (k == MP_BACK) back();
}
static void addHit(int x, int y, int w, int h, int row) { if (nhit < 40) { Hit *b = &hit[nhit++]; b->x = (short)x; b->y = (short)y; b->w = (short)w; b->h = (short)h; b->row = (short)row; } }
enum { HIT_NONE = -1, HIT_BACK = -2, HIT_YES = -3, HIT_NO = -4, HIT_BLOCK = -5 };
void menu_touch(int d, int x, int y) {
    static int was;
    if (d && !was) {
        dragOn = 1; dragY = y; redraw = 1;
        for (int i = nhit - 1; i >= 0; i--) {
            const Hit *b = &hit[i];
            if (x >= b->x && x < b->x + b->w && y >= b->y && y < b->y + b->h) {
                if (b->row == HIT_BACK) { sel = nrows - 1; back(); }
                else if (b->row == HIT_YES) { confirmOn = 0; confirmed(confirm.act, confirm.arg); }
                else if (b->row == HIT_NO) confirmOn = 0;
                else if (b->row >= 0) { sel = b->row; Row r = rows[sel]; act(&r, x - b->x < b->w / 3 ? -1 : 1); }
                break;
            }
        }
    } else if (d && dragOn) {
        int dy = y - dragY;
        if (abs(dy) > 12) { scrollPos = scrollPos - (dy > 0 ? 1 : -1); if (scrollPos < 0) scrollPos = 0; dragY = y; redraw = 1; }
    } else if (!d) dragOn = 0;
    was = d;
}
void menu_init(const uint8_t *r) { (void)r; }
void menu_show(int pause) { pauseMode = pause; page = P_MAIN; sel = 0; scrollPos = 0; confirmOn = 0; nstack = 0; waiting = -1; kitColors(); build(); }
int menu_tick(void) {
    tick++;
    if (toastT && toastT == tick) redraw = 1;
    if (page == P_SYSTEM && tick % 20 == 0) build();     /* the timing line, the capture's state */
    int r = redraw; redraw = 0; return r;
}

/* ------------------------------------------------------------------ drawing */
static void card(int x, int y, int w, int h) {
    if (useKit()) {                                       /* the pause panel, grown to whole 16px pieces around the same centre */
        int kw = (w + 8 + 8) / 16 * 16, kh = (h + 4 + 8) / 16 * 16;
        blit(kit_card(kw, kh), x + w / 2 - kw / 2, y + h / 2 - kh / 2); return;
    }
    rect(x - 2, y - 2, w + 4, h + 4, C_brown); rect(x - 1, y - 1, w + 2, h + 2, C_orange); rect(x, y, w, h, C_cream);
    rect(x, y + h - 2, w, 2, C_parch); rect(x + w - 2, y, 2, h, C_parch);
}
static void cursor(int x, int y) { if (useKit()) blit(K.cursor, x - 1, y); else text(">", x, y, C_orange); }
static void plate(int x, int y, int w) { rectA(x + 1, y - 2, w - 2, 12, 0x1E1206, 158); rectA(x, y - 1, w, 10, 0x1E1206, 158); }
static void rbox(int x, int y, int w, int h, uint32_t col, uint32_t edge) {
    rect(x + 1, y - 1, w - 2, h + 2, edge); rect(x - 1, y + 1, w + 2, h - 2, edge); rect(x, y, w, h, edge);
    rect(x + 1, y, w - 2, h, col); rect(x, y + 1, w, h - 2, col);
}
static void value(const char *v, int rx, int y, int wait) {
    if (wait) { textR(v, rx, y, (tick >> 3) % 2 ? C_orange : C_dred); return; }
    if (!strcmp(v, "ON")) { textR(v, rx, y, 0x24742A); return; }
    if (!strcmp(v, "OFF")) { textR(v, rx, y, 0x76664E); return; }
    if (!strcmp(v, ">")) { textR(">", rx, y, 0x8A6A40); return; }
    textR(v, rx, y, 0x1D4F9A);
}
static uint32_t catColor(const char *l) {
    if (!strcmp(l, "DISPLAY")) return 0x5A82C0;
    if (!strcmp(l, "CONTROLS")) return 0xC06050;
    if (!strcmp(l, "SOUND")) return 0xC8A040;
    if (!strcmp(l, "GAMEPLAY")) return 0xC88040;
    if (!strcmp(l, "SYSTEM")) return 0x8A8A90;
    return 0;
}
static const char *pageTitle(void) {
    switch (page) {
    case P_MAIN: return "PAUSE"; case P_SAVE: return "SAVE STATE"; case P_LOAD: return "LOAD STATE"; case P_SETTINGS: return "SETTINGS"; case P_DISPLAY: return "DISPLAY";
    case P_CONTROLS: return "CONTROLS"; case P_BUTTONS: return "BUTTONS"; case P_SOUND: return "SOUND"; case P_GAMEPLAY: return "GAMEPLAY"; case P_SYSTEM: return "SYSTEM"; case P_DEV: return "PLAYTEST CODES";
    default: return "";
    }
}
static void slotGrid(int y) {     /* SAVE / LOAD: 2x2 cards with the slot's picture, then BACK. Every card is one big tap target. */
    int n = nrows - 1, gap = 4, backH = 14, th = THUMB_H - 8, tw = THUMB_W - 12, cw = 146, ch = th + 13, x0 = (W - (cw * 2 + gap)) / 2;
    for (int i = 0; i < n; i++) {
        Row *r = &rows[i]; const AppSlot *sl = app_slot(r->arg);
        int on = i == sel, x = x0 + (i % 2) * (cw + gap), yy = y + (i / 2) * (ch + gap);
        rect(x, yy, cw, ch, on ? BAR : C_parch);
        uint32_t e = on ? C_orange : C_brown;
        rect(x, yy, cw, 1, e); rect(x, yy + ch - 1, cw, 1, e); rect(x, yy, 1, ch, e); rect(x + cw - 1, yy, 1, ch, e);
        if (on) { rect(x + 1, yy + 1, cw - 2, 1, e); rect(x + 1, yy + ch - 2, cw - 2, 1, e); rect(x + 1, yy, 1, ch, e); rect(x + cw - 2, yy, 1, ch, e); }
        int tx = x + (cw - tw) / 2, ty = yy + 3;
        if (sl->time && sl->thumb) blitScaled(sl->thumb, THUMB_W, THUMB_H, tx, ty, tw, th);
        else { rect(tx, ty, tw, th, C_sky); textC(sl->time ? "..." : "EMPTY", x + cw / 2, ty + th / 2 - 4, C_white); }
        char s[4]; snprintf(s, sizeof s, "%d", r->arg); text(s, x + 4, yy + ch - 10, on ? INK_SEL : INK);
        if (sl->time) textR(r->value, x + cw - 4, yy + ch - 10, on ? INK_SEL : 0x1D4F9A);
        addHit(x, yy, cw, ch, i);
    }
    int by = y + 2 * (ch + gap) + 1, bw = 80, bx = (W - bw) / 2, bon = sel == n;
    rect(bx, by, bw, backH, bon ? BAR : C_parch);
    rect(bx, by, bw, 1, bon ? C_orange : C_brown); rect(bx, by + backH - 1, bw, 1, bon ? C_orange : C_brown);
    textC("BACK", W / 2, by + 4, bon ? INK_SEL : INK);
    addHit(bx, by, bw, backH, HIT_BACK);
}
static void drawConfirm(void) {
    addHit(0, 0, W, H, HIT_BLOCK);
    int qw = (int)strlen(confirm.q) * 8 + 16, w = qw < 120 ? 120 : qw > W - 16 ? W - 16 : qw, h = 44, x = (W - w) / 2, y = (H - h) / 2;
    card(x, y, w, h);
    textC(confirm.q, W / 2, y + 8, C_dred);
    int yes = confirm.sel == 0; char t[8];
    snprintf(t, sizeof t, "%sYES", yes ? ">" : " "); text(t, x + w / 2 - 52, y + 26, yes ? C_orange : C_brown);
    snprintf(t, sizeof t, "%sNO", !yes ? ">" : " "); text(t, x + w / 2 + 12, y + 26, !yes ? C_orange : C_brown);
    addHit(x, y + 20, w / 2, 20, HIT_YES); addHit(x + w / 2, y + 20, w / 2, 20, HIT_NO);
}
void menu_draw(uint16_t *fb) {
    cv_target(fb, W, H);
    font_solid = 0;
    nhit = 0;
    int launcher = page == P_MAIN && !pauseMode;
    memcpy(fb, kit_backdrop(320), W * H * 2);
    int cx = W / 2, y;
    big = page == P_MAIN && H - 56 >= nrows * 18;
    int cardW = launcher ? (big ? 200 : 150) : 280, cardX = cx - cardW / 2;
    int cardY = launcher ? 10 : 6, cardH = H - cardY - (launcher ? 34 : 8), subRow = 15;
    if (page != P_SAVE && page != P_LOAD) {
        int need = (launcher ? 12 : 30) + nrows * (page == P_MAIN ? (big ? 18 : 13) : subRow) + 10;
        if (need < cardH) { cardY = launcher ? (H - 24 - need) / 2 : (H - need) / 2; cardH = need; }
    }
    card(cardX, cardY, cardW, cardH);
    y = cardY + (launcher ? 3 : 6);
    if (launcher) {
        const char *line = app_rom_line();
        if (!strcmp(app_kit_state(), "working")) line = "PREPARING MENU GRAPHICS";
        int lw = (int)strlen(line) * 8 + 8;
        plate(cx - lw / 2, H - 22, lw); textC(line, cx, H - 22, C_white);
        char v[24]; snprintf(v, sizeof v, "V%s", VERSION_3DS);
        plate(1, H - 10, (int)strlen(v) * 8 + 6); text(v, 4, H - 10, C_white);
    } else {
        const char *title = pageTitle();
        int tsc = (int)strlen(title) * 16 <= cardW - 8 ? 2 : 1;
        y = cardY + 6;
        textCS(title, cx, y + (tsc == 1 ? 4 : 0), TITLE, tsc);
        rect(cardX + 10, y + 19, cardW - 20, 1, 0xD8B878);
        y += 24;
    }
    if (page == P_SAVE || page == P_LOAD) slotGrid(y);
    else {
        int icons = 0;
        if (useKit()) for (int i = 0; i < nrows; i++) if (rows[i].icon != IC_NONE && K.icons[rows[i].icon]) icons = 1;
        int centered = page == P_MAIN, rowH = big ? 18 : launcher ? 12 : centered ? 13 : icons ? 18 : subRow, ts = big ? 2 : 1;
        int bottom = cardY + cardH - 4, visible = (bottom - y) / rowH; if (visible < 1) visible = 1;
        if (centered || page == P_SETTINGS) { int yy = y + (bottom - y - nrows * rowH) / 2; if (yy > y) y = yy; }
        if (sel < scrollPos) scrollPos = sel;
        if (sel >= scrollPos + visible) scrollPos = sel - visible + 1;
        if (scrollPos > nrows - visible) scrollPos = nrows - visible;
        if (scrollPos < 0) scrollPos = 0;
        int listW = cardW - 12, x0 = cardX + 6;
        for (int i = scrollPos; i < nrows && i < scrollPos + visible; i++) {
            Row *r = &rows[i]; int yy = y + (i - scrollPos) * rowH, on = i == sel;
            if (r->note) { textC(r->label, cx, yy + 2, 0x75644A); continue; }
            if (centered) {
                if (on) rbox(x0, yy - 1, listW, rowH - 2, BAR, BAR_EDGE);
                textCS(r->label, cx, yy + (big ? 1 : 2), on ? INK_SEL : INK, ts);
                if (on) cursor(x0 + 3, yy + (rowH - 10) / 2);
            } else {
                int ry = yy + (rowH - 9) / 2;
                if (on) rbox(x0 - 2, yy, listW + 4, rowH - 1, BAR, BAR_EDGE);
                uint32_t chip = page == P_SETTINGS ? catColor(r->label) : 0;
                int ix = 0;
                if (icons && r->icon != IC_NONE && K.icons[r->icon]) { blit(K.icons[r->icon], x0 + 8, yy + 1); ix = 1; }
                if (chip) rbox(x0 + 8, ry, 7, 7, chip, shade24(chip, 0.7f));
                text(r->label, x0 + (ix || icons ? 27 : chip ? 19 : 8), ry, on ? INK_SEL : INK);
                if (on) cursor(x0, ry);
                if (r->value[0]) {
                    char v[24]; int maxc = (listW - (int)strlen(r->label) * 8 - (icons ? 35 : 16)) / 8;
                    snprintf(v, sizeof v, "%s", r->value);
                    if ((int)strlen(v) > maxc) v[maxc < 1 ? 1 : maxc] = 0;
                    value(v, x0 + listW, ry, waiting == r->arg && r->act == R_BIND);
                }
            }
            addHit(x0 - 4, yy, listW + 8, rowH, i);
        }
        if (scrollPos > 0) textC("^", cx, y - 9, 0x75644A);
        if (scrollPos + visible < nrows) textC("v", cx, bottom - 6, 0x75644A);
    }
    if (confirmOn) drawConfirm();
    if (toastT > tick) {
        int tw = (int)strlen(toastMsg) * 8 + 12;
        rect(cx - tw / 2, H - 30, tw, 14, C_navy); textC(toastMsg, cx, H - 27, C_white);
    }
    (void)C_red; (void)C_yellow;
}

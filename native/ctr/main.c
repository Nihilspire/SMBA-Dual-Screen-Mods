/* main.c - Super Mario Advance Dual 3DS (alpha): the patched mGBA core (../gbacore.c, the same as the Android app's) on the top screen,
 * the Link screen (linkscr.c) or the menus (menuscr.c) on the bottom touch screen. libctru only: both screens are RGB565 framebuffers.
 *
 * The player's ROM (Super Mario Advance (USA, Europe), CRC32 1E4C6D6A) goes in the same folder as the .3dsx, or in sdmc:/3ds/sma-dual
 * (a .cia looks there, then in sdmc:/3ds): any .gba that is that ROM. The battery save, the states, the settings and the first-start
 * capture go in sdmc:/3ds/sma-dual. Nothing from the game ships in the .3dsx or the .cia. Sound needs sdmc:/3ds/dspfirm.cdc.
 *
 * Buttons, all but the D-pad changeable in SETTINGS > CONTROLS: A and B as on the GBA, X / Y turbo A / B, R held fast-forward, ZL / ZR
 * (New 3DS) quick save / load. Always: Circle Pad as the D-pad, SELECT+START the pause menu, L+X / L+Y quick save / load. */
#include <3ds.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <strings.h>
#include <stdarg.h>
#include <dirent.h>
#include <time.h>
#include <sys/stat.h>
#include "dual3ds.h"
#include "sma.h"
#include "topgpu.h"
#include "pace.h"
#ifdef CTR_TEST
#include "test_script.h"     /* written by test/build_test.sh: #define TEST_SCRIPT {...}, {...} and maybe TEST_STATE, TEST_QUIT */
#endif

#define DATA_DIR "sdmc:/3ds/sma-dual"
#define SCREEN_HZ 59.83                                            /* the 3DS screen's refresh */
#define BTN(x) (1u << (x))                                         /* libretro joypad ids */
enum { J_B = 0, J_SELECT = 2, J_START = 3, J_UP = 4, J_DOWN = 5, J_LEFT = 6, J_RIGHT = 7, J_A = 8, J_L = 10, J_R = 11 };
static const u32 KEY3[KEY3_N] = { KEY_DUP, KEY_DDOWN, KEY_DLEFT, KEY_DRIGHT, KEY_A, KEY_B, KEY_X, KEY_Y, KEY_L, KEY_R, KEY_ZL, KEY_ZR, KEY_START, KEY_SELECT };
const char *const KEY3_NAME[KEY3_N] = { "UP", "DOWN", "LEFT", "RIGHT", "A", "B", "X", "Y", "L", "R", "ZL", "ZR", "START", "SELECT" };
const char *const ACT_NAME[ACT_N] = { "UP", "DOWN", "LEFT", "RIGHT", "B", "A", "SELECT", "START", "TURBO B", "TURBO A", "MENU", "FAST", "SAVE", "LOAD", "L", "R" };

Prefs pf;
static u8 *rom; static size_t romLen; static int romOk;
static char ROMDIR[256], ROMPATH[320], romName[128];
static bool n3ds; static int onEmulator, core2ok, threadedNow;
enum { ST_LAUNCHER, ST_GAME, ST_PAUSE };
static int state = ST_LAUNCHER, started, exitFlag, prefsDirty, devUsed, kitState, topDirty, touchBlock;   /* kitState: 0 none, 1 ready, 2 failed, 3 working */
static uint16_t menuBuf[LINK_W * LINK_H];
static uint16_t border[400 * 240];

void gba_log(const char *m) { (void)m; }
FS_Archive sdmcArchive;      /* mGBA's config code (built for the 3DS now, for its threads) expects it, and never reads a file here */

/* ------------------------------------------------------------------ the log: read it after a run on the real machine */
static FILE *logf; static int logLines;
static void logp(const char *fmt, ...) {
    if (!logf || logLines > 2000) return;
    va_list ap; va_start(ap, fmt); vfprintf(logf, fmt, ap); va_end(ap); fputc('\n', logf); fflush(logf); logLines++;
}

/* ------------------------------------------------------------------ settings */
void prefs_defaults(void) {
    memset(&pf, 0, sizeof pf);
    pf.bezels = 1; pf.audio = 1; pf.music = 1; pf.overclock = 1; pf.romMenu = 1; pf.xray = 1; pf.markers = 1; pf.zoom = 1; pf.thread = 1;
    static const int8_t K[KEY3_N] = { ACT_UP, ACT_DOWN, ACT_LEFT, ACT_RIGHT, ACT_A, ACT_B, ACT_TURBO_A, ACT_TURBO_B, ACT_L, ACT_FAST, ACT_SAVE, ACT_LOAD, ACT_START, ACT_SELECT };
    memcpy(pf.key, K, sizeof K);
}
/* a button already used swaps over */
void binds_assign(int key, int action) {
    int prev = pf.key[key], old = -1;
    if (prev == action) return;
    for (int k = 0; k < KEY3_N; k++) if (pf.key[k] == action) { old = k; break; }
    if (prev >= 0 && old >= 0) pf.key[old] = (int8_t)prev;
    else for (int k = 0; k < KEY3_N; k++) if (pf.key[k] == action) pf.key[k] = -1;
    pf.key[key] = (int8_t)action;
}
static void prefsLoad(void) {
    char p[300]; snprintf(p, sizeof p, "%s/sma-dual.cfg", DATA_DIR);
    FILE *f = fopen(p, "rb"); if (!f) return;
    uint32_t h[2];
    if (fread(h, 4, 2, f) == 2 && h[0] == 0x50444D53) { Prefs t = pf; size_t n = h[1] < sizeof t ? h[1] : sizeof t; if (fread(&t, 1, n, f) == n) pf = t; }
    fclose(f);
}
static void prefsSave(void) {
    char p[300]; snprintf(p, sizeof p, "%s/sma-dual.cfg", DATA_DIR);
    FILE *f = fopen(p, "wb"); if (!f) return;
    uint32_t h[2] = { 0x50444D53, sizeof pf };
    fwrite(h, 4, 2, f); fwrite(&pf, 1, sizeof pf, f); fclose(f);
    prefsDirty = 0;
}
/* 804 MHz, the L2 cache and NO SLOWDOWN */
static void applySettings(void) {
    static int cc = -1, shape = -1;
    osSetSpeedupEnable(true);
    gba_set_overclock(pf.overclock);
    if (cc != pf.colorfix) { cc = pf.colorfix; gba_set_color_correction(cc); }   /* this makes the core reload its options */
    int sh = pf.stretch | pf.integer << 1 | pf.smooth << 2 | pf.bezels << 3;
    if (shape != sh) { shape = sh; topDirty = 2; }
}
void app_settings_changed(void) { prefsDirty = 1; applySettings(); }

/* ------------------------------------------------------------------ screens */
/* 3DS framebuffers are stored rotated: pixel (x, y) of a 400x240 / 320x240 screen at fb[x * 240 + 239 - y]. */
static int gx, gy, gw, gh;
static uint16_t *gameLin[3];                               /* the core's pictures, in linear memory for the GPU: 256 stride x 160 */
static int cloudsOff;
static void topLayout(void) {
    if (pf.stretch) { gx = 0; gy = 0; gw = 400; gh = 240; }
    else if (pf.integer) { gx = 80; gy = 40; gw = 240; gh = 160; }
    else { gx = 20; gy = 0; gw = 360; gh = 240; }
}
static void drawBottom(const uint16_t *link) {
    uint16_t *fb = (uint16_t *)gfxGetFramebuffer(GFX_BOTTOM, GFX_LEFT, NULL, NULL);
    for (int x = 0; x < LINK_W; x++) {
        const uint16_t *s = link + x; uint16_t *dst = fb + x * 240 + 239;
        for (int y = 0; y < LINK_H; y++) dst[-y] = s[y * LINK_W];
    }
}
/* The third core is the New 3DS's spare. A CIA's spec could refuse it, so find out before the core depends on it: mGBA doesn't check. */
static void noThread(void *a) { (void)a; }
static int canUseCore2(void) {
    Thread t = threadCreate(noThread, NULL, 0x1000, 0x18, 2, false);
    if (!t) return 0;
    threadJoin(t, U64_MAX); threadFree(t);
    return 1;
}
/* the launcher's top screen: the backdrop and the title's logo */
static void drawLauncherTop(void) {
    static uint16_t t[400 * 240];
    memcpy(t, kit_backdrop(400), sizeof t);
    cv_target(t, 400, 240);
    const Img *logo = link_logo();
    blit(logo, (400 - logo->w) / 2, 6);
    font_solid = 0;
    textCS("DUAL", 201, 135, 0x3A2008, 2); textCS("DUAL", 200, 134, 0xF8C800, 2);
    if (K.ok && K.coin) { blit(K.coin, 200 - 56, 134); blit(K.coin, 200 + 40, 134); }
    tg_border(t);
    TgFrame f; memset(&f, 0, sizeof f);
    tg_present(&f);
}
static void clearTop(void) {
    static uint16_t t[400 * 240];
    tg_border(t);
    TgFrame f; memset(&f, 0, sizeof f);
    tg_present(&f);
}

/* ------------------------------------------------------------------ sound (NDSP; silent when the DSP firmware is missing) */
#define NBUF 8
#define BUFSAMP 1024                          /* stereo frames per buffer, ~31 ms */
/* The game makes 65536 stereo samples a second, halved below. The screen runs at 59.83 against the GBA's 59.7275, so the channel plays
   that much faster, and the queue's depth trims it the rest of the way. */
#define SOUND_RATE (32768.0 * SCREEN_HZ / GBA_FPS)
static ndspWaveBuf wb[NBUF];
static int16_t *sbuf; static int sfill, scur, haveSound, soundHeld, queued;
static float rateNow;
static void soundInit(void) {
    if (R_FAILED(ndspInit())) { logp("no sound: ndspInit failed (dspfirm.cdc?)"); return; }
    ndspSetOutputMode(NDSP_OUTPUT_STEREO);
    ndspChnSetInterp(0, NDSP_INTERP_LINEAR);
    rateNow = (float)SOUND_RATE;
    ndspChnSetRate(0, rateNow);
    ndspChnSetFormat(0, NDSP_FORMAT_STEREO_PCM16);
    sbuf = (int16_t *)linearAlloc(NBUF * BUFSAMP * 4);
    if (!sbuf) { ndspExit(); return; }
    memset(wb, 0, sizeof wb);
    for (int i = 0; i < NBUF; i++) { wb[i].data_vaddr = sbuf + i * BUFSAMP * 2; wb[i].nsamples = BUFSAMP; wb[i].status = NDSP_WBUF_DONE; }
    haveSound = 1; soundHeld = 1;
    ndspChnSetPaused(0, true);
}
static void soundFlush(void) {
    if (!haveSound) return;
    ndspChnWaveBufClear(0);
    for (int i = 0; i < NBUF; i++) wb[i].status = NDSP_WBUF_DONE;
    scur = 0; sfill = 0; soundHeld = 1;
    ndspChnSetPaused(0, true);
}
static void soundPush(const int16_t *s, int frames) {
    if (!haveSound) return;
    for (int i = 0; i < frames; i++) {
        if (wb[scur].status != NDSP_WBUF_DONE && wb[scur].status != NDSP_WBUF_FREE) return;   /* the DSP is behind: drop */
        int16_t *d = (int16_t *)wb[scur].data_vaddr;
        d[sfill * 2] = s[i * 2]; d[sfill * 2 + 1] = s[i * 2 + 1];
        if (++sfill == BUFSAMP) {
            DSP_FlushDataCache(d, BUFSAMP * 4);
            ndspChnWaveBufAdd(0, &wb[scur]);
            scur = (scur + 1) % NBUF; sfill = 0;
            int q = 0;
            for (int k = 0; k < NBUF; k++) if (wb[k].status != NDSP_WBUF_DONE && wb[k].status != NDSP_WBUF_FREE) q++;
            queued = q;
            if (soundHeld && q >= 3) { soundHeld = 0; ndspChnSetPaused(0, false); }
            float want = (float)SOUND_RATE * (q > 4 ? 1.003f : q < 2 ? 0.997f : 1.0f);
            if (want != rateNow) { rateNow = want; ndspChnSetRate(0, want); }
        }
    }
}

/* ------------------------------------------------------------------ states: mGBA's state, then "SMAT" + the Dual tracker (as on Android) */
static AppSlot slots[APP_SLOTS]; static uint16_t thumbs[APP_SLOTS][THUMB_W * THUMB_H];
static int saveStateFile(const char *p) {
    size_t n = gba_state_size(); if (!n) return 0;
    void *b = malloc(n); int ok = b && gba_state_save(b, n);
    if (ok) {
        FILE *f = fopen(p, "wb"); ok = 0;
        if (f) { ok = fwrite(b, 1, n, f) == n; fwrite("SMAT", 1, 4, f); fwrite(sma_t, 1, sizeof sma_t, f); fclose(f); }
    }
    free(b);
    return ok;
}
static int loadStateFile(const char *p) {
    FILE *f = fopen(p, "rb"); if (!f) return 0;
    fseek(f, 0, SEEK_END); long n = ftell(f); fseek(f, 0, SEEK_SET);
    uint8_t *b = malloc((size_t)n); int ok = 0;
    if (b && fread(b, 1, (size_t)n, f) == (size_t)n) {
        size_t tn = 4 + sizeof sma_t;
        if ((size_t)n >= tn && !memcmp(b + n - tn, "SMAT", 4)) { ok = gba_state_load(b, (size_t)n - tn); if (ok) memcpy(sma_t, b + n - tn + 4, sizeof sma_t); }
        else { ok = gba_state_load(b, (size_t)n); if (ok) sma_track_clear(); }
    }
    free(b); fclose(f);
    return ok;
}
static void slotPath(char *p, size_t n, const char *ext, int slot) { snprintf(p, n, "%s/slot%d.%s", DATA_DIR, slot, ext); }
static void readSlots(void) {
    for (int i = 0; i < APP_SLOTS; i++) {
        char p[300]; slotPath(p, sizeof p, "th", i + 1);
        slots[i].time = 0; slots[i].thumb = NULL;
        FILE *f = fopen(p, "rb"); if (!f) continue;
        uint32_t h[2];
        if (fread(h, 4, 2, f) == 2 && h[0] == 0x31544D53 && fread(thumbs[i], 2, THUMB_W * THUMB_H, f) == THUMB_W * THUMB_H) { slots[i].time = h[1] ? h[1] : 1; slots[i].thumb = thumbs[i]; }
        fclose(f);
    }
}
const AppSlot *app_slot(int n) { return &slots[n < 1 ? 0 : n > APP_SLOTS ? APP_SLOTS - 1 : n - 1]; }
int app_save_slot(int n) {
    if (!started) return 0;
    char p[300]; slotPath(p, sizeof p, "st", n);
    if (!saveStateFile(p)) return 0;
    int w, h, fresh; const uint16_t *pic = gba_frame(&w, &h, &fresh);
    uint16_t *t = thumbs[n - 1];
    for (int y = 0; y < THUMB_H; y++) for (int x = 0; x < THUMB_W; x++) t[y * THUMB_W + x] = pic[y * 2 * w + x * 2];
    slotPath(p, sizeof p, "th", n);
    FILE *f = fopen(p, "wb");
    if (f) { uint32_t hd[2] = { 0x31544D53, (uint32_t)time(NULL) }; fwrite(hd, 4, 2, f); fwrite(t, 2, THUMB_W * THUMB_H, f); fclose(f); }
    readSlots();
    gba_save_flush();
    return 1;
}
static void startCore(void);
static void afterLoad(void) { link_session(); border_reset(); soundFlush(); }
static void changeScreen(int st) { state = st; touchBlock = 1; link_touch_cancel(); if (st == ST_GAME) topDirty = 2; }
int app_load_slot(int n) {
    char p[300]; slotPath(p, sizeof p, "st", n);
    if (!started) startCore();
    if (!loadStateFile(p)) return 0;
    afterLoad();
    changeScreen(ST_GAME);
    return 1;
}
static int haveAuto = -1;
int app_have_auto(void) {
    if (haveAuto < 0) { char p[300]; snprintf(p, sizeof p, "%s/auto.st", DATA_DIR); FILE *f = fopen(p, "rb"); haveAuto = f != NULL; if (f) fclose(f); }
    return haveAuto;
}
static void saveAuto(void) {
    char p[300]; snprintf(p, sizeof p, "%s/auto.st", DATA_DIR);
    if (saveStateFile(p)) haveAuto = 1;
    gba_save_flush();
}

/* ------------------------------------------------------------------ the app's states */
int app_running(void) { return started; }
int app_core2(void) { return core2ok; }
int app_threaded(void) { return threadedNow; }
int app_dev_used(void) { return devUsed; }
const char *app_data_dir(void) { return DATA_DIR; }
static void startCore(void) {
    if (started) return;
    gba_set_savedir(DATA_DIR);
    music_cut();
    cloudsOff = 0;
    threadedNow = core2ok && pf.thread;
    gba_set_threaded(threadedNow);
    logp("drawing on %s", threadedNow ? "the third core" : "the main core");
    if (gba_load_mem(rom, romLen, ROMPATH)) { logp("core could not load the ROM"); exitFlag = 1; return; }
    applySettings();
    started = 1; devUsed = pf.devUnlock && (pf.codeHealth || pf.codeLives);
    afterLoad();
}
void app_start(int cont) {
    startCore();
    if (cont) { char p[300]; snprintf(p, sizeof p, "%s/auto.st", DATA_DIR); loadStateFile(p); afterLoad(); }
    changeScreen(ST_GAME);
    clearTop();
}
void app_pause(void) { changeScreen(ST_PAUSE); soundFlush(); menu_show(1); prefsSave(); gba_save_flush(); }
void app_resume(void) { changeScreen(ST_GAME); soundFlush(); }
void app_reset(void) { gba_reset(); link_session(); border_reset(); changeScreen(ST_GAME); soundFlush(); }
void app_quit(void) {
    if (started) { saveAuto(); gba_unload(); started = 0; }
    changeScreen(ST_LAUNCHER); soundFlush();
    menu_show(0); drawLauncherTop();
    if (pf.music) music_play();
}
void app_exit(void) { exitFlag = 1; }
const char *app_kit_state(void) { return kitState == 1 ? "ready" : kitState == 3 ? "working" : "failed"; }
const char *app_rom_line(void) { return romOk ? "SMA (USA, EUROPE) OK" : "UNKNOWN ROM"; }

/* ------------------------------------------------------------------ the first-start capture (../extract.c): boots the ROM with no save, walks
 * title > file > 1-1 > pause by itself and keeps the title, 1-1 and pause screens' video memory. Your real save is never opened. */
#define KIT_FILE DATA_DIR "/kit-2-1e4c6d6a.bin"
static void kitScreen(int f) {
    uint16_t *b = menuBuf;
    memcpy(b, kit_backdrop(320), sizeof menuBuf);
    cv_target(b, LINK_W, LINK_H);
    font_solid = 1;
    rectA(30, 96, 260, 48, 0x1E1206, 180);
    textC("PREPARING MENU GRAPHICS", 160, 104, 0xF8F8F8);
    textC("ONLY ON THE FIRST START", 160, 116, 0xC8C0B0);
    int w = f * 240 / 1500; rect(40, 130, w > 240 ? 240 : w, 4, 0xF8C800);
    drawBottom(b); gfxFlushBuffers(); gfxScreenSwapBuffers(GFX_BOTTOM, false);
}
static int kitTick(int f) {
    gba_skip_next_render();                               /* it keeps video memory, never the picture */
    if (f % 30 == 0) kitScreen(f);
    if (f % 8 == 0 && !aptMainLoop()) { exitFlag = 1; return 1; }
    return 0;
}
static int captureKit(void) {
    u64 t0 = osGetTime();
    kitState = 3;
    gba_set_overclock(0);
    gba_set_threaded(0);
    gba_set_savedir(DATA_DIR "/no-such-folder");          /* an empty save: reading fails and writing fails */
    int n = gba_load_mem(rom, romLen, ROMPATH) ? -10 : 0;
    if (!n) {
        size_t cap = sma_extract_size(); uint8_t *b = malloc(cap);
        sma_extract_tick = kitTick;
        n = sma_extract(b, cap);
        sma_extract_tick = NULL;
        if (n > 0) {
            FILE *f = fopen(KIT_FILE ".tmp", "wb");
            if (!f || fwrite(b, 1, (size_t)n, f) != (size_t)n) n = -11;
            if (f) fclose(f);
            if (n > 0) { remove(KIT_FILE); rename(KIT_FILE ".tmp", KIT_FILE); }
        }
        free(b);
        gba_unload();
    }
    gba_set_savedir(DATA_DIR);
    logp("menu capture %d in %d ms", n, (int)(osGetTime() - t0));
    kitState = n > 0 ? 1 : 2;
    return n > 0;
}
static void loadKit(int force) {
    FILE *f = force ? NULL : fopen(KIT_FILE, "rb");
    if (!f) {
        if (!force) { FILE *fl = fopen(DATA_DIR "/kit-failed", "rb"); if (fl) { fclose(fl); kitState = 2; return; } }
        if (!captureKit()) { FILE *fl = fopen(DATA_DIR "/kit-failed", "wb"); if (fl) fclose(fl); return; }
        remove(DATA_DIR "/kit-failed");
        f = fopen(KIT_FILE, "rb");
        if (!f) { kitState = 2; return; }
    }
    fseek(f, 0, SEEK_END); size_t n = (size_t)ftell(f); fseek(f, 0, SEEK_SET);
    uint8_t *b = malloc(n);
    int ok = b && fread(b, 1, n, f) == n && kit_load(b, n);
    free(b); fclose(f);
    kitState = ok ? 1 : 2;
    link_kit_changed();
}
void app_rekit(void) {
    if (started) return;
    music_cut(); soundFlush();
    loadKit(1);
    drawLauncherTop();
    if (pf.music) music_play();
}

/* ------------------------------------------------------------------ playtest codes (MainActivity.devAction, applyCodes, applyWarp) */
static int pendingWarp = -1, csFrames; static char osd[32]; static int osdT;
static void showOsd(const char *m) { snprintf(osd, sizeof osd, "%s", m); osdT = 90; }
static const char *const LVN[20] = { "1-1", "1-2", "1-3", "2-1", "2-2", "2-3", "3-1", "3-2", "3-3", "4-1", "4-2", "4-3", "5-1", "5-2", "5-3", "6-1", "6-2", "6-3", "7-1", "7-2" };
const char *app_dev(const char *what) {
    static char msg[40];
    if (!started) return "START A GAME FIRST";
    devUsed = 1;
    if (!strcmp(what, "report")) { link_toggle_ending(); return "ENDING CARD ON / OFF"; }
    strcpy(msg, "DONE");
    if (!strcmp(what, "refill")) { gba_poke(A_HEALTH, (unsigned)((S_maxHearts() - 1) * 0x10 + 0x0F)); strcpy(msg, "LIFE FULL"); }
    else if (!strcmp(what, "star")) { gba_poke(A_STAR, 0xFF); strcpy(msg, "STARMAN"); }
    else if (!strcmp(what, "stopwatch")) { gba_poke(A_WATCH, 0xFF); strcpy(msg, "STOPWATCH"); }
    else if (!strcmp(what, "lives")) { int l = S_lives() + 5; gba_poke(A_LIVES, (unsigned)(l > 99 ? 99 : l)); strcpy(msg, "+5 LIVES"); }
    else if (!strcmp(what, "cherries")) { gba_poke(A_CHERRIES, 4); strcpy(msg, "4 CHERRIES"); }
    else if (!strncmp(what, "warp:", 5)) {
        int L = atoi(what + 5);
        if (L >= 0 && L <= 19) { csFrames = 0; pendingWarp = L; snprintf(msg, sizeof msg, S_charSelect() ? "WARP TO %s NOW" : "%s AT CHARACTER SELECT", LVN[L]); }
    } else if (!strncmp(what, "time:", 5)) {
        long fr = (long)(atoi(what + 5) * 60 * GBA_FPS);
        sma_t[0] = (uint32_t)fr;
        snprintf(msg, sizeof msg, "TIME %ld:%02ld", fr / 215019, (long)(fr / (60 * GBA_FPS)) % 60);
    }
    return msg;
}
static void applyCodes(void) {
    if (!S_playing()) return;
    if (pf.codeHealth) { int h = P(A_HEALTH), full = (S_maxHearts() - 1) * 0x10 + 0x0F; if (h < 0xF0 && h != full) gba_poke(A_HEALTH, (unsigned)full); }
    if (pf.codeLives && S_lives() < 5) gba_poke(A_LIVES, 5);
}
/* The game reloads the level from the file block when character select opens, so a poke in a level is undone: wait for the next
 * character select and poke there, again if the game rewrites it, until the level starts. The world's graphics set is held too. */
static void applyWarp(void) {
    if (!S_inLevel()) {
        int w = 0; static const int WS[7] = { 0, 3, 6, 9, 12, 15, 18 };
        for (int i = 0; i < 7; i++) if (pendingWarp >= WS[i]) w = i;
        gba_poke(A_TILESET, (unsigned)w); gba_poke(A_TILESET + 1, 0);
    }
    if (S_charSelect()) {
        if (++csFrames >= 4 && csFrames < 600 && S_level() != pendingWarp) { gba_poke(A_LEVEL, (unsigned)pendingWarp); sma_track_reset(); }
    } else {
        if (csFrames >= 4 && S_inLevel()) {
            char m[24]; snprintf(m, sizeof m, S_level() == pendingWarp ? "WARPED TO %s" : "WARP FAILED", LVN[pendingWarp]);
            showOsd(m); pendingWarp = -1;
        }
        csFrames = 0;
    }
}

/* ------------------------------------------------------------------ finding the ROM */
static uint32_t crc32of(const uint8_t *d, size_t n) {
    static uint32_t T[256]; static int ready;
    if (!ready) { for (uint32_t i = 0; i < 256; i++) { uint32_t c = i; for (int k = 0; k < 8; k++) c = c & 1 ? 0xEDB88320u ^ (c >> 1) : c >> 1; T[i] = c; } ready = 1; }
    uint32_t c = 0xFFFFFFFFu;
    for (size_t i = 0; i < n; i++) c = T[(c ^ d[i]) & 255] ^ (c >> 8);
    return c ^ 0xFFFFFFFFu;
}
static void *readFile(const char *p, size_t *n) {
    FILE *f = fopen(p, "rb"); if (!f) return NULL;
    fseek(f, 0, SEEK_END); *n = (size_t)ftell(f); fseek(f, 0, SEEK_SET);
    void *b = malloc(*n);
    if (b && fread(b, 1, *n, f) != *n) { free(b); b = NULL; }
    fclose(f);
    return b;
}
/* the ROM in folder dir: any .gba of the right size that is Super Mario Advance (USA, Europe) */
static int findIn(const char *dir) {
    DIR *d = opendir(dir); struct dirent *e;
    if (!d) return 0;
    while ((e = readdir(d))) {
        size_t n = strlen(e->d_name);
        if (n < 5 || strcasecmp(e->d_name + n - 4, ".gba")) continue;
        char p[320]; snprintf(p, sizeof p, "%s/%s", dir, e->d_name);
        size_t len; uint8_t *b = readFile(p, &len);
        if (b && len == 0x400000 && crc32of(b, len) == SMA_CRC) {
            rom = b; romLen = len; romOk = 1; snprintf(ROMPATH, sizeof ROMPATH, "%s", p); snprintf(ROMDIR, sizeof ROMDIR, "%s", dir); snprintf(romName, sizeof romName, "%s", e->d_name);
            closedir(d); return 1;
        }
        free(b);
    }
    closedir(d);
    return 0;
}
static int findRom(int argc, char **argv) {
    char dir[256] = "";
    if (argc > 0 && argv && argv[0] && strchr(argv[0], '/')) {
        snprintf(dir, sizeof dir, "%s", argv[0]);
        *strrchr(dir, '/') = 0;
        if (!strchr(dir, '/')) strcat(dir, "/");                   /* "sdmc:" -> "sdmc:/" */
        if (findIn(dir)) return 1;
    }
    static const char *const PLACES[] = { DATA_DIR, "sdmc:/3ds", "sdmc:/roms/gba", "sdmc:/roms", "sdmc:/gba" };
    for (size_t i = 0; i < sizeof PLACES / sizeof PLACES[0]; i++) if (findIn(PLACES[i])) return 1;
    snprintf(ROMDIR, sizeof ROMDIR, "%s", dir[0] ? dir : DATA_DIR);
    return 0;
}
static void waitExit(const char *msg) {
    consoleInit(GFX_BOTTOM, NULL);
    printf("\n  SUPER MARIO ADVANCE DUAL 3DS\n  %s\n\n%s\n\n  Press START to quit.\n", VERSION_3DS, msg);
    while (aptMainLoop()) { hidScanInput(); if (hidKeysDown() & KEY_START) break; gspWaitForVBlank(); }
}

/* ------------------------------------------------------------------ suspend and exit */
static aptHookCookie cookie;
static void aptEvent(APT_HookType hook, void *param) {
    (void)param;
    if (hook == APTHOOK_ONSUSPEND || hook == APTHOOK_ONSLEEP) { if (started) gba_save_flush(); if (prefsDirty) prefsSave(); }
    if (hook == APTHOOK_ONRESTORE || hook == APTHOOK_ONWAKEUP) soundFlush();
}

/* ------------------------------------------------------------------ timing, on SYSTEM and in the log */
static struct { u64 gba, top, link, max; int nGba, n; } tm;
static int msGba, msWait, msTop, msLink, msMax, fpsGame, fpsShown;
static char timingLine[64], timingLine2[64];
static u64 waitTicks;
const char *app_timing(void) {
    if (!msGba) return "";
    snprintf(timingLine, sizeof timingLine, "GBA %d.%d WAIT %d.%d", msGba / 10, msGba % 10, msWait / 10, msWait % 10);
    return timingLine;
}
const char *app_timing2(void) {
    if (!msGba) return "";
    snprintf(timingLine2, sizeof timingLine2, "TOP %d.%d LINK %d.%d MAX %d.%d", msTop / 10, msTop % 10, msLink / 10, msLink % 10, msMax / 10, msMax % 10);
    return timingLine2;
}
/* The FPS and message lines: redrawn into their texture only when the words change. */
static void drawOverlays(TgFrame *f) {
    static uint16_t t[256 * TG_TEXT_H];
    static char last[2][32];
    char s[2][32] = { "", "" };
    if (pf.stats) snprintf(s[0], 32, fpsShown < fpsGame ? "%d FPS %d SHOWN" : "%d FPS", fpsGame, fpsShown);
    if (osdT > 0) { osdT--; snprintf(s[1], 32, "%s", osd); }
    for (int i = 0; i < 2; i++) {
        int w = (int)strlen(s[i]) * 8 + (i ? 8 : 4);
        if (s[i][0] && strcmp(s[i], last[i])) {
            memset(t, 0, sizeof t);
            cv_target(t, 256, TG_TEXT_H); font_solid = 1; text(s[i], i ? 4 : 2, 2, i ? 0xFC9838 : 0x58F898);
            tg_text(i, t, w);
        }
        memcpy(last[i], s[i], 32);
        f->textW[i] = s[i][0] ? w : 0;
        f->textX[i] = i ? gx + (gw - w) / 2 : gx; f->textY[i] = i ? gy + 16 : gy;
    }
}

int main(int argc, char **argv) {
    gfxInitDefault();
    osSetSpeedupEnable(true);
    APT_CheckNew3DS(&n3ds);
    s64 emu = 0; onEmulator = R_SUCCEEDED(svcGetSystemInfo(&emu, 0x20000, 0)) && emu != 0;   /* Azahar answers 2. Success alone isn't enough: a real 3DS logged itself as an emulator */
    mkdir("sdmc:/3ds", 0777); mkdir(DATA_DIR, 0777);
    { char p[300]; snprintf(p, sizeof p, "%s/sma-3ds.log", DATA_DIR); logf = fopen(p, "w"); }
    logp("Super Mario Advance Dual 3DS %s, %s 3DS%s", VERSION_3DS, n3ds ? "New" : "old", onEmulator ? " (emulator)" : "");
    prefs_defaults();
    prefsLoad();
#ifdef TEST_PREFS
    TEST_PREFS
#endif
    if (!findRom(argc, argv)) {
        char msg[500];
        snprintf(msg, sizeof msg, "  Put your Super Mario Advance\n  (USA, Europe) ROM in:\n  %s\n  or %s\n  (CRC32 1E4C6D6A, no game data\n  ships with this app)", ROMDIR, DATA_DIR);
        logp("no ROM");
        waitExit(msg); gfxExit(); return 0;
    }
    logp("ROM %s", ROMPATH);
    core2ok = canUseCore2();
    logp("third core %s", core2ok ? "available" : "refused");
    gfxSetScreenFormat(GFX_TOP, GSP_RGB565_OES);
    gfxSetScreenFormat(GFX_BOTTOM, GSP_RGB565_OES);
    gfxSetDoubleBuffering(GFX_TOP, true);
    gfxSetDoubleBuffering(GFX_BOTTOM, false);
    hidSetRepeatParameters(24, 6);
    for (int i = 0; i < 3; i++) { gameLin[i] = linearAlloc(256 * 160 * 2); if (gameLin[i]) memset(gameLin[i], 0, 256 * 160 * 2); }
    if (!gameLin[0] || !gameLin[1] || !gameLin[2] || !tg_init()) { logp("the GPU would not start"); waitExit("  The GPU would not start."); gfxExit(); return 0; }
    gba_set_output(gameLin[0], 256);
    gba_set_frames(gameLin[0], gameLin[1], gameLin[2]);
    gfxScreenSwapBuffers(GFX_BOTTOM, false);                   /* the GPU only presents the top screen: this is what sets the bottom one's format */
    gba_init(DATA_DIR, DATA_DIR);
    soundInit();
    link_init(rom, romLen);
    readSlots();
    aptHook(&cookie, aptEvent, NULL);
    clearTop();
    loadKit(0);
    if (exitFlag) goto done;
    menu_show(0);

    int cont = 0;
#if defined(CTR_TEST) && !defined(TEST_LAUNCHER)
    cont = -1;
#endif
    if (pf.skipLauncher && app_have_auto()) cont = 1;
    if (cont > 0) app_start(1);
#ifdef CTR_TEST
    else if (cont < 0) { app_start(0); }
#endif
    else { drawLauncherTop(); if (pf.music) music_play(); }

    static int16_t snd[16384], snd2[8192];
    static int oddL, oddR, oddHave;
    u64 secAt = osGetTime(); int frames = 0, shown = 0, frame = 0, comboHeld = 0, tick = 0, wasSave = 0, wasLoad = 0, wasMenu = 0;
    static double avgGba, avgCpu, avgOther;
    static u64 due;                                                /* when this loop should finish: pace.h */
    int linkFresh = 0;
    u64 bootAt = osGetTime(); int logN = 0;
    while (aptMainLoop() && !exitFlag) {
        hidScanInput();
        u32 held = hidKeysHeld(), down = hidKeysDown(), rep = hidKeysDownRepeat();
        int tDown = (held & KEY_TOUCH) != 0, tx = 0, ty = 0;
        if (tDown) { touchPosition t; hidTouchRead(&t); tx = t.px; ty = t.py; }
        /* a screen just changed under the stylus: this press belongs to the old one */
        if (!tDown) touchBlock = 0;
        if (touchBlock) tDown = 0;
#ifdef CTR_TEST
        static int testPad, testFF, testTapX = -1, testTapY, testHold;
        {   /* test build only (test/): buttons, taps and fast-forward by frame, because an emulator's input can't be driven headless.
               TEST_SCRIPT entries: { frame, frames, pad bits, tap x, tap y, ff, menu act } */
            static const struct { int at, len; unsigned pad; int tx, ty, ff, act; } S[] = { TEST_SCRIPT };
            testPad = 0; testFF = 0; testTapX = -1;
            for (size_t i = 0; i < sizeof S / sizeof S[0]; i++) {
                if (frame < S[i].at || frame >= S[i].at + S[i].len) continue;
                testPad |= (int)S[i].pad; testFF |= S[i].ff;
                if (S[i].tx >= 0) { testTapX = S[i].tx; testTapY = S[i].ty; }
                if (S[i].act == 1 && frame == S[i].at && state == ST_GAME) app_pause();
                else if (S[i].act == 2 && frame == S[i].at && state == ST_PAUSE) app_resume();
                else if (S[i].act >= 10 && frame == S[i].at) menu_pad(S[i].act - 10);
            }
            (void)testHold;
            if (testTapX >= 0) { tDown = 1; tx = testTapX; ty = testTapY; }
        }
#ifdef TEST_STATE
        if (frame == 2 && !loadStateFile(TEST_STATE)) logp("test state failed: " TEST_STATE); else if (frame == 2) afterLoad();
#endif
#ifdef TEST_HASH
        if (frame == TEST_HASH) {   /* the whole machine and the picture after a script: the same in any build of the core, or that build changed the emulation */
            size_t n = gba_state_size(); uint8_t *b = malloc(n); uint32_t h1 = 2166136261u, h2 = 2166136261u; int w_, h_, f_;
            if (b && gba_state_save(b, n)) for (size_t i = 0; i < n; i++) h1 = (h1 ^ b[i]) * 16777619u;
            free(b);
            svcSleepThread(200000000); gba_nudge();                  /* the drawing core catches up: the picture is the frame just run, on either path */
            svcSleepThread(200000000);
            const uint16_t *p_ = gba_take_frame(); if (!p_) p_ = gba_frame(&w_, &h_, &f_);
            if (p_) for (int y = 0; y < 160; y++) for (int x = 0; x < 240; x++) h2 = (h2 ^ p_[y * 256 + x]) * 16777619u;
            logp("hash at frame %d: state %08lx picture %08lx", frame, (unsigned long)h1, (unsigned long)h2);
        }
#endif
#ifdef TEST_QUIT
        if (frame == TEST_QUIT) break;
#endif
#endif
        if (state != ST_GAME) {                                    /* the launcher and the pause menu */
            int mw = menu_waiting();
            if (mw >= 0) {
                for (int k = 0; k < KEY3_N; k++) if (down & KEY3[k]) { if (KEY3[k] == KEY_B && 0) break; menu_bound(k); break; }
            } else {
                if (rep & (KEY_DUP | KEY_CPAD_UP)) menu_pad(MP_UP);
                if (rep & (KEY_DDOWN | KEY_CPAD_DOWN)) menu_pad(MP_DOWN);
                if (rep & (KEY_DLEFT | KEY_CPAD_LEFT)) menu_pad(MP_LEFT);
                if (rep & (KEY_DRIGHT | KEY_CPAD_RIGHT)) menu_pad(MP_RIGHT);
                if (down & KEY_A) menu_pad(MP_OK);
                if (down & KEY_B) menu_pad(MP_BACK);
                if ((down & KEY_START) && state == ST_PAUSE) app_resume();
            }
            menu_touch(tDown, tx, ty);
            if (state == ST_GAME) { frame++; continue; }           /* resumed, or a state loaded */
            if (++tick % 3 == 0 && menu_tick() && state != ST_GAME) { menu_draw(menuBuf); drawBottom(menuBuf); }
            if (music_active() && haveSound) {
                static int acc;                                    /* the music, a screen refresh's worth at a time */
                int n = (int)(SOUND_RATE / SCREEN_HZ) + ((acc += 1) % 4 == 0 ? 1 : 0);
                if (!pf.music && music_active() == 1) music_stop();
                music_render(snd2, n, SOUND_RATE);
                soundPush(snd2, n);
            } else if (state == ST_LAUNCHER && pf.music && !music_active()) music_play();
            if (prefsDirty && tick % 60 == 0) prefsSave();
            frame++;
            /* only the bottom: swapping the top would flick between the game's last two frames while paused */
            gfxFlushBuffers(); gfxScreenSwapBuffers(GFX_BOTTOM, false); gspWaitForVBlank();
            continue;
        }

        /* ---------------------------------------------------------------- in the game: the buttons as set on SETTINGS > CONTROLS */
        u32 special = 0; unsigned bits = 0;
        u32 game = held; int lHotkey = 0;
        if (held & KEY_L) {                                        /* while L is held, X and Y are the quick save and load */
            if (down & KEY_X) lHotkey = 1;
            if (down & KEY_Y) lHotkey = 2;
            game &= ~(u32)(KEY_X | KEY_Y);
        }
        for (int k = 0; k < KEY3_N; k++) if ((game & KEY3[k]) && pf.key[k] >= 0) {
            switch (pf.key[k]) {
            case ACT_UP: bits |= BTN(J_UP); break;      case ACT_DOWN: bits |= BTN(J_DOWN); break;
            case ACT_LEFT: bits |= BTN(J_LEFT); break;  case ACT_RIGHT: bits |= BTN(J_RIGHT); break;
            case ACT_B: bits |= BTN(J_B); break;        case ACT_A: bits |= BTN(J_A); break;
            case ACT_SELECT: bits |= BTN(J_SELECT); break; case ACT_START: bits |= BTN(J_START); break;
            case ACT_L: bits |= BTN(J_L); break;        case ACT_R: bits |= BTN(J_R); break;
            default: special |= 1u << pf.key[k]; break;
            }
        }
        if (held & KEY_CPAD_UP) bits |= BTN(J_UP);
        if (held & KEY_CPAD_DOWN) bits |= BTN(J_DOWN);
        if (held & KEY_CPAD_LEFT) bits |= BTN(J_LEFT);
        if (held & KEY_CPAD_RIGHT) bits |= BTN(J_RIGHT);
#ifdef CTR_TEST
        bits |= (unsigned)testPad;
        if (testFF) special |= 1u << ACT_FAST;
#endif
        int ff = (special & (1u << ACT_FAST)) != 0;
        int save = (special & (1u << ACT_SAVE)) != 0, load = (special & (1u << ACT_LOAD)) != 0, menuKey = (special & (1u << ACT_MENU)) != 0;
        if ((save && !wasSave) || lHotkey == 1) { char p[300]; snprintf(p, sizeof p, "%s/quick.st", DATA_DIR); showOsd(saveStateFile(p) ? "SAVED" : "SAVE FAILED"); }
        if ((load && !wasLoad) || lHotkey == 2) { char p[300]; snprintf(p, sizeof p, "%s/quick.st", DATA_DIR); if (loadStateFile(p)) { afterLoad(); showOsd("LOADED"); } else showOsd("NO QUICK STATE"); }
        wasSave = save; wasLoad = load;
        if (menuKey && !wasMenu) { wasMenu = 1; app_pause(); frame++; continue; }
        wasMenu = menuKey;
        int combo = (bits & (BTN(J_SELECT) | BTN(J_START))) == (BTN(J_SELECT) | BTN(J_START));
        if (combo && !comboHeld) { comboHeld = 1; app_pause(); frame++; continue; }  /* SELECT+START: pause menu, once per press */
        if (!combo) comboHeld = 0;
        if (combo) bits &= ~(BTN(J_SELECT) | BTN(J_START));        /* still held after the menu: the game doesn't see it */

        int bottomDrawn = 0;
        if (link_touch(tDown, tx, ty) && state == ST_GAME) { link_redraw(); drawBottom(link_buffer()); bottomDrawn = 1; }
        if (state != ST_GAME) { frame++; continue; }               /* PAUSE GAME, or a state loaded from the OPTIONS tab */

        /* FRAME SKIP. A loop that takes longer than a refresh runs slow; with it on, each loop runs the fewest GBA frames N that fit in N refreshes
           and shows only the last, so the game and the music keep their speed. A frame that isn't shown isn't drawn either, so the costs are kept
           apart: N frames cost N x the CPU's share + one drawing + the rest (top screen, Link screen), with 1 ms spare. */
        u64 t0 = svcGetSystemTick();
        int runs = ff ? 3 : 1;
        if (!ff && pf.frameskip) {
            double cpu = avgCpu > 0 ? avgCpu : avgGba * 0.6, drawn = avgGba > cpu ? avgGba - cpu : 0;
            while (runs < 4 && runs * cpu + drawn + avgOther + SYSCLOCK_ARM11 / 1000.0 > runs * (SYSCLOCK_ARM11 / SCREEN_HZ)) runs++;
        }
        for (int r = 0; r < runs; r++) {
            u64 tr = svcGetSystemTick();
            unsigned b2 = bits;
            if ((frame + r) & 2) { if (special & (1u << ACT_TURBO_B)) b2 |= BTN(J_B); if (special & (1u << ACT_TURBO_A)) b2 |= BTN(J_A); }
            gba_set_pad(b2);
            #ifdef TEST_NORENDER
            gba_skip_next_render();
#endif
            if (r + 1 < runs) gba_skip_next_render();
            gba_run(); frames++;
            if (pf.codeHealth || pf.codeLives) { if (pf.devUnlock) applyCodes(); }
            if (pendingWarp >= 0) applyWarp();
            int n = gba_audio(snd, 16384);                         /* 65536 Hz -> 32768: the mean of each pair */
            if (!ff && pf.audio) {
                int o = 0;
                for (int i = 0; i + 1 < n; i += 2) {
                    if (oddHave) { snd2[o * 2] = (int16_t)((oddL + snd[i]) >> 1); snd2[o * 2 + 1] = (int16_t)((oddR + snd[i + 1]) >> 1); o++; oddHave = 0; }
                    else { oddL = snd[i]; oddR = snd[i + 1]; oddHave = 1; }
                }
                soundPush(snd2, o);
            }
            if (!ff) {
                double c = (double)(svcGetSystemTick() - tr);
                if (r + 1 < runs) avgCpu += (c - avgCpu) / (avgCpu > 0 ? 16 : 1);
                else avgGba += (c - avgGba) / 16;
            }
        }
        u64 t1 = svcGetSystemTick();
        { int want = pf.noClouds && S_inLevel() && S_level() < 18;      /* world 7's sky is BG2 */
          if (want != cloudsOff) { cloudsOff = want; gba_enable_layer(2, !want); } }
        topLayout();
        if (frame % 20 == 0 && pf.bezels) border_sample(rom);
        if ((frame % 10 == 0 || topDirty) && border_build(border, gx, gy, gw, gh, pf.bezels)) topDirty = 1;
        if (topDirty) { tg_border(border); topDirty = 0; }
        u64 t2 = svcGetSystemTick();
        /* The picture, once the drawing core has it. If it isn't done yet, wait only with time this refresh can spare, leaving enough to show it: past that, this loop shows
           nothing new and the next one shows the newest, rather than the game waiting for a picture. */
        const uint16_t *pic = gba_take_frame();
        if (!pic && gba_frames_async()) {
            u64 until = t0 + (u64)(SYSCLOCK_ARM11 / SCREEN_HZ) - (u64)(SYSCLOCK_ARM11 / 666);   /* 1.5 ms to show it */
            while (!pic && svcGetSystemTick() < until) { gba_nudge(); svcSleepThread(200000); pic = gba_take_frame(); }
        }
        u64 t3 = svcGetSystemTick();
        waitTicks += t3 - t2;
        TgFrame tf; memset(&tf, 0, sizeof tf);
        tf.game = 1; tf.x = gx; tf.y = gy; tf.w = gw; tf.h = gh; tf.smooth = pf.smooth;
        drawOverlays(&tf);
        if (pic) { tg_game((void *)pic); shown++; }
        tg_present(&tf);
        u64 t4 = svcGetSystemTick();
        /* The Link screen comes after the picture, so its 10-a-second spike can't make the picture miss this refresh. If it runs over, the next loop starts late and its spare time makes it up. */
        link_work();
        /* 10 times a second, with its two costly parts in different frames so that neither runs long: reading the game and drawing the page, then putting it on the screen */
        ++frame;
        if (frame % 6 == 0) { link_tick(); linkFresh = 1; }
        else if (frame % 6 == 3 && linkFresh) { drawBottom(link_buffer()); bottomDrawn = 1; linkFresh = 0; }
        if (frame % 600 == 0) { gba_save_flush(); if (prefsDirty) prefsSave(); }  /* the game's EEPROM save, when it changed */
        if (bottomDrawn) GSPGPU_FlushDataCache(gfxGetFramebuffer(GFX_BOTTOM, GFX_LEFT, NULL, NULL), 320 * 240 * 2);
        u64 t5 = svcGetSystemTick();
        tm.gba += t1 - t0; tm.nGba += runs; tm.top += (t2 - t1) + (t4 - t3); tm.link += t5 - t4; tm.n++;
        if (t5 - t0 > tm.max) tm.max = t5 - t0;
        if (!ff) avgOther += ((double)((t5 - t1) - (t3 - t2)) - avgOther) / 16;
        if (osGetTime() - secAt >= 1000) {
            secAt = osGetTime(); fpsGame = frames; fpsShown = shown; frames = 0; shown = 0;
            const double tenth = SYSCLOCK_ARM11 / 10000.0;
            msGba = (int)(tm.gba / tenth / (tm.nGba ? tm.nGba : 1) + 0.5); if (!msGba) msGba = 1;
            msWait = (int)((gba_wait_ticks() + waitTicks) / tenth / (tm.n ? tm.n : 1) + 0.5); waitTicks = 0;
            msTop = (int)(tm.top / tenth / tm.n + 0.5); msLink = (int)(tm.link / tenth / tm.n + 0.5); msMax = (int)(tm.max / tenth + 0.5);
            memset(&tm, 0, sizeof tm);
            if (++logN % 5 == 0) logp("%ds: %d fps, shown %d, %s, %s, queue %d", (int)((osGetTime() - bootAt) / 1000), fpsGame, fpsShown, app_timing(), app_timing2(), queued);
        }
        /* the next refresh. A frame-skip loop has its N refreshes, and fast-forward only holds to one loop a refresh */
        { const u64 refresh = (u64)(SYSCLOCK_ARM11 / SCREEN_HZ);
          if (ff) due = t0 + refresh; else due += (u64)(runs - 1) * refresh;
          if (pace_wait(svcGetSystemTick(), &due, refresh)) { gspWaitForVBlank(); pace_waited(svcGetSystemTick(), &due, refresh); } }
    }
done:
#ifdef TEST_PGO
    { extern void __gcov_dump(void); __gcov_dump(); }          /* test/pgo.sh: the training run's counts */
#endif
    if (started) { saveAuto(); gba_unload(); }
    prefsSave();
    if (haveSound) { ndspChnWaveBufClear(0); ndspExit(); }
    if (logf) fclose(logf);
    tg_fini();
    gfxExit();
    return 0;
}

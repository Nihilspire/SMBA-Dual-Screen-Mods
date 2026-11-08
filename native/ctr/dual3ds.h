/* dual3ds.h - shared by the 3DS port's screens. Nothing here knows about the 3DS: main.c is the only file that uses libctru, so
 * everything else also builds on the desktop for test/linkhost.c.
 *
 * canvas.c   drawing onto an RGB565 buffer, the game's font, images
 * kit.c      the first-start capture (menu/gbakit.js): backdrop, the pause panel, item icons
 * linkmap.c  the area map, drawn a few cells a frame
 * linkscr.c  the Link screen's pages (assets/link/link.js)
 * menuscr.c  the launcher, pause menu and settings (assets/menu/menu.js)
 * border.c   the top screen's themed borders and HUD (BezelTheme.java, GameView.java)
 * music.c    the launcher's "Curtain Call" (assets/menu/music.js) */
#pragma once
#include <stdint.h>
#include <stddef.h>

#define LINK_W 320
#define LINK_H 240
#define VERSION_3DS "0.32-alpha"

/* ------------------------------------------------------------------ canvas.c */
typedef struct { short w, h; uint16_t *px; uint8_t *a; } Img;   /* a: 0-255 per pixel, or NULL when opaque */

static inline uint16_t rgb565(uint32_t c) { return (uint16_t)(((c >> 8) & 0xF800) | ((c >> 5) & 0x07E0) | ((c >> 3) & 0x001F)); }
static inline uint16_t bgr555to565(unsigned c) { return (uint16_t)((c & 31) << 11 | ((c >> 5) & 31) << 6 | ((c >> 9) & 1) << 5 | ((c >> 10) & 31)); }
static inline uint32_t bgr555to24(unsigned c) {
    unsigned r = c & 31, g = (c >> 5) & 31, b = (c >> 10) & 31;
    return (r << 3 | r >> 2) << 16 | (g << 3 | g >> 2) << 8 | (b << 3 | b >> 2);
}

void cv_target(uint16_t *buf, int w, int h);
void cv_clip(int x, int y, int w, int h);
void cv_noclip(void);
void rect(int x, int y, int w, int h, uint32_t rgb);
void rectA(int x, int y, int w, int h, uint32_t rgb, int alpha);        /* alpha 0-255 */
void rrect(int x, int y, int w, int h, uint32_t rgb);                   /* 1 px corners cut */
void blit(const Img *im, int x, int y);
void blitA(const Img *im, int x, int y, int alpha);
void blitSub(const Img *im, int sx, int sy, int sw, int sh, int dx, int dy);
void blitShadow(const Img *im, int x, int y, uint32_t rgb, int alpha);
void blitRaw(const uint16_t *src, int stride, int w, int h, int x, int y);
void blitScaled(const uint16_t *src, int sw, int sh, int x, int y, int w, int h);
Img *img_new(int w, int h, int alpha);
void img_free(Img *im);
Img *img_resample(const Img *src, int w2, int h2);
void img_pixel(Img *im, int x, int y, uint32_t rgb);
const uint8_t *font_data(void);
void font_load(const uint8_t *rom4bpp);
extern int font_solid;                                                  /* each letter one colour (the Link screen) */
void text(const char *s, int x, int y, uint32_t rgb);
void textS(const char *s, int x, int y, uint32_t rgb, int scale);
void textC(const char *s, int cx, int y, uint32_t rgb);
void textCS(const char *s, int cx, int y, uint32_t rgb, int scale);
void textR(const char *s, int rx, int y, uint32_t rgb);
uint32_t shade24(uint32_t c, float k);

/* ------------------------------------------------------------------ kit.c */
enum { IC_potion, IC_pow, IC_crystal, IC_shell, IC_bomb, IC_oneup, IC_heart, IC_mushroom, IC_bigveg, IC_bobomb, IC_ninji, IC_cherry, IC_key,
       IC_watch, IC_star, IC_egg, IC_coin, IC_heartFull, IC_heartEmpty, IC_ace, IC_rocket, IC_giantVeg, IC_giantShy, IC_giantShyBlue, IC_N, IC_NONE = -1 };
typedef struct {
    int ok;
    Img *corner, *top, *side, *fill, *cursor, *coin;
    Img *icons[IC_N];                                                   /* the first-start capture's, and the live ones (linkscr.c) */
    uint16_t *bd320, *bd400;                                            /* backdrop for each screen */
    uint32_t cream, parch, light, orange, brown, dark, ink;
} Kit;
extern Kit K;
int kit_load(const uint8_t *d, size_t n);
void kit_make_drawn_backdrops(void);                                    /* the look without a capture */
const Img *kit_card(int w, int h);                                      /* the pause panel at this size, cached */
const uint16_t *kit_backdrop(int w);
void kit_reset(void);

/* ------------------------------------------------------------------ the settings (main.c keeps the file) */
enum { ACT_UP, ACT_DOWN, ACT_LEFT, ACT_RIGHT, ACT_B, ACT_A, ACT_SELECT, ACT_START, ACT_TURBO_B, ACT_TURBO_A, ACT_MENU, ACT_FAST, ACT_SAVE, ACT_LOAD, ACT_L, ACT_R, ACT_N };
enum { KEY3_UP, KEY3_DOWN, KEY3_LEFT, KEY3_RIGHT, KEY3_A, KEY3_B, KEY3_X, KEY3_Y, KEY3_L, KEY3_R, KEY3_ZL, KEY3_ZR, KEY3_START, KEY3_SELECT, KEY3_N };
typedef struct {
    uint8_t stretch, integer, smooth, colorfix, unusedBlend, bezels, stats, audio, music, overclock, skipLauncher, romMenu;   /* unusedBlend: was FRAME BLEND. The byte stays so existing settings files still load */
    uint8_t devUnlock, codeHealth, codeLives, unusedOld3ds, frameskip;   /* unusedOld3ds: was OLD 3DS SPEED. The byte stays so existing settings files still load */
    uint8_t xray, markers, zoom, tab;
    int8_t  key[KEY3_N];                                                /* action per 3DS button, -1 none */
    uint8_t thread;                                                     /* 0.2: the drawing on the third core (New 3DS); new fields go last, old files still load */
    uint8_t noClouds;                                                   /* 0.3: leave BG2, the far clouds and stars, out of the picture (not in world 7, where it is the sky) */
} Prefs;
extern Prefs pf;
void prefs_defaults(void);
extern const char *const KEY3_NAME[KEY3_N];
extern const char *const ACT_NAME[ACT_N];
void binds_assign(int key3, int action);                                /* as Android: a button already used swaps over */

/* ------------------------------------------------------------------ what the screens ask of the app (main.c, or the test's stubs) */
typedef struct { uint32_t time; const uint16_t *thumb; } AppSlot;      /* thumb: 120x80 RGB565, NULL when empty */
#define APP_SLOTS 4
#define THUMB_W 120
#define THUMB_H 80
const AppSlot *app_slot(int n);                                         /* 1-4 */
int app_save_slot(int n);
int app_load_slot(int n);
void app_pause(void);
int app_running(void);
int app_have_auto(void);
int app_core2(void);                                                    /* the system lets this app use the third core */
int app_threaded(void);                                                 /* the running game draws on the third core */
int app_dev_used(void);
void app_start(int cont);
void app_resume(void);
void app_reset(void);
void app_quit(void);
void app_exit(void);
void app_settings_changed(void);
void app_rekit(void);
const char *app_kit_state(void);                                        /* "ready" "failed" "working" */
const char *app_dev(const char *what);
const char *app_timing(void);                                           /* the timing lines for SYSTEM, "" for none */
const char *app_timing2(void);
const char *app_rom_line(void);
const char *app_data_dir(void);

/* ------------------------------------------------------------------ linkscr.c + linkmap.c */
void link_init(const uint8_t *rom, size_t len);
void link_kit_changed(void);
void link_tick(void);                                                   /* 10 times a second: reads the game, draws into the buffer */
void link_work(void);                                                   /* every frame: a few cells of the map */
void link_redraw(void);                                                 /* after a touch */
const uint16_t *link_buffer(void);
int link_touch(int down, int x, int y);                                 /* every frame; 1 when the page should be drawn again */
void link_touch_cancel(void);                                           /* the screen changed under the stylus */
void link_toggle_ending(void);
void link_session(void);                                                /* a new game or a loaded state */
const Img *link_logo(void);                                             /* the title's logo, from the ROM */

/* ------------------------------------------------------------------ menuscr.c */
void menu_init(const uint8_t *rom);
void menu_show(int pause);
void menu_draw(uint16_t *fb);
int menu_tick(void);                                                    /* 20 times a second; 1 when it should be drawn */
void menu_pad(int key);                                                 /* MP_* */
void menu_touch(int down, int x, int y);
int menu_waiting(void);                                                 /* waiting for a button to bind */
void menu_bound(int key3);
enum { MP_UP, MP_DOWN, MP_LEFT, MP_RIGHT, MP_OK, MP_BACK };
void menu_toast(const char *msg);

/* ------------------------------------------------------------------ border.c */
void border_reset(void);
void border_sample(const uint8_t *rom);                                 /* every 20 frames in play */
int border_build(uint16_t *b400, int gx, int gy, int gw, int gh, int on);   /* 1 when the picture changed */

/* ------------------------------------------------------------------ music.c */
void music_play(void);
void music_stop(void);
void music_cut(void);
int music_active(void);
void music_render(int16_t *out, int frames, double rate);               /* interleaved stereo */

/* shared helper */
void fmt_hms(char *out, unsigned secs);

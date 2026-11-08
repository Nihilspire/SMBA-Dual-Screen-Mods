/* linkhost.c - desktop test of the 3DS screens: the real core with driver states, the Link screen, menus and borders written as PPMs, so the
 * pages can be checked against the Android ones without the 3DS.
 *   linkhost ROM DATADIR SCRIPT
 * SCRIPT lines:  run N [buttons] | load STATE | tick [N] | tap X Y | drag X0 Y0 X1 Y1 | down X Y | move X Y | up | shot OUT.ppm | top OUT.ppm
 *                menu 0|1 | mshot OUT.ppm | mpad up|down|left|right|ok|back | mtap X Y | tab N | zoom N | kit | warpyoshi | pen.. | time
 * Buttons as in drive.c. */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include "../dual3ds.h"
#include "../sma.h"
#include "libretro.h"

void gba_log(const char *m) { fputs(m, stderr); }
Prefs pf;
static const char *DIRP = ".";
static AppSlot slots[4]; static uint16_t thumbs[4][THUMB_W * THUMB_H];
const char *const KEY3_NAME[KEY3_N] = { "UP", "DOWN", "LEFT", "RIGHT", "A", "B", "X", "Y", "L", "R", "ZL", "ZR", "START", "SELECT" };
const char *const ACT_NAME[ACT_N] = { "UP", "DOWN", "LEFT", "RIGHT", "B", "A", "SELECT", "START", "TURBO B", "TURBO A", "MENU", "FAST", "SAVE", "LOAD", "L", "R" };
void prefs_defaults(void) {
    memset(&pf, 0, sizeof pf);
    pf.bezels = 1; pf.audio = 1; pf.music = 1; pf.overclock = 1; pf.romMenu = 1; pf.xray = 1; pf.markers = 1; pf.zoom = 1;
    static const int8_t K_[KEY3_N] = { ACT_UP, ACT_DOWN, ACT_LEFT, ACT_RIGHT, ACT_A, ACT_B, ACT_TURBO_A, ACT_TURBO_B, ACT_L, ACT_FAST, ACT_SAVE, ACT_LOAD, ACT_START, ACT_SELECT };
    memcpy(pf.key, K_, sizeof K_);
}
void binds_assign(int key, int action) { pf.key[key] = (int8_t)action; }
const AppSlot *app_slot(int n) { return &slots[n - 1]; }
int app_save_slot(int n) {
    int w, h, f; const uint16_t *p = gba_frame(&w, &h, &f);
    for (int y = 0; y < THUMB_H; y++) for (int x = 0; x < THUMB_W; x++) thumbs[n - 1][y * THUMB_W + x] = p[y * 2 * w + x * 2];
    slots[n - 1].time = 1730000000; slots[n - 1].thumb = thumbs[n - 1];
    printf("save slot %d\n", n); return 1;
}
int app_load_slot(int n) { printf("load slot %d\n", n); return 1; }
void app_pause(void) { puts("pause"); }
int app_running(void) { return 1; }
int app_have_auto(void) { return 1; }
int app_core2(void) { return 1; }
int app_threaded(void) { return 0; }
int app_dev_used(void) { return 0; }
void app_start(int c) { printf("start %d\n", c); }
void app_resume(void) { puts("resume"); }
void app_reset(void) { puts("reset"); }
void app_quit(void) { puts("quit"); }
void app_exit(void) { puts("exit"); }
void app_settings_changed(void) {}
void app_rekit(void) {}
const char *app_kit_state(void) { return "ready"; }
const char *app_dev(const char *w) { return w; }
const char *app_timing(void) { return "GBA 9.1, OF IT WAITING 1.2"; }
const char *app_timing2(void) { return "TOP 1.2 LINK 1.8 MAX 14.2"; }
const char *app_rom_line(void) { return "SMA (USA, EUROPE) OK"; }
const char *app_data_dir(void) { return DIRP; }

static unsigned parse(const char *s) {
    unsigned b = 0;
    for (; s && *s; s++) switch (*s) {
        case 'U': b |= 1 << RETRO_DEVICE_ID_JOYPAD_UP; break;   case 'D': b |= 1 << RETRO_DEVICE_ID_JOYPAD_DOWN; break;
        case 'L': b |= 1 << RETRO_DEVICE_ID_JOYPAD_LEFT; break; case 'R': b |= 1 << RETRO_DEVICE_ID_JOYPAD_RIGHT; break;
        case 'A': b |= 1 << RETRO_DEVICE_ID_JOYPAD_A; break;    case 'B': b |= 1 << RETRO_DEVICE_ID_JOYPAD_B; break;
        case 'T': b |= 1 << RETRO_DEVICE_ID_JOYPAD_START; break; case 'E': b |= 1 << RETRO_DEVICE_ID_JOYPAD_SELECT; break; }
    return b;
}
static void ppm(const char *path, const uint16_t *p, int w, int h) {
    FILE *o = fopen(path, "wb"); if (!o) { perror(path); return; }
    fprintf(o, "P6 %d %d 255\n", w, h);
    for (int i = 0; i < w * h; i++) { unsigned c = p[i]; fputc((int)((c >> 11) * 255 / 31), o); fputc((int)(((c >> 5) & 63) * 255 / 63), o); fputc((int)((c & 31) * 255 / 31), o); }
    fclose(o);
}
static int16_t snd[16384];
static long frame;
static uint8_t *ROM;
static void stepAll(int n) {
    for (int i = 0; i < n; i++) {
        gba_run(); gba_audio(snd, 16384); frame++;
        if (frame % 20 == 0) border_sample(ROM);
        link_work();
        if (frame % 6 == 0) link_tick();
    }
}
int main(int argc, char **argv) {
    if (argc < 4) { fprintf(stderr, "linkhost rom datadir script\n"); return 1; }
    DIRP = argv[2];
    FILE *rf = fopen(argv[1], "rb"); fseek(rf, 0, SEEK_END); size_t rn = (size_t)ftell(rf); fseek(rf, 0, SEEK_SET); ROM = malloc(rn); if (fread(ROM, 1, rn, rf) != rn) return 1; fclose(rf);
    prefs_defaults();
    gba_init(".", "/nonexistent-save-dir");
    if (gba_load_mem(ROM, rn, argv[1])) { fprintf(stderr, "load failed\n"); return 1; }
    { char p[300]; snprintf(p, sizeof p, "%s/kit.bin", DIRP); FILE *kf = fopen(p, "rb");
      link_init(ROM, rn);
      if (kf) { fseek(kf, 0, SEEK_END); size_t n = (size_t)ftell(kf); fseek(kf, 0, SEEK_SET); uint8_t *b = malloc(n); if (fread(b, 1, n, kf) == n) printf("kit %d\n", kit_load(b, n)); free(b); fclose(kf); link_kit_changed(); } }
    menu_init(ROM);
    FILE *sc = fopen(argv[3], "r"); char line[512];
    static uint16_t mbuf[LINK_W * LINK_H], top[400 * 240];
    int mode = 0;
    while (fgets(line, sizeof line, sc)) {
        char cmd[32] = "", a[300] = "", b[64] = "", c[32] = "", d[32] = "";
        if (sscanf(line, "%31s %299s %63s %31s %31s", cmd, a, b, c, d) < 1 || cmd[0] == '#') continue;
        if (!strcmp(cmd, "run")) { gba_set_pad(parse(b)); stepAll(atoi(a)); }
        else if (!strcmp(cmd, "load")) { FILE *i = fopen(a, "rb"); if (!i) { perror(a); continue; } fseek(i, 0, SEEK_END); size_t n = (size_t)ftell(i); rewind(i); void *s = malloc(n); if (fread(s, 1, n, i) != n) n = 0; fclose(i); printf("load %s %d\n", a, gba_state_load(s, n)); free(s); link_session(); border_reset(); }
        else if (!strcmp(cmd, "tick")) { int n = a[0] ? atoi(a) : 1; for (int i = 0; i < n; i++) { for (int k = 0; k < 6; k++) link_work(); link_tick(); } }
        else if (!strcmp(cmd, "tap")) { link_touch(1, atoi(a), atoi(b)); link_touch(1, atoi(a), atoi(b)); link_touch(0, 0, 0); link_redraw(); }
        else if (!strcmp(cmd, "drag")) { int x0 = atoi(a), y0 = atoi(b), x1 = atoi(c), y1 = atoi(d); link_touch(1, x0, y0); for (int k = 1; k <= 8; k++) { link_touch(1, x0 + (x1 - x0) * k / 8, y0 + (y1 - y0) * k / 8); link_redraw(); } link_touch(0, 0, 0); link_redraw(); }
        else if (!strcmp(cmd, "down")) link_touch(1, atoi(a), atoi(b));
        else if (!strcmp(cmd, "move")) { link_touch(1, atoi(a), atoi(b)); }
        else if (!strcmp(cmd, "up")) { link_touch(0, 0, 0); link_redraw(); }
        else if (!strcmp(cmd, "shot")) { ppm(a, link_buffer(), LINK_W, LINK_H); }
        else if (!strcmp(cmd, "top")) {   /* the top screen: mode 0 3:2, 1 integer, 2 stretch; b=bezels */
            mode = atoi(b); int gx = mode == 2 ? 0 : mode == 1 ? 80 : 20, gy = mode == 1 ? 40 : 0, gw = mode == 2 ? 400 : mode == 1 ? 240 : 360, gh = mode == 1 ? 160 : 240;
            border_build(top, gx, gy, gw, gh, 1);
            int w, h, f; const uint16_t *p = gba_frame(&w, &h, &f);
            for (int y = 0; y < gh; y++) for (int x = 0; x < gw; x++) top[(gy + y) * 400 + gx + x] = p[(y * 160 / gh) * w + x * 240 / gw];
            ppm(a, top, 400, 240);
        }
        else if (!strcmp(cmd, "menu")) menu_show(atoi(a));
        else if (!strcmp(cmd, "mshot")) { menu_tick(); menu_draw(mbuf); ppm(a, mbuf, LINK_W, LINK_H); }
        else if (!strcmp(cmd, "mpad")) { int k = !strcmp(a, "up") ? MP_UP : !strcmp(a, "down") ? MP_DOWN : !strcmp(a, "left") ? MP_LEFT : !strcmp(a, "right") ? MP_RIGHT : !strcmp(a, "ok") ? MP_OK : MP_BACK; menu_pad(k); }
        else if (!strcmp(cmd, "mtap")) { menu_draw(mbuf); menu_touch(1, atoi(a), atoi(b)); menu_touch(0, 0, 0); }
        else if (!strcmp(cmd, "tab")) { pf.tab = (uint8_t)atoi(a); link_redraw(); }
        else if (!strcmp(cmd, "zoom")) { pf.zoom = (uint8_t)atoi(a); }
        else if (!strcmp(cmd, "ending")) { link_toggle_ending(); }
        else if (!strcmp(cmd, "poke")) { gba_poke(strtoul(a, 0, 16), strtoul(b, 0, 16)); }
        else if (!strcmp(cmd, "echo")) printf("%s", line + 5);
    }
    return 0;
}

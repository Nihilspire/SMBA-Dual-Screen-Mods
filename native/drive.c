/* drive.c - desktop test driver. Usage: drive rom script
 * script lines:  run N [buttons]   (buttons: U D L R A B T(start) E(select) Q(L) W(R))
 *                tap buttons (6 frames down, 6 up) | shot out.ppm | dump out.bin (see DUMP layout) | save f | load f
 *                poke addr val | peek addr [n] | oc 0/1 | lag (prints lag frames so far) | find hexbytes (searches RAM) */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include "gbacore.h"
#include "libretro.h"
void gba_log(const char *m) { fputs(m, stderr); }
/* DUMP layout: IWRAM 0x8000 | EWRAM 0x40000 | IO 0x400 | PAL 0x400 | VRAM 0x18000 | OAM 0x400 */
static const struct { uint32_t a, n; } DUMP[] = { {0x03000000,0x8000},{0x02000000,0x40000},{0x04000000,0x400},
  {0x05000000,0x400},{0x06000000,0x18000},{0x07000000,0x400} };
static unsigned parse(const char *s) {
  unsigned b = 0;
  for (; s && *s; s++) switch (*s) {
    case 'U': b |= 1 << RETRO_DEVICE_ID_JOYPAD_UP; break;   case 'D': b |= 1 << RETRO_DEVICE_ID_JOYPAD_DOWN; break;
    case 'L': b |= 1 << RETRO_DEVICE_ID_JOYPAD_LEFT; break; case 'R': b |= 1 << RETRO_DEVICE_ID_JOYPAD_RIGHT; break;
    case 'A': b |= 1 << RETRO_DEVICE_ID_JOYPAD_A; break;    case 'B': b |= 1 << RETRO_DEVICE_ID_JOYPAD_B; break;
    case 'T': b |= 1 << RETRO_DEVICE_ID_JOYPAD_START; break; case 'E': b |= 1 << RETRO_DEVICE_ID_JOYPAD_SELECT; break;
    case 'Q': b |= 1 << RETRO_DEVICE_ID_JOYPAD_L; break;    case 'W': b |= 1 << RETRO_DEVICE_ID_JOYPAD_R; break; }
  return b;
}
static long frames; static int16_t tmp[16384];
/* trace lo hi max file: log every CPU write in [lo,hi) (frame, addr, size, value, pc, lr). "trace off" stops. */
extern void (*gbx_trace_hook)(uint32_t, uint32_t, int, uint32_t, uint32_t);
static FILE *tf; static uint32_t tlo, thi; static long tmax, tn;
extern void (*gbx_read_hook)(uint32_t, int, uint32_t, uint32_t);
static FILE *rf; static uint32_t rlo, rhi; static long rmax, rn;
static void rhook(uint32_t a, int s, uint32_t pc, uint32_t lr) {
  if (a + s <= rlo || a >= rhi || rn >= rmax) return;
  fprintf(rf, "%ld %08X %d %08X %08X\n", frames, a, s, pc, lr); rn++;
}
static void thook(uint32_t a, uint32_t v, int s, uint32_t pc, uint32_t lr) {
  if (a + s <= tlo || a >= thi || tn >= tmax) return;
  fprintf(tf, "%ld %08X %d %08X %08X %08X\n", frames, a, s, s == 1 ? v & 0xFF : s == 2 ? v & 0xFFFF : v, pc, lr); tn++;
}
static void step(int n) { for (int i = 0; i < n; i++) { gba_run(); gba_audio(tmp, 16384); frames++; } }
int main(int argc, char **argv) {
  gba_init(".", argc > 3 ? argv[3] : ".");
  if (gba_load(argv[1])) { fprintf(stderr, "load failed\n"); return 1; }
  fprintf(stderr, "fps %.4f rate %.0f state %zu\n", gba_fps(), gba_rate(), gba_state_size());
  FILE *sc = fopen(argv[2], "r"); char line[512];
  while (fgets(line, sizeof line, sc)) {
    char cmd[32] = "", a[400] = "", b[64] = ""; if (sscanf(line, "%31s %399s %63s", cmd, a, b) < 1 || cmd[0] == '#') continue;
    if (!strcmp(cmd, "run")) { gba_set_pad(parse(b)); step(atoi(a)); }
    else if (!strcmp(cmd, "tap")) { gba_set_pad(parse(a)); step(6); gba_set_pad(0); step(6); }
    else if (!strcmp(cmd, "shot")) { int w, h, f; const uint16_t *p = gba_frame(&w, &h, &f); FILE *o = fopen(a, "wb");
      fprintf(o, "P6 %d %d 255\n", w, h);
      for (int i = 0; i < w * h; i++) { uint16_t c = p[i]; fputc((c >> 11) << 3, o); fputc(((c >> 5) & 63) << 2, o); fputc((c & 31) << 3, o); }
      fclose(o); }
    else if (!strcmp(cmd, "dump")) { FILE *o = fopen(a, "wb"); uint8_t *buf = malloc(0x40000);
      for (int k = 0; k < 6; k++) { gba_read(DUMP[k].a, buf, DUMP[k].n); fwrite(buf, 1, DUMP[k].n, o); }
      free(buf); fclose(o); }
    else if (!strcmp(cmd, "save")) { size_t n = gba_state_size(); void *buf = malloc(n); gba_state_save(buf, n); FILE *o = fopen(a, "wb"); fwrite(buf, 1, n, o); fclose(o); free(buf); }
    else if (!strcmp(cmd, "load")) { FILE *i = fopen(a, "rb"); fseek(i, 0, SEEK_END); size_t n = ftell(i); rewind(i); void *buf = malloc(n); fread(buf, 1, n, i); fclose(i); printf("load %d\n", gba_state_load(buf, n)); free(buf); }
    else if (!strcmp(cmd, "poke")) { gba_poke(strtoul(a, 0, 16), strtoul(b, 0, 16)); }
    else if (!strcmp(cmd, "peek")) { uint32_t ad = strtoul(a, 0, 16); int n = b[0] ? atoi(b) : 1; printf("%08X:", ad); for (int i = 0; i < n; i++) printf(" %02X", gba_peek(ad + i)); printf("\n"); }
    else if (!strcmp(cmd, "trace")) {
      if (!strcmp(a, "off")) { gbx_trace_hook = NULL; if (tf) fclose(tf); tf = NULL; }
      else { char c[64], lo[32], hi[32], mx[32], fn[256]; sscanf(line, "%63s %31s %31s %31s %255s", c, lo, hi, mx, fn);
        tlo = strtoul(lo, 0, 16); thi = strtoul(hi, 0, 16); tmax = atol(mx); tn = 0; tf = fopen(fn, "w"); gbx_trace_hook = thook; } }
    else if (!strcmp(cmd, "rec")) {   /* rec N buttons file: run N frames, append IWRAM (32 KB) after each */
      char c[16], n[16], bt[64], fn[256]; sscanf(line, "%15s %15s %63s %255s", c, n, bt, fn);
      FILE *o = fopen(fn, "ab"); uint8_t *buf = malloc(0x8000); gba_set_pad(parse(bt[0] == '-' ? "" : bt));
      for (int i = 0; i < atoi(n); i++) { step(1); gba_read(0x03000000, buf, 0x8000); fwrite(buf, 1, 0x8000, o); }
      free(buf); fclose(o); }
    else if (!strcmp(cmd, "rtrace")) {   /* rtrace lo hi max file: log every CPU read in [lo,hi) */
      if (!strcmp(a, "off")) { gbx_read_hook = NULL; if (rf) fclose(rf); rf = NULL; }
      else { char c[64], lo[32], hi[32], mx[32], fn[256]; sscanf(line, "%63s %31s %31s %31s %255s", c, lo, hi, mx, fn);
        rlo = strtoul(lo, 0, 16); rhi = strtoul(hi, 0, 16); rmax = atol(mx); rn = 0; rf = fopen(fn, "w"); gbx_read_hook = rhook; } }
    else if (!strcmp(cmd, "trk")) { extern uint32_t sma_t[16];   /* the Dual tracker: time deaths cleared whoLo whoHi warped flags */
      printf("trk"); for (int i = 0; i < 7; i++) printf(" %08X", sma_t[i]); printf("\n"); }
    else if (!strcmp(cmd, "trkreset")) sma_track_reset();        /* what the app does for a dev warp */
    else if (!strcmp(cmd, "layer")) gba_enable_layer(atoi(a), atoi(b));   /* layer 0-4 0|1: leave a layer out of the picture */
    else if (!strcmp(cmd, "clock")) {                                     /* clock N: N frames, prints microseconds per frame */
      struct timespec t0, t1; clock_gettime(CLOCK_MONOTONIC, &t0); step(atoi(a)); clock_gettime(CLOCK_MONOTONIC, &t1);
      printf("clock %.1f\n", ((t1.tv_sec - t0.tv_sec) * 1e6 + (t1.tv_nsec - t0.tv_nsec) / 1e3) / atoi(a)); }
    else if (!strcmp(cmd, "oc")) gba_set_overclock(atoi(a));
    else if (!strcmp(cmd, "lag")) printf("frame %ld lag %u\n", frames, gba_lag_frames());
    else if (!strcmp(cmd, "flush")) printf("flush %d\n", gba_save_flush());
    else if (!strcmp(cmd, "extract")) { size_t n = sma_extract_size(); uint8_t *buf = malloc(n); int r = sma_extract(buf, n);
      printf("extract %d\n", r); if (r > 0) { FILE *o = fopen(a, "wb"); fwrite(buf, 1, r, o); fclose(o); } free(buf); }
    else if (!strcmp(cmd, "echo")) printf("%s", line + 5);
  }
  gba_save_flush();
  fprintf(stderr, "frames %ld lag %u\n", frames, gba_lag_frames());
  return 0;
}

/* gbacore.c - minimal libretro frontend around the statically linked mGBA core.
 * mGBA's libretro.c is compiled into this file (its `core` object is static), so we can read the GBA's memory
 * directly. Calls the game's stat tracker (smatrack.c) after every frame. */
#include "libretro.c"
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <stdarg.h>
#include <sys/stat.h>
#include "gbacore.h"
#ifdef SMA_CTR
#include <mgba/feature/video-logger.h>
#endif

/* from mgba-dual.patch */
extern int gbx_overclock;
extern unsigned gbx_vblank_waits;

static char fe_sysdir[512] = ".", fe_savedir[512] = ".", fe_savepath[600];
static uint16_t fb[240 * 160];
static int fb_w = 240, fb_h = 160, fb_new;
static int16_t abuf[16384 * 2];
static volatile int alen;
static uint16_t pad;
static int loaded;
static int fe_var_update, fe_cc, fe_blend;
static unsigned lag_frames;
static uint8_t *last_save; static size_t last_save_n;
#ifdef SMA_CTR
static int fe_threaded;
void gba_set_threaded(int on) { fe_threaded = on; }
/* where the picture goes: linear memory for the 3DS's GPU, at its own stride */
static uint16_t *fb_out; static int fb_stride = 240;
void gba_set_output(uint16_t *p, int stride) { fb_out = p; fb_stride = stride; }
#endif
#ifdef __3DS__
/* how long the main core sat waiting for the drawing core at the end of a frame, for the timing line */
#include <3ds.h>
#include <mgba/internal/gba/renderers/proxy.h>
static uint64_t fe_wait_ticks;
static void (*fe_real_wait)(struct mVideoLogger *);
static void fe_timed_wait(struct mVideoLogger *l) { uint64_t t = svcGetSystemTick(); fe_real_wait(l); fe_wait_ticks += svcGetSystemTick() - t; }
uint64_t gba_wait_ticks(void) { uint64_t t = fe_wait_ticks; fe_wait_ticks = 0; return t; }
/* 0.3: frames from the drawing core without stopping the main core. Waiting for the picture at each frame's end cost the main core about a third of its frame in Azahar, so the
   drawing core now finishes on its own: when a frame is done it copies it out itself, into one of three buffers that go round between the two cores (a triple buffer: the drawing core
   always has one to write, the main core always has one to show, and the third is the newest finished frame). GBA LCD colours and frame blend run on the main core after the frame,
   so with either of them on it goes back to waiting. */
static uint16_t *fe_lin[3]; static int fe_w, fe_s = 1, fe_async; static volatile int fe_mid = 2;
static void (*fe_real_finish)(struct GBAVideoRenderer *);
static void fe_finish(struct GBAVideoRenderer *r) {
  fe_real_finish(r);
  if (!fe_async) return;
  uint16_t *o = fe_lin[fe_w];
  memcpy(o, outputBuffer, 256 * 160 * 2);
  GSPGPU_FlushDataCache(o, 256 * 160 * 2);
  fe_w = __atomic_exchange_n(&fe_mid, fe_w | 4, __ATOMIC_ACQ_REL) & 3;
}
void gba_set_frames(uint16_t *a, uint16_t *b, uint16_t *c) { fe_lin[0] = a; fe_lin[1] = b; fe_lin[2] = c; }
/* the newest picture not yet taken, or NULL: 256 stride x 160 */
const uint16_t *gba_take_frame(void) {
  if (!fe_async) { if (!fb_new) return NULL; fb_new = 0; return fb_out; }
  if (!(__atomic_load_n(&fe_mid, __ATOMIC_ACQUIRE) & 4)) return NULL;
  fe_s = __atomic_exchange_n(&fe_mid, fe_s, __ATOMIC_ACQ_REL) & 3;
  return fe_lin[fe_s];
}
int gba_frames_async(void) { return fe_async; }
/* The drawing thread is only woken every 16 scanlines, and the frame's last packets come after the last of those. */
void gba_nudge(void) {
  struct mVideoLogger *l = core ? core->videoLogger : NULL;
  if (!l || !l->wake) return;
  l->lock(l); l->wake(l, 15); l->unlock(l);
}
#endif

static void fe_log(enum retro_log_level l, const char *fmt, ...) {
  char b[512]; va_list ap; va_start(ap, fmt); vsnprintf(b, sizeof b, fmt, ap); va_end(ap);
  if (l >= RETRO_LOG_WARN) gba_log(b);
}
static bool fe_env(unsigned cmd, void *d) {
  switch (cmd) {
  case RETRO_ENVIRONMENT_GET_LOG_INTERFACE: ((struct retro_log_callback *)d)->log = fe_log; return true;
  case RETRO_ENVIRONMENT_GET_SYSTEM_DIRECTORY: *(const char **)d = fe_sysdir; return true;
  case RETRO_ENVIRONMENT_GET_SAVE_DIRECTORY: *(const char **)d = fe_savedir; return true;
  case RETRO_ENVIRONMENT_SET_PIXEL_FORMAT: return *(enum retro_pixel_format *)d == RETRO_PIXEL_FORMAT_RGB565;
  case RETRO_ENVIRONMENT_GET_INPUT_BITMASKS: return true;
  case RETRO_ENVIRONMENT_GET_VARIABLE: {
    struct retro_variable *v = d; v->value = NULL;
    if (!strcmp(v->key, "mgba_use_bios")) v->value = "OFF";             /* built-in HLE BIOS, no file needed */
    else if (!strcmp(v->key, "mgba_skip_bios")) v->value = "ON";
    else if (!strcmp(v->key, "mgba_idle_optimization")) v->value = "Remove Known";
    else if (!strcmp(v->key, "mgba_frameskip")) v->value = "disabled";
    else if (!strcmp(v->key, "mgba_color_correction")) v->value = fe_cc ? "GBA" : "OFF";
    else if (!strcmp(v->key, "mgba_interframe_blending")) v->value = fe_blend ? "mix_smart" : "OFF";
    else if (!strcmp(v->key, "mgba_audio_low_pass_filter")) v->value = "disabled";
    else if (!strcmp(v->key, "mgba_allow_opposing_directions")) v->value = "no";
    else if (!strcmp(v->key, "mgba_force_gbp")) v->value = "OFF";
    return v->value != NULL;
  }
  case RETRO_ENVIRONMENT_GET_VARIABLE_UPDATE: *(bool *)d = fe_var_update; fe_var_update = 0; return true;
  case RETRO_ENVIRONMENT_GET_CAN_DUPE: *(bool *)d = true; return true;
  case RETRO_ENVIRONMENT_GET_CORE_OPTIONS_VERSION: *(unsigned *)d = 0; return true;
  case RETRO_ENVIRONMENT_SET_SYSTEM_AV_INFO: return true;
  default: return false;
  }
}
static void fe_video(const void *d, unsigned w, unsigned h, size_t pitch) {
  if (!d) return;
  if (w > 240) w = 240; if (h > 160) h = 160;
  fb_w = w; fb_h = h;
#ifdef SMA_CTR
#ifdef __3DS__
  if (fe_async) return;                 /* the drawing core copies it out itself: see fe_finish */
#endif
  if (fb_out) { for (unsigned y = 0; y < h; y++) memcpy(fb_out + y * fb_stride, (const uint8_t *)d + y * pitch, w * 2); fb_new = 1; return; }
#endif
  for (unsigned y = 0; y < h; y++) memcpy(fb + y * w, (const uint8_t *)d + y * pitch, w * 2);
  fb_new = 1;
}
static size_t fe_audio_batch(const int16_t *d, size_t frames) {
  int n = (int)frames * 2;
  if (alen + n > (int)(sizeof abuf / 2)) n = (int)(sizeof abuf / 2) - alen;
  if (n > 0) { memcpy(abuf + alen, d, n * 2); alen += n; }
  return frames;
}
static void fe_audio(int16_t l, int16_t r) { int16_t s[2] = {l, r}; fe_audio_batch(s, 1); }
static void fe_poll(void) {}
static int16_t fe_input(unsigned port, unsigned dev, unsigned idx, unsigned id) {
  if (port || dev != RETRO_DEVICE_JOYPAD) return 0;
  if (id == RETRO_DEVICE_ID_JOYPAD_MASK) return pad;
  return (pad >> id) & 1;
}

void gba_set_overclock(int on) { gbx_overclock = on ? 1 : 0; }
void gba_set_color_correction(int on) { fe_cc = on; fe_var_update = 1; }
void gba_set_frame_blend(int on) { fe_blend = on; fe_var_update = 1; }
unsigned gba_lag_frames(void) { return lag_frames; }
/* The next frame isn't drawn (the 3DS's FRAME SKIP): the scanlines aren't rendered, everything else runs. */
void gba_skip_next_render(void) { if (loaded) ((struct GBA *)core->board)->video.frameskipCounter = 1; }
/* A layer left out of the picture (0-3 BG0-BG3, 4 sprites). The game doesn't know. */
void gba_enable_layer(int id, int on) { if (loaded) core->enableVideoLayer(core, (size_t)id, on != 0); }

void gba_set_savedir(const char *save) {
  snprintf(fe_savedir, sizeof fe_savedir, "%s", save);
  snprintf(fe_savepath, sizeof fe_savepath, "%s/game.sav", fe_savedir);
}
int gba_init(const char *sys, const char *save) {
  snprintf(fe_sysdir, sizeof fe_sysdir, "%s", sys); snprintf(fe_savedir, sizeof fe_savedir, "%s", save);
  snprintf(fe_savepath, sizeof fe_savepath, "%s/game.sav", fe_savedir);
  retro_set_environment(fe_env);
  retro_set_video_refresh(fe_video);
  retro_set_audio_sample(fe_audio);
  retro_set_audio_sample_batch(fe_audio_batch);
  retro_set_input_poll(fe_poll);
  retro_set_input_state(fe_input);
  retro_init();
  return 0;
}
static void save_load(void) {
  uint8_t *s = retro_get_memory_data(RETRO_MEMORY_SAVE_RAM); size_t n = retro_get_memory_size(RETRO_MEMORY_SAVE_RAM);
  if (!s || !n) return;
  FILE *f = fopen(fe_savepath, "rb");
  if (f) { fread(s, 1, n, f); fclose(f); }
  free(last_save); last_save = malloc(n); memcpy(last_save, s, n); last_save_n = n;
}
int gba_save_flush(void) {
  if (!loaded) return 0;
  uint8_t *s = retro_get_memory_data(RETRO_MEMORY_SAVE_RAM); size_t n = retro_get_memory_size(RETRO_MEMORY_SAVE_RAM);
  if (!s || !n) return 0;
  if (last_save && last_save_n == n && !memcmp(last_save, s, n)) return 0;
  char tmp[640]; snprintf(tmp, sizeof tmp, "%s.tmp", fe_savepath);
  FILE *f = fopen(tmp, "wb"); if (!f) return -1;
  fwrite(s, 1, n, f); fclose(f);
#ifdef SMA_CTR
  remove(fe_savepath);        /* the 3DS's SD card won't rename over a file that exists */
#endif
  rename(tmp, fe_savepath);
  free(last_save); last_save = malloc(n); memcpy(last_save, s, n); last_save_n = n;
  return 1;
}
int gba_load_mem(const void *buf, size_t n, const char *path) {
  struct retro_game_info gi = { path, buf, n, NULL };
  if (loaded) { gba_save_flush(); retro_unload_game(); }
  loaded = retro_load_game(&gi);     /* mGBA copies the ROM */
#ifdef SMA_CTR
  /* mGBA's threaded video: the renderer on another core, fed from a queue. Read at the core's first reset, inside the first retro_run. */
  if (loaded && fe_threaded) mCoreConfigSetDefaultIntValue(&core->config, "threadedVideo", 1);
#endif
  if (loaded) { retro_set_controller_port_device(0, RETRO_DEVICE_JOYPAD); save_load(); }
  lag_frames = 0;
  sma_track_clear();
  return loaded ? 0 : -3;
}
int gba_load(const char *path) {
  FILE *f = fopen(path, "rb"); if (!f) return -1;
  fseek(f, 0, SEEK_END); long n = ftell(f); fseek(f, 0, SEEK_SET);
  void *buf = malloc(n);
  if (fread(buf, 1, n, f) != (size_t)n) { fclose(f); free(buf); return -2; }
  fclose(f);
  int r = gba_load_mem(buf, (size_t)n, path);
  free(buf);
  return r;
}
void gba_unload(void) { if (loaded) { gba_save_flush(); retro_unload_game(); } loaded = 0; }
void gba_reset(void) { if (loaded) retro_reset(); sma_track_clear(); }
void gba_set_pad(unsigned bits) { pad = bits; }
void gba_run(void) {
  if (!loaded) return;
#ifdef SMA_CTR
  /* The game's main loop spins on the vblank flag (03001178 bit 0) from 08001CDE, reading nothing else and never halting, which is half
     of a frame's work. mGBA halts until the next interrupt when the CPU keeps branching to its idle loop address. Set here, not in
     mGBA's table of known games, so the Android build's emulation stays as it is. */
  ((struct GBA *)core->board)->idleLoop = 0x08001CDE;
#ifdef __3DS__
  if (core->videoLogger) {
    struct mVideoLogger *vl = core->videoLogger;
    struct GBAVideoProxyRenderer *px = (struct GBAVideoProxyRenderer *)((struct GBA *)core->board)->video.renderer;
    if (vl->wait != fe_timed_wait) { fe_real_wait = vl->wait; vl->wait = fe_timed_wait; }
    if (px->backend->finishFrame != fe_finish) { fe_real_finish = px->backend->finishFrame; px->backend->finishFrame = fe_finish; }
    fe_async = fe_lin[0] && !fe_cc && !fe_blend;
    vl->waitOnFlush = !fe_async;                    /* with no copy on the other core, the picture must be whole when retro_run hands it over */
  } else fe_async = 0;
#endif
#endif
  unsigned w0 = gbx_vblank_waits;
  retro_run();
  if (gbx_vblank_waits == w0) lag_frames++;
#ifdef __3DS__
  if (fe_async) gba_nudge();
#endif
  sma_track();
}
const uint16_t *gba_frame(int *w, int *h, int *fresh) {
#ifdef SMA_CTR
#ifdef __3DS__
  if (fe_async) { *w = 256; *h = 160; *fresh = 1; return fe_lin[fe_s]; }
#endif
  if (fb_out) { *w = fb_stride; *h = fb_h; *fresh = fb_new; return fb_out; }   /* *w is the stride here */
#endif
  *w = fb_w; *h = fb_h; *fresh = fb_new; fb_new = 0; return fb;
}
int gba_audio(int16_t *out, int max) { int n = alen < max ? alen : max; memcpy(out, abuf, n * 2);
  memmove(abuf, abuf + n, (alen - n) * 2); alen -= n; return n; }
double gba_fps(void) { struct retro_system_av_info av; retro_get_system_av_info(&av); return av.timing.fps; }
double gba_rate(void) { struct retro_system_av_info av; retro_get_system_av_info(&av); return av.timing.sample_rate; }

static uint8_t *region(uint32_t a, uint32_t *mask) {
  if (!loaded) return NULL;
  struct GBA *g = core->board;
  switch (a >> 24) {
  case 0x02: *mask = 0x3FFFF; return (uint8_t *)g->memory.wram;
  case 0x03: *mask = 0x7FFF;  return (uint8_t *)g->memory.iwram;
  case 0x04: if ((a & 0xFFFFFF) >= 0x400) return NULL; *mask = 0x3FF; return (uint8_t *)g->memory.io;
  case 0x05: *mask = 0x3FF;   return (uint8_t *)g->video.palette;
  case 0x06: { uint32_t o = a & 0x1FFFF; if (o >= 0x18000) return NULL; *mask = 0x1FFFF; return (uint8_t *)g->video.vram; }
  case 0x07: *mask = 0x3FF;   return (uint8_t *)g->video.oam.raw;
  case 0x08: case 0x09: { uint32_t o = a & 0x1FFFFFF; if (o >= g->memory.romSize) return NULL; *mask = 0x1FFFFFF; return (uint8_t *)g->memory.rom; }
  default: return NULL;
  }
}
int gba_peek(uint32_t a) { uint32_t m; uint8_t *p = region(a, &m); return p ? p[a & m] : 0; }
void gba_read(uint32_t a, uint8_t *out, size_t n) {
  uint32_t m; uint8_t *p = region(a, &m);
  if (!p) { memset(out, 0, n); return; }
  uint32_t o = a & m;
  if (o + n <= m + 1) { memcpy(out, p + o, n); return; }
  for (size_t i = 0; i < n; i++) out[i] = gba_peek(a + i);
}
void gba_poke(uint32_t a, unsigned v) {
  uint32_t m; uint8_t *p = region(a, &m);
  if (p && (a >> 24) >= 2 && (a >> 24) <= 3) p[a & m] = v;
}
size_t gba_state_size(void) { return loaded ? retro_serialize_size() : 0; }
int gba_state_save(void *buf, size_t n) { return loaded && retro_serialize(buf, n); }
int gba_state_load(const void *buf, size_t n) { sma_track_reset(); return loaded && retro_unserialize(buf, n); }

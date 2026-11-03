#pragma once
#include <stdint.h>
#include <stddef.h>
/* gbacore.c - minimal libretro frontend around the statically linked mGBA core (GBA only).
 * Shared by the Android JNI layer (gbajni.c) and the desktop test driver (drive.c). */
void gba_log(const char *msg);                 /* provided by the frontend */
int gba_init(const char *sysdir, const char *savedir);
int gba_load(const char *path);
void gba_set_savedir(const char *savedir);    /* where game.sav lives; takes effect at the next gba_load */
/* extract.c: first-start capture of the title and pause screens' video memory (see that file) */
size_t sma_extract_size(void);
int sma_extract(uint8_t *out, size_t cap);
void gba_unload(void);
void gba_reset(void);
void gba_set_pad(unsigned bits);               /* RETRO_DEVICE_ID_JOYPAD_* bits (B Y SELECT START U D L R A X L R ...) */
void gba_run(void);
const uint16_t *gba_frame(int *w, int *h, int *fresh);   /* RGB565, 240x160 */
int gba_audio(int16_t *out, int max);          /* interleaved stereo samples */
double gba_fps(void);
double gba_rate(void);
/* Side-effect-free reads of the GBA address space: 02xxxxxx EWRAM, 03xxxxxx IWRAM, 04xxxxxx IO,
 * 05xxxxxx palette, 06xxxxxx VRAM, 07xxxxxx OAM, 08xxxxxx ROM. */
int gba_peek(uint32_t a);
void gba_read(uint32_t a, uint8_t *out, size_t n);
void gba_poke(uint32_t a, unsigned v);
size_t gba_state_size(void);
int gba_state_save(void *buf, size_t n);
int gba_state_load(const void *buf, size_t n);
/* battery save (EEPROM / flash / SRAM): kept in <savedir>/game.sav, flushed by gba_save_flush */
int gba_save_flush(void);
void gba_set_overclock(int on);                /* NO SLOWDOWN: CPU runs 2x relative to video/sound/timers */
void gba_set_color_correction(int on);
void gba_set_frame_blend(int on);
unsigned gba_lag_frames(void);                 /* frames since load in which the game never waited for vblank */
void sma_track(void);                          /* smatrack.c: after every frame */
void sma_track_reset(void);                    /* after a state load (keeps the counts) */
void sma_track_clear(void);                    /* new game: counts back to 0 */

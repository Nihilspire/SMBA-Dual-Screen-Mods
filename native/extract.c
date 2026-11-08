/* extract.c - first-start asset capture. Boots the user's ROM with an empty, throwaway save folder, plays the route
 * title -> game select -> file -> character select -> 1-1 -> pause on its own (waiting on the game's state bytes, never
 * on fixed frame counts), and snapshots video memory three times:
 *   shot 0  title screen  (BG2 sky gradient, BG0 ground strip with palms, log and water)
 *   shot 1  pause screen  (cream OBJ panel: 16x16 corner/edge/side/fill pieces, the cursor, the Ace Coin icon)
 *   shot 2  1-1 in play   (version 2: the level's sprite sheet in OBJ VRAM + OBJ palettes = item icons: potion,
 *                          mushroom, 1UP, POW, hearts, cherry, key, ... The pause screen overwrites part of it.)
 * File: "SMAX" u32 version, then per shot IO 0x60 | PAL 0x400 | VRAM 0x18000 | OAM 0x400. menu/gbakit.js decodes it.
 * Nothing is kept from the run but these snapshots; the real game.sav is never opened. */
#include <stdio.h>
#include <string.h>
#include "gbacore.h"
#include "libretro.h"

#define MODE  0x030019B0u
#define SUB   0x03001991u
#define STATE 0x03001992u
#define SHOT_IO 0x60
#define SHOT (SHOT_IO + 0x400 + 0x18000 + 0x400)
#define MAXF 4000

static void snap(uint8_t *o) {
  gba_read(0x04000000, o, SHOT_IO); o += SHOT_IO;
  gba_read(0x05000000, o, 0x400); o += 0x400;
  gba_read(0x06000000, o, 0x18000); o += 0x18000;
  gba_read(0x07000000, o, 0x400);
}
static int oam_tile(int i) { return (gba_peek(0x07000004u + i * 8) | gba_peek(0x07000005u + i * 8) << 8) & 0x3FF; }
static int io16(uint32_t a) { return gba_peek(a) | gba_peek(a + 1) << 8; }

size_t sma_extract_size(void) { return 8 + 3 * SHOT; }
int (*sma_extract_tick)(int frame);

/* ROM must be loaded (fresh, empty save). Returns bytes written, or <0: -1 buffer, -2 timeout, -3 title check,
 * -4 pause check, -5 play check (the HUD's two life hearts must be OAM 0 and 1). */
int sma_extract(uint8_t *out, size_t cap) {
  static int16_t snd[16384];
  if (cap < sma_extract_size()) return -1;
  memcpy(out, "SMAX", 4); out[4] = 2; out[5] = out[6] = out[7] = 0;
  int have = 0, inTitle = 0, inLevel = 0, inPause = 0, f;
  int play = 0;
  for (f = 0; f < MAXF && have < 2; f++) {
    int m = gba_peek(MODE), s = gba_peek(SUB), st = gba_peek(STATE);
    unsigned pad = 0, tap = (f % 20) < 6;
    if (m == 2 && s == 1) {                                   /* title */
      if (++inTitle == 90 && !have) {
        if (io16(0x04000008) != 0x5E01 || io16(0x0400000C) != 0x1B03) return -3;
        snap(out + 8); have = 1;
      }
      if (have && tap) pad = 1 << RETRO_DEVICE_ID_JOYPAD_START;
    } else if (m == 2 || (m == 3 && s == 0)) {                /* game select, file select, character select, chart */
      if (have && tap) pad = 1 << RETRO_DEVICE_ID_JOYPAD_A;
    } else if (m == 3 && s == 1 && st == 1) {                 /* in 1-1 */
      inPause = 0;
      if (++inLevel == 60 && !play) {
        int h0 = oam_tile(0), h1 = oam_tile(1);
        if ((h0 != 128 && h0 != 130) || (h1 != 128 && h1 != 130)) return -5;
        snap(out + 8 + 2 * SHOT); play = 1;
      }
      if (inLevel > 60 && tap) pad = 1 << RETRO_DEVICE_ID_JOYPAD_START;
    } else if (m == 3 && s == 1 && st == 0x0D) {              /* pause panel up */
      if (++inPause == 40) {
        if (oam_tile(42) != 768 || oam_tile(43) != 769 || oam_tile(53) != 800 || oam_tile(54) != 801 || oam_tile(6) != 814) return -4;
        snap(out + 8 + SHOT); have = 2;
      }
    }
    gba_set_pad(pad);
    gba_run();
    while (gba_audio(snd, 16384) > 0) {}
    if (sma_extract_tick && sma_extract_tick(f)) { gba_set_pad(0); return -2; }
  }
  gba_set_pad(0);
  return have == 2 && play ? (int)sma_extract_size() : -2;
}

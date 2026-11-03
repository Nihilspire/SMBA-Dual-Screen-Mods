/* smatrack.c - play stats Super Mario Advance doesn't keep: play time, deaths, levels cleared and by whom.
 * Kept here (not in game RAM) and stored as a trailer on every save state (gbajni.c), so they travel with states.
 * Addresses: see RAM-MAP.md. */
#include <string.h>
#include "gbacore.h"

#define MODE         0x030019B0u   /* 3 = SMB2 */
#define SUB_MODE     0x03001991u   /* 1 = in a level (0 = character select / chart) */
#define MAIN_STATE   0x03001992u   /* in a level: 1 playing, 0x0D pause. Also 1 at character select, hence the others */
#define CUR_LEVEL    0x03001160u   /* 0..19 = 1-1 .. 7-2 */
#define LIVES        0x03001161u
#define CHARACTER    0x03001C70u   /* 0 Mario, 1 Peach, 2 Toad, 3 Luigi */

uint32_t sma_t[16];                /* T_TIME, T_DEATHS, T_CLEARED, T_WHO_LO, T_WHO_HI, T_WARPED, T_FLAGS */
static int last_level = -1, last_lives = -1, last_char;

void sma_track_reset(void) { last_level = -1; last_lives = -1; }
void sma_track_clear(void) { memset(sma_t, 0, sizeof sma_t); sma_track_reset(); }

static void cleared(int lv, int ch) {
  if (lv < 0 || lv > 19) return;
  sma_t[2] |= 1u << lv;
  int bit = lv * 2;
  uint32_t *w = bit < 32 ? &sma_t[3] : &sma_t[4]; bit &= 31;
  *w = (*w & ~(3u << bit)) | ((uint32_t)(ch & 3) << bit);
}

void sma_track(void) {
  if (gba_peek(MODE) != 3 || gba_peek(SUB_MODE) != 1 || gba_peek(MAIN_STATE) != 1) return;
  sma_t[0]++;
  int lv = gba_peek(CUR_LEVEL), li = gba_peek(LIVES), ch = gba_peek(CHARACTER) & 3;
  if (last_lives >= 0 && li == last_lives - 1) sma_t[1]++;
  if (last_level >= 0 && lv != last_level && lv <= 19) {
    if (lv == last_level + 1) cleared(last_level, last_char);
    else if (lv > last_level + 1) {        /* warp jar: the level left counts as cleared, the ones skipped as warped */
      cleared(last_level, last_char);
      for (int k = last_level + 1; k < lv; k++) sma_t[5] |= 1u << k;
    }
  }
  if (lv == 19) sma_t[6] |= 1;             /* reached 7-2 */
  last_level = lv; last_lives = li; last_char = ch;
}

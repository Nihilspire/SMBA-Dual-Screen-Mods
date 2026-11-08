/* sma.h - Super Mario Advance (USA, Europe) RAM and ROM map for the 3DS port: the same addresses as assets/link/sma.js and
 * Gba.java (see RAM-MAP.md), read straight from the emulated machine. */
#pragma once
#include <stdint.h>
#include "../gbacore.h"

#define P(a) gba_peek((uint32_t)(a))
static inline int P16(uint32_t a) { return P(a) | P(a + 1) << 8; }

#define SMA_CRC 0x1E4C6D6Au

/* game state: mode 19B0 (2 menus, 3 SMB2, 4 Mario Bros.), sub-mode 1991, state 1992 */
static inline int S_mainState(void) { return P(0x03001992); }
static inline int S_gameMode(void) { return P(0x030019B0); }
static inline int S_subMode(void) { return P(0x03001991); }
static inline int S_inLevel(void) { return S_gameMode() == 3 && S_subMode() == 1; }
static inline int S_playing(void) { return S_inLevel() && S_mainState() == 1; }
static inline int S_charSelect(void) { return S_gameMode() == 3 && S_subMode() == 0 && S_mainState() == 1; }
static inline int S_bonusChance(void) { return S_gameMode() == 3 && S_subMode() == 2; }
static inline int S_ending(void) { return S_gameMode() == 3 && S_subMode() == 4; }
static inline int S_charClears(int c) { return P(0x03001148 + c); }
static inline int S_gameOver(void) { int s = S_mainState(); return S_inLevel() && s >= 4 && s <= 8 && s != 5; }
static inline int S_playerState(void) { return P(0x03001C6F); }
static inline int S_dying(void) { return S_playerState() == 7; }
static inline int S_charge(void) { return P(0x03001C7F); }
static inline int S_cursorChar(void) { return P(0x03001E01) & 3; }
static inline int S_level(void) { return P(0x03001160); }
static inline int S_lives(void) { return P(0x03001161); }
static inline uint64_t S_score(void) { return (uint64_t)((uint32_t)P16(0x0300114C) + ((uint32_t)P16(0x0300114E) << 16)) * 10; }
static inline int S_aceSaved(int L) {
    int v = 0;
    for (int i = 0; i < 5; i++) { int k = L * 5 + i; v |= ((P(0x03001150 + (k >> 3)) >> (k & 7)) & 1) << i; }
    return v;
}
static inline int S_cleared(int L) { return (P(0x0300115D + (L >> 3)) >> (L & 7)) & 1; }
static inline int S_clearedCount(void) { int n = 0; for (int L = 0; L < 20; L++) n += S_cleared(L); return n; }
static inline int S_aceTotal(void) { int n = 0; for (int i = 0; i < 100; i++) if ((P(0x03001150 + (i >> 3)) >> (i & 7)) & 1) n++; return n; }
static inline int S_area(void) { return P(0x030019EC); }
static inline int S_areaNo(void) { return P(0x030019FE); }
static inline int S_pages(void) { int p = P(0x03001A06) + 1; return p < 10 ? p : 10; }
static inline int S_vertical(void) { return P(0x0300116F) != 0; }
static inline int S_coins(void) { return P(0x03001A07); }
static inline int S_cherries(void) { return P(0x03001A26); }
static inline int S_aceLevel(void) { return P(0x03001C35) & 31; }
static inline int S_yoshi(void) {
    if (!P(0x03001162)) return 0;
    int i = P(0x030019CC);
    return i < 0x60 && P(0x030019CE + i) == 0x14;
}
static inline int S_eggsVisit(void) { return P(0x03001C37) & 3; }
static inline int S_eggsFile(int L) { int k = L * 2; return (P(0x03001140 + (k >> 3)) >> (k & 7)) & 3; }
static inline int S_eggTotal(void) { int n = 0; for (int i = 0; i < 40; i++) n += (P(0x03001140 + (i >> 3)) >> (i & 7)) & 1; return n; }
static inline int S_mushIndex(int t) { return t == 0x23 ? 0 : t == 0x24 ? 1 : t == 0x93 || t == 0x9D ? 2 : -1; }
static inline int S_eggIndex(int t) { return t == 0x94 ? 0 : t == 0x9E ? 1 : -1; }
static inline int S_isMushSpot(int t) { return S_mushIndex(t) >= 0; }
static inline int S_isEggSpot(int t) { return S_eggIndex(t) >= 0; }
static inline int S_quadMode(void) {
    if (P(0x03001A4B) == 1) return 2;
    return P16(0x030019FC) == 3 && P(0x03001A29) == 0 ? 1 : 0;
}
static inline int S_retile(int id, int mode, int e) {
    if (!mode || id <= 0x26D || id > 0x2C5) return e;
    return mode == 2 ? (e & 0x1FF) | 0x6000 : (e & 0xFF) | 0x2200;
}
static inline int S_px(void) { return P16(0x03001C4A); }
static inline int S_py(void) { return P16(0x03001C4E); }
static inline int S_character(void) { return P(0x03001C70) & 3; }
static inline int S_holding(void) { return P(0x03001C74); }
static inline int S_health(void) { int h = P(0x03001C76); return h >= 0xF0 ? 0 : (h >> 4) + 1; }
static inline int S_maxHearts(void) { int m = 2 + P(0x03001C77); return m < 4 ? m : 4; }
static inline int S_oneUpTaken(void) { return P(0x03001A3E) != 0; }
static inline int S_star(void) { return P(0x03001C88); }
static inline int S_stopwatch(void) { return P(0x03001CAC); }
static inline int S_subspace(void) { return P(0x03001CCF); }
static inline int S_extraPage(void) { return S_inLevel() && S_mainState() == 0x0A; }
static inline int S_pot(void) { return P(0x03001A29) != 0; }
static inline int S_mbLeft(void) { return P(0x03001DEE); }
static inline int S_mbRound(void) { return P(0x03001DFC); }
static inline uint32_t S_mbScore(void) { return (uint32_t)P16(0x03001E10) + ((uint32_t)P16(0x03001E12) << 16); }
static inline int S_mbLives(void) { return P(0x03001E35); }

/* ROM offsets */
#define ROM_FONT        0x2F3D7C
#define FONT_BYTES      (48 * 32)
#define ROM_METATILE_PTRS 0x3400E0
#define ROM_OBJ_TABLE   0x364D84
#define ROM_CLASS_TABLE 0x362A47
#define ROM_NO_MUSH     0x3E75D4
#define ACE_FIRST       0x6F
#define OBJ_KEY         0x43
#define OBJ_CRYSTAL     0x4A
#define OBJ_EXIT        0x49
#define SKY_TABLE       0x02002B20
#define LOGO_TILES      0x32DB10
#define LOGO_MAP        0x3380D0
#define LOGO_PAL        0x332B10
#define SHEET_FIRST     512
#define SHEET_LEN       (102 * 32)

/* playtest and tracker */
#define A_HEALTH   0x03001C76
#define A_STAR     0x03001C88
#define A_WATCH    0x03001CAC
#define A_LIVES    0x03001161
#define A_CHERRIES 0x03001A26
#define A_LEVEL    0x03001160
#define A_TILESET  0x030019FC
#define GBA_FPS    59.7275
extern uint32_t sma_t[16];     /* smatrack.c: time deaths cleared whoLo whoHi warped flags */

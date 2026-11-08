/* topgpu.h - the top screen on the GPU: see topgpu.c. */
#pragma once
#include <stdint.h>
#define TG_TEXT_H 12
typedef struct {
    int game;                     /* draw the game's picture */
    int x, y, w, h;               /* where */
    int smooth;                   /* bilinear filtering */
    int textW[2], textX[2], textY[2];   /* the FPS line and the message line; width 0 is none */
} TgFrame;
int tg_init(void);
void tg_fini(void);
void tg_border(const uint16_t *b400);        /* 400x240 row-major, kept until the next call */
void tg_game(void *lin256);                  /* 256 stride x 160 rows of RGB565 in linear memory: only the left 240 are used */
void tg_text(int row, const uint16_t *pixels, int w);   /* row 0 or 1: TG_TEXT_H rows at stride 256 */
void tg_present(const TgFrame *f);

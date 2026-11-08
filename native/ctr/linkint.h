/* linkint.h - what linkscr.c (the pages) and linkmap.c (the map) share. */
#pragma once
#include "dual3ds.h"
#include "sma.h"

typedef struct { int x, y, w, h; } Box;
typedef struct { uint32_t white, cream, parch, orange, brown, dbrown, red, dred, green, dgreen, blue, lblue, grey, navy, night, yellow, pink, black, edge, line, title, big; } Cols;
typedef struct { uint32_t red, blue, green, muted, gold; } TCols;
extern Cols C;
extern TCols T;
extern const uint8_t *rom;
extern size_t romLen;
extern int tickN;

/* the last picture of the game in play (the Android page's Native.video): VRAM | PAL | OAM | IO */
#define V_PAL 0x18000
#define V_OAM 0x18400
#define V_IO  0x18800
extern uint8_t video[0x18860];
extern int haveVideo;
extern uint8_t lvl[5280];
extern int haveLvl;
static inline int vu16(int o) { return video[o] | video[o + 1] << 8; }

/* linkmap.c */
typedef struct { int vert, pages, page0; } Geo;
extern Geo geo;
extern Box mapBox;
extern int mapMoved;
void map_init(void);
void map_snapshot(void);
void map_update_geo(void);
void map_mark_far(void);
void map_draw(Box inner);
void map_work(void);
void map_session(void);
void map_zoom_next(void);
void map_pan_start(void);
void map_pan_move(float dx, float dy);
void map_pan_end(void);
int map_cell(int c, int r);
void map_area_size(int *cols, int *rows);
void map_camera(int *cx, int *cy);
const Img *icon_src(int name);
const Img *icon_at(int name, int size);
int draw_icon(int name, int x, int y, int size, int alpha);
int has_icons(void);
void live_load(const char *dir);
void live_grab(const char *dir);
int still_there(int t);
int obj_count(void);
void obj_get(int i, int *type, int *x, int *y);
int ace_here(int *left);
int grass_icon(int t);
const char *grass_letter(int t);
const char *grass_name(int t);
int is_grass(int t);
extern const char *const CHARS[4];
extern const uint32_t CHAR_COL[4];

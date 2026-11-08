/* canvas.c - everything the 3DS port's screens draw with: rectangles, images with alpha and the game's own font, onto one RGB565
 * buffer at a time (the same few calls as the Android pages' canvas). Row-major; main.c turns the buffers for the 3DS's framebuffers. */
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include "dual3ds.h"

static uint16_t *cvb; static int cvw, cvh, cx0, cy0, cx1, cy1;
int font_solid;

void cv_noclip(void) { cx0 = cy0 = 0; cx1 = cvw; cy1 = cvh; }
void cv_target(uint16_t *b, int w, int h) { cvb = b; cvw = w; cvh = h; cv_noclip(); }
void cv_clip(int x, int y, int w, int h) {
    cx0 = x < 0 ? 0 : x; cy0 = y < 0 ? 0 : y;
    cx1 = x + w > cvw ? cvw : x + w; cy1 = y + h > cvh ? cvh : y + h;
    if (cx1 < cx0) cx1 = cx0;
    if (cy1 < cy0) cy1 = cy0;
}

/* a 0-32 */
static inline uint16_t blend(uint16_t d, uint16_t s, unsigned a) {
    uint32_t dd = (d | (uint32_t)d << 16) & 0x07E0F81Fu, ss = (s | (uint32_t)s << 16) & 0x07E0F81Fu;
    uint32_t r = ((((ss - dd) * a) >> 5) + dd) & 0x07E0F81Fu;
    return (uint16_t)(r | r >> 16);
}
static inline unsigned a32(int alpha) { return (unsigned)(alpha * 32 + 127) / 255; }

void rect(int x, int y, int w, int h, uint32_t rgb) {
    int x1 = x + w, y1 = y + h; uint16_t c = rgb565(rgb);
    if (x < cx0) x = cx0;
    if (y < cy0) y = cy0;
    if (x1 > cx1) x1 = cx1;
    if (y1 > cy1) y1 = cy1;
    if (x >= x1) return;
    for (; y < y1; y++) { uint16_t *d = cvb + y * cvw + x; for (int i = x1 - x; i > 0; i--) *d++ = c; }
}
void rectA(int x, int y, int w, int h, uint32_t rgb, int alpha) {
    int x1 = x + w, y1 = y + h; uint16_t c = rgb565(rgb); unsigned a = a32(alpha);
    if (x < cx0) x = cx0;
    if (y < cy0) y = cy0;
    if (x1 > cx1) x1 = cx1;
    if (y1 > cy1) y1 = cy1;
    if (x >= x1) return;
    for (; y < y1; y++) { uint16_t *d = cvb + y * cvw + x; for (int i = x1 - x; i > 0; i--, d++) *d = blend(*d, c, a); }
}
void rrect(int x, int y, int w, int h, uint32_t rgb) { rect(x + 1, y, w - 2, h, rgb); rect(x, y + 1, w, h - 2, rgb); }

static void blitCore(const Img *im, int sx, int sy, int sw, int sh, int x, int y, int alpha) {
    if (!im) return;
    int x1 = x + sw, y1 = y + sh;
    if (x < cx0) { sx += cx0 - x; x = cx0; }
    if (y < cy0) { sy += cy0 - y; y = cy0; }
    if (x1 > cx1) x1 = cx1;
    if (y1 > cy1) y1 = cy1;
    if (x >= x1 || y >= y1) return;
    int n = x1 - x; unsigned ga = a32(alpha);
    for (; y < y1; y++, sy++) {
        const uint16_t *s = im->px + sy * im->w + sx; uint16_t *d = cvb + y * cvw + x;
        if (!im->a && alpha >= 255) { memcpy(d, s, n * 2); continue; }
        const uint8_t *m = im->a ? im->a + sy * im->w + sx : NULL;
        for (int i = 0; i < n; i++) {
            unsigned v = m ? m[i] : 255;
            if (!v) continue;
            if (v == 255 && alpha >= 255) d[i] = s[i];
            else d[i] = blend(d[i], s[i], alpha >= 255 ? a32(v) : (a32(v) * ga + 16) >> 5);
        }
    }
}
void blit(const Img *im, int x, int y) { if (im) blitCore(im, 0, 0, im->w, im->h, x, y, 255); }
void blitA(const Img *im, int x, int y, int alpha) { if (im) blitCore(im, 0, 0, im->w, im->h, x, y, alpha); }
void blitSub(const Img *im, int sx, int sy, int sw, int sh, int dx, int dy) { blitCore(im, sx, sy, sw, sh, dx, dy, 255); }
void blitShadow(const Img *im, int x, int y, uint32_t rgb, int alpha) {
    if (!im) return;
    uint16_t c = rgb565(rgb); unsigned ga = a32(alpha);
    int x0 = x < cx0 ? cx0 : x, x1 = x + im->w > cx1 ? cx1 : x + im->w, y0 = y < cy0 ? cy0 : y, y1 = y + im->h > cy1 ? cy1 : y + im->h;
    for (int yy = y0; yy < y1; yy++) {
        const uint8_t *m = im->a ? im->a + (yy - y) * im->w : NULL; uint16_t *d = cvb + yy * cvw;
        for (int xx = x0; xx < x1; xx++) {
            unsigned v = m ? m[xx - x] : 255;
            if (v) d[xx] = blend(d[xx], c, (a32(v) * ga + 16) >> 5);
        }
    }
}
void blitRaw(const uint16_t *src, int stride, int w, int h, int x, int y) {
    int sx = 0, sy = 0, x1 = x + w, y1 = y + h;
    if (x < cx0) { sx = cx0 - x; x = cx0; }
    if (y < cy0) { sy = cy0 - y; y = cy0; }
    if (x1 > cx1) x1 = cx1;
    if (y1 > cy1) y1 = cy1;
    if (x >= x1) return;
    for (; y < y1; y++, sy++) memcpy(cvb + y * cvw + x, src + sy * stride + sx, (x1 - x) * 2);
}
/* nearest-neighbour, for thumbnails */
void blitScaled(const uint16_t *src, int sw, int sh, int x, int y, int w, int h) {
    for (int yy = 0; yy < h; yy++) {
        int dy = y + yy; if (dy < cy0 || dy >= cy1) continue;
        const uint16_t *s = src + (yy * sh / h) * sw; uint16_t *d = cvb + dy * cvw;
        for (int xx = 0; xx < w; xx++) { int dx = x + xx; if (dx >= cx0 && dx < cx1) d[dx] = s[xx * sw / w]; }
    }
}

Img *img_new(int w, int h, int alpha) {
    Img *im = malloc(sizeof *im);
    im->w = (short)w; im->h = (short)h;
    im->px = calloc((size_t)w * h, 2);
    im->a = alpha ? calloc((size_t)w, h) : NULL;
    return im;
}
void img_free(Img *im) { if (im) { free(im->px); free(im->a); free(im); } }
void img_pixel(Img *im, int x, int y, uint32_t rgb) {
    if (x < 0 || y < 0 || x >= im->w || y >= im->h) return;
    im->px[y * im->w + x] = rgb565(rgb);
    if (im->a) im->a[y * im->w + x] = 255;
}

/* Area-average resampling (link.js resample): every source pixel counts, so outlines thin rather than vanish. */
static int taps(int n, int n2, short *from, float *wt, int *start) {
    float k = (float)n / n2; int c = 0;
    for (int i = 0; i < n2; i++) {
        float a = i * k, b = a + k;
        start[i] = c;
        for (int j = (int)floorf(a); j < n && j < (int)ceilf(b); j++) {
            float lo = a > j ? a : (float)j, hi = b < j + 1 ? b : (float)(j + 1), w = hi - lo;
            if (w > 1e-6f) { from[c] = (short)j; wt[c++] = w / k; }
        }
    }
    start[n2] = c;
    return c;
}
Img *img_resample(const Img *src, int W2, int H2) {
    int w = src->w, h = src->h;
    short fx[256], fy[256]; float wx[256], wy[256]; int sx[66], sy[66];
    if (W2 < 1) W2 = 1;
    if (H2 < 1) H2 = 1;
    if (w > 64 || h > 64 || W2 > 64 || H2 > 64) return NULL;
    taps(w, W2, fx, wx, sx); taps(h, H2, fy, wy, sy);
    float *mid = malloc(sizeof(float) * 4 * W2 * h);
    for (int y = 0; y < h; y++) for (int x = 0; x < W2; x++) {
        float r = 0, g = 0, b = 0, a = 0;
        for (int t = sx[x]; t < sx[x + 1]; t++) {
            int o = y * w + fx[t]; unsigned c = src->px[o];
            float wa = wx[t] * (src->a ? src->a[o] / 255.0f : 1.0f);
            r += (c >> 11) * wa; g += ((c >> 5) & 63) * wa; b += (c & 31) * wa; a += wa;
        }
        float *q = mid + (y * W2 + x) * 4; q[0] = r; q[1] = g; q[2] = b; q[3] = a;
    }
    Img *o = img_new(W2, H2, 1);
    for (int y = 0; y < H2; y++) for (int x = 0; x < W2; x++) {
        float r = 0, g = 0, b = 0, a = 0;
        for (int t = sy[y]; t < sy[y + 1]; t++) {
            const float *q = mid + (fy[t] * W2 + x) * 4; float v = wy[t];
            r += q[0] * v; g += q[1] * v; b += q[2] * v; a += q[3] * v;
        }
        if (a > 0.01f) {
            int R = (int)(r / a + 0.5f), G = (int)(g / a + 0.5f), B = (int)(b / a + 0.5f), A = (int)(a * 255 + 0.5f);
            o->px[y * W2 + x] = (uint16_t)(R << 11 | G << 5 | B);
            o->a[y * W2 + x] = (uint8_t)(A > 255 ? 255 : A);
        }
    }
    free(mid);
    return o;
}

/* ------------------------------------------------------------------ the font: SMA's 8x8 from the player's ROM (gbagfx.js) */
static uint8_t glyph[128][64]; static uint8_t hasGlyph[128];
static const struct { char c; uint8_t r[8]; } EXTRA[] = {
    { ':', { 0, 24, 24, 0, 24, 24, 0, 0 } }, { '/', { 2, 6, 12, 24, 48, 96, 64, 0 } }, { '!', { 24, 24, 24, 24, 24, 0, 24, 0 } },
    { '%', { 98, 102, 12, 24, 48, 102, 70, 0 } }, { '+', { 0, 24, 24, 126, 24, 24, 0, 0 } }, { '>', { 32, 48, 56, 60, 56, 48, 32, 0 } },
    { '<', { 4, 12, 28, 60, 28, 12, 4, 0 } }, { '\'', { 24, 24, 48, 0, 0, 0, 0, 0 } }, { '(', { 12, 24, 48, 48, 48, 24, 12, 0 } },
    { ')', { 48, 24, 12, 12, 12, 24, 48, 0 } }, { '#', { 0, 0, 0, 0, 0, 0, 0, 0 } }, { '^', { 24, 60, 126, 24, 24, 24, 24, 0 } },
    { 'v', { 24, 24, 24, 24, 126, 60, 24, 0 } }, { '*', { 0, 108, 254, 254, 124, 56, 16, 0 } }, { '=', { 0, 0, 126, 0, 126, 0, 0, 0 } } };
static const char FONT_MAP[] = "0123456789ABCDEFGHIJKLMNOPxRSTUVWXYZ-?.,";
void font_load(const uint8_t *d) {
    memset(hasGlyph, 0, sizeof hasGlyph);
    for (int t = 0; FONT_MAP[t]; t++) {
        uint8_t *m = glyph[(int)FONT_MAP[t]];
        for (int i = 0; i < 32; i++) {
            int b = d[t * 32 + i], lo = b & 15, hi = b >> 4;
            m[i * 2] = lo == 15 ? 1 : lo ? 2 : 0; m[i * 2 + 1] = hi == 15 ? 1 : hi ? 2 : 0;
        }
        hasGlyph[(int)FONT_MAP[t]] = 1;
    }
    memcpy(glyph['Q'], glyph['O'], 64); hasGlyph['Q'] = 1;
    for (size_t k = 0; k < sizeof EXTRA / sizeof EXTRA[0]; k++) {
        uint8_t *m = glyph[(int)EXTRA[k].c];
        for (int y = 0; y < 8; y++) for (int x = 0; x < 8; x++) m[y * 8 + x] = (EXTRA[k].r[y] >> (7 - x)) & 1;
        hasGlyph[(int)EXTRA[k].c] = 1;
    }
}
uint32_t shade24(uint32_t c, float k) {
    int v[3] = { (int)(c >> 16) & 255, (int)(c >> 8) & 255, (int)c & 255 }, o = 0;
    for (int i = 0; i < 3; i++) {
        float f = k < 1 ? v[i] * k : v[i] + (255 - v[i]) * (k - 1);
        int n = (int)(f + 0.5f); o = o << 8 | (n < 0 ? 0 : n > 255 ? 255 : n);
    }
    return (uint32_t)o;
}
void textS(const char *s, int x, int y, uint32_t rgb, int scale) {
    uint16_t ink = rgb565(rgb), dark = font_solid ? ink : rgb565(shade24(rgb, 0.6f));
    for (; *s; s++, x += 8 * scale) {
        int ch = (unsigned char)*s; if (ch >= 128) continue;
        if (!hasGlyph[ch] && ch >= 'a' && ch <= 'z') ch -= 32;
        if (!hasGlyph[ch]) continue;
        if (x + 8 * scale <= cx0 || x >= cx1 || y + 8 * scale <= cy0 || y >= cy1) continue;
        const uint8_t *m = glyph[ch];
        for (int gy = 0; gy < 8; gy++) for (int gx = 0; gx < 8; gx++) {
            int v = m[gy * 8 + gx]; if (!v) continue;
            uint16_t c = v == 1 ? ink : dark;
            for (int sy = 0; sy < scale; sy++) {
                int py = y + gy * scale + sy; if (py < cy0 || py >= cy1) continue;
                uint16_t *d = cvb + py * cvw;
                for (int sx = 0; sx < scale; sx++) { int px = x + gx * scale + sx; if (px >= cx0 && px < cx1) d[px] = c; }
            }
        }
    }
}
void text(const char *s, int x, int y, uint32_t rgb) { textS(s, x, y, rgb, 1); }
void textC(const char *s, int cx, int y, uint32_t rgb) { text(s, cx - (int)strlen(s) * 4, y, rgb); }
void textCS(const char *s, int cx, int y, uint32_t rgb, int scale) { textS(s, cx - (int)strlen(s) * 4 * scale, y, rgb, scale); }
void textR(const char *s, int rx, int y, uint32_t rgb) { text(s, rx - (int)strlen(s) * 8, y, rgb); }

void fmt_hms(char *o, unsigned sec) {
    unsigned h = sec / 3600, m = (sec / 60) % 60, s = sec % 60;
    char t[16]; int n = 0;
    if (h == 0) t[n++] = '0';
    while (h) { t[n++] = (char)('0' + h % 10); h /= 10; }
    while (n) *o++ = t[--n];
    *o++ = ':'; *o++ = (char)('0' + m / 10); *o++ = (char)('0' + m % 10);
    *o++ = ':'; *o++ = (char)('0' + s / 10); *o++ = (char)('0' + s % 10); *o = 0;
}

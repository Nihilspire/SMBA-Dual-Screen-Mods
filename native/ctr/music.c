/* music.c - the launcher's music: "Curtain Call", the original tune written for Super Mario Advance Dual (assets/menu/music.js), played
 * on a small software synth instead of WebAudio. The same score and the same instruments: music box, a pad of detuned triangles through
 * a low-pass, sine bass, a shaker, a gentle low-pass over everything and a soft room. The room is a Schroeder reverb here, because the
 * page's 2.2 s convolution would take the whole CPU. Launcher only. */
#include <math.h>
#include <stdlib.h>
#include <string.h>
#include "dual3ds.h"

#define BPM 84.0
#define BAR 16
#define NSTEPS (16 * BAR)
#define SWING 0.28
#define LEVEL 1.7f
#define MAXV 48
#define SINE_N 4096
typedef enum { V_OFF, V_BOX, V_PAD, V_BASS, V_SHAKE } Kind;
typedef struct {
    Kind kind; uint32_t ph[8], inc[8]; int n;
    double t, len; float vol, g, d, p2g, p2d, att, rel, tail; float lp1, lp2, hp;
    int delay;
} Voice;
static Voice v[MAXV];
static float sine[SINE_N]; static int ready;
static double fs, stepLen, nextStep; static int step, playing; static float master, masterTarget, masterSlope;
static float lpA, lpB;                                   /* the bus low-pass (biquad-ish: two poles) */
#define NCOMB 4
#define NAP 2
static const short COMB_T[2][NCOMB] = { { 1116, 1188, 1277, 1356 }, { 1139, 1211, 1300, 1379 } }, AP_T[2][NAP] = { { 225, 556 }, { 248, 579 } };
static float *comb[2][NCOMB], *ap[2][NAP]; static short ci[2][NCOMB], ai[2][NAP];
static float combLp[2][NCOMB];
static uint32_t rng = 0x12345;

static inline float noise1(void) { rng = rng * 1664525u + 1013904223u; return (float)(int32_t)rng / 2147483648.0f; }
static double hz(double m) { return 440.0 * pow(2.0, (m - 69) / 12); }
static uint32_t incOf(double f) { return (uint32_t)(f / fs * 4294967296.0); }
static Voice *voice(Kind k, double delay) {
    for (int i = 0; i < MAXV; i++) if (v[i].kind == V_OFF) { memset(&v[i], 0, sizeof v[i]); v[i].kind = k; v[i].delay = (int)(delay * fs); return &v[i]; }
    return NULL;
}
static void musicBox(int m, double delay, float vol, float ring) {
    Voice *w = voice(V_BOX, delay); if (!w) return;
    w->inc[0] = incOf(hz(m)); w->inc[1] = incOf(hz(m) * 4.01); w->vol = vol; w->len = ring + 0.05;
    w->att = 0.003f; w->d = powf(0.0006f / vol, (float)(1.0 / (ring * fs)));
    w->g = 0; w->p2g = 0.22f; w->p2d = powf(0.01f / 0.22f, (float)(1.0 / (0.25 * fs)));
}
static void padChord(const int *m, double delay, float len, float vol) {
    Voice *w = voice(V_PAD, delay); if (!w) return;
    for (int i = 0; i < 4; i++) { w->inc[i * 2] = incOf(hz(m[i] - 0.07)); w->inc[i * 2 + 1] = incOf(hz(m[i] + 0.07)); w->ph[i * 2 + 1] = (uint32_t)i * 0x31000000u; }
    w->n = 8; w->vol = vol; w->len = len; w->att = 0.9f; w->rel = 0.9f;
}
static void bass(int m, double delay, float len, float vol) {
    Voice *w = voice(V_BASS, delay); if (!w) return;
    w->inc[0] = incOf(hz(m)); w->vol = vol; w->len = len; w->att = 0.02f; w->d = powf(0.35f, (float)(1.0 / (len * 0.6 * fs))); w->g = 1;
}
static void shaker(double delay, float vol) {
    Voice *w = voice(V_SHAKE, delay); if (!w) return;
    w->vol = vol; w->len = 0.12; w->att = 0.01f; w->d = powf(0.0005f / vol, (float)(1.0 / (0.08 * fs))); w->g = 1;
}
/* the score: 16 bars of 4/4 with a light shuffle */
static const int8_t CH[10][4] = { { 58, 62, 65, 69 }, { 55, 58, 62, 69 }, { 51, 55, 58, 62 }, { 53, 58, 60, 63 }, { 50, 53, 57, 60 }, { 48, 51, 55, 58 }, { 53, 57, 60, 63 }, { 55, 58, 62, 65 }, { 50, 53, 58, 62 }, { 51, 54, 58, 60 } };
static const int8_t ROOTS[10] = { 46, 43, 39, 41, 38, 36, 41, 43, 38, 39 };
enum { Bbmaj7, Gm9, Ebmaj7, F7sus, Dm7, Cm7, F7, Gm7, BbD, Ebm6 };
static const int8_t PROG[16] = { Bbmaj7, Gm9, Ebmaj7, F7sus, Bbmaj7, Dm7, Cm7, F7, Gm7, Ebmaj7, BbD, Cm7, Ebm6, F7sus, Bbmaj7, Bbmaj7 };
static const int8_t MEL[][3] = {
    { 0, 0, 74 }, { 0, 6, 77 }, { 0, 8, 79 }, { 0, 12, 77 }, { 1, 0, 74 }, { 1, 4, 72 }, { 1, 8, 70 }, { 2, 0, 75 }, { 2, 4, 79 }, { 2, 8, 82 }, { 2, 12, 79 }, { 3, 0, 77 }, { 3, 10, 72 },
    { 4, 0, 74 }, { 4, 6, 77 }, { 4, 8, 81 }, { 4, 12, 79 }, { 5, 0, 77 }, { 5, 4, 74 }, { 5, 8, 72 }, { 5, 12, 69 }, { 6, 0, 70 }, { 6, 4, 75 }, { 6, 8, 79 }, { 6, 12, 77 }, { 7, 0, 75 }, { 7, 8, 72 }, { 7, 12, 69 },
    { 8, 0, 70 }, { 8, 4, 74 }, { 8, 8, 77 }, { 8, 12, 82 }, { 9, 0, 79 }, { 9, 6, 77 }, { 9, 8, 75 }, { 10, 0, 74 }, { 10, 4, 77 }, { 10, 8, 74 }, { 10, 12, 70 }, { 11, 0, 72 }, { 11, 6, 75 }, { 11, 8, 79 },
    { 12, 0, 78 }, { 12, 8, 75 }, { 13, 0, 77 }, { 13, 6, 72 }, { 13, 8, 74 }, { 13, 12, 75 }, { 14, 0, 74 }, { 14, 8, 70 }, { 15, 0, 70 } };
static void schedule(int s, double delay) {
    int bar = (s / BAR) % 16, st = s % BAR, ch = PROG[bar]; const int8_t *t8 = CH[ch]; int tones[4] = { t8[0], t8[1], t8[2], t8[3] };
    if (st % 2 == 1) delay += stepLen * SWING;
    if (st == 0) padChord(tones, delay, (float)(stepLen * BAR * 1.05), 0.010f);
    if (st == 0) bass(ROOTS[ch], delay, (float)(stepLen * 7), 0.08f);
    if (st == 8) bass(ROOTS[ch] + (bar % 2 ? 7 : 12), delay, (float)(stepLen * 6), 0.06f);
    if (st == 6 || st == 14) musicBox(tones[st == 6 ? 1 : 2] + 12, delay, 0.014f, 0.9f);   /* soft comping on the "and" of 2 and 4 */
    if ((st == 4 || st == 12) && bar % 8 != 7) shaker(delay, 0.013f);
    if ((st == 6 || st == 14) && bar % 4 == 3) shaker(delay, 0.008f);
    if (st == 15 && bar % 2 == 1) musicBox(tones[3] + 24, delay, 0.008f, 0.6f);             /* a few stars */
    for (size_t i = 0; i < sizeof MEL / sizeof MEL[0]; i++) if (MEL[i][0] == bar && MEL[i][1] == st) musicBox(MEL[i][2], delay, 0.05f, bar == 15 ? 3.2f : 1.7f);
}
void music_play(void) {
    if (!ready) {
        for (int i = 0; i < SINE_N; i++) sine[i] = sinf(2 * 3.14159265f * i / SINE_N);
        for (int c = 0; c < 2; c++) {
            for (int i = 0; i < NCOMB; i++) comb[c][i] = calloc(COMB_T[c][i], sizeof(float));
            for (int i = 0; i < NAP; i++) ap[c][i] = calloc(AP_T[c][i], sizeof(float));
        }
        ready = 1;
    }
    if (playing) return;
    playing = 1; step = 0; nextStep = 0.08; master = masterTarget = 0; fs = 0;
    memset(v, 0, sizeof v);
}
void music_stop(void) { if (playing) playing = 2; }     /* fades out over 0.4 s, then stops */
void music_cut(void) { playing = 0; }                   /* the game is starting */
int music_active(void) { return playing != 0; }
void music_render(int16_t *out, int frames, double rate) {
    if (!playing) { memset(out, 0, (size_t)frames * 4); return; }
    if (fs != rate) { fs = rate; stepLen = 60.0 / BPM / 4; masterSlope = LEVEL / (float)(3.0 * fs); }
    float lpCoef = 1 - expf(-2 * 3.14159265f * 3200 / (float)fs), padCoef = 1 - expf(-2 * 3.14159265f * 900 / (float)fs), hpCoef = expf(-2 * 3.14159265f * 6500 / (float)fs);
    float fadeOut = LEVEL / (float)(0.4 * fs);
    double dt = 1.0 / fs;
    for (int f = 0; f < frames; f++) {
        if (playing == 1) { nextStep -= dt; while (nextStep <= 0.02) { schedule(step, nextStep < 0 ? 0 : nextStep); step = (step + 1) % NSTEPS; nextStep += stepLen; } }
        float mix = 0;
        for (int i = 0; i < MAXV; i++) {
            Voice *w = &v[i];
            if (w->kind == V_OFF) continue;
            if (w->delay > 0) { w->delay--; continue; }
            float t = (float)w->t, a; w->t += dt;
            if (w->t >= w->len) { w->kind = V_OFF; continue; }
            switch (w->kind) {
            case V_BOX:
                a = t < w->att ? w->vol * t / w->att : (w->g = (w->g ? w->g * w->d : w->vol));
                { float s = sine[w->ph[0] >> 20] + sine[w->ph[1] >> 20] * w->p2g;
                  w->ph[0] += w->inc[0]; w->ph[1] += w->inc[1];
                  if (w->p2g > 0.01f) w->p2g *= w->p2d;
                  mix += s * a; }
                break;
            case V_PAD: {
                float s = 0;
                for (int k = 0; k < 8; k++) {
                    uint32_t p = w->ph[k]; w->ph[k] = p + w->inc[k];
                    float tri = (float)(int32_t)p / 2147483648.0f; tri = (tri < 0 ? -tri : tri) * 2 - 1;
                    s += tri;
                }
                float e = (float)w->len - t;
                a = t < w->att ? t / w->att : e < w->rel ? e / w->rel : 1;
                w->lp1 += (s - w->lp1) * padCoef; w->lp2 += (w->lp1 - w->lp2) * padCoef;
                mix += w->lp2 * a * w->vol;
                break;
            }
            case V_BASS: {
                float s = sine[w->ph[0] >> 20]; w->ph[0] += w->inc[0];
                float rt = (float)w->len * 0.4f, e = (float)w->len - t;
                if (t >= w->att && t < (float)w->len * 0.6f) w->g *= w->d;
                a = t < w->att ? t / w->att : e < rt ? w->g * e / rt : w->g;
                mix += s * a * w->vol;
                break;
            }
            case V_SHAKE: {
                float n = noise1(); w->hp = hpCoef * (w->hp + n - w->lp1); w->lp1 = n;
                if (t >= w->att) w->g *= w->d;
                a = t < w->att ? t / w->att : w->g;
                mix += w->hp * a * w->vol;
                break;
            }
            default: break;
            }
        }
        if (playing == 1) { if (master < LEVEL) master += masterSlope; }
        else { master -= fadeOut; if (master <= 0) { master = 0; playing = 0; memset(out + f * 2, 0, (size_t)(frames - f) * 4); return; } }
        float in = mix * master;
        lpA += (in - lpA) * lpCoef; lpB += (lpA - lpB) * lpCoef;     /* the bus: gentle low-pass, then dry + a soft room */
        float wet[2];
        for (int c = 0; c < 2; c++) {
            float o = 0;
            for (int i = 0; i < NCOMB; i++) {
                float *b = comb[c][i]; int k = ci[c][i], len = COMB_T[c][i]; float y = b[k];
                combLp[c][i] += (y - combLp[c][i]) * 0.55f;
                b[k] = lpB * 0.09f + combLp[c][i] * 0.86f; o += y;
                ci[c][i] = (short)(k + 1 == len ? 0 : k + 1);
            }
            for (int i = 0; i < NAP; i++) {
                float *b = ap[c][i]; int k = ai[c][i], len = AP_T[c][i]; float y = b[k];
                b[k] = o + y * 0.5f; o = y - o * 0.5f;
                ai[c][i] = (short)(k + 1 == len ? 0 : k + 1);
            }
            wet[c] = o;
        }
        for (int c = 0; c < 2; c++) {
            float s = lpB * 0.8f + wet[c] * 0.3f;
            int q = (int)(s * 32767); out[f * 2 + c] = (int16_t)(q > 32767 ? 32767 : q < -32768 ? -32768 : q);
        }
    }
}

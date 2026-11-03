/* gbajni.c - JNI bridge for Mario Advance Dual (com.jack.smadual.Native). */
#include <jni.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <android/log.h>
#include "gbacore.h"

#define J(name) Java_com_jack_smadual_Native_##name
#define EXPORT __attribute__((visibility("default")))

void gba_log(const char *m) { __android_log_write(ANDROID_LOG_INFO, "SMADual", m); }

static int16_t abuf[16384];

EXPORT jint J(init)(JNIEnv *e, jclass c, jstring sys, jstring save) {
  const char *a = (*e)->GetStringUTFChars(e, sys, 0), *b = (*e)->GetStringUTFChars(e, save, 0);
  int r = gba_init(a, b);
  (*e)->ReleaseStringUTFChars(e, sys, a); (*e)->ReleaseStringUTFChars(e, save, b);
  return r;
}
EXPORT jint J(load)(JNIEnv *e, jclass c, jstring path) {
  const char *p = (*e)->GetStringUTFChars(e, path, 0); int r = gba_load(p);
  (*e)->ReleaseStringUTFChars(e, path, p); return r;
}
EXPORT void J(unload)(JNIEnv *e, jclass c) { gba_unload(); }
EXPORT void J(reset)(JNIEnv *e, jclass c) { gba_reset(); }
EXPORT jdouble J(fps)(JNIEnv *e, jclass c) { return gba_fps(); }
EXPORT jdouble J(rate)(JNIEnv *e, jclass c) { return gba_rate(); }
EXPORT void J(setOverclock)(JNIEnv *e, jclass c, jboolean on) { gba_set_overclock(on); }
EXPORT void J(setColorCorrection)(JNIEnv *e, jclass c, jboolean on) { gba_set_color_correction(on); }
EXPORT void J(setFrameBlend)(JNIEnv *e, jclass c, jboolean on) { gba_set_frame_blend(on); }
EXPORT jint J(lagFrames)(JNIEnv *e, jclass c) { return (jint)gba_lag_frames(); }
EXPORT jint J(saveFlush)(JNIEnv *e, jclass c) { return gba_save_flush(); }
/* Runs one frame with the given pad bits. Copies the picture into dst (direct buffer, RGB565).
 * Returns (w << 16 | h) when a new picture was produced, else 0. */
EXPORT jint J(runFrame)(JNIEnv *e, jclass c, jint pad, jobject dst) {
  gba_set_pad((unsigned)pad);
  gba_run();
  int w, h, fresh; const uint16_t *fb = gba_frame(&w, &h, &fresh);
  if (!fresh || !dst) return 0;
  void *d = (*e)->GetDirectBufferAddress(e, dst); jlong cap = (*e)->GetDirectBufferCapacity(e, dst);
  if (!d || cap < (jlong)w * h * 2) return 0;
  memcpy(d, fb, (size_t)w * h * 2);
  return (w << 16) | h;
}
EXPORT jint J(audio)(JNIEnv *e, jclass c, jshortArray out) {
  int max = (*e)->GetArrayLength(e, out); if (max > 16384) max = 16384;
  int n = gba_audio(abuf, max);
  if (n > 0) (*e)->SetShortArrayRegion(e, out, 0, n, abuf);
  return n;
}
EXPORT jint J(peek)(JNIEnv *e, jclass c, jint a) { return gba_peek((uint32_t)a); }
EXPORT void J(poke)(JNIEnv *e, jclass c, jint a, jint v) { gba_poke((uint32_t)a, (unsigned)v); }
/* Reads memory ranges "addr:len,addr:len" (hex GBA address, decimal len) and returns them as one hex string. */
#define MEM_MAX 0x30000
EXPORT jstring J(mem)(JNIEnv *e, jclass c, jstring spec) {
  const char *s = (*e)->GetStringUTFChars(e, spec, 0);
  static char out[MEM_MAX * 2 + 8]; static uint8_t tmp[MEM_MAX]; int o = 0; const char *p = s;
  static const char hx[] = "0123456789abcdef";
  while (*p) {
    char *end; uint32_t a = strtoul(p, &end, 16); unsigned n = 1;
    if (*end == ':') n = strtoul(end + 1, &end, 10);
    if (n > (unsigned)(MEM_MAX - o / 2)) n = MEM_MAX - o / 2;
    gba_read(a, tmp, n);
    for (unsigned i = 0; i < n; i++) { out[o++] = hx[tmp[i] >> 4]; out[o++] = hx[tmp[i] & 15]; }
    p = *end ? end + 1 : end;
  }
  out[o] = 0;
  (*e)->ReleaseStringUTFChars(e, spec, s);
  return (*e)->NewStringUTF(e, out);
}
/* Any memory range into a byte array (for Java-side readers: bezel theme, 3D view). */
EXPORT void J(read)(JNIEnv *e, jclass c, jint a, jbyteArray out, jint off, jint n) {
  jbyte *b = (*e)->GetByteArrayElements(e, out, 0);
  gba_read((uint32_t)a, (uint8_t *)b + off, (size_t)n);
  (*e)->ReleaseByteArrayElements(e, out, b, 0);
}
extern uint32_t sma_t[16];
void sma_track_clear(void);
void sma_track_reset(void);
/* State files: mGBA state, then "SMAT" + the tracker block (sma_t). Older/other files without it still load. */
EXPORT jint J(stateSave)(JNIEnv *e, jclass c, jstring path) {
  size_t n = gba_state_size(); if (!n) return -1;
  void *buf = malloc(n); int ok = gba_state_save(buf, n);
  const char *p = (*e)->GetStringUTFChars(e, path, 0);
  FILE *f = ok ? fopen(p, "wb") : NULL; int r = -2;
  if (f) { r = fwrite(buf, 1, n, f) == n ? 0 : -3; fwrite("SMAT", 1, 4, f); fwrite(sma_t, 1, sizeof sma_t, f); fclose(f); }
  (*e)->ReleaseStringUTFChars(e, path, p); free(buf); return r;
}
EXPORT jint J(stateLoad)(JNIEnv *e, jclass c, jstring path) {
  const char *p = (*e)->GetStringUTFChars(e, path, 0);
  FILE *f = fopen(p, "rb"); (*e)->ReleaseStringUTFChars(e, path, p);
  if (!f) return -1;
  fseek(f, 0, SEEK_END); long n = ftell(f); fseek(f, 0, SEEK_SET);
  void *buf = malloc(n); int r = -2;
  if (fread(buf, 1, n, f) == (size_t)n) {
    size_t sn = gba_state_size(), tn = 4 + sizeof sma_t;
    uint8_t *t = (uint8_t *)buf + (n - tn);
    if (n >= tn && !memcmp(t, "SMAT", 4)) { r = gba_state_load(buf, n - tn) ? 0 : -3; if (!r) memcpy(sma_t, t + 4, sizeof sma_t); }
    else { r = gba_state_load(buf, n) ? 0 : -3; if (!r) sma_track_clear(); }
    (void)sn;
  }
  fclose(f); free(buf); return r;
}
EXPORT void J(tracker)(JNIEnv *e, jclass c, jbyteArray out) { (*e)->SetByteArrayRegion(e, out, 0, sizeof sma_t, (const jbyte *)sma_t); }
EXPORT void J(trackReset)(JNIEnv *e, jclass c) { sma_track_reset(); }
EXPORT void J(setTracker)(JNIEnv *e, jclass c, jint i, jint v) { if (i >= 0 && i < 16) sma_t[i] = (uint32_t)v; }

/* First start: boots the ROM with an empty save folder (tmpdir), captures the title and pause screens (extract.c) into
 * outPath, then unloads and points battery saves back at savedir. Returns bytes written or <0. */
EXPORT jint J(extract)(JNIEnv *e, jclass c, jstring rom, jstring tmpdir, jstring savedir, jstring outPath) {
  const char *r = (*e)->GetStringUTFChars(e, rom, 0), *t = (*e)->GetStringUTFChars(e, tmpdir, 0);
  const char *s = (*e)->GetStringUTFChars(e, savedir, 0), *o = (*e)->GetStringUTFChars(e, outPath, 0);
  gba_set_savedir(t);
  int n = gba_load(r) ? -10 : 0;
  if (!n) {
    size_t cap = sma_extract_size(); uint8_t *buf = malloc(cap);
    n = sma_extract(buf, cap);
    if (n > 0) { char tmp[600]; snprintf(tmp, sizeof tmp, "%s.tmp", o); FILE *f = fopen(tmp, "wb");
      if (!f || fwrite(buf, 1, n, f) != (size_t)n) n = -11; if (f) fclose(f); if (n > 0) rename(tmp, o); }
    free(buf);
    gba_unload();
  }
  gba_set_savedir(s);
  (*e)->ReleaseStringUTFChars(e, rom, r); (*e)->ReleaseStringUTFChars(e, tmpdir, t);
  (*e)->ReleaseStringUTFChars(e, savedir, s); (*e)->ReleaseStringUTFChars(e, outPath, o);
  return n;
}

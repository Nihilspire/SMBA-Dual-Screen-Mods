/* musicdump.c - the launcher's music from music.c as a WAV, to compare with assets/menu/music.js rendered in Chromium: clang -I.. musicdump.c ../music.c -lm */
#include <stdio.h>
#include <stdlib.h>
#include <stdint.h>
#include "../dual3ds.h"
int main(int argc, char **argv) {
    int secs = argc > 2 ? atoi(argv[2]) : 50, rate = 32768;
    FILE *f = fopen(argv[1], "wb");
    uint32_t n = (uint32_t)secs * rate * 4;
    uint32_t hdr[11] = { 0x46464952, 36 + n, 0x45564157, 0x20746D66, 16, 0x00020001, (uint32_t)rate, (uint32_t)rate * 4, 0x00100004, 0x61746164, n };
    fwrite(hdr, 4, 11, f);
    static int16_t b[1092];
    music_play();
    for (int i = 0; i < secs * rate / 546; i++) { music_render(b, 546, rate); fwrite(b, 4, 546, f); }
    fclose(f);
    return 0;
}

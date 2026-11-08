/* pacetest.c: pace.h against a model of the screen, with the frame costs read from a real New 3DS's log, next to 0.3's wait. Time is in microseconds. A loop shows its picture when its
 * work before the Link screen is done, and the screen takes the newest picture at each refresh. Prints what each wait does and fails if the new one breaks a rule.
 *   cc -O2 -I.. pacetest.c -o pacetest && ./pacetest */
#include <stdio.h>
#include <stdlib.h>
#include "pace.h"

#define REFRESH 16714ULL                                 /* 59.83 Hz */
#define WAKE 100ULL                                      /* waking from a refresh takes a moment */
#define SECONDS 60

typedef struct { double fps; int noNew; int refreshes; } Result;

static uint64_t nextVblank(uint64_t t) { return (t / REFRESH + 1) * REFRESH; }

/* normal, linkExtra: what a loop costs and what every sixth adds after the picture. stallAt: the loop that also takes stallLen. */
static unsigned copyCost;                               /* 0: the page and its copy in the same frame. Otherwise the copy, three frames after the page, out of linkExtra */
static Result sim(int useNew, unsigned normal, unsigned linkExtra, int stallAt, unsigned stallLen) {
    uint64_t t = 0, due = 0, end = SECONDS * 1000000ULL;
    uint64_t vb = REFRESH, lastShownAt = 0;
    int loops = 0, noNew = 0, refreshes = 0, fresh = 0;
    srand(7);
    while (t < end) {
        uint64_t t0 = t;
        unsigned j = (unsigned)(rand() % 1000);          /* a millisecond of jitter, as the log shows */
        uint64_t shown = t0 + normal + j;
        t = shown;
        if (loops % 6 == 0) t += linkExtra - copyCost;
        if (loops % 6 == 3) t += copyCost;
        if (loops == stallAt) t += stallLen;
        loops++;
        /* the refreshes up to this picture, then this picture waits for the next */
        while (vb <= shown) { refreshes++; if (!fresh) noNew++; fresh = 0; vb += REFRESH; }
        fresh = 1; lastShownAt = shown;
        int wait;
        if (useNew) wait = pace_wait(t, &due, REFRESH);
        else wait = t - t0 < REFRESH;
        if (wait) { t = nextVblank(t) + WAKE; if (useNew) pace_waited(t, &due, REFRESH); }
    }
    (void)lastShownAt;
    Result r = { loops / (double)SECONDS, noNew, refreshes };
    return r;
}

static int failed;
static void check(int ok, const char *what) { if (!ok) { printf("FAILED: %s\n", what); failed = 1; } }

int main(void) {
    static const struct { unsigned normal, linkExtra; const char *what; } C[] = {
        { 10000,  6000, "light Link page (log: LINK 1.0, MAX 16)" },
        { 10000, 12000, "heavy Link page (log: LINK 2.2, MAX 22)" },
        { 10800, 12000, "heavy Link page, slower frames" },
        { 11200, 12000, "heavy Link page, busy scene" },
        { 12000, 12000, "heavy Link page, busier scene" },
        { 14000, 12000, "much busier scene" },
    };
    double full = 1000000.0 / REFRESH;
    printf("the screen's own rate: %.2f fps\n", full);
    printf("%-42s %-24s %s\n", "", "0.3's wait", "pace.h");
    for (size_t i = 0; i < sizeof C / sizeof C[0]; i++) {
        Result a = sim(0, C[i].normal, C[i].linkExtra, -1, 0), b = sim(1, C[i].normal, C[i].linkExtra, -1, 0);
        printf("%-42s %5.1f fps, %4d repeats   %5.1f fps, %4d repeats\n", C[i].what, a.fps, a.noNew, b.fps, b.noNew);
        check(b.fps >= a.fps - 0.01, "the new wait ran slower than the old");
        check(b.fps <= full + 0.1, "the new wait ran faster than the screen");
    }
    copyCost = 3000;
    printf("the copy to the screen 3 ms and split off, with pace.h:\n");
    for (size_t i = 0; i < sizeof C / sizeof C[0]; i++) { Result b = sim(1, C[i].normal, C[i].linkExtra, -1, 0); printf("%-42s %5.1f fps, %4d repeats\n", C[i].what, b.fps, b.noNew); check(b.fps <= full + 0.1, "split: faster than the screen"); }
    copyCost = 0;
    /* frames that never fit: no waiting at all, the same as before */
    Result a = sim(0, 19000, 0, -1, 0), b = sim(1, 19000, 0, -1, 0);
    printf("%-42s %5.1f fps, %4d repeats   %5.1f fps, %4d repeats\n", "every frame over a refresh", a.fps, a.noNew, b.fps, b.noNew);
    check(b.fps >= a.fps - 0.01, "slow frames ran slower than before");
    /* one long stall, a save or a menu: the time is lost, not made up in a rush */
    Result s = sim(1, 10000, 0, 600, 283000), q = sim(1, 10000, 0, -1, 0);
    double lost = (q.fps - s.fps) * SECONDS;
    printf("a 283 ms stall loses %.0f frames (it spans %.0f refreshes; at most %d are made up)\n", lost, 283000.0 / REFRESH, PACE_BEHIND);
    check(lost >= 283000.0 / REFRESH - PACE_BEHIND - 2 && lost <= 283000.0 / REFRESH + 2, "a long stall wasn't handled as lost time");
    /* the rule itself, by hand */
    uint64_t due = 1000;
    check(pace_wait(999, &due, 100) == 1, "on time should wait");
    check(pace_wait(1000, &due, 100) == 0 && due == 1100, "late should go straight on, the next due a refresh later");
    due = 1000;
    check(pace_wait(1301, &due, 100) == 0 && due == 1401, "far behind should start again from now");
    puts(failed ? "FAILED" : "ok");
    return failed;
}

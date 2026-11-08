/* pace.h - when the main loop waits for the next refresh (0.31).
 * A frame that also draws the Link screen can run longer than a refresh. 0.3 waited whenever a loop itself was short, so when such a frame and the one after it together took a little
 * over two refreshes, the second waited for a third with its own refresh just gone: the game lost a frame, 1 to 4 a second on a real New 3DS. So each loop now has a time to finish by,
 * one refresh after the last. A loop that misses it goes straight on and the next one makes it up. More than PACE_BEHIND refreshes behind (a save, a menu) is given up as lost, so the
 * game never rushes for long. test/pacetest.c runs this against a model of the screen, since an emulator can't reproduce a real 3DS's timing. No libctru here, so that test builds anywhere. */
#ifndef PACE_H
#define PACE_H
#include <stdint.h>
#define PACE_BEHIND 3

/* the loop has finished at `now`. 1: wait for the refresh, then call pace_waited. 0: go straight on. */
static inline int pace_wait(uint64_t now, uint64_t *due, uint64_t refresh) {
    if (now < *due) return 1;
    if (now - *due > PACE_BEHIND * refresh) *due = now;
    *due += refresh;
    return 0;
}
/* woken by the refresh at `now`. The next one comes a refresh later: a little less here, because waking takes a moment, and calling the next refresh early only skips a wait that was nearly over. */
static inline void pace_waited(uint64_t now, uint64_t *due, uint64_t refresh) {
    *due = now + refresh - refresh / 16;
}
#endif

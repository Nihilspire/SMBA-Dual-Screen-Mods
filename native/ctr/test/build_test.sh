#!/bin/bash
# build_test.sh SCRIPTFILE [OUT]: the build with CTR_TEST, which starts the game at once and plays a compiled-in script by frame, because Azahar's input
# can't be driven headless. SCRIPTFILE is C:
#   #define TEST_SCRIPT { frame, frames, pad bits, tap x, tap y, ff, act }, ...
#   #define TEST_STATE "sdmc:/3ds/sma-dual/L03.st"        (loaded at frame 2; optional)
#   #define TEST_QUIT 3000                                 (optional)
#   #define TEST_HASH 600                                  (optional: logs a hash of the whole machine and of the picture at that frame, the same in any build that hasn't changed the emulation)
# Pad bits are libretro ids: B 1<<0, SELECT 1<<2, START 1<<3, UP 1<<4, DOWN 1<<5, LEFT 1<<6, RIGHT 1<<7, A 1<<8. act: 1 pause, 2 resume, 10-15 menu pad (MP_*).
set -e
HERE=$(cd "$(dirname "$0")/.." && pwd)
T=${2:-$HOME/sma3d/ctr-test}
mkdir -p "$T/obj"
cp "$1" "$T/test_script.h"
# the core is the same as the plain build's: reuse its objects
[ -e "$T/obj/core" ] || ln -s "$HOME/sma3d/ctr/obj/core" "$T/obj/core"
cp "$HOME/sma3d/ctr/obj/mgba_version.o" "$HOME/sma3d/ctr/obj/gbacore.o" "$HOME/sma3d/ctr/obj/smatrack.o" "$HOME/sma3d/ctr/obj/extract.o" "$T/obj/"
rm -f "$T/obj/main.o"
export DEVKITPRO=/opt/devkitpro DEVKITARM=/opt/devkitpro/devkitARM
cd "$HERE" && make -j8 OUT="$T" OBJDIR="$T/obj" MGBA="$HOME/src/mgba" EXTRA="-DCTR_TEST -I$T" 2>&1 | grep -E 'error|warning: impl|3dsx$' | tail -5

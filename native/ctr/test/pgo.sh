#!/bin/bash
# pgo.sh: profile-guided optimisation for the core. Builds it with counters, plays scripted stretches of several levels in Azahar (the counts are the same as on a device: the same
# ARM code on the same game), and leaves the counts in native/ctr/pgo/, which every normal build then uses (PGO=off leaves them out). The counts match the core's source: redo this if mGBA or the patch changes. A function that changed since just isn't optimised by them.
set -e
HERE=$(cd "$(dirname "$0")/.." && pwd)
export DEVKITPRO=/opt/devkitpro DEVKITARM=/opt/devkitpro/devkitARM
T=${OUT:-$HOME/sma3d/ctr}
AZ_SD=${AZHOME:-$HOME/sma3d/azahar}/data/azahar-emu/sdmc
cd "$HERE"
rm -rf "$T/obj"; mkdir -p "$T/obj"; rm -rf "$AZ_SD/pgo"; mkdir -p "$AZ_SD/pgo"
for L in ${LEVELS:-02 03 04 09 12 18}; do
  cp "$HOME/sma3d/work/au/L$L.st" "$AZ_SD/3ds/sma-dual/train.st"
  cat > "$T/test_script.h" <<'EOF'
#define TEST_SCRIPT {30, 280, 1u<<7, -1, -1, 0, 0}, {60, 12, 1u<<8, -1, -1, 0, 0}, {140, 12, 1u<<8, -1, -1, 0, 0}, {220, 12, 1u<<8, -1, -1, 0, 0}, {330, 200, 1u<<6, -1, -1, 0, 0}, {380, 12, 1u<<8, -1, -1, 0, 0}, {470, 12, 1u<<8, -1, -1, 0, 0}, {560, 240, 1u<<7, -1, -1, 0, 0}, {600, 12, 1u<<8, -1, -1, 0, 0}, {700, 12, 1u<<8, -1, -1, 0, 0}
#define TEST_STATE "sdmc:/3ds/sma-dual/train.st"
#define TEST_QUIT 850
#define TEST_PGO 1
#define TEST_PREFS pf.frameskip = 0;
EOF
  rm -f "$T/obj/main.o"
  make -j8 OUT="$T" OBJDIR="$T/obj" MGBA="$HOME/src/mgba" PGO=gen EXTRA="-DCTR_TEST -I$T" 2>&1 | grep -E ' error |undefined' | cut -c1-200 | head -5 || true
  KEEP=1 LOGLINES=1 AZLINES=0 bash test/azahar_run.sh "$T/Super Mario Advance Dual 3DS.3dsx" "$T/az_pgo" "w:${TRAIN_SECS:-55}" | grep -E "fps" | sed "s/^/L$L /" || true
  ls "$AZ_SD/pgo" | wc -l
done
rm -rf "$HERE/pgo"; mkdir -p "$HERE/pgo"
cp "$AZ_SD/pgo"/*.gcda "$HERE/pgo/"
ls "$HERE/pgo" | wc -l; du -sh "$HERE/pgo"
rm -rf "$T/obj"          # the next normal build must not reuse the counting objects

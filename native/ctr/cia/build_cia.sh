#!/bin/bash
# build_cia.sh - Super Mario Advance Dual 3DS as a .cia (HOME Menu title), from the .3dsx build's ELF.
#   needs: makerom (3DSGuy/Project_CTR) and bannertool (carstene1ns/3ds-bannertool) in $TOOLS (default /home/claude/work/ctr/tools), Pillow.
# The icon is the owner's Android launcher icon; the banner is silent, thick 3D text drawn by banner/make_art.py, with no game art.
set -e
HERE=$(cd "$(dirname "$0")/.." && pwd)
ROOT=$(cd "$HERE/../.." && pwd)
TOOLS=${TOOLS:-/home/claude/work/ctr/tools}
OUT=${OUT:-$HOME/sma3d/ctr}
VER=$(grep -E '^VERSION' "$HERE/Makefile" | sed 's/.*:= *//')
B=$OUT/cia; mkdir -p "$B"
python3 -I "$HERE/banner/make_art.py" "$B" "$ROOT/app/res/mipmap-xxxhdpi/smba_dual.png"
python3 -I -c "import wave,sys; w=wave.open(sys.argv[1],'wb'); w.setnchannels(2); w.setsampwidth(2); w.setframerate(32728); w.writeframes(bytes(4*3273)); w.close()" "$B/silence.wav"
"$TOOLS/bannertool-bin" makebanner -i "$B/banner.png" -a "$B/silence.wav" -o "$B/banner.bnr"
"$TOOLS/bannertool-bin" makesmdh -s "SMA Dual 3DS" -l "Super Mario Advance Dual 3DS $VER: the game + a Link screen" -p "Jack" -i "$B/icon.png" -o "$B/icon.icn"
"$TOOLS/makerom-bin" -f cia -o "$OUT/Super Mario Advance Dual 3DS.cia" -target t -exefslogo \
    -elf "$OUT/smadual3ds.elf" -rsf "$HERE/cia/smadual.rsf" -icon "$B/icon.icn" -banner "$B/banner.bnr" \
    -major "$(echo "$VER" | cut -d. -f1)" -minor "$(echo "$VER" | cut -d. -f2 | cut -d- -f1)" -micro 0
ls -la "$OUT/Super Mario Advance Dual 3DS.cia"

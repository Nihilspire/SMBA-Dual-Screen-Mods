#!/bin/bash
# build_host.sh [OUTDIR]: test/linkhost.c with the desktop core's library (native/Makefile TARGET=host, kept in $HOME/sma3d/out/host).
set -e
HERE=$(cd "$(dirname "$0")/.." && pwd); N=$(cd "$HERE/.." && pwd)
O=${1:-$HOME/sma3d/ctr-host}; mkdir -p "$O"
A=$HOME/sma3d/out/host/libmgba.a
[ -f "$A" ] || { echo "no $A: build the desktop driver first"; exit 1; }
MGBA=${MGBA:-$HOME/src/mgba}
DEFS="-DCOLOR_16_BIT -DCOLOR_5_6_5 -DDISABLE_THREADING -DENABLE_DIRECTORIES -DENABLE_VFS -DENABLE_VFS_FD -DHAVE_FREELOCALE -DHAVE_FUTIMENS -DHAVE_LOCALE -DHAVE_LOCALTIME_R -DHAVE_NEWLOCALE -DHAVE_PTHREAD_CREATE -DHAVE_REALPATH -DHAVE_SETLOCALE -DHAVE_STRDUP -DHAVE_STRLCPY -DHAVE_STRNDUP -DHAVE_USELOCALE -DHAVE_VASPRINTF -DMGBA_STANDALONE -DMINIMAL_CORE=2 -DM_CORE_GB -DM_CORE_GBA -DM_PI=3.14159265358979323846 -DUSE_PTHREADS -D_GNU_SOURCE -D__LIBRETRO__ -DNDEBUG -DGBX_TRACE"
INCS=(-I"$N/mgba_include" -I"$MGBA/src" -I"$MGBA/include" -I"$MGBA/src/platform/libretro" -I"$N")
for f in gbacore smatrack extract; do clang -O2 -g -fPIC -fwrapv -std=gnu11 -Wno-everything $DEFS "${INCS[@]}" -c "$N/$f.c" -o "$O/$f.o"; done
WARN="-Wall -Wextra -Wno-unused-parameter -Wno-missing-field-initializers -Wno-unused-function -Wno-implicit-fallthrough"
for f in canvas kit linkscr linkmap menuscr border music; do clang -O1 -g -std=gnu11 $WARN -I"$N" -I"$HERE" -c "$HERE/$f.c" -o "$O/$f.o"; done
clang -O1 -g -std=gnu11 $WARN -I"$N" -I"$HERE" -I"$MGBA/src/platform/libretro" -I"$MGBA/include" -c "$HERE/test/linkhost.c" -o "$O/linkhost.o"
clang "$O"/*.o "$A" -lm -o "$O/linkhost"
ls -la "$O/linkhost"

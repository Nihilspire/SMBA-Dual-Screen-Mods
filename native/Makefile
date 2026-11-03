# Builds mGBA (libretro, static) + glue for a target.
#   make TARGET=android   -> out/android/libsmadual.so
#   make TARGET=host      -> out/host/drive (desktop test driver)
# MGBA = mGBA checkout (libretro/mgba @ 7a12d6d) with mgba-dual.patch applied (tools/setup_toolchain.sh does both).
TARGET ?= android
ROOT := $(abspath ..)
OBJDIR := $(ROOT)/out/$(TARGET)/obj
SRCS := $(filter-out src/platform/libretro/libretro.c,$(shell cat mgba_sources.txt))
DEFS := -DCOLOR_16_BIT -DCOLOR_5_6_5 -DDISABLE_THREADING -DENABLE_DIRECTORIES -DENABLE_VFS -DENABLE_VFS_FD \
  -DHAVE_FREELOCALE -DHAVE_FUTIMENS -DHAVE_LOCALE -DHAVE_LOCALTIME_R -DHAVE_NEWLOCALE -DHAVE_PTHREAD_CREATE \
  -DHAVE_REALPATH -DHAVE_SETLOCALE -DHAVE_STRDUP -DHAVE_STRLCPY -DHAVE_STRNDUP -DHAVE_USELOCALE -DHAVE_VASPRINTF \
  -DMGBA_STANDALONE -DMINIMAL_CORE=2 -DM_CORE_GB -DM_CORE_GBA -DM_PI=3.14159265358979323846 -DUSE_PTHREADS \
  -D_GNU_SOURCE -D__LIBRETRO__ -DNDEBUG
INCS := -I$(ROOT)/native/mgba_include -I$(MGBA)/src -I$(MGBA)/include -I$(MGBA)/src/platform/libretro
ifeq ($(TARGET),android)
CC := clang --target=aarch64-linux-android23 --sysroot=$(ROOT)/out/sysroot
CFLAGS := -O2 -fPIC -ffunction-sections -fdata-sections -fvisibility=hidden -DANDROID -DHAVE_STRTOF_L
else
CC := clang
CFLAGS := -O2 -g -fPIC -DGBX_TRACE
endif
CFLAGS += $(DEFS) $(INCS) -fwrapv -std=gnu11 -Wno-everything
OBJS := $(patsubst %.c,$(OBJDIR)/%.o,$(SRCS)) $(OBJDIR)/mgba_version.o
$(OBJDIR)/mgba_version.o: mgba_version.c
	@mkdir -p $(dir $@)
	@$(CC) $(CFLAGS) -c $< -o $@
$(OBJDIR)/%.o: $(MGBA)/%.c
	@mkdir -p $(dir $@)
	@$(CC) $(CFLAGS) -c $< -o $@
$(ROOT)/out/$(TARGET)/libmgba.a: $(OBJS)
	@llvm-ar rcs $@ $^
GLUE := gbacore.c smatrack.c extract.c
android: $(ROOT)/out/android/libmgba.a gbajni.c $(GLUE)
	$(CC) $(CFLAGS) -shared -nostdlib -fuse-ld=lld -Wl,--gc-sections -Wl,-soname,libsmadual.so \
	  -Wl,--version-script=jni.map -Wl,-z,max-page-size=16384 -Wl,-s \
	  gbajni.c $(GLUE) $(ROOT)/out/android/libmgba.a -L$(ROOT)/out/sysroot/usr/lib -lbuiltins -lm -llog -ldl -lc \
	  -o $(ROOT)/out/android/libsmadual.so
host: $(ROOT)/out/host/libmgba.a drive.c $(GLUE)
	$(CC) $(CFLAGS) drive.c $(GLUE) $(ROOT)/out/host/libmgba.a -lm -o $(ROOT)/out/host/drive

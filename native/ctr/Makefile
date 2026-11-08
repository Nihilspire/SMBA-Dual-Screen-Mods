# Super Mario Advance Dual 3DS (alpha): mGBA (libretro fork + ../mgba-dual.patch, the Android app's core) + ../gbacore.c + the 3DS frontend -> .3dsx
#   make -C native/ctr MGBA=$HOME/src/mgba OUT=$HOME/sma3d/ctr        -> $OUT/Super Mario Advance Dual 3DS.3dsx
# Needs devkitPro's 3ds-dev (devkitARM, libctru, 3dsxtool, smdhtool) and ImageMagick for the icon. Everything here is reached by relative paths,
# so the checkout's path may have spaces, but keep OUT on the Linux side: make can't take a target path with spaces in it beyond the final name.
DEVKITPRO	?= /opt/devkitpro
DEVKITARM	?= $(DEVKITPRO)/devkitARM
MGBA		?= $(HOME)/src/mgba
OUT		?= $(HOME)/sma3d/ctr
OBJDIR		?= $(OUT)/obj
VERSION		:= 0.32-alpha

PREFIX		:= $(DEVKITARM)/bin/arm-none-eabi-
CC		:= $(PREFIX)gcc
ARCH		:= -march=armv6k -mtune=mpcore -mfloat-abi=hard -mtp=soft

# the core: as native/Makefile's Android flags, minus what newlib or the 3DS doesn't have. __3DS__ gives it mGBA's 3DS threads, for its threaded video (0.2); no _3DS, or libretro.c takes its own 3DS paths.
# No MINIMAL_CORE: the video proxy is compiled out with it. A flag change needs rm -rf $(OBJDIR).
MDEFS		:= -D__3DS__ -DCOLOR_16_BIT -DCOLOR_5_6_5 -DENABLE_DIRECTORIES -DENABLE_VFS -DENABLE_VFS_FD -DHAVE_LOCALE -DHAVE_NEWLOCALE -DHAVE_FREELOCALE -DHAVE_USELOCALE -DHAVE_STRTOF_L -DHAVE_STRDUP -DHAVE_STRLCPY -DHAVE_STRNDUP -DHAVE_VASPRINTF \
		   -DMGBA_STANDALONE -DM_CORE_GB -DM_CORE_GBA -DM_PI=3.14159265358979323846 -D_GNU_SOURCE -D__LIBRETRO__ -DNDEBUG -DSMA_CTR -DLOG_SILENT
MINCS		:= -I$(DEVKITPRO)/libctru/include -I../mgba_include -I$(MGBA)/src -I$(MGBA)/include -I$(MGBA)/src/platform/libretro
MFLAGS		:= -O3 -fomit-frame-pointer -ffast-math -fwrapv -std=gnu11 -ffunction-sections -fdata-sections -w $(ARCH) $(MDEFS) $(MINCS)
# Profile-guided optimisation of the core (0.3): test/pgo.sh makes the counts in pgo/ with PGO=gen; every other build uses them if they're there. PGO=off leaves them out.
PGO		?= use
# GCC only flattens a profile's name and trims this prefix when the object's own name is relative, and OBJDIR is absolute: so the rules below give each object a relative one (-dumpdir, -dumpbase),
# which makes the profile's file name the same wherever the build goes.
PGOPATH		:= '-fprofile-prefix-path=$(CURDIR)'
ifeq ($(PGO),gen)
MFLAGS		+= -fprofile-generate -fprofile-dir=sdmc:/pgo -fprofile-update=single $(PGOPATH)
PGOLD		:= -fprofile-generate
else ifeq ($(PGO),use)
ifneq ($(wildcard pgo/*.gcda),)
MFLAGS		+= -fprofile-use=pgo -fprofile-correction -fprofile-partial-training -Wno-error=coverage-mismatch $(PGOPATH)
endif
endif
# the frontend and the screens
CFLAGS		:= -O2 -ffunction-sections -fdata-sections $(ARCH) -D__3DS__ -DSMA_CTR -I$(DEVKITPRO)/libctru/include -I.. -Wall -Wextra -Wno-unused-parameter -Wno-missing-field-initializers -Wno-format-truncation -std=gnu11 $(EXTRA)
LDFLAGS		:= -specs=3dsx.specs $(ARCH) -Wl,--gc-sections -L$(DEVKITPRO)/libctru/lib $(PGOLD)

# as Android, but malloc for mmap (the GB core stays: libretro.c assumes it)
SKIP		:= src/platform/libretro/libretro.c src/platform/posix/memory.c src/gba/renderers/gl.c
SRCS		:= $(filter-out $(SKIP),$(shell cat ../mgba_sources.txt)) src/util/memory.c src/feature/thread-proxy.c src/feature/video-logger.c src/gba/extra/proxy.c src/gb/extra/proxy.c src/core/input.c
OBJS		:= $(patsubst %.c,$(OBJDIR)/core/%.o,$(SRCS)) $(OBJDIR)/mgba_version.o $(OBJDIR)/gbacore.o $(OBJDIR)/smatrack.o $(OBJDIR)/extract.o \
		   $(OBJDIR)/main.o $(OBJDIR)/topgpu.o $(OBJDIR)/quad_shbin.o $(OBJDIR)/canvas.o $(OBJDIR)/kit.o $(OBJDIR)/linkscr.o $(OBJDIR)/linkmap.o $(OBJDIR)/menuscr.o $(OBJDIR)/border.o $(OBJDIR)/music.o

all: $(OUT)/Super\ Mario\ Advance\ Dual\ 3DS.3dsx

$(OBJDIR)/core/%.o: $(MGBA)/%.c
	@mkdir -p $(dir $@)
	@$(CC) $(MFLAGS) -dumpdir core/$(dir $*) -dumpbase $(notdir $*) -c $< -o $@
$(OBJDIR)/mgba_version.o: ../mgba_version.c
	@mkdir -p $(dir $@)
	@$(CC) $(MFLAGS) -dumpdir app/ -dumpbase mgba_version -c $< -o $@
$(OBJDIR)/gbacore.o: ../gbacore.c ../gbacore.h
	@mkdir -p $(dir $@)
	$(CC) $(MFLAGS) -dumpdir app/ -dumpbase gbacore -I.. -c $< -o $@
$(OBJDIR)/smatrack.o: ../smatrack.c ../gbacore.h
	$(CC) $(MFLAGS) -dumpdir app/ -dumpbase smatrack -I.. -c $< -o $@
$(OBJDIR)/extract.o: ../extract.c ../gbacore.h
	$(CC) $(MFLAGS) -dumpdir app/ -dumpbase extract -I.. -c $< -o $@
$(OBJDIR)/quad_shbin.o: quad.v.pica
	@mkdir -p $(dir $@)
	$(DEVKITPRO)/tools/bin/picasso -o $(OBJDIR)/quad.shbin $<
	$(DEVKITPRO)/tools/bin/bin2s $(OBJDIR)/quad.shbin > $(OBJDIR)/quad_shbin.s
	$(CC) $(ARCH) -x assembler -c $(OBJDIR)/quad_shbin.s -o $@
$(OBJDIR)/%.o: %.c dual3ds.h linkint.h sma.h topgpu.h pace.h
	@mkdir -p $(dir $@)
	$(CC) $(CFLAGS) -c $< -o $@
$(OBJDIR)/linkmap.o: linkmap.c dual3ds.h linkint.h sma.h
	@mkdir -p $(dir $@)
	$(CC) $(CFLAGS) -O3 -c $< -o $@

$(OUT)/smadual3ds.elf: $(OBJS)
	$(CC) $(LDFLAGS) $(OBJS) -lcitro3d -lctru -lm -o $@

$(OUT)/icon.png: ../../app/res/mipmap-xxxhdpi/smba_dual.png
	convert "$<" -filter box -resize 48x48 $@

$(OUT)/smadual3ds.smdh: $(OUT)/icon.png
	$(DEVKITPRO)/tools/bin/smdhtool --create "Super Mario Advance Dual" "$(VERSION): the game + a Link screen" "Jack" $< $@

$(OUT)/Super\ Mario\ Advance\ Dual\ 3DS.3dsx: $(OUT)/smadual3ds.elf $(OUT)/smadual3ds.smdh
	$(DEVKITPRO)/tools/bin/3dsxtool $< "$@" --smdh=$(OUT)/smadual3ds.smdh
	@ls -la "$@"

clean:
	rm -rf $(OBJDIR) $(OUT)/*.elf $(OUT)/*.3dsx $(OUT)/*.smdh $(OUT)/icon.png

.PHONY: all clean

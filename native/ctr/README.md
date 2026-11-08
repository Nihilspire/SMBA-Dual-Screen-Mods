# Super Mario Advance Dual 3DS (0.32-alpha)

**New 3DS and New 2DS only.** The old 3DS is no longer a target: OLD 3DS SPEED and its code paths are gone. It may still start on one, but it won't run well and nothing is tested there.

0.32-alpha (Nov 6 2026): **FRAME BLEND is gone** from the 3DS app. SETTINGS > DISPLAY no longer lists it, and a settings file saved with it on is ignored. Android still has it, so `gbacore.c` keeps the code and the 3DS just never calls it.

**0.31 on a real New 3DS, 3:2, FAR CLOUDS on:** 60-61 fps in 32 of 38 samples, every picture shown in nearly all of them. GBA 8-10 ms, TOP 0.5, LINK 1-1.2, MAX 14-23. The settings file has GBA LCD COLORS on by the end, which fits GBA rising to 12-15 from 181 s, still at 60. The label on the log's first line is gone on real hardware too. Still there: single frames of 34-43 ms when entering an area, one of 247 ms at about 181 s, and two samples at 52 fps with normal frame times, probably menus.

0.31-alpha (Nov 6 2026), after 0.3's log from a real New 3DS read 57-60 fps at 3:2, with about 5 ms a frame to spare on average:
- **The frame wait** (`pace.h`). The frames that also draw the Link screen can run longer than a refresh. 0.3 waited whenever a loop itself was short, so when such a frame and the one after it took a little over two refreshes, the second waited for a third and the game lost a frame. Now each loop has a time to finish by, and a loop that misses it goes straight on, so the next one makes the time up. More than 3 refreshes behind (a save, a menu) counts as lost, so the game never rushes for long.
- **The Link screen's costly parts are in different frames.** Reading the game and drawing the page stay on one frame, and copying the page to the screen moves three frames later. That's 50 ms later on a screen that updates 10 times a second.
- **The log's "(emulator)" label** now needs Azahar's actual answer, because a real 3DS logged itself as an emulator too. Nothing else used it.

`test/pacetest.c` models this with the costs closest to that log (frames of 10 ms, 12 ms more for the Link screen every sixth, assuming 3 ms of that is the copy): 0.3's wait gives 57.5 fps and 140 repeated pictures a minute, where the log read 58.6 fps. 0.31 gives 59.9 fps, the screen's own rate, and none. In Azahar, the title went from 52-54 fps to 55-60 and 5-1 from 48-50 to 51-54. Entering a new area still draws much of the map at once, so a brief dip there is expected.

0.3-alpha (Nov 6 2026), after 0.2 measured close to 60 fps at integer scale and less at 3:2:
- **The GPU scales the top screen.** 0.1 and 0.2 scaled on the CPU, pixel by pixel, which is why the smaller integer scale ran faster. Now the CPU hands over a 240x160 picture and one textured quad scales it (`topgpu.c`), at the same CPU cost at any size, with real bilinear filtering for SMOOTH SCALING. The borders and the FPS and message lines are their own textures, uploaded only when they change.
- **The game no longer waits for the drawing.** The drawing core copies each finished picture into one of three buffers, and the main core takes the newest. If it isn't ready, the main core waits only with time the refresh can spare, then shows it next time. GBA LCD COLORS still uses the old waiting path, so it costs more (FRAME BLEND did too, until 0.32 removed it).
- **The Link screen runs after the picture**, so its spike every sixth frame can't make the picture miss a refresh. Its map stops redrawing cells once a full sweep has finished, and starts again only when something changes.
- **Profile-guided optimisation of the core** (`pgo/`, made by `test/pgo.sh`): about 6% less work on the main core and a slightly smaller file, with the same machine state and picture as the plain build. LTO on top added almost nothing, so it's left out.
- **The CIA now asks for the third core by name** (`CanAccessCore2` in `cia/smadual.rsf`). The 0.2 CIA didn't, yet its log on a real New 3DS says it got it, so this may change nothing there. SETTINGS > SYSTEM > DRAW ON 3RD CORE now says REFUSED if the system won't allow it.
- **FAR CLOUDS** (SETTINGS > DISPLAY, on by default) can leave BG2 out of the picture, except in world 7, where that layer is the sky. You shouldn't need it: see "Which backgrounds" below.

In Azahar, the same 3:2 scene (5-1, standing):

| | GBA frame | Waiting in that | Top | Link | fps |
|---|---|---|---|---|---|
| 0.2 | 28-30 ms | 9.7 | 1.2-2.4 | 1.9 | about 30 |
| 0.3 | 16.5-18 ms | 0.0 | 0.6 | 1.2-1.5 | 50 |

Azahar counts instructions, not time, and it charges the GPU almost nothing, so those two rows only compare builds. **On a real New 3DS at 3:2,** 0.2 read GBA 13-15, TOP 3.1, LINK 0.8 at 51-53 fps at its busiest. 0.3 reads GBA 9-10, TOP 0.5, LINK 1-2, MAX 15-23 at 57-60 fps, in about 10 minutes of play. FAR CLOUDS was off by the end of that test, and the log doesn't say when it was switched. Its dips below 60 lined up with the frames that also draw the Link screen, which is what 0.31 goes after. The file to read is `sma-3ds.log`, or the SYSTEM page's GBA, WAIT, TOP, LINK and MAX lines. In Azahar the FPS line often shows fewer pictures than frames, because of how it shares time between cores; that may not happen on a 3DS.

### Which backgrounds
The share of the emulator's whole frame each layer costs, counted with callgrind over 300 frames on the desktop core, levels 1-1 to 6-3:

| Layer | What it is | Saving | What you'd lose |
|---|---|---|---|
| BG2 | Far clouds, stars | 0 in caves, 3-9% outdoors | Almost nothing to see, outside world 7 |
| BG1 | Jungle, dunes, cave walls | 3-11% outdoors, 13-16% caves and ice | The scenery. Caves go black |
| BG3 | The HUD | about 3% | The HUD |
| Sprites | Everyone | about 7% | The game |
| BG0 | The level | | The game |

**Nothing needs to go on a New 3DS.** Layers are drawn on the third core, which only has to finish within a frame, so leaving one out only helps if pictures are being missed: the FPS line says something like `53 FPS 40 SHOWN`. If that happens, FAR CLOUDS off is the one to try. BG1 is the only other real saving, and it costs too much of the look.

0.2-alpha (Nov 5 2026), after 0.1 measured about 43 fps on a real 3DS: the drawing moves to the third core on a New 3DS. It's mGBA's own threaded video: the emulation on the main core logs video
writes and scanlines to a queue, and a thread on core 2 runs the renderer from it. In 0.2 the main core waited at the end of each frame until the picture was whole. Drawing was about 40% of a frame's work
(13 of 31 ms in Azahar), so the main core should go from roughly 23 ms a frame to under the 16.7 it needs, but **that's an estimate: Azahar doesn't show time on other cores, so only the device can say.**
- SETTINGS > SYSTEM > DRAW ON 3RD CORE, on by default, turns it off, from the next game start (for comparing, or if it misbehaves; the launcher comes up before the thread starts). A refused core 2 runs as 0.1 did. `sma-3ds.log` says which it used.
- The screen flush now covers only what was drawn: the top screen each frame, the bottom one when the Link screen changed. Azahar doesn't count that cost (TOP stays 2.4 ms with the thread off), so what it saves is unmeasured. With the thread on TOP reads 1.2 there, which is how Azahar shares its ticks between cores, not a real saving.
- The first-start capture doesn't draw the frames it runs, since it keeps video memory and never the picture.
- mGBA's threaded video has never shipped on the 3DS upstream: mGBA's own 3DS port doesn't turn it on. Reset, quit to title, CONTINUE (a new thread) and state loads all ran in Azahar; hardware is the open question.
- The core is now built with `__3DS__` and without `MINIMAL_CORE`, because both gate the threads and the proxy. The Android build's flags are unchanged.

The Android app's mGBA core and Link screen on a 3DS, built the same way as Shantae Dual 3DS (`Shantae\shantae-dual\native\ctr`) and Xenogears Dual 3DS: a libctru
frontend with the game on the top screen and the Link screen, written in C, on the touch screen. Everything Android has except the 3D view, and what has no meaning here
(screen modes, the touch pad).

## Running it
Needs a New 3DS or New 2DS. Nothing from the game ships in the `.3dsx` or the `.cia`.

1. Put your **Super Mario Advance (USA, Europe)** ROM (CRC32 `1E4C6D6A`, any file name ending in `.gba`) in `sdmc:/3ds/sma-dual/`.
   It's also found next to the `.3dsx`, in `sdmc:/3ds`, `sdmc:/roms/gba`, `sdmc:/roms` and `sdmc:/gba`.
2. Run the `.3dsx` from the Homebrew Launcher, or install the `.cia` with FBI. The `(SD root)` zip puts them in place: extract it at the root of the card, and it adds `3ds/sma-dual/` and `cias/`.
3. Sound needs the usual DSP firmware dump at `sdmc:/3ds/dspfirm.cdc`; without it the game plays silent.
4. The first start boots the game once with no save, walks title > file > 1-1 > pause by itself and keeps those screens' video memory for the menu's backdrop, panel and
   item icons (a progress bar shows, ~15 s in Azahar). Your real save is never opened.

Everything is kept in `sdmc:/3ds/sma-dual/`: `game.sav` (the game's own save), `slot1-4.st` + `.th`, `auto.st`, `quick.st`, `sma-dual.cfg`, the capture `kit-2-1e4c6d6a.bin`,
`sma-portraits.bin`, `sma-live.bin`, `sma-endings.bin` (signed ending cards) and `sma-3ds.log`. **After a run, that log has the frame rate and what each part cost, every 5
seconds. It's the file to look at for real speed.**

| 3DS | Does |
|---|---|
| D-pad, Circle Pad | D-pad |
| A / B | A / B |
| X / Y | Turbo A / turbo B |
| R (held) | Fast-forward, 3x, no sound |
| L | GBA L |
| ZL / ZR (New 3DS) | Quick save / quick load |
| L+X / L+Y | Quick save / quick load, on any 3DS (while L is held, X and Y don't reach the game) |
| Start / Select | Start / Select |
| Select+Start | Pause menu (also OPTIONS > PAUSE GAME) |

All but the Circle Pad and those two L combinations can be moved in SETTINGS > CONTROLS > BUTTONS. A button already in use swaps over, as on Android.

## What's here, against Android
- **The core:** the same mGBA with `native/mgba-dual.patch` through `native/gbacore.c`, so NO SLOWDOWN, GBA LCD colours, the EEPROM save and the tracker are the
  same code. **States are the same format**: a driver or Android state loads here. One 3DS-only change: the idle loop (see "Speed").
- **Top screen:** 3:2 (360x240), INTEGER SCALE (240x160) or STRETCH (400x240), the borders themed from the level's own blocks (one per area, locked after ~4 s), and at integer
  scale the HUD in the borders (hearts, lives, cherries, Ace Coins, level, character, time). SHOW FPS. New here: SMOOTH SCALING, because 1.5x is not a whole number.
- **Link screen:** STATUS, MAP, CHART and OPTIONS, the logo dimmed on the menus and at character select, Bonus Chance, Mario Bros. and the signed ending card, all from the same RAM as
  `link.js`. Map: the game's own background, X-ray grass, Ace Coins, keys, crystal, bosses, exits, four zooms, drag to look round. The screen is 320x240 at 1:1, so it uses the page's
  landscape layout: status always on the left, the rest behind three tabs, and a small third card for what's hidden in the area (under the map on Android).
- **Menus:** launcher (CONTINUE, NEW GAME, LOAD STATE, SETTINGS, EXIT), pause menu (RESUME, SAVE / LOAD STATE, SETTINGS, RESET, QUIT TO TITLE), 4 state slots with pictures, an
  auto state on quit, SKIP LAUNCHER, rebinding, MENU GRAPHICS and REBUILD MENU GRAPHICS, D-pad or stylus. The launcher's music, "Curtain Call", is the same score on a small
  software synth (`music.c`): rendered offline it's within 1% of the WebAudio version's loudness and 6% in each frequency band. Its room is a Schroeder reverb, not the page's convolution.
- **Playtest codes:** up up down down left right left right B A on the launcher, as on Android. All of them, warp included.
- **Not ported:** the 3D view (as asked). Screen modes, the touch pad and the second display have nothing to act on. The ROM isn't picked from a list: it's found by its CRC.
- **New here, from Shantae Dual 3DS:** SETTINGS > SYSTEM has FRAME SKIP, DRAW ON 3RD CORE and the timing lines. The HOME Menu icon is the Android launcher's.

## Speed
**0.1 measured about 43 fps on a real 3DS (23 ms a frame), 0.2 close to 60 at integer scale, and 0.3 57-60 at 3:2 and 0.31 60 at 3:2.** The top of this file has 0.3's numbers; this section is how the emulation's own cost came down. Azahar counts instructions, not time, and doesn't show time on other cores, so it can only compare work on the main core. If that 43 fps was a busy scene, Azahar's 35 ms is about 23 real ms, a factor of 0.65.

| In Azahar, 4-1, walking | GBA frame |
|---|---|
| NO SLOWDOWN off | 34 ms |
| NO SLOWDOWN on | 60 ms |
| With the idle loop halted, either way | 19 ms |

**The idle loop was most of it.** The game's main loop spins on the vblank flag (`03001178` bit 0, from `08001CDE`) and never calls Halt: the Android build's lag meter counts every frame
as lag for the same reason, about 3700 reads a frame. With NO SLOWDOWN the CPU runs twice as many of those spins in a frame, which is why it cost as much as it did. mGBA can halt until the next interrupt when a CPU
keeps branching to a known idle address, and the loop has no side effects, so `gbacore.c` sets `idleLoop = 08001CDE` under `SMA_CTR`. Android is left as it was. It would save
battery there too, if it's ever wanted.

Splitting the overclock's sums out of mGBA's main loop (`mgba-dual.patch`) saved about another 6% with it off. It doesn't change the emulation: RAM, VRAM and pictures are byte-identical to the old core after 400 frames with
the overclock off and on. What's left is ordinary work, about 60% the CPU and 40% the drawing: in a busy scene (5-1's hills) 18 + 13 ms in Azahar, plus 2.5 for the top screen and 2 for the Link screen, which at 0.65 is the 23 ms seen on the device. Every sixth frame also draws the Link screen, which can run over by itself, but the next frame catches up.

A long frame runs slightly slow rather than dropping to 30. FRAME SKIP (SETTINGS > SYSTEM, off by default) keeps the game and music at full speed instead by not drawing some frames. When a frame's CPU work alone is close to a refresh it can draw as few as 1 in 4, so turn it on only if the game runs slow.

## What's been checked
- **In Azahar (New 3DS mode):** the `.3dsx` from a clean SD card (first-start capture, launcher, music running), and the `.cia` installed with `azahar -i` and booted to the launcher. A scripted game from boot through the title,
  file select and character select into 1-1. The tabs, saving slot 1 from OPTIONS, a tap on the map, the pause menu, loading slot 1 back from its picture, the auto state and CONTINUE, smooth scaling, integer scale with the HUD, FRAME SKIP.
- **On the desktop, real core:** every level's MAP and top screen, CHART, OPTIONS, Bonus Chance, Mario Bros., the ending card signed and saved, the logo screen, every menu page, the Konami code. Side by side with the Android page on the same five moments: the same numbers, icons, markers and background.
- **0.3, in Azahar:** 3:2, stretch and integer scale, smooth on and off, no borders, the FPS line, LCD colours, frame blend, the third core off, FRAME SKIP, FAR CLOUDS off outdoors; pause, saving slot 1 and its picture, loading it back, reset, quit to the launcher, CONTINUE; the SYSTEM and DISPLAY pages; the CIA installed on a clean SD card, its first-start capture and launcher, with the core 2 flag in its header. The machine state and the picture are identical with the third core on and off on the title's spotlight, 2-1, 4-3, 5-1 and the character select, and with the plain and the profiled core on 2-1, 5-1 and the character select.
- **0.31, in Azahar:** the title, 5-1, fast-forward and FRAME SKIP against 0.3, the same machine state and picture as 0.3 on the title and 5-1, pause and resume, all three Link tabs, the CIA from a clean SD card. The wait itself runs in `test/pacetest.c`, since Azahar's frames don't come close to a real 3DS's.
- **0.32, in Azahar:** a saved FRAME BLEND is ignored (full speed, not the old waiting path), the DISPLAY rows still switch the right settings, FAR CLOUDS off in 7-2 keeps its sky (100 pixels of 448,000 differ from on), LCD colours still work, the same machine state and picture as 0.31 on the title and 5-1, the CIA from a clean SD card.
- **On a real 3DS:** 0.1 about 43 fps. 0.2 close to 60 at integer scale, and its log shows the CIA running with the third core drawing, at 51-60 fps. That log's first line says "(emulator)", but its file date (a 3DS clock set to 2050) and its timings point to real hardware; the label only affects the log. 0.3 at 3:2, 57-60 fps, and 0.31 at 3:2 with FAR CLOUDS on, 60 fps, both from their logs. 0.32 only removes a setting and hasn't run there. Everything else still open: real sound (Azahar only ran the sound path with a dummy firmware file, nobody has heard it), the HOME Menu icon and banner, buttons (scripted input only: L+X, L+Y, ZL and ZR have never been pressed), the stylus (scripted taps), HOME and sleep.

## Build and test
```
export DEVKITPRO=/opt/devkitpro DEVKITARM=/opt/devkitpro/devkitARM
make -C native/ctr MGBA=$HOME/src/mgba OUT=$HOME/sma3d/ctr      # -> $OUT/Super Mario Advance Dual 3DS.3dsx
OUT=$HOME/sma3d/ctr bash native/ctr/cia/build_cia.sh            # -> $OUT/Super Mario Advance Dual 3DS.cia
```
Change a core flag in the Makefile and `rm -rf $OUT/obj` first: make doesn't track flags, and a core built half with `ENABLE_DIRECTORIES` has two layouts of `struct mCore` (it crashed on
`core->init`, a null pointer).

- `test/linkhost.c` (`test/build_host.sh`): the real core on the desktop with driver states, the screens written as PPMs. All the screens but `main.c` build here because none of them use libctru.
- `test/build_test.sh` + `test/azahar_run.sh`: the ARM build with `CTR_TEST` boots straight into the game and plays a compiled-in script of buttons, taps and menu keys by frame, since Azahar's
  input can't be driven headless. `TEST_STATE` loads a state at frame 2. `TEST_HASH` logs a hash of the machine and the picture at a frame, to compare two builds. From a boot it also depends on `game.sav`, so compare on the same SD card.
- `test/pgo.sh`: redoes `pgo/`, the compiler's profile of the core, by playing six levels in Azahar with a counting build. Redo it if mGBA or the patch changes; a function that changed just isn't optimised by it. `PGO=off` builds without it.
  GCC finds each core file's profile by that file's path, so the profile only applies with mGBA where it was recorded (`$HOME/src/mgba` on the PC that made it). With mGBA anywhere else the core quietly builds with almost none of it, and the `.3dsx` comes out about 12 KB larger. A build from a fresh clone with `MGBA=$HOME/src/mgba` is byte-identical to one from the original folder.
- `test/pacetest.c`: `pace.h` against a model of the screen, next to 0.3's wait. It builds on any computer and fails if the wait breaks its rules.
- `test/musicdump.c`: the music as a WAV.

## Files
| File | What it is |
|---|---|
| `main.c` | The frontend: screens, sound, files, buttons, states, the capture, playtest codes |
| `topgpu.c`, `quad.v.pica` | The top screen on the GPU |
| `pace.h` | When the main loop waits for the next refresh |
| `linkscr.c`, `linkmap.c` | The Link screen (`link.js`). The map's picture holds each metatile cell at exactly k x k pixels and is drawn a few cells a frame |
| `menuscr.c` | Launcher, pause and settings (`menu.js`) |
| `kit.c` | The first-start capture (`gbakit.js`) |
| `border.c` | The top screen's borders and HUD (`BezelTheme.java`, `GameView.java`) |
| `music.c` | "Curtain Call" (`music.js`) |
| `canvas.c` | Rectangles, images and the game's font on an RGB565 buffer |
| `sma.h` | The RAM map (`sma.js`) |

## Licence
mGBA is MPL-2.0: its licence is at `app/assets/licenses/mGBA-MPL-2.0.txt`, and its source with our patch is `native/mgba-dual.patch` on libretro/mgba @ `7a12d6d`. In the `(SD root)` zip, the licence and the patch are beside this file.

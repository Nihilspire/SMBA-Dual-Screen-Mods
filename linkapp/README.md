# Super Mario Advance Link 0.1 (RetroArch) - alpha

Super Mario Advance Dual's Link screen on its own: an Android app for the second screen that reads Super Mario Advance (USA, Europe)
running in RetroArch, the way Shantae Link, Xenogears Link and Metroid Link do. No emulator and no native code (about 170 KB). Nothing
from the game ships: the player picks their own ROM.

## Setting up
1. **RetroArch Nightly** (Stable works, but on the Thor its Network Commands setting doesn't stick after a restart).
2. Settings > Network > **Network Commands: ON**, **Network Command Port: 55355**.
3. Core: **mGBA**. Load Super Mario Advance (USA, Europe) (CRC32 `1E4C6D6A`).
4. Start Super Mario Advance Link on the second screen, tap **CHOOSE ROM** and pick the same ROM file. It's kept, so that's once.

RetroArch must run on the same device: the app always talks to 127.0.0.1:55355. The window never takes input focus, so the controller
stays with RetroArch. The setup screen says what's missing at each step: no ROM, wrong ROM, no reply, no game, another game, a core with no
memory map.

## The 3D tab (Link only)
OPTIONS > 3D VIEW > **3D TAB: ON** adds a 3D tab. Dual draws the 3D view on the game's own screen; here that screen is RetroArch's, so the
view comes to the Link screen. It's Dual's own `Scene3D` and `Renderer3D`, compiled in from `app/src`, fed from RetroArch's memory about 30
times a second. Cameras under the picture (LOW, TILT, DIORAMA, FAR; tapping the picture steps them), and BLOCK DEPTH, BACK WALL, BACKGROUND,
PLAYER LIGHT, THICK and ROUND SPRITES in OPTIONS. Away from play (a pause, a door) the last frame stays up, since there is no 2D picture to
fall back on. The tab is off by default and uses nothing when it isn't on screen.

## How it reads the game
- `RetroArch.java`: UDP. All the wanted reads go out as `READ_CORE_MEMORY`, then the replies are collected (RetroArch answers everything
  queued in one frame). Replies fill a mirror of the GBA's memory, and the page and the 3D view read that and never wait on the network. What
  is read how often comes from counting what changes frame to frame in play (RAM-MAP.md, "RetroArch"). `GET_STATUS` once a second.
- `LinkActivity.java`: the WebView, the GL view, the ROM, and the page's `window.Native` with Dual's calls (the page is Dual's, unchanged:
  `LINK = !!Native.link` guards what differs).
- `LinkCore.java`: after each round, the stats and the 3D view's frame. After a door or a pause it holds the old picture until the fresh video
  memory has all come back.
- `Tracker.java`: `native/smatrack.c` over RetroArch's memory, kept in the app's preferences.
- `KitRom.java`: the same "SMAX" menu art Dual captures by playing the game on first start, built from the ROM by a table of offsets. It all sits
  uncompressed in the ROM. `tests/ra/kitcheck.py`: every canvas from it matches a real capture, pixel for pixel.
- `Native.java`: Gba.java's `Native.peek` answered from the mirror.

## What differs from Dual
| Dual | Link |
|---|---|
| The 3D view on the game's screen | The 3D tab, on the Link screen, about 30 frames a second |
| Save states with thumbnails | RetroArch's slots 1-4. LOAD by `LOAD_STATE_SLOT`. SAVE steps the slot (RetroArch 1.22.2 has no `SAVE_STATE_SLOT` and can't report its slot): down until it stops at -1, up to n, then `SAVE_STATE`, 50 ms apart, about 1.3 s, leaving RetroArch on slot n. No thumbnails, and it can't list which slots hold a state, so LOAD is always offered. |
| PAUSE GAME | RETROARCH MENU (`MENU_TOGGLE`) |
| Stats travel with save states | Stats are kept on the device and only count while Link runs. A new file clears them when it starts its first level (nothing cleared on it); RESET STATS in OPTIONS. A state loaded in RetroArch doesn't count as a clear, a warp or a death. With several files in turn, they share one set: not separated yet. |
| Ending card's DEV CODES USED | No playtest codes here, so never shown |
| Playtest codes, NO SLOWDOWN, borders, touch pad, music on the menu | Dual only: they live in the emulator, or RetroArch has its own |

## Build and test
```
bash tools/build_link_apk.sh        # -> out/Super Mario Advance Link <VERSION>.apk (VERSION in LinkActivity.java)
bash tests/ra/build.sh              # the plain-Java parts on the desktop, into tests/ra/out
python3 tests/mock_ra.py sma.gba DUMP_OR_DIR     # a stand-in RetroArch: mGBA's memory map, one dump a frame
java -cp tests/ra/out com.jack.smadual.RaCheck sma.gba 127.0.0.1 8 3d [dumpdir]   # the transport and the 3D feed: pace, reads, frames mapped
java -cp tests/ra/out com.jack.smadual.TrackCheck dump0.bin dump1.bin ...          # the tracker over a series of dumps
python3 tests/ra/kitcheck.py _kit.bin kit_from_rom.bin                             # KitOut writes the second one
python3 tests/ra/linkpage.py sma.gba kit.bin dump.bin outdir [WxH]                 # the page: setup screens and tabs
```
Against a real RetroArch: `-Dport=` on RaCheck. On this PC Windows reserves UDP 55320-55419 (`netsh interface ipv4 show excludedportrange
protocol=udp`), so RetroArch can't bind 55355 ("Failed to bind socket" in its log): use another port there with `--appendconfig`, along with
`config_save_on_exit = "false"`. The firewall also keeps WSL out, so run from Windows or relay. Driver states load in RetroArch's mGBA as they are:
copy one to `<game>.state9` in its state folder, then `LOAD_STATE_SLOT 9`.

## Checked, and not
Against RetroArch 1.22.2 on Windows with the buildbot mGBA (0.11-212, the same commit Dual builds): every read as `tests/mock_ra.py` predicts, the page
drawn from its live memory, the 3D view mapped on all 174 frames in play at 2-1, save to a slot and load, the menu (reads still answer with it open).
The game stayed at 60 frames a second under every load tried, including 36 KB in one frame.

The game sat still in that run; moving play was run against `tests/mock_ra.py` playing back recorded frames (every frame in play mapped there too).

**Not run on an Android device yet**: the APK builds and passes aapt and apksigner, but the app itself, the GL view over the page, and RetroArch's pace on a
phone are untested. Also the same never-focused window as the other Link apps, so the same open question about Android's "isn't responding" dialog
(Shantae Link's README).

Keystore: `tools/smalink.keystore`, alias `smalink`, not in git; keep it: updates must use the same key.

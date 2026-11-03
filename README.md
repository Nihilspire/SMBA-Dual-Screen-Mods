# Super Mario Advance Dual

One APK: Super Mario Advance (USA, Europe) on one screen and its Link screen on the other.
Built on Mario 2 Dual 0.1 (itself on Metroid Dual 0.45): same frontend, screens, controls, pacing and menus, with the
NES core swapped for a GBA one.

- **Emulator:** mGBA, libretro fork (MPL-2.0, github.com/libretro/mgba @ 7a12d6d) plus `native/mgba-dual.patch`
  (NO SLOWDOWN overclock, a vblank-wait counter, and a write-trace hook for desktop builds). mGBA's `libretro.c` is
  compiled into `native/gbacore.c` (its `core` is static), statically linked into `libsmadual.so` with the JNI layer
  (`native/gbajni.c`), the play-stat tracker (`native/smatrack.c`) and the first-start menu capture
  (`native/extract.c`). Built-in HLE BIOS: no BIOS file needed. mGBA's licence text ships in
  `assets/licenses/mGBA-MPL-2.0.txt` and SETTINGS > SYSTEM names it; its source (with our patch) is in the source zip.
- **Nothing from the game ships in the APK.** You pick your ROM on first launch (CRC32 `1E4C6D6A`). The font, the
  metatile tables, the menu's graphics and every picture on the Link screen come from that ROM and the running game's
  VRAM.
- **Battery save:** the game's own EEPROM save is kept in the app's files (`game.sav`) and written when you pause,
  quit or leave the app.
- **RAM / ROM map:** see [RAM-MAP.md](RAM-MAP.md).

## Screens
| Setup | What happens |
|---|---|
| Surface Duo, app spanned | Game on one screen, Link on the other, split at the hinge. |
| Second display (AYN Thor style, cast/HDMI) | Link goes to the second display as a Presentation. |
| One screen | Game in 3:2 with borders themed from the level's own blocks; hearts, lives, cherries, Ace Coins, level, character and play time in the borders. Link via Pause > LINK SCREEN or Settings > LINK SIDE PANEL. |
| Anything else | Settings > SCREENS > SPLIT: SIDE BY SIDE / TOP + BOTTOM (+ HINGE GAP), SWAP SCREENS. |

## Controls
Touch pad (auto / on / off, opacity), controllers with rebinding (Settings > CONTROLS > CONTROLLER BUTTONS; L and R
are bindable too), **SELECT+START** or Back opens the pause menu, quick save / load (L2 / R2 by default), turbo,
fast-forward.

## Launcher and pause menu
0.8 look (calmer than 0.4): dark ink on cream, a soft gold selection bar, red titles, ON/OFF as coloured words, muted category chips; Link screen cards with a thin brown outline, cream tabs, softened bands and world colours. 0.4 had: bright orange-red selection bar with white text, big text on the main pages when it fits, panels sized to
their contents, red titles, ON/OFF pills, a coloured chip per settings category. Link screen: the title screen's sky
and ground behind the cards (from the same capture; a drawn sky without it), cards with the pause panel's orange
ring, coloured tabs (STATUS red, MAP green, CHART blue, OPTIONS yellow), header ribbons, a colour per world on the
chart, the status card's band in your character's colour.
Drawn in SMA's own font. **Menu graphics from the ROM (0.2):** on first start (and after picking a new ROM) the app
boots the game once in the background with an empty, throwaway save folder, walks title > file > character select >
1-1 > pause by itself (it waits on the game's state bytes, so it never depends on timing), and keeps the title,
1-1 and pause screens' video memory (~300 KB, `kit-2-<crc>.bin`; about a second on a phone). The menu then uses the title
screen's sky and ground (palms, log, water) as its backdrop, the game's own pause panel (9-sliced from its 16x16
pieces) as the card, its arrow as the cursor, the Ace Coin icon on the launcher and the panel's palette for the text.
Your real `game.sav` is never opened. If the capture fails, or SETTINGS > SYSTEM > MENU GRAPHICS is DRAWN, the 0.1
look (a drawn look-alike) is used; REBUILD MENU GRAPHICS captures again. `menu/gbakit.js` decodes the capture. Launcher music: "Curtain Call", an original shuffle synthesised in WebAudio (launcher only).
Save states: 4 slots with screenshots + an auto state on exit.
Settings: DISPLAY (3:2 / stretch, integer scale, GBA LCD colours, frame blend, borders, FPS), SCREENS, 3D VIEW,
CONTROLS, SOUND, GAMEPLAY (NO SLOWDOWN, on by default), SYSTEM.
Playtest codes: enter up up down down left right left right B A on the launcher (again to hide them): infinite life,
infinite lives, refill life, Starman, stopwatch, 4 cherries, +5 lives, set play time, **warp to any level** (takes
effect at the next character select: the game reloads the level number from the file there).

## Link screen
- **Status:** world-level, area number (the game's own, `030019FE`) or SUBSPACE, your character (live sprite from OAM), hearts (the meter's real size: 2 + mushrooms taken, from RAM), lives, score, cherries
  toward a Starman, coins, Ace Coins taken in this level, Yoshi eggs; timers (star / stopwatch / subspace), play
  time, deaths, levels cleared (the file's own bits), Ace Coin total (%). The level's five Ace Coins show which
  ones you have (this visit or already on the file).
- **Map:** the whole current area from the decoded level at `02000000`, in the game's own tiles. Close view follows
  you (drag to look around); tap for the **strip (0.10)**: the area's full height in one row (full width in one column
  for vertical areas) that follows you — it holds still while you're in its middle and eases after you otherwise —
  with a bar showing where you are in the area. 0.9 folded the whole area into several rows instead. Markers: X-ray grass (C coin, V big
  vegetable, R rocket, S shell, B bomb, P potion, 1 1UP, W POW, and SMA's own grass: H heart, N Ninji, G giant
  vegetable / POW / Shy Guy, E Yoshi egg), cherries, M mushroom spots, live objects (white enemies, yellow items).
  **Ace Coins (0.3):** every Ace Coin in the area, read from the level's object list in the ROM, even before it
  spawns — red where you still need it, pale where it's on the file or taken this visit. A line underneath lists
  what's hidden in the area, Ace Coins left first.
  **Item icons (0.10):** the game's own sprites for potion, mushroom, 1UP, POW, hearts, cherries, coins, Ace Coins,
  shell, bomb, Bob-omb, big vegetable, Ninji, Yoshi egg, Starman and stopwatch, cut from 1-1's sprite sheet at first
  start (`menu/gbakit.js`, table in RAM-MAP.md). They replace the letters on the map, the drawn hearts / cherries /
  coins on the status card, the words in the line under the map (now icon × count), the timers and the grass legend,
  and mark the playtest codes in the menu. Without a capture the 0.9 look stays.
  **0.11:** markers from the area's object list for the **key**, the **crystal ball**, the **exit** (Hawkmouth) and the
  **boss** (by name); rocket / giant vegetable / giant Shy Guy icons are taken from the live picture the first time
  one is on screen (they aren't loaded in 1-1 at start). Both map views follow you like a game camera (still while
  you're in the middle 40%, easing after you otherwise); the close view used to re-centre every update. The strip is
  drawn from an area-averaged copy at a whole-pixels-per-cell scale, 1:1 — every pixel the same size, no dropped rows.
  Readability: all text on the cream cards is ≥ 4.5:1 contrast (names, labels, values), the "you" marker and the
  chart's current level are steady instead of blinking, empty cherry / Ace Coin slots are tan silhouettes, map icons
  have a drop shadow, and the launcher's bottom lines sit on dark plates.
  **0.12:** zoomed out — both views show 3 GBA screens across by default (pinch the map, or OPTIONS > MAP ZOOM,
  1–6 screens); the map tracks only you (no enemy / item dots); you're a map pin instead of the upright block that
  looked like a door; mushrooms and the 1UP you've already taken in this level drop off the map and the count below it
  (the game's own flags, so it holds after continuing or loading a state). Map drags follow the finger at any zoom.
  **0.13** tried three screen styles (Map First, Daylight, Classic); **0.14** keeps only the cream cards. **Portraits
  (0.13):** one fixed picture per character and size (big, or small on the last heart), taken once while you stand still
  on the ground (not carrying, not on a vine, fully on screen) and kept; before the first one there's the initial.
  **0.14 — zoom levels:** tap the map to step through four zooms (dots in the corner, the level's name for 2 s after a
  change; also OPTIONS > MAP ZOOM). For horizontal areas: wider, **FIT** (the area's whole height fills the map),
  halfway, 1:1; vertical areas (about one screen wide, so FIT is nearly 1:1) spread the steps further out. Drag to look
  around; about 4 s after you let go the view eases back to following you. An area shorter than the map sits on its
  bottom edge (no empty band under the ground). Pinch zoom and the 1–6 screens setting are gone.
  **0.16 — Yoshi Challenge** (the post-game mode on a beaten file; found with two 100% GameShark saves): the map marks
  each level's two Yoshi eggs where they appear in Subspace (faded when the egg is already on the file, gone once taken
  on this visit), the status card shows the level's two egg slots, the timers card shows EGGS n/40 instead of levels,
  and the chart shows two egg pips per level plus the total. Also new in every mode: SMA's added third mushroom spot is
  marked (it was drawn as sky), and in the Yoshi Challenge the mushrooms the game leaves out drop off the map. The
  status card's egg count read a bitmask as a number (both eggs showed "x3").
  **0.18:** the map's sky in world 7: palette colour 0 there is black behind a pale sky layer, so the map drew a black
  sky. When colour 0 is dark the map now takes the far layers' most common colour along the top of the screen (as the 3D
  view does), falling back to colour 0 (dark caves stay dark).
- **Chart:** 7×3 levels, each with its five Ace Coins from the file (which ones, not just how many) plus this visit's
  in the current level; levels cleared on the file underlined (in the colour of who cleared them when the Dual
  tracker saw it, dark otherwise), warped levels, Ace Coin total, levels cleared.
- **Mario Bros.:** in the Classic Mario Bros. game: phase, enemies left, score, lives.
- **Options:** save slots, map options, grass legend.

## 3D view (experimental)
Settings > 3D VIEW (or R3 / F2). Each 8x8 cell of the level layer is textured from the live tile map and built from
the metatile under it: groups 1/2 (stand-on / solid) become blocks, partly transparent scenery (grass, cherries,
palms, vines) cut-outs, opaque scenery (hill bodies) the back wall. **Background:** the game's own parallax layers
(BG2 under BG1) on a far plane; the HUD layer flat in front. Camera presets, block depth, back wall, background,
player light. `Scene3D.java` builds the geometry (no Android code); `Renderer3D.java` draws it.
**0.4:** the 3D view only draws 3D during actual play (in a level, not paused) after 8 frames of play, and only while
the level data matches the screen; everything else (title, file / character select, the chart, pause, transitions)
shows the plain 2D picture in the same view. The camera and the vertical/horizontal layout are now picked by which
candidate matches the screen best (0.3 trusted the vertical flag, which the game sets late, and lost the match for
whole stretches of 1-1's opening drop, flipping between 3D and a flat wall). Far layers are drawn only where they are
on screen (the rest of their maps is stale). `tests/scene3d/SceneSeq` runs one Scene3D over a sequence of dumps.
**0.5 (low camera):** cells past the screen edges (4 tiles all round) are built from the level data and the ROM's
tile quads, so turned and low cameras see the level continue instead of a hard cut; the far layers fade into the sky
at the screen edges; the sky colour is the far layers' colour along the top of the screen when palette colour 0 isn't
what you see (7-1: black behind a light sky); the camera search covers every wrap in the area (the player's position
is wrong while falling into a pit or dying). Desktop renders: `SIZE=1920x1080 python3 tests/scene3d/shot.py out.png
f.s3d low`.
**0.6:** hills (and anything else with a stand-on cap) are one solid block: opaque scenery directly under a block,
down to the first gap, is built as block too. Before, the cap stuck out as a separate slab over a recessed body.
**0.7:** stacked hills sit at different depths. A hill whose left edge or cap cuts into another hill's body was drawn
over it, so it is in front; each hill (a connected piece of body/cap metatiles) comes forward by 0.75 of the block
depth per hill it stands in front of, with side faces where it steps out.
**0.8:** sprites (Mario, enemies, items) stand on the blocks' front face instead of the middle of their depth, so
their feet meet the ground's front edge (at z = 0 they looked like they were floating); on a hill that comes forward
the sprite plane comes forward with it.
**0.9:** affine sprites are drawn with their OAM matrix (scale, mirror) around the centre of their box, double-size
boxes included. SMA's giant Shy Guys are 32x32 sprites in a 64x64 double-size box, mirrored by the matrix; drawn at
the box corner they floated 16 px above the platform.
**0.11:** sprite-built platforms (object class `0D`: 1-1's big striped block, Hawkmouth) have the blocks' depth: their
sprites are drawn as 9 slices from the back face to the front, darker toward the back, so they read as solid.
**0.12:** a character stands at one depth: sprites that touch (head and body, a carried item) are grouped and the
group's feet pick the hill depth. In 0.11 each 16x16 half used its own bottom edge, so on a stepped hill Peach's head
and body were drawn at different depths and came apart.
**0.14:** hill corners: SMA has several cap variants (left `11B/11D/123/125`, middle `11F`, right `11C/121/124/126`)
that weren't counted as hill, so their corners went missing or sat at the wrong depth; they're part of their hill now.
Block faces use a copy of their tile with the clear pixels filled from the nearest opaque colour, so rounded caps and
corner tiles don't leave holes in solid blocks. The ground under a hill that steps forward comes forward with it
(it was left behind, so the hill overhung empty space). OBJ-window sprites (mode 2: never drawn by the GBA, they only
mark where a window effect applies — SMA's light through an open door) are skipped; they were drawn as a red/white
shape in caves.
**0.15:** a sprite drawn over the level layer stays in front of every hill it overlaps on screen (SMB2/SMA hill bodies
are scenery you walk and fly in front of). It took the depth under its feet only, so in the diorama and low cameras
Peach floating past a hill that steps forward, or anyone on the ground in front of one, went behind the hill's face.
Checked over 12 saved spots × all five cameras (`SIZE=1280x720 python3 tests/scene3d/shot.py out.png f.s3d diorama`).
**0.17:** world 4's ice walls. The game redraws wall metatiles `0x26E`–`0x2C5` with tile base `0x200` and palette 2
when the world's graphics set (`030019FC`) is ice (and with palette 6 under another flag); the 3D view, the Link map
and the border theme used the ROM's tile table as-is, so in real 4-1/4-2 the 3D view's screen check failed (flat
picture) and the map drew those walls with the wrong graphics. Older test spots warped by poking the level number
never set `030019FC` (world 1's graphics everywhere), which hid it; captures now set it too.
**0.19:** pyramids (2-1). They are scenery in the level layer, and the 3D view split them by tile coverage: the slope
tiles as cut-outs in the middle of the depth, the opaque fill on the back wall, so each pyramid broke into a stepped
outline floating in front of a sunken middle. A pyramid is now found from its metatiles (apex, slopes, base row; see
RAM-MAP) and drawn as two sloped faces of a real pyramid seen corner-on, as the art shows it (light left face, dark right
face): the outline stays on the back plane and the corner facing the camera comes forward to 0.85 of the block depth.
Nothing else in the 3D view changed (23 test spots compared with 0.18; only 2-1 differs).
**0.20:** sprites next to stacked hills and logs over waterfalls (1-1's main area). A sprite's depth comes from the hill
under its feet or any hill its box overlaps (0.15), which changes in whole steps of 0.75 of the block depth: a Shy Guy
walking along a hill step snapped 21 px on screen in one frame in the diorama view, and at a step's edge flicked
forward and back on consecutive frames. Each sprite group now eases to its depth (a fifth of the way per frame, matched
to last frame's group by its feet; at most 0.6 px of extra movement per frame over a 240-frame 1-1 recording). The
log bridges: the ground under a forward hill comes forward with it (0.14), and so did the log's end piece under the
hill, while the rest of the log stayed at the back, so the log kinked into a notch between its banks. A run of
blocks in a row between two blocks that sit further forward now takes the nearer one's depth (also closes the notch
in the ground between two forward hills). 123 spots over 11 levels compared with 0.19; only those two kinds differ.
**Ground lip:** ground and other solid blocks stand 0.375 of the block depth in front of the hill scenery at the same
nest. Flush with the cliff behind them, characters looked pasted on the wall; now they stand on a strip of grass in
front of it (less than a hill step, so a hill that steps forward still covers the ground behind it).
**Thick sprites** (Settings > 3D VIEW > THICK SPRITES, on by default): every sprite pixel is a little block. Each sprite
tile keeps its flat front and gets walls 4 px deep going back from it along every edge where an opaque texel meets a
clear one, in that texel's colour shaded like the block faces (lit tops, darker sides and undersides); affine sprites
(giant Shy Guys) are extruded through their matrix. Sprites are drawn in their own depth pass (depth cleared, LEQUAL so
the higher-priority OAM entry still wins a tie), so they stay in front of the terrain as before but hide their own
walls. Not extruded: the life meter (HUD) and sprite-built platforms (already sliced through the block depth).
Desktop testing from a battery save: `tests/scene3d` as before, levels reached through the Yoshi Challenge picker.
**0.21:** the character select (shown before every level) was a purple slab: BG3 was always drawn flat on top as the
HUD, but on that screen it is the backmost layer (priority 3, the backdrop and stage), and it covered the characters
and the text. BG3 is the HUD only while no other layer is in front of it; otherwise it joins the far layers, behind
BG2/BG1 by priority. The light through an open door (3-3) is an OBJ window inside which the game brightens everything
by BLDY/16; the 3D view skipped the window sprites (0.13) but had no effect, so the doorway was a grey slab and the
shaft was missing. The window (union of the mode-2 sprites' pixels) now goes out as one quad per run of pixels per row,
drawn white (or black, for darken) at alpha BLDY/16 at the end of the sprite pass. The sky colour, taken from whatever
far layer covers the top of the screen, flipped every few frames as the camera moved (5-3's cave between black, brown
and olive rock; 5-1 between clouds and bushes) and the space round the level flashed; it now eases (8% per frame) and
snaps only on a new area or screen.
**0.22:** the Link map lost you in two places. Subspace is built as one more page after the area's last (x ≥ pages ×
256) and the map read only 10 pages and clamped its view to the area, so the marker left the map; it now reads 11 pages
and shows just the subspace page while you're in it. And 1-1 opens with the vertical flag still clear while you fall
down a vertical area, so the map laid it out sideways; like the 3D view it now takes the layout whose level data matches
BG0's tile map (until a picture settles it, being below the first 15 rows means vertical). The 3D cameras are down to
three, LOW / TILT / FAR (diorama and side read as tilt), and a bindable 3D CAMERA button (F3 by default) steps through
them. Ledges flicking back a step on phones (worst from the low camera, e.g. walking 1-1's log) was depth precision:
a 16-bit depth buffer with the near plane at 10 resolved only ~0.12 units at the scene, coarser than the gaps between
some faces, so they z-fought as the camera moved. The view now asks for 24 bits (16 if that's all there is) with near /
far at 40 / 2000. Desktop SwiftShader always had the precision, so the harness never showed it.
Clouds (metatiles 101-103: 1-1's climb, 7-1) were solid slabs: block tiles fill their clear pixels with the nearest
opaque colour (for hill corners), which on a cloud is the dark outline, so every cloud had a thick black rim and dark
sides. They are cut-outs now (clear texels dropped on every face) and their top / bottom / side faces take the tile's
middle row / column, the white body, instead of streaking the outline through the depth.
**0.23:** RAM mapping for the screens the Link page didn't know (RAM-MAP.md, "Mode 3 sub-modes and the ending"): the
game's sub-mode `03001991` is 2 in Bonus Chance, 4 in the ending, 5 / 6 on the Yoshi Challenge screens, and the ending
steps `03001992` through its own 23 stages (Wart's room, the parade, the MVP stage, Mario's dream, the cast roll and THE
END). The MVP stage's numbers are levels cleared per character, kept on the file at `03001148`-`0300114B`. The Link
page now shows a BONUS CHANCE card (spins left, lives) and, through the ending and credits, a summary card (each
character's levels cleared with the MVP marked, then time, deaths, levels, Ace Coins, eggs) instead of the stale level
cards. The tracker now counts 7-2 as cleared (and by whom) when the ending starts; there is no next level to move to,
so it never did.
More addresses (RAM-MAP.md, "In-level states, player state, cursors"), found by recording IWRAM every frame through
a pit death, the potion door, ducking and the menus: the level's own states (life lost, GAME OVER and its menu, door,
Subspace, TRY AGAIN), the player state `03001C6F` (7 dying, 0x0B at a door), the power-jump charge `03001C7F` (full at
60), the character select cursor `03001E01` and the pause / game-over menu cursor `03001A63`. The Link page's status
line now says GAME OVER, OUCH or POWER JUMP when they apply, and at character select the status card follows the
cursor (name, colour, portrait) instead of showing the last character played.
**0.24:** clouds no longer vanish from the 3D view near the top of a vertical area (reported on 1-1's vine climb: the
cloud under the player and the one below it went as the camera scrolled up). The 3D view picks the area's layout
(vertical pages or horizontal) by matching the level data against BG0, because SMA's vertical flag can't be trusted
(it read 0 on the vine climb). Near the top, the horizontal reading is page 0's 15 rows, the same data, so it matched
every cell it had and simply left out the rows below; it tied with the right layout and won on the flag. Those rows
then had no level data and their clouds were dropped as sky. A cell that a layout puts outside the area now counts as
a miss when BG0 draws something there. The same tie hid the clouds below the start door for the first frames of 1-1;
the other levels' dumps are unchanged.

**0.25:** clouds are puffy pillows instead of a box per tile. Each cloud is inflated from its own outline: a distance
field over its opaque pixels sets a rounded height (0 at the edge, full 6 px in), front and back bulge either side of
the block's middle and meet at the outline, and the shading follows the curve. The black of the checkered tops turns
the cloud's pink on them, because black was harsh on the curves. The DIORAMA camera is back (LOW / TILT / DIORAMA / FAR,
on the menu and the 3D CAMERA button). The busiest cloud frame (7-1) gains about 21k vertices.

**0.26:** more things get their own shape in 3D, all built with the cloud's rounded-edge field (`Scene3D.Field`) or
simple solids, the game's art always the texture:
- **hills** keep flat fronts, rounded off (quarter round, 5 px) wherever a hill's front ends, including where one hill
  steps in front of another;
- **jars** (`21A`–`21F`) are upright cylinders with a lid, **logs** (`220`–`222` outside the desert) lying cylinders
  with cut ends; the art wraps round by angle, so the outline doesn't smear over the curve from the side cameras;
- **water** (`004`/`005`, and `008`/`00A` in world 4) is a volume instead of a wall of waves: its front a quarter block
  behind the ground's edge and a level surface from there back, textured with the water body;
- **grass, vines and palm tops** get a second copy crossed at 90 degrees (only noticeable from the side cameras: their art
  is on the metatile's centre line, so the copies line up from the front);
- **ice, bone and sand blocks** get a 2 px bevel on each block, so neighbours meet in a groove;
- **ROUND SPRITES** (3D menu, off by default) inflates sprites from their outlines like the clouds, per object, so a
  character made of several sprites rounds as one.

The ids are checked per world graphics set (`030019FC`), since `220`–`222` are logs in world 1 and bone blocks in the
desert. Over the 714 test frames, the camera, layout and map match are unchanged; the mean vertex count goes from 11k to
21k (mostly the hills' rounded edges) and the peak stays 46.6k. Renders of each in all four cameras: `out/renders-0.26/`.

**0.27:** logs clipped in 0.26. As a round log in the middle of the block's depth they sat behind the banks' fronts (the
player floated in front of them), and the banks still left out their side face next to them as if a full block were
there, so sky showed round the log. A log now fills the depth like the box it was, round at the front and the back
with a flat wooden top and bottom between, and banks (any block) keep their faces next to logs and jars.

## Build
```
bash tools/setup_toolchain.sh      # bionic headers + stub libs + compiler-rt builtins, android.jar, mGBA @ 7a12d6d + patch
bash tools/build_apk.sh            # -> out/Super Mario Advance Dual <ver>.apk
```
apt: clang lld llvm aapt zipalign apksigner dalvik-exchange openjdk-17-jdk-headless.
`out/android/libsmadual.so` is included so the core doesn't have to be rebuilt. Keystore: `tools/smadual.keystore`
(pass: smadual).

## Test
- `make -C native TARGET=host MGBA=... host` -> `out/host/drive rom script [savedir]`
  (run/tap/shot/dump/save/load/peek/poke/rec/trace/rtrace/oc/lag/extract). `extract file` runs the first-start
  capture on a freshly loaded ROM (use an empty savedir). `dump` = IWRAM | EWRAM | IO | PAL | VRAM | OAM.
  `trace lo hi max file` logs every CPU write in a range with PC and LR; `rec N buttons file` appends IWRAM per frame.
- `tests/linktest.py rom dump out [js]`: Link page in headless Chromium with a mock Native fed from a dump.
- `tests/menutest.py rom thumb.png outdir [kit.bin|-] [standin]`: launcher / pause / settings, with a capture
  from `drive ... extract`; `standin` swaps every ROM piece (and the font) for plain stand-ins for preview images.
- `tests/scene3d/`: `build.sh`, then `SceneDump rom dump out.s3d` and `shot.py out.png out.s3d [preset] [standin]`
  renders the 3D view with Renderer3D's own shaders in headless Chromium (WebGL).

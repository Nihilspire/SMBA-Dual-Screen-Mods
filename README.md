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
- **Super Mario Advance Link** is the second APK: the Link screen on its own, reading the game in RetroArch over its network commands, with a 3D tab only
  it has. See [linkapp/README.md](linkapp/README.md).

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

**0.28:** the ending card (as Metroid Dual's mission report and Shantae Dual's QUEST COMPLETE) replaces the ending's summary
card. All through the ending and credits (sub-mode `03001991` = 4) the whole Link screen shows SUBCON IS FREE: the real date
and time, play time, deaths, levels, Ace Coins, score, Yoshi eggs if any, each character's levels cleared with the MVP in
gold, DEV CODES USED if any were, and a field you sign with a finger (CLEAR / SAVE). The stats are taken once, after the
ending's init has counted 7-2. SAVE keeps the run with the signature as a small PNG in localStorage `sma.hall` (last 20;
nothing shows the list yet). PLAYTEST CODES > ENDING CARD ON/OFF opens the card on the Link screen without touching the game,
because jumping into the ending's later stages from a level freezes the picture. The status card's score now matches the
HUD (the file holds the score / 10).

**0.29:** a check of the 3D view against the 2D picture, every world, every camera:
- **The sky.** Levels have no sky picture: the game rewrites colour 0 down the screen from a 16-colour table at `02002B20`, and
  the 3D view only saw what colour 0 ends the frame as (white, or navy in 2-1). The far plane now has the real gradient.
- **The world no longer ends in mid-air.** Terrain was built for the screen + 32 px and the far layers for the screen alone, so
  FAR and DIORAMA showed a slab against an empty sky. `Scene3D.setCamera` works out how far each side of the view reaches and
  `build` fills that. Past the area's edges solid ground and water run on (hill bodies downwards; pits stay open).
- **The far layers go on past the screen**: real map rows up and down, real columns behind the camera. Columns ahead were never
  written (they hold fill entries or a menu's tiles); dense scenery takes the column 256 px over there, which is only a fair
  guess since the layers have no real period, and sparse clouds fade out past their last known column instead.
- **Subspace is 3D** (it fell back to the flat picture): the extra page after the area's last, each metatile mirrored as the game
  draws it, darkened by the game's own BLDCNT.
- Natural ground and rock round off where they end, like the hills; the hills' rounding is now worked out from the neighbouring
  cells instead of a per-pixel field, and only on exposed edges. The desert's cacti are round like the jars. Cloud shadows are
  pink instead of grey (LOW showed them dark), and the desert's soft floor (`050`, no collision) comes forward from the back
  wall to where sprites walk.
- Build time per frame is about half of 0.27's at TILT and LOW and still below it at FAR and DIORAMA (desktop, same frames).

**0.30:** NO SLOWDOWN and DMA. mGBA starts a DMA 3 cycles after it's requested, and with instruction cycles halved the next instructions can run first, so a late DMA can overwrite what they just wrote. It broke Metroid Fusion's intro (a late memory clear wiped a pointer). No scene in this game was seen to break: over a walk from a new game and nine saved spots the overclocked picture is identical before and after. It's a precaution: a DMA now starts before the next instruction while the overclock is on (`native/mgba-dual.patch`, `src/gba/dma.c`).

**0.31:** the second screen's windows are the game's own. They were flat cream cards with a brown line over a backdrop washed
out with white, close to the game's pause panel but not it. Now every card is the real panel from the first-start capture
(scalloped edge, corner pins, as the pause menu uses) over the title screen's full-colour backdrop, and content keeps inside
the scallops. The words follow the panel too: each letter one solid colour (`G.solid`: the font's second tone was drawn darker,
which the game's windows never do), the panel's deep orange for labels and its darkest for values, its plain orange for big
titles, instead of red, blue and green labels. Labels stay a step darker than the game's own orange (`ef9452`, about 2:1 on the
panel) so small print reads. An empty cherry, Ace Coin or egg slot is now the real icon, dimmed, not a tan silhouette. On a narrow
chart the Ace Coins are five pips instead of a count that read as part of the level ("1-15"). Without a capture yet, the drawn
card stays, in the same place.

**0.32:** the ending card is for signing with a finger. The title is gone and the stats fold into a small block: the date, two
columns (time, levels, deaths, Ace Coins, score, warps) and one row of characters, so the field is 216 x 88 on the Thor's lower
screen instead of 216 x 35 (25 with dev codes on). WARPS is new: the levels skipped with a warp jar, which is why LEVELS can end under 20. The pen is 2 px
instead of 1, and it reads every touch sample (`getCoalescedEvents`), eases them and joins them with curves, so a fingertip's wobble
doesn't show. A stroke can start just outside the field, a second touch is ignored while one is going, and CLEAR and SAVE are half
the field wide and 18 px tall (they were 52 x 11) and still press if the finger rolls a little.

**0.33:** the ending card's character row shows the characters themselves, each beside its clear count with MVP above the count.
The sprites come from the ROM (`S.CHAR_SHEET`, RAM-MAP.md "Player sprites"), so all four are there even if you never played one.
The Thor's lower screen and other short layouts use the game's small frames, which takes the field from 216 x 88 to 216 x 74; tall
layouts use the big frames. Without the ROM yet the row falls back to each character's initial on a coloured badge.

**0.34:** two things.
- While the game is on its own menus (the title, Choose a Game, the file screens) the second screen goes quiet: the game's logo,
  dimmed and centred on black, as Xenogears Dual does in cutscenes, instead of "NO MAP HERE". The logo comes from the ROM. Tapping it
  opens OPTIONS for the save states, and any other tab goes back to the logo.
- The insides of pots in 3D. A pot's BG1 is one picture with black all round it, lying under BG0's outline of the pot, but it was
  treated as far scenery: on a far plane, so the black slid in behind the walls from any turned camera, and repeated past its edges,
  so other screens' tiles and black stripes showed around the pot. Now it's the pot's back wall, right behind the outline with no
  parallax, with plain black outside. The walls' sides are tan instead of grey. Pots that use the extra page weren't 3D at all (they
  fell back to the flat view): that page is always page 10, not the one after the area's last, which only held in areas of 10 pages,
  and only Subspace is mirrored. That also fixes Subspace in shorter areas, and the map, which said "NO MAP HERE" in those pots.

**0.35:** two things.
- Vertical areas are laid out right from their first frame. The map showed their pages side by side until the camera first moved down,
  because the game's flag stays clear until then and the first page reads the same either way. The area's object list in the ROM says
  which way it runs (objects are placed in world tiles), so the map and the 3D view now go by that (RAM-MAP.md).
- The chart shows who cleared a level with that character's own sprite, head and shoulders, instead of a coloured line, and the
  legend is gone. The coins moved under the level's number to make the room.
- Ladders in 3D. A ladder you can stand on (`115`, `116`) was a block because of its collision, and one hanging under a block (`03E`,
  `03F`) counted as part of that block's body, the rule for hills and houses. Either way it became a solid slab, out of line with the
  ladder above it, with its gaps filled in dark green (the colour of the rails' outline). All of them now stay see-through.

**0.36:** the app has its own icon: the owner's Toad and DUAL set from Pixel Icon Lab (`app/res/mipmap-*/smba_dual*`), with adaptive layers
and a themed monochrome layer on Android 8 and up, and plain and round PNGs below that.

**0.37:** the map has the game's own background behind the level instead of one flat colour: the far layers and the sky gradient, read
from video memory, or a pot's picture. The layers scroll slower than the level, so they have no one place in the area: they tile at the
level's scale, using columns that have been on screen, as the 3D view does, so the picture fills in as you go. Subspace has no far layers
on and keeps its plain colour.

**0.38:** solid tiles that are partly clear, in 3D. A block's clear pixels took the nearest colour, usually the dark outline, which is what made
the ladders green in 0.35. An audit of every area of every level found 96 such tiles, and those not already rounded some other way now get
a shape that fits them:
- World 4's whales, half-height bars and platforms (`106`, `10C`, `10E`, `10F`), mushroom blocks (`214`), the boulder (`20A`) and the like have
  their clear pixels cut out, with walls running back from the real outline, coloured from just inside it.
- World 4's sand pillars (`208`, `209`) and world 7's columns (`242`, `243`) are round, like the jars and cacti. As boxes their clear sides filled with the dark outline.
- A vine's top (`127`) and 7-2's chain (`105`) stay see-through and cross like the other vines. A vine under a block no longer joins the block.
- The sea round a whale (`00C`, `011`, `016`, `017`) is water, not a stack of striped blocks.

**0.39:** more of the same, from play.
- Jars topped with `2C6` (the rows in 6-1, 5-2's) had a cube for a top: the jar's art is shared by `2C6`, `1F5` and `149`-`14E`, which now count as
  jars. Upright round things have no back half any more: its outline showed through wherever the art is clear, as dark steps down one side.
- The deserts' bones (`220`-`222`) and the log ends in pots (`113`) keep their real outline. The bones sat on a filled box, and the logs' rings ran
  along their sides.
- Clouds were holed along their tops where the art is clear, which only showed against a night sky (5-2's black squares).
- On the map, a pot's picture follows the scroll instead of keeping its first position, which could be taken while the screen was changing over and
  leave it a page off.

**0.40:** who finished a level, on the chart. The game marks a level cleared as it leaves for Bonus Chance, but the tracker only took the name on the
next level's first playing frame. So the level just finished showed a dash through Bonus Chance, the chart and character select, and for good if a
state load, a resume from the auto state or a dev warp came before the next level. The tracker now takes the name as the level ends (`native/smatrack.c`).
A level already missed gets its name from the file's own count per character, when only one character has clears the tracker can't place; if two do,
it stays a dash.

**0.41:** the dimmed logo on the second screen also covers character select and the screens from there into a level (the Yoshi Challenge's, the level's
card, the level's first frames). Tapping it opens OPTIONS as on the menus, but here the other tabs work too, so the chart can still be read between
levels; the tab you had comes back when the level starts. Doors and lost lives don't show it, and Bonus Chance keeps its own screen.

**0.42:** that screen keeps the app's look, as Xenogears Link keeps its background: the sky backdrop and a scalloped window with the logo on it, the
whole page dimmed to 40%, instead of the logo on black. No tabs on it, as Xenogears drops its bars there.

**0.43 (alpha, with Super Mario Advance Link 0.1):** nothing changes in Dual. `Renderer3D` can hold its last frame instead of showing the 2D picture and its start
delay is a setting (both for Link, both off here), and the Link page has what only Link uses behind `LINK` (the setup screen, the 3D tab and its options, RetroArch's
state slots). The page in Dual is pixel for pixel 0.42's on every tab checked, and the native library is 0.42's, byte for byte. See [linkapp/README.md](linkapp/README.md).

## Build
```
bash tools/setup_toolchain.sh      # bionic headers + stub libs + compiler-rt builtins, android.jar, mGBA @ 7a12d6d + patch
bash tools/build_apk.sh            # -> out/Super Mario Advance Dual <ver>.apk
bash tools/build_link_apk.sh       # -> out/Super Mario Advance Link <ver>.apk (no native code, no setup step)
```
apt: clang lld llvm aapt zipalign apksigner dalvik-exchange openjdk-17-jdk-headless.
`out/android/libsmadual.so` is included so the core doesn't have to be rebuilt. Keystore: `tools/smadual.keystore`
(pass: smadual).

## Test
- `make -C native TARGET=host MGBA=... host` -> `out/host/drive rom script [savedir]`
  (run/tap/shot/dump/save/load/peek/poke/rec/trace/rtrace/trk/trkreset/oc/lag/extract). `trk` prints the Dual tracker. `extract file` runs the first-start
  capture on a freshly loaded ROM (use an empty savedir). `dump` = IWRAM | EWRAM | IO | PAL | VRAM | OAM.
  `trace lo hi max file` logs every CPU write in a range with PC and LR; `rec N buttons file` appends IWRAM per frame.
- `tests/linktest.py rom dump out [js]`: Link page in headless Chromium with a mock Native fed from a dump.
- `tests/menutest.py rom thumb.png outdir [kit.bin|-] [standin]`: launcher / pause / settings, with a capture
  from `drive ... extract`; `standin` swaps every ROM piece (and the font) for plain stand-ins for preview images.
- `tests/scene3d/`: `build.sh`, then `SceneDump rom dump out.s3d` and `shot.py out.png out.s3d [preset] [standin]`
  renders the 3D view with Renderer3D's own shaders in headless Chromium (WebGL).
- `tests/ra/`, `tests/mock_ra.py`: Link's transport, tracker, kit and page; see [linkapp/README.md](linkapp/README.md).
- `tests/showcase/`: the release pictures. `spots.py WORK` plays to each spot on the driver (flying along the top so the game really scrolls and spawns what's
  there, characters poked in), `render3d.py WORK OUT` renders each in every camera and the 3D menu's options, `linkshots.py WORK OUT` Link's pictures with the game on top.

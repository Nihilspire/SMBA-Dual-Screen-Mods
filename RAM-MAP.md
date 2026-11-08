# Super Mario Advance (USA, Europe) — RAM / ROM map used by Super Mario Advance Dual

ROM: *Super Mario Advance (USA, Europe)*, game code AMAE, 4 MB. CRC32 of the whole file: **`1E4C6D6A`**
(the app checks this; `Gba.SMA_CRC`).

GBA address space: `02xxxxxx` EWRAM (256 KB), `03xxxxxx` IWRAM (32 KB), `04` IO, `05` palette, `06` VRAM, `07` OAM,
`08` ROM. Every address below was checked in the desktop driver (`native/drive.c`) unless marked *(Data Crystal)*,
which means it comes from datacrystal.tcrf.net/wiki/Super_Mario_Advance/RAM_map and was only spot-checked.
The game's code is unoptimised C (Thumb, `r7` frame pointer), so variables are plain globals.

## Game state

| Address | Meaning |
|---|---|
| `030019B0` | game mode: `02` title and file menus, `03` SMB2 (character select and play), `04` Mario Bros. Classic |
| `03001991` | sub-mode. In mode 2: `01` title, `02` choose game, `03`–`04` file select, `05`–`06` later file menus. In mode 3: `00` character select / chart, `01` in a level, `02` Bonus Chance, `04` the ending, `05` / `06` Yoshi Challenge screens (see "Mode 3 sub-modes") |
| `03001992` | in a level (`1991 = 1`): `01` playing, `0C`/`0D` pause screen, `0F` unpausing. At character select (`1991 = 0`): `01` choosing, `02` leaving, `05`/`06` chart |

**In a level and playing = `19B0 = 3` and `1991 = 1` and `1992 = 1`.** `1992 = 1` alone is also true at character
select (0.1 used that and was wrong there). Character select open = `19B0 = 3`, `1991 = 0`, `1992 = 1`.
| `030019EC` | 0 in the first area entered, 1 after a door (1-1 start: 0; 1-1 after the first door: 1; 2-1 start: 1). Not the area number — use `030019FE`. Start of the level-state struct (pointer at `03001110`) |
| `030019FE` | **area number** 0–10, SMB2's numbering (1-1: 0 the cloud drop at the start, 1 the vine climb, 2 the main ground, …). It picks the ROM object list (`level·11 + area`, see Objects) |
| `03001A06` | pages in the area − 1 (1-1 area 0: 3, area 1: 9), as SMB2's `CurrentLevelPages` |
| `0300116F` | 1 in vertical areas, 0 in horizontal ones |
| `030019FC` | u16 **world graphics set** = world − 1 (0 grass … 3 ice … 6 sky). Set by a real level start; a level-number poke alone leaves it (world 1's look) unless it is poked too |
| `03001A29` / `03001A4B` / `03001A2A` | level-renderer flags (`08029EC2`): metatiles `0x26E`–`0x2C5` are drawn as `(quad & 0xFF) \| 0x2200` when `030019FC` = 3 and `03001A29` = 0, as `(quad & 0x1FF) \| 0x6000` when `03001A4B` = 1; `03001A2A` = 2 draws everything mirrored (Subspace) |

## In-level states, player state, cursors (0.23)

In a level (`1991 = 01`) `1992` indexes the table at `08346BB4` (17 handlers). Found by poking each from a 1-2 state
and by recording IWRAM every frame through a pit death and the potion door:

| `1992` | Meaning |
|---|---|
| `00` | area init, then `01` |
| `01` | playing |
| `02` | reload a level from its start (a poke went to 1-1's start) |
| `03` | **life lost**: after the dying animation (`03001C6F` = 07, ~180 frames) lives drop by one here, then `00` → `01` |
| `04` → `06` | **GAME OVER** screen (Birdo, WORLD, TOP SCORE) |
| `05` | fade (the shared `08001E2C`) |
| `07` / `08` | the menu after GAME OVER: CONTINUE / SAVE AND CONTINUE / SAVE AND QUIT |
| `09` | going through a door (the potion door: `09` → `0A`) |
| `0A` | in Subspace |
| `0B` | area transition (a poke leaves the screen half drawn) |
| `0C` / `0D` | pause panel opening / open |
| `0E` | pause panel with TRY AGAIN (from a poke) |
| `0F` | unpausing |
| `10` | restart the level (TRY AGAIN; then `01`) |

| Address | Meaning |
|---|---|
| `03001C6F` | **player state**, like SMB2's `PlayerState`: `00` normal, `07` dying (health `03001C76` reads `00`), `0B` entering a door |
| `03001C75` | player pose / animation frame (`01`/`02` walking, `0B` dying, `0D`/`0F` at a door) |
| `03001C7F` | **power-jump charge**: +1 per frame while ducking, full at `3C` (60 frames; the character flashes), 0 when released |
| `03001E01` | **character select cursor** = the character under it (0 Mario, 1 Peach, 2 Toad, 3 Luigi; Right steps 0 → 3 → 2 → 1, the screen's order). Poking it changes the name and stats shown |
| `03001A63` | **pause / game-over menu cursor** (0 CONTINUE, 1 SAVE AND CONTINUE, 2 SAVE AND QUIT, 3 TRY AGAIN), followed Up/Down in a recording |

**From character select into a level** (frame by frame, `1991`/`1992`): `00`/`00` 2 frames, `00`/`01` choosing, `00`/`02` 81 frames, on a Yoshi Challenge file `05`/`00`, `05`/`01` about 100 frames, `05`/`02`,
then `00`/`05` 2 frames, `00`/`06` about 95 frames (the level's card), `01`/`00` 6 frames, `01`/`01`. A lost life goes `01`/`03` to `00`/`00` and on the same way.

## Mode 3 sub-modes and the ending (0.23)

The main loop calls `[0833D5B4 + mode·4]` (mode 3 → `0800187C`), which calls `[0833D590 + sub·4]` with `1991`.
Each was checked by poking `19B0`/`1991`/`1992` from a 7-1 state and watching the screen:

| `1991` | Handler | Screen |
|---|---|---|
| `00` | `0802F590` | character select / chart |
| `01` | `08047024` | in a level |
| `02` | `0803268C` | **Bonus Chance** slot machine. `03001A07` (the coin count) = spins left, counting down; `03001161` lives go up on a win; `1992` = `01` waiting, `02` reels spinning. Back to `00` when done |
| `03` | `0804F8E4` | not identified (set at `080A90DE`; poked alone it freezes the frame) |
| `04` | `080554C0` | **the ending** (below) |
| `05` / `06` | `0803528C` / `0803B078` | Yoshi Challenge level screen / egg total screen |
| `07` / `08` | `08008D90` / `0800D468` | Mario Bros. Classic title / play (as reached from mode 3) |

**The ending** (`1991 = 04`): `1992` indexes the table at `08349588` (23 handlers):

| `1992` | Scene |
|---|---|
| `00` | fade (`08001EEC`, then the stage in `030019AE`) |
| `01` | init (`0805369C`): sets **`03001162` = 1** (game beaten) and **7-2's cleared bit** (`0300115F` bit 3) |
| `02`–`06` | Wart's room, the jar opens |
| `07` | the parade on the balcony. Sub-step **`03001BBB`** (`[03001110] + 0x1CF`): 0 until the walker at `03001C60` passes x `0x11F`, 1, then 2 → stage 8. (A forced start from a level leaves its x wrapping at 256; poking `1BBB` = 1 moves on. Never poke 2: entry 2 of its table at `08349988` is data and the CPU jumps to `018A01CA`) |
| `08` | **MVP stage**: each character's number over their head, the MVP's name on the stand (~15 s) |
| `09`–`0B` | fades |
| `0C` | Mario's dream (bed, thought bubble) |
| `0E` / `0F` | lead-in to the cast |
| `10` | **the cast roll** (CAST, then every character and enemy by name), then **THE END** with TOP SCORE and PRESS START; waits for Start |
| `11` / `12` | fade out, then the title (mode 2, sub 1) |

| Address | Meaning |
|---|---|
| `03001148`–`0300114B` | **levels cleared per character** (u8 each; Mario, Peach, Toad, Luigi = the `03001C70` order). The MVP stage's numbers; part of the 40-byte file. Checked by poking 1–4 and reading the stage: Mario 1, Luigi 4, Toad 3, Peach 2, MVP LUIGI |

## Save block (unpacked from the EEPROM when a file is loaded; bit-packed by the code at `080B6B40`)

| Address | Meaning |
|---|---|
| `03001140`–`03001167` | the file, 40 bytes |
| `0300114C` | score / 10, u32; the HUD adds the last 0 (108863 here shows as 1088630) *(Data Crystal)* |
| `03001150`–`0300115C` | Ace Coins collected: 100 bits, **level L owns bits L·5 … L·5+4**, LSB first (read at `0803182C`, ORed in from `03001C35` at `080347D8`). The pause screen's "A = n%" is the number of set bits |
| `0300115D`–`0300115F` | levels cleared on this file: bit L (code at `080317D2` / `08031544`) |
| `03001160` | current level 0–19 = 1-1 … 7-2. Poked at character select (after the file block is unpacked, a few frames in) it changes the level that starts; poked in a level it is undone at the next character select |
| `03001162` | game beaten: unlocks the Yoshi Challenge. **Yoshi Challenge on** = `03001162` ≠ 0 and the byte at `030019CE + [030019CC]` = `0x14` (the game's own test: HUD egg slots `0805AFC2`, egg spawner `0809B8E4`, mushroom spawner `080905CC`) |
| `03001140`–`03001144` | **Yoshi eggs on the file**, 40 bits: level L owns bit 2L (egg 1, the Yoshi Challenge screen's top row) and 2L+1 (egg 2, bottom row). Copied for the current level to `03001C38` at level start (`0802F4CC`) |
| `03001161` | lives, shown as-is next to the character icon |

## Player (IWRAM)

| Address | Meaning |
|---|---|
| `03001C4A` / `03001C4E` | X / Y, u16 world pixels (top-left of the 16x32 box). Copy at `03001C60`/`03001C62`; poke both to teleport |
| `03001168` | camera X (copy at `03001C40`). **Teleporting (0.14):** a straight poke of X/Y leaves BG0's map stale (the game only streams the columns it scrolls past). "Fly" instead: each frame poke X (+ copy), Y and the camera 4 px further, so the game streams the map as it goes. Poking Y and the camera in one jump resets the player |
| `03001C70` | character: 0 Mario, 1 Peach, 2 Toad, 3 Luigi *(Data Crystal)* |
| `03001C71` / `03001C74` | in the air / holding something *(Data Crystal)* |
| `03001C76` | health, SMB2 encoding: `$0F` 1 heart, `$1F` 2, `$2F` 3, `$3F` 4 |
| `03001C77` | **mushrooms taken in this level** (0–2). The life meter is 2 + this many hearts: the HUD loop at `080452F4` draws `[1C77] + 2` hearts; the level-start code at `0804958C` zeroes it and sets health to 2 hearts (1 if you came in with 1). Poking 2 gives a 4-heart meter. (0.1 called it a HUD copy of health: wrong) |
| `03001C35` | **bitmask** of the Ace Coins taken in this visit (same bit order as the save bits; not a count) |
| `03001C37` | Yoshi eggs taken **this visit**, bitmask: bit 0 egg 1, bit 1 egg 2 (the HUD's egg slots, `0805B04A`); `03001C38` = the level's eggs already on the file |
| `03001C88` / `03001CAC` / `03001CCF` | star / stopwatch / subspace timers *(Data Crystal)* |
| `03001A07` / `03001A26` | coins / cherries (5 → Starman) *(Data Crystal)* |

## Objects

**Live slots:** `0x70` bytes each from **`03001CF0`** (the game's own base pointer `[03005CD8]` = `03001DD0` is slot 2;
0.1/0.2 read from `03001CE8`, 8 bytes early). At least 16 slots.

| Offset | Meaning |
|---|---|
| `+0x30` / `+0x32` | X / Y, u16 world pixels (like the player). `+0x0C`/`+0x0E` hold the spawn tile X / Y |
| `+0x58` / `+0x59` | per-type parameters, copied from the ROM tables at `08362771` / `083628DC` (indexed by object type) |
| `+0x65` | `01` = live object (other values seen: `04`, `0C` = effects) |
| `+0x66` | class, from the table at `08362A47`: `08` item (Ace Coin, pulled vegetables), `0B` enemy (Shy Guy, Tweeter, Ninji …), `0C` heart, `0D` sprite-built platform |

The object's type number is not kept in the slot; it is the argument of the spawn function `08041D08(type, record)`.

**Object lists in the ROM (what each area spawns):** `0x364D84` = 20 levels × 11 areas of u32 pointers (0 = no list),
index `level·11 + [030019FE]` (`08041D40`). A list is records of three u16 — **type, tile X, tile Y** (×16 = world
pixels) — ended by a single u16 `0x00FF`. Checked: the spawn of 1-1's first Ace Coin read the record at
`0836313A` = (`6F`, 21, 8) and the coin appeared at (336, 128).

Types are **SMB2's enemy ids** where SMB2 has them (Xkeeper0/smb2 `Enemy_*`: `01` Shy Guy red, `02` Tweeter, `03`
Shy Guy pink, `0D`/`0E` Ninji, `14` Hoopster, `17` Phanto, `1A` Pokey, `1C` Birdo, `1D` Mouser, `1F` Tryclyde, `26`
Autobomb, `28` whale spout, `29` Flurry, `2C` Wart, `2D` Hawkmouth boss, `43` Hawkmouth …) — the levels they sit in
match SMB2's (Tryclyde 2-3, Wart 7-2, Flurries / Autobombs 4-1, whales 4-2, Cobrats in the desert). Checked in the
emulator: `01` red Shy Guy. SMA adds its own:

| Type | Meaning |
|---|---|
| **`6F`–`73`** | **Ace Coins 1–5.** Exactly 20 of each type, one per level. Taking one sets bit `type − 0x6F` in `03001C35` (`080A10B0`: `1 << (type − 0x6F)`), later ORed into the level's 5 save bits. So every Ace Coin's area and position is known from the ROM: 1-1 = `6F` area 2 (21,8), `70` area 2 (74,2), `71` area 4 (10,5), `72` area 1 (13,28), `73` area 1 (1,11) |
| `32`–`37` | SMA's own enemies in SMB2's vegetable/shell slots: `34` **Robirdo** (3-3's boss, class `0A`), `36`/`37` **giant Shy Guys** (1-1's first area has one on a cloud) |
| `38`–`4C` | **SMB2's item block moved up by 6:** `41` falling logs (1-1, 1-3, 5-1 waterfalls), **`43` key** (exactly the 7 levels with a Phanto), `48` Hawkmouth facing right (first area of 15 levels, where you come in), **`49` Hawkmouth facing left = the exit** (beside the crystal ball), **`4A` crystal ball** (all 20 levels; Birdo's position in x-1/x-2) |
| class `0A` | **bosses** (per-type class table `0x362A47`): `1C` Birdo, `1D` Mouser, `1F` Tryclyde, `21` Clawgrip, `2A` Fryguy, `2C` Wart, `2D` Hawkmouth (7-2), `34` Robirdo. Not every boss is in a list (4-3's Fryguy, 5-3's Clawgrip, 6-3's Tryclyde are spawned some other way) |
| class `0D` | sprite-built platforms: `48`/`49` Hawkmouth, `6C` (1-1 area 0: the big striped block with a face, OAM 6x4 tiles `904`+ pal 15), `66` |
| `74`–`78`, `6D`, `4F`–`5E`, `60`–`6A` | SMA-only or re-used ids, not identified. E.g. `78` ×5 at (0–11, 47) in 1-1 area 0 (class `0B`), `5B` at (160, 0) in area 10 of most levels |

The full per-level table (type, area, tile x/y) is generated by `tests/objlist.py` (needs the ROM).

## Taken this level (0.12)
SMB2's flags, same order: **`03001A3E`** = the 1UP grass has been pulled, **`03001A3F`** = mushroom 1 taken (subspace
spot tile `23`), **`03001A40`** = mushroom 2 taken (spot tile `24`). Set when taken (`1C77`, the mushroom count, goes up
with the mushroom flags); they last through area changes, deaths and save states. Found by taking each in the emulator
(potion door → subspace → pick up the mushroom; pull 1UP grass) and diffing IWRAM.

## Level data (EWRAM)

| Address | Meaning |
|---|---|
| `02000000` | the whole current area decoded, **u16 metatile ids**, pages of 15 rows × 16 columns like SMB2's `$6000`: page p, row r, column c at index `p·240 + r·16 + c`. Horizontal areas: page = x>>8, row = y>>4. Vertical areas: rows run on (index = row·16 + column). Pulling grass writes `0x22` (sky) into its cell. Subspace is one more page after the area's last, in every kind of area (`03001A06` + 1 = the area's pages, so a level buffer holds up to 11); BG0 draws it mirrored (each metatile's left and right tiles swap and flip), darkened by BLDCNT `00C1` / BLDY 8, with main state `03001992` = `0A` |
| `02002B20` | **the sky**: 16 u16 colours (0.29), a copy of a ROM table: `2F2BFE` day, `2F2C1E` 2-1's night, `2F2C5E` 1-1's clouds. The game rewrites palette colour 0 from it down the screen, so colour 0 itself only ever reads as the last entry at the end of a frame. Entries start on screen rows 0, 8, 16, 24, 32, 38, 44, 50, 56, 64, 68, 72, 76, 80, 84, 87 (read off the 2D picture; fixed to the screen, not the camera). All 0 in caves and 7-1, where a far layer covers everything; while the effect runs the last entry equals colour 0 |
| far-layer maps (BG1 / BG2) | hold 28 good rows (past that, leftovers of other screens). Columns stream in about 24 px ahead of the scroll; ones never written hold `00A0` / `00A1` or a menu's tiles. No fixed period (measured over maps the camera has passed whole), and the rows aren't stored raw in ROM or RAM |

Metatile id = (group << 8) | index. **The group is the collision class** (tested in the emulator for every id: Mario
standing on a floor made of it): group 0 = no collision, group 1 = stand on top / jump through (clouds, hill tops,
logs), group 2 = solid (ground, bricks); one exception, `0x202`, has no collision.

| Id | Meaning |
|---|---|
| `0x22` `0x23` `0x24` `0x93` `0x94` `0x9E` | sky (same picture). `0x23`/`0x24` sit where SMB2 has its subspace-mushroom spots ($41/$42) |
| `0x25`–`0x2F` | grass, SMB2's 11 kinds in SMB2's order ($43–$4D: coin, big vegetable, vegetable, rocket, shell, bomb, potion, 1UP, POW, Bob-omb, pulled). The potion grass on the first hill after 1-1's door is `0x2B` |
| `0x95`–`0x9C` | SMA's own grass (same picture). Pulled in the emulator next to 1-1's door: `95` Yoshi egg (giant), `96` heart (floats up; health +1), `97` giant vegetable, `98` giant POW block, `99` giant red Shy Guy, `9A` a giant round item (not identified), `9B` Ninji, `9C` giant blue Shy Guy. Giant things take a few seconds to come out. (0.1 said `96` gave a vegetable; it gave a heart in both 1-1 tests here) |
| `0x30` | cherry |
| `0x31` / `0x33` | door top / bottom |
| `0x60`–`0x62` | hill body (left, middle, right) |
| `0x11B`–`0x126` | hill caps (0.14): left `11B/11D/123/125`, middle `11F`, right `11C/121/124/126` (variants for where a cap meets another hill) |
| `0x4E` / `0x4F` | doors set inside a hill body (1-1 area 2, column 126) |
| `0x42`–`0x4D` | pyramid (2-1, world 2's graphics set): apex `42 45`, each row one cell wider on both sides — `42` left slope, `46` / `43` light fill, `44` / `47` dark fill, `45` right slope; base row `48 49 4A… 4B… 4C 4D`. 2-1 has four (columns 1–4, 8–13, 17–30, 145–158; the last with a `4E/4F` door in its dark half). The same ids mean other things in other worlds (a lone `47` in 1-1) |
| `0x101`–`0x103` | cloud (left, middle, right) |
| `0x93` / `0x9D` | **mushroom 3 spot** (SMA's added mushroom; drawn as sky). Taken flag `03001A41` (with `03001A3E`–`A40` for the 1UP and mushrooms 1 / 2). Read when Subspace is built (`0802CCE4`) |
| `0x94` / `0x9E` | **Yoshi egg 1 / 2 spot** (drawn as sky): in the Yoshi Challenge, Subspace spawns the egg there unless it was taken on this visit (`0809B8E4`; an egg already on the file comes back) |
| `0x24A` / `0x231` | ground top / ground below |

Group 0 ids are SMB2's background ids shifted: SMB2 $40–$7A → SMA $22–$5C (grass, cherry and doors line up).

## ROM (file offsets)

| Offset | Meaning |
|---|---|
| `0x3400E0` | 3 pointers (one per metatile group) to tables of 4 BG map entries per id (TL, TR, BL, BR; GBA text-BG format: tile, flips, palette). Group 0 at `0x3BBD94` (159 ids), group 1 at `0x3BC28C` (47), group 2 at `0x3BC404` (runs on past `0x28B`) |
| `0x2F3D7C` | font: 48 tiles 4bpp, `0-9 A-P × R-Z - ? . ,` (no Q; tile 26 is a times sign). Pixel 15 = ink, 13/14 = its shading |
| `0x2F437C` | HUD digits (BG tile `0x340` = 0) |
| `0x364D84` | object lists: 20 × 11 u32 pointers (see Objects) |
| `0x362771` / `0x3628DC` / `0x362A47` / `0x362863` | per-object-type tables (byte per type): slot `+0x58`, `+0x59`, class `+0x66`, spawn flags (bit 7 / bit 5 tested in `08041D08`) |
| `0x3E75D4` | Yoshi Challenge: 3 bytes per level (mushroom 1 / 2 / 3), nonzero = that mushroom is left out in the challenge (`080905A2`) |

## Screen

BG0 = level (512x256 map, char base 0), BG1/BG2 = parallax backgrounds, BG3 = HUD (priority 0, map at screenblock 25).
Sprites use 1D mapping. The camera is BG0's scroll plus whole wraps (the map wraps every 512 / 256 pixels); the
3D view and the Link page pick the wrap that keeps the player on screen.
OBJ mode 2 (attr0 bits 10–11) = OBJ window: the sprite is never drawn, it marks where WINOBJ settings apply. SMA's
light through an open door is one (DISPCNT bit 15, WINOUT `0x3F1F`, BLDCNT brighten); the 3D view skips them (0.14).

## Screens captured for the menu (first start, `native/extract.c`)

Title screen (mode 2, sub 1; up from frame ~5, no fades): BG0 `5E01` = ground strip, 512x256 (palms, log, water, hills),
scroll y 96; BG2 `1B03` = sky gradient; BG3 = the letterbox frame; the logo-less picture plus OBJs (Mario, copyright).
Pause screen (`1992 = 0D`): the cream panel is **OBJs, not a BG**: 16x16 sprites in 2D mapping, OBJ palette 2:
tile `768` corner (OAM 42; flipped for the other three), `769` top edge (bottom = v-flip), `800` left side (right =
h-flip), `801` fill; `814` the menu arrow (OAM 6), `862` the Ace Coin icon (OBJ palette 1), `771`/`775` "PAUSE".
OBJ palette 2: 7 cream, 13 parchment, 12 light orange, 14 orange, 10 brown, 15 dark brown.

### Item sprites (0.10: shot 2, 1-1 in play, frame 60)
In a level the game keeps a fixed sprite sheet in OBJ VRAM (2D mapping, 32 tiles per row); OBJ palettes are the same
in play and on the pause screen, but the pause panel overwrites part of the sheet and tiles 4–7 / 36–41 animate. Each
item's tile and palette were read from OAM while it was on screen (pulled from grass poked into the level buffer,
subspace through a potion door, the HUD). All 16x16, top-left tile:

| Item | Tile | Pal | | Item | Tile | Pal |
|---|---|---|---|---|---|---|
| Cherry | 0 | 1 | | Key | 142 | 1 |
| Crystal ball | 4 | 1 | | Stopwatch | 154 | 1 |
| POW (letters animate) | 6 | 1 | | 1UP mushroom | 156 | 1 |
| Potion | 8 | 1 | | Bomb (fuse = 228, 8x8) | 158 | 1 |
| Shell | 16 | 1 | | Ninji | 274 | 1 |
| Floating heart (grass 96) | 80 | 1 | | Mushroom (life-up, subspace) | 280 | 1 |
| Coin (subspace) | 90 | 1 | | Starman | 282 | 9 |
| Yoshi egg | 94 | 14 | | Vegetable (sprout) | 332 | 4 |
| Life meter: full / empty heart | 130 / 128 | 1 / 2 | | Big vegetable | 334 | 4 |
| | | | | Bob-omb | 344 | 7 |

Loaded only when spawned (not in the capture): rocket (`400`/`404`, 32x16 ×2, pal 3), the giant items (`896`+,
32x32 affine; same area as the pause panel), grass coin pop (`864`, 8x8, pal 8). OAM 0/1 = the life meter's hearts.
**0.11:** the Link screen takes these from live VRAM the first time OAM shows them (`rocket` 400+404 pal 3, `giantVeg`
908 pal 4, `giantShy` 920 pal 1, `giantShyBlue` 920 pal 2; 2D mapping) and keeps them in localStorage (`sma.live.*`).

### Player sprites (0.33)
Each character has a sprite sheet in the ROM, uncompressed, in rows of 32 tiles (0x2000 bytes per character for the part below).
It is what OBJ VRAM holds from tile 512 in a level, and OBJ palette 0 is the character's. Found by starting 1-1 as each character
at character select and searching the ROM for the player's tiles (poking `03001C70` in a level doesn't reload them).

| Character | Tile 512 at | OBJ palette 0 at |
|---|---|---|
| Mario | `2EAB5C` | `305A84` |
| Luigi | `2ECB5C` | `305AA4` |
| Peach | `2EEB5C` | `305AC4` |
| Toad | `2F0B5C` | `305AE4` |

Standing is OAM 4 above OAM 5, 16x16 each, 2D mapping, h-flipped when facing right: tiles `512` + `516` with 2 or more hearts,
`576` + `580` with one. The small frames are 19 to 23 px tall, the big ones 27 to 32.

## Title logo (0.34)

The finished title screen's logo (Mario, Birdo, SUPER MARIO ADVANCE) is its BG1 (`9C86`: 8 bpp, char base 1, map at screenblock 28,
256x512), which appears after the character parade, or at once if Start is pressed. All of it is raw in the ROM: tiles from `32DB10`
(312, 64 bytes each), map rows 14-29 from `3380D0` (32 u16 per row), palette from `332B10` (the entries it uses match the live BG
palette). The ink is 202x125, at (20, 2) in those 16 rows.

## World 4's whales and pillars (0.38)

Whales (4-2, graphics set 3): `24E`-`25A` for the edges, tail and spout, `10A` along the back, `03B` / `03C` for the body. Palette 1's colour 2, the
outline, is dark green, and the purple runs up through colours 3-7. They sit in the sea on `00C`, with `016` / `017` / `011` where they meet it. Sand
pillars (4-1, 4-2, 4-3): `208` top and `209` body, about 13 px wide with clear either side.

## Ladders (0.35)

Six metatiles draw the same two tiles (`94`, `95`, palette 2, clear between the rungs): `03E` and `03F` (group 0), `115` and `116` (group 1,
stand on top; 3-2's ladders end in them at the floor), and `0B4` and `0B5` past the end of their table. Palette 2's colour 2, the outline
of the rails and rungs, is dark green.

## Which way an area runs (0.35)

`0300116F` stays 0 when an area loads, and in a vertical area it only turns 1 once the camera has moved down (it held 0 through 150
frames after loading in every area tried). At the top of a vertical area the screen can't tell either, as both readings of page 0 are
the same. The object lists can: their tile coordinates are world tiles, so an object at row 15 or more means vertical and one at
column 16 or more means horizontal. Of the 100 areas of more than one page none has both, and 3 have neither: 2-3 area 2, 3-1 area 2,
4-3 area 4. A guess from whether the level data lines up across page seams was tried and read 1-1's first area wrongly.

Any area can be loaded directly for testing: poke the level to `03001160`, the area to `030019FE` and 0 to `03001992`, then run about
60 frames. The door's own route doesn't take pokes, since its state 2 rereads the destination from the area's table.

## Inside a pot (0.34)

There are two kinds, and `03001A29` tells them from everything else:

| | Pot on the extra page | Pot that is its own area | Subspace | Otherwise |
|---|---|---|---|---|
| Main state `1992` | `0A` | `01` | `0A` | `01` |
| `03001A29` | 1 | 2 | 0 | 0 |
| `03001A2A` | 1 | 0 | 2 | 0 |
| Subspace timer `03001CCF` | 0 | 0 | counting | 0 |
| Page | 10 | 0 | 10 | |
| Drawn mirrored | no | no | yes | no |

Seen so far: a jar topped `21E` goes to the extra page and one topped `21A` to its own area (`21F` in 1-3 did nothing when entered this
way). The extra page is always page 10, the level buffer's last, whatever `03001A06` says (0 in these pots). BG0 holds the pot's
outline (metatiles `296`-`2B0`) and the blocks and is clear inside and out. BG1 is one picture, 32x28 tiles down to the floor: the pot's
inside with black all round it, under the outline; the rest of its 512x256 map is other screens' tiles. BG2 is off. The camera sits
at BG0 scroll (8, 64) and BG1 at (0, 64), so the neck is above the screen. Palette colour 0 is left over from outside on the extra page.

## Dual tracker (not in game RAM)

Play time, deaths (lives dropping by one), levels cleared and by whom, levels skipped by a warp: kept in
`native/smatrack.c` and written as a trailer (`"SMAT"` + 64 bytes) on every save state, so they travel with states.
`Native.tracker()` returns the block (u32: time, deaths, cleared mask, who lo/hi (2 bits per level), warped mask, flags).

**The end of a level, in the order it happens** (traced on 4-3's end and read from the code):

| When | What the game does | Where |
|---|---|---|
| The mask at the end of an x-1 or x-2 level, or player state `0A` (handler `080ABB98`) with `+158` top bits `80` when its timer `030019BC` runs out | Sub-mode 1 → 2 (Bonus Chance), or 6 (egg screen) in the Yoshi Challenge; state 0. On the mask, that character's count at `03001148` goes up here | `0808DFD4` / `0808DF98`, `080ABE6C` / `080ABE30`; count `080B1398` |
| Bonus Chance opens | Sets the level's cleared bit, saves the Ace Coins | `08031DC4`, `08034770` |
| Bonus Chance ends | `03001160` + 1, sub-mode 2 → 0 (chart, then character select) | `080346B4` |
| The Yoshi Challenge's other way out (seen on 4-1's mask with a beaten, 40-egg file) | Cleared bit and `03001160` + 1 in one go, sub-mode 1 → 0 | `080468A8` |

The mask on an x-3 level doesn't end it (`0808E010`, read from the code; the object lists put a boss's room after it). Player state
`03001C6F` indexes the handlers at `08367CEC`. `080AB05C(4)` is what starts state `0A`, adding to the count and setting the timer to 180;
its callers look for metatile `038` (`08368954[4]`) and Up, but neither the tile nor the value poked set it off, so what shows it in play
isn't known. The last step (state `0A`, timer at 1) was run by poking those bytes in 4-3's boss room, not by beating Fryguy.

The tracker records the finisher at the first line (0.40), reading `03001C70`, which holds until the next character is chosen. It used
to wait for the next level's first playing frame, with Bonus Chance, the chart and character select in between (about 8 s on 4-3's way
out, with one spin and the screens pressed through).

## RetroArch (Super Mario Advance Link)

mGBA's libretro core maps IWRAM `03`, EWRAM `02`, IO `04` (0x400), palette `05`, VRAM `06`, OAM `07` and the ROM `08`-`0D` for RetroArch at their GBA addresses
(`libretro.c` `_setupMaps`; the same commit as Dual's). `READ_CORE_MEMORY` reaches them all, including the write-only BG registers, since it reads the core's
own copy of IO. An address outside them: `READ_CORE_MEMORY 9000000 -1 no descriptor for address`.

`03001993` goes up by 1 every frame in play: Link times play with it. (`03001FD4`, `03002044`, `030020B4` did too in 4-2 only: object slots.)

What changes from one frame to the next in play, over 240 frames of running and jumping in each of 1-1, 2-1 and 4-2, which sets what Link reads how often:

| Where | How often it changes |
|---|---|
| VRAM `06000000`-`067FF` | Every frame, about 87 bytes: the BG tiles the game animates |
| VRAM `06010000`-`107FF` | 30 of 239 frames, about 70 bytes: the player's frames |
| OAM, the scroll registers, IWRAM | Most frames, a few bytes |
| Palette | 0 to 37 of 239 frames |
| BG maps (`0600D000`-`0600FFFF`) | 1 to 14 of 239 frames, per 2 KB: columns streamed in as the camera moves |
| The rest of VRAM | Not at all, until a new area or the pause screen loads over it |

So three reads hold all the game state the page and the 3D view use: `03001140`:0x40, `03001990`:0xD4, `03001C30`:0x7C0 (the player, then the object slots).

The menu art Dual captures is all uncompressed in the ROM: the title's maps at `332D10` (BG0) and `3378D0` (BG2) and palette at `332B10`; the pause panel's OBJ tiles at `2FE424`+;
item sprites at `2DBF5C` (a 2D sheet: rows are 0x400 apart), `2E6FDC`+, `2C41D0`+; OBJ palettes 1, 2, 4, 9 at `3BA6D0`, `3BA6F0`, `3BA730`, `3BA770`, 7 at `3BAA50`, 14 at
`2F2CBC`. The full table of which piece goes where is `KitRom.T` in `linkapp`.

## The main loop's wait (3DS port)

The main loop at `08001BC6` runs the mode's handler from `0833D5B4`, clears bit 0 of `03001178`, then spins from `08001CBC` to `08001CDE`–`08001CFC` until the vblank interrupt sets that bit. It never calls Halt or
VBlankIntrWait (so the app's lag meter counts every frame), reads nothing else and has no side effects: about 3700 reads of `03001178` a frame, over half the CPU's time. Title, character select and play all use this loop.
`native/ctr` tells mGBA it's an idle loop (`idleLoop = 08001CDE`), which halts until the next interrupt.

## Not mapped yet

SMA-only object types besides the Ace Coins (`74`–`78` …), grass `9A`, the character stats card, the
big-vegetable / kill counters, mode 3 sub-mode `03`, modes `05`–`0B` (handlers at `0833D5C8`+; mode 5 poked alone is a
blank white screen). The game didn't lag in
the stretches tested, and it never calls the BIOS vblank waits (mGBA removes its idle loop), so the lag meter
doesn't apply; NO SLOWDOWN halves CPU instruction cycles in mGBA (`native/mgba-dual.patch`).

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
| `03001991` | sub-mode. In mode 2: `01` title, `02` choose game, `03`–`04` file select, `05`–`06` later file menus. In mode 3: `00` character select / chart, `01` in a level |
| `03001992` | in a level (`1991 = 1`): `01` playing, `0C`/`0D` pause screen, `0F` unpausing. At character select (`1991 = 0`): `01` choosing, `02` leaving, `05`/`06` chart |

**In a level and playing = `19B0 = 3` and `1991 = 1` and `1992 = 1`.** `1992 = 1` alone is also true at character
select (0.1 used that and was wrong there). Character select open = `19B0 = 3`, `1991 = 0`, `1992 = 1`.
| `030019EC` | 0 in the first area entered, 1 after a door (1-1 start: 0; 1-1 after the first door: 1; 2-1 start: 1). Not the area number — use `030019FE`. Start of the level-state struct (pointer at `03001110`) |
| `030019FE` | **area number** 0–10, SMB2's numbering (1-1: 0 the cloud drop at the start, 1 the vine climb, 2 the main ground, …). It picks the ROM object list (`level·11 + area`, see Objects) |
| `03001A06` | pages in the area − 1 (1-1 area 0: 3, area 1: 9), as SMB2's `CurrentLevelPages` |
| `0300116F` | 1 in vertical areas, 0 in horizontal ones |
| `030019FC` | u16 **world graphics set** = world − 1 (0 grass … 3 ice … 6 sky). Set by a real level start; a level-number poke alone leaves it (world 1's look) unless it is poked too |
| `03001A29` / `03001A4B` / `03001A2A` | level-renderer flags (`08029EC2`): metatiles `0x26E`–`0x2C5` are drawn as `(quad & 0xFF) \| 0x2200` when `030019FC` = 3 and `03001A29` = 0, as `(quad & 0x1FF) \| 0x6000` when `03001A4B` = 1; `03001A2A` = 2 draws everything mirrored (Subspace) |

## Save block (unpacked from the EEPROM when a file is loaded; bit-packed by the code at `080B6B40`)

| Address | Meaning |
|---|---|
| `03001140`–`03001167` | the file, 40 bytes |
| `0300114C` | score, u32 *(Data Crystal; HUD agrees)* |
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
| `02000000` | the whole current area decoded, **u16 metatile ids**, pages of 15 rows × 16 columns like SMB2's `$6000`: page p, row r, column c at index `p·240 + r·16 + c`. Horizontal areas: page = x>>8, row = y>>4. Vertical areas: rows run on (index = row·16 + column). Pulling grass writes `0x22` (sky) into its cell. |

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

## Dual tracker (not in game RAM)

Play time, deaths (lives dropping by one), levels cleared and by whom, levels skipped by a warp: kept in
`native/smatrack.c` and written as a trailer (`"SMAT"` + 64 bytes) on every save state, so they travel with states.
`Native.tracker()` returns the block (u32: time, deaths, cleared mask, who lo/hi (2 bits per level), warped mask, flags).

## Not mapped yet

SMA-only object types besides the Ace Coins (`74`–`78` …), grass `9A`, the character stats card, `03001162`, the
big-vegetable / kill counters, the ending (Wart). The game didn't lag in
the stretches tested, and it never calls the BIOS vblank waits (mGBA removes its idle loop), so the lag meter
doesn't apply; NO SLOWDOWN halves CPU instruction cycles in mGBA (`native/mgba-dual.patch`).

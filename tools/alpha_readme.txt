Super Mario Advance Dual and Link - alpha

Two Android apps for Super Mario Advance (USA, Europe), for dual-screen handhelds. Neither has any game data in it: you pick your own ROM.

SUPER MARIO ADVANCE DUAL
Everything in one app: the game on one screen (with its own built-in emulator and the optional 3D view) and the Link screen on the other.
Install it, start it, choose your ROM. The first start plays a few seconds of the game on its own to capture the menu art.
Hotkeys, all rebindable in Settings > Controls > Controller Buttons:
  L2 saves to state slot 1 (without asking, so it overwrites what's there)
  R2 loads state slot 1
  Left stick click changes the 3D camera
  Right stick click switches between 2D and 3D

SUPER MARIO ADVANCE LINK
Only the Link screen, for playing in RetroArch instead. It reads the game over RetroArch's network commands on the same device.
1. Install RetroArch Nightly.
2. In RetroArch: Settings > Network > Network Commands ON, Network Command Port 55355.
3. Load Super Mario Advance (USA, Europe) with the mGBA core.
4. Start Super Mario Advance Link on the second screen, tap CHOOSE ROM and pick the same ROM file. You only do this once.

The game must be the USA, Europe ROM (CRC32 1E4C6D6A). The controller stays with RetroArch.

The 3D tab is Link's own and starts off: OPTIONS > 3D VIEW > 3D TAB ON. It shows the 3D view on the Link screen, since the game's screen is RetroArch's.
Tap the picture or the row under it to change the camera.

ON THE LINK SCREEN
Status: hearts, lives, score, cherries, Ace Coins, eggs, play time, deaths.
Map: the area drawn in the game's own tiles, with what's hidden in the grass. Tap for the next zoom, drag to look around.
Chart: every level, its Ace Coins, and who finished it.
Options: save states, map settings.
At the end of the game, an ending card to sign with your finger.

KNOWN LIMITS OF THIS ALPHA
- Link has only been run against RetroArch on a PC and in test tools, not yet on an Android device.
- Link's play time, deaths and who-finished-what are kept on the device and only count while Link is running. They don't follow RetroArch save states, and
  different save files share them.
- RetroArch's save slots have no thumbnails in Link, and it can't tell which slots are empty.

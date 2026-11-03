# Dual-screen clips and stills (0.18)

Run from a working folder that holds `sma.gba`, `_kit.bin` (a first-start capture) and the save states the clips start
from (`clips.py`), with the desktop driver at `../sma-dual/out/host/drive`.

- `cliptool.py` — `record(state, steps, outdir)`: plays button steps (`(frames, "RBA")`, `('poke', addr, val)`) and writes a
  dump + screenshot every 2nd frame (30 fps). `fly_lines()` moves the player with the camera so the map streams in.
- `makeclip.py name [r3lc]` — record → 3D (`tests/scene3d/SceneSeq` + `shotseq.py`, camera presets or an eased
  `[pitch, yaw, zoom]` list) → Link page (`linkseq.py`: the page's 100 ms poll driven by hand, scripted taps/drags) →
  stacked 960x1148 frames → H.264. Frames the 3D view can't match (Subspace, transitions) show the 2D picture, as the app.
- `stills.py` — 1920x1080 3D render with the Link screen (900x560 css px at 2.13x) underneath.

Use states the game reached by itself: a level-number warp keeps world 1's collision and graphics set, so levels behave
wrongly in play (ice isn't solid). The Yoshi Challenge menu and normal progression load levels properly.

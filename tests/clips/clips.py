# Clip definitions for makeclip.py (30 fps output = every 2nd emulator frame; 360 emulator frames = 6 s)
def eased(targets, frames=180, sub=2, start=(16, 0, 1)):
    """Renderer3D's camera easing (x += (target - x) * 0.12 per 60 Hz frame): targets = [(from_frame, (pitch, yaw, zoom))]."""
    cur, out = list(start), []
    for f in range(frames):
        tgt = [t for t in targets if t[0] <= f][-1][1]
        for _ in range(sub): cur = [c + (t - c) * 0.12 for c, t in zip(cur, tgt)]
        out.append([round(v, 3) for v in cur])
    return out

TILT, DIORAMA, SIDE, LOW, FAR = (16, 0, 1), (30, -24, 0.92), (0, 0, 1), (-10, 18, 1.05), (22, 0, 0.62)
TAB = {'status': (112, 545), 'map': (337, 545), 'chart': (562, 545), 'options': (787, 545)}

def _collect():
    s = [(3, 'L'), (40, ''), (6, 'B'), (24, ''), (6, 'L'), (14, 'LA'), (14, 'L'), (22, 'L'), (14, 'LA'), (10, 'L'), (10, ''), (6, 'B'),
         (24, ''), (18, 'RA'), (30, 'R'), (30, '')]
    return s + [(360 - sum(x[0] for x in s), '')]

CLIPS = {
    'collect_1-1': dict(state='clip/g2.st', steps=_collect(), cam='tilt'),
    'map_controls_4-1': dict(state='gs1/y9.st', savedir='gs1',
        steps=[(80, 'RB'), (20, 'RBA'), (30, 'RB'), (20, 'RBA'), (24, 'RB'), (20, 'RBA'), (6, 'RB'), (14, 'L'), (146, '')], cam='tilt',
        events=[[24, 'tap', 450, 250], [52, 'tap', 450, 250], [80, 'tap', 450, 250],
                [100, 'down', 450, 260], [103, 'move', 520, 262], [106, 'move', 600, 265], [109, 'move', 680, 268], [112, 'up', 700, 270],
                [132, 'tap'] + list(TAB['chart']), [154, 'tap'] + list(TAB['status']), [174, 'tap'] + list(TAB['map'])]),
    'camera_presets_2-1': dict(state='gs1/y3.st', savedir='gs1', steps=[(240, 'R'), (120, '')],
        cam=eased([(0, TILT), (36, DIORAMA), (72, LOW), (108, FAR), (144, SIDE)])),
    'subspace_1-1': dict(state='clip/sub160.st', steps=[(30, 'R'), (190, ''), (40, 'L'), (100, '')], cam='tilt'),
}

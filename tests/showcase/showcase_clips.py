# What dualscreen.py shows. Clips: 300 frames of the game (5 s), a GIF frame every 3rd. 'top': [gif frame, what]; 'events': [gif frame, what] on the page.
# Tab names: status map chart options (3d in Link). Taps are fractions of the page. A caption line fits 10 characters; the font has no colon.
# 'mode': the 3D menu's options, as tests/scene3d/SceneSeq takes them. The inputs were picked so nobody dies or takes a hit on camera.
L2, R2, L3, R3 = 'L2', 'R2', 'L3', 'R3'
def cams(*names, every=25, first=20):
    t = []
    for i, n in enumerate(names):
        t += [[first + i * every, 'key', L3], [first + i * every, 'cam', n]]
    return t
def modes(*items, every=25):
    return [[i * every, 'mode', env, label] for i, (env, label) in enumerate(items)]
PYRAMIDS = [(100, 'R'), (200, '')]
WHALES = [(15, 'R'), (24, 'RBA'), (60, 'RB'), (201, '')]
CLIPS = {
    '01_r3_switch_2d_3d': dict(state='2-1_pyramids', steps=PYRAMIDS, top=[[18, 'key', R3], [18, '3d', True], [72, 'key', R3], [72, '3d', False]],
        caption=['R3', 'SWITCHES', '2D AND 3D']),
    '02_l3_camera_pyramids': dict(state='2-1_pyramids', steps=PYRAMIDS, view3d=True, cam='low', events0=[['tab', 'status']], top=cams('tilt', 'diorama', 'far'),
        caption=['L3', 'CHANGES', 'THE CAMERA', 'PYRAMIDS']),
    '03_l3_camera_whales': dict(state='4-2_whale_back', steps=[(60, 'R'), (240, '')], view3d=True, cam='low', top=cams('diorama', 'far', 'tilt'),
        caption=['L3', 'CHANGES', 'THE CAMERA', 'WHALES']),
    '04_l3_camera_temple': dict(state='7-1_temple', steps=[(90, 'R'), (210, '')], view3d=True, cam='far', events0=[['tab', 'chart']], top=cams('low', 'tilt', 'diorama'),
        caption=['L3', 'CHANGES', 'THE CAMERA', 'TEMPLE']),
    '05_default_3d_whales': dict(state='4-2_whales', steps=WHALES, view3d=True, cam='low',
        caption=['3D VIEW', 'DEFAULT', 'SETTINGS', 'LOW CAMERA', 'DEPTH 20']),
    '06_mode_block_depth': dict(state='1-3_waterfall', steps=[(90, 'R'), (210, '')], view3d=True, cam='diorama',
        top=modes(({'DEPTH': '8'}, 'DEPTH 8'), ({'DEPTH': '20'}, 'DEPTH 20'), ({'DEPTH': '32'}, 'DEPTH 32'), ({'DEPTH': '48'}, 'DEPTH 48')),
        caption=['3D MODES', 'BLOCK', 'DEPTH']),
    # the back wall only changes the picture where the level has it: of 15 spots measured, 5-3's cave by far the most (12-20% of the pixels), the rest under 2%
    '07_mode_back_wall_background': dict(state='5-3_cave', steps=[(6, 'R'), (16, 'RA'), (120, 'R'), (158, '')], view3d=True, cam='low',
        top=modes(({}, 'WALL NEAR'), ({'BACK': 'far'}, 'WALL DEEP'), ({'BACK': 'off'}, 'WALL OFF'), ({'NOCAVE': '1'}, 'BACKGROUND\nOFF')),
        caption=['3D MODES', 'BACK WALL', 'BACKGROUND']),
    '08_mode_sprites': dict(state='4-2_whales', steps=WHALES, view3d=True, cam='diorama', events0=[['tab', 'status']],
        top=modes(({}, 'THICK\nSPRITES'), ({'NOTHICK': '1'}, 'FLAT\nSPRITES'), ({'ROUND': '1'}, 'ROUND\nSPRITES'), every=34),
        caption=['3D MODES', 'SPRITES']),
    '09_mode_player_light': dict(state='2-1_oasis', steps=[(300, 'R')], view3d=True, cam='tilt',
        top=modes(({}, 'LIGHT OFF'), ({'LIGHT': '1'}, 'PLAYER\nLIGHT ON'), every=50),
        caption=['3D MODES', 'PLAYER', 'LIGHT']),
    '10_map_zoom_and_drag': dict(state='1-3_waterfall', steps=[(150, 'R'), (150, '')],
        events=[[14, 'tap', .5, .4], [34, 'tap', .5, .4], [54, 'tap', .5, .4], [66, 'down', .7, .4], [70, 'move', .5, .4], [74, 'move', .3, .4], [78, 'up']],
        caption=['MAP TAB', 'TAP TO', 'ZOOM', 'DRAG TO', 'LOOK']),
    '11_tabs_during_play': dict(state='7-1_temple', steps=[(90, 'R'), (210, '')], view3d=True, cam='low', events0=[['tab', 'status']],
        events=[[25, 'tab', 'map'], [50, 'tab', 'chart'], [75, 'tab', 'options']], caption=['LINK', 'SCREEN', 'TABS WHILE', 'YOU PLAY']),
    '12_l2_r2_save_and_load': dict(state='2-1_oasis', steps=[(45, 'R'), ('mark', 'saved'), (135, 'R'), (15, ''), ('loadmark', 'saved'), (105, 'R')], view3d=True, cam='low', events0=[['tab', 'options']],
        top=[[15, 'key', L2], [15, 'osd', 'SAVED TO SLOT 1'], [65, 'key', R2], [65, 'osd', 'LOADED SLOT 1']], events=[[15, 'slot1']],
        caption=['L2 SAVES', 'R2 LOADS', 'STATE', 'SLOT 1']),
    '13_character_select_logo': dict(state='@cs.st', steps=[(50, ''), (6, 'R'), (40, ''), (6, 'R'), (40, ''), (6, 'A'), (152, '')],
        caption=['DIMMED', 'LOGO AT', 'CHARACTER', 'SELECT']),
    '14_ending_card_signature': dict(state='@probe/e7.st', steps=[(300, '')], theme_from='7-2_machine',
        events=[[18, 'down', .28, .62], [24, 'move', .36, .52], [30, 'move', .42, .68], [36, 'move', .50, .50], [42, 'move', .56, .66], [48, 'move', .64, .54], [54, 'move', .72, .64], [58, 'up'],
                [64, 'down', .34, .74], [74, 'move', .70, .72], [78, 'up']],
        caption=['ENDING', 'CARD', 'SIGNED BY', 'FINGER']),
    '15_link_3d_tab': dict(state='4-2_whales', steps=WHALES, link3d=True, cam='low', events0=[['tab', '3d']],
        top=[[30, 'cam', 'tilt'], [60, 'cam', 'diorama']], events=[[30, 'tap', 90 / 240, 155 / 176], [60, 'tap', 150 / 240, 155 / 176]],
        caption=['LINK APP', 'RETROARCH', 'ON TOP', '3D TAB', 'BELOW']),
}
STILLS = {
    # the four cameras, same scene
    'camera_low_2-1_pyramids': dict(spot='2-1_pyramids', view3d=True, cam='low', tab='map', caption=['CAMERA', 'LOW']),
    'camera_tilt_2-1_pyramids': dict(spot='2-1_pyramids', view3d=True, cam='tilt', tab='map', caption=['CAMERA', 'TILT']),
    'camera_diorama_2-1_pyramids': dict(spot='2-1_pyramids', view3d=True, cam='diorama', tab='map', caption=['CAMERA', 'DIORAMA']),
    'camera_far_2-1_pyramids': dict(spot='2-1_pyramids', view3d=True, cam='far', tab='map', caption=['CAMERA', 'FAR']),
    'camera_low_4-2_whales': dict(spot='4-2_whale_back', settle=120, view3d=True, cam='low', tab='status', caption=['CAMERA', 'LOW']),
    'camera_tilt_4-2_whales': dict(spot='4-2_whale_back', settle=120, view3d=True, cam='tilt', tab='status', caption=['CAMERA', 'TILT']),
    'camera_diorama_4-2_whales': dict(spot='4-2_whale_back', settle=120, view3d=True, cam='diorama', tab='status', caption=['CAMERA', 'DIORAMA']),
    'camera_far_4-2_whales': dict(spot='4-2_whale_back', settle=120, view3d=True, cam='far', tab='status', caption=['CAMERA', 'FAR']),
    # the 3D menu's other options, one at a time
    'mode_default_1-1': dict(spot='1-1_logs_water', view3d=True, cam='diorama', tab='map', caption=['DEFAULT', 'SETTINGS']),
    'mode_block_depth_8_1-1': dict(spot='1-1_logs_water', view3d=True, cam='diorama', env={'DEPTH': '8'}, tab='map', caption=['BLOCK', 'DEPTH 8']),
    'mode_block_depth_48_1-1': dict(spot='1-1_logs_water', view3d=True, cam='diorama', env={'DEPTH': '48'}, tab='map', caption=['BLOCK', 'DEPTH 48']),
    'mode_back_wall_near_5-3': dict(spot='5-3_cave', view3d=True, cam='low', tab='chart', caption=['BACK WALL', 'NEAR']),
    'mode_back_wall_deep_5-3': dict(spot='5-3_cave', view3d=True, cam='low', env={'BACK': 'far'}, tab='chart', caption=['BACK WALL', 'DEEP']),
    'mode_back_wall_off_5-3': dict(spot='5-3_cave', view3d=True, cam='low', env={'BACK': 'off'}, tab='chart', caption=['BACK WALL', 'OFF']),
    'mode_no_background_5-3': dict(spot='5-3_cave', view3d=True, cam='tilt', env={'NOCAVE': '1'}, tab='map', caption=['BACKGROUND', 'OFF']),
    'mode_flat_sprites_6-3': dict(spot='6-3_sky_pyramid', view3d=True, cam='low', env={'NOTHICK': '1'}, tab='status', caption=['FLAT', 'SPRITES']),
    'mode_round_sprites_6-3': dict(spot='6-3_sky_pyramid', view3d=True, cam='low', env={'ROUND': '1'}, tab='status', caption=['ROUND', 'SPRITES']),
    'mode_player_light_2-1': dict(spot='2-1_oasis', view3d=True, cam='tilt', env={'LIGHT': '1'}, tab='options', caption=['PLAYER', 'LIGHT']),
    'mode_player_light_7-2': dict(spot='7-2_machine', view3d=True, cam='tilt', env={'LIGHT': '1'}, tab='options', caption=['PLAYER', 'LIGHT']),
    # 2D, with the level's own borders
    'view_2d_1-1_map': dict(spot='1-1_hills', tab='map', caption=['2D VIEW', 'BORDERS', 'FROM THE', 'LEVEL']),
    'view_2d_4-1_status': dict(spot='4-1_ice', tab='status', caption=['2D VIEW', 'STATUS TAB']),
    'view_2d_6-3_whole_map': dict(spot='6-3_sky_pyramid', tab='map', zoom=3, caption=['2D VIEW', 'WHOLE AREA', 'MAP']),
}

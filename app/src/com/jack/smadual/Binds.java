package com.jack.smadual;

import android.view.KeyEvent;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/** keycode -> button index (Gba.BUTTON_NAMES). Setting a key that's already used swaps the two. */
final class Binds {
    final android.util.SparseIntArray map = new android.util.SparseIntArray();

    void defaults() {
        map.clear();
        map.put(KeyEvent.KEYCODE_DPAD_UP, 0); map.put(KeyEvent.KEYCODE_DPAD_DOWN, 1);
        map.put(KeyEvent.KEYCODE_DPAD_LEFT, 2); map.put(KeyEvent.KEYCODE_DPAD_RIGHT, 3);
        map.put(KeyEvent.KEYCODE_BUTTON_A, 4); map.put(KeyEvent.KEYCODE_BUTTON_B, 5);
        map.put(KeyEvent.KEYCODE_BUTTON_X, 8); map.put(KeyEvent.KEYCODE_BUTTON_Y, 9);
        map.put(KeyEvent.KEYCODE_BUTTON_SELECT, 6); map.put(KeyEvent.KEYCODE_BUTTON_START, 7);
        map.put(KeyEvent.KEYCODE_BUTTON_MODE, 10);
        map.put(KeyEvent.KEYCODE_BUTTON_L1, 17); map.put(KeyEvent.KEYCODE_BUTTON_R1, 18);
        map.put(KeyEvent.KEYCODE_A, 17); map.put(KeyEvent.KEYCODE_S, 18);
        // the hotkeys: L2 saves to state slot 1, R2 loads it, L3 steps the 3D camera, R3 switches between 2D and 3D
        map.put(KeyEvent.KEYCODE_BUTTON_L2, 12); map.put(KeyEvent.KEYCODE_BUTTON_R2, 13);
        map.put(KeyEvent.KEYCODE_BUTTON_THUMBL, 19); map.put(KeyEvent.KEYCODE_BUTTON_THUMBR, 14);
        map.put(KeyEvent.KEYCODE_Z, 4); map.put(KeyEvent.KEYCODE_X, 5);
        map.put(KeyEvent.KEYCODE_SHIFT_RIGHT, 6); map.put(KeyEvent.KEYCODE_ENTER, 7);
        map.put(KeyEvent.KEYCODE_ESCAPE, 10); map.put(KeyEvent.KEYCODE_TAB, 11);
        map.put(KeyEvent.KEYCODE_F5, 12); map.put(KeyEvent.KEYCODE_F7, 13);
        map.put(KeyEvent.KEYCODE_F2, 14);
        map.put(KeyEvent.KEYCODE_PAGE_UP, 15); map.put(KeyEvent.KEYCODE_PAGE_DOWN, 16);
        map.put(KeyEvent.KEYCODE_F3, 19);
    }

    void load(String json) {
        defaults();
        if (json == null || json.isEmpty()) return;
        try {
            JSONObject o = new JSONObject(json);
            map.clear();
            Iterator<String> it = o.keys();
            while (it.hasNext()) { String k = it.next(); map.put(Integer.parseInt(k), o.getInt(k)); }
            // bindings saved before 0.22 have no 3D CAMERA key: give it F3 when that's free
            if (map.indexOfValue(19) < 0 && map.get(KeyEvent.KEYCODE_F3, -1) < 0) map.put(KeyEvent.KEYCODE_F3, 19);
        } catch (Exception e) { defaults(); }
    }

    /** Once, for bindings saved before 0.44: L3 was FAST and is now 3D CAMERA, unless it had been moved to something else. */
    void upgrade44() {
        if (map.get(KeyEvent.KEYCODE_BUTTON_THUMBL, -1) == 11) map.put(KeyEvent.KEYCODE_BUTTON_THUMBL, 19);
    }

    String save() {
        try {
            JSONObject o = new JSONObject();
            for (int i = 0; i < map.size(); i++) o.put(String.valueOf(map.keyAt(i)), map.valueAt(i));
            return o.toString();
        } catch (Exception e) { return ""; }
    }

    int button(int code) { return map.get(code, -1); }

    void assign(int code, int idx) {
        int prev = map.get(code, -1);
        if (prev == idx) return;
        List<Integer> old = new ArrayList<Integer>();
        for (int i = 0; i < map.size(); i++) if (map.valueAt(i) == idx) old.add(map.keyAt(i));
        if (prev >= 0 && !old.isEmpty()) {
            map.put(old.get(0), prev);          // swap
        } else {
            for (int k : old) {
                // keep the other keyboard/pad entries for this button only if they are a different device class
                if (isPadKey(k) == isPadKey(code)) map.delete(k);
            }
        }
        map.put(code, idx);
    }

    static boolean isPadKey(int k) { return KeyEvent.isGamepadButton(k) || (k >= KeyEvent.KEYCODE_DPAD_UP && k <= KeyEvent.KEYCODE_DPAD_CENTER); }

    /** A key's name for the CONTROLLER BUTTONS page. */
    static String keyName(int code) {
        return KeyEvent.keyCodeToString(code).replace("KEYCODE_", "").replace("BUTTON_", "").replace("THUMBL", "L3").replace("THUMBR", "R3").replace("_", " ");
    }

    String describe() {
        try {
            JSONArray a = new JSONArray();
            for (int b = 0; b < Gba.BUTTON_NAMES.length; b++) {
                JSONObject o = new JSONObject();
                o.put("name", Gba.BUTTON_NAMES[b]);
                JSONArray keys = new JSONArray();
                for (int i = 0; i < map.size(); i++) if (map.valueAt(i) == b) keys.put(keyName(map.keyAt(i)));
                o.put("keys", keys);
                a.put(o);
            }
            return a.toString();
        } catch (Exception e) { return "[]"; }
    }
}

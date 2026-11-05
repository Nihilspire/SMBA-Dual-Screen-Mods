package com.jack.smadual;

import android.view.KeyEvent;

/** The default hotkeys, what 0.44 does to saved bindings, and L2 / R2 from every kind of trigger. java -cp tests/input/out com.jack.smadual.InputCheck [binds.json] */
public class InputCheck {
    static int bad, checks;
    static void is(String what, Object got, Object want) {
        checks++;
        if (!String.valueOf(got).equals(String.valueOf(want))) { bad++; System.out.println("WRONG " + what + ": " + got + ", wanted " + want); }
    }
    static String name(Binds b, int code) { int i = b.button(code); return i < 0 ? "-" : Gba.BUTTON_NAMES[i]; }

    /** One button's worth of Triggers, as MainActivity drives it: counts presses and releases, and how the button stands now. */
    static final class Pull {
        final Triggers t = new Triggers(); int presses, releases; boolean held;
        void act(boolean down) { if (down) { presses++; held = true; } else { releases++; held = false; } }
        Pull axis(float... vs) { for (float v : vs) { int a = t.axis(0, v); if (a != 0) act(a > 0); } return this; }
        Pull key(boolean down) { if (t.key(0, down)) act(down); return this; }
        void want(String what, int p, int r) { is(what + " presses", presses, p); is(what + " releases", releases, r); is(what + " left held", held, p != r); }
    }

    public static void main(String[] a) throws Exception {
        Binds b = new Binds(); b.load("");
        is("L2", name(b, KeyEvent.KEYCODE_BUTTON_L2), "SAVE SLOT 1");
        is("R2", name(b, KeyEvent.KEYCODE_BUTTON_R2), "LOAD SLOT 1");
        is("L3", name(b, KeyEvent.KEYCODE_BUTTON_THUMBL), "3D CAMERA");
        is("R3", name(b, KeyEvent.KEYCODE_BUTTON_THUMBR), "3D VIEW");
        is("L1", name(b, KeyEvent.KEYCODE_BUTTON_L1), "L");
        is("R1", name(b, KeyEvent.KEYCODE_BUTTON_R1), "R");
        is("TAB still fast", name(b, KeyEvent.KEYCODE_TAB), "FAST");
        is("F3 still camera", name(b, KeyEvent.KEYCODE_F3), "3D CAMERA");
        is("F2 still 3D", name(b, KeyEvent.KEYCODE_F2), "3D VIEW");
        is("name of L3", Binds.keyName(KeyEvent.KEYCODE_BUTTON_THUMBL), "L3");
        is("name of R3", Binds.keyName(KeyEvent.KEYCODE_BUTTON_THUMBR), "R3");
        is("name of L2", Binds.keyName(KeyEvent.KEYCODE_BUTTON_L2), "L2");
        is("name of up", Binds.keyName(KeyEvent.KEYCODE_DPAD_UP), "DPAD UP");
        if (a.length > 0) java.nio.file.Files.write(java.nio.file.Paths.get(a[0]), b.describe().getBytes("UTF-8"));

        // bindings saved under 0.43's defaults: L3 moves from FAST to 3D CAMERA, and nothing else does
        Binds old = new Binds(); old.load(""); old.map.put(KeyEvent.KEYCODE_BUTTON_THUMBL, 11);
        String saved = old.save();
        Binds up = new Binds(); up.load(saved); is("before upgrade", name(up, KeyEvent.KEYCODE_BUTTON_THUMBL), "FAST");
        up.upgrade44(); up.load(up.save());
        Binds fresh = new Binds(); fresh.load("");
        is("upgraded save", up.save(), fresh.save());
        // a saved rebinding of L3 stays, and once upgraded it isn't touched again: FAST bound back to L3 by hand stays there
        Binds mine = new Binds(); mine.load(""); mine.map.put(KeyEvent.KEYCODE_BUTTON_THUMBL, 7); mine.upgrade44();
        is("L3 bound by hand", name(mine, KeyEvent.KEYCODE_BUTTON_THUMBL), "START");
        Binds later = new Binds(); later.load(""); later.assign(KeyEvent.KEYCODE_BUTTON_THUMBL, 11); later.load(later.save());
        is("FAST bound to L3 after the upgrade", name(later, KeyEvent.KEYCODE_BUTTON_THUMBL), "FAST");
        Binds junk = new Binds(); junk.load("not json"); is("corrupt save falls back", name(junk, KeyEvent.KEYCODE_BUTTON_L2), "SAVE SLOT 1");

        // triggers: axes only, keys only, and both together in every order they might come
        new Pull().axis(0, .2f, .7f, .9f, 1, .5f, .25f, 0).want("axis, one pull", 1, 1);
        new Pull().axis(0, .7f, 0, .7f, 0).want("axis, two pulls", 2, 2);
        new Pull().axis(.61f, .59f, .61f, .59f, .61f).want("axis jitter at the pull point", 1, 0);
        new Pull().axis(.7f, .31f, .29f, .31f, .29f).want("axis jitter at the let go point", 1, 1);
        new Pull().axis(0, .3f, .59f, 0).want("half pull does nothing", 0, 0);
        new Pull().key(true).key(false).key(true).key(false).want("keys, two pulls", 2, 2);
        new Pull().key(true).axis(.7f).key(false).axis(0).want("key down, axis, key up, axis down", 1, 1);
        new Pull().key(true).axis(.7f, 0).key(false).want("key down, axis up and down, key up", 1, 1);
        new Pull().axis(.7f).key(true).key(false).axis(0).want("axis, key down, key up, axis down", 1, 1);
        new Pull().axis(.7f).key(true).axis(0).key(false).want("axis, key down, axis down, key up", 1, 1);
        new Pull().key(true).axis(.7f, 0).key(false).axis(.7f).key(true).axis(0).key(false).want("both, two pulls", 2, 2);
        Triggers two = new Triggers();
        is("L2 axis", two.axis(0, 1), 1); is("R2 apart", two.axis(1, 0), 0); is("R2 axis", two.axis(1, 1), 1); is("L2 up", two.axis(0, 0), -1);

        System.out.println(bad == 0 ? "ALL AS EXPECTED (" + checks + " checks)" : bad + " WRONG of " + checks);
        System.exit(bad == 0 ? 0 : 1);
    }
}

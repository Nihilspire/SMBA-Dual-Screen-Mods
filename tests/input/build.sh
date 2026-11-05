#!/bin/bash
# Binds and Triggers on the desktop, with small stand-ins for the Android classes Binds uses. KeyEvent's numbers come from android.jar.
set -e
D=$(cd "$(dirname "$0")" && pwd); A=$D/../../app/src/com/jack/smadual
ANDROID_JAR=${ANDROID_JAR:-$HOME/src/platforms/android-33/android.jar}
O=$D/out; rm -rf "$O"; S=$O/stub; mkdir -p "$S/android/view" "$S/android/util" "$S/org/json" "$S/com/jack/smadual"
{ echo 'package android.view; public class KeyEvent {'
  javap -constants -cp "$ANDROID_JAR" android.view.KeyEvent | grep -E 'public static final int KEYCODE_[A-Z0-9_]+ = [0-9]+;'
  cat <<'J'
  public static boolean isGamepadButton(int k) { return k >= 96 && k <= 110 || k >= 188 && k <= 203; }
  public static String keyCodeToString(int k) {
    try { for (java.lang.reflect.Field f : KeyEvent.class.getFields()) if (f.getInt(null) == k) return f.getName(); } catch (Exception e) { }
    return String.valueOf(k);
  }
}
J
} > "$S/android/view/KeyEvent.java"
cat > "$S/android/util/SparseIntArray.java" <<'J'
package android.util;
import java.util.*;
public class SparseIntArray {
  private final TreeMap<Integer, Integer> m = new TreeMap<Integer, Integer>();
  public void clear() { m.clear(); }
  public void put(int k, int v) { m.put(k, v); }
  public void delete(int k) { m.remove(k); }
  public int get(int k, int d) { Integer v = m.get(k); return v == null ? d : v; }
  public int size() { return m.size(); }
  public int keyAt(int i) { return new ArrayList<Integer>(m.keySet()).get(i); }
  public int valueAt(int i) { return new ArrayList<Integer>(m.values()).get(i); }
  public int indexOfValue(int v) { return new ArrayList<Integer>(m.values()).indexOf(v); }
}
J
cat > "$S/org/json/JSONObject.java" <<'J'
package org.json;
import java.util.*;
public class JSONObject {
  private final LinkedHashMap<String, Object> m = new LinkedHashMap<String, Object>();
  public JSONObject() { }
  public JSONObject(String s) {
    String t = s.trim();
    if (!t.startsWith("{") || !t.endsWith("}")) throw new RuntimeException("not an object");
    for (String p : t.substring(1, t.length() - 1).split(",")) {
      if (p.trim().isEmpty()) continue;
      String[] kv = p.split(":");
      m.put(kv[0].trim().replace("\"", ""), Integer.valueOf(kv[1].trim()));
    }
  }
  public JSONObject put(String k, Object v) { m.put(k, v); return this; }
  public int getInt(String k) { return (Integer) m.get(k); }
  public Iterator<String> keys() { return m.keySet().iterator(); }
  @Override public String toString() {
    StringBuilder b = new StringBuilder("{");
    for (Map.Entry<String, Object> e : m.entrySet()) {
      if (b.length() > 1) b.append(',');
      b.append('"').append(e.getKey()).append("\":");
      Object v = e.getValue();
      b.append(v instanceof String ? "\"" + v + "\"" : String.valueOf(v));
    }
    return b.append('}').toString();
  }
}
J
cat > "$S/org/json/JSONArray.java" <<'J'
package org.json;
import java.util.*;
public class JSONArray {
  private final List<Object> l = new ArrayList<Object>();
  public JSONArray put(Object v) { l.add(v); return this; }
  @Override public String toString() {
    StringBuilder b = new StringBuilder("[");
    for (Object v : l) { if (b.length() > 1) b.append(','); b.append(v instanceof String ? "\"" + v + "\"" : String.valueOf(v)); }
    return b.append(']').toString();
  }
}
J
cat > "$S/com/jack/smadual/Native.java" <<'J'
package com.jack.smadual;
final class Native { static int peek(int a) { return 0; } }
J
javac -nowarn -d "$O" "$S/android/view/KeyEvent.java" "$S/android/util/SparseIntArray.java" "$S/org/json/JSONObject.java" "$S/org/json/JSONArray.java" \
  "$S/com/jack/smadual/Native.java" "$D/InputCheck.java" "$A/Binds.java" "$A/Triggers.java" "$A/Gba.java" 2>&1 | grep -v -e '^Note' -e JAVA_TOOL || true

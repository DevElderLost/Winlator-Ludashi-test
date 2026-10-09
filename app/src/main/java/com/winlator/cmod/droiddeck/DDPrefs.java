package com.winlator.cmod.droiddeck;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.HashMap;
import java.util.Map;

/** Preferensi kontrol DroidDeck (port dari ControllerPrefs.kt). Disimpan terpisah dari preferensi Winlator. */
public final class DDPrefs {
    public static final int STEAM_BLUE = 0xFF1A9FFF;
    public static final String OFF = "off";

    public static final int[] TINTS = {
        STEAM_BLUE, 0xFF66C0F4, 0xFFE8EEF4, 0xFFC77DFF, 0xFF3DDC84, 0xFFFFA726, 0xFFFF5252, 0xFFFF6FB5
    };
    public static final String[] TINT_NAMES = {
        "Steam blue", "Sky", "White", "Violet", "Green", "Amber", "Red", "Pink"
    };
    public static final int[] OPACITIES = {40, 60, 80, 100};
    public static final int[] SIZES = {80, 90, 100, 110, 125};
    // Rentang untuk slider (seekbar) Opacity/Size; OPACITIES/SIZES lama tetap dipakai dialog legacy di DDController.
    public static final int OPACITY_MIN = 10, OPACITY_MAX = 100, OPACITY_STEP = 5;
    public static final int SIZE_MIN = 50, SIZE_MAX = 150, SIZE_STEP = 5;
    public static final int[] SENSITIVITIES = {50, 75, 100, 125, 150, 200};  // persen; 100 = bawaan

    public static final String[] MAPPABLE_IDS = {"a", "b", "x", "y", "lb", "rb", "lt", "rt", "select", "start", "guide"};
    public static final String[] MAPPABLE_NAMES = {
        "A button", "B button", "X button", "Y button", "Left bumper", "Right bumper",
        "Left trigger", "Right trigger", "View button", "Menu button", "Steam button"
    };
    public static final String[] TARGET_IDS = {
        "a", "b", "x", "y", "lb", "rb", "lt", "rt", "l3", "r3", "select", "start", "guide",
        "up", "down", "left", "right", OFF
    };
    public static final String[] TARGET_NAMES = {
        "A", "B", "X", "Y", "LB", "RB", "LT", "RT", "L3", "R3", "View", "Menu", "Steam",
        "D-pad up", "D-pad down", "D-pad left", "D-pad right", "Hidden"
    };

    // DroidDeck-deckmap: grip/QAM Deck juga diteruskan ke pad evdev (game XInput tidak bisa membaca hidraw)
    public static final String NONE = "none";
    public static final String[] DECK_IDS = {"dk_L4", "dk_L5", "dk_qam", "dk_R5", "dk_R4"};  // urutan sama dengan tombol di DDDeckControlsView
    public static final String[] DECK_NAMES = {"L4 (back grip)", "L5 (back grip)", "QAM (... button)", "R5 (back grip)", "R4 (back grip)"};
    public static final String[] DECK_TARGET_IDS = {
        NONE, "a", "b", "x", "y", "lb", "rb", "lt", "rt", "l3", "r3", "select", "start", "guide", "up", "down", "left", "right"
    };
    public static final String[] DECK_TARGET_NAMES = {
        "Hidraw only", "A", "B", "X", "Y", "LB", "RB", "LT", "RT", "L3", "R3", "View", "Menu", "Steam",
        "D-pad up", "D-pad down", "D-pad left", "D-pad right"
    };
    public static final String[] PAD_MODE_IDS = {"mouse", "stick"};
    public static final String[] PAD_MODE_NAMES = {"Mouse", "Right stick"};
    public static final String[] GYRO_IDS = {"off", "always", "pad"};
    public static final String[] GYRO_NAMES = {"Off", "Always on", "While right pad touched"};
    public static final int GYRO_SENS_MIN = 25, GYRO_SENS_MAX = 300, GYRO_SENS_STEP = 5;

    public static final class Settings {
        public int tint, opacity, size, stickSens = 100, padSens = 100;
        public boolean stickClick, adaptiveSticks;
        public boolean rumble = true;  // DroidDeck-rumble: getar HP dari rumble pad virtual
        public final Map<String, String> mapping = new HashMap<>();
    }

    private DDPrefs() {}

    private static SharedPreferences p(Context c) {
        return c.getSharedPreferences("droiddeck_controller", Context.MODE_PRIVATE);
    }

    private static boolean has(int[] list, int v) {
        for (int i : list) if (i == v) return true;
        return false;
    }

    public static boolean isEnabled(Context c) { return p(c).getBoolean("enabled", false); }
    public static void setEnabled(Context c, boolean on) { p(c).edit().putBoolean("enabled", on).apply(); }

    private static int snap(int v, int min, int max, int step, int def) {
        if (v < min || v > max) return def;
        return Math.round(v / (float) step) * step;
    }

    public static Settings read(Context c) {
        SharedPreferences sp = p(c);
        Settings s = new Settings();
        s.tint = sp.getInt("tint", STEAM_BLUE);
        int op = sp.getInt("opacity", 100);
        s.opacity = snap(op, OPACITY_MIN, OPACITY_MAX, OPACITY_STEP, 100);
        int sz = sp.getInt("size", 100);
        s.size = snap(sz, SIZE_MIN, SIZE_MAX, SIZE_STEP, 100);
        int ss = sp.getInt("stickSens", 100);
        s.stickSens = has(SENSITIVITIES, ss) ? ss : 100;
        int ps = sp.getInt("padSens", 100);
        s.padSens = has(SENSITIVITIES, ps) ? ps : 100;
        s.stickClick = sp.getBoolean("stickClick", true);
        s.adaptiveSticks = sp.getBoolean("adaptiveSticks", true);
        s.rumble = sp.getBoolean("rumble", true);
        for (String id : MAPPABLE_IDS) s.mapping.put(id, target(c, id));
        return s;
    }

    public static String target(Context c, String id) {
        String t = p(c).getString("map." + id, id);
        for (String known : TARGET_IDS) if (known.equals(t)) return t;
        return id;
    }

    public static void setTarget(Context c, String id, String target) { p(c).edit().putString("map." + id, target).apply(); }

    private static String known(String[] list, String v, String def) {
        for (String k : list) if (k.equals(v)) return v;
        return def;
    }

    public static String deckTarget(Context c, String id) { return known(DECK_TARGET_IDS, p(c).getString("dkmap." + id, NONE), NONE); }
    public static void setDeckTarget(Context c, String id, String target) { p(c).edit().putString("dkmap." + id, target).apply(); }
    public static String padMode(Context c) { return known(PAD_MODE_IDS, p(c).getString("padMode", "mouse"), "mouse"); }
    public static void setPadMode(Context c, String mode) { p(c).edit().putString("padMode", mode).apply(); }
    public static String gyroMode(Context c) { return known(GYRO_IDS, p(c).getString("gyroMode", "off"), "off"); }
    public static void setGyroMode(Context c, String mode) { p(c).edit().putString("gyroMode", mode).apply(); }
    public static int gyroSens(Context c) {
        int v = p(c).getInt("gyroSens", 100);
        return snap(v, GYRO_SENS_MIN, GYRO_SENS_MAX, GYRO_SENS_STEP, 100);
    }
    public static void setGyroSens(Context c, int v) { p(c).edit().putInt("gyroSens", v).apply(); }

    public static void resetMapping(Context c) {
        SharedPreferences sp = p(c);
        SharedPreferences.Editor e = sp.edit();
        for (String k : sp.getAll().keySet()) if (k.startsWith("map.") || k.startsWith("dkmap.")) e.remove(k);
        e.remove("padMode").remove("gyroMode").remove("gyroSens");  // DroidDeck-deckmap
        e.apply();
    }

    public static void setTint(Context c, int tint) { p(c).edit().putInt("tint", tint).apply(); }
    public static void setOpacity(Context c, int v) { p(c).edit().putInt("opacity", v).apply(); }
    public static void setSize(Context c, int v) { p(c).edit().putInt("size", v).apply(); }
    public static void setStickSens(Context c, int v) { p(c).edit().putInt("stickSens", v).apply(); }
    public static void setPadSens(Context c, int v) { p(c).edit().putInt("padSens", v).apply(); }
    public static void setStickClick(Context c, boolean on) { p(c).edit().putBoolean("stickClick", on).apply(); }
    public static void setAdaptiveSticks(Context c, boolean on) { p(c).edit().putBoolean("adaptiveSticks", on).apply(); }
    public static boolean isRumbleEnabled(Context c) { return p(c).getBoolean("rumble", true); }
    public static void setRumble(Context c, boolean on) { p(c).edit().putBoolean("rumble", on).apply(); }

    /** Posisi grup kontrol (pecahan 0..1 dari lebar/tinggi) untuk ukuran layar tertentu. */
    public static Map<String, float[]> layout(Context c, int width, int height) {
        Map<String, float[]> out = new HashMap<>();
        String raw = p(c).getString("layout." + width + "x" + height, null);
        if (raw == null || raw.isEmpty()) return out;
        for (String entry : raw.split(";")) {
            String[] kv = entry.split(":");
            if (kv.length != 2) continue;
            String[] xy = kv[1].split(",");
            if (xy.length != 2) continue;
            try {
                out.put(kv[0], new float[]{Float.parseFloat(xy[0]), Float.parseFloat(xy[1])});
            } catch (NumberFormatException ignored) {
            }
        }
        return out;
    }

    public static void setLayout(Context c, int width, int height, Map<String, float[]> positions) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, float[]> e : positions.entrySet()) {
            if (sb.length() > 0) sb.append(';');
            sb.append(e.getKey()).append(':').append(e.getValue()[0]).append(',').append(e.getValue()[1]);
        }
        p(c).edit().putString("layout." + width + "x" + height, sb.toString()).apply();
    }

    public static void resetLayout(Context c, int width, int height) {
        p(c).edit().remove("layout." + width + "x" + height).apply();
    }

    public static void resetAll(Context c) {
        boolean enabled = isEnabled(c);
        p(c).edit().clear().putBoolean("enabled", enabled).apply();
    }
}

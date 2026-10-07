package com.winlator.cmod.droiddeck;

import com.winlator.cmod.container.Container;
import com.winlator.cmod.container.Shortcut;
import com.winlator.cmod.core.EnvVars;
import com.winlator.cmod.inputcontrols.GamepadState;

/**
 * Klien Steam virtual untuk pad Steam Deck (28DE:1205).
 *
 * Di Steam Deck asli, klien Steam memegang pad (mematikan "lizard mode"), membaca laporan hidraw penuh
 * (semua tombol, L4/R4/L5/R5, QAM, dua trackpad, gyro) lalu menerjemahkannya lewat konfigurasi Steam Input
 * menjadi pad XInput + mouse + keyboard virtual. Winlator tidak punya klien Steam yang bisa memegang pad
 * itu, jadi kelas ini memerankan klien tersebut:
 *   - selama sesi Deck aktif dan belum ada klien native (driver HIDAPI SDL / Steam) yang mengirim feature
 *     report ke hidraw, pad dianggap sudah "dipegang" oleh klien virtual ini;
 *   - terjemahan mengikuti templat bawaan Deck: trackpad kanan = mouse, trackpad kiri = D-pad saat diklik
 *     (keduanya sudah ditangani DDController), ditambah gyro dan tombol belakang di sini;
 *   - begitu klien native memegang pad (ST_FSET naik), klien virtual berhenti agar game tidak menerima
 *     input ganda, persis seperti Steam yang menyembunyikan pad fisik saat Steam Input aktif.
 *
 * Pengaturan (Setting Container / Shortcut, menu Input):
 *   extra "droiddeckSteam": "" = default (4) | 0 klien mati | 1 hidup, gyro mati |
 *       2 gyro->mouse saat trackpad kanan disentuh | 3 gyro->mouse selalu |
 *       4 gyro->stik kanan saat trackpad kanan disentuh | 5 gyro->stik kanan selalu
 *   extra "droiddeckGrips": "" / 0 = tombol belakang tidak dipetakan (bawaan Steam) |
 *       1 L4=X R4=A L5=Y R5=B | 2 L4=L3 R4=R3 L5=LB R5=RB | 3 L4=Back R4=Start L5=L3 R5=R3
 * Penimpaan lewat Environment Variables container (nilai di sini mengalahkan pengaturan di atas):
 *   DD_STEAM=0..5, DD_GYRO_SENS=10..1000 (persen, bawaan 100), DD_STEAM_BTN=overlay|off,
 *   DD_GRIP_L4 / DD_GRIP_R4 / DD_GRIP_L5 / DD_GRIP_R5 / DD_QAM = target
 *   (off a b x y lb rb lt rt select start l3 r3 up right down left mouse1 mouse2 mouse3).
 */
public final class DDSteamClient {
    public static final String EXTRA_STEAM = "droiddeckSteam";
    public static final String EXTRA_GRIPS = "droiddeckGrips";

    private static final int DEFAULT_PROFILE = 4;

    // Bit laporan Deck (sama dengan DDDeck / SDL_hidapi_steamdeck.c). Snapshot.low = kata rendah, .high = kata tinggi.
    private static final int H_L4 = 0x200, H_R4 = 0x400, H_QAM = 0x40000;
    private static final int L_L5 = 0x8000, L_R5 = 0x10000, L_RTOUCH = 0x100000;

    // Bit di heldMask.
    private static final int G_L4 = 1, G_R4 = 2, G_L5 = 4, G_R5 = 8, G_QAM = 16;
    private static final int GRIP_COUNT = 5;  // L4, R4, L5, R5, QAM

    private static final String[] VALID_TARGETS = {
        "off", "a", "b", "x", "y", "lb", "rb", "lt", "rt", "select", "start", "l3", "r3",
        "up", "right", "down", "left", "mouse1", "mouse2", "mouse3"};

    // Urutan: L4, R4, L5, R5, QAM
    private static final String[][] GRIP_PRESETS = {
        {"off", "off", "off", "off", "off"},
        {"x", "a", "y", "b", "off"},
        {"l3", "r3", "lb", "rb", "off"},
        {"select", "start", "l3", "r3", "off"},
    };

    private static final float DPS_PER_COUNT = 2000f / 32768f;  // 32768 hitungan = 2000 deg/s (lihat DDDeck.PadMotion)
    private static final float PX_PER_DEG = 8f;                 // piksel kursor per derajat pada sensitivitas 100%
    private static final float STICK_FULL_DPS = 90f;            // deg/s untuk defleksi stik penuh pada sensitivitas 100%
    private static final float DEADZONE_DPS = 1.5f;
    private static final long PUSH_INTERVAL_NS = 8_000_000L;

    /** Diimplementasikan DDController: semua pemanggilan sudah aman dari thread mana pun. */
    public interface Host {
        void pushPad();                              // kirim ulang keadaan pad XInput (DDController.pushPadState)
        void mouseDelta(int dx, int dy);
        void mouseButton(int which, boolean down);   // 1 kiri, 2 kanan, 3 tengah
    }

    private static volatile Host host;
    private static volatile String extraSteam = "", extraGrips = "";
    private static volatile String envSteam = "", envSens = "", envSteamBtn = "";
    private static final String[] envGrip = {"", "", "", "", ""};

    private static volatile int heldMask = 0;
    private static volatile boolean rightTouch = false;
    private static volatile float gyroStickX = 0f, gyroStickY = 0f;
    private static final boolean[] mouseDown = new boolean[3];
    private static long lastGyroNs = 0L, lastPushNs = 0L;
    private static float remX = 0f, remY = 0f;

    private DDSteamClient() {}

    // ------------------------------------------------------------ konfigurasi

    private static String clean(String v) { return v == null ? "" : v.trim(); }

    private static String pick(String shortcutValue, String containerValue) {
        String s = clean(shortcutValue);
        return s.isEmpty() ? clean(containerValue) : s;
    }

    /** Dibaca dari pengaturan game/container; aman dipanggil berulang (dipanggil dari DDDeck.configureLaunch). */
    public static void configure(Container container, Shortcut shortcut) {
        extraSteam = pick(shortcut != null ? shortcut.getExtra(EXTRA_STEAM, "") : "",
                          container != null ? container.getExtra(EXTRA_STEAM, "") : "");
        extraGrips = pick(shortcut != null ? shortcut.getExtra(EXTRA_GRIPS, "") : "",
                          container != null ? container.getExtra(EXTRA_GRIPS, "") : "");
    }

    /**
     * Penimpaan dari Environment Variables container, dipanggil GuestProgramLauncherComponent setelah variabel
     * pengguna digabung. Menambah SteamDeck=1 (variabel yang dicek game/Proton untuk mode Deck) bila klien
     * virtual aktif dan pengguna belum mengaturnya.
     */
    public static void applyLaunchEnv(EnvVars env) {
        if (env == null) return;
        envSteam = clean(env.get("DD_STEAM"));
        envSens = clean(env.get("DD_GYRO_SENS"));
        envSteamBtn = clean(env.get("DD_STEAM_BTN"));
        DDDeck.applyLaunchEnv(clean(env.get("DD_ACCEL")), clean(env.get("DD_GYRO")));  // DD_ACCEL=0 / DD_GYRO=0: sensor mati
        envGrip[0] = clean(env.get("DD_GRIP_L4"));
        envGrip[1] = clean(env.get("DD_GRIP_R4"));
        envGrip[2] = clean(env.get("DD_GRIP_L5"));
        envGrip[3] = clean(env.get("DD_GRIP_R5"));
        envGrip[4] = clean(env.get("DD_QAM"));
        if (isEnabled() && !env.has("SteamDeck")) env.put("SteamDeck", "1");
    }

    private static int parseInt(String v, int min, int max, int fallback) {
        if (v == null || v.isEmpty()) return fallback;
        try {
            int n = Integer.parseInt(v.trim());
            return n >= min && n <= max ? n : fallback;
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static int profile() {
        return parseInt(!envSteam.isEmpty() ? envSteam : extraSteam, 0, 5, DEFAULT_PROFILE);
    }

    private static float sensitivity() {
        return parseInt(envSens, 10, 1000, 100) / 100f;
    }

    private static String gripTarget(int index) {
        String e = envGrip[index];
        if (!e.isEmpty()) {
            String t = e.toLowerCase(java.util.Locale.ROOT);
            for (String known : VALID_TARGETS) if (known.equals(t)) return t;
            return "off";
        }
        int preset = parseInt(extraGrips, 0, GRIP_PRESETS.length - 1, 0);
        return GRIP_PRESETS[preset][index];
    }

    /** Pengaturan mengizinkan klien virtual (belum tentu aktif: lihat isActive). */
    public static boolean isEnabled() {
        return DDDeck.isWanted() && profile() != 0;
    }

    /**
     * Klien virtual sedang memegang pad: sesi Deck berjalan, pengaturan menyala, dan tidak ada klien native
     * yang sudah mengirim feature report ke hidraw.
     */
    public static boolean isActive() {
        return DDDeck.isSessionActive() && isEnabled() && !DDDeck.isHidrawClientActive();
    }

    /** Tombol Steam mengirim hotkey overlay Steam (Shift+Tab), seperti klien Steam pada Windows. */
    public static boolean steamOverlayHotkey() {
        return !"off".equalsIgnoreCase(envSteamBtn);
    }

    public static void setHost(Host h) {
        host = h;
        if (h == null) reset();
    }

    private static synchronized void reset() {
        heldMask = 0;
        rightTouch = false;
        gyroStickX = 0f;
        gyroStickY = 0f;
        lastGyroNs = 0L;
        remX = 0f;
        remY = 0f;
        java.util.Arrays.fill(mouseDown, false);
    }

    // ------------------------------------------------------------ tombol dan trackpad

    /** Dipanggil DDDeck setelah tombol belakang, QAM, atau sentuhan trackpad berubah. */
    public static void onDeckChanged() {
        int mask = 0;
        boolean touch = false;
        if (isActive()) {
            DDDeck.Snapshot s = DDDeck.snapshot();
            if ((s.high & H_L4) != 0) mask |= G_L4;
            if ((s.high & H_R4) != 0) mask |= G_R4;
            if ((s.low & L_L5) != 0) mask |= G_L5;
            if ((s.low & L_R5) != 0) mask |= G_R5;
            if ((s.high & H_QAM) != 0) mask |= G_QAM;
            touch = (s.low & L_RTOUCH) != 0;
        }
        update(mask, touch);
    }

    private static void update(int mask, boolean touch) {
        boolean[] want = new boolean[3];
        for (int i = 0; i < GRIP_COUNT; i++) {
            if ((mask & (1 << i)) == 0) continue;
            String t = gripTarget(i);
            if (t.startsWith("mouse")) want[t.charAt(5) - '1'] = true;
        }
        boolean padChanged;
        boolean[] edges = new boolean[3];
        boolean[] downs = new boolean[3];
        synchronized (DDSteamClient.class) {
            padChanged = mask != heldMask;
            heldMask = mask;
            rightTouch = touch;
            for (int i = 0; i < 3; i++) {
                if (want[i] != mouseDown[i]) {
                    mouseDown[i] = want[i];
                    edges[i] = true;
                    downs[i] = want[i];
                }
            }
        }
        Host h = host;
        if (h == null) return;
        for (int i = 0; i < 3; i++) if (edges[i]) h.mouseButton(i + 1, downs[i]);
        if (padChanged) h.pushPad();
    }

    // ------------------------------------------------------------------ gyro

    private static float dead(float dps) {
        float a = Math.abs(dps);
        return a < DEADZONE_DPS ? 0f : Math.signum(dps) * (a - DEADZONE_DPS);
    }

    private static float clamp1(float v) { return Math.max(-1f, Math.min(1f, v)); }

    /**
     * Dipanggil DDDeck dari thread sensor tiap sampel gyro baru.
     * @param pitch hitungan mentah putaran di sumbu kanan layar (positif = sisi atas mendekat ke pemain)
     * @param yaw hitungan mentah putaran di sumbu atas layar (positif = kamera menengok ke kiri)
     * @param timestampNs SensorEvent.timestamp
     */
    public static void onGyro(int pitch, int yaw, long timestampNs) {
        long prev = lastGyroNs;
        lastGyroNs = timestampNs;
        int p = profile();
        boolean mouse = p == 2 || p == 3;
        boolean stick = p == 4 || p == 5;
        boolean gated = p == 2 || p == 4;
        Host h = host;
        boolean run = h != null && (mouse || stick) && isActive() && !(gated && !rightTouch);
        if (!run) {
            if (gyroStickX != 0f || gyroStickY != 0f) {
                gyroStickX = 0f;
                gyroStickY = 0f;
                if (h != null) h.pushPad();
            }
            remX = 0f;
            remY = 0f;
            return;
        }
        if (prev == 0L || timestampNs <= prev) return;
        float dt = Math.min((timestampNs - prev) / 1e9f, 0.05f);
        float pitchDps = dead(pitch * DPS_PER_COUNT);
        float yawDps = dead(yaw * DPS_PER_COUNT);
        float sens = sensitivity();
        if (mouse) {
            remX += -yawDps * dt * PX_PER_DEG * sens;   // menengok ke kiri -> kursor ke kiri
            remY += pitchDps * dt * PX_PER_DEG * sens;  // sisi atas mendekat -> melihat ke bawah -> kursor ke bawah
            int dx = (int) remX, dy = (int) remY;
            if (dx != 0 || dy != 0) {
                remX -= dx;
                remY -= dy;
                h.mouseDelta(dx, dy);
            }
            return;
        }
        float sx = clamp1(-yawDps * sens / STICK_FULL_DPS);
        float sy = clamp1(pitchDps * sens / STICK_FULL_DPS);  // GamepadState: Y positif = bawah
        if (sx == gyroStickX && sy == gyroStickY) return;
        gyroStickX = sx;
        gyroStickY = sy;
        boolean zero = sx == 0f && sy == 0f;
        if (zero || timestampNs - lastPushNs >= PUSH_INTERVAL_NS) {
            lastPushNs = timestampNs;
            h.pushPad();
        }
    }

    // ------------------------------------------------------------ keluaran pad

    private static void press(String target, GamepadState s) {
        switch (target) {
            case "a": s.setPressed(0, true); break;
            case "b": s.setPressed(1, true); break;
            case "x": s.setPressed(2, true); break;
            case "y": s.setPressed(3, true); break;
            case "lb": s.setPressed(4, true); break;
            case "rb": s.setPressed(5, true); break;
            case "select": s.setPressed(6, true); break;
            case "start": s.setPressed(7, true); break;
            case "l3": s.setPressed(8, true); break;
            case "r3": s.setPressed(9, true); break;
            case "lt": s.triggerL = 1f; break;
            case "rt": s.triggerR = 1f; break;
            case "up": s.dpad[0] = true; break;
            case "right": s.dpad[1] = true; break;
            case "down": s.dpad[2] = true; break;
            case "left": s.dpad[3] = true; break;
            default: break;  // off dan mouse*: mouse ditangani update()
        }
    }

    /** Menggabungkan tombol belakang dan gyro->stik ke keadaan pad XInput yang akan dikirim ke Wine. */
    public static void overlay(GamepadState s) {
        if (s == null || !isActive()) return;
        int mask = heldMask;
        for (int i = 0; i < GRIP_COUNT; i++) if ((mask & (1 << i)) != 0) press(gripTarget(i), s);
        float gx = gyroStickX, gy = gyroStickY;
        if (gx != 0f || gy != 0f) {
            s.thumbRX = clamp1(s.thumbRX + gx);
            s.thumbRY = clamp1(s.thumbRY + gy);
        }
    }
}

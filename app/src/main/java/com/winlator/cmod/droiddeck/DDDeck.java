package com.winlator.cmod.droiddeck;

import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Handler;
import android.os.HandlerThread;
import android.system.Os;
import android.util.Log;
import android.view.Surface;

import com.winlator.cmod.container.Container;
import com.winlator.cmod.container.Shortcut;
import com.winlator.cmod.inputcontrols.GamepadState;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteOrder;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.util.HashMap;
import java.util.Map;

/**
 * Pad Steam Deck (28DE:1205) untuk libfakeinput: menulis file state yang dibaca thread laporan
 * native tiap 4 ms, dan pohon sysfs yang dicari klien Steam lewat udev.
 * Port dari SteamDeckPad.kt, PadMotion.kt dan DeckControls.kt (DroidDeck).
 */
public final class DDDeck implements SensorEventListener {
    private static final String TAG = "DDDeck";
    private static final int MAJOR = 240, MINOR = 16;
    private static final String NODE = "hidraw" + MINOR, SERIAL = "DROIDDECK0001";
    private static final int STATE_SIZE = 64, STATE_MAGIC = 0x31534B44;

    // Bit laporan Deck (SDL_hidapi_steamdeck.c): kata rendah lalu tinggi.
    private static final int L_R2 = 0x1, L_L2 = 0x2, L_R1 = 0x4, L_L1 = 0x8, L_Y = 0x10, L_B = 0x20, L_X = 0x40,
        L_A = 0x80, L_UP = 0x100, L_RIGHT = 0x200, L_LEFT = 0x400, L_DOWN = 0x800, L_VIEW = 0x1000,
        L_STEAM = 0x2000, L_MENU = 0x4000, L_L5 = 0x8000, L_R5 = 0x10000, L_LPAD = 0x20000, L_RPAD = 0x40000,
        L_LTOUCH = 0x80000, L_RTOUCH = 0x100000, L_L3 = 0x400000, L_R3 = 0x4000000;
    private static final int H_L4 = 0x200, H_R4 = 0x400, H_QAM = 0x40000;

    public static final int GRIP_L4 = 1, GRIP_R4 = 2, GRIP_L5 = 4, GRIP_R5 = 8;

    // Mode ditentukan di Setting Container / Setting Shortcut (menu Input), bukan di sidebar in-game:
    //   extra "droiddeckDeck"      : "" = ikut container / default (mati), "1" = nyala, "0" = mati
    //   extra "droiddeckDeckEvdev" : "" = ikut container / default (nyala), "1" = nyala, "0" = hanya hidraw
    // Shortcut mengalahkan container; container mengalahkan default.
    public static final String EXTRA_DECK = "droiddeckDeck";
    public static final String EXTRA_EVDEV = "droiddeckDeckEvdev";
    private static volatile boolean wanted = false;
    private static volatile boolean alsoEvdev = true;
    // DroidDeck-fix: evdev dipaksa aktif sampai terbukti Wine punya UDEV (hidraw hanya terbaca bila ada UDEV)
    private static volatile boolean forceEvdev = true;
    private static volatile int udevState = -1;  // -1 belum diketahui, 0 tanpa UDEV, 1 ada UDEV
    private static final java.util.Map<String, Boolean> UDEV_CACHE = new java.util.HashMap<>();
    private static volatile boolean sessionActive = false;

    private static final DDDeck INSTANCE = new DDDeck();
    private static MappedByteBuffer map;
    private static MappedByteBuffer statusMap;  // DroidDeck-bp
    private static File root;

    // Keadaan yang digabung menjadi satu laporan.
    private static int padLow = 0, extraLow = 0, extraHigh = 0;
    private static final short[] sticks = new short[4];
    private static final short[] trig = new short[2];
    private static final short[] pads = new short[4];
    private static final short[] pressure = new short[2];
    private static final short[] accel = new short[3];
    private static final short[] gyro = new short[3];
    private static long seq = 0;

    private HandlerThread thread;
    private volatile boolean active;
    private java.util.function.IntSupplier rotation = () -> Surface.ROTATION_0;

    private DDDeck() {}

    private static boolean resolve(String shortcutValue, String containerValue, boolean fallback) {
        if ("1".equals(shortcutValue)) return true;
        if ("0".equals(shortcutValue)) return false;
        if ("1".equals(containerValue)) return true;
        if ("0".equals(containerValue)) return false;
        return fallback;
    }

    private static String extra(Shortcut shortcut, Container container, String name, boolean fromShortcut) {
        String v = fromShortcut ? (shortcut != null ? shortcut.getExtra(name, "") : "")
                                : (container != null ? container.getExtra(name, "") : "");
        return v == null ? "" : v;
    }

    /** Dibaca dari pengaturan game/container; aman dipanggil berulang dan dari urutan mana pun. */
    public static void configureLaunch(Container container, Shortcut shortcut) {
        wanted = resolve(extra(shortcut, container, EXTRA_DECK, true), extra(shortcut, container, EXTRA_DECK, false), false);
        alsoEvdev = resolve(extra(shortcut, container, EXTRA_EVDEV, true), extra(shortcut, container, EXTRA_EVDEV, false), true)
            || forceEvdev;  // DroidDeck-fix
        DDSteamClient.configure(container, shortcut);  // DroidDeck-steam
    }

    /** Versi dengan jalur runtime Wine: memutuskan apakah pilihan "hidraw only" boleh dihormati. */
    public static void configureLaunch(Container container, Shortcut shortcut, String winePath) {
        boolean udev = wineHasUdev(winePath);
        udevState = udev ? 1 : 0;
        forceEvdev = !udev;
        Log.i(TAG, "Wine UDEV: " + (udev ? "ada" : "tidak ada / tak terdeteksi") + " (" + winePath + ") -> evdev "
            + (forceEvdev ? "dipaksa aktif" : "mengikuti setting"));
        configureLaunch(container, shortcut);
    }

    /** -1 belum diketahui, 0 Wine tanpa UDEV, 1 Wine dengan UDEV. */
    public static int udevState() { return udevState; }

    /**
     * true hanya bila SEMUA winebus.so di runtime memuat "libudev"; tanpa itu winebus mencetak
     * "UDEV support not compiled in!" dan tidak pernah mengenumerasi hidraw.
     */
    public static boolean wineHasUdev(String winePath) {
        if (winePath == null || winePath.isEmpty()) return false;
        synchronized (UDEV_CACHE) {
            Boolean cached = UDEV_CACHE.get(winePath);
            if (cached != null) return cached;
        }
        java.util.List<File> found = new java.util.ArrayList<>();
        collect(new File(winePath), "winebus.so", 7, found);
        boolean all = !found.isEmpty();
        // Build glibc memuat "libudev" (dlopen). Build NDK (winebus-test) men-link libudev-zero statis sehingga
        // string itu tidak ada; di sana pertanda UDEV aktif adalah TIDAKnya pesan "not compiled in" (hanya ada di cabang #else).
        for (File f : found) if (!fileContains(f, "libudev") && fileContains(f, "UDEV support not compiled in")) { all = false; break; }
        synchronized (UDEV_CACHE) { UDEV_CACHE.put(winePath, all); }
        return all;
    }

    private static void collect(File dir, String name, int depth, java.util.List<File> out) {
        File[] kids = dir.listFiles();
        if (kids == null) return;
        for (File k : kids) {
            if (k.isDirectory()) { if (depth > 0) collect(k, name, depth - 1, out); }
            else if (k.getName().equals(name)) out.add(k);
        }
    }

    private static boolean fileContains(File f, String needle) {
        byte[] pat = needle.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        byte[] buf = new byte[1 << 16];
        int keep = pat.length - 1, have = 0;
        try (java.io.FileInputStream in = new java.io.FileInputStream(f)) {
            int n;
            while ((n = in.read(buf, have, buf.length - have)) > 0) {
                int total = have + n;
                for (int i = 0; i + pat.length <= total; i++) {
                    int j = 0;
                    while (j < pat.length && buf[i + j] == pat[j]) j++;
                    if (j == pat.length) return true;
                }
                have = Math.min(keep, total);
                System.arraycopy(buf, total - have, buf, 0, have);
            }
        } catch (IOException e) {
            return false;
        }
        return false;
    }

    /** Pad Deck diminta untuk sesi ini (hasil configureLaunch). */
    public static boolean isWanted() { return wanted; }

    /** Selain hidraw, kirim juga ke pad evdev/XInput biasa agar game tanpa klien Steam tetap dikendalikan. */
    public static boolean isAlsoEvdev() { return alsoEvdev; }

    /** true hanya bila sesi ini benar-benar menyiapkan pad Deck (file state + sysfs berhasil dibuat). */
    public static boolean isSessionActive() { return sessionActive; }
    public static void setSessionActive(boolean on) { sessionActive = on; }

    /** Menyiapkan file state dan pohon sysfs; mengembalikan variabel lingkungan untuk proses guest. Idempoten. */
    public static synchronized Map<String, String> prepare(Context c) {
        Map<String, String> env = new HashMap<>();
        try {
            File base = new File(c.getFilesDir(), "deck");
            root = new File(base, "root");
            File state = new File(base, "state.bin");
            if (map == null) {
                base.mkdirs();
                try (RandomAccessFile raf = new RandomAccessFile(state, "rw")) {
                    raf.setLength(STATE_SIZE);
                    map = raf.getChannel().map(FileChannel.MapMode.READ_WRITE, 0, STATE_SIZE);
                }
                map.order(ByteOrder.LITTLE_ENDIAN);
                buildSysfs();
                publish();
            }
            // DroidDeck-bp: file penghitung status yang ditulis libfakeinput (dibaca layar uji)
            File statusFile = new File(base, "status.bin");
            if (statusMap == null) {
                try (RandomAccessFile sraf = new RandomAccessFile(statusFile, "rw")) {
                    sraf.setLength(64);
                    statusMap = sraf.getChannel().map(FileChannel.MapMode.READ_WRITE, 0, 64);
                }
                statusMap.order(ByteOrder.LITTLE_ENDIAN);
                for (int i = 0; i < 16; i++) statusMap.putInt(i * 4, 0);
            }
            env.put("FAKE_DECK_STATUS", statusFile.getAbsolutePath());
            File devDir = new File(base, "devdir");  // pengganti /dev yang ditolak SELinux (opendir/inotify)
            devDir.mkdirs();
            env.put("FAKE_EVDEV_DECK", "1");
            env.put("FAKE_DECK_STATE", state.getAbsolutePath());
            env.put("FAKE_DECK_SYSFS_DIR", root.getAbsolutePath());
            env.put("FAKE_DECK_DEVDIR", devDir.getAbsolutePath());
            // winebus (Proton) membuang hidraw yang tidak ada di allowlist -> "deferring to a different backend"
            env.put("PROTON_ENABLE_HIDRAW", "0x28de/0x1205");
            // Game berbasis SDL (>= 2.28): driver HIDAPI Steam Deck membaca hidraw sendiri (gyro, trackpad, L4/R4/L5/R5)
            env.put("SDL_JOYSTICK_HIDAPI_STEAMDECK", "1");
        } catch (Exception e) {
            Log.w(TAG, "Deck pad tidak bisa disiapkan: " + e);
            env.clear();
        }
        return env;
    }

    // ---------------------------------------------------------------- sysfs

    private static final byte[] DESCRIPTOR = {
        0x06, (byte) 0xff, (byte) 0xff, 0x09, 0x01, (byte) 0xa1, 0x01, 0x09, 0x02, 0x09, 0x03, 0x15, 0x00,
        0x26, (byte) 0xff, 0x00, 0x75, 0x08, (byte) 0x95, 0x40, (byte) 0x81, 0x02, 0x09, 0x06, 0x09, 0x07,
        0x15, 0x00, 0x26, (byte) 0xff, 0x00, 0x75, 0x08, (byte) 0x95, 0x40, (byte) 0xb1, 0x02, (byte) 0xc0};

    private static File dir(String path) {
        File f = new File(root, path);
        f.mkdirs();
        return f;
    }

    private static void write(String path, String name, String text) throws IOException {
        try (java.io.FileWriter w = new java.io.FileWriter(new File(dir(path), name))) { w.write(text); }
    }

    /** Symlink relatif, agar penelusurannya tetap di dalam pohon tanpa bantuan hook. */
    private static void link(File link, File target) throws Exception {
        link.getParentFile().mkdirs();
        link.delete();
        String[] from = link.getParentFile().getAbsolutePath().split("/");
        String[] to = target.getAbsolutePath().split("/");
        int i = 0;
        while (i < from.length && i < to.length && from[i].equals(to[i])) i++;
        StringBuilder rel = new StringBuilder();
        for (int k = i; k < from.length; k++) rel.append("../");
        for (int k = i; k < to.length; k++) { rel.append(to[k]); if (k < to.length - 1) rel.append('/'); }
        Os.symlink(rel.toString(), link.getPath());
    }

    private static void deleteTree(File f) {
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) deleteTree(k);
        f.delete();
    }

    /** Tata letak sysfs antarmuka kontroler Deck seperti kernel, dicerminkan di bawah root. */
    private static void buildSysfs() throws Exception {
        deleteTree(root);
        String usb = "sys/devices/droiddeck/usb1";
        String iface = usb + "/1-1:1.2";
        String hid = iface + "/0003:28DE:1205.0001";
        String hidraw = hid + "/hidraw/" + NODE;
        write(usb, "uevent", "DEVTYPE=usb_device\nPRODUCT=28de/1205/100\nTYPE=0/0/0\nBUSNUM=001\nDEVNUM=002\n");
        write(usb, "idVendor", "28de\n");
        write(usb, "idProduct", "1205\n");
        write(usb, "bcdDevice", "0100\n");
        write(usb, "manufacturer", "Valve Software\n");
        write(usb, "product", "Steam Deck Controller\n");
        write(usb, "serial", SERIAL + "\n");
        File busUsb = dir("sys/bus/usb"), busHid = dir("sys/bus/hid"), clsHidraw = dir("sys/class/hidraw");
        link(new File(dir(usb), "subsystem"), busUsb);
        write(iface, "uevent", "DEVTYPE=usb_interface\nPRODUCT=28de/1205/100\nINTERFACE=3/0/0\n");
        write(iface, "bInterfaceNumber", "02\n");
        write(iface, "bInterfaceClass", "03\n");
        link(new File(dir(iface), "subsystem"), busUsb);
        write(hid, "uevent", "DRIVER=hid-steam\nHID_ID=0003:000028DE:00001205\n"
            + "HID_NAME=Valve Software Steam Deck Controller\nHID_PHYS=usb-droiddeck-1/input2\n"
            + "HID_UNIQ=" + SERIAL + "\nMODALIAS=hid:b0003g0001v000028DEp00001205\n");
        try (java.io.FileOutputStream o = new java.io.FileOutputStream(new File(dir(hid), "report_descriptor"))) { o.write(DESCRIPTOR); }
        link(new File(dir(hid), "subsystem"), busHid);
        write(hidraw, "uevent", "MAJOR=" + MAJOR + "\nMINOR=" + MINOR + "\nDEVNAME=" + NODE + "\n");
        write(hidraw, "dev", MAJOR + ":" + MINOR + "\n");
        link(new File(dir(hidraw), "subsystem"), clsHidraw);
        link(new File(dir(hidraw), "device"), dir(hid));
        link(new File(clsHidraw, NODE), dir(hidraw));
        link(new File(dir("sys/dev/char"), MAJOR + ":" + MINOR), dir(hidraw));
        write("run/udev/data", "c" + MAJOR + ":" + MINOR, "I:1\nE:ID_INPUT=1\nE:ID_INPUT_JOYSTICK=1\n");
    }

    // ---------------------------------------------------------------- state

    private static void publish() {
        if (map == null) return;
        synchronized (DDDeck.class) {
            seq++;                                   // ganjil: sedang ditulis
            map.putLong(8, seq);
            map.putInt(0, STATE_MAGIC);
            map.putInt(16, padLow | extraLow);
            map.putInt(20, extraHigh);
            for (int i = 0; i < 4; i++) map.putShort(24 + i * 2, pads[i]);
            for (int i = 0; i < 4; i++) map.putShort(32 + i * 2, sticks[i]);
            map.putShort(40, trig[0]);
            map.putShort(42, trig[1]);
            map.putShort(44, pressure[0]);
            map.putShort(46, pressure[1]);
            for (int i = 0; i < 3; i++) map.putShort(48 + i * 2, accel[i]);
            for (int i = 0; i < 3; i++) map.putShort(54 + i * 2, gyro[i]);
            seq++;                                   // genap: konsisten
            map.putLong(8, seq);
        }
    }

    private static short axis(float v) { return (short) (Math.max(-1f, Math.min(1f, v)) * 32767f); }

    /** Tombol/stik/trigger/d-pad dari kontrol layar DroidDeck. */
    public static void updatePad(GamepadState s) {
        int low = 0;
        if (s.isPressed((byte) 0)) low |= L_A;
        if (s.isPressed((byte) 1)) low |= L_B;
        if (s.isPressed((byte) 2)) low |= L_X;
        if (s.isPressed((byte) 3)) low |= L_Y;
        if (s.isPressed((byte) 4)) low |= L_L1;
        if (s.isPressed((byte) 5)) low |= L_R1;
        if (s.isPressed((byte) 6)) low |= L_VIEW;
        if (s.isPressed((byte) 7)) low |= L_MENU;
        if (s.isPressed((byte) 8)) low |= L_L3;
        if (s.isPressed((byte) 9)) low |= L_R3;
        if (s.dpad[0]) low |= L_UP;
        if (s.dpad[1]) low |= L_RIGHT;
        if (s.dpad[2]) low |= L_DOWN;
        if (s.dpad[3]) low |= L_LEFT;
        if (s.triggerL > 0.5f) low |= L_L2;
        if (s.triggerR > 0.5f) low |= L_R2;
        synchronized (DDDeck.class) {
            padLow = low;
            sticks[0] = axis(s.thumbLX);
            sticks[1] = axis(-s.thumbLY);   // Winlator: Y ke bawah; Deck: Y ke atas
            sticks[2] = axis(s.thumbRX);
            sticks[3] = axis(-s.thumbRY);
            trig[0] = (short) (Math.max(0f, Math.min(1f, s.triggerL)) * 32767f);
            trig[1] = (short) (Math.max(0f, Math.min(1f, s.triggerR)) * 32767f);
        }
        publish();
    }

    public static void setGuide(boolean down) { synchronized (DDDeck.class) { extraLow = down ? (extraLow | L_STEAM) : (extraLow & ~L_STEAM); } publish(); }
    public static void setQam(boolean down) { synchronized (DDDeck.class) { extraHigh = down ? (extraHigh | H_QAM) : (extraHigh & ~H_QAM); } publish(); DDSteamClient.onDeckChanged(); }

    public static void setGrip(int grip, boolean down) {
        synchronized (DDDeck.class) {
            if (grip == GRIP_L4) extraHigh = down ? (extraHigh | H_L4) : (extraHigh & ~H_L4);
            else if (grip == GRIP_R4) extraHigh = down ? (extraHigh | H_R4) : (extraHigh & ~H_R4);
            else if (grip == GRIP_L5) extraLow = down ? (extraLow | L_L5) : (extraLow & ~L_L5);
            else if (grip == GRIP_R5) extraLow = down ? (extraLow | L_R5) : (extraLow & ~L_R5);
        }
        publish();
        DDSteamClient.onDeckChanged();  // DroidDeck-steam
    }

    /** Pendengar trackpad untuk jalur evdev (dipasang DDController): meniru pemetaan bawaan Steam Deck tanpa klien Steam. */
    public interface PadListener {
        void onPad(boolean right, boolean touching, float x, float y);
        void onClick(boolean right, boolean down);
    }

    private static volatile PadListener padListener;

    public static void setPadListener(PadListener l) { padListener = l; }

    /** Wine sudah membuka /dev/hidraw16 (penghitung ST_OPEN dari libfakeinput; direset tiap peluncuran). */
    public static boolean isHidrawDetected() { return status(ST_OPEN) > 0; }

    /**
     * Ada klien native (driver SDL Steam Deck, Steam, dsb.) yang mengirim feature report ke pad Deck, yaitu
     * "lizard mode" dimatikan dan klien itu yang memegang pad. Penghitung ST_FSET naik hanya untuk SET feature.
     */
    public static boolean isHidrawClientActive() { return status(ST_FSET) > 0; }

    /**
     * evdev aktif bila dipilih/dipaksa, atau selama hidraw belum terdeteksi, agar pad tidak diam-diam mati.
     * Begitu klien native memegang pad Deck, evdev (dan pemetaan trackpad ke mouse/D-pad) dimatikan supaya
     * game tidak menerima input ganda, seperti Steam yang menyembunyikan pad fisik saat Steam Input aktif.
     */
    public static boolean isEvdevActive() {
        // DroidDeck-steam: klien Steam virtual memakai jalur pad XInput ini sebagai keluarannya
        return !isHidrawClientActive() && (alsoEvdev || !isHidrawDetected() || DDSteamClient.isEnabled());
    }

    /** Jari di (atau lepas dari) satu trackpad pada x, y dalam -1..1, y ke atas. */
    public static void setPad(boolean right, boolean touching, float x, float y) {
        synchronized (DDDeck.class) {
            int at = right ? 2 : 0;
            int bit = right ? L_RTOUCH : L_LTOUCH;
            extraLow = touching ? (extraLow | bit) : (extraLow & ~bit);
            pads[at] = touching ? axis(x) : 0;
            pads[at + 1] = touching ? axis(y) : 0;
        }
        publish();
        DDSteamClient.onDeckChanged();  // DroidDeck-steam: sentuhan trackpad kanan mengaktifkan gyro
        PadListener l = padListener;
        if (l != null) l.onPad(right, touching, x, y);
    }

    public static void setClick(boolean right, boolean down) {
        synchronized (DDDeck.class) {
            int bit = right ? L_RPAD : L_LPAD;
            extraLow = down ? (extraLow | bit) : (extraLow & ~bit);
            pressure[right ? 1 : 0] = down ? Short.MAX_VALUE : 0;
        }
        publish();
        PadListener l = padListener;
        if (l != null) l.onClick(right, down);
    }

    public static void releaseAll() {
        synchronized (DDDeck.class) {
            padLow = 0; extraLow = 0; extraHigh = 0;
            java.util.Arrays.fill(sticks, (short) 0);
            java.util.Arrays.fill(trig, (short) 0);
            java.util.Arrays.fill(pads, (short) 0);
            java.util.Arrays.fill(pressure, (short) 0);
        }
        publish();
        DDSteamClient.onDeckChanged();  // DroidDeck-steam
        PadListener l = padListener;
        if (l != null) {
            l.onPad(false, false, 0f, 0f);
            l.onPad(true, false, 0f, 0f);
            l.onClick(false, false);
            l.onClick(true, false);
        }
    }

    // ---- DroidDeck-bp: data untuk layar uji ----

    /** Salinan keadaan pad Deck saat ini (unit laporan: stik/pad/trigger/gyro dalam hitungan mentah). */
    public static final class Snapshot {
        public int low, high;
        public final short[] sticks = new short[4], trig = new short[2], pads = new short[4],
            pressure = new short[2], accel = new short[3], gyro = new short[3];
    }

    public static Snapshot snapshot() {
        Snapshot s = new Snapshot();
        synchronized (DDDeck.class) {
            s.low = padLow | extraLow;
            s.high = extraHigh;
            System.arraycopy(sticks, 0, s.sticks, 0, 4);
            System.arraycopy(trig, 0, s.trig, 0, 2);
            System.arraycopy(pads, 0, s.pads, 0, 4);
            System.arraycopy(pressure, 0, s.pressure, 0, 2);
            System.arraycopy(accel, 0, s.accel, 0, 3);
            System.arraycopy(gyro, 0, s.gyro, 0, 3);
        }
        return s;
    }

    private static void put16(byte[] r, int at, int v) { r[at] = (byte) v; r[at + 1] = (byte) (v >> 8); }
    private static void put32(byte[] r, int at, int v) { for (int i = 0; i < 4; i++) r[at + i] = (byte) (v >> (8 * i)); }

    /** Laporan input 64-byte yang dibaca klien dari /dev/hidraw16 (tata letak sama dengan libfakeinput). */
    public static byte[] buildReport(int packet) {
        Snapshot s = snapshot();
        byte[] r = new byte[64];
        r[0] = 0x01;
        r[2] = 0x09;
        r[3] = 64;
        put32(r, 4, packet);
        put32(r, 8, s.low);
        put32(r, 12, s.high);
        for (int i = 0; i < 4; i++) put16(r, 16 + i * 2, s.pads[i]);
        for (int i = 0; i < 3; i++) { put16(r, 24 + i * 2, s.accel[i]); put16(r, 30 + i * 2, s.gyro[i]); }
        put16(r, 44, s.trig[0]);
        put16(r, 46, s.trig[1]);
        for (int i = 0; i < 4; i++) put16(r, 48 + i * 2, s.sticks[i]);
        put16(r, 56, s.pressure[0]);
        put16(r, 58, s.pressure[1]);
        return r;
    }

    public static final int ST_SYSFS = 1, ST_OPEN = 2, ST_CLOSE = 3, ST_FSET = 4, ST_FGET = 5, ST_UNHANDLED = 6,
        ST_REPORTS = 7, ST_LASTFEATURE = 8;

    /** Penghitung dari libfakeinput; -1 bila file status belum disiapkan. */
    public static int status(int index) {
        MappedByteBuffer m = statusMap;
        return m == null ? -1 : m.getInt(index * 4);
    }

    public static boolean hasStatus() { return statusMap != null; }

    public static void resetStatus() {
        MappedByteBuffer m = statusMap;
        if (m != null) for (int i = 0; i < 16; i++) m.putInt(i * 4, 0);
    }

    /** Pohon sysfs Deck sudah ada di disk (dibuat prepare()). */
    public static boolean sysfsReady() {
        return root != null && new File(root, "sys/class/hidraw/" + NODE).exists();
    }

    public static boolean stateReady() { return map != null; }
    public static boolean motionRunning() { return INSTANCE.thread != null; }
    // ---- end DroidDeck-bp ----

    // --------------------------------------------------------------- motion

    /** Gyro dan accelerometer HP sebagai milik Deck (PadMotion.kt): 1 g = 16384, 2000 deg/s = 32768. */
    public static void startMotion(Context c, java.util.function.IntSupplier rotation) {
        INSTANCE.start(c, rotation);
    }

    public static void stopMotion() { INSTANCE.stop(); }

    private void start(Context c, java.util.function.IntSupplier rot) {
        if (thread != null) return;
        SensorManager sm = (SensorManager) c.getSystemService(Context.SENSOR_SERVICE);
        if (sm == null) return;
        Sensor g = sm.getDefaultSensor(Sensor.TYPE_GYROSCOPE), a = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        if (g == null && a == null) return;
        rotation = rot;
        thread = new HandlerThread("dd-deck-motion", android.os.Process.THREAD_PRIORITY_DISPLAY);
        thread.start();
        Handler h = new Handler(thread.getLooper());
        active = true;
        if (g != null) sm.registerListener(this, g, 4000, h);
        if (a != null) sm.registerListener(this, a, 4000, h);
    }

    private void stop() {
        HandlerThread t = thread;
        if (t == null) return;
        thread = null;
        active = false;
        new Handler(t.getLooper()).post(() -> {
            synchronized (DDDeck.class) { java.util.Arrays.fill(gyro, (short) 0); }
            publish();
        });
        t.quitSafely();
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (!active) return;
        float[] v = event.values;
        float x, y;
        switch (rotation.getAsInt()) {
            case Surface.ROTATION_90: x = -v[1]; y = v[0]; break;
            case Surface.ROTATION_180: x = -v[0]; y = -v[1]; break;
            case Surface.ROTATION_270: x = v[1]; y = -v[0]; break;
            default: x = v[0]; y = v[1]; break;
        }
        float z = v[2];
        short[] target;
        float scale;
        if (event.sensor.getType() == Sensor.TYPE_GYROSCOPE) {
            target = gyro; scale = (32768f / 2000f) * (180f / (float) Math.PI);
        } else if (event.sensor.getType() == Sensor.TYPE_ACCELEROMETER) {
            target = accel; scale = 16384f / SensorManager.GRAVITY_EARTH;
        } else return;
        short gyroPitch = 0, gyroYaw = 0;
        synchronized (DDDeck.class) {   // bingkai layar (kanan, atas, ke pemain) -> Deck (kanan, menjauh, atas)
            target[0] = counts(x * scale);
            target[1] = counts(-z * scale);
            target[2] = counts(y * scale);
            if (target == gyro) { gyroPitch = gyro[0]; gyroYaw = gyro[2]; }
        }
        publish();
        if (target == gyro) DDSteamClient.onGyro(gyroPitch, gyroYaw, event.timestamp);  // DroidDeck-steam
    }

    @Override public void onAccuracyChanged(Sensor sensor, int accuracy) {}

    private static short counts(float v) { return (short) Math.max(-32768, Math.min(32767, Math.round(v))); }
}

package com.winlator.cmod.core;

import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * XInput/DInput lama Winlator (seperti experimental-v2): DLL xinput*, dinput*, windows.gaming.input milik Winlator
 * membaca gamepad lewat UDP ke WinHandler, tanpa winebus/HID Wine, sehingga tetap jalan walau services.exe dimatikan
 * (mode Aggressive). DLL ini menimpa DLL bawaan Wine di lib/wine, yang dipakai bersama semua container, jadi
 * file aslinya dicadangkan dan dipulihkan otomatis saat peluncuran bukan-Aggressive berikutnya.
 */
public final class LegacyInputDlls {
    private static final String TAG = "LegacyInputDlls";
    private static final String[] DIRS = {"aarch64-windows", "i386-windows", "x86_64-windows"};
    private static final String[] NAMES = {
            "xinput1_1.dll", "xinput1_2.dll", "xinput1_3.dll", "xinput1_4.dll", "xinput9_1_0.dll",
            "dinput.dll", "dinput8.dll", "windows.gaming.input.dll"
    };
    private static final String BACKUP_DIR = ".ludashi-input-orig";
    private static final String MARKER = "installed";
    private static final String ABSENT_SUFFIX = ".absent";

    /** Mengekstrak DLL Winlator ke folder lib/wine yang diberikan. */
    public interface Extractor {
        boolean extractTo(File wineLibDir);
    }

    private LegacyInputDlls() {}

    public static boolean isInstalled(File wineLibDir) {
        return new File(new File(wineLibDir, BACKUP_DIR), MARKER).isFile();
    }

    /**
     * enable=true: cadangkan file asli lalu pasang DLL Winlator (idempotent). enable=false: pulihkan file asli
     * (tidak berbuat apa-apa jika belum terpasang). Mengembalikan false jika gagal; pada kegagalan pemasangan
     * file asli otomatis dipulihkan.
     */
    public static boolean apply(File wineLibDir, boolean enable, Extractor extractor) {
        if (wineLibDir == null || !wineLibDir.isDirectory()) return !enable;
        try {
            if (!enable) {
                restore(wineLibDir);
                return true;
            }
            if (isInstalled(wineLibDir)) return true;
            backup(wineLibDir);
            if (extractor == null || !extractor.extractTo(wineLibDir)) {
                Log.w(TAG, "Gagal mengekstrak DLL input lama, memulihkan file asli");
                restore(wineLibDir);
                return false;
            }
            File marker = new File(new File(wineLibDir, BACKUP_DIR), MARKER);
            if (!marker.createNewFile() && !marker.isFile()) throw new IOException("marker");
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "apply(" + enable + ") gagal", t);
            try { if (enable) restore(wineLibDir); } catch (Throwable ignored) { }
            return false;
        }
    }

    private static void backup(File wineLibDir) throws IOException {
        File backupRoot = new File(wineLibDir, BACKUP_DIR);
        // Cadangan sebagian dari percobaan sebelumnya yang gagal tidak boleh dipakai: mulai bersih.
        deleteRecursive(backupRoot);
        for (String dir : DIRS) {
            for (String name : NAMES) {
                File original = new File(new File(wineLibDir, dir), name);
                File saved = new File(new File(backupRoot, dir), name);
                File parent = saved.getParentFile();
                if (!parent.isDirectory() && !parent.mkdirs()) throw new IOException("mkdirs " + parent);
                if (original.isFile()) {
                    copy(original, saved);
                } else if (!new File(saved.getPath() + ABSENT_SUFFIX).createNewFile()) {
                    throw new IOException("absent marker " + saved);
                }
            }
        }
    }

    private static void restore(File wineLibDir) throws IOException {
        File backupRoot = new File(wineLibDir, BACKUP_DIR);
        if (!backupRoot.isDirectory()) return;
        for (String dir : DIRS) {
            for (String name : NAMES) {
                File original = new File(new File(wineLibDir, dir), name);
                File saved = new File(new File(backupRoot, dir), name);
                if (saved.isFile()) {
                    copy(saved, original);
                } else if (new File(saved.getPath() + ABSENT_SUFFIX).isFile() && original.isFile()) {
                    if (!original.delete()) throw new IOException("delete " + original);
                }
                // tanpa cadangan dan tanpa penanda "absent": tidak diketahui, biarkan apa adanya
            }
        }
        deleteRecursive(backupRoot);
    }

    private static void copy(File from, File to) throws IOException {
        File tmp = new File(to.getPath() + ".tmp");
        try (InputStream in = new FileInputStream(from); OutputStream out = new FileOutputStream(tmp)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = in.read(buffer)) > 0) out.write(buffer, 0, read);
        }
        if (to.exists() && !to.delete()) throw new IOException("delete " + to);
        if (!tmp.renameTo(to)) throw new IOException("rename " + tmp);
    }

    private static void deleteRecursive(File file) {
        if (file == null || !file.exists()) return;
        File[] children = file.listFiles();
        if (children != null) for (File child : children) deleteRecursive(child);
        file.delete();
    }
}

package com.winlator.cmod.core;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Menimpa winebus.so / setupapi.dll / cfgmgr32.dll / hidclass.sys milik Proton yang baru dipasang dengan hasil build
 * repo winebus-test (dukungan Steam Deck Pad murni hidraw). Paket patch ada di assets/steam-input-patch/proton-<mayor>.tar.zst
 * dan dipilih dari angka versi mayor Proton, jadi 10.0-4 dan 10.0-5 memakai berkas yang sama (proton-10.tar.zst).
 * Hanya Proton arm64ec yang dipatch karena build winebus-test memang untuk arm64ec.
 */
public abstract class SteamInputPatch {
    private static final String TAG = "SteamInputPatch";
    public static final String ASSET_DIR = "steam-input-patch";
    private static final Pattern MAJOR = Pattern.compile("(?<![0-9.])(\\d{1,3})\\.\\d+");

    /** Nama aset patch untuk label versi seperti "proton-9.0-arm64ec", "10.0-5-arm64ec", "Proton-11.0-2-arm64ec-3"; null bila tidak berlaku. */
    public static String assetNameFor(String versionLabel) {
        if (versionLabel == null) return null;
        String label = versionLabel.toLowerCase(Locale.ROOT);
        if (!label.contains("arm64ec")) return null;
        Matcher matcher = MAJOR.matcher(label);
        if (!matcher.find()) return null;
        return "proton-" + matcher.group(1) + ".tar.zst";
    }

    /** Pasang patch ke akar instalasi Proton dengan lib di "lib" (default /opt/proton-9.0-arm64ec). */
    public static boolean applyToWineDir(Context context, File baseDir, String versionLabel) {
        return applyToWineDir(context, baseDir, versionLabel, "lib");
    }

    /**
     * @param baseDir      akar Proton: /opt/<id> di imagefs, atau folder contents (sebelum/ sesudah dipindah ke contents/Proton/<versi>-<urutan>)
     * @param versionLabel id paket atau verName contents; menentukan paket patch yang dipakai
     * @param libRel       folder lib relatif terhadap baseDir (profile.wineLibPath pada contents)
     * @return true bila patch terpasang; false bila tidak berlaku/gagal. Kegagalan tidak boleh menggagalkan instalasi Proton.
     */
    public static boolean applyToWineDir(Context context, File baseDir, String versionLabel, String libRel) {
        String assetName = assetNameFor(versionLabel);
        if (assetName == null) return false;
        if (baseDir == null || !baseDir.isDirectory()) return false;
        if (libRel == null || libRel.trim().isEmpty()) libRel = "lib";

        try {
            String[] available = context.getAssets().list(ASSET_DIR);
            if (available == null || !Arrays.asList(available).contains(assetName)) {
                Log.i(TAG, "Tidak ada " + ASSET_DIR + "/" + assetName + " untuk " + versionLabel + ", dilewati");
                return false;
            }
        }
        catch (Exception e) {
            Log.w(TAG, "Gagal membaca daftar aset " + ASSET_DIR, e);
            return false;
        }

        File tmp = new File(context.getCacheDir(), "steam-input-patch-" + System.nanoTime());
        try {
            if (!tmp.mkdirs() || !TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, context, ASSET_DIR + "/" + assetName, tmp)) {
                Log.e(TAG, "Gagal mengekstrak " + assetName);
                return false;
            }
            File libWine = new File(baseDir, libRel + "/wine");
            int count = merge(tmp, baseDir, libRel, libWine, 0);
            if (count <= 0) {
                Log.e(TAG, "Isi " + assetName + " tidak cocok dengan tata letak Proton di " + baseDir);
                return false;
            }
            Log.i(TAG, assetName + " -> " + baseDir.getPath() + " (" + count + " berkas, " + versionLabel + ")");
            return true;
        }
        catch (Exception e) {
            Log.e(TAG, "Gagal memasang " + assetName + " ke " + baseDir, e);
            return false;
        }
        finally {
            FileUtils.delete(tmp);
        }
    }

    private static boolean isArchDir(File file) {
        String name = file.getName();
        return file.isDirectory() && (name.endsWith("-unix") || name.endsWith("-windows"));
    }

    /** Kembalikan jumlah berkas yang ditulis. */
    private static int merge(File payload, File baseDir, String libRel, File libWine, int depth) {
        File[] top = payload.listFiles();
        if (top == null || top.length == 0) return 0;

        File libDir = new File(payload, libRel);
        File wineDir = new File(payload, "wine");
        boolean hasArch = false;
        for (File file : top) if (isArchDir(file)) { hasArch = true; break; }

        if (libDir.isDirectory()) return copyTree(payload, baseDir);          // lib/wine/<arch>/...
        if (wineDir.isDirectory() && !hasArch) return copyTree(wineDir, libWine);  // wine/<arch>/...
        if (hasArch) return copyTree(payload, libWine);                        // <arch>/...

        // bungkus satu folder (mis. proton-9/...): turun satu tingkat
        if (depth == 0 && top.length == 1 && top[0].isDirectory()) return merge(top[0], baseDir, libRel, libWine, 1);

        // berkas datar: timpa berkas bernama sama yang sudah ada di lib/wine
        return replaceByName(payload, libWine);
    }

    private static int copyTree(File src, File dst) {
        int count = 0;
        File[] children = src.listFiles();
        if (children == null) return 0;
        for (File child : children) {
            File target = new File(dst, child.getName());
            if (FileUtils.isSymlink(child)) continue;
            if (child.isDirectory()) count += copyTree(child, target);
            else if (overwrite(child, target)) count++;
        }
        return count;
    }

    private static boolean overwrite(File src, File dst) {
        File parent = dst.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) return false;
        if ((dst.exists() || FileUtils.isSymlink(dst)) && !FileUtils.delete(dst)) return false;  // lepas symlink/berkas lama dulu
        if (!FileUtils.copy(src, dst) || !dst.isFile()) return false;
        FileUtils.chmod(dst, 0755);
        return true;
    }

    private static int replaceByName(File payload, File libWine) {
        List<File> flat = new ArrayList<>();
        collectFiles(payload, flat);
        int count = 0;
        for (File src : flat) {
            List<File> existing = new ArrayList<>();
            findByName(libWine, src.getName(), existing);
            for (File dst : existing) if (overwrite(src, dst)) count++;
            if (existing.isEmpty()) Log.w(TAG, src.getName() + " tidak ada di " + libWine + ", dilewati");
        }
        return count;
    }

    private static void collectFiles(File dir, List<File> out) {
        File[] children = dir.listFiles();
        if (children == null) return;
        for (File child : children) {
            if (child.isDirectory()) collectFiles(child, out);
            else if (child.isFile()) out.add(child);
        }
    }

    private static void findByName(File dir, String name, List<File> out) {
        File[] children = dir.listFiles();
        if (children == null) return;
        for (File child : children) {
            if (FileUtils.isSymlink(child)) continue;
            if (child.isDirectory()) findByName(child, name, out);
            else if (child.getName().equals(name)) out.add(child);
        }
    }
}

package com.winlator.cmod.reshade;

import android.content.Context;

import java.io.File;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Scans for drop-in ReShade (.fx) effect folders and exposes them so a UI or
 * ReshadeConfigWriter can turn a selection into a vkBasalt config.
 * <p>
 * Layout on disk (mirrors the convention used by other ReShade/vkBasalt front-ends):
 * <pre>
 * Android/data/&lt;package&gt;/files/ReShade/&lt;EffectName&gt;/EffectName.fx
 * Android/data/&lt;package&gt;/files/ReShade/&lt;EffectName&gt;/*.fxh   (optional includes)
 * Android/data/&lt;package&gt;/files/ReShade/&lt;EffectName&gt;/Textures/*  (optional textures)
 * </pre>
 * Each effect is self-contained in its own folder so its .fxh includes and
 * textures travel with it. The bundled libvkbasalt.so already embeds the
 * ReShade FX compiler (confirmed via reshadefx/ReshadeEffect symbols) and
 * reads reshadeTexturePath / reshadeIncludePath from the config file that
 * ReshadeConfigWriter produces.
 */
public class ReshadeManager {
    private static final String RESHADE_DIR_NAME = "ReShade";

    public static class ReshadeEffect {
        public final String name;
        public final File folder;
        public final File fxFile;

        public ReshadeEffect(String name, File folder, File fxFile) {
            this.name = name;
            this.folder = folder;
            this.fxFile = fxFile;
        }

        /** Folder used for both reshadeIncludePath and reshadeTexturePath (self-contained layout). */
        public File getIncludeDir() {
            return folder;
        }

        public File getTextureDir() {
            File textures = new File(folder, "Textures");
            return textures.isDirectory() ? textures : folder;
        }
    }

    /** Root folder under app-external-files where drop-in effects live. Created if missing. */
    public static File getReshadeRootDir(Context context) {
        File base = context.getExternalFilesDir(null);
        File root = new File(base, RESHADE_DIR_NAME);
        if (!root.exists()) {
            //noinspection ResultOfMethodCallIgnored
            root.mkdirs();
        }
        return root;
    }

    /**
     * Scans the drop-in folder and returns every effect that has at least one
     * .fx file directly inside its own subfolder. Effects are sorted by name.
     */
    public static List<ReshadeEffect> scanEffects(Context context) {
        List<ReshadeEffect> effects = new ArrayList<>();
        File root = getReshadeRootDir(context);
        File[] subDirs = root.listFiles();
        if (subDirs == null) return effects;

        for (File dir : subDirs) {
            if (!dir.isDirectory()) continue;
            File fxFile = findPrimaryFxFile(dir);
            if (fxFile == null) continue;
            effects.add(new ReshadeEffect(dir.getName(), dir, fxFile));
        }

        effects.sort(Comparator.comparing(e -> e.name.toLowerCase()));
        return effects;
    }

    public static ReshadeEffect findByName(Context context, String name) {
        if (name == null || name.isEmpty() || name.equals("None")) return null;
        for (ReshadeEffect effect : scanEffects(context)) {
            if (effect.name.equals(name)) return effect;
        }
        return null;
    }

    private static File findPrimaryFxFile(File dir) {
        File[] files = dir.listFiles((d, fileName) -> fileName.toLowerCase().endsWith(".fx"));
        if (files == null || files.length == 0) return null;

        // Prefer a .fx file that matches the folder name (EffectName/EffectName.fx),
        // fall back to the first .fx file found otherwise.
        for (File f : files) {
            String base = f.getName().substring(0, f.getName().length() - 3);
            if (base.equalsIgnoreCase(dir.getName())) return f;
        }
        return files[0];
    }
}

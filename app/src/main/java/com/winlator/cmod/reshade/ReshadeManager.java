package com.winlator.cmod.reshade;

import android.content.Context;

import java.io.File;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Scans for drop-in ReShade (.fx) effects and exposes them so a UI or
 * ReshadeConfigWriter can turn a selection into a vkBasalt config.
 * <p>
 * Layout on disk (matches the OFFICIAL ReShade installer convention -- a
 * single shared Shaders/ and Textures/ folder, NOT one isolated folder per
 * effect):
 * <pre>
 * Android/data/&lt;package&gt;/files/ReShade/Shaders/EffectName.fx
 * Android/data/&lt;package&gt;/files/ReShade/Shaders/SomeInclude.fxh
 * Android/data/&lt;package&gt;/files/ReShade/Shaders/Sub/Nested.fxh
 * Android/data/&lt;package&gt;/files/ReShade/Textures/texture.png
 * </pre>
 * This is REQUIRED (not just a style choice) for multiple active effects to
 * work together: vkBasalt only accepts a single reshadeIncludePath and a
 * single reshadeTexturePath value each -- it does not search multiple
 * colon-joined paths (confirmed from an actual crash log: joining paths
 * with ':' made it treat the whole joined string as one literal path and
 * fail to open textures, e.g. "couldn't open texture: .../A:/.../B/tex.png").
 * So every downloaded effect's files must live under the SAME two folders.
 */
public class ReshadeManager {
    private static final String RESHADE_DIR_NAME = "ReShade";
    private static final String SHADERS_DIR_NAME = "Shaders";
    private static final String TEXTURES_DIR_NAME = "Textures";

    public static class ReshadeEffect {
        public final String name;
        public final File fxFile;

        public ReshadeEffect(String name, File fxFile) {
            this.name = name;
            this.fxFile = fxFile;
        }
    }

    /** Root folder under app-external-files where drop-in effects live. Created if missing. */
    public static File getReshadeRootDir(Context context) {
        File base = context.getExternalFilesDir(null);
        File root = new File(base, RESHADE_DIR_NAME);
        if (!root.exists()) //noinspection ResultOfMethodCallIgnored
            root.mkdirs();
        return root;
    }

    /** Shared folder for every effect's .fx and .fxh files. Always used as reshadeIncludePath. */
    public static File getShadersDir(Context context) {
        File dir = new File(getReshadeRootDir(context), SHADERS_DIR_NAME);
        if (!dir.exists()) //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
        return dir;
    }

    /** Shared folder for every effect's textures. Always used as reshadeTexturePath. */
    public static File getTexturesDir(Context context) {
        File dir = new File(getReshadeRootDir(context), TEXTURES_DIR_NAME);
        if (!dir.exists()) //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
        return dir;
    }

    /**
     * Scans the shared Shaders/ folder and returns every top-level .fx file
     * found there (subfolders hold only include dependencies, not
     * standalone selectable effects). Sorted by name.
     */
    public static List<ReshadeEffect> scanEffects(Context context) {
        List<ReshadeEffect> effects = new ArrayList<>();
        File[] files = getShadersDir(context).listFiles(
                (dir, name) -> name.toLowerCase().endsWith(".fx"));
        if (files == null) return effects;

        for (File f : files) {
            String name = f.getName().substring(0, f.getName().length() - 3);
            effects.add(new ReshadeEffect(name, f));
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

    /**
     * Deletes just the top-level .fx file for this effect. Shared includes
     * and textures under Shaders/Textures are intentionally left alone --
     * they're small and may still be used by other downloaded effects, so
     * it isn't safe to guess whether they're now orphaned.
     */
    public static boolean deleteEffect(Context context, String name) {
        if (name == null || name.isEmpty()) return false;
        File fx = new File(getShadersDir(context), name + ".fx");
        return !fx.exists() || fx.delete();
    }
}

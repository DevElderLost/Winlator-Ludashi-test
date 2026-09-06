package com.winlator.cmod.reshade;

import android.content.Context;

import com.winlator.cmod.container.Container;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Builds a real multi-line vkBasalt.conf file (not the single-line
 * "VKBASALT_CONFIG" env var format) and writes it under the container's own
 * rootfs, so it is visible to the guest process the same way WINEPREFIX and
 * other container-relative paths already are in XServerDisplayActivity.
 * <p>
 * Combines the existing built-in sharpen (CAS/DLS) with ANY NUMBER of
 * drop-in .fx effects (multi-select, all active ones chained together):
 * <pre>
 * effects = cas:myeffect1:myeffect2
 * casSharpness = 0.80
 * myeffect1 = /abs/path/Effect1/Effect1.fx
 * myeffect2 = /abs/path/Effect2/Effect2.fx
 * reshadeIncludePath = /abs/path/Effect1:/abs/path/Effect2
 * reshadeTexturePath = /abs/path/Effect1/Textures:/abs/path/Effect2
 * enableOnLaunch = true
 * </pre>
 * NOTE on the colon-joined reshadeIncludePath/reshadeTexturePath: each
 * downloaded effect is self-contained in its own folder (its own resolved
 * copy of ReShade.fxh, its own Textures/), so combining several active
 * effects needs the compiler to search across ALL of their folders at once.
 * This assumes the bundled libvkbasalt.so accepts multiple colon-joined
 * search paths for these two keys (already confirmed to be a custom-patched
 * build rather than vanilla vkBasalt, since it also supports the inline
 * VKBASALT_CONFIG env var vanilla vkBasalt doesn't have). If it turns out
 * this specific build only honors a single path, only the first listed
 * effect's includes/textures would resolve -- other active effects may
 * then fail to compile individually without affecting the rest of the chain.
 */
public class ReshadeConfigWriter {
    /**
     * Shortcut/container extra key holding the set of ENABLED drop-in .fx
     * effect names (folder names), comma-separated. Empty/missing = none
     * active. Replaces the old single-select "reshadeFxEffect" key.
     */
    public static final String EXTRA_FX_EFFECTS = "reshadeFxEffects";

    /**
     * @deprecated kept only so the legacy (unused) ShortcutSettingsDialog.java
     * dialog still compiles. The live UI (ShortcutEditorV2) uses
     * {@link #EXTRA_FX_EFFECTS} (multi-select) instead.
     */
    @Deprecated
    public static final String EXTRA_FX_EFFECT = "reshadeFxEffect";

    private static final String CONFIG_DIR = ".wine/drive_c/winlator/vkbasalt";
    private static final String CONFIG_FILE_NAME = "vkBasalt.conf";

    public static class BuiltConfig {
        public final String content;
        public final boolean isEmpty;

        BuiltConfig(String content, boolean isEmpty) {
            this.content = content;
            this.isEmpty = isEmpty;
        }
    }

    /** Parses the comma-separated extra value into a mutable ordered set of names. */
    public static Set<String> parseEnabledNames(String extraValue) {
        Set<String> names = new java.util.LinkedHashSet<>();
        if (extraValue == null || extraValue.trim().isEmpty()) return names;
        for (String part : extraValue.split(",")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) names.add(trimmed);
        }
        return names;
    }

    public static String joinEnabledNames(Set<String> names) {
        return String.join(",", names);
    }

    /**
     * @param sharpnessEffect "None", "CAS" or "DLS" (existing built-in vkBasalt effects)
     * @param sharpnessLevel  0-100
     * @param sharpnessDenoise 0-100 (only meaningful for DLS)
     * @param fxEffects       every ENABLED drop-in .fx effect (multi-select), may be empty
     */
    public static BuiltConfig buildConfig(
            String sharpnessEffect,
            double sharpnessLevel,
            double sharpnessDenoise,
            List<ReshadeManager.ReshadeEffect> fxEffects
    ) {
        boolean hasBuiltIn = sharpnessEffect != null && !sharpnessEffect.equalsIgnoreCase("None");
        boolean hasFxEffects = fxEffects != null && !fxEffects.isEmpty();

        if (!hasBuiltIn && !hasFxEffects) {
            return new BuiltConfig("", true);
        }

        List<String> chain = new ArrayList<>();
        StringBuilder body = new StringBuilder();

        if (hasBuiltIn) {
            String key = sharpnessEffect.toLowerCase(Locale.ROOT);
            chain.add(key);
            body.append("casSharpness = ").append(fmt(sharpnessLevel / 100.0)).append('\n');
            body.append("dlsSharpness = ").append(fmt(sharpnessLevel / 100.0)).append('\n');
            body.append("dlsDenoise = ").append(fmt(sharpnessDenoise / 100.0)).append('\n');
        }

        if (hasFxEffects) {
            Set<String> usedKeys = new HashSet<>();
            List<String> includeDirs = new ArrayList<>();
            List<String> textureDirs = new ArrayList<>();

            for (ReshadeManager.ReshadeEffect fx : fxEffects) {
                String key = uniqueKey(sanitizeKey(fx.name), usedKeys);
                usedKeys.add(key);
                chain.add(key);
                body.append(key).append(" = ").append(fx.fxFile.getAbsolutePath()).append('\n');
                includeDirs.add(fx.getIncludeDir().getAbsolutePath());
                textureDirs.add(fx.getTextureDir().getAbsolutePath());
            }

            body.append("reshadeIncludePath = ").append(String.join(":", includeDirs)).append('\n');
            body.append("reshadeTexturePath = ").append(String.join(":", textureDirs)).append('\n');
        }

        StringBuilder out = new StringBuilder();
        out.append("effects = ").append(String.join(":", chain)).append('\n');
        out.append(body);
        out.append("enableOnLaunch = true\n");

        return new BuiltConfig(out.toString(), false);
    }

    /**
     * Writes the config under the container's own rootfs (same base used
     * elsewhere for .wine/drive_c/winlator paths) and returns the resulting
     * file, or null if the config was empty (caller should skip setting
     * ENABLE_VKBASALT/VKBASALT_CONFIG_FILE in that case).
     */
    public static File writeConfigFile(Context context, Container container, BuiltConfig config) {
        if (config.isEmpty) return null;

        File dir = new File(container.getRootDir(), CONFIG_DIR);
        if (!dir.exists() && !dir.mkdirs()) return null;

        File file = new File(dir, CONFIG_FILE_NAME);
        try (FileOutputStream fos = new FileOutputStream(file);
             OutputStreamWriter writer = new OutputStreamWriter(fos, StandardCharsets.UTF_8)) {
            writer.write(config.content);
        } catch (IOException e) {
            return null;
        }
        return file;
    }

    private static String fmt(double value) {
        return String.format(Locale.ROOT, "%.3f", value);
    }

    private static String uniqueKey(String base, Set<String> used) {
        if (!used.contains(base)) return base;
        int i = 2;
        while (used.contains(base + i)) i++;
        return base + i;
    }

    /** vkBasalt effect keys must be simple identifiers; strip anything that isn't alnum/underscore. */
    private static String sanitizeKey(String name) {
        String key = name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_]", "");
        if (key.isEmpty()) key = "fx";
        // Avoid clashing with vkBasalt's built-in effect names.
        if (key.equals("cas") || key.equals("dls") || key.equals("fxaa") || key.equals("smaa") || key.equals("lut")) {
            key = "fx_" + key;
        }
        return key;
    }
}

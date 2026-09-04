package com.winlator.cmod.reshade;

import android.content.Context;

import com.winlator.cmod.container.Container;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Builds a real multi-line vkBasalt.conf file (not the single-line
 * "VKBASALT_CONFIG" env var format) and writes it under the container's own
 * rootfs, so it is visible to the guest process the same way WINEPREFIX and
 * other container-relative paths already are in XServerDisplayActivity.
 * <p>
 * Combines two independent sources into one effect chain so the existing
 * built-in sharpen (CAS/DLS) and a custom drop-in .fx effect can run
 * together:
 * <pre>
 * effects = cas:myeffect
 * casSharpness = 0.80
 * myeffect = /abs/path/Effect.fx
 * reshadeTexturePath = /abs/path/Textures
 * reshadeIncludePath = /abs/path
 * enableOnLaunch = true
 * </pre>
 */
public class ReshadeConfigWriter {
    /** Shortcut extra key holding the selected drop-in .fx effect name (folder name), or "None". */
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

    /**
     * @param sharpnessEffect "None", "CAS" or "DLS" (existing built-in vkBasalt effects)
     * @param sharpnessLevel  0-100
     * @param sharpnessDenoise 0-100 (only meaningful for DLS)
     * @param fxEffect        a drop-in .fx effect resolved via ReshadeManager, or null
     */
    public static BuiltConfig buildConfig(
            String sharpnessEffect,
            double sharpnessLevel,
            double sharpnessDenoise,
            ReshadeManager.ReshadeEffect fxEffect
    ) {
        boolean hasBuiltIn = sharpnessEffect != null && !sharpnessEffect.equalsIgnoreCase("None");
        boolean hasFxEffect = fxEffect != null;

        if (!hasBuiltIn && !hasFxEffect) {
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

        if (hasFxEffect) {
            String key = sanitizeKey(fxEffect.name);
            chain.add(key);
            body.append(key).append(" = ").append(fxEffect.fxFile.getAbsolutePath()).append('\n');
            body.append("reshadeIncludePath = ").append(fxEffect.getIncludeDir().getAbsolutePath()).append('\n');
            body.append("reshadeTexturePath = ").append(fxEffect.getTextureDir().getAbsolutePath()).append('\n');
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

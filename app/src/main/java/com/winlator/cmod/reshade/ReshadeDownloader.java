package com.winlator.cmod.reshade;

import android.content.Context;

import com.winlator.cmod.contents.Downloader;

import java.io.File;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Downloads a single catalog effect into ReshadeManager's self-contained
 * per-effect folder layout:
 * <pre>
 *   ReShade/&lt;EffectName&gt;/EffectName.fx
 *   ReShade/&lt;EffectName&gt;/SomeInclude.fxh   (resolved recursively from #include)
 *   ReShade/&lt;EffectName&gt;/Textures/foo.png  (resolved from texture "source" annotations)
 * </pre>
 * Rather than mirroring the entire upstream Shaders/Textures folders (which
 * would pull down hundreds of unrelated files), this only fetches the .fx
 * file plus whatever it actually references -- resolved by parsing the
 * downloaded text, which keeps things bandwidth-friendly on mobile.
 * <p>
 * Blocking network I/O -- run off the main thread.
 */
public class ReshadeDownloader {
    private static final Pattern INCLUDE_PATTERN =
            Pattern.compile("#include\\s+\"([^\"]+)\"");
    private static final Pattern TEXTURE_SOURCE_PATTERN =
            Pattern.compile("source\\s*=\\s*\"([^\"]+)\"");

    public static class Result {
        public final boolean success;
        public final String effectName;

        Result(boolean success, String effectName) {
            this.success = success;
            this.effectName = effectName;
        }
    }

    /**
     * @param entry catalog entry picked by the user (from ReshadeCatalog.fetchCatalog())
     */
    public static Result downloadEffect(Context context, ReshadeCatalog.CatalogEntry entry) {
        File root = ReshadeManager.getReshadeRootDir(context);
        File effectDir = new File(root, entry.displayName);
        if (!effectDir.exists() && !effectDir.mkdirs()) {
            return new Result(false, entry.displayName);
        }

        File fxFile = new File(effectDir, entry.fileName);
        if (!Downloader.downloadFile(ReshadeCatalog.RAW_SHADERS_BASE + entry.fileName, fxFile)) {
            return new Result(false, entry.displayName);
        }

        // Resolve #include "X.fxh" recursively (breadth-first, dedup by file name).
        Set<String> resolved = new HashSet<>();
        resolved.add(entry.fileName);
        ArrayDeque<File> queue = new ArrayDeque<>();
        queue.add(fxFile);

        while (!queue.isEmpty()) {
            File current = queue.poll();
            String text = readFile(current);
            if (text == null) continue;

            for (String includeName : findIncludes(text)) {
                if (!resolved.add(includeName)) continue; // already handled
                File includeFile = new File(effectDir, includeName);
                if (Downloader.downloadFile(ReshadeCatalog.RAW_SHADERS_BASE + includeName, includeFile)) {
                    queue.add(includeFile);
                }
                // If an include fails to download, keep going -- some effects
                // reference optional/platform-specific includes.
            }

            for (String textureName : findTextures(text)) {
                File texturesDir = new File(effectDir, "Textures");
                if (!texturesDir.exists()) //noinspection ResultOfMethodCallIgnored
                    texturesDir.mkdirs();
                File textureFile = new File(texturesDir, textureName);
                if (!textureFile.exists()) {
                    Downloader.downloadFile(ReshadeCatalog.RAW_TEXTURES_BASE + textureName, textureFile);
                    // Best-effort: a missing/failed texture shouldn't block the whole effect.
                }
            }
        }

        return new Result(true, entry.displayName);
    }

    private static Set<String> findIncludes(String text) {
        Set<String> includes = new HashSet<>();
        Matcher m = INCLUDE_PATTERN.matcher(text);
        while (m.find()) {
            String name = m.group(1);
            // Only resolve bare filenames (same-folder includes). Relative paths like
            // "../Shared/x.fxh" are skipped since our per-effect folder is flat.
            if (name != null && !name.contains("/") && !name.contains("\\")) {
                includes.add(name);
            }
        }
        return includes;
    }

    private static Set<String> findTextures(String text) {
        Set<String> textures = new HashSet<>();
        Matcher m = TEXTURE_SOURCE_PATTERN.matcher(text);
        while (m.find()) {
            String name = m.group(1);
            if (name != null && !name.contains("/") && !name.contains("\\")) {
                textures.add(name);
            }
        }
        return textures;
    }

    private static String readFile(File file) {
        try {
            byte[] data = java.nio.file.Files.readAllBytes(file.toPath());
            return new String(data, java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }
}

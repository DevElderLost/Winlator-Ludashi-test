package com.winlator.cmod.reshade;

import android.content.Context;

import com.winlator.cmod.contents.Downloader;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Downloads a single catalog effect (picked from ReshadeCatalog, which now
 * covers ~40 independent repos via EffectPackages.ini) into ReshadeManager's
 * self-contained per-effect folder layout:
 * <pre>
 *   ReShade/&lt;EffectName&gt;/EffectName.fx
 *   ReShade/&lt;EffectName&gt;/SomeInclude.fxh
 *   ReShade/&lt;EffectName&gt;/Textures/foo.png
 * </pre>
 * Because every package lives in a different repo with its own internal
 * folder layout (InstallPath in EffectPackages.ini describes where the
 * OFFICIAL installer places files locally, not necessarily the source
 * repo's actual internal structure), file locations aren't guessed --
 * instead the repo's full recursive file tree is fetched once (one GitHub
 * API call) and used to resolve any filename to its real path, wherever it
 * happens to live in that repo.
 * <p>
 * Blocking network I/O -- run off the main thread.
 */
public class ReshadeDownloader {
    private static final Pattern INCLUDE_PATTERN = Pattern.compile("#include\\s+\"([^\"/\\\\]+)\"");
    private static final Pattern TEXTURE_SOURCE_PATTERN = Pattern.compile("source\\s*=\\s*\"([^\"/\\\\]+)\"");

    public static class Result {
        public final boolean success;
        public final String effectName;

        Result(boolean success, String effectName) {
            this.success = success;
            this.effectName = effectName;
        }
    }

    public static Result downloadEffect(Context context, ReshadeCatalog.CatalogEntry entry) {
        String effectName = entry.effectName();
        Map<String, String> tree = fetchRepoTree(entry.pkg);
        if (tree.isEmpty()) return new Result(false, effectName);

        String mainPath = tree.get(entry.fileName.toLowerCase());
        if (mainPath == null) return new Result(false, effectName);

        File effectDir = new File(ReshadeManager.getReshadeRootDir(context), effectName);
        if (!effectDir.exists() && !effectDir.mkdirs()) return new Result(false, effectName);

        File fxFile = new File(effectDir, entry.fileName);
        if (!downloadRawFile(entry.pkg, mainPath, fxFile)) return new Result(false, effectName);

        Set<String> resolved = new HashSet<>();
        resolved.add(entry.fileName.toLowerCase());
        ArrayDeque<File> queue = new ArrayDeque<>();
        queue.add(fxFile);

        while (!queue.isEmpty()) {
            File current = queue.poll();
            String text = readFile(current);
            if (text == null) continue;

            for (String includeName : findMatches(text, INCLUDE_PATTERN)) {
                String key = includeName.toLowerCase();
                if (!resolved.add(key)) continue;
                String path = tree.get(key);
                if (path == null) continue; // optional/platform-specific include, skip silently
                File includeFile = new File(effectDir, includeName);
                if (downloadRawFile(entry.pkg, path, includeFile)) {
                    queue.add(includeFile);
                }
            }

            for (String textureName : findMatches(text, TEXTURE_SOURCE_PATTERN)) {
                String key = textureName.toLowerCase();
                String path = tree.get(key);
                if (path == null) continue; // best-effort; missing texture shouldn't block the effect
                File texturesDir = new File(effectDir, "Textures");
                if (!texturesDir.exists()) //noinspection ResultOfMethodCallIgnored
                    texturesDir.mkdirs();
                File textureFile = new File(texturesDir, textureName);
                if (!textureFile.exists()) {
                    downloadRawFile(entry.pkg, path, textureFile);
                }
            }
        }

        return new Result(true, effectName);
    }

    /**
     * Fetches the full recursive file tree of a package's repo (one API
     * call) and returns a map of lowercased-basename -> repo-relative path.
     * If multiple files share a basename, the first one found wins.
     */
    private static Map<String, String> fetchRepoTree(ReshadeCatalog.Package pkg) {
        Map<String, String> map = new HashMap<>();
        String url = "https://api.github.com/repos/" + pkg.repoOwner + "/" + pkg.repoName
                + "/git/trees/" + pkg.repoBranch + "?recursive=1";
        String json = Downloader.downloadString(url);
        if (json == null) return map;

        try {
            JSONObject root = new JSONObject(json);
            JSONArray tree = root.optJSONArray("tree");
            if (tree == null) return map;

            for (int i = 0; i < tree.length(); i++) {
                JSONObject item = tree.getJSONObject(i);
                if (!"blob".equals(item.optString("type"))) continue;
                String path = item.optString("path", "");
                if (path.isEmpty()) continue;
                int slash = path.lastIndexOf('/');
                String basename = (slash >= 0 ? path.substring(slash + 1) : path).toLowerCase();
                map.putIfAbsent(basename, path);
            }
        } catch (Exception e) {
            e.printStackTrace();
            return new HashMap<>();
        }
        return map;
    }

    private static boolean downloadRawFile(ReshadeCatalog.Package pkg, String repoRelativePath, File dest) {
        String url = "https://raw.githubusercontent.com/" + pkg.repoOwner + "/" + pkg.repoName
                + "/" + pkg.repoBranch + "/" + repoRelativePath;
        return Downloader.downloadFile(url, dest);
    }

    private static Set<String> findMatches(String text, Pattern pattern) {
        Set<String> names = new HashSet<>();
        Matcher m = pattern.matcher(text);
        while (m.find()) {
            String name = m.group(1);
            if (name != null && !name.isEmpty()) names.add(name);
        }
        return names;
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

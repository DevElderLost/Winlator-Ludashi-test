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
 * Downloads a single catalog effect (picked from ReshadeCatalog, which
 * covers ~40 independent repos via EffectPackages.ini) into the SHARED
 * Shaders/ and Textures/ folders (see ReshadeManager) -- required so that
 * multiple active effects can all be found via the single reshadeIncludePath
 * / reshadeTexturePath value vkBasalt actually supports.
 * <p>
 * Two correctness fixes baked in here:
 * <p>
 * 1. #include / texture "source" references that contain a subfolder (e.g.
 *    "Shared/Blend.fxh") are saved at that SAME relative path under
 *    Shaders/ (or Textures/), because the shader compiler resolves an
 *    #include exactly as written, relative to reshadeIncludePath.
 * <p>
 * 2. Virtually every .fx effect starts with #include "ReShade.fxh" and
 *    #include "ReShadeUI.fxh" -- but those two files only exist in the base
 *    crosire/reshade-shaders repo, NOT inside each individual package's own
 *    repo (SweetFX, qUINT, prod80, etc. don't bundle copies of them). There's
 *    a fallback lookup against the base "slim" repo's tree whenever a file
 *    isn't found in the effect's own package.
 * <p>
 * Files already present under Shaders/Textures (e.g. ReShade.fxh downloaded
 * once for an earlier effect) are NOT re-downloaded, since the shared-folder
 * model means later effects reuse what earlier ones already fetched.
 * <p>
 * Blocking network I/O -- run off the main thread.
 */
public class ReshadeDownloader {
    private static final Pattern INCLUDE_PATTERN = Pattern.compile("#include\\s+\"([^\"]+)\"");
    private static final Pattern TEXTURE_SOURCE_PATTERN = Pattern.compile("source\\s*=\\s*\"([^\"]+)\"");

    private static final String BASE_REPO_OWNER = "crosire";
    private static final String BASE_REPO_NAME = "reshade-shaders";
    private static final String BASE_REPO_BRANCH = "slim";

    /** Cached once per process -- reused across every single-effect download in this session. */
    private static Map<String, String> baseRepoTreeCache;
    /** Cached per (owner/repo/branch) -- reused if multiple effects from the same package are downloaded. */
    private static final Map<String, Map<String, String>> packageTreeCache = new HashMap<>();

    public static class Result {
        public final boolean success;
        public final String effectName;

        Result(boolean success, String effectName) {
            this.success = success;
            this.effectName = effectName;
        }
    }

    /** Where a resolved file actually lives: which repo, and its path within that repo. */
    private static class Resolved {
        final ReshadeCatalog.Package repo;
        final String path;

        Resolved(ReshadeCatalog.Package repo, String path) {
            this.repo = repo;
            this.path = path;
        }
    }

    public static Result downloadEffect(Context context, ReshadeCatalog.CatalogEntry entry) {
        String effectName = entry.effectName();
        Map<String, String> ownTree = fetchRepoTreeCached(entry.pkg);
        if (ownTree.isEmpty()) return new Result(false, effectName);

        String mainPath = ownTree.get(basenameOf(entry.fileName).toLowerCase());
        if (mainPath == null) return new Result(false, effectName);

        File shadersDir = ReshadeManager.getShadersDir(context);
        File texturesDir = ReshadeManager.getTexturesDir(context);

        File fxFile = new File(shadersDir, entry.fileName);
        if (!downloadRawFile(entry.pkg, mainPath, fxFile)) return new Result(false, effectName);

        Set<String> resolved = new HashSet<>();
        resolved.add(normalizeKey(entry.fileName));
        ArrayDeque<File> queue = new ArrayDeque<>();
        queue.add(fxFile);

        while (!queue.isEmpty()) {
            File current = queue.poll();
            String text = readFile(current);
            if (text == null) continue;

            for (String includeRef : findMatches(text, INCLUDE_PATTERN)) {
                String key = normalizeKey(includeRef);
                if (!resolved.add(key)) continue;

                File includeFile = new File(shadersDir, includeRef.replace('\\', '/'));
                if (includeFile.exists()) {
                    // Already fetched by an earlier effect sharing this include -- reuse it,
                    // but still scan it for further nested includes/textures.
                    queue.add(includeFile);
                    continue;
                }

                Resolved found = resolveFile(entry.pkg, ownTree, includeRef);
                if (found == null) continue; // optional/platform-specific include, skip silently

                File parent = includeFile.getParentFile();
                if (parent != null && !parent.exists()) //noinspection ResultOfMethodCallIgnored
                    parent.mkdirs();
                if (downloadRawFile(found.repo, found.path, includeFile)) {
                    queue.add(includeFile);
                }
            }

            for (String textureRef : findMatches(text, TEXTURE_SOURCE_PATTERN)) {
                File textureFile = new File(texturesDir, textureRef.replace('\\', '/'));
                if (textureFile.exists()) continue; // already fetched for an earlier effect

                Resolved found = resolveFile(entry.pkg, ownTree, textureRef);
                if (found == null) continue; // best-effort; missing texture shouldn't block the effect

                File parent = textureFile.getParentFile();
                if (parent != null && !parent.exists()) //noinspection ResultOfMethodCallIgnored
                    parent.mkdirs();
                downloadRawFile(found.repo, found.path, textureFile);
            }
        }

        return new Result(true, effectName);
    }

    /**
     * Looks up a file (by its basename, as referenced in an #include or
     * texture "source" annotation) first in the effect's own package repo,
     * then falls back to the shared base "slim" repo -- since foundational
     * files like ReShade.fxh / ReShadeUI.fxh only live there.
     */
    private static Resolved resolveFile(ReshadeCatalog.Package ownPackage, Map<String, String> ownTree, String reference) {
        String basename = basenameOf(reference).toLowerCase();

        String ownPath = ownTree.get(basename);
        if (ownPath != null) return new Resolved(ownPackage, ownPath);

        Map<String, String> baseTree = fetchBaseRepoTreeCached();
        String basePath = baseTree.get(basename);
        if (basePath != null) {
            ReshadeCatalog.Package baseRepo = new ReshadeCatalog.Package(
                    "ReShade base shaders", "", BASE_REPO_OWNER, BASE_REPO_NAME, BASE_REPO_BRANCH);
            return new Resolved(baseRepo, basePath);
        }

        return null;
    }

    private static Map<String, String> fetchBaseRepoTreeCached() {
        if (baseRepoTreeCache != null) return baseRepoTreeCache;
        ReshadeCatalog.Package baseRepo = new ReshadeCatalog.Package(
                "ReShade base shaders", "", BASE_REPO_OWNER, BASE_REPO_NAME, BASE_REPO_BRANCH);
        baseRepoTreeCache = fetchRepoTreeCached(baseRepo);
        return baseRepoTreeCache;
    }

    private static String cacheKey(ReshadeCatalog.Package pkg) {
        return pkg.repoOwner + "/" + pkg.repoName + "@" + pkg.repoBranch;
    }

    /**
     * Fetches the full recursive file tree of a package's repo (one API
     * call, cached per repo+branch for the life of the process) and returns
     * a map of lowercased-basename -> repo-relative path. If multiple files
     * share a basename, the first one found wins.
     */
    private static Map<String, String> fetchRepoTreeCached(ReshadeCatalog.Package pkg) {
        String key = cacheKey(pkg);
        Map<String, String> cached = packageTreeCache.get(key);
        if (cached != null) return cached;

        Map<String, String> map = new HashMap<>();
        String url = "https://api.github.com/repos/" + pkg.repoOwner + "/" + pkg.repoName
                + "/git/trees/" + pkg.repoBranch + "?recursive=1";
        String json = Downloader.downloadString(url);
        if (json == null) return map;

        try {
            JSONObject root = new JSONObject(json);
            JSONArray tree = root.optJSONArray("tree");
            if (tree != null) {
                for (int i = 0; i < tree.length(); i++) {
                    JSONObject item = tree.getJSONObject(i);
                    if (!"blob".equals(item.optString("type"))) continue;
                    String path = item.optString("path", "");
                    if (path.isEmpty()) continue;
                    map.putIfAbsent(basenameOf(path).toLowerCase(), path);
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
            return new HashMap<>();
        }

        packageTreeCache.put(key, map);
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

    private static String basenameOf(String path) {
        String normalized = path.replace('\\', '/');
        int slash = normalized.lastIndexOf('/');
        return slash >= 0 ? normalized.substring(slash + 1) : normalized;
    }

    /** Dedup key for the "already resolved" set -- normalized to forward slashes, lowercased. */
    private static String normalizeKey(String reference) {
        return reference.replace('\\', '/').toLowerCase();
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

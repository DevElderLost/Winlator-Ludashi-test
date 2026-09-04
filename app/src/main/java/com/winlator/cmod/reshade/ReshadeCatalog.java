package com.winlator.cmod.reshade;

import com.winlator.cmod.contents.Downloader;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Fetches the list of available ReShade .fx effects from the community's
 * canonical shader repository (crosire/reshade-shaders, branch "slim" --
 * verified to contain top-level Shaders/ and Textures/ folders, the same
 * layout every other ReShade front-end already relies on).
 * <p>
 * This is a THIN catalog: it only lists effect names. Actually downloading
 * an effect (plus its .fxh includes and textures) is handled by
 * ReshadeDownloader, kept separate so the catalog listing stays cheap/fast.
 * <p>
 * All methods here do blocking network I/O -- callers must run them off the
 * main thread (see ShortcutSettingsDialog's CONTENT_IO_EXECUTOR usage).
 */
public class ReshadeCatalog {
    private static final String REPO_OWNER = "crosire";
    private static final String REPO_NAME = "reshade-shaders";
    private static final String REPO_BRANCH = "slim";

    private static final String API_SHADERS_URL =
            "https://api.github.com/repos/" + REPO_OWNER + "/" + REPO_NAME + "/contents/Shaders?ref=" + REPO_BRANCH;

    public static final String RAW_SHADERS_BASE =
            "https://raw.githubusercontent.com/" + REPO_OWNER + "/" + REPO_NAME + "/" + REPO_BRANCH + "/Shaders/";
    public static final String RAW_TEXTURES_BASE =
            "https://raw.githubusercontent.com/" + REPO_OWNER + "/" + REPO_NAME + "/" + REPO_BRANCH + "/Textures/";

    public static class CatalogEntry {
        /** File name including extension, e.g. "Deband.fx" -- used to build the download URL. */
        public final String fileName;
        /** Display name without extension, e.g. "Deband". */
        public final String displayName;

        public CatalogEntry(String fileName) {
            this.fileName = fileName;
            this.displayName = fileName.substring(0, fileName.length() - 3);
        }
    }

    /**
     * @return every top-level .fx effect in the catalog, sorted by name, or
     * an empty list if the fetch failed (no network, rate-limited, etc).
     */
    public static List<CatalogEntry> fetchCatalog() {
        List<CatalogEntry> entries = new ArrayList<>();
        String json = Downloader.downloadString(API_SHADERS_URL);
        if (json == null) return entries;

        try {
            JSONArray items = new JSONArray(json);
            for (int i = 0; i < items.length(); i++) {
                JSONObject item = items.getJSONObject(i);
                String type = item.optString("type", "");
                String name = item.optString("name", "");
                if (!"file".equals(type)) continue;
                if (!name.toLowerCase().endsWith(".fx")) continue; // skip .fxh includes, only list standalone effects
                entries.add(new CatalogEntry(name));
            }
        } catch (Exception e) {
            e.printStackTrace();
            return new ArrayList<>();
        }

        Collections.sort(entries, (a, b) -> a.displayName.compareToIgnoreCase(b.displayName));
        return entries;
    }
}

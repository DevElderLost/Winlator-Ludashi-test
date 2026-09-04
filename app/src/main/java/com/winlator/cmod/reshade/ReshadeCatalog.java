package com.winlator.cmod.reshade;

import com.winlator.cmod.contents.Downloader;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Fetches and parses EffectPackages.ini -- the REAL official multi-package
 * catalog used by ReShade's own installer (crosire/reshade-shaders, branch
 * "list"). Unlike scanning a single repo's Shaders/ folder (which only
 * covers a handful of "slim" built-in effects), this covers ~40 independent
 * shader packages/repos and several hundred effects total.
 * <p>
 * Format (INI-like, one effect package per numbered section):
 * <pre>
 * [01]
 * Enabled=1
 * PackageName=SweetFX by CeeJay.dk
 * PackageDescription=...
 * DownloadUrl=https://github.com/CeeJayDK/SweetFX/archive/master.zip
 * RepositoryUrl=https://github.com/CeeJayDK/SweetFX
 * EffectFiles=ASCII.fx,Border.fx,CAS.fx,...
 * DenyEffectFiles=Template.fx
 * </pre>
 * All methods here do blocking network I/O -- callers must run them off the
 * main thread.
 */
public class ReshadeCatalog {
    private static final String PACKAGES_URL =
            "https://raw.githubusercontent.com/crosire/reshade-shaders/list/EffectPackages.ini";

    /** https://github.com/<owner>/<repo>/archive/(refs/heads/)?<branch>.zip */
    private static final Pattern DOWNLOAD_URL_PATTERN = Pattern.compile(
            "github\\.com/([^/]+)/([^/]+)/archive/(?:refs/heads/)?([^/]+)\\.zip");

    public static class Package {
        public final String name;
        public final String description;
        public final String repoOwner;
        public final String repoName;
        public final String repoBranch;

        public Package(String name, String description, String repoOwner, String repoName, String repoBranch) {
            this.name = name;
            this.description = description;
            this.repoOwner = repoOwner;
            this.repoName = repoName;
            this.repoBranch = repoBranch;
        }
    }

    public static class CatalogEntry {
        public final Package pkg;
        /** File name including extension, e.g. "CAS.fx". */
        public final String fileName;
        /** Shown in the picker list, includes the source package for disambiguation. */
        public final String displayName;

        public CatalogEntry(Package pkg, String fileName) {
            this.pkg = pkg;
            this.fileName = fileName;
            this.displayName = effectNameOf(fileName) + "  (" + pkg.name + ")";
        }

        /** Folder-safe effect name used by ReshadeManager / shortcut extras. */
        public String effectName() {
            return effectNameOf(fileName);
        }

        private static String effectNameOf(String fileName) {
            return fileName.toLowerCase().endsWith(".fx")
                    ? fileName.substring(0, fileName.length() - 3) : fileName;
        }
    }

    /**
     * @return every effect across every enabled package, sorted by display
     * name, or an empty list if the fetch/parse failed.
     */
    public static List<CatalogEntry> fetchCatalog() {
        String ini = Downloader.downloadString(PACKAGES_URL);
        if (ini == null) return new ArrayList<>();

        List<CatalogEntry> entries = new ArrayList<>();
        try {
            for (Map<String, String> section : splitSections(ini)) {
                if ("0".equals(section.get("Enabled"))) continue; // explicitly disabled package

                String name = section.get("PackageName");
                String downloadUrl = section.get("DownloadUrl");
                String effectFilesCsv = section.get("EffectFiles");
                if (name == null || downloadUrl == null || effectFilesCsv == null) continue;

                Matcher m = DOWNLOAD_URL_PATTERN.matcher(downloadUrl);
                if (!m.find()) continue;

                Package pkg = new Package(
                        name,
                        section.getOrDefault("PackageDescription", ""),
                        m.group(1),
                        m.group(2),
                        m.group(3)
                );

                Set<String> deny = new HashSet<>(parseCsvList(section.get("DenyEffectFiles")));
                for (String fileName : parseCsvList(effectFilesCsv)) {
                    if (deny.contains(fileName)) continue;
                    entries.add(new CatalogEntry(pkg, fileName));
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
            return new ArrayList<>();
        }

        Collections.sort(entries, (a, b) -> a.displayName.compareToIgnoreCase(b.displayName));
        return entries;
    }

    // --- INI parsing -------------------------------------------------------

    private static List<String> parseCsvList(String csv) {
        List<String> result = new ArrayList<>();
        if (csv == null || csv.trim().isEmpty()) return result;
        for (String part : csv.split(",")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) result.add(trimmed);
        }
        return result;
    }

    /** Splits the INI text into an ordered list of "[NN]" sections, each as a key->value map. */
    private static List<Map<String, String>> splitSections(String ini) {
        List<Map<String, String>> sections = new ArrayList<>();
        Map<String, String> current = null;

        for (String rawLine : ini.split("\n")) {
            String line = rawLine.trim();
            if (line.isEmpty()) continue;

            if (line.startsWith("[") && line.endsWith("]")) {
                current = new HashMap<>();
                sections.add(current);
                continue;
            }
            if (current == null) continue; // stray line before first section

            int eq = line.indexOf('=');
            if (eq <= 0) continue;
            String key = line.substring(0, eq).trim();
            String value = line.substring(eq + 1).trim();
            current.put(key, value);
        }
        return sections;
    }
}

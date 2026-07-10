#!/usr/bin/env python3
"""
patch_fex_unixlibs.py  (v2)

Menambahkan fitur "Support FEX Unix libs" ke fork Winlator (non-Compose / old-style UI).

File yang dipatch:
  1. ContentsManager.java              -> core inti: deteksi .so pada profile FEXCore yang
                                           terpasang + copy/hapus .so ke/dari folder wine unix
  2. Container.java                    -> field persisten `useUnixLibs` (default true)
  3. GuestProgramLauncherComponent.java -> saat launch, baca preferensi + panggil ContentsManager
                                           untuk menerapkan mode "unixlibs" (.so) atau "dll"
  4. ContainerDetailFragment.java       -> checkbox UI + simpan/baca saat create/edit container
  5. ShortcutSettingsDialog.java        -> checkbox UI + simpan/baca sebagai shortcut extra
  6. container_detail_fragment.xml      -> tambah <CheckBox> di fexcoreFrame
  7. shortcut_settings_dialog.xml       -> tambah <CheckBox> di fexcoreFrame
  8. strings.xml (opsional)             -> string resource untuk label checkbox

CATATAN v2:
- Versi sebelumnya (v1) belum punya akses ke ContentsManager.java, jadi deteksi ".so" dilakukan
  dengan scan mentah folder system32 setelah ekstraksi (kurang presisi, bisa salah tangkap file
  dari profile lain). Sekarang dengan ContentsManager.java tersedia, deteksi dipindah ke method
  resmi di ContentsManager (berbasis fileList milik profile FEXCore yang benar-benar dipakai):
    profileHasUnixLibs() / fexcoreVersionHasUnixLibs() / copyUnixLibsToDir() /
    removeAppliedUnixLibs() / deleteUnixLibsFromDir()
  Ini lebih akurat karena scoped ke profile yang sedang aktif, bukan seluruh isi system32dir.
- Jika GuestProgramLauncherComponent.java sudah pernah dipatch oleh v1 (helper method inline
  isSharedObjectFile/dirContainsSharedObject/dst ada di file), script ini otomatis MIGRASI:
  blok lama dihapus lalu diganti dengan pemanggilan ContentsManager yang baru.

Idempotent: aman dijalankan berkali-kali, tidak membuat file .bak.

Cara pakai:
    python3 patch_fex_unixlibs.py --root /path/ke/project
"""

import argparse
import os
import re
import sys

TARGET_FILENAMES = [
    "ContentsManager.java",
    "Container.java",
    "GuestProgramLauncherComponent.java",
    "ContainerDetailFragment.java",
    "ShortcutSettingsDialog.java",
    "container_detail_fragment.xml",
    "shortcut_settings_dialog.xml",
    "strings.xml",
]


def find_file(root, filename):
    matches = []
    for dirpath, _dirnames, filenames in os.walk(root):
        if filename in filenames:
            matches.append(os.path.join(dirpath, filename))
    if not matches:
        return None
    for m in matches:
        if f"{os.sep}build{os.sep}" not in m:
            return m
    return matches[0]


def read(path):
    with open(path, "r", encoding="utf-8") as f:
        return f.read()


def write(path, content):
    with open(path, "w", encoding="utf-8") as f:
        f.write(content)


def apply_patch(path, label, patches):
    """
    patches: list of tuples (marker, description, patch_fn)
    patch_fn(content) -> new_content atau None jika gagal / tidak ada perubahan.
    marker dipakai untuk cek idempoten: kalau marker sudah ada di file, skip.
    """
    content = read(path)
    original = content
    applied, skipped, failed = [], [], []

    for marker, desc, patch_fn in patches:
        if marker in content:
            skipped.append(desc)
            continue
        new_content = patch_fn(content)
        if new_content is None or new_content == content:
            failed.append(desc)
            continue
        content = new_content
        applied.append(desc)

    if content != original:
        write(path, content)

    print(f"\n[{label}] {path}")
    for d in applied:
        print(f"  + applied : {d}")
    for d in skipped:
        print(f"  = skip    : {d} (sudah ada)")
    for d in failed:
        print(f"  ! GAGAL   : {d} (anchor tidak ditemukan, cek manual)")

    return len(failed) == 0


# ----------------------------------------------------------------------------
# 1. ContentsManager.java  -- core inti deteksi & manajemen FEX Unix libs
# ----------------------------------------------------------------------------
def patch_contents_manager(path):
    def add_import(content):
        anchor = "import java.util.Map;\n"
        if anchor not in content:
            return None
        return content.replace(anchor, anchor + "import java.util.Locale;\n", 1)

    def add_methods(content):
        anchor = (
            "    public boolean applyContent(ContentProfile profile) {\n"
        )
        if anchor not in content:
            return None
        methods = (
            "    // === FEX Unix libs: core inti (deteksi .so pada profile FEXCore) ===\n"
            "    public boolean profileHasUnixLibs(ContentProfile profile) {\n"
            "        if (profile == null) return false;\n"
            "        return dirContainsSharedObject(getInstallDir(context, profile));\n"
            "    }\n"
            "\n"
            "    public boolean fexcoreVersionHasUnixLibs(String fexcoreVersion) {\n"
            "        if (fexcoreVersion == null || fexcoreVersion.isEmpty()) return false;\n"
            "        return profileHasUnixLibs(getProfileByEntryName(\"fexcore-\" + fexcoreVersion));\n"
            "    }\n"
            "\n"
            "    private static boolean isSharedObject(String name) {\n"
            "        String lower = name.toLowerCase(Locale.ROOT);\n"
            "        return lower.endsWith(\".so\") || lower.contains(\".so.\");\n"
            "    }\n"
            "\n"
            "    private static boolean dirContainsSharedObject(File dir) {\n"
            "        if (dir == null) return false;\n"
            "        File[] files = dir.listFiles();\n"
            "        if (files == null) return false;\n"
            "        for (File file : files) {\n"
            "            if (file.isDirectory()) {\n"
            "                if (dirContainsSharedObject(file)) return true;\n"
            "            } else if (isSharedObject(file.getName())) {\n"
            "                return true;\n"
            "            }\n"
            "        }\n"
            "        return false;\n"
            "    }\n"
            "\n"
            "    public void removeAppliedUnixLibs(ContentProfile profile) {\n"
            "        if (profile == null || profile.fileList == null) return;\n"
            "        for (ContentProfile.ContentFile contentFile : profile.fileList) {\n"
            "            if (!isSharedObject(new File(contentFile.target).getName())) continue;\n"
            "            File targetFile = new File(getPathFromTemplate(contentFile.target));\n"
            "            if (targetFile.exists() && targetFile.delete()) {\n"
            "                Log.i(\"ContentsManager\", \"UnixLibs: removed \" + targetFile.getName());\n"
            "            }\n"
            "        }\n"
            "    }\n"
            "\n"
            "    public void copyUnixLibsToDir(ContentProfile profile, File destDir) {\n"
            "        if (profile == null || profile.fileList == null) return;\n"
            "        if (!destDir.exists()) destDir.mkdirs();\n"
            "        for (ContentProfile.ContentFile contentFile : profile.fileList) {\n"
            "            String name = new File(contentFile.target).getName();\n"
            "            if (!isSharedObject(name)) continue;\n"
            "            File sourceFile = new File(getInstallDir(context, profile), contentFile.source);\n"
            "            if (!sourceFile.exists()) continue;\n"
            "            File destFile = new File(destDir, name);\n"
            "            FileUtils.copy(sourceFile, destFile);\n"
            "            FileUtils.chmod(destFile, 0771);\n"
            "        }\n"
            "    }\n"
            "\n"
            "    public void deleteUnixLibsFromDir(ContentProfile profile, File destDir) {\n"
            "        if (profile == null || profile.fileList == null) return;\n"
            "        for (ContentProfile.ContentFile contentFile : profile.fileList) {\n"
            "            String name = new File(contentFile.target).getName();\n"
            "            if (!isSharedObject(name)) continue;\n"
            "            File destFile = new File(destDir, name);\n"
            "            if (destFile.exists()) destFile.delete();\n"
            "        }\n"
            "    }\n"
            "\n"
            + anchor
        )
        return content.replace(anchor, methods, 1)

    patches = [
        ("import java.util.Locale;", "import java.util.Locale", add_import),
        ("profileHasUnixLibs(ContentProfile profile)", "method inti profileHasUnixLibs/fexcoreVersionHasUnixLibs/copyUnixLibsToDir/removeAppliedUnixLibs/deleteUnixLibsFromDir", add_methods),
    ]
    return apply_patch(path, "ContentsManager.java", patches)


# ----------------------------------------------------------------------------
# 2. Container.java
# ----------------------------------------------------------------------------
def patch_container_java(path):
    def add_field(content):
        anchor = "    private String emulator;\n"
        if anchor not in content:
            return None
        return content.replace(anchor, anchor + "    private boolean useUnixLibs = true;\n", 1)

    def add_getter_setter(content):
        anchor = "    public String getFEXCoreVersion() {\n        return this.fexcoreVersion;\n    }\n"
        if anchor not in content:
            return None
        insert = (
            anchor
            + "\n"
            + "    public void setUseUnixLibs(boolean useUnixLibs) {\n"
            + "        this.useUnixLibs = useUnixLibs;\n"
            + "    }\n\n"
            + "    public boolean isUseUnixLibs() {\n"
            + "        return this.useUnixLibs;\n"
            + "    }\n"
        )
        return content.replace(anchor, insert, 1)

    def add_save(content):
        anchor = '            data.put("fexcoreVersion", fexcoreVersion);\n'
        if anchor not in content:
            return None
        return content.replace(anchor, anchor + '            data.put("useUnixLibs", useUnixLibs);\n', 1)

    def add_load(content):
        anchor = (
            '                case "fexcoreVersion":\n'
            "                    setFEXCoreVersion(data.getString(key));\n"
            "                    break;\n"
        )
        if anchor not in content:
            return None
        insert = (
            anchor
            + '                case "useUnixLibs":\n'
            + "                    setUseUnixLibs(data.getBoolean(key));\n"
            + "                    break;\n"
        )
        return content.replace(anchor, insert, 1)

    patches = [
        ("private boolean useUnixLibs = true;", "field useUnixLibs", add_field),
        ("public boolean isUseUnixLibs()", "getter/setter isUseUnixLibs/setUseUnixLibs", add_getter_setter),
        ('data.put("useUnixLibs", useUnixLibs);', "saveData(): persist useUnixLibs", add_save),
        ('case "useUnixLibs":', "loadData(): baca useUnixLibs", add_load),
    ]
    return apply_patch(path, "Container.java", patches)


# ----------------------------------------------------------------------------
# 3. GuestProgramLauncherComponent.java
# ----------------------------------------------------------------------------
OLD_HELPER_ANCHOR_START = "    public boolean isFexUnixLibsActive() { return this.fexUnixLibsActive; }\n"
OLD_HELPER_MARKER = "isFexUnixLibsActive()"
OLD_APPLY_MARKER = "FEX Unix libs: baca preferensi"
NEW_APPLY_MARKER = "FEX Unix libs (ContentsManager): baca preferensi"


def patch_guest_program_launcher(path):
    def add_field(content):
        anchor = "    private String fexcorePreset = FEXCorePreset.INTERMEDIATE;\n"
        if anchor not in content:
            return None
        return content.replace(anchor, anchor + "    private boolean fexUnixLibsActive = false;\n", 1)

    def strip_old_helper_block(content):
        """Migrasi v1 -> v2: buang helper method inline lama (isSharedObjectFile dkk)."""
        if OLD_HELPER_MARKER not in content:
            return content
        start = content.find(OLD_HELPER_ANCHOR_START)
        if start == -1:
            return content
        end_marker = "    private int execGuestProgram() {"
        end = content.find(end_marker, start)
        if end == -1:
            return content
        insert_back = "    public boolean isFexUnixLibsActive() { return this.fexUnixLibsActive; }\n\n"
        return content[:start] + insert_back + content[end:]

    def strip_old_apply_block(content):
        """Migrasi v1 -> v2: buang blok apply-logic lama berbasis scan filesystem mentah."""
        if OLD_APPLY_MARKER not in content or NEW_APPLY_MARKER in content:
            return content
        start = content.find("\n        // === FEX Unix libs: baca preferensi")
        if start == -1:
            return content
        end_marker = "        if (containerDataChanged) container.saveData();\n"
        end = content.find(end_marker, start)
        if end == -1:
            return content
        return content[:start] + "\n" + content[end:]

    def add_apply_logic(content):
        anchor = (
            '        if (!fexcoreVersion.equals(container.getExtra("fexcoreVersion"))) {\n'
            "            ContentProfile profile = contentsManager.getProfileByEntryName(\"fexcore-\" + fexcoreVersion);\n"
            "            if (profile != null)\n"
            "                contentsManager.applyContent(profile);\n"
            "            else\n"
            "                TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, environment.getContext(), \"fexcore/fexcore-\" + fexcoreVersion + \".tzst\", system32dir);\n"
            "            container.putExtra(\"fexcoreVersion\", fexcoreVersion);\n"
            "            containerDataChanged = true;\n"
            "        }\n"
        )
        if anchor not in content:
            return None

        insert = (
            anchor
            + "\n"
            + f"        // === {NEW_APPLY_MARKER} & terapkan via ContentsManager ===\n"
            + "        boolean unixLibsPref = container.isUseUnixLibs();\n"
            + "        if (shortcut != null) {\n"
            + "            unixLibsPref = \"1\".equals(shortcut.getExtra(\"useUnixLibs\", unixLibsPref ? \"1\" : \"0\"));\n"
            + "        }\n"
            + "        ContentProfile fexcoreProfile = contentsManager.getProfileByEntryName(\"fexcore-\" + fexcoreVersion);\n"
            + "        File wineUnixDir = new File(environment.getImageFs().getWinePath(), \"lib/wine/aarch64-unix\");\n"
            + "        fexUnixLibsActive = unixLibsPref && contentsManager.fexcoreVersionHasUnixLibs(fexcoreVersion);\n"
            + "        if (fexcoreProfile != null) {\n"
            + "            if (fexUnixLibsActive) {\n"
            + "                contentsManager.copyUnixLibsToDir(fexcoreProfile, wineUnixDir);\n"
            + "                Log.i(\"GuestProgramLauncherComponent\", \"FEX UnixLibs: mode aktif, .so disalin ke \" + wineUnixDir);\n"
            + "            } else {\n"
            + "                contentsManager.removeAppliedUnixLibs(fexcoreProfile);\n"
            + "                contentsManager.deleteUnixLibsFromDir(fexcoreProfile, wineUnixDir);\n"
            + "                Log.i(\"GuestProgramLauncherComponent\", \"FEX UnixLibs: mode nonaktif (dll mode)\");\n"
            + "            }\n"
            + "        }\n"
        )
        return content.replace(anchor, insert, 1)

    def migrate_then_apply(content):
        content = strip_old_helper_block(content)
        content = strip_old_apply_block(content)
        return add_apply_logic(content)

    patches = [
        ("private boolean fexUnixLibsActive = false;", "field fexUnixLibsActive", add_field),
        (NEW_APPLY_MARKER, "extractEmulatorsDlls(): terapkan UnixLibs via ContentsManager (migrasi otomatis dari v1 jika perlu)", migrate_then_apply),
    ]
    return apply_patch(path, "GuestProgramLauncherComponent.java", patches)


# ----------------------------------------------------------------------------
# 4. ContainerDetailFragment.java
# ----------------------------------------------------------------------------
def patch_container_detail_fragment(path):
    def add_findviewbyid(content):
        anchor = "        final Spinner sFEXCorePreset = view.findViewById(R.id.SFEXCorePreset);\n        FEXCorePresetManager.loadSpinner(sFEXCorePreset, isEditMode() ? container.getFEXCorePreset() : preferences.getString(\"fexcore_preset\", FEXCorePreset.INTERMEDIATE));\n"
        if anchor not in content:
            return None
        insert = (
            anchor
            + "\n"
            + "        final CheckBox cbUseUnixLibs = view.findViewById(R.id.CBUseUnixLibs);\n"
            + "        cbUseUnixLibs.setChecked(isEditMode() ? container.isUseUnixLibs() : true);\n"
        )
        return content.replace(anchor, insert, 1)

    def add_save_edit_mode(content):
        anchor = "                    container.setFEXCorePreset(fexcorePreset);\n"
        if anchor not in content:
            return None
        return content.replace(anchor, anchor + "                    container.setUseUnixLibs(cbUseUnixLibs.isChecked());\n", 1)

    def add_save_new_mode(content):
        anchor = '                    data.put("fexcorePreset", fexcorePreset);\n'
        if anchor not in content:
            return None
        return content.replace(anchor, anchor + '                    data.put("useUnixLibs", cbUseUnixLibs.isChecked());\n', 1)

    patches = [
        ("cbUseUnixLibs = view.findViewById", "load checkbox CBUseUnixLibs (bind ke container.isUseUnixLibs())", add_findviewbyid),
        ("container.setUseUnixLibs(cbUseUnixLibs.isChecked());", "simpan useUnixLibs saat edit container", add_save_edit_mode),
        ('data.put("useUnixLibs", cbUseUnixLibs.isChecked());', "simpan useUnixLibs saat create container baru", add_save_new_mode),
    ]
    return apply_patch(path, "ContainerDetailFragment.java", patches)


# ----------------------------------------------------------------------------
# 5. ShortcutSettingsDialog.java
# ----------------------------------------------------------------------------
def patch_shortcut_settings_dialog(path):
    def add_findviewbyid(content):
        anchor = '        final Spinner sFEXCorePreset = findViewById(R.id.SFEXCorePreset);\n        FEXCorePresetManager.loadSpinner(sFEXCorePreset, shortcut.getExtra("fexcorePreset", shortcut.container.getFEXCorePreset()));\n'
        if anchor not in content:
            return None
        insert = (
            anchor
            + "\n"
            + "        final CheckBox cbUseUnixLibs = findViewById(R.id.CBUseUnixLibs);\n"
            + '        boolean isUseUnixLibs = shortcut.getExtra("useUnixLibs", shortcut.container.isUseUnixLibs() ? "1" : "0").equals("1");\n'
            + "        cbUseUnixLibs.setChecked(isUseUnixLibs);\n"
        )
        return content.replace(anchor, insert, 1)

    def add_save(content):
        anchor = '                String fexcorePreset = FEXCorePresetManager.getSpinnerSelectedId(sFEXCorePreset);\n                shortcut.putExtra("fexcorePreset", fexcorePreset);\n'
        if anchor not in content:
            return None
        insert = anchor + "\n" + '                shortcut.putExtra("useUnixLibs", cbUseUnixLibs.isChecked() ? "1" : "0");\n'
        return content.replace(anchor, insert, 1)

    patches = [
        ("cbUseUnixLibs = findViewById", "load checkbox CBUseUnixLibs (bind ke shortcut/container extra useUnixLibs)", add_findviewbyid),
        ('shortcut.putExtra("useUnixLibs"', "simpan useUnixLibs sebagai shortcut extra", add_save),
    ]
    return apply_patch(path, "ShortcutSettingsDialog.java", patches)


# ----------------------------------------------------------------------------
# 6 & 7. Layout XML
# ----------------------------------------------------------------------------
def patch_layout_xml(path, label):
    def add_checkbox(content):
        anchor_pattern = re.compile(
            r'(<Spinner\s+style="@style/ComboBox"\s+android:id="@\+id/SFEXCorePreset"[^/]*?/>\s*\n)',
            re.DOTALL,
        )
        m = anchor_pattern.search(content)
        if not m:
            return None
        checkbox_block = (
            "\n"
            '                    <CheckBox\r\n'
            '                        android:id="@+id/CBUseUnixLibs"\r\n'
            '                        android:layout_width="wrap_content"\r\n'
            '                        android:layout_height="wrap_content"\r\n'
            '                        android:layout_marginTop="4dp"\r\n'
            '                        android:checked="true"\r\n'
            '                        android:text="@string/support_fex_unix_libs" />\r\n'
        )
        return content[: m.end()] + checkbox_block + content[m.end():]

    patches = [
        ('android:id="@+id/CBUseUnixLibs"', "tambah CheckBox Support FEX Unix libs di fexcoreFrame", add_checkbox),
    ]
    return apply_patch(path, label, patches)


# ----------------------------------------------------------------------------
# 8. strings.xml (opsional)
# ----------------------------------------------------------------------------
def patch_strings_xml(path):
    def add_string(content):
        anchor = "</resources>"
        if anchor not in content:
            return None
        return content.replace(anchor, '    <string name="support_fex_unix_libs">Support FEX Unix libs</string>\n' + anchor, 1)

    patches = [
        ('name="support_fex_unix_libs"', "tambah string support_fex_unix_libs", add_string),
    ]
    return apply_patch(path, "strings.xml", patches)


# ----------------------------------------------------------------------------
def main():
    ap = argparse.ArgumentParser(description="Patch fitur Support FEX Unix libs ke Winlator fork (v2, terintegrasi ContentsManager).")
    ap.add_argument("--root", default=".", help="Root direktori project untuk pencarian file (default: cwd)")
    args = ap.parse_args()
    root = os.path.abspath(args.root)

    if not os.path.isdir(root):
        print(f"Root tidak ditemukan: {root}")
        sys.exit(1)

    print(f"Mencari file target di bawah: {root}\n")

    paths = {name: find_file(root, name) for name in TARGET_FILENAMES}

    for name, p in paths.items():
        if p is None:
            print(f"  ! tidak ditemukan: {name}")

    ok = True
    if paths["ContentsManager.java"]:
        ok &= patch_contents_manager(paths["ContentsManager.java"])
    else:
        print("\n! ContentsManager.java tidak ditemukan -> GuestProgramLauncherComponent tidak bisa dipatch dengan benar, hentikan.")
        ok = False

    if paths["Container.java"]:
        ok &= patch_container_java(paths["Container.java"])
    if ok and paths["GuestProgramLauncherComponent.java"] and paths["ContentsManager.java"]:
        ok &= patch_guest_program_launcher(paths["GuestProgramLauncherComponent.java"])
    if paths["ContainerDetailFragment.java"]:
        ok &= patch_container_detail_fragment(paths["ContainerDetailFragment.java"])
    if paths["ShortcutSettingsDialog.java"]:
        ok &= patch_shortcut_settings_dialog(paths["ShortcutSettingsDialog.java"])
    if paths["container_detail_fragment.xml"]:
        ok &= patch_layout_xml(paths["container_detail_fragment.xml"], "container_detail_fragment.xml")
    if paths["shortcut_settings_dialog.xml"]:
        ok &= patch_layout_xml(paths["shortcut_settings_dialog.xml"], "shortcut_settings_dialog.xml")
    if paths["strings.xml"]:
        ok &= patch_strings_xml(paths["strings.xml"])
    else:
        print(
            "\n[strings.xml] tidak ditemukan -> tambahkan manual:\n"
            '  <string name="support_fex_unix_libs">Support FEX Unix libs</string>'
        )

    print("\n" + ("SELESAI: semua patch berhasil diterapkan (atau sudah ada)." if ok else
                   "SELESAI dengan PERINGATAN: beberapa anchor tidak ditemukan / file hilang, cek log di atas."))


if __name__ == "__main__":
    main()

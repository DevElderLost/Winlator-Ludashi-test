package com.winlator.cmod.xserver;

import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

/**
 * Parser untuk file Xcursor dari /usr/share/icons/<theme>/cursors/<name>.
 *
 * Format Xcursor (magic 0x72756358 = "Xcur"):
 *   Header: magic(4) + header_size(4) + version(4) + ntoc(4)
 *   TOC entries: type(4) + subtype(4) + position(4)
 *   Image chunk: header(4) + type(4) + subtype(size) + version(4)
 *                + width(4) + height(4) + xhot(4) + yhot(4) + delay(4)
 *                + pixels[width*height] ARGB 32-bit
 *
 * Kelas ini mengembalikan list XcursorFrame — setiap frame berisi
 * pixel ARGB, dimensi, hotspot, dan delay animasi dalam milidetik.
 */
public class XcursorLoader {

    private static final String TAG = "XcursorLoader";
    private static final int XCURSOR_MAGIC     = 0x72756358; // "Xcur"
    private static final int XCURSOR_IMAGE_TYPE = 0xfffd0002;

    public static class XcursorFrame {
        public final int width;
        public final int height;
        public final int hotSpotX;
        public final int hotSpotY;
        public final int delayMs;        // delay animasi dalam milidetik
        public final int[] pixels;       // ARGB 32-bit, panjang = width * height

        public XcursorFrame(int width, int height, int hotSpotX, int hotSpotY,
                            int delayMs, int[] pixels) {
            this.width    = width;
            this.height   = height;
            this.hotSpotX = hotSpotX;
            this.hotSpotY = hotSpotY;
            this.delayMs  = delayMs;
            this.pixels   = pixels;
        }
    }

    /**
     * Load semua frame dari file cursor Xcursor.
     * @param file  path ke file cursor, misal /usr/share/icons/DMZ-White/cursors/watch
     * @return list frame terurut (index 0 = frame pertama), atau list kosong jika gagal
     */
    public static List<XcursorFrame> load(File file) {
        List<XcursorFrame> frames = new ArrayList<>();
        try (FileInputStream fis = new FileInputStream(file)) {
            byte[] raw = new byte[(int) file.length()];
            //noinspection ResultOfMethodCallIgnored
            fis.read(raw);
            ByteBuffer buf = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);

            // Validasi magic number
            int magic = buf.getInt();
            if (magic != XCURSOR_MAGIC) {
                Log.w(TAG, "Bukan file Xcursor: " + file.getPath());
                return frames;
            }

            int headerSize = buf.getInt();
            int version    = buf.getInt();
            int ntoc       = buf.getInt(); // jumlah entry di table of contents

            // Baca table of contents
            int[] tocType = new int[ntoc];
            int[] tocSubtype = new int[ntoc];
            int[] tocPos  = new int[ntoc];
            for (int i = 0; i < ntoc; i++) {
                tocType[i]    = buf.getInt();
                tocSubtype[i] = buf.getInt();
                tocPos[i]     = buf.getInt();
            }

            // Proses setiap entry bertipe IMAGE
            for (int i = 0; i < ntoc; i++) {
                if (tocType[i] != XCURSOR_IMAGE_TYPE) continue;

                buf.position(tocPos[i]);

                int chunkHeader = buf.getInt(); // ukuran header chunk
                int chunkType   = buf.getInt();
                int chunkSubtype= buf.getInt();
                int chunkVer    = buf.getInt();
                int width       = buf.getInt();
                int height      = buf.getInt();
                int hotSpotX    = buf.getInt();
                int hotSpotY    = buf.getInt();
                int delayMs     = buf.getInt();

                if (width <= 0 || height <= 0 || width > 512 || height > 512) {
                    Log.w(TAG, "Ukuran cursor tidak valid: " + width + "x" + height);
                    continue;
                }

                int[] pixels = new int[width * height];
                for (int p = 0; p < pixels.length; p++) {
                    // Xcursor menyimpan pixel sebagai ARGB little-endian
                    // Android Bitmap menggunakan ARGB_8888 big-endian
                    // Tidak perlu swap — ByteBuffer sudah LITTLE_ENDIAN
                    pixels[p] = buf.getInt();
                }

                frames.add(new XcursorFrame(width, height, hotSpotX, hotSpotY, delayMs, pixels));
            }

        } catch (IOException e) {
            Log.e(TAG, "Gagal load cursor: " + file.getPath(), e);
        }

        return frames;
    }

    /**
     * Cari file cursor dari theme yang diinstall.
     * Mencoba path: /usr/share/icons/<theme>/cursors/<name>
     * Fallback ke theme "default" dan "DMZ-White".
     */
    public static File findCursorFile(String theme, String cursorName) {
        String[] searchPaths = {
            "/usr/share/icons/" + theme + "/cursors/" + cursorName,
            "/usr/share/icons/default/cursors/" + cursorName,
            "/usr/share/icons/DMZ-White/cursors/" + cursorName,
            "/usr/share/icons/DMZ-Black/cursors/" + cursorName,
            "/usr/share/cursors/xorg-x11/" + cursorName,
        };

        for (String path : searchPaths) {
            File f = new File(path);
            if (f.exists() && f.canRead()) return f;
        }
        return null;
    }
}

package com.winlator.cmod.xserver;

import android.util.Log;
import android.util.SparseArray;

import com.winlator.cmod.xserver.AnimatedCursor;
import com.winlator.cmod.xserver.XcursorLoader;

import java.io.File;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.util.List;

public class CursorManager extends XResourceManager {
    private static final String TAG = "CursorManager";
    private final SparseArray<Cursor> cursors = new SparseArray<>();
    private final DrawableManager drawableManager;

    // Theme cursor aktif — bisa diset dari Settings
    private String cursorTheme = "DMZ-White";

    public CursorManager(DrawableManager drawableManager) {
        this.drawableManager = drawableManager;
    }

    public void setCursorTheme(String theme) {
        this.cursorTheme = theme;
    }

    public String getCursorTheme() {
        return cursorTheme;
    }

    public Cursor getCursor(int id) {
        return cursors.get(id);
    }

    // -------------------------------------------------------------------------
    // Cursor X11 klasik (1-bit source + 1-bit mask, opcode CREATE_CURSOR)
    // -------------------------------------------------------------------------
    public Cursor createCursor(int id, short x, short y, Pixmap sourcePixmap, Pixmap maskPixmap) {
        if (cursors.indexOfKey(id) >= 0) return null;

        Drawable drawable = drawableManager.createDrawable(
            0,
            sourcePixmap.drawable.width,   // sudah short
            sourcePixmap.drawable.height,  // sudah short
            sourcePixmap.drawable.visual
        );
        Cursor cursor = new Cursor(id, x, y, drawable,
            sourcePixmap.drawable,
            maskPixmap != null ? maskPixmap.drawable : null);

        // BUG FIX: Evaluasi visibilitas langsung saat konstruksi.
        // Tanpa ini ada window waktu di mana cursor mask kosong tetap dirender
        // sebelum recolorCursor pertama kali dipanggil.
        if (maskPixmap != null && maskPixmap.drawable != null
                && maskPixmap.drawable.getData() != null
                && isEmptyMaskImage(maskPixmap.drawable)) {
            cursor.setVisible(false);
        }

        cursors.put(id, cursor);
        triggerOnCreateResourceListener(cursor);
        return cursor;
    }

    // -------------------------------------------------------------------------
    // Cursor ARGB 32-bit (XRender/Xcursor, opcode XRenderCreateCursor)
    // pixels: array ARGB 32-bit, panjang = width * height
    // -------------------------------------------------------------------------
    public Cursor createArgbCursor(int id, short hotX, short hotY,
                                   int width, int height, int[] pixels) {
        if (cursors.indexOfKey(id) >= 0) return null;

        Visual visual = drawableManager.getVisual();
        Drawable drawable = drawableManager.createDrawable(0, (short) width, (short) height, visual);

        // Salin pixel ARGB langsung ke drawable — tidak perlu recolor
        ByteBuffer buf = drawable.getData();
        buf.rewind();
        IntBuffer ibuf = buf.asIntBuffer();
        ibuf.put(pixels);

        Cursor cursor = new Cursor(id, hotX, hotY, drawable, true /*isArgb*/);
        cursors.put(id, cursor);
        triggerOnCreateResourceListener(cursor);
        return cursor;
    }

    // -------------------------------------------------------------------------
    // Cursor Xcursor dari /usr/share/icons/<theme>/cursors/<name>
    // Mendukung animasi multi-frame (watch, loading, dsb)
    // -------------------------------------------------------------------------
    public Cursor createXcursorFromTheme(int id, String cursorName) {
        if (cursors.indexOfKey(id) >= 0) return null;

        File file = XcursorLoader.findCursorFile(cursorTheme, cursorName);
        if (file == null) {
            Log.w(TAG, "Cursor tidak ditemukan di theme: " + cursorName);
            return null;
        }

        List<XcursorLoader.XcursorFrame> frames = XcursorLoader.load(file);
        if (frames.isEmpty()) {
            Log.w(TAG, "Gagal parse Xcursor: " + file.getPath());
            return null;
        }

        // Pilih ukuran frame terbaik (terdekat dengan 32x32)
        int targetSize = 32;
        List<XcursorLoader.XcursorFrame> bestFrames = selectBestSizeFrames(frames, targetSize);

        XcursorLoader.XcursorFrame first = bestFrames.get(0);
        Visual visual = drawableManager.getVisual();

        // Buat drawable utama dari frame pertama
        Drawable drawable = drawableManager.createDrawable(0, (short) first.width, (short) first.height, visual);
        copyPixelsToDrawable(first.pixels, drawable);

        Cursor cursor = new Cursor(id, (short) first.hotSpotX, (short) first.hotSpotY,
            drawable, true /*isArgb*/);

        // Jika multi-frame → buat AnimatedCursor
        if (bestFrames.size() > 1) {
            AnimatedCursor.Frame[] animFrames = new AnimatedCursor.Frame[bestFrames.size()];
            for (int i = 0; i < bestFrames.size(); i++) {
                XcursorLoader.XcursorFrame f = bestFrames.get(i);
                Drawable fd = drawableManager.createDrawable(0, (short) f.width, (short) f.height, visual);
                copyPixelsToDrawable(f.pixels, fd);
                animFrames[i] = new AnimatedCursor.Frame(
                    fd, f.hotSpotX, f.hotSpotY, f.delayMs > 0 ? f.delayMs : 50);
            }
            cursor.setAnimatedCursor(new AnimatedCursor(animFrames));
            Log.d(TAG, "Loaded animated cursor '" + cursorName
                + "' frames=" + bestFrames.size()
                + " delay=" + bestFrames.get(0).delayMs + "ms");
        } else {
            Log.d(TAG, "Loaded static Xcursor '" + cursorName + "' " + first.width + "x" + first.height);
        }

        cursors.put(id, cursor);
        triggerOnCreateResourceListener(cursor);
        return cursor;
    }

    public void freeCursor(int id) {
        triggerOnFreeResourceListener(cursors.get(id));
        cursors.remove(id);
    }

    // -------------------------------------------------------------------------
    // Recolor — hanya untuk cursor X11 klasik (bukan ARGB)
    // -------------------------------------------------------------------------
    public void recolorCursor(Cursor cursor, byte foreRed, byte foreGreen, byte foreBlue,
                               byte backRed, byte backGreen, byte backBlue) {
        // Cursor ARGB tidak perlu recolor — warna sudah benar dari file Xcursor
        if (cursor.isArgb) return;

        if (cursor.maskImage != null) {
            boolean visible = !isEmptyMaskImage(cursor.maskImage);
            cursor.setVisible(visible);
            if (visible) {
                cursor.cursorImage.drawAlphaMaskedBitmap(
                    foreRed, foreGreen, foreBlue,
                    backRed, backGreen, backBlue,
                    cursor.sourceImage, cursor.maskImage);
            }
        }
    }

    // -------------------------------------------------------------------------
    // Hide/Show cursor — dipanggil dari XFixesExtension
    // -------------------------------------------------------------------------
    public void hideCursor(int id) {
        Cursor cursor = cursors.get(id);
        if (cursor != null) cursor.setForceHidden(true);
    }

    public void showCursor(int id) {
        Cursor cursor = cursors.get(id);
        if (cursor != null) cursor.setForceHidden(false);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------
    private static boolean isEmptyMaskImage(Drawable maskImage) {
        IntBuffer maskData = maskImage.getData().asIntBuffer();
        for (int i = 0; i < maskData.capacity(); i++) {
            if (maskData.get(i) != 0x000000) return false;
        }
        return true;
    }

    private static void copyPixelsToDrawable(int[] pixels, Drawable drawable) {
        ByteBuffer buf = drawable.getData();
        buf.rewind();
        IntBuffer ibuf = buf.asIntBuffer();
        ibuf.put(pixels);
    }

    /**
     * Dari semua frame yang tersedia, pilih frame dengan ukuran terdekat ke targetSize.
     * Semua frame dengan ukuran yang sama dianggap satu set animasi.
     */
    private static List<XcursorLoader.XcursorFrame> selectBestSizeFrames(
            List<XcursorLoader.XcursorFrame> allFrames, int targetSize) {
        int bestSize = -1;
        int bestDiff = Integer.MAX_VALUE;
        for (XcursorLoader.XcursorFrame f : allFrames) {
            int diff = Math.abs(f.width - targetSize);
            if (diff < bestDiff) {
                bestDiff = diff;
                bestSize = f.width;
            }
        }
        final int chosenSize = bestSize;
        List<XcursorLoader.XcursorFrame> result = new java.util.ArrayList<>();
        for (XcursorLoader.XcursorFrame f : allFrames) {
            if (f.width == chosenSize) result.add(f);
        }
        return result;
    }
}

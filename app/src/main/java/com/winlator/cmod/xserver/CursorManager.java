package com.winlator.cmod.xserver;

import android.util.SparseArray;

import java.nio.IntBuffer;

public class CursorManager extends XResourceManager {
    private final SparseArray<Cursor> cursors = new SparseArray<>();
    private final DrawableManager drawableManager;

    public CursorManager(DrawableManager drawableManager) {
        this.drawableManager = drawableManager;
    }

    public Cursor getCursor(int id) {
        return cursors.get(id);
    }

    public Cursor createCursor(int id, short x, short y, Pixmap sourcePixmap, Pixmap maskPixmap) {
        if (cursors.indexOfKey(id) >= 0) return null;
        Drawable drawable = drawableManager.createDrawable(0, sourcePixmap.drawable.width, sourcePixmap.drawable.height, sourcePixmap.drawable.visual);
        Cursor cursor = new Cursor(id, x, y, drawable, sourcePixmap.drawable, maskPixmap != null ? maskPixmap.drawable : null);

        // BUG FIX: Evaluasi visibilitas segera saat cursor dibuat.
        // Cursor dengan mask yang seluruhnya transparan/kosong langsung ditandai tidak visible,
        // sehingga tidak ada jendela waktu di mana cursor yang seharusnya tersembunyi
        // sempat dirender sebelum recolorCursor pertama kali dipanggil.
        if (maskPixmap != null && maskPixmap.drawable != null
                && maskPixmap.drawable.getData() != null
                && isEmptyMaskImage(maskPixmap.drawable)) {
            cursor.setVisible(false);
        }

        cursors.put(id, cursor);
        triggerOnCreateResourceListener(cursor);
        return cursor;
    }

    public void freeCursor(int id) {
        triggerOnFreeResourceListener(cursors.get(id));
        cursors.remove(id);
    }

    private static boolean isEmptyMaskImage(Drawable maskImage) {
        IntBuffer maskData = maskImage.getData().asIntBuffer();
        boolean result = true;
        for (int i = 0; i < maskData.capacity(); i++) {
            if (maskData.get(i) != 0x000000) {
                result = false;
                break;
            }
        }
        return result;
    }

    public void recolorCursor(Cursor cursor, byte foreRed, byte foreGreen, byte foreBlue, byte backRed, byte backGreen, byte backBlue) {
        if (cursor.maskImage != null) {
            boolean visible = !isEmptyMaskImage(cursor.maskImage);
            cursor.setVisible(visible);
            if (visible) cursor.cursorImage.drawAlphaMaskedBitmap(foreRed, foreGreen, foreBlue, backRed, backGreen, backBlue, cursor.sourceImage, cursor.maskImage);
        }
    }

    /**
     * BUG FIX: Dipanggil oleh handler XFixesHideCursor (atau mekanisme hide cursor lainnya).
     * Menandai cursor secara eksplisit sebagai "force hidden" sehingga GLRenderer
     * tidak akan merendernya — termasuk tidak jatuh ke fallback rootCursorDrawable.
     */
    public void hideCursor(int id) {
        Cursor cursor = cursors.get(id);
        if (cursor != null) cursor.setForceHidden(true);
    }

    /**
     * Dipanggil oleh handler XFixesShowCursor.
     * Mengembalikan cursor ke kondisi visible normal.
     */
    public void showCursor(int id) {
        Cursor cursor = cursors.get(id);
        if (cursor != null) cursor.setForceHidden(false);
    }
}

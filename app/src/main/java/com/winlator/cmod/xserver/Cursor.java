package com.winlator.cmod.xserver;

import com.winlator.cmod.xserver.AnimatedCursor;

/**
 * Representasi cursor X11.
 *
 * Perubahan dari versi lama:
 * 1. Tambah flag forceHidden — memisahkan "cursor sengaja disembunyikan game"
 *    dari "cursor mask kosong/transparan". Tanpa ini, GLRenderer selalu
 *    jatuh ke fallback rootCursorDrawable meski game sudah hide cursor.
 * 2. Tambah field animatedCursor — mendukung cursor XCursor multi-frame
 *    (watch, loading, dsb) dari /usr/share/icons/<theme>/cursors/.
 * 3. Flag isArgb — membedakan cursor X11 klasik (1-bit mask) dari
 *    cursor ARGB 32-bit penuh (XRender/Xcursor format).
 */
public class Cursor extends XResource {
    public final int hotSpotX;
    public final int hotSpotY;
    public final Drawable cursorImage;
    public final Drawable sourceImage;
    public final Drawable maskImage;

    // Apakah cursor ini format ARGB 32-bit penuh (dari Xcursor/XRender)?
    // Jika true, recolorCursor() tidak dijalankan — warna sudah benar dari file.
    public final boolean isArgb;

    // Untuk cursor animasi (Xcursor multi-frame): berisi semua frame + delay.
    // Null untuk cursor statis biasa.
    private AnimatedCursor animatedCursor;

    // BUG FIX 1: Pisahkan dua kondisi invisible:
    //   visible     = false → mask kosong/transparan (dari recolorCursor)
    //   forceHidden = true  → game eksplisit hide cursor (XFixesHideCursor dsb)
    private boolean visible = true;
    private boolean forceHidden = false;

    // Konstruktor untuk cursor X11 klasik (1-bit source + 1-bit mask)
    public Cursor(int id, int hotSpotX, int hotSpotY, Drawable cursorImage,
                  Drawable sourceImage, Drawable maskImage) {
        super(id);
        this.hotSpotX    = hotSpotX;
        this.hotSpotY    = hotSpotY;
        this.cursorImage = cursorImage;
        this.sourceImage = sourceImage;
        this.maskImage   = maskImage;
        this.isArgb      = false;
    }

    // Konstruktor untuk cursor ARGB (Xcursor/XRender) — tidak butuh sourceImage/maskImage
    public Cursor(int id, int hotSpotX, int hotSpotY, Drawable cursorImage, boolean isArgb) {
        super(id);
        this.hotSpotX    = hotSpotX;
        this.hotSpotY    = hotSpotY;
        this.cursorImage = cursorImage;
        this.sourceImage = null;
        this.maskImage   = null;
        this.isArgb      = isArgb;
    }

    /**
     * True hanya jika cursor harus dirender:
     * - mask tidak kosong (visible == true), DAN
     * - tidak di-hide paksa oleh game (forceHidden == false)
     */
    public boolean isVisible() {
        return visible && !forceHidden;
    }

    /** Dipanggil dari recolorCursor — mencerminkan apakah mask kosong atau tidak. */
    public void setVisible(boolean visible) {
        this.visible = visible;
    }

    /**
     * BUG FIX: Dipanggil oleh XFixesExtension saat game eksplisit hide/show cursor.
     * Ketika forceHidden == true, cursor tidak dirender sama sekali —
     * termasuk tidak jatuh ke fallback rootCursorDrawable di GLRenderer.
     */
    public void setForceHidden(boolean forceHidden) {
        this.forceHidden = forceHidden;
    }

    public boolean isForceHidden() {
        return forceHidden;
    }

    /** Set animated cursor (untuk Xcursor multi-frame). */
    public void setAnimatedCursor(AnimatedCursor animatedCursor) {
        this.animatedCursor = animatedCursor;
    }

    public AnimatedCursor getAnimatedCursor() {
        return animatedCursor;
    }

    public boolean isAnimated() {
        return animatedCursor != null && animatedCursor.isAnimated();
    }
}

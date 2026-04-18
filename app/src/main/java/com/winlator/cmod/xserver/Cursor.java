package com.winlator.cmod.xserver;

public class Cursor extends XResource {
    public final int hotSpotX;
    public final int hotSpotY;
    public final Drawable cursorImage;
    public final Drawable sourceImage;
    public final Drawable maskImage;

    // BUG FIX: Pisahkan dua kondisi "invisible":
    //   visible      = false → mask kosong / transparan (dari recolorCursor)
    //   forceHidden  = true  → game secara eksplisit menyembunyikan cursor
    //                          (XFixesHideCursor, XDefineCursor dengan cursor None, dsb.)
    // Cursor hanya akan dirender jika visible == true DAN forceHidden == false.
    private boolean visible = true;
    private boolean forceHidden = false;

    public Cursor(int id, int hotSpotX, int hotSpotY, Drawable cursorImage, Drawable sourceImage, Drawable maskImage) {
        super(id);
        this.hotSpotX = hotSpotX;
        this.hotSpotY = hotSpotY;
        this.cursorImage = cursorImage;
        this.sourceImage = sourceImage;
        this.maskImage = maskImage;
    }

    /** True hanya jika cursor memang harus ditampilkan (tidak di-hide paksa dan mask tidak kosong). */
    public boolean isVisible() {
        return visible && !forceHidden;
    }

    /** Dipanggil dari recolorCursor — mencerminkan isi mask (kosong = transparan). */
    public void setVisible(boolean visible) {
        this.visible = visible;
    }

    /**
     * Dipanggil oleh handler X11 yang secara eksplisit menyembunyikan cursor:
     * XFixesHideCursor, XDefineCursor(None), atau mekanisme sejenis.
     * Ketika forceHidden == true, cursor tidak dirender meskipun visible == true,
     * sehingga cursor bawaan game di dalam Wine tidak tertimpa cursor Winlator.
     */
    public void setForceHidden(boolean forceHidden) {
        this.forceHidden = forceHidden;
    }

    public boolean isForceHidden() {
        return forceHidden;
    }
}

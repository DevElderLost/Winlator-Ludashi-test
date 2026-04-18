package com.winlator.cmod.xserver;

import java.util.List;

/**
 * Wrapper untuk animated cursor (multi-frame).
 *
 * Menyimpan semua frame hasil parse XcursorLoader dan menyediakan
 * logika cycling frame berdasarkan waktu (delayMs per frame).
 *
 * GLRenderer memanggil getCurrentFrame() setiap render pass.
 * Jika cursor hanya 1 frame (cursor statis), getCurrentFrame() selalu
 * mengembalikan frame[0] tanpa overhead timer.
 */
public class AnimatedCursor {

    public static class Frame {
        public final Drawable drawable;   // pixel ARGB sudah di-copy ke Drawable
        public final int hotSpotX;
        public final int hotSpotY;
        public final int delayMs;

        public Frame(Drawable drawable, int hotSpotX, int hotSpotY, int delayMs) {
            this.drawable  = drawable;
            this.hotSpotX  = hotSpotX;
            this.hotSpotY  = hotSpotY;
            this.delayMs   = delayMs;
        }
    }

    private final Frame[] frames;
    private int currentIndex = 0;
    private long lastFrameTimeMs = 0;
    private final boolean animated;

    public AnimatedCursor(Frame[] frames) {
        this.frames   = frames;
        this.animated = frames.length > 1;
    }

    /**
     * Kembalikan frame yang seharusnya ditampilkan sekarang.
     * Otomatis advance ke frame berikutnya jika delay sudah terlewat.
     */
    public Frame getCurrentFrame() {
        if (!animated) return frames[0];

        long now = System.currentTimeMillis();
        if (lastFrameTimeMs == 0) lastFrameTimeMs = now;

        Frame current = frames[currentIndex];
        if (now - lastFrameTimeMs >= current.delayMs) {
            currentIndex = (currentIndex + 1) % frames.length;
            lastFrameTimeMs = now;
        }
        return frames[currentIndex];
    }

    public boolean isAnimated() {
        return animated;
    }

    public int getFrameCount() {
        return frames.length;
    }

    /**
     * Reset animasi ke frame pertama — dipanggil saat cursor baru di-assign ke window.
     */
    public void reset() {
        currentIndex    = 0;
        lastFrameTimeMs = 0;
    }
}

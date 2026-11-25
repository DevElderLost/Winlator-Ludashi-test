package com.winlator.cmod.renderer.old;

import com.winlator.cmod.xserver.ScreenInfo;

/** Port dari FullscreenTransformation experimental-v2 (hanya bagian render). */
public class OldFullscreenTransformation {
    public short x;
    public short y;
    public short width;
    public short height;

    /**
     * Hitung posisi dan ukuran window agar memenuhi layar secara aspect-ratio-preserving.
     */
    public void update(ScreenInfo screenInfo, short srcWidth, short srcHeight) {
        short targetHeight = (short) Math.min(
                screenInfo.height,
                ((float) screenInfo.width / srcWidth) * srcHeight
        );
        short targetWidth = (short) (((float) targetHeight / srcHeight) * srcWidth);

        this.x = (short) ((screenInfo.width - targetWidth) * 0.5f);
        this.y = (short) ((screenInfo.height - targetHeight) * 0.5f);
        this.width = targetWidth;
        this.height = targetHeight;
    }
}

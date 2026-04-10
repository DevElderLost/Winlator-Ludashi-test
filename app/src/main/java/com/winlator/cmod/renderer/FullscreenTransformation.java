package com.winlator.cmod.renderer;

import com.winlator.cmod.xserver.ScreenInfo;
import com.winlator.cmod.xserver.Window;

public class FullscreenTransformation {

    public short x;
    public short y;
    public short width;
    public short height;

    private final Window window;

    public FullscreenTransformation(Window window) {
        this.window = window;
    }

    /**
     * Hitung posisi dan ukuran window agar memenuhi layar secara aspect-ratio-preserving.
     * Hasil disimpan ke field {@code x, y, width, height}.
     *
     * @param screenInfo info resolusi layar target
     * @param srcWidth   lebar asli window (piksel)
     * @param srcHeight  tinggi asli window (piksel)
     */
    public void update(ScreenInfo screenInfo, short srcWidth, short srcHeight) {
        short targetHeight = (short) Math.min(
                screenInfo.height,
                ((float) screenInfo.width / srcWidth) * srcHeight
        );
        short targetWidth = (short) (((float) targetHeight / srcHeight) * srcWidth);

        this.x = (short) ((screenInfo.width  - targetWidth)  * 0.5f);
        this.y = (short) ((screenInfo.height - targetHeight) * 0.5f);
        this.width  = targetWidth;
        this.height = targetHeight;
    }

    /**
     * Transformasi koordinat pointer dari ruang layar ke ruang window asli.
     * Digunakan agar input mouse tetap akurat saat window di-scale ke fullscreen.
     *
     * @param screenX koordinat X pointer di ruang layar
     * @param screenY koordinat Y pointer di ruang layar
     * @return koordinat [x, y] dalam ruang window asli
     */
    public short[] transformPointerCoords(short screenX, short screenY) {
        short[] localPoint = window.rootPointToLocal(screenX, screenY);

        short transformedX = (short) Math.max(0.0f,
                (localPoint[0] * ((float) window.getWidth()  / width))  + window.getRootX());
        short transformedY = (short) Math.max(0.0f,
                (localPoint[1] * ((float) window.getHeight() / height)) + window.getRootY());

        return new short[]{ transformedX, transformedY };
    }
}

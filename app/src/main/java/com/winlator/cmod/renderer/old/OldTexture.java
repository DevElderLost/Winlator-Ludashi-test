package com.winlator.cmod.renderer.old;

import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.opengl.GLES30;
import android.util.Log;

import com.winlator.cmod.xserver.Drawable;

import java.nio.ByteBuffer;

/**
 * Tekstur GL milik renderer "Old Display". Diadaptasi dari Texture/GPUImage experimental-v2,
 * tetapi sumber pikselnya mengikuti Drawable 4.1 yang berbasis AHardwareBuffer:
 *  - konten window biasa: dikunci (lockBuffer) lalu diunggah dengan glTexSubImage2D,
 *  - konten direct (DRI3): AHardwareBuffer diimpor langsung sebagai EGLImage (tanpa copy).
 * Seluruh method harus dipanggil dari GL thread.
 */
public class OldTexture {
    private static final String TAG = "OldTexture";

    static {
        System.loadLibrary("winlator");
    }

    private int textureId = 0;
    private long imageKHRPtr = 0;
    private boolean imported = false;
    private int uploadedWidth = 0;
    private int uploadedHeight = 0;
    private boolean needsUpdate = true;

    public int getTextureId() {
        return textureId;
    }

    public boolean isAllocated() {
        return textureId > 0;
    }

    public boolean isImported() {
        return imported;
    }

    public void setNeedsUpdate(boolean needsUpdate) {
        this.needsUpdate = needsUpdate;
    }

    public boolean isNeedsUpdate() {
        return needsUpdate;
    }

    private void generateTexture() {
        int[] ids = new int[1];
        GLES20.glGenTextures(1, ids, 0);
        textureId = ids[0];
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
    }

    /**
     * Impor AHardwareBuffer sebagai tekstur (jalur DRI3 / direct content). Mengembalikan false bila gagal.
     */
    public boolean importHardwareBuffer(long hardwareBufferPtr) {
        if (isAllocated()) return imported;
        if (hardwareBufferPtr == 0) return false;

        generateTexture();
        imageKHRPtr = nativeCreateImage(hardwareBufferPtr, textureId);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0);
        if (imageKHRPtr == 0) {
            Log.e(TAG, "Failed to create EGLImage from AHardwareBuffer");
            destroy();
            return false;
        }
        imported = true;
        needsUpdate = false;
        return true;
    }

    /**
     * Unggah piksel Drawable (jalur konten window biasa). Mengembalikan false bila buffer tidak dapat dikunci
     * sehingga pemanggil boleh mencoba lagi di frame berikutnya.
     */
    public boolean updateFromDrawable(Drawable drawable) {
        if (imported) return true;
        if (isAllocated() && !needsUpdate) return true;

        long ahb = drawable.backingAHB;
        if (ahb == 0) return false;

        ByteBuffer data = drawable.lockBuffer(ahb);
        if (data == null) return false;

        try {
            int width = drawable.width;
            int height = drawable.height;
            int stride = drawable.getStride();
            if (stride <= 0) stride = width;

            int format = drawable.format == Drawable.HAL_PIXEL_FORMAT_RGBA_8888
                    ? GLES20.GL_RGBA : GLES11Ext.GL_BGRA;

            boolean firstUpload = !isAllocated();
            if (firstUpload) generateTexture();

            GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId);
            GLES20.glPixelStorei(GLES20.GL_UNPACK_ALIGNMENT, 4);

            boolean useRowLength = stride != width;
            if (useRowLength) GLES30.glPixelStorei(GLES30.GL_UNPACK_ROW_LENGTH, stride);

            data.position(0);
            if (firstUpload || uploadedWidth != width || uploadedHeight != height) {
                GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, format, width, height, 0, format,
                        GLES20.GL_UNSIGNED_BYTE, data);
                uploadedWidth = width;
                uploadedHeight = height;
            } else {
                GLES20.glTexSubImage2D(GLES20.GL_TEXTURE_2D, 0, 0, 0, width, height, format,
                        GLES20.GL_UNSIGNED_BYTE, data);
            }

            if (useRowLength) GLES30.glPixelStorei(GLES30.GL_UNPACK_ROW_LENGTH, 0);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0);
            needsUpdate = false;
            return true;
        } finally {
            drawable.unlockBuffer(ahb);
        }
    }

    public void destroy() {
        if (imageKHRPtr != 0) {
            nativeDestroyImage(imageKHRPtr);
            imageKHRPtr = 0;
        }
        if (textureId > 0) {
            GLES20.glDeleteTextures(1, new int[]{textureId}, 0);
            textureId = 0;
        }
        imported = false;
        uploadedWidth = 0;
        uploadedHeight = 0;
        needsUpdate = true;
    }

    private static native long nativeCreateImage(long hardwareBufferPtr, int textureId);

    private static native void nativeDestroyImage(long imageKHRPtr);
}

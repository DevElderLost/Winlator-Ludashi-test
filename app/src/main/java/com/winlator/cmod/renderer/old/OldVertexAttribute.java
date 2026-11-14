package com.winlator.cmod.renderer.old;

import android.opengl.GLES20;

import java.nio.Buffer;
import java.nio.FloatBuffer;

/** Port dari VertexAttribute experimental-v2. */
public class OldVertexAttribute {
    private Buffer buffer;
    private int bufferId = 0;
    private int location = -1;
    private final String name;
    private final byte itemSize;
    private boolean needsUpdate = true;

    public OldVertexAttribute(String name, int itemSize) {
        this.name = name;
        this.itemSize = (byte) itemSize;
    }

    public void put(float[] array) {
        buffer = FloatBuffer.wrap(array);
        needsUpdate = true;
    }

    private void update() {
        if (!needsUpdate || buffer == null) return;
        if (bufferId == 0) {
            int[] bufferIds = new int[1];
            GLES20.glGenBuffers(1, bufferIds, 0);
            bufferId = bufferIds[0];
        }

        int size = buffer.limit() * 4;
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, bufferId);
        GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, size, buffer, GLES20.GL_STATIC_DRAW);
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0);
        needsUpdate = false;
    }

    public void bind(int programId) {
        update();
        // Lokasi atribut bisa berbeda per program; hitung ulang tiap bind agar aman dipakai
        // bersama oleh WindowMaterial dan CursorMaterial.
        location = GLES20.glGetAttribLocation(programId, name);
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, bufferId);
        GLES20.glEnableVertexAttribArray(location);
        GLES20.glVertexAttribPointer(location, itemSize, GLES20.GL_FLOAT, false, 0, 0);
    }

    public void disable() {
        if (location == -1) return;
        GLES20.glDisableVertexAttribArray(location);
    }

    public void destroy() {
        if (buffer != null) {
            buffer.limit(0);
            buffer = null;
        }
        if (bufferId > 0) {
            GLES20.glDeleteBuffers(1, new int[]{bufferId}, 0);
            bufferId = 0;
        }
        location = -1;
        needsUpdate = true;
    }

    public int count() {
        return buffer != null ? buffer.limit() / itemSize : 0;
    }
}

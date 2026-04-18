package com.winlator.cmod.xserver.extensions;

import android.util.Log;

import com.winlator.cmod.xconnector.XInputStream;
import com.winlator.cmod.xconnector.XOutputStream;
import com.winlator.cmod.xconnector.XStreamLock;
import com.winlator.cmod.xserver.Cursor;
import com.winlator.cmod.xserver.Window;
import com.winlator.cmod.xserver.XClient;
import com.winlator.cmod.xserver.XLock;
import com.winlator.cmod.xserver.XServer;
import com.winlator.cmod.xserver.errors.XRequestError;

import java.io.IOException;

/**
 * Implementasi extension XFixes untuk Winlator X server.
 *
 * Menangani XFixesHideCursor dan XFixesShowCursor yang dikirim game/Wine
 * ketika mereka ingin menyembunyikan cursor sistem dan menggantinya dengan
 * cursor bawaan mereka sendiri (misal FPS game dengan crosshair).
 *
 * Tanpa extension ini, request hide cursor dari game diabaikan diam-diam
 * sehingga cursor Winlator tetap tampil di atas cursor bawaan game.
 */
public class XFixesExtension implements Extension {

    public static final byte MAJOR_OPCODE = -105;

    // Minor opcode XFixes sesuai spesifikasi protokol xfixes
    private static final int X_XFIXES_QUERY_VERSION = 0;
    private static final int X_XFIXES_HIDE_CURSOR   = 29;
    private static final int X_XFIXES_SHOW_CURSOR   = 30;

    // Versi XFixes yang kita klaim support (5.0 — mencakup HideCursor/ShowCursor)
    private static final int XFIXES_MAJOR_VERSION = 5;
    private static final int XFIXES_MINOR_VERSION = 0;

    @Override
    public String getName() {
        return "XFIXES";
    }

    @Override
    public byte getMajorOpcode() {
        return MAJOR_OPCODE;
    }

    @Override
    public byte getFirstErrorId() {
        return 0;
    }

    @Override
    public byte getFirstEventId() {
        return 0;
    }

    @Override
    public void handleRequest(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException, XRequestError {
        int minorOpcode = client.getRequestData() & 0xFF;

        switch (minorOpcode) {
            case X_XFIXES_QUERY_VERSION:
                handleQueryVersion(client, inputStream, outputStream);
                break;
            case X_XFIXES_HIDE_CURSOR:
                handleHideCursor(client, inputStream, outputStream);
                break;
            case X_XFIXES_SHOW_CURSOR:
                handleShowCursor(client, inputStream, outputStream);
                break;
            default:
                client.skipRequest();
                Log.d("XFixesExtension", "Unhandled XFixes minor opcode: " + minorOpcode);
                break;
        }
    }

    /**
     * XFixesQueryVersion — client mengirim versi yang diinginkan,
     * server membalas dengan versi yang disupport.
     * Wajib diimplementasi agar client tidak abort setelah QueryExtension.
     */
    private void handleQueryVersion(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException {
        int clientMajor = inputStream.readInt();
        int clientMinor = inputStream.readInt();

        Log.d("XFixesExtension", "QueryVersion: client wants " + clientMajor + "." + clientMinor);

        try (XStreamLock lock = outputStream.lock()) {
            outputStream.writeByte((byte) 1);            // reply code
            outputStream.writeByte((byte) 0);            // unused
            outputStream.writeShort(client.getSequenceNumber());
            outputStream.writeInt(0);                    // reply length
            outputStream.writeInt(XFIXES_MAJOR_VERSION);
            outputStream.writeInt(XFIXES_MINOR_VERSION);
            outputStream.writePad(16);                   // unused padding
        }
    }

    /**
     * XFixesHideCursor — game meminta cursor disembunyikan pada window tertentu.
     * Cursor ditandai forceHidden = true sehingga GLRenderer tidak merendernya,
     * termasuk tidak jatuh ke fallback rootCursorDrawable.
     */
    private void handleHideCursor(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException {
        int windowId = inputStream.readInt();

        try (XLock lock = client.xServer.lock(XServer.Lockable.WINDOW_MANAGER, XServer.Lockable.CURSOR_MANAGER)) {
            Window window = client.xServer.windowManager.getWindow(windowId);
            if (window != null) {
                Cursor cursor = window.attributes.getCursor();
                if (cursor != null) {
                    cursor.setForceHidden(true);
                } else {
                    // Tidak ada cursor eksplisit di window — sembunyikan cursor default renderer
                    if (client.xServer.getRenderer() != null) {
                        client.xServer.getRenderer().setCursorVisible(false);
                    }
                }
                Log.d("XFixesExtension", "HideCursor on window " + windowId);
            }
        }

        if (client.xServer.getRenderer() != null) {
            client.xServer.getRenderer().xServerView.requestRender();
        }
    }

    /**
     * XFixesShowCursor — game meminta cursor dikembalikan ke kondisi visible.
     */
    private void handleShowCursor(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException {
        int windowId = inputStream.readInt();

        try (XLock lock = client.xServer.lock(XServer.Lockable.WINDOW_MANAGER, XServer.Lockable.CURSOR_MANAGER)) {
            Window window = client.xServer.windowManager.getWindow(windowId);
            if (window != null) {
                Cursor cursor = window.attributes.getCursor();
                if (cursor != null) {
                    cursor.setForceHidden(false);
                } else {
                    if (client.xServer.getRenderer() != null) {
                        client.xServer.getRenderer().setCursorVisible(true);
                    }
                }
                Log.d("XFixesExtension", "ShowCursor on window " + windowId);
            }
        }

        if (client.xServer.getRenderer() != null) {
            client.xServer.getRenderer().xServerView.requestRender();
        }
    }
}

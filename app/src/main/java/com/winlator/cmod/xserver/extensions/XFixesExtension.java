package com.winlator.cmod.xserver.extensions;

import android.util.Log;

import com.winlator.cmod.xconnector.XInputStream;
import com.winlator.cmod.xconnector.XOutputStream;
import com.winlator.cmod.xserver.Cursor;
import com.winlator.cmod.xserver.Window;
import com.winlator.cmod.xserver.XClient;
import com.winlator.cmod.xserver.XLock;
import com.winlator.cmod.xserver.XServer;

import java.io.IOException;

/**
 * Implementasi extension XFixes untuk Winlator X server.
 *
 * Extension ini menangani request XFixesHideCursor dan XFixesShowCursor
 * yang dikirim oleh game/aplikasi Wine ketika mereka ingin menyembunyikan
 * cursor sistem dan menggantinya dengan cursor bawaan mereka sendiri.
 *
 * Tanpa extension ini, request hide cursor dari game diabaikan diam-diam,
 * sehingga cursor Winlator tetap tampil di atas cursor bawaan game.
 *
 * Referensi protokol: xfixes.h (X.Org), minor opcode 29 = HideCursor, 30 = ShowCursor.
 */
public class XFixesExtension extends Extension {

    // Opcode major XFixes — ditetapkan saat QueryExtension, nilainya negatif
    // karena di Winlator extension opcode disimpan sebagai byte (signed).
    // Nilai aktual dinegosiasikan saat runtime; kita daftarkan di slot yang
    // belum dipakai extension lain. Nilai -4 dipakai karena BigReq=-1,
    // MITSHM=-2, DRI3=-3, Present sudah ada, Sync sudah ada.
    // Jika ada konflik, sesuaikan dengan daftar extension yang terdaftar di XServer.
    public static final byte MAJOR_OPCODE = -105;

    // Minor opcode XFixes sesuai spesifikasi protokol xfixes
    private static final int X_XFIXES_QUERY_VERSION  = 0;
    private static final int X_XFIXES_HIDE_CURSOR    = 29;
    private static final int X_XFIXES_SHOW_CURSOR    = 30;

    // Versi XFixes yang kita klaim support (5.0 — mencakup HideCursor/ShowCursor)
    private static final int XFIXES_MAJOR_VERSION = 5;
    private static final int XFIXES_MINOR_VERSION = 0;

    @Override
    public String getName() {
        return "XFIXES";
    }

    @Override
    public void handleRequest(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException {
        // Minor opcode ada di requestData yang sudah dibaca sebelum handleRequest dipanggil
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
                // Request XFixes lain yang belum diimplementasi — skip saja
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
        // Baca versi yang diminta client (masing-masing 4 byte)
        int clientMajor = inputStream.readInt();
        int clientMinor = inputStream.readInt();

        Log.d("XFixesExtension", "QueryVersion: client wants " + clientMajor + "." + clientMinor);

        try (com.winlator.cmod.xconnector.XStreamLock lock = outputStream.lock()) {
            outputStream.writeByte((byte) 1);           // reply
            outputStream.writeByte((byte) 0);           // unused
            outputStream.writeShort(client.getSequenceNumber());
            outputStream.writeInt(0);                   // reply length (0 = 32 bytes)
            outputStream.writeInt(XFIXES_MAJOR_VERSION);
            outputStream.writeInt(XFIXES_MINOR_VERSION);
            outputStream.writePad(16);                  // unused padding
        }
    }

    /**
     * XFixesHideCursor — game meminta cursor disembunyikan pada window tertentu.
     *
     * Kita ambil cursor yang sedang aktif di window tersebut dan tandai
     * sebagai forceHidden = true sehingga GLRenderer tidak merendernya.
     * rootCursorDrawable fallback juga tidak akan dirender karena
     * pengecekan isForceHidden() ada sebelum pengecekan cursor == null.
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
                    // Belum ada cursor eksplisit di window ini — tandai di renderer langsung
                    // agar cursor default (rootCursorDrawable) juga tidak tampil.
                    // Kita gunakan flag global di renderer sebagai fallback.
                    if (client.xServer.getRenderer() != null) {
                        client.xServer.getRenderer().setCursorVisible(false);
                    }
                }
                Log.d("XFixesExtension", "HideCursor on window " + windowId);
            }

            // XFixes HideCursor tidak mengirim reply — hanya request satu arah
        }

        if (client.xServer.getRenderer() != null) {
            client.xServer.getRenderer().xServerView.requestRender();
        }
    }

    /**
     * XFixesShowCursor — game meminta cursor dikembalikan ke kondisi visible.
     * Kebalikan dari HideCursor.
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
                    // Kembalikan cursor default renderer
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

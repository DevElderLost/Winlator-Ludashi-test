package com.winlator.cmod.xserver.requests;

import static com.winlator.cmod.xserver.XClientRequestHandler.RESPONSE_CODE_SUCCESS;

import com.winlator.cmod.xconnector.XInputStream;
import com.winlator.cmod.xconnector.XOutputStream;
import com.winlator.cmod.xconnector.XStreamLock;
import com.winlator.cmod.xserver.Cursor;
import com.winlator.cmod.xserver.Pixmap;
import com.winlator.cmod.xserver.XClient;
import com.winlator.cmod.xserver.XLock;
import com.winlator.cmod.xserver.XServer;
import com.winlator.cmod.xserver.errors.BadIdChoice;
import com.winlator.cmod.xserver.errors.BadMatch;
import com.winlator.cmod.xserver.errors.BadPixmap;
import com.winlator.cmod.xserver.errors.XRequestError;

import java.io.IOException;

public abstract class CursorRequests {

    /**
     * CREATE_CURSOR (opcode 93) — cursor X11 klasik, 1-bit source + 1-bit mask.
     * Warna cursor ditentukan oleh foreColor/backColor.
     */
    public static void createCursor(XClient client, XInputStream inputStream,
                                    XOutputStream outputStream) throws XRequestError {
        int cursorId      = inputStream.readInt();
        int sourcePixmapId = inputStream.readInt();
        int maskPixmapId  = inputStream.readInt();

        if (!client.isValidResourceId(cursorId)) throw new BadIdChoice(cursorId);

        Pixmap sourcePixmap = client.xServer.pixmapManager.getPixmap(sourcePixmapId);
        if (sourcePixmap == null) throw new BadPixmap(sourcePixmapId);

        Pixmap maskPixmap = client.xServer.pixmapManager.getPixmap(maskPixmapId);
        if (maskPixmap != null && (
            maskPixmap.drawable.visual.depth != 1 ||
            maskPixmap.drawable.width  != sourcePixmap.drawable.width ||
            maskPixmap.drawable.height != sourcePixmap.drawable.height)) {
            throw new BadMatch();
        }

        byte foreRed   = (byte) inputStream.readShort();
        byte foreGreen = (byte) inputStream.readShort();
        byte foreBlue  = (byte) inputStream.readShort();
        byte backRed   = (byte) inputStream.readShort();
        byte backGreen = (byte) inputStream.readShort();
        byte backBlue  = (byte) inputStream.readShort();
        short x = inputStream.readShort();
        short y = inputStream.readShort();

        Cursor cursor = client.xServer.cursorManager.createCursor(
            cursorId, x, y, sourcePixmap, maskPixmap);
        if (cursor == null) throw new BadIdChoice(cursorId);

        // recolorCursor akan skip otomatis jika cursor.isArgb == true
        client.xServer.cursorManager.recolorCursor(
            cursor, foreRed, foreGreen, foreBlue, backRed, backGreen, backBlue);
        client.registerAsOwnerOfResource(cursor);
    }

    /**
     * CREATE_CURSOR_FROM_XCURSOR — cursor ARGB 32-bit dari theme Xcursor.
     *
     * Dipanggil oleh handler XRenderCreateCursor atau saat Wine meminta
     * cursor dari /usr/share/icons/<theme>/cursors/<n>.
     *
     * Format stream:
     *   cursorId (4), cursorName length (4), cursorName (UTF-8, padded ke 4 byte)
     */
    public static void createCursorFromXcursor(XClient client, XInputStream inputStream,
                                               XOutputStream outputStream) throws IOException, XRequestError {
        int cursorId = inputStream.readInt();
        if (!client.isValidResourceId(cursorId)) throw new BadIdChoice(cursorId);

        int nameLen = inputStream.readInt();
        byte[] nameBytes = new byte[nameLen];
        inputStream.read(nameBytes);
        inputStream.readPad(-nameLen & 3); // padding ke 4 byte
        String cursorName = new String(nameBytes, "UTF-8").trim();

        try (XLock lock = client.xServer.lock(XServer.Lockable.CURSOR_MANAGER)) {
            Cursor cursor = client.xServer.cursorManager.createXcursorFromTheme(cursorId, cursorName);
            if (cursor == null) throw new BadIdChoice(cursorId);
            client.registerAsOwnerOfResource(cursor);
        }
    }

    /**
     * RECOLOR_CURSOR (opcode 96) — ubah warna cursor X11 klasik.
     * Cursor ARGB tidak terpengaruh (recolorCursor() skip otomatis).
     */
    public static void recolorCursor(XClient client, XInputStream inputStream,
                                     XOutputStream outputStream) throws XRequestError {
        int cursorId = inputStream.readInt();
        Cursor cursor = client.xServer.cursorManager.getCursor(cursorId);
        if (cursor == null) return;

        byte foreRed   = (byte) inputStream.readShort();
        byte foreGreen = (byte) inputStream.readShort();
        byte foreBlue  = (byte) inputStream.readShort();
        byte backRed   = (byte) inputStream.readShort();
        byte backGreen = (byte) inputStream.readShort();
        byte backBlue  = (byte) inputStream.readShort();

        client.xServer.cursorManager.recolorCursor(
            cursor, foreRed, foreGreen, foreBlue, backRed, backGreen, backBlue);
    }

    /**
     * FREE_CURSOR (opcode 95).
     */
    public static void freeCursor(XClient client, XInputStream inputStream,
                                  XOutputStream outputStream) throws XRequestError {
        client.xServer.cursorManager.freeCursor(inputStream.readInt());
    }

    /**
     * GET_POINTER_MAPPING (opcode 116).
     */
    public static void getPointerMaping(XClient client, XInputStream inputStream,
                                        XOutputStream outputStream) throws IOException, XRequestError {
        try (XStreamLock lock = outputStream.lock()) {
            byte[] buttonsMap = {1, 2, 3};
            byte n      = (byte) buttonsMap.length;
            int padLen  = -n & 3;

            outputStream.writeByte(RESPONSE_CODE_SUCCESS);
            outputStream.writeByte(n);
            outputStream.writeShort(client.getSequenceNumber());
            outputStream.writeInt((n + padLen) / 4);
            outputStream.writePad(24);

            for (byte b : buttonsMap) outputStream.writeByte(b);
            outputStream.writePad(padLen);
        }
    }
}

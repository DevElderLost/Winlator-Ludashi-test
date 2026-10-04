package com.winlator.cmod.droiddeck;

import com.winlator.cmod.xserver.XKeycode;

/** Kode evdev Linux -> XKeycode Winlator (keycode X11 = evdev + 8). */
public final class DDEvdev {
    private static final XKeycode[] TABLE = new XKeycode[256];

    static {
        for (XKeycode k : XKeycode.values()) {
            if (k == XKeycode.KEY_NONE || k == XKeycode.KEY_MAX) continue;
            int id = k.id & 0xFF;
            if (id >= 8) TABLE[id - 8] = k;
        }
    }

    private DDEvdev() {}

    /** @return XKeycode, atau null bila Winlator tidak punya tombol itu (mis. Super). */
    public static XKeycode fromEvdev(int code) {
        return code >= 0 && code < TABLE.length ? TABLE[code] : null;
    }
}

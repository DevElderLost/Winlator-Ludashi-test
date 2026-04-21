package com.winlator.cmod.contentdialog;

import android.content.Context;
import android.content.SharedPreferences;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.preference.PreferenceManager;

import com.winlator.cmod.R;
import com.winlator.cmod.renderer.GLRenderer;
import com.winlator.cmod.xserver.Cursor;
import com.winlator.cmod.xserver.Window;
import com.winlator.cmod.xserver.XLock;
import com.winlator.cmod.xserver.XServer;

public class CursorPositionDialog extends ContentDialog {
    private final GLRenderer glRenderer;
    private final XServer xServer;
    private CursorPositionView cursorPositionView;
    private int cursorWidth, cursorHeight;
    private float offsetScale = 1.0f;

    private static final float SCALE_MIN     = 0.5f;
    private static final float SCALE_MAX     = 4.0f;
    private static final float SCALE_DEFAULT = 1.0f;

    public CursorPositionDialog(@NonNull Context context, GLRenderer renderer, XServer xServer) {
        super(context, R.layout.dialog_cursor_position);
        this.glRenderer = renderer;
        this.xServer    = xServer;
        init();
    }

    private void init() {
        cursorPositionView = findViewById(R.id.cursorPositionView);

        // Terapkan tema
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(getContext());
        boolean isDarkMode = prefs.getBoolean("dark_mode", false);
        cursorPositionView.updateTheme(isDarkMode);

        // Listener offset (saat lingkaran digeser)
        cursorPositionView.setOnOffsetChangedListener((relX, relY) ->
                updateOffsetToRenderer(relX, relY));

        // Listener skala (saat seekbar digeser)
        cursorPositionView.setOnScaleChangedListener(scale -> {
            offsetScale = scale;
            updateOffsetToRenderer(
                    cursorPositionView.getOffsetRelativeX(),
                    cursorPositionView.getOffsetRelativeY());
        });

        // Sembunyikan BTCancel saja — BTConfirm dipakai untuk menyimpan posisi
        findViewById(R.id.BTCancel).setVisibility(View.GONE);

        // Tampilkan BTConfirm untuk menyimpan offset ke SharedPreferences
        View btConfirm = findViewById(R.id.BTConfirm);
        btConfirm.setVisibility(View.VISIBLE);
        btConfirm.setOnClickListener(v -> {
            // Simpan offset saat ini ke SharedPreferences agar persisten
            glRenderer.saveCursorHotspotToPrefs();
            dismiss();
        });

        // Tampilkan BTReset dan posisikan di tengah bottom bar
        View btReset = findViewById(R.id.BTReset);
        btReset.setVisibility(View.VISIBLE);

        btReset.setOnClickListener(v -> {
            offsetScale = SCALE_DEFAULT;
            cursorPositionView.resetToCenter();
            cursorPositionView.setScaleProgress(progressFromScale(SCALE_DEFAULT));
            // Reset nilai di renderer
            glRenderer.setCursorHotspotOffset(0, 0);
            // Langsung tulis nilai reset (0,0) ke SharedPreferences
            glRenderer.saveCursorHotspotToPrefs();
        });

        // Dapatkan ukuran kursor saat ini
        Cursor currentCursor = getCurrentCursor();
        if (currentCursor != null) {
            cursorWidth  = currentCursor.cursorImage.width;
            cursorHeight = currentCursor.cursorImage.height;
        } else {
            cursorWidth  = 32;
            cursorHeight = 32;
        }

        // Ambil offset yang sudah tersimpan di renderer, konversi ke posisi relatif (0..1)
        int offsetX = glRenderer.getCursorHotspotOffsetX();
        int offsetY = glRenderer.getCursorHotspotOffsetY();

        float rawRelX = (offsetX + cursorWidth  / 2f) / cursorWidth;
        float rawRelY = (offsetY + cursorHeight / 2f) / cursorHeight;
        final float relX = Math.max(0f, Math.min(1f, rawRelX));
        final float relY = Math.max(0f, Math.min(1f, rawRelY));

        // Set posisi awal setelah view selesai di-layout
        cursorPositionView.post(() -> {
            cursorPositionView.setOffsetRelative(relX, relY);
            cursorPositionView.setScaleProgress(progressFromScale(SCALE_DEFAULT));
        });

        setTitle(R.string.cursor_hotspot_offset);
    }

    private void updateOffsetToRenderer(float relX, float relY) {
        int newOffsetX = (int) ((relX * cursorWidth  - cursorWidth  / 2f) * offsetScale);
        int newOffsetY = (int) ((relY * cursorHeight - cursorHeight / 2f) * offsetScale);
        glRenderer.setCursorHotspotOffset(newOffsetX, newOffsetY);
    }

    private int progressFromScale(float scale) {
        float t = (scale - SCALE_MIN) / (SCALE_MAX - SCALE_MIN);
        return Math.round(t * 100);
    }

    private Cursor getCurrentCursor() {
        try (XLock lock = xServer.lock(XServer.Lockable.INPUT_DEVICE)) {
            Window pointWindow = xServer.inputDeviceManager.getPointWindow();
            if (pointWindow != null) {
                return pointWindow.attributes.getCursor();
            }
        }
        return null;
    }
}

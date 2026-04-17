package com.winlator.cmod.contentdialog;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.PorterDuff;
import android.view.View;
import android.widget.ImageButton;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
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

    private static final float SCALE_MIN = 0.5f;
    private static final float SCALE_MAX = 2.0f;
    private static final float SCALE_DEFAULT = 1.0f;

    public CursorPositionDialog(@NonNull Context context, GLRenderer renderer, XServer xServer) {
        super(context, R.layout.dialog_cursor_position);
        this.glRenderer = renderer;
        this.xServer = xServer;
        init();
    }

    private void init() {
        cursorPositionView = findViewById(R.id.cursorPositionView);

        // Terapkan tema
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(getContext());
        boolean isDarkMode = prefs.getBoolean("dark_mode", false);
        cursorPositionView.updateTheme(isDarkMode);

        // Setup listener offset
        cursorPositionView.setOnOffsetChangedListener((relX, relY) -> {
            updateOffsetToRenderer(relX, relY);
        });

        // Setup listener skala
        cursorPositionView.setOnScaleChangedListener(scale -> {
            offsetScale = scale;
            // Update offset dengan skala baru
            updateOffsetToRenderer(cursorPositionView.getOffsetRelativeX(),
                                   cursorPositionView.getOffsetRelativeY());
        });

        // Setup callback reset
        cursorPositionView.setOnResetCallback(() -> {
            offsetScale = SCALE_DEFAULT;
            cursorPositionView.setScaleProgress(progressFromScale(SCALE_DEFAULT));
            glRenderer.setCursorHotspotOffset(0, 0);
        });

        // Dapatkan ukuran kursor
        Cursor currentCursor = getCurrentCursor();
        if (currentCursor != null) {
            cursorWidth = currentCursor.cursorImage.width;
            cursorHeight = currentCursor.cursorImage.height;
        } else {
            cursorWidth = 32;
            cursorHeight = 32;
        }

        // Ambil offset saat ini dari renderer, konversi ke relatif (0..1)
        int offsetX = glRenderer.getCursorHotspotOffsetX();
        int offsetY = glRenderer.getCursorHotspotOffsetY();

        float relX = (offsetX + cursorWidth / 2f) / cursorWidth;
        float relY = (offsetY + cursorHeight / 2f) / cursorHeight;
        relX = Math.max(0f, Math.min(1f, relX));
        relY = Math.max(0f, Math.min(1f, relY));

        // Set posisi awal
        cursorPositionView.post(() -> {
            cursorPositionView.setOffsetRelative(relX, relY);
            // Progress awal sesuai skala default
            cursorPositionView.setScaleProgress(progressFromScale(SCALE_DEFAULT));
        });

        setTitle("Cursor Hotspot Offset");
    }

    private void updateOffsetToRenderer(float relX, float relY) {
        int newOffsetX = (int) ((relX * cursorWidth - cursorWidth / 2f) * offsetScale);
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
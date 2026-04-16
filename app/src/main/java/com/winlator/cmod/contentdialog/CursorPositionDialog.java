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

    public CursorPositionDialog(@NonNull Context context, GLRenderer renderer, XServer xServer) {
        super(context, R.layout.dialog_cursor_position);
        this.glRenderer = renderer;
        this.xServer = xServer;
        init();
    }

    private void init() {
        cursorPositionView = findViewById(R.id.cursorPositionView);

        // Terapkan tema ke view
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(getContext());
        boolean isDarkMode = prefs.getBoolean("dark_mode", false);
        cursorPositionView.updateTheme(isDarkMode);

        // Tombol reset di pojok kanan atas (ImageButton) dengan tint colorPrimary
        ImageButton resetButton = findViewById(R.id.BTReset);
        if (resetButton != null) {
            resetButton.setColorFilter(
                ContextCompat.getColor(getContext(), R.color.colorPrimary),
                PorterDuff.Mode.SRC_IN
            );
            resetButton.setOnClickListener(v -> {
                glRenderer.setCursorHotspotOffset(0, 0);
                cursorPositionView.resetToCenter();
            });
        }

        // Dapatkan ukuran kursor saat ini
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

        final float finalRelX = relX;
        final float finalRelY = relY;
        cursorPositionView.post(() -> cursorPositionView.setOffsetRelative(finalRelX, finalRelY));

        cursorPositionView.setOnOffsetChangedListener((relX2, relY2) -> {
            int newOffsetX = (int) (relX2 * cursorWidth - cursorWidth / 2f);
            int newOffsetY = (int) (relY2 * cursorHeight - cursorHeight / 2f);
            glRenderer.setCursorHotspotOffset(newOffsetX, newOffsetY);
        });

        setTitle("Cursor Hotspot Offset");
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
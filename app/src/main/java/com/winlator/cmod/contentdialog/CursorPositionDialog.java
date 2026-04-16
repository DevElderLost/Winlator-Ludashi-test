package com.winlator.cmod.contentdialog;

import android.content.Context;
import android.view.View;
import android.widget.ImageButton;

import androidx.annotation.NonNull;

import com.winlator.cmod.R;
import com.winlator.cmod.renderer.GLRenderer;
import com.winlator.cmod.xserver.Cursor;
import com.winlator.cmod.xserver.Window;
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

        // Sembunyikan tombol bawaan (Confirm, Cancel, Neutral) jika tidak diperlukan
        findViewById(R.id.BTConfirm).setVisibility(View.GONE);
        findViewById(R.id.BTCancel).setVisibility(View.GONE);
        View neutral = findViewById(R.id.BTNeutral);
        if (neutral != null) neutral.setVisibility(View.GONE);

        // Dapatkan ukuran kursor saat ini
        Cursor currentCursor = getCurrentCursor();
        if (currentCursor != null) {
            cursorWidth = currentCursor.cursorImage.width;
            cursorHeight = currentCursor.cursorImage.height;
        } else {
            // fallback ukuran default root cursor (misal 32x32)
            cursorWidth = 32;
            cursorHeight = 32;
        }

        // Ambil offset saat ini dari renderer, konversi ke relatif (0..1)
        int offsetX = glRenderer.getCursorHotspotOffsetX();
        int offsetY = glRenderer.getCursorHotspotOffsetY();
        // Offset dihitung dari pusat gambar; relatif 0.5 = offset 0
        float relX = (offsetX + cursorWidth / 2f) / cursorWidth;
        float relY = (offsetY + cursorHeight / 2f) / cursorHeight;
        relX = Math.max(0f, Math.min(1f, relX));
        relY = Math.max(0f, Math.min(1f, relY));

        cursorPositionView.post(() -> cursorPositionView.setOffsetRelative(relX, relY));

        cursorPositionView.setOnOffsetChangedListener((relX2, relY2) -> {
            // Konversi ke offset piksel (dari pusat kursor)
            int newOffsetX = (int) (relX2 * cursorWidth - cursorWidth / 2f);
            int newOffsetY = (int) (relY2 * cursorHeight - cursorHeight / 2f);
            glRenderer.setCursorHotspotOffset(newOffsetX, newOffsetY);
        });

        // Tombol reset dengan ImageButton dari layout
        ImageButton resetButton = findViewById(R.id.BTReset);
        resetButton.setOnClickListener(v -> {
            glRenderer.setCursorHotspotOffset(0, 0);
            cursorPositionView.setOffsetRelative(0.5f, 0.5f);
        });

        setTitle("Cursor Hotspot Offset");
    }

    private Cursor getCurrentCursor() {
        try (com.winlator.cmod.xserver.XLock lock = xServer.lock(XServer.Lockable.INPUT_DEVICE_MANAGER)) {
            Window pointWindow = xServer.inputDeviceManager.getPointWindow();
            if (pointWindow != null) {
                return pointWindow.attributes.getCursor();
            }
        }
        return null;
    }
}
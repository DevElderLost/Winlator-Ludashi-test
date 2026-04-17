package com.winlator.cmod.contentdialog;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.PorterDuff;
import android.view.View;
import android.widget.ImageButton;
import android.widget.SeekBar;

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
    private SeekBar seekBarOffsetScale;
    private int cursorWidth, cursorHeight;

    private float offsetScale = 1.0f;
    private static final String PREF_CURSOR_OFFSET_SCALE = "cursor_offset_scale";

    public CursorPositionDialog(@NonNull Context context, GLRenderer renderer, XServer xServer) {
        super(context, R.layout.dialog_cursor_position);
        this.glRenderer = renderer;
        this.xServer = xServer;
        init();
    }

    private void init() {
        cursorPositionView = findViewById(R.id.cursorPositionView);
        seekBarOffsetScale = findViewById(R.id.seekBarOffsetScale);

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
                // Reset juga skala ke default 1.0 jika diinginkan
                // (opsional, bisa dikomentari)
                offsetScale = 1.0f;
                seekBarOffsetScale.setProgress(10);
                applyCurrentOffsetWithScale();
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

        // Muat nilai skala yang tersimpan
        float savedScale = prefs.getFloat(PREF_CURSOR_OFFSET_SCALE, 1.0f);
        offsetScale = savedScale;
        seekBarOffsetScale.setProgress((int) (savedScale * 10));

        // Ambil offset saat ini dari renderer
        int offsetX = glRenderer.getCursorHotspotOffsetX();
        int offsetY = glRenderer.getCursorHotspotOffsetY();

        // Konversi ke relatif dengan memperhitungkan skala
        float baseOffsetX = offsetX / offsetScale;
        float baseOffsetY = offsetY / offsetScale;
        float relX = (baseOffsetX + cursorWidth / 2f) / cursorWidth;
        float relY = (baseOffsetY + cursorHeight / 2f) / cursorHeight;
        relX = Math.max(0f, Math.min(1f, relX));
        relY = Math.max(0f, Math.min(1f, relY));

        final float finalRelX = relX;
        final float finalRelY = relY;
        cursorPositionView.post(() -> cursorPositionView.setOffsetRelative(finalRelX, finalRelY));

        // Listener saat posisi lingkaran diubah oleh user
        cursorPositionView.setOnOffsetChangedListener((relX2, relY2) -> applyCurrentOffsetWithScale());

        // Listener SeekBar untuk skala
        seekBarOffsetScale.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                offsetScale = progress / 10.0f;
                applyCurrentOffsetWithScale();
                // Simpan nilai skala
                prefs.edit().putFloat(PREF_CURSOR_OFFSET_SCALE, offsetScale).apply();
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {}

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {}
        });

        setTitle("Cursor Hotspot Offset");
    }

    /**
     * Menghitung offset piksel berdasarkan posisi relatif dan skala,
     * lalu mengirimkannya ke GLRenderer.
     */
    private void applyCurrentOffsetWithScale() {
        float relX = cursorPositionView.getOffsetRelativeX();
        float relY = cursorPositionView.getOffsetRelativeY();

        // Hitung offset piksel dasar (tanpa skala)
        int baseOffsetX = (int) (relX * cursorWidth - cursorWidth / 2f);
        int baseOffsetY = (int) (relY * cursorHeight - cursorHeight / 2f);

        // Terapkan skala
        int newOffsetX = (int) (baseOffsetX * offsetScale);
        int newOffsetY = (int) (baseOffsetY * offsetScale);

        glRenderer.setCursorHotspotOffset(newOffsetX, newOffsetY);
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
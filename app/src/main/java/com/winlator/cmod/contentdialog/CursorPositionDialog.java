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
    private SeekBar sbScale;
    private int cursorWidth, cursorHeight;
    private float offsetScale = 1.0f;

    // Rentang skala: 0.5 sampai 2.0
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
        sbScale = findViewById(R.id.SBScale);

        // Terapkan tema ke view
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(getContext());
        boolean isDarkMode = prefs.getBoolean("dark_mode", false);
        cursorPositionView.updateTheme(isDarkMode);

        // Tombol reset dengan tint colorPrimary (background sudah transparan via Borderless)
        ImageButton resetButton = findViewById(R.id.BTReset);
        if (resetButton != null) {
            resetButton.setColorFilter(
                ContextCompat.getColor(getContext(), R.color.colorPrimary),
                PorterDuff.Mode.SRC_IN
            );
            resetButton.setOnClickListener(v -> {
                // Reset posisi ke tengah dan skala ke default
                offsetScale = SCALE_DEFAULT;
                updateSeekBarFromScale();
                cursorPositionView.resetToCenter();
                // Update offset ke renderer (posisi tengah dengan skala 1.0)
                updateOffsetToRenderer(cursorPositionView.getOffsetRelativeX(),
                                       cursorPositionView.getOffsetRelativeY());
            });
        }

        // Setup SeekBar
        sbScale.setMax(100);
        updateSeekBarFromScale(); // set progress sesuai offsetScale awal
        sbScale.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (!fromUser) return;
                offsetScale = scaleFromProgress(progress);
                updateOffsetToRenderer(cursorPositionView.getOffsetRelativeX(),
                                       cursorPositionView.getOffsetRelativeY());
            }

            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });

        // Dapatkan ukuran kursor saat ini
        Cursor currentCursor = getCurrentCursor();
        if (currentCursor != null) {
            cursorWidth = currentCursor.cursorImage.width;
            cursorHeight = currentCursor.cursorImage.height;
        } else {
            cursorWidth = 32;
            cursorHeight = 32;
        }

        // Ambil offset saat ini dari renderer, konversi ke relatif (0..1) dengan memperhitungkan skala
        int offsetX = glRenderer.getCursorHotspotOffsetX();
        int offsetY = glRenderer.getCursorHotspotOffsetY();

        // Hitung skala dari offset yang tersimpan (jika memungkinkan)
        // Asumsi: offset disimpan tanpa skala? Sebenarnya offsetX/Y adalah hasil dari (rel*size - size/2)*scale.
        // Untuk memulihkan rel, kita perlu scale. Karena kita tidak menyimpan scale, kita set scale default 1.0.
        offsetScale = SCALE_DEFAULT;

        float relX = (offsetX + cursorWidth / 2f) / cursorWidth;
        float relY = (offsetY + cursorHeight / 2f) / cursorHeight;
        relX = Math.max(0f, Math.min(1f, relX));
        relY = Math.max(0f, Math.min(1f, relY));

        final float finalRelX = relX;
        final float finalRelY = relY;
        cursorPositionView.post(() -> {
            cursorPositionView.setOffsetRelative(finalRelX, finalRelY);
            updateOffsetToRenderer(finalRelX, finalRelY); // terapkan ulang dengan scale default
        });

        cursorPositionView.setOnOffsetChangedListener((relX2, relY2) -> {
            updateOffsetToRenderer(relX2, relY2);
        });

        setTitle("Cursor Hotspot Offset");
    }

    private void updateOffsetToRenderer(float relX, float relY) {
        int newOffsetX = (int) ((relX * cursorWidth - cursorWidth / 2f) * offsetScale);
        int newOffsetY = (int) ((relY * cursorHeight - cursorHeight / 2f) * offsetScale);
        glRenderer.setCursorHotspotOffset(newOffsetX, newOffsetY);
    }

    private float scaleFromProgress(int progress) {
        float t = progress / 100f;
        return SCALE_MIN + t * (SCALE_MAX - SCALE_MIN);
    }

    private int progressFromScale(float scale) {
        float t = (scale - SCALE_MIN) / (SCALE_MAX - SCALE_MIN);
        return Math.round(t * 100);
    }

    private void updateSeekBarFromScale() {
        sbScale.setProgress(progressFromScale(offsetScale));
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
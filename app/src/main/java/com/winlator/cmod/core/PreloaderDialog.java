package com.winlator.cmod.core;

import android.app.Activity;
import android.app.Dialog;
import android.content.SharedPreferences;
import android.view.Window;
import android.view.WindowManager;
import android.widget.TextView;

import androidx.preference.PreferenceManager;

import com.winlator.cmod.R;

public class PreloaderDialog {
    private final Activity activity;
    private Dialog dialog;

    public PreloaderDialog(Activity activity) {
        this.activity = activity;
    }

    private boolean isDarkMode() {
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(activity);
        return preferences.getBoolean("dark_mode", false);
    }

    private void create() {
        if (dialog != null) return;
        dialog = new Dialog(activity, android.R.style.Theme_Translucent_NoTitleBar_Fullscreen);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setCancelable(false);
        dialog.setCanceledOnTouchOutside(false);

        // Gunakan layout dark atau default berdasarkan preferensi dark mode
        if (isDarkMode()) {
            dialog.setContentView(R.layout.preloader_dialog_dark);
        } else {
            dialog.setContentView(R.layout.preloader_dialog);
        }

        Window window = dialog.getWindow();
        if (window != null) {
            window.clearFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE);
            window.clearFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE);
        }
    }

    public synchronized void show(int textResId) {
        if (isShowing()) return;
        close();
        if (dialog == null) create();
        ((TextView)dialog.findViewById(R.id.TextView)).setText(textResId);
        dialog.show();
    }

    public void showOnUiThread(final int textResId) {
        activity.runOnUiThread(() -> show(textResId));
    }

    public synchronized void close() {
        try {
            if (dialog != null) {
                dialog.dismiss();
                dialog = null; // Reset agar create() bisa dipanggil ulang dengan tema terbaru
            }
        }
        catch (Exception e) {}
    }

    public void closeOnUiThread() {
        activity.runOnUiThread(this::close);
    }

    public boolean isShowing() {
        return dialog != null && dialog.isShowing();
    }
}

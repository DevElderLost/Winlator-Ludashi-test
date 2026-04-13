package com.winlator.cmod.contentdialog;

import android.content.Context;
import android.graphics.Bitmap;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;

import com.winlator.cmod.R;
import com.winlator.cmod.core.AppUtils;
import com.winlator.cmod.core.ImageUtils;
import com.winlator.cmod.core.UnitUtils;
import com.winlator.cmod.renderer.GLRenderer;
import com.winlator.cmod.xserver.Window;
import com.winlator.cmod.xserver.WindowManager;
import com.winlator.cmod.xserver.XLock;
import com.winlator.cmod.xserver.XServer;

import java.util.ArrayList;
import java.util.List;

public class ActiveWindowsDialog extends ContentDialog {

    private final XServer xServer;
    private final GLRenderer renderer;
    private final List<Window> activeWindows = new ArrayList<>();
    private LinearLayout llWindowList;

    public ActiveWindowsDialog(@NonNull Context context, XServer xServer, GLRenderer renderer) {
        super(context);

        this.xServer = xServer;
        this.renderer = renderer;

        setTitle("Active Windows");

        llWindowList = findViewById(R.id.LLWindowList);

        findViewById(R.id.BTConfirm).setVisibility(View.GONE);

        refreshWindows();
    }

    private void refreshWindows() {
        activeWindows.clear();

        try (XLock lock = xServer.lock(XServer.Lockable.WINDOW_MANAGER, XServer.Lockable.DRAWABLE_MANAGER)) {
            collectMappedWindows(xServer.windowManager.rootWindow, activeWindows);
        }

        loadWindowViews(activeWindows);
    }

    private void collectMappedWindows(Window window, List<Window> result) {
        if (window == null || !window.attributes.isMapped()) return;

        // Skip root window
        if (window != xServer.windowManager.rootWindow) {
            result.add(window);
        }

        for (Window child : window.getChildren()) {
            collectMappedWindows(child, result);
        }
    }

    private void loadWindowViews(List<Window> windows) {
        if (llWindowList == null) return;

        llWindowList.removeAllViews();

        TextView tvEmpty = findViewById(R.id.TVEmptyText);

        if (windows.isEmpty()) {
            if (tvEmpty != null) tvEmpty.setVisibility(View.VISIBLE);
            return;
        }

        if (tvEmpty != null) tvEmpty.setVisibility(View.GONE);

        LayoutInflater inflater = LayoutInflater.from(getContext());
        float iconSize = UnitUtils.dpToPx(24.0f);
        int imageHeight = (int) UnitUtils.dpToPx(116.0f);

        for (int i = windows.size() - 1; i >= 0; i--) {
            Window window = windows.get(i);
            Window parent = window.getParent();

            View itemView = inflater.inflate(R.layout.active_window_list_item, llWindowList, false);

            ImageView ivIcon    = itemView.findViewById(R.id.IVIcon);
            ImageView ivWindow  = itemView.findViewById(R.id.IVWindow);
            ImageView ivDashed  = itemView.findViewById(R.id.IVDashedFrame);
            ImageView ivHidden  = itemView.findViewById(R.id.IVHidden);
            TextView  tvName    = itemView.findViewById(R.id.TVName);
            ImageButton btnClose = itemView.findViewById(R.id.IBtnClose);
            View       cardView  = itemView.findViewById(R.id.LLWindowCard);

            btnClose.ImageViewCompat.setImageTintList(btnClose, ColorStateList.valueOf(R.colors.colorPrimary));

            // --- Judul window ---
            String name = window.getName();
            if (name == null || name.trim().isEmpty()) {
                name = window.getClassName();
            }
            if (name == null || name.trim().isEmpty()) {
                name = "Window " + window.id;
            }
            tvName.setText(name);

            // --- Icon window ---
            Bitmap windowIcon = xServer.pixmapManager.getWindowIcon(window);
            if (windowIcon == null && parent != null) {
                windowIcon = xServer.pixmapManager.getWindowIcon(parent);
            }
            ivIcon.setImageResource(R.drawable.icon_window_default);
            if (windowIcon != null) {
                ivIcon.setImageBitmap(windowIcon);
            }

            // --- Thumbnail ---
            if (!window.isIconic()) {
                Window content = window.getContent();
                if (content != null) {
                    int[] scaledSize = ImageUtils.getScaledSize(
                            (float) content.width, (float) content.height,
                            0.0f, (float) imageHeight);
                    tvName.setMaxWidth((int) (scaledSize[0] - iconSize));
                    ivWindow.setLayoutParams(
                            new android.widget.FrameLayout.LayoutParams(scaledSize[0], scaledSize[1]));
                    renderer.takeWindowScreenshot(content, bitmap -> ivWindow.setImageBitmap(bitmap));
                }
            } else {
                // Window sedang minimize / iconic
                if (ivDashed != null) ivDashed.setVisibility(View.VISIBLE);
                if (ivHidden != null) ivHidden.setVisibility(View.VISIBLE);
                tvName.setMaxWidth((int) (imageHeight - iconSize));
                ivWindow.setLayoutParams(
                        new android.widget.FrameLayout.LayoutParams(imageHeight, imageHeight));
            }

            // --- Listener: klik kartu → bring to front (logika sama seperti sebelumnya) ---
            final Window finalWindow = window;
            cardView.setOnClickListener(v -> bringToFront(finalWindow));
            // Pastikan klik pada area selain tombol X juga trigger bring to front
            itemView.setOnClickListener(v -> bringToFront(finalWindow));

            // --- Listener: tombol X kecil → close window (logika sama seperti sebelumnya) ---
            btnClose.setOnClickListener(v -> closeWindow(finalWindow));

            llWindowList.addView(itemView);
        }
    }

    private void bringToFront(Window window) {
        if (window == null || window == xServer.windowManager.rootWindow) return;

        try (XLock lock = xServer.lock(XServer.Lockable.WINDOW_MANAGER)) {
            xServer.windowManager.changeWindowZOrder(Window.StackMode.ABOVE, window, null);
        }

        if (renderer != null) {
            renderer.updateScene();
            renderer.xServerView.requestRender();
        }

        refreshWindows();
    }

    private void closeWindow(Window window) {
        if (window == null || window == xServer.windowManager.rootWindow) return;

        try (XLock lock = xServer.lock(XServer.Lockable.WINDOW_MANAGER)) {
            xServer.windowManager.unmapWindow(window);
        }

        if (renderer != null) {
            renderer.updateScene();
            renderer.xServerView.requestRender();
        }

        refreshWindows();
    }

    public static void show(Context context, XServer xServer, GLRenderer renderer) {
        new ActiveWindowsDialog(context, xServer, renderer).show();
    }
}

package com.winlator.cmod.contentdialog;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.widget.ImageViewCompat;

import com.winlator.cmod.R;
import com.winlator.cmod.core.UnitUtils;
import com.winlator.cmod.renderer.GLRenderer;
import com.winlator.cmod.xserver.Drawable;
import com.winlator.cmod.xserver.Window;
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
        float iconSize  = UnitUtils.dpToPx(24.0f);
        int imageHeight = (int) UnitUtils.dpToPx(116.0f);

        for (int i = windows.size() - 1; i >= 0; i--) {
            Window window  = windows.get(i);
            Window parent  = window.getParent();

            View        itemView = inflater.inflate(R.layout.active_window_list_item, llWindowList, false);
            ImageView   ivIcon   = itemView.findViewById(R.id.IVIcon);
            ImageView   ivWindow = itemView.findViewById(R.id.IVWindow);
            ImageView   ivDashed = itemView.findViewById(R.id.IVDashedFrame);
            ImageView   ivHidden = itemView.findViewById(R.id.IVHidden);
            TextView    tvName   = itemView.findViewById(R.id.TVName);
            ImageButton btnClose = itemView.findViewById(R.id.IBtnClose);
            View        cardView = itemView.findViewById(R.id.LLWindowCard);

            // --- Judul window ---
            // getName() / getClassName() mengembalikan "" bukan null — cukup isEmpty()
            String name = window.getName();
            if (name.isEmpty()) name = window.getClassName();
            if (name.isEmpty()) name = "Window " + window.id;
            tvName.setText(name);

            // --- Icon window ---
            // Fallback ke icon_hide (drawable yang pasti ada di project)
            ivIcon.setImageResource(R.drawable.icon_hide);
            Bitmap windowIcon = xServer.pixmapManager.getWindowIcon(window);
            if (windowIcon == null && parent != null) {
                windowIcon = xServer.pixmapManager.getWindowIcon(parent);
            }
            if (windowIcon != null) ivIcon.setImageBitmap(windowIcon);

            // --- Tint tombol X: putih ---
            ImageViewCompat.setImageTintList(btnClose,
                    ColorStateList.valueOf(Color.WHITE));

            // --- Thumbnail ---
            // getContent() → Drawable; width/height via getWidth()/getHeight() (private fields)
            // isIconic() tidak ada di Window cmod — deteksi via content null sebagai fallback
            Drawable content = window.getContent();
            if (content != null && content.width > 0 && content.height > 0) {
                // Kalkulasi scaled size inline — ImageUtils.getScaledSize tidak ada di cmod
                int srcW = content.width;
                int srcH = content.height;
                int scaledW, scaledH;
                if (srcW >= srcH) {
                    scaledW = imageHeight;
                    scaledH = Math.max(1, (int) ((float) srcH / srcW * imageHeight));
                } else {
                    scaledH = imageHeight;
                    scaledW = Math.max(1, (int) ((float) srcW / srcH * imageHeight));
                }

                tvName.setMaxWidth((int) (scaledW - iconSize));
                ivWindow.setLayoutParams(new FrameLayout.LayoutParams(scaledW, scaledH));

                // takeWindowScreenshot dipanggil dari UI thread, callback dari GL thread
                // → post ke UI thread sebelum update ImageView
                final ImageView target = ivWindow;
                renderer.takeWindowScreenshot(content, bitmap -> {
                    if (bitmap != null) target.post(() -> target.setImageBitmap(bitmap));
                });
            } else {
                // Tidak ada content → tampilkan placeholder dashed frame
                if (ivDashed != null) ivDashed.setVisibility(View.VISIBLE);
                if (ivHidden != null) ivHidden.setVisibility(View.VISIBLE);
                tvName.setMaxWidth((int) (imageHeight - iconSize));
                ivWindow.setLayoutParams(new FrameLayout.LayoutParams(imageHeight, imageHeight));
            }

            // --- Listeners (logika identik dengan versi ListView semula) ---
            final Window finalWindow = window;
            cardView.setOnClickListener(v -> bringToFront(finalWindow));
            itemView.setOnClickListener(v -> bringToFront(finalWindow));
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

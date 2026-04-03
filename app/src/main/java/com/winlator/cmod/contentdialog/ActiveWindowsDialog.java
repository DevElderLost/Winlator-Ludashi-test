package com.winlator.cmod.contentdialog;

import android.content.Context;
import android.view.View;
import android.widget.ImageButton;
import android.widget.ListView;
import android.widget.TextView;

import androidx.annotation.NonNull;

import com.winlator.cmod.R;
import com.winlator.cmod.core.AppUtils;
import com.winlator.cmod.core.Callback;
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
    private ActiveWindowsAdapter adapter;
    private ListView listView;

    public ActiveWindowsDialog(@NonNull Context context, XServer xServer, GLRenderer renderer) {
        super(context);
        this.xServer = xServer;
        this.renderer = renderer;

        setTitle("Active Windows");

        listView = findViewById(R.id.ListView);
        if (listView != null) {
            listView.getLayoutParams().width = AppUtils.getPreferredDialogWidth(context);
            listView.setVisibility(View.VISIBLE);
        }

        findViewById(R.id.BTConfirm).setVisibility(View.GONE);

        setupAdapter();
        refreshWindows();
    }

    private void setupAdapter() {
        adapter = new ActiveWindowsAdapter(getContext(), activeWindows,
                this::bringToFront,
                this::closeWindow);

        listView.setAdapter(adapter);

        listView.setOnItemClickListener((parent, view, position, id) -> {
            if (position >= 0 && position < activeWindows.size()) {
                bringToFront(activeWindows.get(position));
            }
        });
    }

    private void refreshWindows() {
        activeWindows.clear();

        try (XLock lock = xServer.lock(XServer.Lockable.WINDOW_MANAGER, XServer.Lockable.DRAWABLE_MANAGER)) {
            collectMappedWindows(xServer.windowManager.rootWindow, activeWindows);
        }

        if (adapter != null) {
            adapter.updateWindows(activeWindows);
        }

        setMessage(activeWindows.isEmpty() ? "No active windows found." : "");
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

    // ==================== Adapter ====================
    private static class ActiveWindowsAdapter extends android.widget.BaseAdapter {

        private final Context context;
        private final List<Window> windows;
        private final Callback<Window> onBringToFront;
        private final Callback<Window> onClose;

        ActiveWindowsAdapter(Context context, List<Window> windows,
                             Callback<Window> onBringToFront,
                             Callback<Window> onClose) {
            this.context = context;
            this.windows = new ArrayList<>(windows);
            this.onBringToFront = onBringToFront;
            this.onClose = onClose;
        }

        @Override public int getCount() { return windows.size(); }
        @Override public Window getItem(int position) { return windows.get(position); }
        @Override public long getItemId(int position) { return position; }

        @Override
        public View getView(int position, View convertView, android.view.ViewGroup parent) {
            if (convertView == null) {
                convertView = android.view.LayoutInflater.from(context)
                        .inflate(R.layout.active_window_spinner_item, parent, false);
            }

            Window window = getItem(position);
            TextView tvTitle = convertView.findViewById(R.id.tvWindowTitle);
            ImageButton btnClose = convertView.findViewById(R.id.btnCloseWindow);

            String title = window.getTitle();
            if (title == null || title.trim().isEmpty()) {
                title = window.getClassName();
                if (title == null || title.trim().isEmpty()) {
                    title = "Window #" + window.getId();
                }
            }
            tvTitle.setText(title);

            convertView.setOnClickListener(v -> onBringToFront.call(window));
            btnClose.setOnClickListener(v -> onClose.call(window));
            btnClose.setFocusable(false);

            return convertView;
        }

        void updateWindows(List<Window> newList) {
            windows.clear();
            windows.addAll(newList);
            notifyDataSetChanged();
        }
    }
}
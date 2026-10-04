package com.winlator.cmod.droiddeck;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Color;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.winlator.cmod.R;
import com.winlator.cmod.inputcontrols.GamepadState;
import com.winlator.cmod.winhandler.WinHandler;
import com.winlator.cmod.xserver.XKeycode;
import com.winlator.cmod.xserver.XServer;

/**
 * Perekat mode DroidDeck di dalam Winlator. Default MATI: saat mati tidak ada view
 * yang tampil dan jalur kontrol bawaan Winlator berjalan seperti biasa.
 */
public final class DDController {
    private static final String RAIL_TAG = "dd_rail_item";

    private static Activity activity;
    private static FrameLayout root;
    private static XServer xServer;
    private static View winlatorControls;
    private static DDOnScreenControls controls;
    private static DDPcKeyboardView keyboard;
    private static FrameLayout editor;
    private static int savedWinlatorVisibility = View.GONE;
    private static boolean winlatorHidden = false;

    // ---- DD-UI-SELECT: pilihan UI kontrol per game / per container ----
    private static Boolean uiOverride = null;
    private static Boolean pendingUiOverride = null;

    /** "1" = DroidDeck, "0" = Winlator, lainnya = tidak diatur. Setting game lebih kuat dari container. */
    private static Boolean resolveUiOverride(String shortcutUi, String containerUi) {
        if ("1".equals(shortcutUi)) return Boolean.TRUE;
        if ("0".equals(shortcutUi)) return Boolean.FALSE;
        if ("1".equals(containerUi)) return Boolean.TRUE;
        if ("0".equals(containerUi)) return Boolean.FALSE;
        return null;
    }

    /** Status efektif: pilihan game/container jika ada, selain itu toggle global DDPrefs. */
    public static boolean isControlsEnabled() {
        if (uiOverride != null) return uiOverride.booleanValue();
        return activity != null && DDPrefs.isEnabled(activity);
    }

    private static void storeEnabled(boolean on) {
        if (activity == null) return;
        if (uiOverride != null) uiOverride = Boolean.valueOf(on);
        else DDPrefs.setEnabled(activity, on);
    }

    public static void install(Activity act, FrameLayout rootView, XServer server, View winlatorControlsView,
                               String shortcutUi, String containerUi) {
        pendingUiOverride = resolveUiOverride(shortcutUi, containerUi);
        install(act, rootView, server, winlatorControlsView);
    }
    // ---- end DD-UI-SELECT ----

    private DDController() {}

    public static void install(Activity act, FrameLayout rootView, XServer server, View winlatorControlsView) {
        activity = act;
        root = rootView;
        xServer = server;
        winlatorControls = winlatorControlsView;
        controls = null;
        keyboard = null;
        editor = null;
        winlatorHidden = false;
        uiOverride = pendingUiOverride;  // DD-UI-SELECT
        pendingUiOverride = null;
        installRailItem();
        applyMode();
    }

    // ------------------------------------------------------------------ mode

    private static void applyMode() {
        if (activity == null || root == null) return;
        boolean on = isControlsEnabled();  // DD-UI-SELECT
        if (on) {
            if (controls == null) {
                controls = new DDOnScreenControls(activity, padSink, false);
                root.addView(controls, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            } else {
                controls.reload();
            }
            controls.setVisibility(View.VISIBLE);
            if (winlatorControls != null && !winlatorHidden) {
                savedWinlatorVisibility = winlatorControls.getVisibility();
                winlatorControls.setVisibility(View.GONE);
                winlatorHidden = true;
            }
            if (keyboard != null) keyboard.bringToFront();
        } else {
            if (controls != null) {
                controls.releaseAll();
                root.removeView(controls);
                controls = null;
            }
            WinHandler wh = winHandler();
            if (wh != null) wh.releaseDroidDeckGamepad();
            if (winlatorControls != null && winlatorHidden) {
                winlatorControls.setVisibility(savedWinlatorVisibility);
                winlatorHidden = false;
            }
        }
    }

    private static WinHandler winHandler() {
        return xServer != null ? xServer.getWinHandler() : null;
    }

    private static final DDOnScreenControls.Sink padSink = new DDOnScreenControls.Sink() {
        @Override public void onPad(GamepadState state) {
            WinHandler wh = winHandler();
            if (wh != null) wh.sendDroidDeckGamepadState(state);
        }

        // Tombol Steam: hotkey overlay Steam (Shift+Tab). Hanya terkirim bila ada window yang menerima.
        @Override public void onGuide(boolean down) {
            if (xServer == null) return;
            if (down) {
                xServer.injectKeyPress(XKeycode.KEY_SHIFT_L);
                xServer.injectKeyPress(XKeycode.KEY_TAB);
            } else {
                xServer.injectKeyRelease(XKeycode.KEY_TAB);
                xServer.injectKeyRelease(XKeycode.KEY_SHIFT_L);
            }
        }
    };

    // -------------------------------------------------------------- keyboard

    private static final DDPcKeyboardView.Sink keySink = new DDPcKeyboardView.Sink() {
        @Override public void key(int evdev, boolean down) {
            if (xServer == null) return;
            XKeycode code = DDEvdev.fromEvdev(evdev);
            if (code == null) return;
            if (down) xServer.injectKeyPress(code); else xServer.injectKeyRelease(code);
        }

        @Override public void androidKeyboard() {
            if (activity == null) return;
            InputMethodManager imm = (InputMethodManager) activity.getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) imm.toggleSoftInput(InputMethodManager.SHOW_FORCED, 0);
        }

        @Override public void close() { hideKeyboard(); }
    };

    public static void toggleKeyboard() {
        if (keyboard == null) showKeyboard(); else hideKeyboard();
    }

    private static void showKeyboard() {
        if (activity == null || root == null || keyboard != null) return;
        keyboard = new DDPcKeyboardView(activity, keySink, DDPrefs.read(activity).tint);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM);
        root.addView(keyboard, lp);
    }

    private static void hideKeyboard() {
        if (keyboard == null || root == null) return;
        keyboard.releaseAll();
        root.removeView(keyboard);
        keyboard = null;
    }

    // ----------------------------------------------------------- sidebar item

    private static void installRailItem() {
        final Activity act = activity;
        View rail = act.findViewById(R.id.IngameSidebarRail);
        if (!(rail instanceof LinearLayout)) return;
        final LinearLayout railLayout = (LinearLayout) rail;
        if (railLayout.findViewWithTag(RAIL_TAG) != null) return;

        float d = act.getResources().getDisplayMetrics().density;
        FrameLayout item = new FrameLayout(act);
        item.setTag(RAIL_TAG);
        item.setClickable(true);
        item.setFocusable(true);
        item.setBackgroundResource(R.drawable.sidebar_nav_icon_bg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams((int) (48 * d), (int) (48 * d));
        lp.topMargin = (int) (10 * d);
        TextView label = new TextView(act);
        label.setText("DD");
        label.setTextColor(Color.WHITE);
        label.setTextSize(13f);
        label.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        label.setGravity(Gravity.CENTER);
        item.addView(label, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        item.setOnClickListener(v -> {
            closeDrawer(railLayout);
            openMenu();
        });

        View input = act.findViewById(R.id.BTItemInput);
        int index = input != null ? railLayout.indexOfChild(input) + 1 : railLayout.getChildCount();
        railLayout.addView(item, Math.max(0, Math.min(index, railLayout.getChildCount())), lp);
    }

    private static void closeDrawer(View from) {
        View v = from;
        while (v != null) {
            if (v instanceof androidx.drawerlayout.widget.DrawerLayout) {
                ((androidx.drawerlayout.widget.DrawerLayout) v).closeDrawers();
                return;
            }
            v = v.getParent() instanceof View ? (View) v.getParent() : null;
        }
    }

    // ------------------------------------------------------------------ menu

    public static void openMenu() {  // DD-COMPOSE-SETTINGS
        if (activity == null) return;
        try {
            DDSettingsComposeDialog.show(activity);
            return;
        } catch (Throwable t) {
            android.util.Log.w("DDController", "Panel Compose gagal dibuka, pakai menu lama", t);
        }
        openMenuLegacy();
    }

    /** Menu AlertDialog lama, hanya fallback kalau panel Compose gagal. */
    private static void openMenuLegacy() {
        if (activity == null) return;
        final boolean on = isControlsEnabled();  // DD-UI-SELECT
        String[] items = {
            "DroidDeck controls: " + (on ? "ON" : "OFF"),
            "PC keyboard: " + (keyboard != null ? "shown" : "hidden"),
            "Appearance (tint, opacity, size)",
            "Behaviour (stick click, adaptive sticks)",
            "Button mapping",
            "Edit layout",
            "Reset layout / mapping / all"
        };
        new AlertDialog.Builder(activity).setTitle("DroidDeck")
            .setItems(items, (dialog, which) -> {
                switch (which) {
                    case 0: storeEnabled(!on); applyMode(); break;
                    case 1: toggleKeyboard(); break;
                    case 2: appearanceMenu(); break;
                    case 3: behaviourMenu(); break;
                    case 4: mappingMenu(); break;
                    case 5: startEditor(); break;
                    case 6: resetMenu(); break;
                    default: break;
                }
            }).show();
    }

    // ---- DD-COMPOSE-SETTINGS: jembatan untuk DDSettingsComposeDialog (Kotlin) ----
    public static boolean isKeyboardShown() { return keyboard != null; }

    public static void setKeyboardShown(boolean show) {
        if (show) showKeyboard(); else hideKeyboard();
    }

    public static void setControlsEnabled(boolean on) {
        if (activity == null) return;
        storeEnabled(on);
        applyMode();
    }

    public static void refreshControls() { reloadControls(); }

    public static void openLayoutEditor() { startEditor(); }

    public static void resetLayoutPrefs() {
        if (activity == null) return;
        if (controls != null) controls.resetLayout();
        else if (root != null) DDPrefs.resetLayout(activity, root.getWidth(), root.getHeight());
        reloadControls();
    }

    public static void resetMappingPrefs() {
        if (activity == null) return;
        DDPrefs.resetMapping(activity);
        reloadControls();
    }

    public static void resetEverything() {
        if (activity == null) return;
        DDPrefs.resetAll(activity);
        reloadControls();
    }
    // ---- end DD-COMPOSE-SETTINGS ----

    private static void reloadControls() { if (controls != null) controls.reload(); }

    private static void appearanceMenu() {
        String[] items = {"Tint", "Opacity", "Size"};
        new AlertDialog.Builder(activity).setTitle("Appearance").setItems(items, (d, which) -> {
            DDPrefs.Settings s = DDPrefs.read(activity);
            if (which == 0) {
                int checked = 0;
                for (int i = 0; i < DDPrefs.TINTS.length; i++) if (DDPrefs.TINTS[i] == s.tint) checked = i;
                new AlertDialog.Builder(activity).setTitle("Tint")
                    .setSingleChoiceItems(DDPrefs.TINT_NAMES, checked, (dd, i) -> {
                        DDPrefs.setTint(activity, DDPrefs.TINTS[i]); reloadControls(); dd.dismiss();
                    }).show();
            } else if (which == 1) {
                String[] names = new String[DDPrefs.OPACITIES.length];
                int checked = 0;
                for (int i = 0; i < names.length; i++) { names[i] = DDPrefs.OPACITIES[i] + "%"; if (DDPrefs.OPACITIES[i] == s.opacity) checked = i; }
                new AlertDialog.Builder(activity).setTitle("Opacity")
                    .setSingleChoiceItems(names, checked, (dd, i) -> {
                        DDPrefs.setOpacity(activity, DDPrefs.OPACITIES[i]); reloadControls(); dd.dismiss();
                    }).show();
            } else {
                String[] names = new String[DDPrefs.SIZES.length];
                int checked = 0;
                for (int i = 0; i < names.length; i++) { names[i] = DDPrefs.SIZES[i] + "%"; if (DDPrefs.SIZES[i] == s.size) checked = i; }
                new AlertDialog.Builder(activity).setTitle("Size")
                    .setSingleChoiceItems(names, checked, (dd, i) -> {
                        DDPrefs.setSize(activity, DDPrefs.SIZES[i]); reloadControls(); dd.dismiss();
                    }).show();
            }
        }).show();
    }

    private static void behaviourMenu() {
        DDPrefs.Settings s = DDPrefs.read(activity);
        final boolean[] checked = {s.stickClick, s.adaptiveSticks};
        new AlertDialog.Builder(activity).setTitle("Behaviour")
            .setMultiChoiceItems(new String[]{"Double-tap stick = L3/R3 click", "Adaptive sticks (appear under finger)"}, checked,
                (d, i, isChecked) -> checked[i] = isChecked)
            .setPositiveButton("OK", (d, w) -> {
                DDPrefs.setStickClick(activity, checked[0]);
                DDPrefs.setAdaptiveSticks(activity, checked[1]);
                reloadControls();
            }).setNegativeButton("Cancel", null).show();
    }

    private static void mappingMenu() {
        String[] names = new String[DDPrefs.MAPPABLE_IDS.length];
        for (int i = 0; i < names.length; i++) {
            String target = DDPrefs.target(activity, DDPrefs.MAPPABLE_IDS[i]);
            String shown = target;
            for (int t = 0; t < DDPrefs.TARGET_IDS.length; t++) if (DDPrefs.TARGET_IDS[t].equals(target)) shown = DDPrefs.TARGET_NAMES[t];
            names[i] = DDPrefs.MAPPABLE_NAMES[i] + "  \u2192  " + shown;
        }
        new AlertDialog.Builder(activity).setTitle("Button mapping").setItems(names, (d, which) -> {
            final String id = DDPrefs.MAPPABLE_IDS[which];
            String current = DDPrefs.target(activity, id);
            int checked = 0;
            for (int t = 0; t < DDPrefs.TARGET_IDS.length; t++) if (DDPrefs.TARGET_IDS[t].equals(current)) checked = t;
            new AlertDialog.Builder(activity).setTitle(DDPrefs.MAPPABLE_NAMES[which] + " sends")
                .setSingleChoiceItems(DDPrefs.TARGET_NAMES, checked, (dd, t) -> {
                    DDPrefs.setTarget(activity, id, DDPrefs.TARGET_IDS[t]);
                    reloadControls();
                    dd.dismiss();
                    mappingMenu();
                }).show();
        }).setNegativeButton("Close", null).show();
    }

    private static void resetMenu() {
        String[] items = {"Reset layout (this screen size)", "Reset button mapping", "Reset everything"};
        new AlertDialog.Builder(activity).setTitle("Reset").setItems(items, (d, which) -> {
            if (which == 0 && controls != null) controls.resetLayout();
            else if (which == 0) DDPrefs.resetLayout(activity, root.getWidth(), root.getHeight());
            else if (which == 1) DDPrefs.resetMapping(activity);
            else DDPrefs.resetAll(activity);
            reloadControls();
        }).show();
    }

    // ---------------------------------------------------------------- editor

    private static void startEditor() {
        if (activity == null || root == null || editor != null) return;
        if (controls != null) controls.setVisibility(View.GONE);
        final DDOnScreenControls edit = new DDOnScreenControls(activity, null, true);
        editor = new FrameLayout(activity);
        editor.setBackgroundColor(Color.argb(150, 0, 0, 0));
        editor.setClickable(true);
        editor.addView(edit, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout bar = new LinearLayout(activity);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.addView(editorButton("Save", v -> { edit.saveLayout(); stopEditor(); }));
        bar.addView(editorButton("Reset", v -> edit.resetLayout()));
        bar.addView(editorButton("Cancel", v -> stopEditor()));
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        lp.topMargin = (int) (8 * activity.getResources().getDisplayMetrics().density);
        editor.addView(bar, lp);
        root.addView(editor, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    private static Button editorButton(String label, View.OnClickListener l) {
        Button b = new Button(activity);
        b.setText(label);
        b.setOnClickListener(l);
        return b;
    }

    private static void stopEditor() {
        if (editor != null && root != null) root.removeView(editor);
        editor = null;
        if (controls != null) { controls.setVisibility(View.VISIBLE); controls.reload(); }
    }
}

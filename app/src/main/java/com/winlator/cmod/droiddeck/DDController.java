package com.winlator.cmod.droiddeck;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;
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
import com.winlator.cmod.xserver.Pointer;
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

    // ---- DD-UI-EXCLUSIVE: hanya satu UI kontrol yang aktif ----
    /** true jika v adalah overlay kontrol Winlator in-game dan UI DroidDeck sedang aktif (Winlator harus tetap tersembunyi). */
    public static boolean isWinlatorControlsSuppressed(View v) {
        return v != null && v == winlatorControls && activity != null && isControlsEnabled();
    }
    // ---- end DD-UI-EXCLUSIVE ----

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
        attachDeck(act, rootView); // DroidDeck-deck
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
                int restoreVisibility = savedWinlatorVisibility;  // DD-UI-EXCLUSIVE
                if (winlatorControls instanceof com.winlator.cmod.widget.InputControlsView
                        && ((com.winlator.cmod.widget.InputControlsView) winlatorControls).getProfile() != null) {
                    restoreVisibility = View.VISIBLE;  // profil Winlator sedang dipakai -> tampilkan lagi
                }
                winlatorControls.setVisibility(restoreVisibility);
                winlatorHidden = false;
            }
        }
        applyDeckVisibility(on);  // DroidDeck-deckmap: satu UI kontrol; elemen Deck ikut hilang bila kontrol dimatikan
    }

    // DroidDeck-deckmap: elemen khas Deck (trackpad, grip, QAM) adalah bagian dari UI kontrol yang sama
    private static void applyDeckVisibility(boolean on) {
        if (deckView == null) return;
        if (on) {
            deckView.setVisibility(View.VISIBLE);
            deckView.reload();
        } else {
            deckView.releaseAll();
            deckView.setVisibility(View.GONE);
        }
    }

    private static WinHandler winHandler() {
        return xServer != null ? xServer.getWinHandler() : null;
    }

    private static final DDOnScreenControls.Sink padSink = new DDOnScreenControls.Sink() {
        @Override public void onPad(GamepadState state) {
            WinHandler wh = winHandler();
            // DroidDeck-deck: mode pad Deck menulis file state; evdev tetap dikirim bila dipilih
            if (DDDeck.isSessionActive()) {
                DDDeck.updatePad(state);
                if (!DDDeck.isEvdevActive()) return;
            }
            padBase.copy(state);  // simpan agar D-pad trackpad bisa digabung
            if (wh != null) wh.sendDroidDeckGamepadState(withPadDpad());
        }

        // Tombol Steam: hotkey overlay Steam (Shift+Tab). Hanya terkirim bila ada window yang menerima.
        @Override public void onGuide(boolean down) {
            if (xServer == null) return;
            // DroidDeck-deck: pada mode pad Deck, Guide adalah tombol Steam
            if (DDDeck.isSessionActive()) {
                DDDeck.setGuide(down);
                return;
            }
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
        if (installSidebarPanel(act)) return;  // DD-SIDEBAR-PANEL
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
        try { if (DDSidebarPanel.select(activity)) return; } catch (Throwable ignored) { }  // DD-SIDEBAR-PANEL
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

    // ---- DD-SIDEBAR-PANEL: panel pengaturan inline di left sidebar ----
    private static boolean installSidebarPanel(final Activity act) {
        final View item = act.findViewById(R.id.BTItemDD);
        final View panel = act.findViewById(R.id.LLSubDD);
        if (item == null || !(panel instanceof FrameLayout)) return false;
        try {
            DDSidebarPanel.attach(act, (FrameLayout) panel);
        } catch (Throwable t) {
            android.util.Log.w("DDController", "Panel sidebar Compose gagal, pakai dialog", t);
            item.setOnClickListener(v -> {
                closeDrawerFromRail();
                openMenu();
            });
        }
        return true;
    }

    private static void closeDrawerFromRail() {
        if (activity == null) return;
        View rail = activity.findViewById(R.id.IngameSidebarRail);
        if (rail != null) closeDrawer(rail);
    }

    public static boolean panelControlsEnabled() { return isControlsEnabled(); }

    public static void panelSetControlsEnabled(boolean enabled) {
        setControlsEnabled(enabled);
    }

    public static boolean panelKeyboardShown() { return keyboard != null; }

    public static void panelSetKeyboard(boolean show) {
        if (show) {
            showKeyboard();
            closeDrawerFromRail();
        } else {
            hideKeyboard();
        }
    }

    public static void panelRefreshControls() { reloadControls(); }

    public static void panelEditLayout() {
        closeDrawerFromRail();
        startEditor();
    }

    public static void panelResetLayout() {
        if (activity == null) return;
        if (controls != null) controls.resetLayout();
        else if (root != null) DDPrefs.resetLayout(activity, root.getWidth(), root.getHeight());
        reloadControls();
    }

    public static void panelResetMapping() {
        if (activity == null) return;
        DDPrefs.resetMapping(activity);
        reloadControls();
    }

    public static void panelResetAll() {
        if (activity == null) return;
        DDPrefs.resetAll(activity);
        reloadControls();
    }
    // ---- end DD-SIDEBAR-PANEL ----

    // ---- DD-SIDEBAR-FIX: jalur dialog untuk fallback panel sidebar ----
    public static void panelOpenDialog() {
        if (activity == null) return;
        try {
            DDSettingsComposeDialog.show(activity);
            return;
        } catch (Throwable t) {
            android.util.Log.w("DDController", "Dialog Compose gagal, pakai menu lama", t);
        }
        openMenuLegacy();
    }
    // ---- end DD-SIDEBAR-FIX ----

    // ---- DroidDeck-evdev: trackpad Deck di jalur evdev ----
    // Pemetaan bawaan Steam Deck: trackpad kanan = mouse (klik pad = klik kiri); trackpad kiri = D-pad,
    // arah dipilih dari posisi jari saat pad diklik ("D-Pad requires click"). Hanya aktif saat evdev aktif;
    // pada jalur hidraw murni, Steam yang menangani trackpad.
    private static final float PAD_MOUSE_PX = 450f;   // piksel kursor per satuan pad (-1..1) pada sensitivitas 100%
    private static final float PAD_DPAD_DEAD = 0.30f; // klik di tengah pad tidak menekan arah apa pun
    private static final long PAD_DPAD_HOLD_MS = 80L;
    private static final Handler padHandler = new Handler(Looper.getMainLooper());
    private static final GamepadState padBase = new GamepadState();
    private static final GamepadState padOut = new GamepadState();
    private static final boolean[] padDpad = new boolean[4];  // atas, kanan, bawah, kiri (urutan GamepadState.dpad)
    private static boolean mouseTouching, mouseLeftDown;
    private static float mouseLastX, mouseLastY, mouseRemX, mouseRemY, padGain = 1f, leftPadX, leftPadY;
    private static final Runnable releaseDpad = () -> {
        java.util.Arrays.fill(padDpad, false);
        pushPadState();
    };

    private static GamepadState withPadDpad() {
        padOut.copy(padBase);
        for (int i = 0; i < 4; i++) if (padDpad[i]) padOut.dpad[i] = true;
        for (int i = 0; i < deckExtraDown.length; i++) if (deckExtraDown[i]) applyExtraTarget(padOut, deckExtraTarget[i]);
        if (rightPadStick) {
            if (rightPadTouching) {  // y trackpad ke atas, stik layar ke bawah
                padOut.thumbRX = clampAxis(padOut.thumbRX + rightPadX);
                padOut.thumbRY = clampAxis(padOut.thumbRY - rightPadY);
            }
            if (rightPadClick) padOut.setPressed(9, true);  // klik trackpad = R3
        }
        if (gyroActive()) {
            padOut.thumbRX = clampAxis(padOut.thumbRX + gyroOutX);
            padOut.thumbRY = clampAxis(padOut.thumbRY + gyroOutY);
        }
        return padOut;
    }

    // ---- DroidDeck-deckmap: grip/QAM Deck, mode trackpad kanan dan gyro untuk jalur evdev ----
    // Game XInput/DirectInput tidak bisa membaca pad Deck di hidraw (HID vendor), jadi tombol dan sensor khas Deck
    // juga diteruskan ke pad evdev lewat pemetaan di panel samping (pengganti pemetaan Steam Input tanpa klien Steam).
    private static final boolean[] deckExtraDown = new boolean[DDPrefs.DECK_IDS.length];
    private static final String[] deckExtraTarget = new String[DDPrefs.DECK_IDS.length];
    static { java.util.Arrays.fill(deckExtraTarget, DDPrefs.NONE); }
    private static boolean deckGuideDown = false;
    private static boolean rightPadStick = false, rightPadClick = false;
    private static volatile boolean rightPadTouching = false;
    private static float rightPadX = 0f, rightPadY = 0f;
    private static volatile String gyroMode = "off";
    private static volatile float gyroGain = 1f;
    private static volatile float gyroOutX = 0f, gyroOutY = 0f;
    private static float gyroSmoothX = 0f, gyroSmoothY = 0f;
    private static float gyroPushedX = 0f, gyroPushedY = 0f;
    private static volatile long gyroLastPush = 0L;
    private static final Handler gyroHandler = new Handler(Looper.getMainLooper());
    private static final java.util.concurrent.atomic.AtomicBoolean gyroPushPending = new java.util.concurrent.atomic.AtomicBoolean(false);
    private static final float GYRO_FULL_RATE = 3.5f;  // rad/s (~200 derajat/dtk) = stik penuh pada sensitivitas 100%
    private static final float GYRO_DEADZONE = 0.05f;  // rad/s

    private static float clampAxis(float v) { return Math.max(-1f, Math.min(1f, v)); }

    private static void loadDeckMap() {
        if (activity == null) return;
        for (int i = 0; i < deckExtraTarget.length; i++) deckExtraTarget[i] = DDPrefs.deckTarget(activity, DDPrefs.DECK_IDS[i]);
        rightPadStick = "stick".equals(DDPrefs.padMode(activity));
        if (!rightPadStick) rightPadClick = false;
        gyroMode = DDPrefs.gyroMode(activity);
        gyroGain = DDPrefs.gyroSens(activity) / 100f;
    }

    private static boolean gyroActive() {
        String m = gyroMode;
        return "always".equals(m) || ("pad".equals(m) && rightPadTouching);
    }

    private static void applyExtraTarget(GamepadState s, String target) {
        if (target == null) return;
        switch (target) {
            case "a": s.setPressed(0, true); break;
            case "b": s.setPressed(1, true); break;
            case "x": s.setPressed(2, true); break;
            case "y": s.setPressed(3, true); break;
            case "lb": s.setPressed(4, true); break;
            case "rb": s.setPressed(5, true); break;
            case "select": s.setPressed(6, true); break;
            case "start": s.setPressed(7, true); break;
            case "l3": s.setPressed(8, true); break;
            case "r3": s.setPressed(9, true); break;
            case "lt": s.triggerL = 1f; break;
            case "rt": s.triggerR = 1f; break;
            case "up": s.dpad[0] = true; break;
            case "right": s.dpad[1] = true; break;
            case "down": s.dpad[2] = true; break;
            case "left": s.dpad[3] = true; break;
            default: break;  // none dan guide (guide ditangani setDeckGuide)
        }
    }

    /** Dipanggil DDDeckControlsView: 0=L4, 1=L5, 2=QAM, 3=R5, 4=R4 (urutan DDPrefs.DECK_IDS). */
    public static void onDeckExtra(int index, boolean down) {
        if (index < 0 || index >= deckExtraDown.length) return;
        deckExtraDown[index] = down;
        if (!DDDeck.isEvdevActive()) return;
        String target = deckExtraTarget[index];
        if (target == null || DDPrefs.NONE.equals(target)) return;
        if ("guide".equals(target)) { setDeckGuide(down); return; }
        pushPadState();
    }

    private static void setDeckGuide(boolean down) {
        if (xServer == null || deckGuideDown == down) return;
        deckGuideDown = down;
        if (down) {
            xServer.injectKeyPress(XKeycode.KEY_SHIFT_L);
            xServer.injectKeyPress(XKeycode.KEY_TAB);
        } else {
            xServer.injectKeyRelease(XKeycode.KEY_TAB);
            xServer.injectKeyRelease(XKeycode.KEY_SHIFT_L);
        }
    }

    private static float gyroShape(float rate) {
        float a = Math.abs(rate);
        if (a < GYRO_DEADZONE) return 0f;
        float v = Math.min(1f, (a - GYRO_DEADZONE) * gyroGain / GYRO_FULL_RATE);
        return rate < 0f ? -v : v;
    }

    private static final Runnable gyroPushRunnable = new Runnable() {
        @Override public void run() {
            gyroLastPush = android.os.SystemClock.uptimeMillis();
            gyroPushPending.set(false);
            gyroPushedX = gyroOutX;
            gyroPushedY = gyroOutY;
            if (DDDeck.isEvdevActive()) pushPadState();
        }
    };

    // Dipanggil dari thread sensor. Layar diputar ke kanan/kiri (sumbu y) = menoleh; ke bawah/atas (sumbu x) = menunduk/mendongak.
    private static final DDDeck.GyroListener gyroListener = new DDDeck.GyroListener() {
        @Override public void onGyro(float rateX, float rateY) {
            if ("off".equals(gyroMode)) return;
            gyroSmoothX += (gyroShape(rateY) - gyroSmoothX) * 0.5f;
            gyroSmoothY += (gyroShape(rateX) - gyroSmoothY) * 0.5f;
            if (Math.abs(gyroSmoothX) < 0.002f) gyroSmoothX = 0f;
            if (Math.abs(gyroSmoothY) < 0.002f) gyroSmoothY = 0f;
            gyroOutX = gyroSmoothX;
            gyroOutY = gyroSmoothY;
            if (!gyroActive()) return;
            if (gyroOutX == gyroPushedX && gyroOutY == gyroPushedY) return;
            if (android.os.SystemClock.uptimeMillis() - gyroLastPush >= 8L && gyroPushPending.compareAndSet(false, true)) {
                gyroHandler.post(gyroPushRunnable);
            }
        }
    };

    private static void pushPadState() {
        WinHandler wh = winHandler();
        if (wh != null && DDDeck.isEvdevActive()) wh.sendDroidDeckGamepadState(withPadDpad());
    }

    private static void moveMouse(boolean touching, float x, float y) {
        if (!touching) { mouseTouching = false; mouseRemX = 0f; mouseRemY = 0f; return; }
        if (!mouseTouching) {  // jari baru menyentuh: titik awal + sensitivitas terkini dari pengaturan
            mouseTouching = true;
            mouseLastX = x; mouseLastY = y;
            mouseRemX = 0f; mouseRemY = 0f;
            if (activity != null) padGain = DDPrefs.read(activity).padSens / 100f;
            return;
        }
        mouseRemX += (x - mouseLastX) * PAD_MOUSE_PX * padGain;
        mouseRemY += -(y - mouseLastY) * PAD_MOUSE_PX * padGain;  // y pad ke atas, layar ke bawah
        mouseLastX = x; mouseLastY = y;
        int dx = (int) mouseRemX, dy = (int) mouseRemY;
        if (dx == 0 && dy == 0) return;
        mouseRemX -= dx; mouseRemY -= dy;
        if (xServer != null) xServer.injectPointerMoveDelta(dx, dy);
    }

    private static void clickMouse(boolean down) {
        if (xServer == null || mouseLeftDown == down) return;
        mouseLeftDown = down;
        if (down) xServer.injectPointerButtonPress(Pointer.Button.BUTTON_LEFT);
        else xServer.injectPointerButtonRelease(Pointer.Button.BUTTON_LEFT);
    }

    private static void clickDpad(boolean down) {
        if (!down) return;  // dilepas oleh timer agar tekanan cukup lama terbaca game
        if (Math.hypot(leftPadX, leftPadY) < PAD_DPAD_DEAD) return;
        int dir;
        if (Math.abs(leftPadX) >= Math.abs(leftPadY)) dir = leftPadX > 0f ? 1 : 3;
        else dir = leftPadY > 0f ? 0 : 2;
        padHandler.removeCallbacks(releaseDpad);
        java.util.Arrays.fill(padDpad, false);
        padDpad[dir] = true;
        pushPadState();
        padHandler.postDelayed(releaseDpad, PAD_DPAD_HOLD_MS);
    }

    private static void stopEvdevPad() {
        padHandler.removeCallbacks(releaseDpad);
        java.util.Arrays.fill(padDpad, false);
        mouseTouching = false;
        if (mouseLeftDown) clickMouse(false);
        java.util.Arrays.fill(deckExtraDown, false);  // DroidDeck-deckmap
        rightPadTouching = false; rightPadClick = false; rightPadX = 0f; rightPadY = 0f;
        gyroSmoothX = 0f; gyroSmoothY = 0f; gyroOutX = 0f; gyroOutY = 0f;
        setDeckGuide(false);
    }

    private static final DDDeck.PadListener evdevPadListener = new DDDeck.PadListener() {
        @Override public void onPad(boolean right, boolean touching, float x, float y) {
            if (!DDDeck.isEvdevActive()) { stopEvdevPad(); return; }
            if (right) {
                rightPadTouching = touching;
                rightPadX = touching ? x : 0f;
                rightPadY = touching ? y : 0f;
                if (rightPadStick) pushPadState();  // DroidDeck-deckmap: trackpad kanan sebagai stik kanan
                else {
                    moveMouse(touching, x, y);
                    if (!"off".equals(gyroMode)) pushPadState();  // gyro "saat trackpad disentuh" ikut berubah
                }
            }
            else if (touching) { leftPadX = x; leftPadY = y; }
        }

        @Override public void onClick(boolean right, boolean down) {
            if (!DDDeck.isEvdevActive()) return;
            if (right) {
                if (rightPadStick) { rightPadClick = down; pushPadState(); }  // klik trackpad = R3
                else clickMouse(down);
            } else clickDpad(down);
        }
    };

    // DroidDeck-deck
    private static DDDeckControlsView deckView;

    private static void attachDeck(Activity act, FrameLayout rootView) {
        deckView = null;
        DDDeck.setSessionActive(false);
        DDDeck.setPadListener(null);
        DDDeck.setGyroListener(null);
        stopEvdevPad();
        if (!DDDeck.isWanted()) return;
        if (DDDeck.prepare(act).isEmpty()) return;  // gagal menyiapkan: tetap pakai jalur biasa
        DDDeck.setSessionActive(true);
        DDDeck.setPadListener(evdevPadListener);
        DDDeck.setGyroListener(gyroListener);
        loadDeckMap();
        deckView = new DDDeckControlsView(act, DDPrefs.read(act).tint);
        rootView.addView(deckView, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        applyDeckVisibility(isControlsEnabled());  // DroidDeck-deckmap: satu UI kontrol
        final Activity a = act;
        DDDeck.startMotion(act, () -> a.getWindowManager().getDefaultDisplay().getRotation());
    }

    // DroidDeck-bp: overlay Controller Test di atas game (tab Status memperlihatkan apakah Steam menemukan pad)
    private static DDControllerTestView testOverlay;

    private static android.view.View findDrawer(android.view.View v) {
        if (v instanceof androidx.drawerlayout.widget.DrawerLayout) return v;
        if (v instanceof android.view.ViewGroup) {
            android.view.ViewGroup g = (android.view.ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                android.view.View found = findDrawer(g.getChildAt(i));
                if (found != null) return found;
            }
        }
        return null;
    }

    public static void openControllerTest() {
        if (activity == null || root == null || testOverlay != null) return;
        android.view.View drawer = findDrawer(activity.getWindow().getDecorView());
        if (drawer != null) ((androidx.drawerlayout.widget.DrawerLayout) drawer).closeDrawers();
        testOverlay = new DDControllerTestView(activity, new DDControllerTestView.Host() {
            @Override public void close() { closeControllerTest(); }
            @Override public void toggleControls() {}
            @Override public boolean controlsVisible() { return true; }
            @Override public boolean standalone() { return false; }
        });
        root.addView(testOverlay, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    public static void closeControllerTest() {
        if (testOverlay != null && root != null) root.removeView(testOverlay);
        testOverlay = null;
    }

    private static void reloadControls() {
        loadDeckMap();  // DroidDeck-deckmap
        if (controls != null) controls.reload();
        if (deckView != null) deckView.reload();  // posisi trackpad/grip/QAM ikut layout tersimpan
    }

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
        final boolean[] checked = {s.stickClick, s.adaptiveSticks, s.rumble};
        new AlertDialog.Builder(activity).setTitle("Behaviour")
            .setMultiChoiceItems(new String[]{"Double-tap stick = L3/R3 click", "Adaptive sticks (appear under finger)", "Rumble (phone vibration)"}, checked,
                (d, i, isChecked) -> checked[i] = isChecked)
            .setPositiveButton("OK", (d, w) -> {
                DDPrefs.setStickClick(activity, checked[0]);
                DDPrefs.setAdaptiveSticks(activity, checked[1]);
                DDPrefs.setRumble(activity, checked[2]);
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
        if (deckView != null) { deckView.releaseAll(); deckView.setVisibility(View.GONE); }
        final DDOnScreenControls edit = new DDOnScreenControls(activity, null, true);
        editor = new FrameLayout(activity);
        editor.setBackgroundColor(Color.argb(150, 0, 0, 0));
        editor.setClickable(true);
        editor.addView(edit, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        // Trackpad, grip L4/R4/L5/R5 dan QAM ikut bisa digeser bila mode Deck aktif.
        // View ini di atas; sentuhan yang tidak mengenai elemennya diteruskan ke kontrol biasa.
        final DDDeckControlsView deckEdit = deckView != null ? new DDDeckControlsView(activity, DDPrefs.read(activity).tint, true) : null;
        if (deckEdit != null) {
            editor.addView(deckEdit, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            deckEdit.setOnSelectListener(edit::clearSelection);
        }

        LinearLayout bar = new LinearLayout(activity);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.addView(editorButton("Save", v -> { edit.saveLayout(); if (deckEdit != null) deckEdit.saveLayout(); stopEditor(); }));
        bar.addView(editorButton("Reset", v -> { edit.resetLayout(); if (deckEdit != null) deckEdit.resetLayout(); }));
        bar.addView(editorButton("Cancel", v -> stopEditor()));
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER);  // tengah layar: kosong; atas-tengah dipakai grip L4/R4/L5/R5 dan QAM
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
        if (deckView != null) { deckView.setVisibility(View.VISIBLE); deckView.reload(); }
    }
}

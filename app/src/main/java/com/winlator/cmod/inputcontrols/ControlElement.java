package com.winlator.cmod.inputcontrols;

import android.animation.ValueAnimator;
import android.content.Context;
import android.view.inputmethod.InputMethodManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PointF;
import android.graphics.Rect;
import android.graphics.RectF;
import android.view.animation.DecelerateInterpolator;

import androidx.core.graphics.ColorUtils;

import com.winlator.cmod.core.CubicBezierInterpolator;
import com.winlator.cmod.math.Mathf;
import com.winlator.cmod.widget.InputControlsView;
import com.winlator.cmod.widget.TouchpadView;
import com.winlator.cmod.winhandler.MouseEventFlags;
import com.winlator.cmod.xserver.XServer;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

public class ControlElement {
    public static final float STICK_DEAD_ZONE = 0.15f;
    public static final float DPAD_DEAD_ZONE = 0.3f;
    public static final float STICK_SENSITIVITY = 3.0f;
    public static final float TRACKPAD_MIN_SPEED = 0.8f;
    public static final float TRACKPAD_MAX_SPEED = 20.0f;
    public static final byte TRACKPAD_ACCELERATION_THRESHOLD = 4;
    public static final short BUTTON_MIN_TIME_TO_KEEP_PRESSED = 300;

    public enum Type {
        BUTTON, D_PAD, RANGE_BUTTON, STICK, TRACKPAD, TOUCHSCREEN_TOGGLE, RIGHT_STICK, MENU_NAVIGATION, MULTIPLE_BUTTON;

        public static String[] names() {
            Type[] types = values();
            String[] names = new String[types.length];
            for (int i = 0; i < types.length; i++) names[i] = types[i].name().replace("_", "-");
            return names;
        }
    }

    public enum Shape {
        CIRCLE, RECT, ROUND_RECT, SQUARE;

        public static String[] names() {
            Shape[] shapes = values();
            String[] names = new String[shapes.length];
            for (int i = 0; i < shapes.length; i++) names[i] = shapes[i].name().replace("_", " ");
            return names;
        }
    }

    public enum Range {
        FROM_A_TO_Z(26), FROM_0_TO_9(10), FROM_F1_TO_F12(12), FROM_NP0_TO_NP9(10);

        public final byte max;

        Range(int max) {
            this.max = (byte) max;
        }

        public static String[] names() {
            Range[] ranges = values();
            String[] names = new String[ranges.length];
            for (int i = 0; i < ranges.length; i++) names[i] = ranges[i].name().replace("_", " ");
            return names;
        }
    }

    private final InputControlsView inputControlsView;
    private Type type = Type.BUTTON;
    private Shape shape = Shape.CIRCLE;
    private List<Binding> bindings = new ArrayList<>();
    private float scale = 1.0f;
    private short x;
    private short y;
    private boolean selected = false;
    private boolean toggleSwitch = false;
    private int currentPointerId = -1;
    private final Rect boundingBox = new Rect();
    private boolean[] states = new boolean[0];
    private boolean boundingBoxNeedsUpdate = true;
    private String text = "";
    private byte iconId;
    private Range range;

    private byte orientation;
    private PointF currentPosition;
    private PointF visualThumbPosition; // posisi visual thumbstick RIGHT_STICK (terpisah dari currentPosition yang dipakai input)
    private RangeScroller scroller;

    // === CURSOR MOVE MODE (RIGHT_STICK only) ===
    // Center = tengah layar X server, PERMANEN, tidak pernah berubah.
    // Konsep: pointer bergerak melingkar di sekitar center.
    //
    // Saat jari BERGERAK:
    //   pointerPos = center + clamp(lastOffset + (currentDelta - startDelta), -1..1) * radius
    //
    // "lastOffset" menyimpan posisi ternormalisasi pointer saat jari terakhir dilepas,
    // sehingga sentuhan berikutnya MELANJUTKAN dari posisi terakhir (bukan kembali ke center).
    // "startDelta" adalah posisi jari saat pertama menyentuh — dipakai sebagai titik referensi
    // agar gerakan jari baru dihitung relatif terhadap posisi awal sentuhan itu.
    private boolean isCursorMove = false;
    // Radius lingkaran pergerakan pointer dalam piksel X server (default 150px).
    private int cursorMoveRadius = 150;
    // Center layar — dihitung sekali, tidak pernah berubah selama mode aktif.
    private float cursorMoveCenterX = -1f;
    private float cursorMoveCenterY = -1f;
    // Offset ternormalisasi (-1..1) pointer saat jari terakhir dilepas.
    // Dipakai sebagai titik awal pergerakan pada sentuhan berikutnya.
    private float cursorMoveLastOffsetX = 0f;
    private float cursorMoveLastOffsetY = 0f;
    // Posisi jari ternormalisasi saat pertama menyentuh dalam sesi ini.
    // Delta aktual = currentDelta - startDelta, ditambahkan ke lastOffset.
    private float cursorMoveStartDeltaX = 0f;
    private float cursorMoveStartDeltaY = 0f;
    // Apakah startDelta sudah direkam untuk sesi sentuhan saat ini.
    private boolean cursorMoveStartRecorded = false;

    // Icon per-slot: D_PAD=[up,right,down,left], STICK/RIGHT_STICK=[outer,inner],
    // MENU_NAVIGATION=[mainButton, keyboard, inputControls, exit] → slot 0..3
    private byte[] slotIconIds = new byte[7];
    private CubicBezierInterpolator interpolator;
    private Object touchTime;

    // Cached Rect objects untuk drawIconExact — menghindari alokasi per frame (anti-lag)
    private final Rect iconSrcRect = new Rect();
    private final Rect iconDstRect = new Rect();

    // === TAMBAHAN UNTUK EFEK VISUAL PRESSED ===
    private boolean isPressed = false;

    // === MENU NAVIGATION ===
    // true = sub-menu sedang tampil (expanded), false = tersembunyi (collapsed)
    private boolean menuExpanded = false;
    // Animasi expand/collapse sub-menu (0.0 = collapsed, 1.0 = expanded)
    private float menuAnimProgress = 0f;
    // Animator untuk animasi slide sub-menu
    private ValueAnimator menuAnimator;

    // === MULTIPLE BUTTON ===
    // Maksimal 8 sub-button, masing-masing punya:
    //   - bindings sendiri (combo, seperti BUTTON)
    //   - arah animasi keluar (8 arah): 0=UP, 1=UP_RIGHT, 2=RIGHT, 3=DOWN_RIGHT,
    //                                   4=DOWN, 5=DOWN_LEFT, 6=LEFT, 7=UP_LEFT
    //   - text/icon sendiri
    //
    // Tap tombol utama → toggle expanded/collapsed
    // Tap sub-button   → tekan semua binding sub-button itu (lepas saat jari diangkat)
    //
    // Data per sub-button disimpan dalam array paralel berindeks 0..multiButtonCount-1
    public static final int MULTI_BTN_MAX = 8;
    // Jumlah sub-button aktif
    private int multiButtonCount = 4;
    // Bindings per sub-button: List<List<Binding>>, diinisialisasi di reset()
    private List<List<Binding>> multiButtonBindings = new ArrayList<>();
    // Arah per sub-button (nilai 0..7)
    private byte[] multiButtonDirections = new byte[MULTI_BTN_MAX];
    // Text per sub-button
    private String[] multiButtonTexts = new String[MULTI_BTN_MAX];
    // Icon ID per sub-button (0 = tidak ada icon)
    private byte[] multiButtonIconIds = new byte[MULTI_BTN_MAX];
    // State expand/collapse (sama dengan MENU_NAVIGATION)
    private boolean multiBtnExpanded = false;
    private float multiBtnAnimProgress = 0f;
    private ValueAnimator multiBtnAnimator;
    // Index sub-button yang sedang ditekan (-1 = tidak ada)
    private int multiBtnPressedIndex = -1;
    // Inisialisasi awal list — diisi lengkap saat reset() dipanggil
    {
        for (int _i = 0; _i < MULTI_BTN_MAX; _i++) {
            List<Binding> sl = new ArrayList<>();
            sl.add(Binding.NONE);
            multiButtonBindings.add(sl);
            multiButtonDirections[_i] = (byte) 0xFF;
            multiButtonTexts[_i] = "";
            multiButtonIconIds[_i] = 0;
        }
    }

    public ControlElement(InputControlsView inputControlsView) {
        this.inputControlsView = inputControlsView;
    }

    private void reset() {
        bindings.clear();
        states = new boolean[0];
        scroller = null;

        if (type == Type.D_PAD || type == Type.STICK) {
            bindings.add(Binding.KEY_W);
            bindings.add(Binding.KEY_D);
            bindings.add(Binding.KEY_S);
            bindings.add(Binding.KEY_A);
            states = new boolean[4];
        } else if (type == Type.TRACKPAD) {
            bindings.add(Binding.MOUSE_MOVE_UP);
            bindings.add(Binding.MOUSE_MOVE_RIGHT);
            bindings.add(Binding.MOUSE_MOVE_DOWN);
            bindings.add(Binding.MOUSE_MOVE_LEFT);
            states = new boolean[4];
        } else if (type == Type.RIGHT_STICK) {
            bindings.add(Binding.GAMEPAD_RIGHT_THUMB_UP);
            bindings.add(Binding.GAMEPAD_RIGHT_THUMB_RIGHT);
            bindings.add(Binding.GAMEPAD_RIGHT_THUMB_DOWN);
            bindings.add(Binding.GAMEPAD_RIGHT_THUMB_LEFT);
            states = new boolean[4];
        } else if (type == Type.RANGE_BUTTON) {
            scroller = new RangeScroller(inputControlsView, this);
        } else if (type == Type.TOUCHSCREEN_TOGGLE) {
            bindings.add(Binding.NONE);
            states = new boolean[1];
        } else {
            // BUTTON, MENU_NAVIGATION, dan tipe lain default 1 binding
            bindings.add(Binding.NONE);
            states = new boolean[1];
        }

        // MULTIPLE_BUTTON: inisialisasi sub-button data
        if (type == Type.MULTIPLE_BUTTON) {
            multiButtonBindings.clear();
            // Default: 4 sub-button, semua arah NONE (user mengatur sendiri)
            multiButtonCount = 4;
            for (int i = 0; i < MULTI_BTN_MAX; i++) {
                List<Binding> sl = new ArrayList<>();
                sl.add(Binding.NONE);
                multiButtonBindings.add(sl);
                multiButtonDirections[i] = (byte) 0xFF; // NONE/hidden by default
                multiButtonTexts[i] = "";
                multiButtonIconIds[i] = 0;
            }
        }

        text = "";
        iconId = 0;
        range = null;
        boundingBoxNeedsUpdate = true;
    }

    public Type getType() {
        return type;
    }

    public void setType(Type type) {
        this.type = type;
        reset();
    }

    public int getBindingCount() {
        return bindings.size();
    }

    public void setBindingCount(int count) {
        while (bindings.size() > count) {
            bindings.remove(bindings.size() - 1);
        }
        while (bindings.size() < count) {
            bindings.add(Binding.NONE);
        }
        boolean[] newStates = new boolean[count];
        System.arraycopy(states, 0, newStates, 0, Math.min(states.length, count));
        states = newStates;
        boundingBoxNeedsUpdate = true;
    }

    public Shape getShape() {
        return shape;
    }

    public void setShape(Shape shape) {
        this.shape = shape;
        boundingBoxNeedsUpdate = true;
    }

    public Range getRange() {
        return range != null ? range : Range.FROM_A_TO_Z;
    }

    public void setRange(Range range) {
        this.range = range;
    }



    public byte getOrientation() {
        return orientation;
    }

    public void setOrientation(byte orientation) {
        this.orientation = orientation;
        boundingBoxNeedsUpdate = true;
    }

    public boolean isToggleSwitch() {
        return toggleSwitch;
    }

    public void setToggleSwitch(boolean toggleSwitch) {
        this.toggleSwitch = toggleSwitch;
    }

    // === CURSOR MOVE MODE ===
    public boolean isCursorMove() {
        return isCursorMove;
    }

    public void setCursorMove(boolean cursorMove) {
        this.isCursorMove = cursorMove;
        if (!cursorMove) {
            cursorMoveCenterX        = -1f;
            cursorMoveCenterY        = -1f;
            cursorMoveLastOffsetX    = 0f;
            cursorMoveLastOffsetY    = 0f;
            cursorMoveStartDeltaX    = 0f;
            cursorMoveStartDeltaY    = 0f;
            cursorMoveStartRecorded  = false;
        }
    }

    public int getCursorMoveRadius() {
        return cursorMoveRadius;
    }

    public void setCursorMoveRadius(int radius) {
        // Range 50–500 piksel layar X server
        this.cursorMoveRadius = Math.max(50, Math.min(500, radius));
    }

    // === MENU NAVIGATION ===
    public boolean isMenuExpanded() {
        return menuExpanded;
    }

    // === MULTIPLE BUTTON ===
    public boolean isMultiBtnExpanded() { return multiBtnExpanded; }

    public int getMultiButtonCount() { return multiButtonCount; }

    public void setMultiButtonCount(int count) {
        count = Math.max(1, Math.min(MULTI_BTN_MAX, count));
        while (multiButtonBindings.size() < MULTI_BTN_MAX) {
            List<Binding> sl = new ArrayList<>();
            sl.add(Binding.NONE);
            multiButtonBindings.add(sl);
        }
        this.multiButtonCount = count;
        boundingBoxNeedsUpdate = true;
    }

    public List<Binding> getMultiButtonBindings(int index) {
        // Pastikan list sudah diinisialisasi (guard untuk elemen yang di-load dari JSON lama)
        if (multiButtonBindings == null) multiButtonBindings = new ArrayList<>();
        while (multiButtonBindings.size() <= index) {
            List<Binding> sl = new ArrayList<>();
            sl.add(Binding.NONE);
            multiButtonBindings.add(sl);
        }
        List<Binding> sl = multiButtonBindings.get(index);
        if (sl == null || sl.isEmpty()) {
            sl = new ArrayList<>();
            sl.add(Binding.NONE);
            multiButtonBindings.set(index, sl);
        }
        return sl;
    }

    public void setMultiButtonBindings(int index, List<Binding> b) {
        while (multiButtonBindings.size() <= index) {
            List<Binding> sl = new ArrayList<>();
            sl.add(Binding.NONE);
            multiButtonBindings.add(sl);
        }
        multiButtonBindings.set(index, b != null ? b : new ArrayList<>());
    }

    /** Arah: 0=UP,1=UP_RIGHT,2=RIGHT,3=DOWN_RIGHT,4=DOWN,5=DOWN_LEFT,6=LEFT,7=UP_LEFT
     *  0xFF (atau nilai negatif saat cast) = NONE/hidden — button tidak ditampilkan */
    public byte getMultiButtonDirection(int index) {
        return (index >= 0 && index < MULTI_BTN_MAX) ? multiButtonDirections[index] : 0;
    }

    public void setMultiButtonDirection(int index, byte dir) {
        if (index >= 0 && index < MULTI_BTN_MAX) {
            // 0xFF = NONE/hidden, 0..7 = valid arah
            if (dir == (byte) 0xFF) {
                multiButtonDirections[index] = (byte) 0xFF;
            } else {
                multiButtonDirections[index] = (byte)(((dir % 8) + 8) % 8);
            }
        }
    }

    public String getMultiButtonText(int index) {
        if (index >= 0 && index < MULTI_BTN_MAX) {
            String t = multiButtonTexts[index];
            return t != null ? t : "";
        }
        return "";
    }

    public void setMultiButtonText(int index, String text) {
        if (index >= 0 && index < MULTI_BTN_MAX)
            multiButtonTexts[index] = text != null ? text : "";
    }

    public byte getMultiButtonIconId(int index) {
        return (index >= 0 && index < MULTI_BTN_MAX) ? multiButtonIconIds[index] : 0;
    }

    public void setMultiButtonIconId(int index, byte id) {
        if (index >= 0 && index < MULTI_BTN_MAX) multiButtonIconIds[index] = id;
    }

    private void animateMultiBtn(boolean expand) {
        if (multiBtnAnimator != null) multiBtnAnimator.cancel();
        float to = expand ? 1f : 0f;
        multiBtnAnimator = ValueAnimator.ofFloat(multiBtnAnimProgress, to);
        multiBtnAnimator.setDuration(280);
        multiBtnAnimator.setInterpolator(new DecelerateInterpolator());
        multiBtnAnimator.addUpdateListener(anim -> {
            multiBtnAnimProgress = (float) anim.getAnimatedValue();
            inputControlsView.invalidate();
        });
        multiBtnAnimator.start();
    }

    private void toggleMultiBtn() {
        multiBtnExpanded = !multiBtnExpanded;
        animateMultiBtn(multiBtnExpanded);
    }

    /**
     * Hit-test sub-button ke-i menggunakan rect yang sama dengan computeMultiBtnSubRect.
     * Harus konsisten dengan drawMultipleButton agar area yang digambar = area yang bisa ditekan.
     */
    private boolean isMultiBtnSubHit(int index, float px, float py) {
        if (multiBtnAnimProgress < 0.5f) return false;
        byte dirByte = multiButtonDirections[index];
        if (dirByte == (byte) 0xFF) return false;

        Rect bb  = getBoundingBox();
        float w  = bb.width();
        float h  = bb.height();
        int   dir = dirByte & 0x07;
        float gap = inputControlsView.getSnappingSize() * 0.4f * scale;

        // Hitung laneIdx: berapa banyak item sebelum index ini dengan arah yang sama
        int laneIdx = 0;
        for (int j = 0; j < index; j++) {
            byte d = multiButtonDirections[j];
            if (d != (byte) 0xFF && (d & 0x07) == dir) laneIdx++;
        }

        RectF rect = computeMultiBtnSubRect(bb, dir, laneIdx, w, h, gap);
        return rect.contains(px, py);
    }

    private void animateMenu(boolean expand) {
        if (menuAnimator != null) menuAnimator.cancel();
        float from = menuAnimProgress;
        float to   = expand ? 1f : 0f;
        menuAnimator = ValueAnimator.ofFloat(from, to);
        // 380ms: cukup untuk 4 item cascade terasa berurutan tanpa terasa lambat
        menuAnimator.setDuration(380);
        menuAnimator.setInterpolator(new DecelerateInterpolator());
        menuAnimator.addUpdateListener(anim -> {
            menuAnimProgress = (float) anim.getAnimatedValue();
            inputControlsView.invalidate();
        });
        menuAnimator.start();
    }

    /** Dipanggil saat tombol utama MENU_NAVIGATION ditekan — toggle expand/collapse. */
    private void toggleMenu() {
        menuExpanded = !menuExpanded;
        animateMenu(menuExpanded);
    }

    /**
     * Eksekusi aksi item sub-menu langsung dari ControlElement tanpa listener.
     * Context diambil dari inputControlsView — saat runtime di XServerDisplayActivity
     * ini adalah instance Activity itu sendiri.
     *
     * Keyboard      : toggle soft keyboard via InputMethodManager
     * Task Manager  : buka TaskManagerDialog
     * Active Windows: buka ActiveWindowsDialog
     * Exit          : cast ke XServerDisplayActivity, panggil exitApp()
     *
     * @param itemIndex 0=Keyboard, 1=Task Manager, 2=Active Windows, 3=Exit
     */
    private void executeMenuAction(int itemIndex) {
        Context context = inputControlsView.getContext();
        android.os.Handler uiHandler = new android.os.Handler(android.os.Looper.getMainLooper());

        switch (itemIndex) {
            case 0: { // Keyboard — toggle soft keyboard
                uiHandler.post(() -> {
                    InputMethodManager imm = (InputMethodManager)
                            context.getSystemService(Context.INPUT_METHOD_SERVICE);
                    if (imm != null) {
                        imm.toggleSoftInput(InputMethodManager.SHOW_FORCED, 0);
                    }
                });
                break;
            }
            case 1: { // Cursor Hotspot Position
                if (context instanceof com.winlator.cmod.XServerDisplayActivity) {
                com.winlator.cmod.XServerDisplayActivity activity = (com.winlator.cmod.XServerDisplayActivity) context;
                uiHandler.post(() -> {
                    com.winlator.cmod.renderer.GLRenderer renderer = activity.getXServerView() != null 
                            ? activity.getXServerView().getRenderer() : null;
                    if (renderer != null) {
                        new com.winlator.cmod.contentdialog.CursorPositionDialog(activity, renderer, activity.getXServer()).show();
                    }
                });
                }
                break;
            }
            case 2: { // Task Manager dialog
                if (context instanceof com.winlator.cmod.XServerDisplayActivity) {
                    com.winlator.cmod.XServerDisplayActivity activity =
                            (com.winlator.cmod.XServerDisplayActivity) context;
                    uiHandler.post(() ->
                            new com.winlator.cmod.winhandler.TaskManagerDialog(activity).show());
                }
                break;
            }
            case 3: { // Active Windows dialog
                if (context instanceof com.winlator.cmod.XServerDisplayActivity) {
                    com.winlator.cmod.XServerDisplayActivity activity =
                            (com.winlator.cmod.XServerDisplayActivity) context;
                    uiHandler.post(() -> {
                        com.winlator.cmod.renderer.GLRenderer renderer =
                                activity.getXServerView() != null
                                        ? activity.getXServerView().getRenderer()
                                        : null;
                        com.winlator.cmod.contentdialog.ActiveWindowsDialog.show(
                                activity, activity.getXServer(), renderer);
                    });
                }
                break;
            }
            case 4: { // Exit
                if (context instanceof com.winlator.cmod.XServerDisplayActivity) {
                    com.winlator.cmod.XServerDisplayActivity activity =
                            (com.winlator.cmod.XServerDisplayActivity) context;
                    uiHandler.post(activity::exitApp);
                }
                break;
            }
        }
    }


    public Binding getBindingAt(int index) {
        return (index >= 0 && index < bindings.size()) ? bindings.get(index) : Binding.NONE;
    }

    public void setBindingAt(int index, Binding binding) {
        while (bindings.size() <= index) {
            bindings.add(Binding.NONE);
        }
        bindings.set(index, binding != null ? binding : Binding.NONE);

        if (states.length <= index) {
            boolean[] newStates = new boolean[index + 1];
            System.arraycopy(states, 0, newStates, 0, states.length);
            states = newStates;
        }
        boundingBoxNeedsUpdate = true;
    }

    public void addBinding(Binding binding) {
        bindings.add(binding != null ? binding : Binding.NONE);
        boolean[] newStates = new boolean[states.length + 1];
        System.arraycopy(states, 0, newStates, 0, states.length);
        states = newStates;
        boundingBoxNeedsUpdate = true;
    }

    public void removeBinding(int index) {
        if (index >= 0 && index < bindings.size()) {
            bindings.remove(index);
            if (index < states.length) {
                boolean[] newStates = new boolean[states.length - 1];
                System.arraycopy(states, 0, newStates, 0, index);
                if (index + 1 < states.length) {
                    System.arraycopy(states, index + 1, newStates, index, states.length - index - 1);
                }
                states = newStates;
            }
            boundingBoxNeedsUpdate = true;
        }
    }

    public void setBinding(Binding binding) {
        // Hanya fill semua elemen — JANGAN ubah size list.
        // RangeScroller memanggil setBinding(NONE) saat touch-down untuk reset state,
        // tanpa bermaksud mengubah jumlah slot. Mengubah size akan merusak
        // computeBoundingBox() yang bergantung pada getBindingCount() == bindings.size().
        Binding b = binding != null ? binding : Binding.NONE;
        for (int i = 0; i < bindings.size(); i++) bindings.set(i, b);
    }

    public float getScale() {
        return scale;
    }

    public void setScale(float scale) {
        this.scale = scale;
        boundingBoxNeedsUpdate = true;
    }

    public short getX() {
        return x;
    }

    public void setX(int x) {
        this.x = (short) x;
        boundingBoxNeedsUpdate = true;
    }

    public short getY() {
        return y;
    }

    public void setY(int y) {
        this.y = (short) y;
        boundingBoxNeedsUpdate = true;
    }

    public boolean isSelected() {
        return selected;
    }

    public void setSelected(boolean selected) {
        this.selected = selected;
    }

    public String getText() {
        return text;
    }

    public void setText(String text) {
        this.text = text != null ? text : "";
    }

    public byte getIconId() {
        return iconId;
    }

    public void setIconId(int iconId) {
        this.iconId = (byte) iconId;
    }

    public byte getSlotIconId(int slot) {
        if (slot >= 0 && slot < slotIconIds.length) return slotIconIds[slot];
        return 0;
    }

    public void setSlotIconId(int slot, byte id) {
        if (slot >= 0 && slot < slotIconIds.length) slotIconIds[slot] = id;
    }

    public byte[] getSlotIconIds() {
        return slotIconIds;
    }

    public void setSlotIconIds(byte[] ids) {
        if (ids != null) {
            for (int i = 0; i < Math.min(ids.length, slotIconIds.length); i++) {
                slotIconIds[i] = ids[i];
            }
        }
    }

    public Rect getBoundingBox() {
        if (boundingBoxNeedsUpdate) computeBoundingBox();
        return boundingBox;
    }

    private Rect computeBoundingBox() {
        int snappingSize = inputControlsView.getSnappingSize();
        int halfWidth = 0;
        int halfHeight = 0;

        switch (type) {
            case BUTTON:
            case TOUCHSCREEN_TOGGLE:
            case MENU_NAVIGATION:
            case MULTIPLE_BUTTON:
                switch (shape) {
                    case RECT:
                    case ROUND_RECT:
                        halfWidth = snappingSize * 4;
                        halfHeight = snappingSize * 2;
                        break;
                    case SQUARE:
                        halfWidth = (int) (snappingSize * 2.5f);
                        halfHeight = (int) (snappingSize * 2.5f);
                        break;
                    case CIRCLE:
                        halfWidth = snappingSize * 3;
                        halfHeight = snappingSize * 3;
                        break;
                }
                break;
            case D_PAD: {
                halfWidth = snappingSize * 7;
                halfHeight = snappingSize * 7;
                break;
            }
            case TRACKPAD:
            case STICK:
            case RIGHT_STICK: {
                halfWidth = snappingSize * 6;
                halfHeight = snappingSize * 6;
                break;
            }
            case RANGE_BUTTON: {
                // Gunakan getBindingCount() (= bindings.size()) sebagai jumlah slot visible.
                // NPColumns di editor mengatur nilai ini via setBindingCount().
                // setBinding(NONE) di RangeScroller tidak mengubah size, jadi stabil.
                halfWidth = snappingSize * ((getBindingCount() * 4) / 2);
                halfHeight = snappingSize * 2;

                if (orientation == 1) {
                    int tmp = halfWidth;
                    halfWidth = halfHeight;
                    halfHeight = tmp;
                }
                break;
            }
        }

        halfWidth *= scale;
        halfHeight *= scale;
        boundingBox.set(x - halfWidth, y - halfHeight, x + halfWidth, y + halfHeight);
        boundingBoxNeedsUpdate = false;
        return boundingBox;
    }

    private String getDisplayText() {
        if (text != null && !text.isEmpty()) {
            return text;
        } else {
            Binding binding = getBindingAt(0);
            String text = binding.toString().replace("NUMPAD ", "NP").replace("BUTTON ", "");
            if (text.length() > 7) {
                String[] parts = text.split(" ");
                StringBuilder sb = new StringBuilder();
                for (String part : parts) sb.append(part.charAt(0));
                return (binding.isMouse() ? "M" : "") + sb;
            } else return text;
        }
    }

    private static float getTextSizeForWidth(Paint paint, String text, float desiredWidth) {
        final byte testTextSize = 48;
        paint.setTextSize(testTextSize);
        return testTextSize * desiredWidth / paint.measureText(text);
    }

    private static String getRangeTextForIndex(Range range, int index) {
        String text = "";
        switch (range) {
            case FROM_A_TO_Z:
                text = String.valueOf((char) (65 + index));
                break;
            case FROM_0_TO_9:
                text = String.valueOf((index + 1) % 10);
                break;
            case FROM_F1_TO_F12:
                text = "F" + (index + 1);
                break;
            case FROM_NP0_TO_NP9:
                text = "NP" + ((index + 1) % 10);
                break;
        }
        return text;
    }

    public void draw(Canvas canvas) {
        int snappingSize = inputControlsView.getSnappingSize();
        Paint paint = inputControlsView.getPaint();
        int primaryColor = inputControlsView.getPrimaryColor();
        int secondaryColor = inputControlsView.getPrimaryColor();

        int baseColor = selected ? secondaryColor : primaryColor;
        float strokeWidth = snappingSize * 0.25f;
        Rect boundingBox = getBoundingBox();

        switch (type) {
            case BUTTON:
            case TOUCHSCREEN_TOGGLE: {
                float cx = boundingBox.centerX();
                float cy = boundingBox.centerY();

                paint.setColor(baseColor);
                paint.setStrokeWidth(strokeWidth);

                boolean shouldFill = (type == Type.TOUCHSCREEN_TOGGLE) ? selected : isPressed;

                // Sembunyikan stroke/fill shape jika ada icon
                if (iconId == 0) {
                    if (shouldFill) {
                        paint.setStyle(Paint.Style.FILL_AND_STROKE);
                    } else {
                        paint.setStyle(Paint.Style.STROKE);
                    }

                    switch (shape) {
                        case CIRCLE:
                            canvas.drawCircle(cx, cy, boundingBox.width() * 0.5f, paint);
                            break;
                        case RECT:
                            canvas.drawRect(boundingBox, paint);
                            break;
                        case ROUND_RECT: {
                            float radius = boundingBox.height() * 0.5f;
                            canvas.drawRoundRect(boundingBox.left, boundingBox.top, boundingBox.right, boundingBox.bottom, radius, radius, paint);
                            break;
                        }
                        case SQUARE: {
                            float radius = snappingSize * 0.75f * scale;
                            canvas.drawRoundRect(boundingBox.left, boundingBox.top, boundingBox.right, boundingBox.bottom, radius, radius, paint);
                            break;
                        }
                    }
                }

                paint.setStyle(Paint.Style.FILL);
                paint.setColor(primaryColor);

                if (iconId > 0) {
                    // Saat normal: icon 80% dari bounding box
                    // Saat pressed: icon membesar ke 100% (tetap dalam batas, tidak keluar)
                    float pressScale = isPressed ? 1.0f : 0.8f;
                    float iconW = boundingBox.width() * pressScale;
                    float iconH = boundingBox.height() * pressScale;
                    drawIconExact(canvas, cx, cy, iconW, iconH, iconId);
                }
                else if (!text.isEmpty() || getBindingAt(0) != Binding.NONE) {
                    String displayText = getDisplayText();
                    if (!displayText.isEmpty()) {
                        paint.setTextSize(Math.min(getTextSizeForWidth(paint, displayText, boundingBox.width() - strokeWidth * 2), snappingSize * 2 * scale));
                        paint.setTextAlign(Paint.Align.CENTER);
                        float textY = cy - ((paint.descent() + paint.ascent()) * 0.5f);
                        canvas.drawText(displayText, cx, textY, paint);
                    }
                }

                break;
            }

            case D_PAD: {
                float cx = boundingBox.centerX();
                float cy = boundingBox.centerY();
                float offsetX = snappingSize * 2 * scale;
                float offsetY = snappingSize * 3 * scale;
                float start = snappingSize * scale;
                Path path = inputControlsView.getPath();

                paint.setColor(baseColor);
                paint.setStrokeWidth(strokeWidth);

                boolean upPressed    = states.length > 0 && states[0] && isPressed;
                boolean rightPressed = states.length > 1 && states[1] && isPressed;
                boolean downPressed  = states.length > 2 && states[2] && isPressed;
                boolean leftPressed  = states.length > 3 && states[3] && isPressed;

                // Ukuran icon global — dipakai oleh global icon dan arm icon (konsisten)
                float globalIconW = boundingBox.width() * 0.8f;
                float globalIconH = boundingBox.height() * 0.8f;

                // Jika ada iconId global: sembunyikan semua arm stroke,
                // gambar iconId sebagai tampilan utama D_PAD — ukuran TETAP tidak membesar saat pressed
                if (iconId > 0) {
                    paint.setStyle(Paint.Style.FILL);
                    paint.setColor(primaryColor);
                    drawIconExact(canvas, cx, cy, globalIconW, globalIconH, iconId);

                    // Slot icon arah digambar di CENTER boundingBox dengan ukuran SAMA seperti global icon
                    // sehingga menimpa global icon saat ditekan
                    if (upPressed && slotIconIds[0] > 0) {
                        drawIconExact(canvas, cx, cy, globalIconW, globalIconH, slotIconIds[0]);
                    } else if (rightPressed && slotIconIds[1] > 0) {
                        drawIconExact(canvas, cx, cy, globalIconW, globalIconH, slotIconIds[1]);
                    } else if (downPressed && slotIconIds[2] > 0) {
                        drawIconExact(canvas, cx, cy, globalIconW, globalIconH, slotIconIds[2]);
                    } else if (leftPressed && slotIconIds[3] > 0) {
                        drawIconExact(canvas, cx, cy, globalIconW, globalIconH, slotIconIds[3]);
                    }
                    break;
                }

                // Tidak ada iconId global: gambar arm stroke biasa
                float armUpDownW   = offsetX * 2 * 0.8f;
                float armUpDownH   = offsetY * 0.8f;
                float armLRW       = offsetY * 0.8f;
                float armLRH       = offsetX * 2 * 0.8f;

                // UP
                path.reset();
                path.moveTo(cx, cy - start);
                path.lineTo(cx - offsetX, cy - offsetY);
                path.lineTo(cx - offsetX, boundingBox.top);
                path.lineTo(cx + offsetX, boundingBox.top);
                path.lineTo(cx + offsetX, cy - offsetY);
                path.close();
                if (slotIconIds[0] > 0) {
                    if (upPressed) { paint.setStyle(Paint.Style.FILL); canvas.drawPath(path, paint); }
                    paint.setStyle(Paint.Style.FILL);
                    paint.setColor(upPressed ? ColorUtils.setAlphaComponent(baseColor, 180) : primaryColor);
                    // Icon arm digambar di center boundingBox, ukuran = globalIconW/H
                    drawIconExact(canvas, cx, cy, globalIconW, globalIconH, slotIconIds[0]);
                } else {
                    paint.setStyle(upPressed ? Paint.Style.FILL_AND_STROKE : Paint.Style.STROKE);
                    canvas.drawPath(path, paint);
                }

                // RIGHT
                paint.setColor(baseColor);
                path.reset();
                path.moveTo(cx + start, cy);
                path.lineTo(cx + offsetY, cy - offsetX);
                path.lineTo(boundingBox.right, cy - offsetX);
                path.lineTo(boundingBox.right, cy + offsetX);
                path.lineTo(cx + offsetY, cy + offsetX);
                path.close();
                if (slotIconIds[1] > 0) {
                    if (rightPressed) { paint.setStyle(Paint.Style.FILL); canvas.drawPath(path, paint); }
                    paint.setStyle(Paint.Style.FILL);
                    paint.setColor(rightPressed ? ColorUtils.setAlphaComponent(baseColor, 180) : primaryColor);
                    drawIconExact(canvas, cx, cy, globalIconW, globalIconH, slotIconIds[1]);
                } else {
                    paint.setStyle(rightPressed ? Paint.Style.FILL_AND_STROKE : Paint.Style.STROKE);
                    canvas.drawPath(path, paint);
                }

                // DOWN
                paint.setColor(baseColor);
                path.reset();
                path.moveTo(cx, cy + start);
                path.lineTo(cx - offsetX, cy + offsetY);
                path.lineTo(cx - offsetX, boundingBox.bottom);
                path.lineTo(cx + offsetX, boundingBox.bottom);
                path.lineTo(cx + offsetX, cy + offsetY);
                path.close();
                if (slotIconIds[2] > 0) {
                    if (downPressed) { paint.setStyle(Paint.Style.FILL); canvas.drawPath(path, paint); }
                    paint.setStyle(Paint.Style.FILL);
                    paint.setColor(downPressed ? ColorUtils.setAlphaComponent(baseColor, 180) : primaryColor);
                    drawIconExact(canvas, cx, cy, globalIconW, globalIconH, slotIconIds[2]);
                } else {
                    paint.setStyle(downPressed ? Paint.Style.FILL_AND_STROKE : Paint.Style.STROKE);
                    canvas.drawPath(path, paint);
                }

                // LEFT
                paint.setColor(baseColor);
                path.reset();
                path.moveTo(cx - start, cy);
                path.lineTo(cx - offsetY, cy - offsetX);
                path.lineTo(boundingBox.left, cy - offsetX);
                path.lineTo(boundingBox.left, cy + offsetX);
                path.lineTo(cx - offsetY, cy + offsetX);
                path.close();
                if (slotIconIds[3] > 0) {
                    if (leftPressed) { paint.setStyle(Paint.Style.FILL); canvas.drawPath(path, paint); }
                    paint.setStyle(Paint.Style.FILL);
                    paint.setColor(leftPressed ? ColorUtils.setAlphaComponent(baseColor, 180) : primaryColor);
                    drawIconExact(canvas, cx, cy, globalIconW, globalIconH, slotIconIds[3]);
                } else {
                    paint.setStyle(leftPressed ? Paint.Style.FILL_AND_STROKE : Paint.Style.STROKE);
                    canvas.drawPath(path, paint);
                }

                break;
            }

            case RANGE_BUTTON: {
                // kode RANGE_BUTTON tetap sama seperti aslinya
                Range range = getRange();
                int oldColor = paint.getColor();
                float radius = snappingSize * 0.75f * scale;
                float elementSize = scroller.getElementSize();
                float minTextSize = snappingSize * 2 * scale;
                float scrollOffset = scroller.getScrollOffset();
                byte[] rangeIndex = scroller.getRangeIndex();
                Path path = inputControlsView.getPath();
                path.reset();

                paint.setColor(baseColor);
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(strokeWidth);

                if (orientation == 0) {
                    float lineTop = boundingBox.top + strokeWidth * 0.5f;
                    float lineBottom = boundingBox.bottom - strokeWidth * 0.5f;
                    float startX = boundingBox.left;
                    canvas.drawRoundRect(startX, boundingBox.top, boundingBox.right, boundingBox.bottom, radius, radius, paint);

                    canvas.save();
                    path.addRoundRect(startX, boundingBox.top, boundingBox.right, boundingBox.bottom, radius, radius, Path.Direction.CW);
                    canvas.clipPath(path);
                    startX -= scrollOffset % elementSize;

                    for (byte i = rangeIndex[0]; i < rangeIndex[1]; i++) {
                        int index = i % range.max;
                        paint.setStyle(Paint.Style.STROKE);
                        paint.setColor(oldColor);

                        if (startX > boundingBox.left && startX < boundingBox.right)
                            canvas.drawLine(startX, lineTop, startX, lineBottom, paint);
                        String text = getRangeTextForIndex(range, index);

                        if (startX < boundingBox.right && startX + elementSize > boundingBox.left) {
                            paint.setStyle(Paint.Style.FILL);
                            paint.setColor(primaryColor);
                            paint.setTextSize(Math.min(getTextSizeForWidth(paint, text, elementSize - strokeWidth * 2), minTextSize));
                            paint.setTextAlign(Paint.Align.CENTER);
                            canvas.drawText(text, startX + elementSize * 0.5f, (y - ((paint.descent() + paint.ascent()) * 0.5f)), paint);
                        }
                        startX += elementSize;
                    }

                    paint.setStyle(Paint.Style.STROKE);
                    paint.setColor(oldColor);
                    canvas.restore();
                } else {
                    float lineLeft = boundingBox.left + strokeWidth * 0.5f;
                    float lineRight = boundingBox.right - strokeWidth * 0.5f;
                    float startY = boundingBox.top;
                    canvas.drawRoundRect(boundingBox.left, startY, boundingBox.right, boundingBox.bottom, radius, radius, paint);

                    canvas.save();
                    path.addRoundRect(boundingBox.left, startY, boundingBox.right, boundingBox.bottom, radius, radius, Path.Direction.CW);
                    canvas.clipPath(inputControlsView.getPath());
                    startY -= scrollOffset % elementSize;

                    for (byte i = rangeIndex[0]; i < rangeIndex[1]; i++) {
                        paint.setStyle(Paint.Style.STROKE);
                        paint.setColor(oldColor);

                        if (startY > boundingBox.top && startY < boundingBox.bottom)
                            canvas.drawLine(lineLeft, startY, lineRight, startY, paint);
                        String text = getRangeTextForIndex(range, i);

                        if (startY < boundingBox.bottom && startY + elementSize > boundingBox.top) {
                            paint.setStyle(Paint.Style.FILL);
                            paint.setColor(primaryColor);
                            paint.setTextSize(Math.min(getTextSizeForWidth(paint, text, boundingBox.width() - strokeWidth * 2), minTextSize));
                            paint.setTextAlign(Paint.Align.CENTER);
                            canvas.drawText(text, x, startY + elementSize * 0.5f - ((paint.descent() + paint.ascent()) * 0.5f), paint);
                        }
                        startY += elementSize;
                    }

                    paint.setStyle(Paint.Style.STROKE);
                    paint.setColor(oldColor);
                    canvas.restore();
                }
                break;
            }

            case STICK: {
                int cx = boundingBox.centerX();
                int cy = boundingBox.centerY();
                int oldColor = paint.getColor();

                paint.setColor(baseColor);
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(strokeWidth);

                // Outer circle stroke — sembunyikan jika ada icon outer
                if (slotIconIds[0] == 0) {
                    canvas.drawCircle(cx, cy, boundingBox.height() * 0.5f, paint);
                }

                // Slot 0: icon outer — pas dengan diameter outer circle
                if (slotIconIds[0] > 0) {
                    paint.setStyle(Paint.Style.FILL);
                    paint.setColor(primaryColor);
                    float outerDiameter = boundingBox.height() - strokeWidth;
                    drawIconExact(canvas, cx, cy, outerDiameter, outerDiameter, slotIconIds[0]);
                }

                float thumbstickX = getCurrentPosition().x;
                float thumbstickY = getCurrentPosition().y;
                short thumbRadius = (short) (snappingSize * 3.5f * scale);
                float innerDiameter = thumbRadius * 2;

                // Inner fill + stroke — sembunyikan jika ada icon inner
                if (slotIconIds[1] == 0) {
                    if (isPressed) {
                        paint.setStyle(Paint.Style.FILL);
                        paint.setColor(baseColor);
                    } else {
                        paint.setStyle(Paint.Style.FILL);
                        paint.setColor(ColorUtils.setAlphaComponent(primaryColor, 50));
                    }
                    canvas.drawCircle(thumbstickX, thumbstickY, thumbRadius, paint);

                    paint.setStyle(Paint.Style.STROKE);
                    paint.setColor(oldColor);
                    paint.setStrokeWidth(strokeWidth * 0.5f);
                    canvas.drawCircle(thumbstickX, thumbstickY, thumbRadius + strokeWidth * 0.5f, paint);
                }

                // Slot 1: icon inner — ukuran pas dengan diameter inner thumbstick
                if (slotIconIds[1] > 0) {
                    paint.setStyle(Paint.Style.FILL);
                    paint.setColor(isPressed ? baseColor : primaryColor);
                    drawIconExact(canvas, thumbstickX, thumbstickY, innerDiameter, innerDiameter, slotIconIds[1]);
                } else if (iconId > 0) {
                    paint.setStyle(Paint.Style.FILL);
                    paint.setColor(primaryColor);
                    drawIconExact(canvas, thumbstickX, thumbstickY, innerDiameter, innerDiameter, iconId);
                }
                break;
            }

            case RIGHT_STICK: {
                int cx = boundingBox.centerX();
                int cy = boundingBox.centerY();
                int oldColor = paint.getColor();

                paint.setColor(baseColor);
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(strokeWidth);

                // Outer circle stroke — sembunyikan jika ada icon outer
                if (slotIconIds[0] == 0) {
                    canvas.drawCircle(cx, cy, boundingBox.height() * 0.5f, paint);
                }

                // Slot 0: icon outer — pas dengan diameter outer circle
                if (slotIconIds[0] > 0) {
                    paint.setStyle(Paint.Style.FILL);
                    paint.setColor(primaryColor);
                    float outerDiameter = boundingBox.height() - strokeWidth;
                    drawIconExact(canvas, cx, cy, outerDiameter, outerDiameter, slotIconIds[0]);
                }

                float thumbstickX, thumbstickY;
                if (isPressed && visualThumbPosition != null) {
                    thumbstickX = visualThumbPosition.x;
                    thumbstickY = visualThumbPosition.y;
                } else {
                    thumbstickX = cx;
                    thumbstickY = cy;
                }
                short thumbRadius = (short) (snappingSize * 3.5f * scale);
                float innerDiameter = thumbRadius * 2;

                // Inner fill + stroke — sembunyikan jika ada icon inner
                if (slotIconIds[1] == 0) {
                    if (isPressed) {
                        paint.setStyle(Paint.Style.FILL);
                        paint.setColor(baseColor);
                    } else {
                        paint.setStyle(Paint.Style.FILL);
                        paint.setColor(ColorUtils.setAlphaComponent(primaryColor, 50));
                    }
                    canvas.drawCircle(thumbstickX, thumbstickY, thumbRadius, paint);

                    paint.setStyle(Paint.Style.STROKE);
                    paint.setColor(oldColor);
                    paint.setStrokeWidth(strokeWidth * 0.5f);
                    canvas.drawCircle(thumbstickX, thumbstickY, thumbRadius + strokeWidth * 0.5f, paint);
                }

                // Label "R" hanya jika tidak ada slot/global icon sama sekali
                if (slotIconIds[0] == 0 && slotIconIds[1] == 0 && iconId == 0) {
                    paint.setStyle(Paint.Style.FILL);
                    paint.setColor(primaryColor);
                    float labelSize = snappingSize * 1.8f * scale;
                    paint.setTextSize(labelSize);
                    paint.setTextAlign(Paint.Align.CENTER);
                    float labelOffset = boundingBox.height() * 0.5f - labelSize * 0.6f;
                    canvas.drawText("R", cx + labelOffset, cy - labelOffset + labelSize * 0.4f, paint);
                }

                // Slot 1: icon inner — ukuran pas dengan diameter inner thumbstick
                if (slotIconIds[1] > 0) {
                    paint.setStyle(Paint.Style.FILL);
                    paint.setColor(isPressed ? baseColor : primaryColor);
                    drawIconExact(canvas, thumbstickX, thumbstickY, innerDiameter, innerDiameter, slotIconIds[1]);
                } else if (iconId > 0) {
                    paint.setStyle(Paint.Style.FILL);
                    paint.setColor(primaryColor);
                    drawIconExact(canvas, thumbstickX, thumbstickY, innerDiameter, innerDiameter, iconId);
                }
                break;
            }

            case TRACKPAD: {
                float cx = boundingBox.centerX();
                float cy = boundingBox.centerY();
                float radius = boundingBox.height() * 0.15f;
                float offset = strokeWidth * 2.5f;
                float innerStrokeWidth = strokeWidth * 2;

                if (iconId == 0) {
                    canvas.drawRoundRect(boundingBox.left, boundingBox.top, boundingBox.right, boundingBox.bottom, radius, radius, paint);
                    float innerHeight = boundingBox.height() - offset * 2;
                    float innerRadius = (innerHeight / boundingBox.height()) * radius - (innerStrokeWidth * 0.5f + strokeWidth * 0.5f);
                    paint.setStrokeWidth(innerStrokeWidth);
                    canvas.drawRoundRect(boundingBox.left + offset, boundingBox.top + offset, boundingBox.right - offset, boundingBox.bottom - offset, innerRadius, innerRadius, paint);
                } else {
                    // Icon mengisi area inner trackpad secara eksak
                    paint.setStyle(Paint.Style.FILL);
                    paint.setColor(primaryColor);
                    float innerW = boundingBox.width() - offset * 2;
                    float innerH = boundingBox.height() - offset * 2;
                    drawIconExact(canvas, cx, cy, innerW, innerH, iconId);
                }
                break;
            }

            case MENU_NAVIGATION: {
                drawMenuNavigation(canvas, boundingBox, paint, primaryColor, strokeWidth, snappingSize);
                break;
            }

            case MULTIPLE_BUTTON: {
                drawMultipleButton(canvas, boundingBox, paint, primaryColor, strokeWidth, snappingSize);
                break;
            }
        }
    }

    /**
     * Menggambar tombol utama MENU_NAVIGATION beserta sub-menu yang muncul ke bawah.
     *
     * Tampilan tombol utama identik dengan BUTTON (shape ROUND_RECT, custom text).
     * Sub-menu terdiri dari 3 item: Keyboard, Input Controls, Exit — muncul dengan
     * animasi slide-down (menuAnimProgress 0→1) dan menghilang dengan slide-up (1→0).
     *
     * Setiap item sub-menu berbentuk ROUND_RECT, lebar sama dengan tombol utama,
     * tinggi = tinggi tombol utama, digeser ke bawah dengan offset berbasis animasi.
     *
     * Slot icon MENU_NAVIGATION:
     *   slot 0 = icon tombol utama (menggantikan text/unicode)
     *   slot 1 = icon item Keyboard
     *   slot 2 = icon item Input Controls
     *   slot 3 = icon item Exit
     *
     * Jika slot icon tersedia → icon di kiri + text label di kanan.
     * Jika tidak → unicode fallback + label di tengah.
     */
    private void drawMenuNavigation(Canvas canvas, Rect boundingBox, Paint paint,
                                    int primaryColor, float strokeWidth, int snappingSize) {
        float cx = boundingBox.centerX();
        float cy = boundingBox.centerY();
        float w  = boundingBox.width();
        float h  = boundingBox.height();
        float r  = h * 0.5f;
        float itemR = r * 0.6f; // radius sudut — sama dengan item sub-menu

        // ── Tombol utama ──────────────────────────────────────────────────
        paint.setStrokeWidth(strokeWidth * 0.75f);

        if (slotIconIds[0] > 0 || iconId > 0) {
            // Ada icon → tampilan penuh (stroke saja, seperti BUTTON biasa)
            paint.setColor(primaryColor);
            paint.setStyle(isPressed ? Paint.Style.FILL_AND_STROKE : Paint.Style.STROKE);
            canvas.drawRoundRect(
                    boundingBox.left, boundingBox.top,
                    boundingBox.right, boundingBox.bottom,
                    r, r, paint);

            paint.setStyle(Paint.Style.FILL);
            float iconSize = Math.min(w, h) * (isPressed ? 1.0f : 0.78f);
            drawIconExact(canvas, cx, cy, iconSize, iconSize,
                    slotIconIds[0] > 0 ? slotIconIds[0] : iconId);
        } else {
            // Tidak ada icon → tampilan identik dengan item sub-menu:
            // background semi-transparan + border + label teks di tengah

            // Background semi-transparan (fill lebih kuat saat pressed)
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(ColorUtils.setAlphaComponent(primaryColor, isPressed ? 80 : 40));
            canvas.drawRoundRect(
                    boundingBox.left, boundingBox.top,
                    boundingBox.right, boundingBox.bottom,
                    itemR, itemR, paint);

            // Border
            paint.setStyle(Paint.Style.STROKE);
            paint.setColor(primaryColor);
            canvas.drawRoundRect(
                    boundingBox.left, boundingBox.top,
                    boundingBox.right, boundingBox.bottom,
                    itemR, itemR, paint);

            // Label teks — custom text atau default "≡"
            paint.setStyle(Paint.Style.FILL);
            paint.setTextAlign(Paint.Align.CENTER);
            String label = (text != null && !text.isEmpty()) ? text : "\u2261";
            float ts = Math.min(
                    getTextSizeForWidth(paint, label, w - strokeWidth * 4),
                    snappingSize * 1.6f * scale);
            paint.setTextSize(ts);
            canvas.drawText(label, cx, cy - (paint.descent() + paint.ascent()) * 0.5f, paint);
        }

        // ── Sub-menu items (animasi pop-out scale) ────────────────────────
        // menuAnimProgress: 0.0 = collapsed (tidak tampil), 1.0 = fully expanded
        // Animasi: setiap item muncul dengan efek pop/membesar dari skala 0 → 1
        // saat expand, dan mengecil dari 1 → 0 saat collapse.
        if (menuAnimProgress <= 0f) return;

        // slot 1=Keyboard, 2=Task Manager, 3=Active Windows, 4=Exit
        final String[] itemFallback = {"\u2328", "\u26CF", "\u2630", "\u25A3", "\u2715"};
        final String[] itemLabels   = {"Keyboard" , "Cursor Pos", "Task Manager", "Active Windows", "Exit"};

        float gap    = snappingSize * 0.4f * scale;
        float itemH  = h;

        // Alpha keseluruhan sub-menu mengikuti progress (fade-in/out bersama scale)
        int menuAlpha = (int) (menuAnimProgress * 255);

        // Setiap item diberi window animasi sendiri di dalam total progress 0..1.
        // 4 item dibagi rata: item-0 di 0.00–0.55, item-1 di 0.20–0.70,
        // item-2 di 0.40–0.85, item-3 di 0.55–1.00 — cukup berurutan tapi
        // total animasi tetap terasa cepat dan tidak ada item yang "terlambat".
        final float[] ITEM_START = {0.00f, 0.16f, 0.32f, 0.48f, 0.64f};
        final float[] ITEM_END   = {0.46f, 0.62f, 0.78f, 0.92f, 1.00f};

        for (int i = 0; i < itemLabels.length; i++) {
            // Posisi final item (saat progress = 1.0)
            float top    = boundingBox.bottom + gap + i * (itemH + gap);
            float bottom = top + itemH;
            float itemCy = (top + bottom) * 0.5f;

            // Progress lokal item-i: 0→1 dalam window [ITEM_START[i]..ITEM_END[i]]
            float window = ITEM_END[i] - ITEM_START[i];
            float itemProgress = Math.min(1f, Math.max(0f,
                    (menuAnimProgress - ITEM_START[i]) / window));

            // Ease-out: decelerating scale — terasa "pop" tanpa overshoot
            float scaleVal = 1f - (1f - itemProgress) * (1f - itemProgress);

            if (scaleVal <= 0f) continue;

            // Gambar item dengan transform scale dari pusat item
            canvas.save();
            canvas.scale(scaleVal, scaleVal, cx, itemCy);

            // Alpha item mengikuti menuAlpha (progress global) × scaleVal lokal
            // → fade-in mengikuti item itu sendiri, bukan semua sekaligus
            int itemAlpha = (int) (scaleVal * menuAlpha);

            // Background fill semi-transparan
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(ColorUtils.setAlphaComponent(primaryColor, (int)(40 * scaleVal)));
            canvas.drawRoundRect(boundingBox.left, top, boundingBox.right, bottom, itemR, itemR, paint);

            // Border — warna SAMA dengan tombol utama (primaryColor penuh × itemAlpha)
            // agar stroke item konsisten dengan stroke menu utama
            paint.setStyle(Paint.Style.STROKE);
            paint.setColor(ColorUtils.setAlphaComponent(primaryColor, itemAlpha));
            paint.setStrokeWidth(strokeWidth * 0.75f);
            canvas.drawRoundRect(boundingBox.left, top, boundingBox.right, bottom, itemR, itemR, paint);

            paint.setStyle(Paint.Style.FILL);
            paint.setColor(ColorUtils.setAlphaComponent(primaryColor, itemAlpha));

            // slot 1–4 untuk empat item sub-menu
            byte slotIcon = (i + 1 < slotIconIds.length) ? slotIconIds[i + 1] : 0;

            if (slotIcon > 0) {
                // Ada slot icon → icon di kiri, text label di kanan
                float iconAreaW = itemH * 0.78f;
                float iconSize  = iconAreaW * 0.80f;
                float iconCx    = boundingBox.left + iconAreaW * 0.5f + strokeWidth;
                drawIconExact(canvas, iconCx, itemCy, iconSize, iconSize, slotIcon);

                float textLeft  = boundingBox.left + iconAreaW + strokeWidth * 2;
                float textAvail = boundingBox.right - textLeft - strokeWidth;
                if (textAvail > 0) {
                    paint.setTextAlign(Paint.Align.LEFT);
                    float ts = Math.min(
                            getTextSizeForWidth(paint, itemLabels[i], textAvail),
                            snappingSize * 1.55f * scale);
                    paint.setTextSize(ts);
                    canvas.drawText(itemLabels[i], textLeft,
                            itemCy - (paint.descent() + paint.ascent()) * 0.5f, paint);
                }
            } else {
                // Tidak ada slot icon → unicode + label di tengah
                String fullLabel = itemFallback[i] + " " + itemLabels[i];
                paint.setTextAlign(Paint.Align.CENTER);
                float ts = Math.min(
                        getTextSizeForWidth(paint, fullLabel, w - strokeWidth * 4),
                        snappingSize * 1.6f * scale);
                paint.setTextSize(ts);
                canvas.drawText(fullLabel, cx,
                        itemCy - (paint.descent() + paint.ascent()) * 0.5f, paint);
            }

            canvas.restore();
        }
    }

    /**
     * Menggambar MULTIPLE_BUTTON.
     *
     * ── Tombol utama ──────────────────────────────────────────────────────
     * Mengikuti persis logika BUTTON + MENU_NAVIGATION:
     *  - shape (CIRCLE / ROUND_RECT / dll) mengikuti property shape elemen
     *  - iconId global (icon tombol utama) → drawIconExact seperti BUTTON
     *  - teks custom atau fallback "▤"
     *  - fill semi-transparan lebih terang saat expanded
     *
     * ── Sub-buttons (saat expanded) ──────────────────────────────────────
     * Persis pola drawMenuNavigation:
     *  - Setiap arah (0=UP … 7=UP_LEFT) punya "lane" tersendiri
     *  - Button yang arahnya sama disusun BERURUTAN (tidak saling tumpuk)
     *    dengan jarak gap sama seperti MENU_NAVIGATION
     *  - Ukuran setiap sub-button = ukuran tombol utama (h × w)
     *  - Shape sub-button mengikuti shape tombol utama
     *  - Animasi pop identik dengan MENU_NAVIGATION: cascade window per item,
     *    ease-out scale dari pusat item, fade-in alpha
     *  - Icon sub-button → multiButtonIconIds[i] via drawIconExact
     *  - Teks sub-button → multiButtonTexts[i]
     *  - Fallback: nama binding pertama (disingkat)
     *
     * ── Hit-test ──────────────────────────────────────────────────────────
     * isMultiBtnSubHit() sekarang pakai getMultiBtnSubItemRect() yang juga
     * menghitung posisi berurutan per lane — konsisten dengan draw.
     */
    private void drawMultipleButton(Canvas canvas, Rect boundingBox, Paint paint,
                                    int primaryColor, float strokeWidth, int snappingSize) {
        float cx   = boundingBox.centerX();
        float cy   = boundingBox.centerY();
        float w    = boundingBox.width();
        float h    = boundingBox.height();
        float r    = h * 0.5f;
        float itemR = r * 0.6f; // corner radius sub-button (identik MENU_NAVIGATION)

        paint.setStrokeWidth(strokeWidth * 0.75f);

        // ── Tombol utama — shape mengikuti property elemen ─────────────────
        if (iconId > 0) {
            // Ada global icon → stroke + icon (identik BUTTON dengan icon)
            paint.setColor(primaryColor);
            paint.setStyle(isPressed ? Paint.Style.FILL_AND_STROKE : Paint.Style.STROKE);
            drawShapeOutline(canvas, paint, boundingBox, r, itemR);
            paint.setStyle(Paint.Style.FILL);
            float iconSize = Math.min(w, h) * (isPressed ? 1.0f : 0.78f);
            drawIconExact(canvas, cx, cy, iconSize, iconSize, iconId);
        } else {
            // Tidak ada icon → background semi-transparan + border + teks
            int bgAlpha = multiBtnExpanded ? (isPressed ? 100 : 65) : (isPressed ? 80 : 40);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(ColorUtils.setAlphaComponent(primaryColor, bgAlpha));
            drawShapeOutline(canvas, paint, boundingBox, r, itemR);

            paint.setStyle(Paint.Style.STROKE);
            paint.setColor(primaryColor);
            drawShapeOutline(canvas, paint, boundingBox, r, itemR);

            paint.setStyle(Paint.Style.FILL);
            paint.setTextAlign(Paint.Align.CENTER);
            String label = (text != null && !text.isEmpty()) ? text : "\u25A4"; // ▤ fallback
            float ts = Math.min(
                    getTextSizeForWidth(paint, label, w - strokeWidth * 4),
                    snappingSize * 1.6f * scale);
            paint.setTextSize(ts);
            canvas.drawText(label, cx, cy - (paint.descent() + paint.ascent()) * 0.5f, paint);
        }

        // ── Sub-buttons ────────────────────────────────────────────────────
        if (multiBtnAnimProgress <= 0f) return;

        float gap = snappingSize * 0.4f * scale;
        int menuAlpha = (int)(multiBtnAnimProgress * 255);

        // Kumpulkan jumlah sub-button per arah agar bisa menghitung urutan
        // lane[dir] = berapa banyak sub-button di arah ini yang sudah digambar
        int[] laneCount = new int[8];

        // Dua pass: pass pertama hitung total per lane untuk cascade timing
        int[] laneTotalCount = new int[8];
        int visibleTotal = 0;
        for (int i = 0; i < multiButtonCount; i++) {
            byte dir = multiButtonDirections[i];
            if (dir == (byte) 0xFF) continue;
            laneTotalCount[dir & 0x07]++;
            visibleTotal++;
        }

        // Global index (0-based) hanya untuk visible items — digunakan untuk cascade window
        int globalVisible = 0;
        for (int i = 0; i < multiButtonCount; i++) {
            byte dirByte = multiButtonDirections[i];
            if (dirByte == (byte) 0xFF) continue;

            int dir = dirByte & 0x07;
            int laneIdx = laneCount[dir]; // posisi urutan di lane ini (0 = terdekat)
            laneCount[dir]++;

            // ── Hitung posisi final item (saat progress = 1.0) ─────────────
            // Posisi: berurutan dari tepi tombol utama ke arah dir
            // Gap pertama: jarak dari tepi tombol utama ke item pertama
            // Gap berikutnya: antar item dalam lane yang sama
            RectF itemRect = computeMultiBtnSubRect(boundingBox, dir, laneIdx, w, h, gap);
            float itemCx = itemRect.centerX();
            float itemCy = itemRect.centerY();

            // ── Animasi cascade identik MENU_NAVIGATION ────────────────────
            // Bagi window progress merata di antara semua visible items
            // agar total animasi tetap cepat
            float windowSize   = Math.max(0.45f, 1.0f / Math.max(1, visibleTotal));
            float windowStart  = globalVisible * (1.0f - windowSize) / Math.max(1, visibleTotal - 1);
            if (visibleTotal == 1) windowStart = 0f;
            float windowEnd    = windowStart + windowSize;
            windowEnd          = Math.min(1.0f, windowEnd);

            float window       = windowEnd - windowStart;
            float itemProgress = Math.min(1f, Math.max(0f,
                    (multiBtnAnimProgress - windowStart) / window));
            float scaleVal     = 1f - (1f - itemProgress) * (1f - itemProgress); // ease-out
            if (scaleVal <= 0f) { globalVisible++; continue; }

            canvas.save();
            canvas.scale(scaleVal, scaleVal, itemCx, itemCy);

            int itemAlpha = (int)(scaleVal * menuAlpha);
            boolean subPressed = (multiBtnPressedIndex == i);

            // Background fill
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(ColorUtils.setAlphaComponent(primaryColor,
                    subPressed ? (int)(80 * scaleVal) : (int)(40 * scaleVal)));
            drawShapeRectOutline(canvas, paint, itemRect, itemR);

            // Border
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(strokeWidth * 0.75f);
            paint.setColor(ColorUtils.setAlphaComponent(primaryColor, itemAlpha));
            drawShapeRectOutline(canvas, paint, itemRect, itemR);

            // Icon / teks sub-button
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(ColorUtils.setAlphaComponent(primaryColor, itemAlpha));

            byte subIcon = multiButtonIconIds[i];
            String subText = (multiButtonTexts[i] != null) ? multiButtonTexts[i] : "";

            if (subIcon > 0) {
                // Custom icon sub-button — pakai drawIconExact sama seperti BUTTON/MENU_NAVIGATION
                float iconSize = Math.min(w, h) * 0.78f;
                drawIconExact(canvas, itemCx, itemCy, iconSize, iconSize, subIcon);
            } else if (!subText.isEmpty()) {
                // Custom text label
                paint.setTextAlign(Paint.Align.CENTER);
                float subTs = Math.min(
                        getTextSizeForWidth(paint, subText, w - strokeWidth * 4),
                        snappingSize * 1.6f * scale);
                paint.setTextSize(subTs);
                canvas.drawText(subText, itemCx,
                        itemCy - (paint.descent() + paint.ascent()) * 0.5f, paint);
            } else {
                // Fallback: nama binding pertama (disingkat)
                List<Binding> sb = getMultiButtonBindings(i);
                String bindLabel = sb.isEmpty() ? "?" :
                        sb.get(0).toString().replace("NUMPAD ", "NP")
                                           .replace("BUTTON_", "")
                                           .replace("KEY_", "");
                if (bindLabel.length() > 6) bindLabel = bindLabel.substring(0, 5) + "…";
                paint.setTextAlign(Paint.Align.CENTER);
                float subTs = Math.min(
                        getTextSizeForWidth(paint, bindLabel, w - strokeWidth * 4),
                        snappingSize * 1.5f * scale);
                paint.setTextSize(subTs);
                canvas.drawText(bindLabel, itemCx,
                        itemCy - (paint.descent() + paint.ascent()) * 0.5f, paint);
            }

            canvas.restore();
            globalVisible++;
        }
    }

    /**
     * Gambar outline shape (CIRCLE atau RoundRect) untuk tombol utama.
     * Shape mengikuti property elemen, bukan hardcode circle.
     */
    private void drawShapeOutline(Canvas canvas, Paint paint, Rect bb, float r, float itemR) {
        switch (shape) {
            case CIRCLE:
                canvas.drawCircle(bb.centerX(), bb.centerY(), r, paint);
                break;
            default:
                canvas.drawRoundRect(bb.left, bb.top, bb.right, bb.bottom, itemR, itemR, paint);
        }
    }

    /**
     * Gambar outline shape untuk sub-button menggunakan RectF.
     * Sub-button mengikuti shape tombol utama.
     */
    private void drawShapeRectOutline(Canvas canvas, Paint paint, RectF rf, float itemR) {
        switch (shape) {
            case CIRCLE:
                canvas.drawCircle(rf.centerX(), rf.centerY(),
                        Math.min(rf.width(), rf.height()) * 0.5f, paint);
                break;
            default:
                canvas.drawRoundRect(rf, itemR, itemR, paint);
        }
    }

    /**
     * Hitung RectF posisi final sub-button pada lane direction tertentu.
     *
     * Logika posisi (identik MENU_NAVIGATION):
     *  - Arah UP/DOWN: sub-button disusun vertikal, lebar = lebar tombol utama
     *  - Arah LEFT/RIGHT: sub-button disusun horizontal, tinggi = tinggi tombol utama
     *  - Arah diagonal (UP_RIGHT, DOWN_RIGHT, dll): disusun diagonal,
     *    ukuran = tombol utama, offset per step = (w+gap) pada sumbu X dan (h+gap) pada sumbu Y
     *
     * @param bb       bounding box tombol utama
     * @param dir      arah 0=UP,1=UP_RIGHT,2=RIGHT,3=DOWN_RIGHT,4=DOWN,5=DOWN_LEFT,6=LEFT,7=UP_LEFT
     * @param laneIdx  urutan di lane ini (0 = terdekat dari tombol utama)
     * @param w        lebar tombol utama
     * @param h        tinggi tombol utama
     * @param gap      jarak antar item
     * @return RectF posisi final
     */
    private RectF computeMultiBtnSubRect(Rect bb, int dir, int laneIdx, float w, float h, float gap) {
        float step = laneIdx + 1; // step ke-1 = item pertama (terdekat), +1 per item berikutnya

        // Vektor arah per-step (dalam satuan item + gap)
        // 8 arah: 0=UP, 1=UP_RIGHT, 2=RIGHT, 3=DOWN_RIGHT, 4=DOWN, 5=DOWN_LEFT, 6=LEFT, 7=UP_LEFT
        float[] dx = {0,  1,  1,  1,  0, -1, -1, -1};  // +1 = kanan, -1 = kiri
        float[] dy = {-1, -1,  0,  1,  1,  1,  0, -1}; // +1 = bawah, -1 = atas

        // Offset item: jarak dari tepi bounding box tombol utama
        // Pada sumbu X: step * (w + gap) jika dx != 0
        // Pada sumbu Y: step * (h + gap) jika dy != 0
        float offsetX = dx[dir] * step * (w + gap);
        float offsetY = dy[dir] * step * (h + gap);

        float left   = bb.left   + offsetX;
        float top    = bb.top    + offsetY;
        float right  = bb.right  + offsetX;
        float bottom = bb.bottom + offsetY;

        return new RectF(left, top, right, bottom);
    }

    private void drawIcon(Canvas canvas, float cx, float cy, float width, float height, int iconId) {
        // Pola defensif seperti TouchAreaButton: cek null sebelum akses bitmap
        Bitmap icon = inputControlsView.getIcon((byte) iconId);
        if (icon == null || icon.isRecycled()) return;

        Paint paint = inputControlsView.getPaint();
        paint.setColorFilter(inputControlsView.getColorFilter());

        // Hitung ukuran icon dengan margin seperti TouchAreaButton (70% dari area)
        float iconWidth;
        float iconHeight;
        float marginFactor = (shape == Shape.CIRCLE || shape == Shape.SQUARE) ? 0.65f : 0.75f;
        iconWidth = width * marginFactor;
        iconHeight = height * marginFactor;

        // Pertahankan aspek rasio bitmap (pola dari TouchAreaButton)
        float bitmapW = icon.getWidth();
        float bitmapH = icon.getHeight();
        if (bitmapW > 0 && bitmapH > 0) {
            float aspectRatio = bitmapW / bitmapH;
            if (aspectRatio > 1f) {
                iconHeight = iconWidth / aspectRatio;
            } else {
                iconWidth = iconHeight * aspectRatio;
            }
        }

        int halfW = (int) (iconWidth * 0.5f);
        int halfH = (int) (iconHeight * 0.5f);

        // Reuse cached Rect — tidak ada alokasi per frame (anti-lag)
        iconSrcRect.set(0, 0, (int) bitmapW, (int) bitmapH);
        iconDstRect.set((int) cx - halfW, (int) cy - halfH, (int) cx + halfW, (int) cy + halfH);
        canvas.drawBitmap(icon, iconSrcRect, iconDstRect, paint);
        paint.setColorFilter(null);
    }

    // Seperti drawIcon tapi ukuran width/height adalah ukuran FINAL (tidak dikalikan marginFactor)
    // Dipakai untuk outer icon STICK/RIGHT_STICK agar pas dengan diameter stroke lingkaran
    private void drawIconExact(Canvas canvas, float cx, float cy, float width, float height, int iconId) {
        Bitmap icon = inputControlsView.getIcon((byte) iconId);
        if (icon == null || icon.isRecycled()) return;

        Paint paint = inputControlsView.getPaint();
        paint.setColorFilter(inputControlsView.getColorFilter());

        float iconWidth = width;
        float iconHeight = height;

        float bitmapW = icon.getWidth();
        float bitmapH = icon.getHeight();
        if (bitmapW > 0 && bitmapH > 0) {
            float aspectRatio = bitmapW / bitmapH;
            if (aspectRatio > 1f) {
                iconHeight = iconWidth / aspectRatio;
            } else {
                iconWidth = iconHeight * aspectRatio;
            }
        }

        int halfW = (int) (iconWidth * 0.5f);
        int halfH = (int) (iconHeight * 0.5f);

        // Reuse cached Rect — tidak ada alokasi per frame (anti-lag)
        iconSrcRect.set(0, 0, (int) bitmapW, (int) bitmapH);
        iconDstRect.set((int) cx - halfW, (int) cy - halfH, (int) cx + halfW, (int) cy + halfH);
        canvas.drawBitmap(icon, iconSrcRect, iconDstRect, paint);
        paint.setColorFilter(null);
    }

    public JSONObject toJSONObject() {
        try {
            JSONObject elementJSONObject = new JSONObject();
            elementJSONObject.put("type", type.name());
            elementJSONObject.put("shape", shape.name());

            JSONArray bindingsJSONArray = new JSONArray();
            for (Binding binding : bindings) {
                bindingsJSONArray.put(binding.name());
            }

            elementJSONObject.put("bindings", bindingsJSONArray);
            elementJSONObject.put("scale", Float.valueOf(scale));
            elementJSONObject.put("x", (float) x / inputControlsView.getMaxWidth());
            elementJSONObject.put("y", (float) y / inputControlsView.getMaxHeight());
            elementJSONObject.put("toggleSwitch", toggleSwitch);
            elementJSONObject.put("text", text);
            elementJSONObject.put("iconId", iconId);

            // Simpan slot icons (D_PAD arah, STICK/RIGHT_STICK outer+inner)
            JSONArray slotIconsArray = new JSONArray();
            for (byte sid : slotIconIds) slotIconsArray.put(sid);
            elementJSONObject.put("slotIconIds", slotIconsArray);

            if (type == Type.RANGE_BUTTON && range != null) {
                elementJSONObject.put("range", range.name());
                if (orientation != 0) elementJSONObject.put("orientation", orientation);
            }

            if (type == Type.TOUCHSCREEN_TOGGLE) {
                elementJSONObject.put("selected", selected);
            }

            if (type == Type.RIGHT_STICK) {
                elementJSONObject.put("isCursorMove", isCursorMove);
                if (isCursorMove) {
                    elementJSONObject.put("cursorMoveRadius", cursorMoveRadius);
                }
            }

            // MENU_NAVIGATION tidak menyimpan state expanded (selalu mulai collapsed)

            // MULTIPLE_BUTTON: simpan semua data sub-button
            if (type == Type.MULTIPLE_BUTTON) {
                elementJSONObject.put("multiButtonCount", multiButtonCount);
                JSONArray mbArr = new JSONArray();
                for (int i = 0; i < MULTI_BTN_MAX; i++) {
                    JSONObject mbObj = new JSONObject();
                    // bindings sub-button ke-i
                    JSONArray mbBindings = new JSONArray();
                    List<Binding> sbList = getMultiButtonBindings(i);
                    for (Binding b : sbList) mbBindings.put(b.name());
                    mbObj.put("bindings", mbBindings);
                    // Simpan direction: 0xFF (hidden) → -1, lainnya normal
                    int dirInt = (multiButtonDirections[i] == (byte) 0xFF) ? -1 : (multiButtonDirections[i] & 0xFF);
                    mbObj.put("direction", dirInt);
                    mbObj.put("text", multiButtonTexts[i] != null ? multiButtonTexts[i] : "");
                    mbObj.put("iconId", multiButtonIconIds[i]);
                    mbArr.put(mbObj);
                }
                elementJSONObject.put("multiButtons", mbArr);
            }

            return elementJSONObject;
        } catch (JSONException e) {
            return null;
        }
    }

    public boolean containsPoint(float x, float y) {
        if (getBoundingBox().contains((int) (x + 0.5f), (int) (y + 0.5f))) return true;
        // MENU_NAVIGATION: area hit-test diperluas ke bawah saat sub-menu sedang expanded
        if (type == Type.MENU_NAVIGATION && menuExpanded && menuAnimProgress > 0.5f) {
            Rect bb = getBoundingBox();
            int snappingSize = inputControlsView.getSnappingSize();
            float gap    = snappingSize * 0.4f * scale;
            float itemH  = bb.height();
            float totalH = (itemH + gap) * 4;
            float bottom = bb.bottom + gap + totalH;
            if (x >= bb.left && x <= bb.right && y >= bb.bottom && y <= bottom) return true;
        }
        // MULTIPLE_BUTTON: hit-test meluas ke sub-buttons saat expanded
        if (type == Type.MULTIPLE_BUTTON && multiBtnExpanded && multiBtnAnimProgress > 0.1f) {
            Rect bb  = getBoundingBox();
            float w  = bb.width();
            float h  = bb.height();
            float gap = inputControlsView.getSnappingSize() * 0.4f * scale;
            int[] laneCount = new int[8];
            for (int i = 0; i < multiButtonCount; i++) {
                byte d = multiButtonDirections[i];
                if (d == (byte) 0xFF) continue;
                int dir = d & 0x07;
                RectF r = computeMultiBtnSubRect(bb, dir, laneCount[dir], w, h, gap);
                if (r.contains(x, y)) return true;
                laneCount[dir]++;
            }
        }
        return false;
    }

    private boolean isKeepButtonPressedAfterMinTime() {
        Binding binding = getBindingAt(0);
        return !toggleSwitch && (binding == Binding.GAMEPAD_BUTTON_L3 || binding == Binding.GAMEPAD_BUTTON_R3);
    }

    public boolean handleTouchDown(int pointerId, float x, float y) {
        // MENU_NAVIGATION: cek apakah sentuhan mengenai item sub-menu terlebih dahulu
        if (type == Type.MENU_NAVIGATION && menuExpanded && menuAnimProgress > 0.5f) {            Rect bb = getBoundingBox();
            int snappingSize = inputControlsView.getSnappingSize();
            float h    = bb.height();
            float gap  = snappingSize * 0.4f * scale;
            float itemH = h;

            for (int i = 0; i < 4; i++) {
                float top    = bb.bottom + gap + i * (itemH + gap);
                float bottom = top + itemH;
                if (x >= bb.left && x <= bb.right && y >= top && y <= bottom) {
                    // Item sub-menu tersentuh — eksekusi aksi langsung
                    // Menu TIDAK ditutup; hanya tombol utama yang toggle expand/collapse
                    executeMenuAction(i);
                    return true;
                }
            }
        }

        // MULTIPLE_BUTTON: cek sub-button hit sebelum tombol utama
        if (type == Type.MULTIPLE_BUTTON && multiBtnExpanded && multiBtnAnimProgress > 0.3f) {
            for (int i = 0; i < multiButtonCount; i++) {
                if (isMultiBtnSubHit(i, x, y)) {
                    multiBtnPressedIndex = i;
                    inputControlsView.invalidate();
                    List<Binding> sb = getMultiButtonBindings(i);
                    for (Binding b : sb) {
                        if (b != Binding.NONE) inputControlsView.handleInputEvent(b, true);
                    }
                    return true;
                }
            }
        }

        if (currentPointerId == -1 && containsPoint(x, y)) {
            currentPointerId = pointerId;
            isPressed = true;
            inputControlsView.invalidate();

            if (type == Type.MENU_NAVIGATION) {
                // Sentuhan pada tombol utama → toggle expand/collapse sub-menu
                return true;
            } else if (type == Type.MULTIPLE_BUTTON) {
                // Sentuhan pada tombol utama → toggle expand/collapse sub-buttons
                return true;
            } else if (type == Type.BUTTON || type == Type.TOUCHSCREEN_TOGGLE) {
                if (isKeepButtonPressedAfterMinTime()) touchTime = System.currentTimeMillis();

                // Tekan SEMUA binding
                for (int i = 0; i < bindings.size(); i++) {
                    Binding b = bindings.get(i);
                    if (b != Binding.NONE) {
                        if (!toggleSwitch || !selected) {
                            inputControlsView.handleInputEvent(b, true);
                        }
                    }
                }
                return true;
            } else if (type == Type.RANGE_BUTTON) {
                scroller.handleTouchDown(x, y);
                return true;
            } else {
                if (type == Type.TRACKPAD || type == Type.RIGHT_STICK) {
                    if (currentPosition == null) currentPosition = new PointF();
                    currentPosition.set(x, y);
                }
                return handleTouchMove(pointerId, x, y);
            }
        } else return false;
    }

    public boolean handleTouchMove(int pointerId, float x, float y) {
        if (pointerId == currentPointerId && (type == Type.D_PAD || type == Type.STICK || type == Type.TRACKPAD || type == Type.RIGHT_STICK)) {
            float deltaX, deltaY;
            Rect boundingBox = getBoundingBox();
            float radius = boundingBox.width() * 0.5f;
            // Hindari radius nol — dapat terjadi saat layout belum selesai
            if (radius <= 0) return false;
            TouchpadView touchpadView = inputControlsView.getTouchpadView();

            if (type == Type.TRACKPAD) {
                if (currentPosition == null) currentPosition = new PointF();
                float[] deltaPoint = touchpadView.computeDeltaPoint(currentPosition.x, currentPosition.y, x, y);
                deltaX = deltaPoint[0];
                deltaY = deltaPoint[1];
                currentPosition.set(x, y);
            } else if (type == Type.RIGHT_STICK) {
                // Delta ternormalisasi -1..1 dari posisi jari dalam bounding box (seperti STICK)
                float localX = x - boundingBox.left;
                float localY = y - boundingBox.top;
                float offsetX = localX - radius;
                float offsetY = localY - radius;
                float distance = Mathf.lengthSq(offsetX, offsetY);
                if (distance > radius * radius) {
                    // Normalisasi vektor langsung — lebih cepat dari atan2/cos/sin
                    float len = (float) Math.sqrt(distance);
                    offsetX = offsetX / len * radius;
                    offsetY = offsetY / len * radius;
                }
                deltaX = Mathf.clamp(offsetX / radius, -1, 1);
                deltaY = Mathf.clamp(offsetY / radius, -1, 1);

                // Update posisi visual thumbstick (dibatasi dalam outer circle)
                if (visualThumbPosition == null) visualThumbPosition = new PointF();
                visualThumbPosition.x = boundingBox.left + offsetX + radius;
                visualThumbPosition.y = boundingBox.top + offsetY + radius;
            } else {
                float localX = x - boundingBox.left;
                float localY = y - boundingBox.top;
                float offsetX = localX - radius;
                float offsetY = localY - radius;

                // Gunakan formula yang sama dengan RIGHT_STICK (lengthSq + normalisasi vektor)
                // untuk konsistensi dan menghindari hasil berbeda antara atan2 vs normalisasi
                // saat jari berada persis di batas lingkaran.
                float distance = Mathf.lengthSq(offsetX, offsetY);
                if (distance > radius * radius) {
                    float len = (float) Math.sqrt(distance);
                    offsetX = offsetX / len * radius;
                    offsetY = offsetY / len * radius;
                }

                deltaX = Mathf.clamp(offsetX / radius, -1, 1);
                deltaY = Mathf.clamp(offsetY / radius, -1, 1);
            }

            if (type == Type.STICK) {
                if (currentPosition == null) currentPosition = new PointF();
                currentPosition.x = boundingBox.left + deltaX * radius + radius;
                currentPosition.y = boundingBox.top + deltaY * radius + radius;

                final boolean[] newStates = {
                        deltaY <= -STICK_DEAD_ZONE,
                        deltaX >= STICK_DEAD_ZONE,
                        deltaY >= STICK_DEAD_ZONE,
                        deltaX <= -STICK_DEAD_ZONE
                };

                for (byte i = 0; i < 4; i++) {
                    float value = (i == 1 || i == 3) ? deltaX : deltaY;
                    Binding binding = getBindingAt(i);

                    if (binding.isGamepad()) {
                        float gamepadValue = Mathf.clamp(
                                Math.max(0, Math.abs(value) - 0.01f) * Mathf.sign(value) * STICK_SENSITIVITY,
                                -1, 1
                        );
                        boolean isActiveNow = Math.abs(gamepadValue) > 0.01f;

                        if (isActiveNow != this.states[i]) {
                            inputControlsView.handleInputEvent(binding, isActiveNow, isActiveNow ? gamepadValue : 0f);
                            this.states[i] = isActiveNow;
                        } else if (isActiveNow) {
                            inputControlsView.handleInputEvent(binding, true, gamepadValue);
                        }
                    } else {
                        boolean state = binding.isMouseMove()
                                ? (newStates[i] || newStates[(i + 2) % 4])
                                : newStates[i];

                        if (state != this.states[i]) {
                            inputControlsView.handleInputEvent(binding, state, value);
                            this.states[i] = state;
                        }
                    }
                }

                inputControlsView.invalidate();
            } else if (type == Type.TRACKPAD) {
                final boolean[] newStates = {deltaY <= -TRACKPAD_MIN_SPEED, deltaX >= TRACKPAD_MIN_SPEED, deltaY >= TRACKPAD_MIN_SPEED, deltaX <= -TRACKPAD_MIN_SPEED};
                int cursorDx = 0;
                int cursorDy = 0;

                for (byte i = 0; i < 4; i++) {
                    float value = (i == 1 || i == 3 ? deltaX : deltaY);
                    Binding binding = getBindingAt(i);
                    if (binding.isGamepad()) {
                        if (interpolator == null) interpolator = new CubicBezierInterpolator();
                        if (Math.abs(value) > TRACKPAD_ACCELERATION_THRESHOLD) value *= STICK_SENSITIVITY;
                        interpolator.set(0.075f, 0.95f, 0.45f, 0.95f);
                        float interpolatedValue = interpolator.getInterpolation(Math.min(1.0f, Math.abs(value / TRACKPAD_MAX_SPEED)));
                        inputControlsView.handleInputEvent(binding, true, Mathf.clamp(interpolatedValue * Mathf.sign(value), -1, 1));
                        this.states[i] = true;
                    } else {
                        if (Math.abs(value) > TouchpadView.CURSOR_ACCELERATION_THRESHOLD) value *= TouchpadView.CURSOR_ACCELERATION;
                        if (binding == Binding.MOUSE_MOVE_LEFT || binding == Binding.MOUSE_MOVE_RIGHT) {
                            cursorDx = Mathf.roundPoint(value);
                        } else if (binding == Binding.MOUSE_MOVE_UP || binding == Binding.MOUSE_MOVE_DOWN) {
                            cursorDy = Mathf.roundPoint(value);
                        } else {
                            inputControlsView.handleInputEvent(binding, newStates[i], value);
                            this.states[i] = newStates[i];
                        }
                    }
                }

                if (cursorDx != 0 || cursorDy != 0) {
                    XServer xServer = inputControlsView.getXServer();
                    if (xServer.isRelativeMouseMovement())
                        xServer.getWinHandler().mouseEvent(MouseEventFlags.MOVE, cursorDx, cursorDy, 0);
                    else
                        inputControlsView.getXServer().injectPointerMoveDelta(cursorDx, cursorDy);
                }
            } else if (type == Type.RIGHT_STICK) {
                if (isCursorMove) {
                    // === CURSOR MOVE MODE ===
                    // Center layar PERMANEN. Pointer melanjutkan dari posisi terakhir
                    // tanpa kembali ke center saat jari disentuhkan lagi.
                    //
                    // Formula:
                    //   totalOffset = clamp(lastOffset + (currentDelta - startDelta), -1..1)
                    //   pointerPos  = center + totalOffset * radius

                    XServer xServer = inputControlsView.getXServer();

                    // Hitung center sekali — tidak pernah berubah
                    if (cursorMoveCenterX < 0) {
                        cursorMoveCenterX = xServer.screenInfo.width  / 2f;
                        cursorMoveCenterY = xServer.screenInfo.height / 2f;
                    }

                    // Rekam posisi jari saat pertama menyentuh sebagai startDelta
                    if (!cursorMoveStartRecorded) {
                        cursorMoveStartDeltaX   = deltaX;
                        cursorMoveStartDeltaY   = deltaY;
                        cursorMoveStartRecorded = true;
                    }

                    // Hitung total offset: offset terakhir + pergerakan jari sejak sentuhan ini
                    float totalOffsetX = cursorMoveLastOffsetX + (deltaX - cursorMoveStartDeltaX);
                    float totalOffsetY = cursorMoveLastOffsetY + (deltaY - cursorMoveStartDeltaY);

                    // Clamp MELINGKAR — normalkan vektor jika panjangnya > 1
                    // agar batas pergerakan pointer berbentuk LINGKARAN, bukan kotak.
                    float offsetLen = (float) Math.sqrt(totalOffsetX * totalOffsetX + totalOffsetY * totalOffsetY);
                    if (offsetLen > 1f) {
                        totalOffsetX /= offsetLen;
                        totalOffsetY /= offsetLen;
                    }

                    // Perbarui lastOffset setiap frame — sehingga saat jari dilepas,
                    // nilai ini sudah mencerminkan posisi pointer terakhir yang dikirim
                    cursorMoveLastOffsetX = totalOffsetX;
                    cursorMoveLastOffsetY = totalOffsetY;

                    float newX = Mathf.clamp(
                            cursorMoveCenterX + totalOffsetX * cursorMoveRadius,
                            0, xServer.screenInfo.width);
                    float newY = Mathf.clamp(
                            cursorMoveCenterY + totalOffsetY * cursorMoveRadius,
                            0, xServer.screenInfo.height);

                    if (xServer.isRelativeMouseMovement()) {
                        xServer.getWinHandler().mouseEvent(MouseEventFlags.MOVE, (int) newX, (int) newY, 0);
                    } else {
                        xServer.injectPointerMove((int) newX, (int) newY);
                    }

                    inputControlsView.invalidate();
                }

                // Binding gamepad selalu dieksekusi — baik cursor move aktif maupun tidak.
                // Cursor move hanya mengontrol pergerakan pointer, binding tetap berjalan normal.
                final boolean[] newStates = {
                        deltaY <= -STICK_DEAD_ZONE,
                        deltaX >= STICK_DEAD_ZONE,
                        deltaY >= STICK_DEAD_ZONE,
                        deltaX <= -STICK_DEAD_ZONE
                };

                for (byte i = 0; i < 4; i++) {
                    float value = (i == 1 || i == 3) ? deltaX : deltaY;
                    Binding binding = getBindingAt(i);

                    if (binding.isGamepad()) {
                        float gamepadValue = Mathf.clamp(
                                Math.max(0, Math.abs(value) - 0.01f) * Mathf.sign(value) * STICK_SENSITIVITY,
                                -1, 1
                        );
                        boolean isActiveNow = Math.abs(gamepadValue) > 0.01f;

                        if (isActiveNow != this.states[i]) {
                            inputControlsView.handleInputEvent(binding, isActiveNow, isActiveNow ? gamepadValue : 0f);
                            this.states[i] = isActiveNow;
                        } else if (isActiveNow) {
                            inputControlsView.handleInputEvent(binding, true, gamepadValue);
                        }
                    } else {
                        boolean state = binding.isMouseMove()
                                ? (newStates[i] || newStates[(i + 2) % 4])
                                : newStates[i];
                        if (state != this.states[i]) {
                            inputControlsView.handleInputEvent(binding, state, value);
                            this.states[i] = state;
                        }
                    }
                }

                inputControlsView.invalidate();
            } else {
                final boolean[] newStates = {deltaY <= -DPAD_DEAD_ZONE, deltaX >= DPAD_DEAD_ZONE, deltaY >= DPAD_DEAD_ZONE, deltaX <= -DPAD_DEAD_ZONE};

                for (byte i = 0; i < 4; i++) {
                    float value = i == 1 || i == 3 ? deltaX : deltaY;
                    Binding binding = getBindingAt(i);
                    boolean state = binding.isMouseMove() ? (newStates[i] || newStates[(i + 2) % 4]) : newStates[i];
                    inputControlsView.handleInputEvent(binding, state, value);
                    this.states[i] = state;
                }
            }

            return true;
        } else if (pointerId == currentPointerId && type == Type.RANGE_BUTTON) {
            scroller.handleTouchMove(x, y);
            return true;
        } else return false;
    }

    public boolean handleTouchUp(int pointerId) {
        // MULTIPLE_BUTTON: lepaskan sub-button yang sedang ditekan
        if (type == Type.MULTIPLE_BUTTON && multiBtnPressedIndex >= 0) {
            int idx = multiBtnPressedIndex;
            multiBtnPressedIndex = -1;
            inputControlsView.invalidate();
            List<Binding> sb = getMultiButtonBindings(idx);
            for (Binding b : sb) {
                if (b != Binding.NONE) inputControlsView.handleInputEvent(b, false);
            }
            return true;
        }

        if (pointerId == currentPointerId) {
            isPressed = false;
            inputControlsView.invalidate();

            if (type == Type.MENU_NAVIGATION) {
                // Toggle sub-menu expand/collapse saat tombol utama dilepas
                toggleMenu();
                currentPointerId = -1;
                return true;
            }

            if (type == Type.MULTIPLE_BUTTON) {
                // Tombol utama dilepas → toggle expand/collapse sub-buttons
                toggleMultiBtn();
                currentPointerId = -1;
                return true;
            }

            if (type == Type.TOUCHSCREEN_TOGGLE) {
                TouchpadView tp = inputControlsView.getTouchpadView();
                if (tp != null) {
                    boolean current = tp.isSimTouchScreen();
                    boolean next = !current;
                    tp.setSimTouchScreen(next);
                    selected = next;
                    inputControlsView.invalidate();
                }
                currentPointerId = -1;
                return true;
            }

            if (type == Type.BUTTON) {
                Binding firstBinding = getBindingAt(0);
                if (isKeepButtonPressedAfterMinTime() && touchTime != null) {
                    selected = (System.currentTimeMillis() - (long) touchTime) > BUTTON_MIN_TIME_TO_KEEP_PRESSED;
                    if (!selected) {
                        inputControlsView.handleInputEvent(firstBinding, false);
                    }
                    touchTime = null;
                    inputControlsView.invalidate();
                } else {
                    // Lepaskan SEMUA binding
                    for (int i = 0; i < bindings.size(); i++) {
                        Binding b = bindings.get(i);
                        if (b != Binding.NONE) {
                            if (!toggleSwitch || selected) {
                                inputControlsView.handleInputEvent(b, false);
                            }
                        }
                    }
                }

                if (toggleSwitch) {
                    selected = !selected;
                    inputControlsView.invalidate();
                }
            } else if (type == Type.RANGE_BUTTON || type == Type.D_PAD || type == Type.STICK || type == Type.TRACKPAD || type == Type.RIGHT_STICK) {
                for (int i = 0; i < states.length; i++) {
                    if (states[i]) inputControlsView.handleInputEvent(getBindingAt(i), false);
                    states[i] = false;
                }

                if (type == Type.RANGE_BUTTON) {
                    scroller.handleTouchUp();
                } else if (type == Type.STICK || type == Type.RIGHT_STICK) {
                    // Reset posisi visual thumbstick ke center
                    currentPosition = null;
                    visualThumbPosition = null;

                    // Cursor Move Mode: simpan total offset saat ini sebagai lastOffset
                    // agar sentuhan berikutnya melanjutkan dari posisi pointer terakhir
                    if (type == Type.RIGHT_STICK && isCursorMove && cursorMoveStartRecorded) {
                        // Hitung ulang totalOffset terakhir dengan deltaX/Y saat ini
                        // (currentPosition sudah di-null, pakai nilai delta terakhir yang valid)
                        // Kita tidak punya akses delta di sini, tapi cursorMoveLastOffsetX/Y
                        // akan diperbarui di handleTouchMove sebelum touch up terjadi,
                        // jadi kita update lastOffset = lastOffset + (lastDelta - startDelta)
                        // Namun karena delta tidak tersedia di sini, kita simpan dengan cara
                        // memanfaatkan bahwa handleTouchMove selalu dipanggil sebelum handleTouchUp:
                        // lastOffset sudah benar dari update di handleTouchMove terakhir.
                        // Yang perlu dilakukan hanya reset flag startRecorded untuk sesi berikutnya.
                        cursorMoveStartRecorded = false;
                    }

                    inputControlsView.invalidate();
                }
            }
            currentPointerId = -1;
            return true;
        }
        return false;
    }

    /**
     * Dipanggil saat Android mengirim ACTION_CANCEL — misalnya saat sistem mengambil alih
     * gesture (scroll, notification bar), atau saat jari lain turun dan pointer capture
     * berubah. Tanpa ini, stick stuck di posisi terakhir sampai di-sentuh ulang.
     *
     * Perilakunya identik dengan handleTouchUp: semua binding di-release dan state direset,
     * tapi tanpa logika TOUCHSCREEN_TOGGLE / BUTTON karena cancel selalu berarti "batalkan".
     *
     * Jika pointerId == -1, force-cancel tanpa mengecek currentPointerId (dipakai saat
     * ACTION_CANCEL global dari InputControlsView).
     */
    public void handleTouchCancel(int pointerId) {
        if (pointerId == -1 || pointerId == currentPointerId) {
            isPressed = false;

            // Release semua binding yang sedang aktif
            for (int i = 0; i < states.length; i++) {
                if (states[i]) inputControlsView.handleInputEvent(getBindingAt(i), false);
                states[i] = false;
            }

            if (type == Type.STICK || type == Type.RIGHT_STICK) {
                currentPosition = null;
                visualThumbPosition = null;
                if (type == Type.RIGHT_STICK && isCursorMove) {
                    cursorMoveStartRecorded = false;
                }
            } else if (type == Type.RANGE_BUTTON) {
                scroller.handleTouchUp();
            } else if (type == Type.BUTTON) {
                // Pastikan semua binding button juga di-release saat cancel
                for (Binding b : bindings) {
                    if (b != Binding.NONE) inputControlsView.handleInputEvent(b, false);
                }
            } else if (type == Type.MENU_NAVIGATION) {
                // Cancel: collapse sub-menu tanpa mengeksekusi aksi
                if (menuExpanded) {
                    menuExpanded = false;
                    animateMenu(false);
                }
            } else if (type == Type.MULTIPLE_BUTTON) {
                // Cancel: lepaskan sub-button yang ditekan jika ada, collapse
                if (multiBtnPressedIndex >= 0) {
                    int idx = multiBtnPressedIndex;
                    multiBtnPressedIndex = -1;
                    List<Binding> sb = getMultiButtonBindings(idx);
                    for (Binding b : sb) {
                        if (b != Binding.NONE) inputControlsView.handleInputEvent(b, false);
                    }
                }
                if (multiBtnExpanded) {
                    multiBtnExpanded = false;
                    animateMultiBtn(false);
                }
            }

            currentPointerId = -1;
            inputControlsView.invalidate();
        }
    }

    public PointF getCurrentPosition() {
        // Jika stick tidak sedang digunakan (tidak ada sentuhan aktif),
        // kembalikan center boundingBox yang selalu up-to-date.
        // Berlaku untuk STICK dan RIGHT_STICK.
        if (currentPosition == null || currentPointerId == -1) {
            Rect bb = getBoundingBox();
            if (currentPosition == null) {
                currentPosition = new PointF();
            }
            currentPosition.set(bb.centerX(), bb.centerY());
        }
        return currentPosition;
    }

    public void setCurrentPosition(float x, float y) {
        if (currentPosition == null) {
            currentPosition = new PointF();
        }
        currentPosition.set(x, y);
        inputControlsView.invalidate();
    }
}

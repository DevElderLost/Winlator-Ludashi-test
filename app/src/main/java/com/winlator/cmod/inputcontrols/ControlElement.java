package com.winlator.cmod.inputcontrols;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PointF;
import android.graphics.Rect;

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
        BUTTON, D_PAD, RANGE_BUTTON, STICK, TRACKPAD, TOUCHSCREEN_TOGGLE, RIGHT_STICK;

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
    // Jika true, right stick menggerakkan pointer cursor (seperti touchpad) bukan gamepad axis.
    // Pointer dimulai dari tengah layar saat pertama kali disentuh, namun tidak kembali ke tengah
    // ketika disentuh lagi — posisi cursor tetap bertahan di mana terakhir kali ditinggalkan.
    // Radius pergerakan mengikuti lingkaran stick (dapat diatur via seekbar).
    private boolean isCursorMove = false;
    // Radius sensitivitas: seberapa jauh delta pointer per frame saat stick di pinggir (0–100, default 30)
    private int cursorMoveRadius = 30;
    // Apakah pointer sudah pernah diinisialisasi ke tengah layar saat mode ini aktif
    private boolean cursorMoveInitialized = false;

    // Icon per-slot: D_PAD=[up,right,down,left], STICK/RIGHT_STICK=[outer,inner]
    private byte[] slotIconIds = new byte[4];
    private CubicBezierInterpolator interpolator;
    private Object touchTime;

    // Cached Rect objects untuk drawIconExact — menghindari alokasi per frame (anti-lag)
    private final Rect iconSrcRect = new Rect();
    private final Rect iconDstRect = new Rect();

    // === TAMBAHAN UNTUK EFEK VISUAL PRESSED ===
    private boolean isPressed = false;

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
            // BUTTON dan tipe lain default 1 binding
            bindings.add(Binding.NONE);
            states = new boolean[1];
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
        // Reset inisialisasi agar pointer mulai dari tengah layar saat mode diaktifkan lagi
        if (!cursorMove) cursorMoveInitialized = false;
    }

    public int getCursorMoveRadius() {
        return cursorMoveRadius;
    }

    public void setCursorMoveRadius(int radius) {
        this.cursorMoveRadius = Math.max(1, Math.min(100, radius));
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
        bindings.clear();
        bindings.add(binding != null ? binding : Binding.NONE);
        states = new boolean[1];
        boundingBoxNeedsUpdate = true;
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
        }
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

            return elementJSONObject;
        } catch (JSONException e) {
            return null;
        }
    }

    public boolean containsPoint(float x, float y) {
        return getBoundingBox().contains((int) (x + 0.5f), (int) (y + 0.5f));
    }

    private boolean isKeepButtonPressedAfterMinTime() {
        Binding binding = getBindingAt(0);
        return !toggleSwitch && (binding == Binding.GAMEPAD_BUTTON_L3 || binding == Binding.GAMEPAD_BUTTON_R3);
    }

    public boolean handleTouchDown(int pointerId, float x, float y) {
        if (currentPointerId == -1 && containsPoint(x, y)) {
            currentPointerId = pointerId;
            isPressed = true;
            inputControlsView.invalidate();

            if (type == Type.BUTTON || type == Type.TOUCHSCREEN_TOGGLE) {
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

                float distance = Mathf.lengthSq(radius - localX, radius - localY);
                if (distance > radius * radius) {
                    float angle = (float) Math.atan2(offsetY, offsetX);
                    offsetX = (float) (Math.cos(angle) * radius);
                    offsetY = (float) (Math.sin(angle) * radius);
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
                    // deltaX / deltaY sudah ternormalisasi -1..1 dari logika RIGHT_STICK di atas.
                    // Kita gunakan nilai itu untuk menggerakkan pointer cursor secara delta,
                    // dengan radius sebagai skala kecepatan (mirip TRACKPAD).
                    // Pointer tidak di-reset ke tengah setiap sentuh — posisi bertahan.

                    XServer xServer = inputControlsView.getXServer();

                    // Inisialisasi pointer ke tengah layar hanya jika belum pernah dilakukan
                    if (!cursorMoveInitialized) {
                        int screenCx = xServer.screenInfo.width / 2;
                        int screenCy = xServer.screenInfo.height / 2;
                        xServer.injectPointerMove(screenCx, screenCy);
                        cursorMoveInitialized = true;
                    }

                    // Terapkan dead zone agar pointer tidak drift saat stick diam
                    float absX = Math.abs(deltaX);
                    float absY = Math.abs(deltaY);
                    if (absX < STICK_DEAD_ZONE) deltaX = 0;
                    if (absY < STICK_DEAD_ZONE) deltaY = 0;

                    if (deltaX != 0 || deltaY != 0) {
                        // Skala kecepatan: cursorMoveRadius adalah nilai 1–100,
                        // dipetakan ke rentang piksel per frame (0.5 – 25 px/frame)
                        float speedScale = 0.5f + (cursorMoveRadius / 100.0f) * 24.5f;

                        // Akselerasi: jika stick > 0.7, tambah kecepatan
                        float len = (float) Math.sqrt(deltaX * deltaX + deltaY * deltaY);
                        if (len > 0.7f) speedScale *= TouchpadView.CURSOR_ACCELERATION;

                        int dx = Mathf.roundPoint(deltaX * speedScale);
                        int dy = Mathf.roundPoint(deltaY * speedScale);

                        if (dx != 0 || dy != 0) {
                            if (xServer.isRelativeMouseMovement()) {
                                xServer.getWinHandler().mouseEvent(MouseEventFlags.MOVE, dx, dy, 0);
                            } else {
                                xServer.injectPointerMoveDelta(dx, dy);
                            }
                        }
                    }
                    inputControlsView.invalidate();
                } else {
                // Logika input seperti STICK biasa: nilai ternormalisasi -1..1 dikirim ke gamepad
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
                } // end else (normal gamepad mode)
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
        if (pointerId == currentPointerId) {
            isPressed = false;
            inputControlsView.invalidate();

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
                    inputControlsView.invalidate();
                }
            }
            currentPointerId = -1;
            return true;
        }
        return false;
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

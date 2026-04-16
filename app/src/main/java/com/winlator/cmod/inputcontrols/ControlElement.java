package com.winlator.cmod.inputcontrols;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PointF;
import android.graphics.Rect;
import android.graphics.RectF;
import android.view.animation.DecelerateInterpolator;
import android.view.inputmethod.InputMethodManager;

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

    public static class SubButton {
        public List<Binding> bindings = new ArrayList<>();
        public byte direction = (byte) 0xFF;
        public String text = "";
        public byte iconId = 0;
        public final RectF drawRect = new RectF();
        public boolean rectValid = false;

        public SubButton() {
            bindings.add(Binding.NONE);
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
    private PointF visualThumbPosition;
    private RangeScroller scroller;

    private boolean isCursorMove = false;
    private int cursorMoveRadius = 150;
    private float cursorMoveCenterX = -1f;
    private float cursorMoveCenterY = -1f;
    private float cursorMoveLastOffsetX = 0f;
    private float cursorMoveLastOffsetY = 0f;
    private float cursorMoveStartDeltaX = 0f;
    private float cursorMoveStartDeltaY = 0f;
    private boolean cursorMoveStartRecorded = false;

    private byte[] slotIconIds = new byte[7];
    private CubicBezierInterpolator interpolator;
    private Object touchTime;

    private final Rect iconSrcRect = new Rect();
    private final Rect iconDstRect = new Rect();

    private boolean isPressed = false;

    private boolean menuExpanded = false;
    private float menuAnimProgress = 0f;
    private ValueAnimator menuAnimator;

    private final ArrayList<SubButton> subButtons = new ArrayList<>();
    private boolean multiBtnExpanded = false;
    private float multiBtnAnimProgress = 0f;
    private ValueAnimator multiBtnAnimator;
    private int multiBtnPressedIndex = -1;

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
            bindings.add(Binding.NONE);
            states = new boolean[1];
        }

        if (type == Type.MULTIPLE_BUTTON) {
            subButtons.clear();
        }

        text = "";
        iconId = 0;
        range = null;
        boundingBoxNeedsUpdate = true;
    }

    public Type getType() { return type; }
    public void setType(Type type) { this.type = type; reset(); }
    public int getBindingCount() { return bindings.size(); }
    public void setBindingCount(int count) {
        while (bindings.size() > count) bindings.remove(bindings.size() - 1);
        while (bindings.size() < count) bindings.add(Binding.NONE);
        boolean[] newStates = new boolean[count];
        System.arraycopy(states, 0, newStates, 0, Math.min(states.length, count));
        states = newStates;
        boundingBoxNeedsUpdate = true;
    }
    public Shape getShape() { return shape; }
    public void setShape(Shape shape) { this.shape = shape; boundingBoxNeedsUpdate = true; }
    public Range getRange() { return range != null ? range : Range.FROM_A_TO_Z; }
    public void setRange(Range range) { this.range = range; }
    public byte getOrientation() { return orientation; }
    public void setOrientation(byte orientation) { this.orientation = orientation; boundingBoxNeedsUpdate = true; }
    public boolean isToggleSwitch() { return toggleSwitch; }
    public void setToggleSwitch(boolean toggleSwitch) { this.toggleSwitch = toggleSwitch; }
    public boolean isCursorMove() { return isCursorMove; }
    public void setCursorMove(boolean cursorMove) {
        this.isCursorMove = cursorMove;
        if (!cursorMove) {
            cursorMoveCenterX = -1f;
            cursorMoveCenterY = -1f;
            cursorMoveLastOffsetX = 0f;
            cursorMoveLastOffsetY = 0f;
            cursorMoveStartDeltaX = 0f;
            cursorMoveStartDeltaY = 0f;
            cursorMoveStartRecorded = false;
        }
    }
    public int getCursorMoveRadius() { return cursorMoveRadius; }
    public void setCursorMoveRadius(int radius) { this.cursorMoveRadius = Math.max(50, Math.min(500, radius)); }
    public boolean isMenuExpanded() { return menuExpanded; }
    public boolean isMultiBtnExpanded() { return multiBtnExpanded; }

    public List<SubButton> getSubButtons() { return subButtons; }
    public int getSubButtonCount() { return subButtons.size(); }
    public void addSubButton() {
        subButtons.add(new SubButton());
        invalidateSubButtonRects();
        boundingBoxNeedsUpdate = true;
    }
    public void removeSubButton(int index) {
        if (index >= 0 && index < subButtons.size()) {
            subButtons.remove(index);
            invalidateSubButtonRects();
            boundingBoxNeedsUpdate = true;
        }
    }
    public SubButton getSubButton(int index) {
        return (index >= 0 && index < subButtons.size()) ? subButtons.get(index) : null;
    }
    public List<Binding> getMultiButtonBindings(int index) {
        SubButton sb = getSubButton(index);
        return sb != null ? sb.bindings : new ArrayList<>();
    }
    public void setMultiButtonBindings(int index, List<Binding> b) {
        SubButton sb = getSubButton(index);
        if (sb != null) {
            sb.bindings.clear();
            if (b != null) sb.bindings.addAll(b);
            if (sb.bindings.isEmpty()) sb.bindings.add(Binding.NONE);
        }
    }
    public byte getMultiButtonDirection(int index) {
        SubButton sb = getSubButton(index);
        return sb != null ? sb.direction : (byte)0xFF;
    }
    public void setMultiButtonDirection(int index, byte dir) {
        SubButton sb = getSubButton(index);
        if (sb != null) {
            if (dir == (byte)0xFF) sb.direction = (byte)0xFF;
            else sb.direction = (byte)(((dir % 8) + 8) % 8);
            invalidateSubButtonRects();
        }
    }
    public String getMultiButtonText(int index) {
        SubButton sb = getSubButton(index);
        return sb != null ? sb.text : "";
    }
    public void setMultiButtonText(int index, String text) {
        SubButton sb = getSubButton(index);
        if (sb != null) sb.text = (text != null) ? text : "";
    }
    public byte getMultiButtonIconId(int index) {
        SubButton sb = getSubButton(index);
        return sb != null ? sb.iconId : 0;
    }
    public void setMultiButtonIconId(int index, byte id) {
        SubButton sb = getSubButton(index);
        if (sb != null) sb.iconId = id;
    }
    private void invalidateSubButtonRects() {
        for (SubButton sb : subButtons) sb.rectValid = false;
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

    private boolean isMultiBtnSubHit(int index, float px, float py) {
        if (multiBtnAnimProgress < 0.5f) return false;
        SubButton sb = getSubButton(index);
        if (sb == null || sb.direction == (byte)0xFF) return false;
        Rect bb = getBoundingBox();
        float w = bb.width(), h = bb.height();
        float gap = inputControlsView.getSnappingSize() * 0.4f * scale;
        int laneIdx = 0;
        for (int j = 0; j < index; j++) {
            SubButton other = getSubButton(j);
            if (other != null && other.direction != (byte)0xFF && (other.direction & 0x07) == (sb.direction & 0x07))
                laneIdx++;
        }
        ensureSubButtonRect(sb, laneIdx, w, h, gap);
        return sb.drawRect.contains(px, py);
    }

    private void animateMenu(boolean expand) {
        if (menuAnimator != null) menuAnimator.cancel();
        float from = menuAnimProgress;
        float to   = expand ? 1f : 0f;
        menuAnimator = ValueAnimator.ofFloat(from, to);
        menuAnimator.setDuration(380);
        menuAnimator.setInterpolator(new DecelerateInterpolator());
        menuAnimator.addUpdateListener(anim -> {
            menuAnimProgress = (float) anim.getAnimatedValue();
            inputControlsView.invalidate();
        });
        menuAnimator.start();
    }
    private void toggleMenu() {
        menuExpanded = !menuExpanded;
        animateMenu(menuExpanded);
    }

    private void executeMenuAction(int itemIndex) {
        Context context = inputControlsView.getContext();
        android.os.Handler uiHandler = new android.os.Handler(android.os.Looper.getMainLooper());
        switch (itemIndex) {
            case 0:
                uiHandler.post(() -> {
                    InputMethodManager imm = (InputMethodManager) context.getSystemService(Context.INPUT_METHOD_SERVICE);
                    if (imm != null) imm.toggleSoftInput(InputMethodManager.SHOW_FORCED, 0);
                });
                break;
            case 1:
                if (context instanceof com.winlator.cmod.XServerDisplayActivity) {
                    com.winlator.cmod.XServerDisplayActivity activity = (com.winlator.cmod.XServerDisplayActivity) context;
                    uiHandler.post(() -> new com.winlator.cmod.winhandler.TaskManagerDialog(activity).show());
                }
                break;
            case 2:
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
            case 3:
                if (context instanceof com.winlator.cmod.XServerDisplayActivity) {
                    com.winlator.cmod.XServerDisplayActivity activity = (com.winlator.cmod.XServerDisplayActivity) context;
                    uiHandler.post(() -> {
                        com.winlator.cmod.renderer.GLRenderer renderer = activity.getXServerView() != null ? activity.getXServerView().getRenderer() : null;
                        com.winlator.cmod.contentdialog.ActiveWindowsDialog.show(activity, activity.getXServer(), renderer);
                    });
                }
                break;
            case 4:
                if (context instanceof com.winlator.cmod.XServerDisplayActivity) {
                    com.winlator.cmod.XServerDisplayActivity activity = (com.winlator.cmod.XServerDisplayActivity) context;
                    uiHandler.post(activity::exitApp);
                }
                break;
        }
    }

    public Binding getBindingAt(int index) {
        return (index >= 0 && index < bindings.size()) ? bindings.get(index) : Binding.NONE;
    }
    public void setBindingAt(int index, Binding binding) {
        while (bindings.size() <= index) bindings.add(Binding.NONE);
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
                if (index + 1 < states.length)
                    System.arraycopy(states, index + 1, newStates, index, states.length - index - 1);
                states = newStates;
            }
            boundingBoxNeedsUpdate = true;
        }
    }
    public void setBinding(Binding binding) {
        Binding b = binding != null ? binding : Binding.NONE;
        for (int i = 0; i < bindings.size(); i++) bindings.set(i, b);
    }

    public float getScale() { return scale; }
    public void setScale(float scale) { this.scale = scale; boundingBoxNeedsUpdate = true; }
    public short getX() { return x; }
    public void setX(int x) { this.x = (short)x; boundingBoxNeedsUpdate = true; }
    public short getY() { return y; }
    public void setY(int y) { this.y = (short)y; boundingBoxNeedsUpdate = true; }
    public boolean isSelected() { return selected; }
    public void setSelected(boolean selected) { this.selected = selected; }
    public String getText() { return text; }
    public void setText(String text) { this.text = text != null ? text : ""; }
    public byte getIconId() { return iconId; }
    public void setIconId(int iconId) { this.iconId = (byte)iconId; }

    public byte getSlotIconId(int slot) {
        return (slot >= 0 && slot < slotIconIds.length) ? slotIconIds[slot] : 0;
    }
    public void setSlotIconId(int slot, byte id) {
        if (slot >= 0 && slot < slotIconIds.length) slotIconIds[slot] = id;
    }
    public byte[] getSlotIconIds() { return slotIconIds; }
    public void setSlotIconIds(byte[] ids) {
        if (ids != null) {
            for (int i = 0; i < Math.min(ids.length, slotIconIds.length); i++)
                slotIconIds[i] = ids[i];
        }
    }

    public Rect getBoundingBox() {
        if (boundingBoxNeedsUpdate) computeBoundingBox();
        return boundingBox;
    }

    private Rect computeBoundingBox() {
        int snappingSize = inputControlsView.getSnappingSize();
        int halfWidth = 0, halfHeight = 0;
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
                        halfWidth = (int)(snappingSize * 2.5f);
                        halfHeight = (int)(snappingSize * 2.5f);
                        break;
                    case CIRCLE:
                        halfWidth = snappingSize * 3;
                        halfHeight = snappingSize * 3;
                        break;
                }
                break;
            case D_PAD:
                halfWidth = snappingSize * 7;
                halfHeight = snappingSize * 7;
                break;
            case TRACKPAD:
            case STICK:
            case RIGHT_STICK:
                halfWidth = snappingSize * 6;
                halfHeight = snappingSize * 6;
                break;
            case RANGE_BUTTON:
                halfWidth = snappingSize * ((getBindingCount() * 4) / 2);
                halfHeight = snappingSize * 2;
                if (orientation == 1) { int tmp = halfWidth; halfWidth = halfHeight; halfHeight = tmp; }
                break;
        }
        halfWidth *= scale;
        halfHeight *= scale;
        boundingBox.set(x - halfWidth, y - halfHeight, x + halfWidth, y + halfHeight);
        boundingBoxNeedsUpdate = false;
        invalidateSubButtonRects();
        return boundingBox;
    }

    private String getDisplayText() {
        if (text != null && !text.isEmpty()) return text;
        Binding binding = getBindingAt(0);
        String txt = binding.toString().replace("NUMPAD ", "NP").replace("BUTTON ", "");
        if (txt.length() > 7) {
            String[] parts = txt.split(" ");
            StringBuilder sb = new StringBuilder();
            for (String part : parts) sb.append(part.charAt(0));
            return (binding.isMouse() ? "M" : "") + sb;
        }
        return txt;
    }

    private static float getTextSizeForWidth(Paint paint, String text, float desiredWidth) {
        final byte testTextSize = 48;
        paint.setTextSize(testTextSize);
        return testTextSize * desiredWidth / paint.measureText(text);
    }

    private static String getRangeTextForIndex(Range range, int index) {
        switch (range) {
            case FROM_A_TO_Z: return String.valueOf((char)(65 + index));
            case FROM_0_TO_9: return String.valueOf((index + 1) % 10);
            case FROM_F1_TO_F12: return "F" + (index + 1);
            case FROM_NP0_TO_NP9: return "NP" + ((index + 1) % 10);
            default: return "";
        }
    }

    public void draw(Canvas canvas) {
        int snappingSize = inputControlsView.getSnappingSize();
        Paint paint = inputControlsView.getPaint();
        int primaryColor = inputControlsView.getPrimaryColor();
        int secondaryColor = inputControlsView.getPrimaryColor();
        int baseColor = selected ? secondaryColor : primaryColor;
        float strokeWidth = snappingSize * 0.25f;
        Rect bb = getBoundingBox();

        switch (type) {
            case BUTTON:
            case TOUCHSCREEN_TOGGLE:
                drawButtonCommon(canvas, bb, paint, primaryColor, baseColor, strokeWidth, snappingSize);
                break;
            case D_PAD:
                drawDPad(canvas, bb, paint, primaryColor, baseColor, strokeWidth, snappingSize);
                break;
            case RANGE_BUTTON:
                drawRangeButton(canvas, bb, paint, primaryColor, baseColor, strokeWidth, snappingSize);
                break;
            case STICK:
                drawStick(canvas, bb, paint, primaryColor, baseColor, strokeWidth, snappingSize, false);
                break;
            case RIGHT_STICK:
                drawStick(canvas, bb, paint, primaryColor, baseColor, strokeWidth, snappingSize, true);
                break;
            case TRACKPAD:
                drawTrackpad(canvas, bb, paint, primaryColor, strokeWidth);
                break;
            case MENU_NAVIGATION:
                drawMenuNavigation(canvas, bb, paint, primaryColor, strokeWidth, snappingSize);
                break;
            case MULTIPLE_BUTTON:
                drawMultipleButton(canvas, bb, paint, primaryColor, baseColor, strokeWidth, snappingSize);
                break;
        }
    }

    private void drawButtonCommon(Canvas canvas, Rect bb, Paint paint, int primaryColor,
                                  int baseColor, float strokeWidth, int snappingSize) {
        float cx = bb.centerX();
        float cy = bb.centerY();
        paint.setColor(baseColor);
        paint.setStrokeWidth(strokeWidth);
        boolean shouldFill = (type == Type.TOUCHSCREEN_TOGGLE) ? selected : isPressed;

        if (iconId == 0) {
            paint.setStyle(shouldFill ? Paint.Style.FILL_AND_STROKE : Paint.Style.STROKE);
            drawShape(canvas, paint, bb);
        }
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(primaryColor);

        if (iconId > 0) {
            float pressScale = isPressed ? 1.0f : 0.8f;
            drawIconExact(canvas, cx, cy, bb.width() * pressScale, bb.height() * pressScale, iconId);
        } else if (!text.isEmpty() || getBindingAt(0) != Binding.NONE) {
            String displayText = getDisplayText();
            if (!displayText.isEmpty()) {
                paint.setTextSize(Math.min(getTextSizeForWidth(paint, displayText, bb.width() - strokeWidth * 2), snappingSize * 2 * scale));
                paint.setTextAlign(Paint.Align.CENTER);
                canvas.drawText(displayText, cx, cy - (paint.descent() + paint.ascent()) * 0.5f, paint);
            }
        }
    }

    private void drawShape(Canvas canvas, Paint paint, Rect bb) {
        switch (shape) {
            case CIRCLE:
                canvas.drawCircle(bb.centerX(), bb.centerY(), bb.width() * 0.5f, paint);
                break;
            case RECT:
                canvas.drawRect(bb, paint);
                break;
            case ROUND_RECT: {
                float r = bb.height() * 0.5f;
                canvas.drawRoundRect(bb.left, bb.top, bb.right, bb.bottom, r, r, paint);
                break;
            }
            case SQUARE: {
                float r = inputControlsView.getSnappingSize() * 0.75f * scale;
                canvas.drawRoundRect(bb.left, bb.top, bb.right, bb.bottom, r, r, paint);
                break;
            }
        }
    }

    private void drawShapeRect(Canvas canvas, Paint paint, RectF rect) {
        switch (shape) {
            case CIRCLE:
                canvas.drawCircle(rect.centerX(), rect.centerY(), rect.width() * 0.5f, paint);
                break;
            default: {
                float r = rect.height() * 0.5f;
                if (shape == Shape.SQUARE) r = inputControlsView.getSnappingSize() * 0.75f * scale;
                canvas.drawRoundRect(rect, r, r, paint);
            }
        }
    }

    private void ensureSubButtonRect(SubButton sb, int laneIdx, float w, float h, float gap) {
        if (sb.rectValid) return;
        Rect bb = getBoundingBox();
        int dir = sb.direction & 0x07;
        float step = laneIdx + 1;
        float[] dx = { 0,  1,  1,  1,  0, -1, -1, -1};
        float[] dy = {-1, -1,  0,  1,  1,  1,  0, -1};
        float offsetX = dx[dir] * step * (w + gap);
        float offsetY = dy[dir] * step * (h + gap);
        sb.drawRect.set(bb.left + offsetX, bb.top + offsetY, bb.right + offsetX, bb.bottom + offsetY);
        sb.rectValid = true;
    }

    private void drawDPad(Canvas canvas, Rect bb, Paint paint, int primaryColor, int baseColor,
                          float strokeWidth, int snappingSize) {
        float cx = bb.centerX();
        float cy = bb.centerY();
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

        float globalIconW = bb.width() * 0.8f;
        float globalIconH = bb.height() * 0.8f;

        if (iconId > 0) {
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(primaryColor);
            drawIconExact(canvas, cx, cy, globalIconW, globalIconH, iconId);

            if (upPressed && slotIconIds[0] > 0) drawIconExact(canvas, cx, cy, globalIconW, globalIconH, slotIconIds[0]);
            else if (rightPressed && slotIconIds[1] > 0) drawIconExact(canvas, cx, cy, globalIconW, globalIconH, slotIconIds[1]);
            else if (downPressed && slotIconIds[2] > 0) drawIconExact(canvas, cx, cy, globalIconW, globalIconH, slotIconIds[2]);
            else if (leftPressed && slotIconIds[3] > 0) drawIconExact(canvas, cx, cy, globalIconW, globalIconH, slotIconIds[3]);
            return;
        }

        // UP
        path.reset();
        path.moveTo(cx, cy - start);
        path.lineTo(cx - offsetX, cy - offsetY);
        path.lineTo(cx - offsetX, bb.top);
        path.lineTo(cx + offsetX, bb.top);
        path.lineTo(cx + offsetX, cy - offsetY);
        path.close();
        if (slotIconIds[0] > 0) {
            if (upPressed) { paint.setStyle(Paint.Style.FILL); canvas.drawPath(path, paint); }
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(upPressed ? ColorUtils.setAlphaComponent(baseColor, 180) : primaryColor);
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
        path.lineTo(bb.right, cy - offsetX);
        path.lineTo(bb.right, cy + offsetX);
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
        path.lineTo(cx - offsetX, bb.bottom);
        path.lineTo(cx + offsetX, bb.bottom);
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
        path.lineTo(bb.left, cy - offsetX);
        path.lineTo(bb.left, cy + offsetX);
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
    }

    private void drawRangeButton(Canvas canvas, Rect bb, Paint paint, int primaryColor,
                                 int baseColor, float strokeWidth, int snappingSize) {
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
            float lineTop = bb.top + strokeWidth * 0.5f;
            float lineBottom = bb.bottom - strokeWidth * 0.5f;
            float startX = bb.left;
            canvas.drawRoundRect(startX, bb.top, bb.right, bb.bottom, radius, radius, paint);

            canvas.save();
            path.addRoundRect(startX, bb.top, bb.right, bb.bottom, radius, radius, Path.Direction.CW);
            canvas.clipPath(path);
            startX -= scrollOffset % elementSize;

            for (byte i = rangeIndex[0]; i < rangeIndex[1]; i++) {
                int index = i % range.max;
                paint.setStyle(Paint.Style.STROKE);
                paint.setColor(oldColor);
                if (startX > bb.left && startX < bb.right) canvas.drawLine(startX, lineTop, startX, lineBottom, paint);
                String text = getRangeTextForIndex(range, index);
                if (startX < bb.right && startX + elementSize > bb.left) {
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
            float lineLeft = bb.left + strokeWidth * 0.5f;
            float lineRight = bb.right - strokeWidth * 0.5f;
            float startY = bb.top;
            canvas.drawRoundRect(bb.left, startY, bb.right, bb.bottom, radius, radius, paint);

            canvas.save();
            path.addRoundRect(bb.left, startY, bb.right, bb.bottom, radius, radius, Path.Direction.CW);
            canvas.clipPath(path);
            startY -= scrollOffset % elementSize;

            for (byte i = rangeIndex[0]; i < rangeIndex[1]; i++) {
                paint.setStyle(Paint.Style.STROKE);
                paint.setColor(oldColor);
                if (startY > bb.top && startY < bb.bottom) canvas.drawLine(lineLeft, startY, lineRight, startY, paint);
                String text = getRangeTextForIndex(range, i);
                if (startY < bb.bottom && startY + elementSize > bb.top) {
                    paint.setStyle(Paint.Style.FILL);
                    paint.setColor(primaryColor);
                    paint.setTextSize(Math.min(getTextSizeForWidth(paint, text, bb.width() - strokeWidth * 2), minTextSize));
                    paint.setTextAlign(Paint.Align.CENTER);
                    canvas.drawText(text, x, startY + elementSize * 0.5f - ((paint.descent() + paint.ascent()) * 0.5f), paint);
                }
                startY += elementSize;
            }
            paint.setStyle(Paint.Style.STROKE);
            paint.setColor(oldColor);
            canvas.restore();
        }
    }

    private void drawStick(Canvas canvas, Rect bb, Paint paint, int primaryColor, int baseColor,
                           float strokeWidth, int snappingSize, boolean isRight) {
        int cx = bb.centerX();
        int cy = bb.centerY();
        int oldColor = paint.getColor();

        paint.setColor(baseColor);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(strokeWidth);

        if (slotIconIds[0] == 0) canvas.drawCircle(cx, cy, bb.height() * 0.5f, paint);
        if (slotIconIds[0] > 0) {
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(primaryColor);
            float outerDiameter = bb.height() - strokeWidth;
            drawIconExact(canvas, cx, cy, outerDiameter, outerDiameter, slotIconIds[0]);
        }

        float thumbX, thumbY;
        if (isPressed && visualThumbPosition != null) {
            thumbX = visualThumbPosition.x;
            thumbY = visualThumbPosition.y;
        } else {
            thumbX = getCurrentPosition().x;
            thumbY = getCurrentPosition().y;
        }
        short thumbRadius = (short)(snappingSize * 3.5f * scale);
        float innerDiameter = thumbRadius * 2;

        if (slotIconIds[1] == 0) {
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(isPressed ? baseColor : ColorUtils.setAlphaComponent(primaryColor, 50));
            canvas.drawCircle(thumbX, thumbY, thumbRadius, paint);
            paint.setStyle(Paint.Style.STROKE);
            paint.setColor(oldColor);
            paint.setStrokeWidth(strokeWidth * 0.5f);
            canvas.drawCircle(thumbX, thumbY, thumbRadius + strokeWidth * 0.5f, paint);
        }

        if (isRight && slotIconIds[0] == 0 && slotIconIds[1] == 0 && iconId == 0) {
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(primaryColor);
            float labelSize = snappingSize * 1.8f * scale;
            paint.setTextSize(labelSize);
            paint.setTextAlign(Paint.Align.CENTER);
            float labelOffset = bb.height() * 0.5f - labelSize * 0.6f;
            canvas.drawText("R", cx + labelOffset, cy - labelOffset + labelSize * 0.4f, paint);
        }

        if (slotIconIds[1] > 0) {
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(isPressed ? baseColor : primaryColor);
            drawIconExact(canvas, thumbX, thumbY, innerDiameter, innerDiameter, slotIconIds[1]);
        } else if (iconId > 0) {
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(primaryColor);
            drawIconExact(canvas, thumbX, thumbY, innerDiameter, innerDiameter, iconId);
        }
    }

    private void drawTrackpad(Canvas canvas, Rect bb, Paint paint, int primaryColor, float strokeWidth) {
        float cx = bb.centerX();
        float cy = bb.centerY();
        float radius = bb.height() * 0.15f;
        float offset = strokeWidth * 2.5f;
        float innerStrokeWidth = strokeWidth * 2;

        if (iconId == 0) {
            canvas.drawRoundRect(bb.left, bb.top, bb.right, bb.bottom, radius, radius, paint);
            float innerHeight = bb.height() - offset * 2;
            float innerRadius = (innerHeight / bb.height()) * radius - (innerStrokeWidth * 0.5f + strokeWidth * 0.5f);
            paint.setStrokeWidth(innerStrokeWidth);
            canvas.drawRoundRect(bb.left + offset, bb.top + offset, bb.right - offset, bb.bottom - offset, innerRadius, innerRadius, paint);
        } else {
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(primaryColor);
            float innerW = bb.width() - offset * 2;
            float innerH = bb.height() - offset * 2;
            drawIconExact(canvas, cx, cy, innerW, innerH, iconId);
        }
    }

    private void drawMenuNavigation(Canvas canvas, Rect bb, Paint paint, int primaryColor,
                                    float strokeWidth, int snappingSize) {
        float cx = bb.centerX();
        float cy = bb.centerY();
        float w = bb.width(), h = bb.height();
        float r = h * 0.5f;
        float itemR = r * 0.6f;

        paint.setStrokeWidth(strokeWidth * 0.75f);

        if (slotIconIds[0] > 0 || iconId > 0) {
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(primaryColor);
            float iconSize = Math.min(w, h) * (isPressed ? 1.0f : 0.78f);
            drawIconExact(canvas, cx, cy, iconSize, iconSize, slotIconIds[0] > 0 ? slotIconIds[0] : iconId);
        } else {
            paint.setColor(primaryColor);
            paint.setStyle(isPressed ? Paint.Style.FILL_AND_STROKE : Paint.Style.STROKE);
            canvas.drawRoundRect(bb.left, bb.top, bb.right, bb.bottom, r, r, paint);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(primaryColor);
            paint.setTextAlign(Paint.Align.CENTER);
            String label = (text != null && !text.isEmpty()) ? text : "\u2261";
            float ts = Math.min(getTextSizeForWidth(paint, label, w - strokeWidth * 4), snappingSize * 1.6f * scale);
            paint.setTextSize(ts);
            canvas.drawText(label, cx, cy - (paint.descent() + paint.ascent()) * 0.5f, paint);
        }

        if (menuAnimProgress <= 0f) return;

        final String[] itemFallback = {"\u2328", "\u25CF", "\u2630", "\u25A3", "\u2715"};
        final String[] itemLabels   = {"Keyboard", "Cursor pos", "Task Manager", "Active Windows", "Exit"};
        float gap = snappingSize * 0.4f * scale;
        float itemH = h;
        int menuAlpha = (int)(menuAnimProgress * 255);
        final float[] ITEM_START = {0.00f, 0.16f, 0.32f, 0.48f, 0.64f};
        final float[] ITEM_END   = {0.46f, 0.62f, 0.78f, 0.92f, 1.00f};

        for (int i = 0; i < itemLabels.length; i++) {
            float top = bb.bottom + gap + i * (itemH + gap);
            float bottom = top + itemH;
            float itemCy = (top + bottom) * 0.5f;
            float window = ITEM_END[i] - ITEM_START[i];
            float itemProgress = Math.min(1f, Math.max(0f, (menuAnimProgress - ITEM_START[i]) / window));
            float scaleVal = 1f - (1f - itemProgress) * (1f - itemProgress);
            if (scaleVal <= 0f) continue;

            canvas.save();
            canvas.scale(scaleVal, scaleVal, cx, itemCy);
            int itemAlpha = (int)(scaleVal * menuAlpha);

            paint.setStyle(Paint.Style.FILL);
            paint.setColor(ColorUtils.setAlphaComponent(primaryColor, (int)(40 * scaleVal)));
            canvas.drawRoundRect(bb.left, top, bb.right, bottom, itemR, itemR, paint);

            paint.setStyle(Paint.Style.STROKE);
            paint.setColor(ColorUtils.setAlphaComponent(primaryColor, itemAlpha));
            paint.setStrokeWidth(strokeWidth * 0.75f);
            canvas.drawRoundRect(bb.left, top, bb.right, bottom, itemR, itemR, paint);

            paint.setStyle(Paint.Style.FILL);
            paint.setColor(ColorUtils.setAlphaComponent(primaryColor, itemAlpha));

            byte slotIcon = (i + 1 < slotIconIds.length) ? slotIconIds[i + 1] : 0;
            if (slotIcon > 0) {
                float iconAreaW = itemH * 0.78f;
                float iconSize = iconAreaW * 0.80f;
                float iconCx = bb.left + iconAreaW * 0.5f + strokeWidth;
                drawIconExact(canvas, iconCx, itemCy, iconSize, iconSize, slotIcon);
                float textLeft = bb.left + iconAreaW + strokeWidth * 2;
                float textAvail = bb.right - textLeft - strokeWidth;
                if (textAvail > 0) {
                    paint.setTextAlign(Paint.Align.LEFT);
                    float ts = Math.min(getTextSizeForWidth(paint, itemLabels[i], textAvail), snappingSize * 1.55f * scale);
                    paint.setTextSize(ts);
                    canvas.drawText(itemLabels[i], textLeft, itemCy - (paint.descent() + paint.ascent()) * 0.5f, paint);
                }
            } else {
                String fullLabel = itemFallback[i] + " " + itemLabels[i];
                paint.setTextAlign(Paint.Align.CENTER);
                float ts = Math.min(getTextSizeForWidth(paint, fullLabel, w - strokeWidth * 4), snappingSize * 1.6f * scale);
                paint.setTextSize(ts);
                canvas.drawText(fullLabel, cx, itemCy - (paint.descent() + paint.ascent()) * 0.5f, paint);
            }
            canvas.restore();
        }
    }

    private void drawMultipleButton(Canvas canvas, Rect bb, Paint paint, int primaryColor, int baseColor,
                                    float strokeWidth, int snappingSize) {
        float cx = bb.centerX();
        float cy = bb.centerY();
        float w = bb.width(), h = bb.height();

        paint.setStrokeWidth(strokeWidth * 0.75f);

        if (iconId > 0) {
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(primaryColor);
            float iconSize = Math.min(w, h) * (isPressed ? 1.0f : 0.78f);
            drawIconExact(canvas, cx, cy, iconSize, iconSize, iconId);
        } else {
            paint.setColor(baseColor);
            paint.setStyle(isPressed ? Paint.Style.FILL_AND_STROKE : Paint.Style.STROKE);
            drawShape(canvas, paint, bb);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(primaryColor);
            paint.setTextAlign(Paint.Align.CENTER);
            String label = (text != null && !text.isEmpty()) ? text : "\u25A4";
            float ts = Math.min(getTextSizeForWidth(paint, label, w - strokeWidth * 4), snappingSize * 1.6f * scale);
            paint.setTextSize(ts);
            canvas.drawText(label, cx, cy - (paint.descent() + paint.ascent()) * 0.5f, paint);
        }

        if (multiBtnAnimProgress <= 0f) return;

        float gap = snappingSize * 0.4f * scale;
        int menuAlpha = (int)(multiBtnAnimProgress * 255);

        int[] laneCounts = new int[8];
        for (SubButton sb : subButtons) if (sb.direction != (byte)0xFF) laneCounts[sb.direction & 0x07]++;

        int[] laneIdx = new int[8];
        int visibleIdx = 0;
        int totalVisible = 0;
        for (SubButton sb : subButtons) if (sb.direction != (byte)0xFF) totalVisible++;

        for (int i = 0; i < subButtons.size(); i++) {
            SubButton sb = subButtons.get(i);
            if (sb.direction == (byte)0xFF) continue;

            int dir = sb.direction & 0x07;
            int idxInLane = laneIdx[dir]++;
            ensureSubButtonRect(sb, idxInLane, w, h, gap);
            RectF rect = sb.drawRect;
            float itemCx = rect.centerX();
            float itemCy = rect.centerY();

            float windowSize = 0.45f;
            float windowStart = (totalVisible > 1) ? visibleIdx * (1.0f - windowSize) / (totalVisible - 1) : 0f;
            float windowEnd = Math.min(1.0f, windowStart + windowSize);
            float itemProgress = Math.min(1f, Math.max(0f, (multiBtnAnimProgress - windowStart) / (windowEnd - windowStart)));
            float scaleVal = 1f - (1f - itemProgress) * (1f - itemProgress);
            if (scaleVal <= 0f) { visibleIdx++; continue; }

            canvas.save();
            canvas.scale(scaleVal, scaleVal, itemCx, itemCy);

            int itemAlpha = (int)(scaleVal * menuAlpha);
            boolean subPressed = (multiBtnPressedIndex == i);

            if (sb.iconId == 0) {
                paint.setStyle(Paint.Style.FILL);
                paint.setColor(ColorUtils.setAlphaComponent(primaryColor, subPressed ? 80 : 0));
                drawShapeRect(canvas, paint, rect);

                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(strokeWidth * 0.75f);
                paint.setColor(ColorUtils.setAlphaComponent(primaryColor, itemAlpha));
                drawShapeRect(canvas, paint, rect);
            }

            paint.setStyle(Paint.Style.FILL);
            paint.setColor(ColorUtils.setAlphaComponent(primaryColor, itemAlpha));

            if (sb.iconId > 0) {
                float iconSize = Math.min(w, h) * 0.78f;
                drawIconExact(canvas, itemCx, itemCy, iconSize, iconSize, sb.iconId);
            } else if (!sb.text.isEmpty()) {
                paint.setTextAlign(Paint.Align.CENTER);
                float subTs = Math.min(getTextSizeForWidth(paint, sb.text, w - strokeWidth * 4), snappingSize * 1.6f * scale);
                paint.setTextSize(subTs);
                canvas.drawText(sb.text, itemCx, itemCy - (paint.descent() + paint.ascent()) * 0.5f, paint);
            } else {
                String bindLabel = sb.bindings.isEmpty() ? "?" : sb.bindings.get(0).toString()
                        .replace("NUMPAD ", "NP").replace("BUTTON_", "").replace("KEY_", "");
                if (bindLabel.length() > 6) bindLabel = bindLabel.substring(0, 5) + "…";
                paint.setTextAlign(Paint.Align.CENTER);
                float subTs = Math.min(getTextSizeForWidth(paint, bindLabel, w - strokeWidth * 4), snappingSize * 1.5f * scale);
                paint.setTextSize(subTs);
                canvas.drawText(bindLabel, itemCx, itemCy - (paint.descent() + paint.ascent()) * 0.5f, paint);
            }

            canvas.restore();
            visibleIdx++;
        }
    }

    private void drawIconExact(Canvas canvas, float cx, float cy, float width, float height, int iconId) {
        Bitmap icon = inputControlsView.getIcon((byte) iconId);
        if (icon == null || icon.isRecycled()) return;
        Paint paint = inputControlsView.getPaint();
        paint.setColorFilter(inputControlsView.getColorFilter());
        float iw = width, ih = height;
        float bw = icon.getWidth(), bh = icon.getHeight();
        if (bw > 0 && bh > 0) {
            float ratio = bw / bh;
            if (ratio > 1f) ih = iw / ratio;
            else iw = ih * ratio;
        }
        int hw = (int)(iw * 0.5f), hh = (int)(ih * 0.5f);
        iconSrcRect.set(0, 0, (int)bw, (int)bh);
        iconDstRect.set((int)cx - hw, (int)cy - hh, (int)cx + hw, (int)cy + hh);
        canvas.drawBitmap(icon, iconSrcRect, iconDstRect, paint);
        paint.setColorFilter(null);
    }

    public JSONObject toJSONObject() {
        try {
            JSONObject obj = new JSONObject();
            obj.put("type", type.name());
            obj.put("shape", shape.name());
            JSONArray bindArr = new JSONArray();
            for (Binding b : bindings) bindArr.put(b.name());
            obj.put("bindings", bindArr);
            obj.put("scale", scale);
            obj.put("x", (float)x / inputControlsView.getMaxWidth());
            obj.put("y", (float)y / inputControlsView.getMaxHeight());
            obj.put("toggleSwitch", toggleSwitch);
            obj.put("text", text);
            obj.put("iconId", iconId);
            JSONArray slotArr = new JSONArray();
            for (byte sid : slotIconIds) slotArr.put(sid);
            obj.put("slotIconIds", slotArr);
            if (type == Type.RANGE_BUTTON && range != null) {
                obj.put("range", range.name());
                if (orientation != 0) obj.put("orientation", orientation);
            }
            if (type == Type.TOUCHSCREEN_TOGGLE) obj.put("selected", selected);
            if (type == Type.RIGHT_STICK) {
                obj.put("isCursorMove", isCursorMove);
                if (isCursorMove) obj.put("cursorMoveRadius", cursorMoveRadius);
            }
            if (type == Type.MULTIPLE_BUTTON) {
                JSONArray subArr = new JSONArray();
                for (SubButton sb : subButtons) {
                    JSONObject sbObj = new JSONObject();
                    JSONArray sbBind = new JSONArray();
                    for (Binding b : sb.bindings) sbBind.put(b.name());
                    sbObj.put("bindings", sbBind);
                    sbObj.put("direction", sb.direction == (byte)0xFF ? -1 : (sb.direction & 0xFF));
                    sbObj.put("text", sb.text);
                    sbObj.put("iconId", sb.iconId);
                    subArr.put(sbObj);
                }
                obj.put("subButtons", subArr);
            }
            return obj;
        } catch (JSONException e) {
            return null;
        }
    }

    public void fromJSONObject(JSONObject obj) throws JSONException {
        if (obj.has("slotIconIds")) {
            JSONArray arr = obj.getJSONArray("slotIconIds");
            for (int i = 0; i < Math.min(arr.length(), slotIconIds.length); i++)
                slotIconIds[i] = (byte) arr.getInt(i);
        }
        if (type == Type.RIGHT_STICK) {
            if (obj.has("isCursorMove")) isCursorMove = obj.getBoolean("isCursorMove");
            if (obj.has("cursorMoveRadius")) cursorMoveRadius = obj.getInt("cursorMoveRadius");
        }
        if (type == Type.MULTIPLE_BUTTON && obj.has("subButtons")) {
            subButtons.clear();
            JSONArray subArr = obj.getJSONArray("subButtons");
            for (int i = 0; i < subArr.length(); i++) {
                JSONObject sbObj = subArr.getJSONObject(i);
                SubButton sb = new SubButton();
                sb.bindings.clear();
                JSONArray bindArr = sbObj.getJSONArray("bindings");
                for (int j = 0; j < bindArr.length(); j++)
                    sb.bindings.add(Binding.valueOf(bindArr.getString(j)));
                int dir = sbObj.getInt("direction");
                sb.direction = (dir == -1) ? (byte)0xFF : (byte) dir;
                sb.text = sbObj.optString("text", "");
                sb.iconId = (byte) sbObj.optInt("iconId", 0);
                subButtons.add(sb);
            }
        }
    }

    public boolean containsPoint(float x, float y) {
        if (getBoundingBox().contains((int)(x + 0.5f), (int)(y + 0.5f))) return true;
        if (type == Type.MENU_NAVIGATION && menuExpanded && menuAnimProgress > 0.5f) {
            Rect bb = getBoundingBox();
            float gap = inputControlsView.getSnappingSize() * 0.4f * scale;
            float itemH = bb.height();
            float totalH = (itemH + gap) * 5;
            float bottom = bb.bottom + gap + totalH;
            if (x >= bb.left && x <= bb.right && y >= bb.bottom && y <= bottom) return true;
        }
        if (type == Type.MULTIPLE_BUTTON && multiBtnExpanded && multiBtnAnimProgress > 0.3f) {
            Rect bb = getBoundingBox();
            float w = bb.width(), h = bb.height();
            float gap = inputControlsView.getSnappingSize() * 0.4f * scale;
            int[] laneIdx = new int[8];
            for (SubButton sb : subButtons) {
                if (sb.direction == (byte)0xFF) continue;
                int dir = sb.direction & 0x07;
                ensureSubButtonRect(sb, laneIdx[dir]++, w, h, gap);
                if (sb.drawRect.contains(x, y)) return true;
            }
        }
        return false;
    }

    private boolean isKeepButtonPressedAfterMinTime() {
        Binding binding = getBindingAt(0);
        return !toggleSwitch && (binding == Binding.GAMEPAD_BUTTON_L3 || binding == Binding.GAMEPAD_BUTTON_R3);
    }

    public boolean handleTouchDown(int pointerId, float x, float y) {
        if (type == Type.MENU_NAVIGATION && menuExpanded && menuAnimProgress > 0.5f) {
            Rect bb = getBoundingBox();
            float gap = inputControlsView.getSnappingSize() * 0.4f * scale;
            float itemH = bb.height();
            for (int i = 0; i < 5; i++) {
                float top = bb.bottom + gap + i * (itemH + gap);
                float bottom = top + itemH;
                if (x >= bb.left && x <= bb.right && y >= top && y <= bottom) {
                    executeMenuAction(i);
                    return true;
                }
            }
        }
        if (type == Type.MULTIPLE_BUTTON && multiBtnExpanded && multiBtnAnimProgress > 0.3f) {
            for (int i = 0; i < subButtons.size(); i++) {
                if (isMultiBtnSubHit(i, x, y)) {
                    multiBtnPressedIndex = i;
                    inputControlsView.invalidate();
                    SubButton sb = subButtons.get(i);
                    for (Binding b : sb.bindings)
                        if (b != Binding.NONE) inputControlsView.handleInputEvent(b, true);
                    return true;
                }
            }
        }
        if (currentPointerId == -1 && containsPoint(x, y)) {
            currentPointerId = pointerId;
            isPressed = true;
            inputControlsView.invalidate();
            if (type == Type.MENU_NAVIGATION) {
                return true;
            } else if (type == Type.MULTIPLE_BUTTON) {
                return true;
            } else if (type == Type.BUTTON || type == Type.TOUCHSCREEN_TOGGLE) {
                if (isKeepButtonPressedAfterMinTime()) touchTime = System.currentTimeMillis();
                for (int i = 0; i < bindings.size(); i++) {
                    Binding b = bindings.get(i);
                    if (b != Binding.NONE && (!toggleSwitch || !selected))
                        inputControlsView.handleInputEvent(b, true);
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
        }
        return false;
    }

    public boolean handleTouchMove(int pointerId, float x, float y) {
        if (pointerId == currentPointerId && (type == Type.D_PAD || type == Type.STICK || type == Type.TRACKPAD || type == Type.RIGHT_STICK)) {
            float deltaX, deltaY;
            Rect bb = getBoundingBox();
            float radius = bb.width() * 0.5f;
            if (radius <= 0) return false;
            TouchpadView touchpadView = inputControlsView.getTouchpadView();

            if (type == Type.TRACKPAD) {
                if (currentPosition == null) currentPosition = new PointF();
                float[] deltaPoint = touchpadView.computeDeltaPoint(currentPosition.x, currentPosition.y, x, y);
                deltaX = deltaPoint[0];
                deltaY = deltaPoint[1];
                currentPosition.set(x, y);
            } else if (type == Type.RIGHT_STICK) {
                float localX = x - bb.left;
                float localY = y - bb.top;
                float offsetX = localX - radius;
                float offsetY = localY - radius;
                float distance = Mathf.lengthSq(offsetX, offsetY);
                if (distance > radius * radius) {
                    float len = (float) Math.sqrt(distance);
                    offsetX = offsetX / len * radius;
                    offsetY = offsetY / len * radius;
                }
                deltaX = Mathf.clamp(offsetX / radius, -1, 1);
                deltaY = Mathf.clamp(offsetY / radius, -1, 1);
                if (visualThumbPosition == null) visualThumbPosition = new PointF();
                visualThumbPosition.x = bb.left + offsetX + radius;
                visualThumbPosition.y = bb.top + offsetY + radius;
            } else {
                float localX = x - bb.left;
                float localY = y - bb.top;
                float offsetX = localX - radius;
                float offsetY = localY - radius;
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
                currentPosition.x = bb.left + deltaX * radius + radius;
                currentPosition.y = bb.top + deltaY * radius + radius;
                final boolean[] newStates = {deltaY <= -STICK_DEAD_ZONE, deltaX >= STICK_DEAD_ZONE, deltaY >= STICK_DEAD_ZONE, deltaX <= -STICK_DEAD_ZONE};
                for (byte i = 0; i < 4; i++) {
                    float value = (i == 1 || i == 3) ? deltaX : deltaY;
                    Binding binding = getBindingAt(i);
                    if (binding.isGamepad()) {
                        float gamepadValue = Mathf.clamp(Math.max(0, Math.abs(value) - 0.01f) * Mathf.sign(value) * STICK_SENSITIVITY, -1, 1);
                        boolean isActiveNow = Math.abs(gamepadValue) > 0.01f;
                        if (isActiveNow != states[i]) {
                            inputControlsView.handleInputEvent(binding, isActiveNow, isActiveNow ? gamepadValue : 0f);
                            states[i] = isActiveNow;
                        } else if (isActiveNow) {
                            inputControlsView.handleInputEvent(binding, true, gamepadValue);
                        }
                    } else {
                        boolean state = binding.isMouseMove() ? (newStates[i] || newStates[(i + 2) % 4]) : newStates[i];
                        if (state != states[i]) {
                            inputControlsView.handleInputEvent(binding, state, value);
                            states[i] = state;
                        }
                    }
                }
                inputControlsView.invalidate();
            } else if (type == Type.TRACKPAD) {
                final boolean[] newStates = {deltaY <= -TRACKPAD_MIN_SPEED, deltaX >= TRACKPAD_MIN_SPEED, deltaY >= TRACKPAD_MIN_SPEED, deltaX <= -TRACKPAD_MIN_SPEED};
                int cursorDx = 0, cursorDy = 0;
                for (byte i = 0; i < 4; i++) {
                    float value = (i == 1 || i == 3 ? deltaX : deltaY);
                    Binding binding = getBindingAt(i);
                    if (binding.isGamepad()) {
                        if (interpolator == null) interpolator = new CubicBezierInterpolator();
                        if (Math.abs(value) > TRACKPAD_ACCELERATION_THRESHOLD) value *= STICK_SENSITIVITY;
                        interpolator.set(0.075f, 0.95f, 0.45f, 0.95f);
                        float interpolatedValue = interpolator.getInterpolation(Math.min(1.0f, Math.abs(value / TRACKPAD_MAX_SPEED)));
                        inputControlsView.handleInputEvent(binding, true, Mathf.clamp(interpolatedValue * Mathf.sign(value), -1, 1));
                        states[i] = true;
                    } else {
                        if (Math.abs(value) > TouchpadView.CURSOR_ACCELERATION_THRESHOLD) value *= TouchpadView.CURSOR_ACCELERATION;
                        if (binding == Binding.MOUSE_MOVE_LEFT || binding == Binding.MOUSE_MOVE_RIGHT) cursorDx = Mathf.roundPoint(value);
                        else if (binding == Binding.MOUSE_MOVE_UP || binding == Binding.MOUSE_MOVE_DOWN) cursorDy = Mathf.roundPoint(value);
                        else {
                            inputControlsView.handleInputEvent(binding, newStates[i], value);
                            states[i] = newStates[i];
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
                    XServer xServer = inputControlsView.getXServer();
                    if (cursorMoveCenterX < 0) {
                        cursorMoveCenterX = xServer.screenInfo.width / 2f;
                        cursorMoveCenterY = xServer.screenInfo.height / 2f;
                    }
                    if (!cursorMoveStartRecorded) {
                        cursorMoveStartDeltaX = deltaX;
                        cursorMoveStartDeltaY = deltaY;
                        cursorMoveStartRecorded = true;
                    }
                    float totalOffsetX = cursorMoveLastOffsetX + (deltaX - cursorMoveStartDeltaX);
                    float totalOffsetY = cursorMoveLastOffsetY + (deltaY - cursorMoveStartDeltaY);
                    float offsetLen = (float) Math.sqrt(totalOffsetX * totalOffsetX + totalOffsetY * totalOffsetY);
                    if (offsetLen > 1f) {
                        totalOffsetX /= offsetLen;
                        totalOffsetY /= offsetLen;
                    }
                    cursorMoveLastOffsetX = totalOffsetX;
                    cursorMoveLastOffsetY = totalOffsetY;
                    float newX = Mathf.clamp(cursorMoveCenterX + totalOffsetX * cursorMoveRadius, 0, xServer.screenInfo.width);
                    float newY = Mathf.clamp(cursorMoveCenterY + totalOffsetY * cursorMoveRadius, 0, xServer.screenInfo.height);
                    if (xServer.isRelativeMouseMovement())
                        xServer.getWinHandler().mouseEvent(MouseEventFlags.MOVE, (int) newX, (int) newY, 0);
                    else
                        xServer.injectPointerMove((int) newX, (int) newY);
                    inputControlsView.invalidate();
                }
                final boolean[] newStates = {deltaY <= -STICK_DEAD_ZONE, deltaX >= STICK_DEAD_ZONE, deltaY >= STICK_DEAD_ZONE, deltaX <= -STICK_DEAD_ZONE};
                for (byte i = 0; i < 4; i++) {
                    float value = (i == 1 || i == 3) ? deltaX : deltaY;
                    Binding binding = getBindingAt(i);
                    if (binding.isGamepad()) {
                        float gamepadValue = Mathf.clamp(Math.max(0, Math.abs(value) - 0.01f) * Mathf.sign(value) * STICK_SENSITIVITY, -1, 1);
                        boolean isActiveNow = Math.abs(gamepadValue) > 0.01f;
                        if (isActiveNow != states[i]) {
                            inputControlsView.handleInputEvent(binding, isActiveNow, isActiveNow ? gamepadValue : 0f);
                            states[i] = isActiveNow;
                        } else if (isActiveNow) {
                            inputControlsView.handleInputEvent(binding, true, gamepadValue);
                        }
                    } else {
                        boolean state = binding.isMouseMove() ? (newStates[i] || newStates[(i + 2) % 4]) : newStates[i];
                        if (state != states[i]) {
                            inputControlsView.handleInputEvent(binding, state, value);
                            states[i] = state;
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
                    states[i] = state;
                }
            }
            return true;
        } else if (pointerId == currentPointerId && type == Type.RANGE_BUTTON) {
            scroller.handleTouchMove(x, y);
            return true;
        }
        return false;
    }

    public boolean handleTouchUp(int pointerId) {
        if (type == Type.MULTIPLE_BUTTON && multiBtnPressedIndex >= 0) {
            int idx = multiBtnPressedIndex;
            multiBtnPressedIndex = -1;
            inputControlsView.invalidate();
            SubButton sb = getSubButton(idx);
            if (sb != null) for (Binding b : sb.bindings) if (b != Binding.NONE) inputControlsView.handleInputEvent(b, false);
            return true;
        }
        if (pointerId == currentPointerId) {
            isPressed = false;
            inputControlsView.invalidate();
            if (type == Type.MENU_NAVIGATION) {
                toggleMenu();
                currentPointerId = -1;
                return true;
            }
            if (type == Type.MULTIPLE_BUTTON) {
                toggleMultiBtn();
                currentPointerId = -1;
                return true;
            }
            if (type == Type.TOUCHSCREEN_TOGGLE) {
                TouchpadView tp = inputControlsView.getTouchpadView();
                if (tp != null) {
                    boolean next = !tp.isSimTouchScreen();
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
                    if (!selected) inputControlsView.handleInputEvent(firstBinding, false);
                    touchTime = null;
                    inputControlsView.invalidate();
                } else {
                    for (int i = 0; i < bindings.size(); i++) {
                        Binding b = bindings.get(i);
                        if (b != Binding.NONE && (!toggleSwitch || selected))
                            inputControlsView.handleInputEvent(b, false);
                    }
                }
                if (toggleSwitch) {
                    selected = !selected;
                    inputControlsView.invalidate();
                }
            } else if (type == Type.RANGE_BUTTON || type == Type.D_PAD || type == Type.STICK || type == Type.TRACKPAD || type == Type.RIGHT_STICK) {
                for (int i = 0; i < states.length; i++)
                    if (states[i]) inputControlsView.handleInputEvent(getBindingAt(i), false);
                states = new boolean[states.length];
                if (type == Type.RANGE_BUTTON) scroller.handleTouchUp();
                else if (type == Type.STICK || type == Type.RIGHT_STICK) {
                    currentPosition = null;
                    visualThumbPosition = null;
                    if (type == Type.RIGHT_STICK && isCursorMove) cursorMoveStartRecorded = false;
                    inputControlsView.invalidate();
                }
            }
            currentPointerId = -1;
            return true;
        }
        return false;
    }

    public void handleTouchCancel(int pointerId) {
        if (pointerId == -1 || pointerId == currentPointerId) {
            isPressed = false;
            for (int i = 0; i < states.length; i++)
                if (states[i]) inputControlsView.handleInputEvent(getBindingAt(i), false);
            states = new boolean[states.length];
            if (type == Type.STICK || type == Type.RIGHT_STICK) {
                currentPosition = null;
                visualThumbPosition = null;
                if (type == Type.RIGHT_STICK && isCursorMove) cursorMoveStartRecorded = false;
            } else if (type == Type.RANGE_BUTTON) {
                scroller.handleTouchUp();
            } else if (type == Type.BUTTON) {
                for (Binding b : bindings) if (b != Binding.NONE) inputControlsView.handleInputEvent(b, false);
            } else if (type == Type.MENU_NAVIGATION) {
                if (menuExpanded) { menuExpanded = false; animateMenu(false); }
            } else if (type == Type.MULTIPLE_BUTTON) {
                if (multiBtnPressedIndex >= 0) {
                    SubButton sb = getSubButton(multiBtnPressedIndex);
                    if (sb != null) for (Binding b : sb.bindings) if (b != Binding.NONE) inputControlsView.handleInputEvent(b, false);
                    multiBtnPressedIndex = -1;
                }
                if (multiBtnExpanded) { multiBtnExpanded = false; animateMultiBtn(false); }
            }
            currentPointerId = -1;
            inputControlsView.invalidate();
        }
    }

    public PointF getCurrentPosition() {
        if (currentPosition == null || currentPointerId == -1) {
            Rect bb = getBoundingBox();
            if (currentPosition == null) currentPosition = new PointF();
            currentPosition.set(bb.centerX(), bb.centerY());
        }
        return currentPosition;
    }

    public void setCurrentPosition(float x, float y) {
        if (currentPosition == null) currentPosition = new PointF();
        currentPosition.set(x, y);
        inputControlsView.invalidate();
    }
}
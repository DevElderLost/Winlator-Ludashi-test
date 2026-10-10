package com.winlator.cmod.droiddeck;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.Build;
import android.os.SystemClock;
import android.util.Log;
import android.util.DisplayMetrics;
import android.view.DisplayCutout;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowInsets;

import com.winlator.cmod.inputcontrols.GamepadState;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Kontrol layar DroidDeck (port dari OnScreenControls.kt). Keluarannya adalah
 * GamepadState Winlator, jadi memakai jalur FakeInputWriter yang sudah ada.
 */
@SuppressLint("ViewConstructor")
public class DDOnScreenControls extends View {

    public interface Sink {
        void onPad(GamepadState state);
        void onGuide(boolean down);
    }

    private static final long DOUBLE_TAP_MS = 300L;
    private static final float MIN_FACE_MM = 9.5f;
    private static final float FULL_SIZE_HEIGHT_MM = 85f;
    private static final float BOTTOM_MARGIN_MM = 5f;
    private static final double DROP_DEGREES = 35.0;
    private static final float PHONE_MAX_HEIGHT_MM = 90f;
    private static final float SHOULDER_WIDTH = 1.45f;
    private static final float SHOULDER_HEIGHT = 0.8f;
    private static final String[] DIRECTIONS = {"up", "right", "down", "left"};

    private static boolean isShoulder(String id) {
        return id.equals("lb") || id.equals("rb") || id.equals("lt") || id.equals("rt");
    }

    private static final class Control {
        final String id, group;
        final int stick;
        final float radiusDp, offsetXDp, offsetYDp;
        final boolean wide;
        String target;
        float radius, cx, cy, kx, ky, ax, ay;
        int pressedBy = -1;
        boolean dirty, clicked;
        long lastUp;

        Control(String id, String group, int stick, float radiusDp, float offsetXDp, float offsetYDp) {
            this.id = id;
            this.group = group;
            this.stick = stick;
            this.radiusDp = radiusDp;
            this.offsetXDp = offsetXDp;
            this.offsetYDp = offsetYDp;
            this.wide = isShoulder(id);
            this.target = id;
        }

        float halfW() { return wide ? radius * SHOULDER_WIDTH : radius; }
        float halfH() { return wide ? radius * SHOULDER_HEIGHT : radius; }

        boolean contains(float x, float y, float r, float padding) {
            float dx = x - cx, dy = y - cy;
            if (wide) return Math.abs(dx) <= halfW() + r * 0.25f + padding && Math.abs(dy) <= halfH() + r * 0.25f + padding;
            float hit = r * 1.25f + padding;
            return dx * dx + dy * dy <= hit * hit;
        }

        void drag(float x, float y) {
            float dx = x - ax, dy = y - ay;
            float d = (float) Math.sqrt(dx * dx + dy * dy);
            if (d > radius) { dx = dx / d * radius; dy = dy / d * radius; }
            kx = dx;
            ky = dy;
            dirty = true;
        }
    }

    private final List<Control> controls = new ArrayList<>();
    private final List<String> groups = new ArrayList<>();
    private final Sink sink;
    private final boolean editing;
    private final GamepadState state = new GamepadState();

    private DDPrefs.Settings settings;
    private final Rect safe = new Rect();
    private boolean quickHidden = false;
    private int quickPressedBy = -1;
    private String selected = null;
    private float grabX, grabY;
    private boolean ignoreSaved = false;
    private float fit = 1f;
    private boolean guideDown = false;

    private int idleFill, heldFill, idleStroke, heldStroke, idleText, stickFill, knobFill;
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path arrow = new Path();
    private final RectF box = new RectF();

    public DDOnScreenControls(Context context, Sink sink, boolean editing) {
        super(context);
        this.sink = sink;
        this.editing = editing;
        fill.setStyle(Paint.Style.FILL);
        stroke.setStyle(Paint.Style.STROKE);
        text.setTextAlign(Paint.Align.CENTER);
        text.setTypeface(Typeface.DEFAULT_BOLD);

        controls.add(new Control("up", "dpad", -1, 26f, 0f, -52f));
        controls.add(new Control("right", "dpad", -1, 26f, 52f, 0f));
        controls.add(new Control("down", "dpad", -1, 26f, 0f, 52f));
        controls.add(new Control("left", "dpad", -1, 26f, -52f, 0f));
        controls.add(new Control("ls", "ls", 0, 48f, 0f, 0f));
        controls.add(new Control("rs", "rs", 1, 48f, 0f, 0f));
        controls.add(new Control("a", "face", -1, 30f, 0f, 50f));
        controls.add(new Control("b", "face", -1, 30f, 50f, 0f));
        controls.add(new Control("x", "face", -1, 30f, -50f, 0f));
        controls.add(new Control("y", "face", -1, 30f, 0f, -50f));
        controls.add(new Control("lb", "lb", -1, 24f, 0f, 0f));
        controls.add(new Control("rb", "rb", -1, 24f, 0f, 0f));
        controls.add(new Control("lt", "lt", -1, 24f, 0f, 0f));
        controls.add(new Control("rt", "rt", -1, 24f, 0f, 0f));
        controls.add(new Control("select", "select", -1, 20f, 0f, 0f));
        controls.add(new Control("start", "start", -1, 20f, 0f, 0f));
        controls.add(new Control("guide", "guide", -1, 22f, 0f, 0f));
        Set<String> seen = new LinkedHashSet<>();
        for (Control c : controls) seen.add(c.group);
        groups.addAll(seen);

        settings = DDPrefs.read(context);
        applySettings();
    }

    private float dp(float v) { return v * getResources().getDisplayMetrics().density; }
    private float scaled(float v) { return dp(v) * settings.size / 100f * fit; }

    private float pxPerMm(float dpi) {
        float nominal = getResources().getDisplayMetrics().densityDpi;
        return (dpi > nominal * 0.6f && dpi < nominal * 1.6f ? dpi : nominal) / 25.4f;
    }

    private float fitScale() {
        DisplayMetrics m = getResources().getDisplayMetrics();
        float heightMm = (getHeight() - safe.top - safe.bottom) / pxPerMm(m.ydpi);
        float faceMm = dp(60f) / pxPerMm(m.xdpi);
        float floor = Math.min(MIN_FACE_MM / faceMm, 1f);
        return Math.max(floor, Math.min(1f, heightMm / FULL_SIZE_HEIGHT_MM));
    }

    /** Lepas pilihan elemen (dipakai editor saat elemen Deck yang dipilih). */
    public void clearSelection() {
        if (selected != null) { selected = null; invalidate(); }
    }

    public void reload() {
        releaseAll();
        ignoreSaved = false;  // setelah Reset, layout yang disimpan berikutnya harus berlaku lagi
        settings = DDPrefs.read(getContext());
        applySettings();
        relayout();
    }

    private void applySettings() {
        for (Control c : controls) {
            String t = settings.mapping.get(c.id);
            c.target = t != null ? t : c.id;
        }
        final int tint = settings.tint;
        final float alpha = settings.opacity / 100f;
        idleFill = shade(tint, alpha, 80, 0.12f);
        heldFill = shade(tint, alpha, 160, 1f);
        idleStroke = light(tint, alpha, 150, 0.25f);
        heldStroke = light(tint, alpha, 230, 0.7f);
        idleText = light(tint, alpha, 210, 0.75f);
        stickFill = shade(tint, alpha, 60, 0.12f);
        knobFill = shade(tint, alpha, 120, 0.35f);
        stroke.setStrokeWidth(dp(1.5f));
    }

    private static int shade(int tint, float alpha, int a, float f) {
        return Color.argb((int) (a * alpha), (int) (Color.red(tint) * f), (int) (Color.green(tint) * f), (int) (Color.blue(tint) * f));
    }

    private static int light(int tint, float alpha, int a, float f) {
        return Color.argb((int) (a * alpha),
            (int) (Color.red(tint) + (255 - Color.red(tint)) * f),
            (int) (Color.green(tint) + (255 - Color.green(tint)) * f),
            (int) (Color.blue(tint) + (255 - Color.blue(tint)) * f));
    }

    /** Sembunyikan seluruh kontrol, sisakan tombol kecil untuk memunculkannya lagi. */
    public void setQuickHidden(boolean hidden) {
        if (editing || quickHidden == hidden) return;
        if (hidden) releaseAll();
        quickHidden = hidden;
        invalidate();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        relayout();
    }

    @Override
    public WindowInsets onApplyWindowInsets(WindowInsets insets) {
        Rect fresh = new Rect();
        if (Build.VERSION.SDK_INT >= 28) {
            DisplayCutout cutout = insets.getDisplayCutout();
            if (cutout != null) fresh.set(cutout.getSafeInsetLeft(), cutout.getSafeInsetTop(), cutout.getSafeInsetRight(), cutout.getSafeInsetBottom());
        }
        if (!fresh.equals(safe)) { safe.set(fresh); relayout(); }
        return super.onApplyWindowInsets(insets);
    }

    @Override
    protected void onDetachedFromWindow() {
        releaseAll();
        super.onDetachedFromWindow();
    }

    private void relayout() {
        if (getWidth() <= 0 || getHeight() <= 0) return;
        fit = fitScale();
        for (Control c : controls) c.radius = scaled(c.radiusDp);
        placeAuto(getWidth(), getHeight());
        if (!ignoreSaved) {
            for (Map.Entry<String, float[]> e : DDPrefs.layout(getContext(), getWidth(), getHeight()).entrySet()) {
                if (groups.contains(e.getKey())) put(e.getKey(), e.getValue()[0] * getWidth(), e.getValue()[1] * getHeight());
            }
        }
        for (String g : groups) clamp(g);
        invalidate();
    }

    private void placeAuto(float w, float h) {
        DisplayMetrics m = getResources().getDisplayMetrics();
        float mmX = pxPerMm(m.xdpi), mmY = pxPerMm(m.ydpi);
        float usableW = w - safe.left - safe.right;
        float usableH = h - safe.top - safe.bottom;
        float inboard = Math.max(24f, Math.min(40f, 0.17f * usableW / mmX)) * mmX;
        float lift = Math.max(22f, Math.min(45f, 0.285f * usableH / mmY)) * mmY;
        float gap = dp(12f);
        float floor = BOTTOM_MARGIN_MM * mmY;
        float drop = Math.max(drop("dpad", "ls", gap), drop("rs", "face", gap));
        float restY = Math.min(h - safe.bottom - lift, h - safe.bottom - floor - drop);
        put("ls", safe.left + inboard, restY);
        put("face", w - safe.right - inboard, restY);
        besideBelow("dpad", "ls", 1f, gap, floor);
        besideBelow("rs", "face", -1f, gap, floor);
        boolean phone = usableH / mmY < PHONE_MAX_HEIGHT_MM;
        float shoulderY = phone ? safe.top + floor + halfH("lb")
            : Math.min(centre("ls")[1] - extent("ls"), centre("face")[1] - extent("face")) - gap - halfH("lb");
        shoulders("ls", "lb", "lt", 1f, gap, shoulderY);
        shoulders("face", "rb", "rt", -1f, gap, shoulderY);
        float guideY = h - safe.bottom - floor - extent("guide");
        put("guide", w / 2f, guideY);
        float inner = Math.max(centre("dpad")[0] + extent("dpad"), w - centre("rs")[0] + extent("rs")) + gap + extent("select");
        put("select", inner, restY);
        put("start", w - inner, restY);
        if (phone) {
            float top = Math.max(centre("lb")[0] + extent("lb"), w - centre("rb")[0] + extent("rb")) + gap * 2.5f + extent("select");
            put("select", top, shoulderY);
            put("start", w - top, shoulderY);
        }
        if (crowded("select", gap, gap / 2f) || crowded("start", gap, gap / 2f)) {
            above("select", "dpad", gap);
            float[] s = centre("select");
            put("start", w - s[0], s[1]);
            if (crowded("start", gap, gap / 2f)) above("start", "rs", gap);
        }
        if (crowded("guide", gap, gap)) {
            float row = centre("select")[1];
            put("guide", w / 2f, row);
            if (crowded("guide", gap, gap)) put("guide", w / 2f, safe.top + dp(8f) + extent("guide"));
        }
    }

    private float drop(String group, String anchor, float gap) {
        return (extent(anchor) + extent(group) + gap) * (float) Math.sin(Math.toRadians(DROP_DEGREES)) + extent(group);
    }

    private void besideBelow(String group, String anchor, float inward, float gap, float margin) {
        float[] a = centre(anchor);
        float reach = extent(anchor) + extent(group) + gap;
        float floor = getHeight() - safe.bottom - margin - extent(group);
        float dy = Math.min(reach * (float) Math.sin(Math.toRadians(DROP_DEGREES)), floor - a[1]);
        float dx = (float) Math.sqrt(Math.max(0f, reach * reach - dy * dy));
        put(group, a[0] + inward * dx, a[1] + dy);
    }

    private void shoulders(String anchor, String bumper, String trigger, float inward, float gap, float y) {
        float ax = centre(anchor)[0];
        float spread = extent(bumper) + gap / 2f;
        put(bumper, ax + inward * spread, y);
        put(trigger, ax - inward * spread, y);
    }

    private void above(String group, String anchor, float gap) {
        float[] a = centre(anchor);
        put(group, a[0], a[1] - extent(anchor) - gap - extent(group));
    }

    private boolean crowded(String group, float gap, float space) {
        float[] c = centre(group);
        for (String other : groups) {
            if (other.equals(group)) continue;
            float[] o = centre(other);
            float dx = c[0] - o[0], dy = c[1] - o[1];
            if (Math.sqrt(dx * dx + dy * dy) < extent(group) + extent(other) + space) return true;
        }
        return false;
    }

    private List<Control> members(String group) {
        List<Control> list = new ArrayList<>();
        for (Control c : controls) if (c.group.equals(group)) list.add(c);
        return list;
    }

    private float[] centre(String group) {
        Control first = members(group).get(0);
        return new float[]{first.cx - scaled(first.offsetXDp), first.cy - scaled(first.offsetYDp)};
    }

    private float extent(String group) {
        float best = 0f;
        for (Control c : members(group)) {
            float ox = scaled(c.offsetXDp), oy = scaled(c.offsetYDp);
            best = Math.max(best, (float) Math.sqrt(ox * ox + oy * oy) + c.halfW());
        }
        return best;
    }

    private float halfH(String group) {
        float best = 0f;
        for (Control c : members(group)) best = Math.max(best, Math.abs(scaled(c.offsetYDp)) + c.halfH());
        return best;
    }

    private void put(String group, float x, float y) {
        for (Control c : members(group)) {
            c.cx = x + scaled(c.offsetXDp);
            c.cy = y + scaled(c.offsetYDp);
        }
    }

    private void clamp(String group) {
        List<Control> list = members(group);
        float margin = dp(4f);
        float minX = Float.MAX_VALUE, maxX = -Float.MAX_VALUE, minY = Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
        for (Control c : list) {
            minX = Math.min(minX, c.cx - c.halfW());
            maxX = Math.max(maxX, c.cx + c.halfW());
            minY = Math.min(minY, c.cy - c.halfH());
            maxY = Math.max(maxY, c.cy + c.halfH());
        }
        minX = minX - safe.left - margin;
        maxX = maxX - (getWidth() - safe.right - margin);
        minY = minY - safe.top - margin;
        maxY = maxY - (getHeight() - safe.bottom - margin);
        float dx = minX < 0 ? -minX : (maxX > 0 ? -maxX : 0f);
        float dy = minY < 0 ? -minY : (maxY > 0 ? -maxY : 0f);
        if (dx == 0f && dy == 0f) return;
        for (Control c : list) { c.cx += dx; c.cy += dy; }
    }

    public void saveLayout() {
        if (getWidth() <= 0 || getHeight() <= 0) return;
        if (ignoreSaved) { DDPrefs.resetLayout(getContext(), getWidth(), getHeight()); return; }
        Map<String, float[]> out = new HashMap<>();
        for (String g : groups) {
            float[] c = centre(g);
            out.put(g, new float[]{c[0] / getWidth(), c[1] / getHeight()});
        }
        DDPrefs.setLayout(getContext(), getWidth(), getHeight(), out);
    }

    public void resetLayout() {
        DDPrefs.resetLayout(getContext(), getWidth(), getHeight());
        ignoreSaved = true;
        selected = null;
        relayout();
    }

    private String label(Control c) {
        switch (c.target) {
            case "ls": return "L";
            case "rs": return "R";
            case "select": return "\u29C9";
            case "start": return "\u2630";
            case "guide": return "\u25C9";
            default: return c.target.toUpperCase();
        }
    }

    // DroidDeck-visibility: D-pad disembunyikan sebagai satu kelompok ("dpad"); lainnya per id
    private boolean isVisible(Control c) {
        return !DDPrefs.OFF.equals(c.target) && !settings.hidden.contains("dpad".equals(c.group) ? "dpad" : c.id);
    }

    private int directionIndex(String target) {
        for (int i = 0; i < DIRECTIONS.length; i++) if (DIRECTIONS[i].equals(target)) return i;
        return -1;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (!quickHidden) {
            for (Control c : controls) {
                if (!isVisible(c)) continue;
                if (c.stick >= 0 && settings.adaptiveSticks && !editing && c.pressedBy == -1) continue;
                boolean held = c.pressedBy != -1;
                float radius = c.radius;
                if (c.stick >= 0) {
                    float bx = held ? c.ax : c.cx, by = held ? c.ay : c.cy;
                    fill.setColor(held ? heldFill : stickFill);
                    canvas.drawCircle(bx, by, radius, fill);
                    stroke.setColor(held ? heldStroke : idleStroke);
                    canvas.drawCircle(bx, by, radius, stroke);
                    float knob = radius * 0.46f;
                    fill.setColor(held || c.clicked ? heldFill : knobFill);
                    canvas.drawCircle(bx + c.kx, by + c.ky, knob, fill);
                    stroke.setColor(held ? heldStroke : idleStroke);
                    canvas.drawCircle(bx + c.kx, by + c.ky, knob, stroke);
                    text.setColor(held ? Color.WHITE : idleText);
                    text.setTextSize(knob * 0.8f);
                    canvas.drawText(label(c), bx + c.kx, by + c.ky + text.getTextSize() * 0.35f, text);
                } else if (c.wide) {
                    box.set(c.cx - c.halfW(), c.cy - c.halfH(), c.cx + c.halfW(), c.cy + c.halfH());
                    float corner = c.halfH() * 0.55f;
                    fill.setColor(held ? heldFill : idleFill);
                    canvas.drawRoundRect(box, corner, corner, fill);
                    stroke.setColor(held ? heldStroke : idleStroke);
                    canvas.drawRoundRect(box, corner, corner, stroke);
                    text.setColor(held ? Color.WHITE : idleText);
                    text.setTextSize(c.halfH() * 0.8f);
                    canvas.drawText(label(c), c.cx, c.cy + text.getTextSize() * 0.35f, text);
                } else {
                    fill.setColor(held ? heldFill : idleFill);
                    canvas.drawCircle(c.cx, c.cy, radius, fill);
                    stroke.setColor(held ? heldStroke : idleStroke);
                    canvas.drawCircle(c.cx, c.cy, radius, stroke);
                    text.setColor(held ? Color.WHITE : idleText);
                    int dir = directionIndex(c.target);
                    if (dir >= 0) {
                        drawArrow(canvas, c.cx, c.cy, radius * 0.34f, dir);
                        continue;
                    }
                    String name = label(c);
                    text.setTextSize(name.length() > 2 ? dp(11f) : radius * (name.length() == 2 ? 0.62f : 0.85f));
                    canvas.drawText(name, c.cx, c.cy + text.getTextSize() * 0.35f, text);
                }
            }
        }
        if (editing) {
            if (selected == null) return;
            float[] ctr = centre(selected);
            stroke.setColor(heldStroke);
            List<Control> list = members(selected);
            if (list.size() == 1 && list.get(0).wide) {
                Control single = list.get(0);
                float pad = dp(6f);
                box.set(ctr[0] - single.halfW() - pad, ctr[1] - single.halfH() - pad, ctr[0] + single.halfW() + pad, ctr[1] + single.halfH() + pad);
                canvas.drawRoundRect(box, single.halfH(), single.halfH(), stroke);
            } else {
                canvas.drawCircle(ctr[0], ctr[1], extent(selected) + dp(6f), stroke);
            }
        } else {
            float[] q = quickCenter();
            float radius = dp(18f);
            fill.setColor(Color.argb(190, Color.red(idleFill), Color.green(idleFill), Color.blue(idleFill)));
            canvas.drawCircle(q[0], q[1], radius, fill);
            stroke.setColor(idleStroke);
            canvas.drawCircle(q[0], q[1], radius, stroke);
            text.setColor(idleText);
            text.setTextSize(dp(22f));
            canvas.drawText(quickHidden ? "+" : "\u2212", q[0], q[1] + text.getTextSize() * 0.35f, text);
        }
    }

    private float[] quickCenter() { return new float[]{safe.left + dp(34f), getHeight() - safe.bottom - dp(34f)}; }

    private boolean quickContains(float x, float y) {
        float[] q = quickCenter();
        float r = dp(26f);
        float dx = x - q[0], dy = y - q[1];
        return dx * dx + dy * dy <= r * r;
    }

    private void drawArrow(Canvas canvas, float x, float y, float size, int direction) {
        float fx, fy;
        switch (direction) {
            case 0: fx = 0f; fy = -1f; break;
            case 1: fx = 1f; fy = 0f; break;
            case 2: fx = 0f; fy = 1f; break;
            default: fx = -1f; fy = 0f; break;
        }
        arrow.reset();
        arrow.moveTo(x + fx * size, y + fy * size);
        arrow.lineTo(x - fx * size * 0.7f - fy * size, y - fy * size * 0.7f + fx * size);
        arrow.lineTo(x - fx * size * 0.7f + fy * size, y - fy * size * 0.7f - fx * size);
        arrow.close();
        fill.setColor(text.getColor());
        canvas.drawPath(arrow, fill);
    }

    private Control controlAt(float x, float y) {
        for (Control c : controls) {
            if (!quickHidden && isVisible(c) && (editing || !settings.adaptiveSticks || c.stick < 0) && c.contains(x, y, c.radius, 0f)) return c;
        }
        return null;
    }

    private Control adaptiveStickAt(float x, float y) {
        if (!settings.adaptiveSticks || editing || quickHidden) return null;
        if (x < safe.left || x >= getWidth() - safe.right || y < safe.top || y >= getHeight() - safe.bottom) return null;
        for (Control c : controls) {
            if (c.stick < 0 && isVisible(c) && c.contains(x, y, c.radius, 5f)) return null;
        }
        Control best = null;
        float bestD = Float.MAX_VALUE;
        for (Control c : controls) {
            float dx = x - c.cx, dy = y - c.cy;
            float reach = c.radius * 1.4f * (float) Math.sqrt(1.5f);
            if (c.stick >= 0 && isVisible(c) && c.pressedBy == -1 && dx * dx + dy * dy <= reach * reach) {
                float d = dx * dx + dy * dy;
                if (d < bestD) { bestD = d; best = c; }
            }
        }
        return best;
    }

    @SuppressLint("ClickableViewAccessibility")
    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (editing) return onEditTouch(event);
        if (DDDeck.latLogOn()) {
            int am = event.getActionMasked();
            if (am == MotionEvent.ACTION_DOWN || am == MotionEvent.ACTION_POINTER_DOWN
                    || am == MotionEvent.ACTION_UP || am == MotionEvent.ACTION_POINTER_UP) {
                // umur = lama event menunggu sebelum UI thread memprosesnya (sentuhan -> onTouchEvent)
                Log.i("DDLAT", "touch act=" + am + " age_ms=" + (SystemClock.uptimeMillis() - event.getEventTime())
                        + " up=" + SystemClock.uptimeMillis());
            }
        }
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_POINTER_DOWN: {
                int index = event.getActionIndex();
                float x = event.getX(index), y = event.getY(index);
                if (event.getActionMasked() == MotionEvent.ACTION_DOWN && quickContains(x, y)) {
                    quickPressedBy = event.getPointerId(index);
                    return true;
                }
                if (quickPressedBy != -1) return true;
                if (quickHidden) return false;
                Control c = controlAt(x, y);
                if (c == null) c = adaptiveStickAt(x, y);
                if (c == null) return false;
                if (c.pressedBy != -1) return true;
                c.pressedBy = event.getPointerId(index);
                if (c.stick >= 0) {
                    c.clicked = settings.stickClick && c.lastUp > 0L && event.getEventTime() - c.lastUp < DOUBLE_TAP_MS;
                    boolean adaptive = settings.adaptiveSticks;
                    c.ax = adaptive ? x : c.cx;
                    c.ay = adaptive ? y : c.cy;
                    c.drag(x, y);
                }
                requestUnbufferedDispatch(event);
                apply();
                return true;
            }
            case MotionEvent.ACTION_MOVE: {
                if (quickPressedBy != -1) return true;
                boolean changed = false;
                for (int index = 0; index < event.getPointerCount(); index++) {
                    int pointer = event.getPointerId(index);
                    float x = event.getX(index), y = event.getY(index);
                    Control stickCtl = null;
                    for (Control c : controls) if (c.stick >= 0 && c.pressedBy == pointer) { stickCtl = c; break; }
                    if (stickCtl != null) { stickCtl.drag(x, y); changed = true; continue; }
                    Control over = controlAt(x, y);
                    if (over != null && over.stick >= 0) over = null;
                    for (Control c : controls) {
                        if (c.stick < 0 && c.pressedBy == pointer && c != over) {
                            c.pressedBy = -1;
                            changed = true;
                        }
                    }
                    if (over != null && over.pressedBy == -1) {
                        over.pressedBy = pointer;
                        changed = true;
                    }
                }
                if (changed) apply();
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_POINTER_UP:
            case MotionEvent.ACTION_CANCEL: {
                int pointer = event.getPointerId(event.getActionIndex());
                if (event.getActionMasked() == MotionEvent.ACTION_CANCEL) {
                    quickPressedBy = -1;
                    releaseAll();
                    return true;
                }
                if (quickPressedBy == pointer) {
                    boolean toggled = event.getActionMasked() == MotionEvent.ACTION_UP && quickContains(event.getX(), event.getY());
                    quickPressedBy = -1;
                    if (toggled) setQuickHidden(!quickHidden);
                    return true;
                }
                if (quickHidden) return false;
                boolean changed = false;
                for (Control c : controls) {
                    if (c.pressedBy == pointer) {
                        if (c.pressedBy != -1) changed = true;
                        if (c.stick >= 0 && c.pressedBy != -1) c.lastUp = c.clicked ? 0L : event.getEventTime();
                        c.pressedBy = -1;
                        c.clicked = false;
                        if (c.stick >= 0 && (c.kx != 0f || c.ky != 0f)) c.dirty = true;
                        c.kx = 0f;
                        c.ky = 0f;
                    }
                }
                if (changed) apply();
                return true;
            }
            default:
                return false;
        }
    }

    private boolean onEditTouch(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                Control c = controlAt(event.getX(), event.getY());
                selected = c != null ? c.group : null;
                if (selected == null) { invalidate(); return false; }
                float[] ctr = centre(selected);
                grabX = ctr[0] - event.getX();
                grabY = ctr[1] - event.getY();
                invalidate();
                break;
            }
            case MotionEvent.ACTION_MOVE: {
                if (selected == null) return true;
                ignoreSaved = false;
                put(selected, event.getX() + grabX, event.getY() + grabY);
                clamp(selected);
                invalidate();
                break;
            }
            default:
                break;
        }
        return true;
    }

    /** Sensitivitas stik dari pengaturan: >100% mencapai defleksi penuh lebih cepat, <100% membatasi keluaran. */
    private float sens(float v) {
        float g = settings != null ? settings.stickSens / 100f : 1f;
        return Math.max(-1f, Math.min(1f, v * g));
    }

    private void apply() {
        if (sink == null || editing) { invalidate(); return; }
        Set<String> used = new HashSet<>();
        Set<String> held = new HashSet<>();
        for (Control c : controls) {
            if (c.stick >= 0) {
                String click = c.stick == 0 ? "l3" : "r3";
                if (settings.stickClick) used.add(click);
                if (c.clicked) held.add(click);
                if (!c.dirty) continue;
                if (c.stick == 0) { state.thumbLX = sens(c.kx / c.radius); state.thumbLY = sens(c.ky / c.radius); }
                else { state.thumbRX = sens(c.kx / c.radius); state.thumbRY = sens(c.ky / c.radius); }
                c.dirty = false;
            } else if (DDPrefs.OFF.equals(c.target)) {
                // tersembunyi
            } else {
                used.add(c.target);
                if (c.pressedBy != -1) held.add(c.target);
            }
        }
        for (String target : used) write(target, held.contains(target));
        sink.onPad(state);
        invalidate();
    }

    private void write(String target, boolean down) {
        switch (target) {
            case "a": state.setPressed(0, down); break;
            case "b": state.setPressed(1, down); break;
            case "x": state.setPressed(2, down); break;
            case "y": state.setPressed(3, down); break;
            case "lb": state.setPressed(4, down); break;
            case "rb": state.setPressed(5, down); break;
            case "select": state.setPressed(6, down); break;
            case "start": state.setPressed(7, down); break;
            case "l3": state.setPressed(8, down); break;
            case "r3": state.setPressed(9, down); break;
            case "lt": state.triggerL = down ? 1f : 0f; break;
            case "rt": state.triggerR = down ? 1f : 0f; break;
            case "up": state.dpad[0] = down; break;
            case "right": state.dpad[1] = down; break;
            case "down": state.dpad[2] = down; break;
            case "left": state.dpad[3] = down; break;
            case "guide":
                if (guideDown != down) { guideDown = down; if (sink != null) sink.onGuide(down); }
                break;
            default: break;
        }
    }

    public void releaseAll() {
        boolean any = false;
        for (Control c : controls) if (c.pressedBy != -1 || c.clicked) { any = true; break; }
        if (!any) return;
        for (Control c : controls) {
            c.pressedBy = -1;
            c.clicked = false;
            c.lastUp = 0L;
            if (c.stick >= 0 && (c.kx != 0f || c.ky != 0f)) c.dirty = true;
            c.kx = 0f;
            c.ky = 0f;
        }
        apply();
    }
}

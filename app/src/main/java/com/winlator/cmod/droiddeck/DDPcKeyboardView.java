package com.winlator.cmod.droiddeck;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.view.MotionEvent;
import android.view.View;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Keyboard PC di layar (port dari PcKeyboard.kt). Setiap tombol adalah tombol fisik
 * (kode evdev) yang ditekan saat jari mendarat dan dilepas saat jari diangkat.
 * Ctrl/Shift/Alt/Super bersifat sticky: ketuk 1x = tahan untuk tombol berikutnya,
 * 2x = kunci, 3x = lepas.
 */
@SuppressLint("ViewConstructor")
public class DDPcKeyboardView extends View {

    public interface Sink {
        void key(int evdev, boolean down);
        void androidKeyboard();
        void close();
    }

    private static final class Key {
        final String label, shifted;
        final int code;
        final float weight;
        final RectF rect = new RectF();
        boolean down;

        Key(String label, int code, float weight, String shifted) {
            this.label = label;
            this.code = code;
            this.weight = weight;
            this.shifted = shifted;
        }
    }

    private static Key k(String label, int code) { return new Key(label, code, 1f, null); }
    private static Key k(String label, int code, float w) { return new Key(label, code, w, null); }
    private static Key k(String label, int code, String shifted) { return new Key(label, code, 1f, shifted); }
    private static Key k(String label, int code, float w, String shifted) { return new Key(label, code, w, shifted); }

    private static final int MOD_OFF = 0, MOD_ONCE = 1, MOD_LOCKED = 2;
    private static final Set<Integer> MODIFIERS = new HashSet<>();
    static {
        MODIFIERS.add(42); MODIFIERS.add(54); MODIFIERS.add(29); MODIFIERS.add(97);
        MODIFIERS.add(56); MODIFIERS.add(100); MODIFIERS.add(125);
    }

    private final List<List<Key>> rows = new ArrayList<>();
    private final Map<Integer, Integer> mods = new HashMap<>();
    private final Map<Integer, Key> pointerKey = new HashMap<>();
    private final Sink sink;
    private boolean capsOn = false;
    private final int tint;
    private final RectF androidBtn = new RectF(), closeBtn = new RectF();
    private int headerPointer = -1;
    private int headerTarget = 0; // 1 = Android, 2 = Hide

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);

    public DDPcKeyboardView(Context context, Sink sink, int tint) {
        super(context);
        this.sink = sink;
        this.tint = tint;
        text.setTextAlign(Paint.Align.CENTER);
        text.setTypeface(Typeface.DEFAULT_BOLD);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeWidth(dp(1f));
        buildRows();
    }

    private void buildRows() {
        List<Key> r0 = new ArrayList<>();
        r0.add(k("Esc", 1));
        int[] f = {59, 60, 61, 62, 63, 64, 65, 66, 67, 68, 87, 88};
        for (int i = 0; i < f.length; i++) r0.add(k("F" + (i + 1), f[i]));
        r0.add(k("Ins", 110)); r0.add(k("Del", 111)); r0.add(k("Home", 102));
        r0.add(k("End", 107)); r0.add(k("PgUp", 104)); r0.add(k("PgDn", 109));
        rows.add(r0);

        List<Key> r1 = new ArrayList<>();
        r1.add(k("`", 41, "~")); r1.add(k("1", 2, "!")); r1.add(k("2", 3, "@")); r1.add(k("3", 4, "#"));
        r1.add(k("4", 5, "$")); r1.add(k("5", 6, "%")); r1.add(k("6", 7, "^")); r1.add(k("7", 8, "&"));
        r1.add(k("8", 9, "*")); r1.add(k("9", 10, "(")); r1.add(k("0", 11, ")")); r1.add(k("-", 12, "_"));
        r1.add(k("=", 13, "+")); r1.add(k("\u232B Backspace", 14, 2f));
        rows.add(r1);

        List<Key> r2 = new ArrayList<>();
        r2.add(k("Tab \u21E5", 15, 1.5f));
        String q = "qwertyuiop";
        int[] qc = {16, 17, 18, 19, 20, 21, 22, 23, 24, 25};
        for (int i = 0; i < q.length(); i++) r2.add(k(String.valueOf(q.charAt(i)), qc[i]));
        r2.add(k("[", 26, "{")); r2.add(k("]", 27, "}")); r2.add(k("\\", 43, 1.5f, "|"));
        rows.add(r2);

        List<Key> r3 = new ArrayList<>();
        r3.add(k("Caps", 58, 1.75f));
        String a = "asdfghjkl";
        int[] ac = {30, 31, 32, 33, 34, 35, 36, 37, 38};
        for (int i = 0; i < a.length(); i++) r3.add(k(String.valueOf(a.charAt(i)), ac[i]));
        r3.add(k(";", 39, ":")); r3.add(k("'", 40, "\"")); r3.add(k("Enter \u23CE", 28, 2.25f));
        rows.add(r3);

        List<Key> r4 = new ArrayList<>();
        r4.add(k("\u21E7 Shift", 42, 2.25f));
        String z = "zxcvbnm";
        int[] zc = {44, 45, 46, 47, 48, 49, 50};
        for (int i = 0; i < z.length(); i++) r4.add(k(String.valueOf(z.charAt(i)), zc[i]));
        r4.add(k(",", 51, "<")); r4.add(k(".", 52, ">")); r4.add(k("/", 53, "?"));
        r4.add(k("\u21E7 Shift", 54, 1.75f)); r4.add(k("\u2191", 103));
        rows.add(r4);

        List<Key> r5 = new ArrayList<>();
        r5.add(k("Ctrl", 29, 1.5f)); r5.add(k("Super", 125, 1.25f)); r5.add(k("Alt", 56, 1.25f));
        r5.add(k("Space", 57, 6.25f)); r5.add(k("AltGr", 100, 1.25f)); r5.add(k("Ctrl", 97, 1.25f));
        r5.add(k("\u2190", 105)); r5.add(k("\u2193", 108)); r5.add(k("\u2192", 106));
        rows.add(r5);
    }

    private float dp(float v) { return v * getResources().getDisplayMetrics().density; }

    private float rowHeight(int parentH) {
        float h = (parentH * 0.47f - dp(24f) - dp(3f) * 6 - dp(10f)) / 6f;
        return Math.max(dp(24f), Math.min(dp(44f), h));
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        int parentH = getParent() instanceof View ? ((View) getParent()).getHeight() : MeasureSpec.getSize(heightSpec);
        if (parentH <= 0) parentH = MeasureSpec.getSize(heightSpec);
        float row = rowHeight(parentH);
        int h = (int) (dp(10f) + dp(24f) + dp(3f) * 6 + row * 6);
        setMeasuredDimension(MeasureSpec.getSize(widthSpec), h);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        layoutKeys(w, h);
    }

    private void layoutKeys(int w, int h) {
        float padX = dp(8f), padY = dp(5f), gap = dp(3f), header = dp(24f);
        float rowH = (h - 2 * padY - header - gap * 6) / 6f;
        float btnW = dp(96f);
        androidBtn.set(w - padX - btnW * 2 - dp(8f), padY, w - padX - btnW - dp(8f), padY + header);
        closeBtn.set(w - padX - btnW, padY, w - padX, padY + header);
        float y = padY + header + gap;
        for (List<Key> row : rows) {
            float sum = 0f;
            for (Key key : row) sum += key.weight;
            float avail = w - 2 * padX - gap * (row.size() - 1);
            float x = padX;
            for (Key key : row) {
                float kw = avail * key.weight / sum;
                key.rect.set(x, y, x + kw, y + rowH);
                x += kw + gap;
            }
            y += rowH + gap;
        }
    }

    private int mod(int code) {
        Integer v = mods.get(code);
        return v == null ? MOD_OFF : v;
    }

    private boolean shifted() { return mod(42) != MOD_OFF || mod(54) != MOD_OFF; }

    private void releaseOnceModifiers() {
        for (Map.Entry<Integer, Integer> e : new HashMap<>(mods).entrySet()) {
            if (e.getValue() == MOD_ONCE) { sink.key(e.getKey(), false); mods.put(e.getKey(), MOD_OFF); }
        }
    }

    private void press(Key key) {
        if (MODIFIERS.contains(key.code)) {
            switch (mod(key.code)) {
                case MOD_OFF: sink.key(key.code, true); mods.put(key.code, MOD_ONCE); break;
                case MOD_ONCE: mods.put(key.code, MOD_LOCKED); break;
                default: sink.key(key.code, false); mods.put(key.code, MOD_OFF); break;
            }
            return;
        }
        sink.key(key.code, true);
    }

    private void release(Key key) {
        if (MODIFIERS.contains(key.code)) return;
        sink.key(key.code, false);
        if (key.code == 58) capsOn = !capsOn;
        releaseOnceModifiers();
    }

    /** Lepas semua yang masih tertahan (dipanggil saat keyboard ditutup). */
    public void releaseAll() {
        for (Key key : pointerKey.values()) { if (key.down) { key.down = false; sink.key(key.code, false); } }
        pointerKey.clear();
        for (Map.Entry<Integer, Integer> e : new HashMap<>(mods).entrySet()) {
            if (e.getValue() != MOD_OFF) sink.key(e.getKey(), false);
        }
        mods.clear();
        invalidate();
    }

    @Override
    protected void onDetachedFromWindow() {
        releaseAll();
        super.onDetachedFromWindow();
    }

    private Key keyAt(float x, float y) {
        for (List<Key> row : rows) for (Key key : row) if (key.rect.contains(x, y)) return key;
        return null;
    }

    @SuppressLint("ClickableViewAccessibility")
    @Override
    public boolean onTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_POINTER_DOWN: {
                int index = event.getActionIndex();
                float x = event.getX(index), y = event.getY(index);
                int id = event.getPointerId(index);
                if (androidBtn.contains(x, y)) { headerPointer = id; headerTarget = 1; invalidate(); return true; }
                if (closeBtn.contains(x, y)) { headerPointer = id; headerTarget = 2; invalidate(); return true; }
                Key key = keyAt(x, y);
                if (key != null) {
                    key.down = true;
                    pointerKey.put(id, key);
                    press(key);
                    invalidate();
                }
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_POINTER_UP: {
                int index = event.getActionIndex();
                int id = event.getPointerId(index);
                float x = event.getX(index), y = event.getY(index);
                if (id == headerPointer) {
                    int target = headerTarget;
                    headerPointer = -1;
                    headerTarget = 0;
                    if (target == 1 && androidBtn.contains(x, y)) sink.androidKeyboard();
                    else if (target == 2 && closeBtn.contains(x, y)) sink.close();
                    invalidate();
                    return true;
                }
                Key key = pointerKey.remove(id);
                if (key != null) { key.down = false; release(key); invalidate(); }
                return true;
            }
            case MotionEvent.ACTION_CANCEL: {
                headerPointer = -1;
                headerTarget = 0;
                for (Key key : new ArrayList<>(pointerKey.values())) { key.down = false; release(key); }
                pointerKey.clear();
                invalidate();
                return true;
            }
            default:
                return true; // semua sentuhan berhenti di keyboard
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float r = dp(14f);
        fill.setColor(0xE6101418);
        canvas.drawRoundRect(0, 0, getWidth(), getHeight() + r, r, r, fill);
        stroke.setColor(Color.argb(60, 255, 255, 255));
        canvas.drawRoundRect(0, 0, getWidth(), getHeight() + r, r, r, stroke);

        text.setColor(Color.argb(190, 255, 255, 255));
        text.setTextSize(dp(12f));
        text.setTextAlign(Paint.Align.LEFT);
        canvas.drawText("PC Keyboard", dp(10f), dp(5f) + dp(24f) * 0.68f, text);
        text.setTextAlign(Paint.Align.CENTER);
        drawButton(canvas, androidBtn, "Android keyboard", headerTarget == 1);
        drawButton(canvas, closeBtn, "Hide", headerTarget == 2);

        boolean shifted = shifted();
        for (List<Key> row : rows) {
            for (Key key : row) {
                int state = mod(key.code);
                boolean lit = state != MOD_OFF || (key.code == 58 && capsOn);
                int alpha = key.down ? 140 : (lit ? (state == MOD_LOCKED ? 115 : 70) : 18);
                fill.setColor(lit || key.down
                    ? Color.argb(alpha, Color.red(tint), Color.green(tint), Color.blue(tint))
                    : Color.argb(alpha, 255, 255, 255));
                canvas.drawRoundRect(key.rect, dp(7f), dp(7f), fill);
                stroke.setColor(lit ? Color.argb(180, Color.red(tint), Color.green(tint), Color.blue(tint)) : Color.argb(30, 255, 255, 255));
                canvas.drawRoundRect(key.rect, dp(7f), dp(7f), stroke);

                String label = key.label;
                if (shifted && key.shifted != null) label = key.shifted;
                else if (label.length() == 1 && Character.isLetter(label.charAt(0)) && (shifted ^ capsOn)) label = label.toUpperCase();
                text.setColor(Color.WHITE);
                text.setTextSize(label.length() > 2 ? dp(11f) : dp(15f));
                canvas.drawText(label, key.rect.centerX(), key.rect.centerY() + text.getTextSize() * 0.35f, text);
            }
        }
    }

    private void drawButton(Canvas canvas, RectF rect, String label, boolean pressed) {
        fill.setColor(Color.argb(pressed ? 70 : 20, 255, 255, 255));
        canvas.drawRoundRect(rect, dp(7f), dp(7f), fill);
        stroke.setColor(Color.argb(40, 255, 255, 255));
        canvas.drawRoundRect(rect, dp(7f), dp(7f), stroke);
        text.setColor(Color.WHITE);
        text.setTextSize(dp(11f));
        canvas.drawText(label, rect.centerX(), rect.centerY() + text.getTextSize() * 0.35f, text);
    }
}

package com.winlator.cmod.droiddeck;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.view.MotionEvent;
import android.view.View;

import java.util.HashMap;
import java.util.Map;

/**
 * Kontrol khas Deck di layar (DeckControlsPanel.kt): dua trackpad, grip L4/R4/L5/R5 dan QAM.
 * Ketuk singkat pada trackpad = klik pad. Sentuhan di luar kontrol ini diteruskan ke kontrol di bawahnya.
 */
@SuppressLint("ViewConstructor")
public class DDDeckControlsView extends View {
    private static final long TAP_MS = 180L;
    private static final float TAP_SLOP_DP = 8f;
    private static final int BUTTONS = 5;  // L4, L5, QAM, R5, R4
    private static final String[] NAMES = {"L4", "L5", "QAM", "R5", "R4"};

    private final RectF[] pad = {new RectF(), new RectF()};
    private final RectF[] btn = new RectF[BUTTONS];
    private final int[] padPointer = {-1, -1};
    private final float[] downX = new float[2], downY = new float[2];
    private final long[] downAt = new long[2];
    private final boolean[] moved = new boolean[2];
    private final int[] btnPointer = {-1, -1, -1, -1, -1};
    private final boolean[] btnDown = new boolean[BUTTONS];
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), stroke = new Paint(Paint.ANTI_ALIAS_FLAG), text = new Paint(Paint.ANTI_ALIAS_FLAG);
    // DroidDeck-deckmap: warna, opacity dan ukuran sama dengan kontrol utama (DDOnScreenControls) dan panel samping
    private DDPrefs.Settings settings;
    private int idleFill, heldFill, idleStroke, heldStroke, idleText, padFill;

    // Mode edit layout: elemen bisa dipilih dan digeser, tidak ada input yang dikirim ke DDDeck.
    // id 0..1 = trackpad kiri/kanan, id 2..6 = L4, L5, QAM, R5, R4 (urutan sama dengan btn[]).
    private static final String[] KEYS = {"dk_padL", "dk_padR", "dk_L4", "dk_L5", "dk_qam", "dk_R5", "dk_R4"};
    private final boolean editing;
    private boolean ignoreSaved = false;
    private int selected = -1, editPointer = -1;
    private float grabX, grabY;
    private Runnable onSelect;

    public DDDeckControlsView(Context context, int tint) { this(context, tint, false); }

    public DDDeckControlsView(Context context, int tint, boolean editing) {
        super(context);
        this.editing = editing;
        for (int i = 0; i < BUTTONS; i++) btn[i] = new RectF();
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeWidth(dp(1.5f));
        text.setTextAlign(Paint.Align.CENTER);
        text.setTypeface(Typeface.DEFAULT_BOLD);
        applySettings();
    }

    private float dp(float v) { return v * getResources().getDisplayMetrics().density; }
    private float scaled(float v) { return dp(v) * settings.size / 100f; }

    /** Baca ulang Tint/Opacity/Size dari panel samping; rumus warna sama dengan DDOnScreenControls. */
    private void applySettings() {
        settings = DDPrefs.read(getContext());
        final int t = settings.tint;
        final float alpha = settings.opacity / 100f;
        idleFill = shade(t, alpha, 80, 0.12f);
        heldFill = shade(t, alpha, 160, 1f);
        idleStroke = light(t, alpha, 150, 0.25f);
        heldStroke = light(t, alpha, 230, 0.7f);
        idleText = light(t, alpha, 210, 0.75f);
        padFill = shade(t, alpha, 60, 0.12f);
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

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        relayout();
    }

    private RectF rect(int id) { return id < 2 ? pad[id] : btn[id - 2]; }

    private void relayout() {
        applySettings();
        int w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0) return;
        float side = Math.min(h * 0.30f, w * 0.15f) * settings.size / 100f;
        float cy = h - side * 0.5f - dp(10f);
        float gap = side * 0.15f;
        pad[0].set(w / 2f - gap / 2f - side, cy - side / 2f, w / 2f - gap / 2f, cy + side / 2f);
        pad[1].set(w / 2f + gap / 2f, cy - side / 2f, w / 2f + gap / 2f + side, cy + side / 2f);
        float bw = scaled(44f), bh = scaled(28f), bg = scaled(6f);
        float total = BUTTONS * bw + (BUTTONS - 1) * bg;
        float x = w / 2f - total / 2f, top = dp(8f);
        for (int i = 0; i < BUTTONS; i++) {
            btn[i].set(x, top, x + bw, top + bh);
            x += bw + bg;
        }
        if (!ignoreSaved) {
            Map<String, float[]> saved = DDPrefs.layout(getContext(), w, h);
            for (int id = 0; id < KEYS.length; id++) {
                float[] v = saved.get(KEYS[id]);
                if (v != null) moveCenter(id, v[0] * w, v[1] * h);
            }
        }
        invalidate();
    }

    /** Pindahkan pusat elemen ke (cx, cy), ukuran tetap, dijaga tetap di dalam layar. */
    private void moveCenter(int id, float cx, float cy) {
        RectF r = rect(id);
        float hw = r.width() / 2f, hh = r.height() / 2f, m = dp(4f);
        cx = Math.max(hw + m, Math.min(getWidth() - hw - m, cx));
        cy = Math.max(hh + m, Math.min(getHeight() - hh - m, cy));
        r.set(cx - hw, cy - hh, cx + hw, cy + hh);
    }

    /** Dipanggil saat layout disimpan/direset dari luar (mode normal): baca ulang posisi tersimpan. */
    public void reload() {
        ignoreSaved = false;
        relayout();
        releaseHidden();  // elemen yang baru disembunyikan tidak boleh tertinggal dalam keadaan tertekan
    }

    // DroidDeck-visibility: id elemen sama dengan DDPrefs.VIS_IDS (pad_l, pad_r, lalu DDPrefs.DECK_IDS)
    private boolean padVisible(int i) { return !settings.hidden.contains(i == 0 ? "pad_l" : "pad_r"); }
    private boolean btnVisible(int i) { return !settings.hidden.contains(DDPrefs.DECK_IDS[i]); }

    private void releaseHidden() {
        for (int p = 0; p < 2; p++) {
            if (!padVisible(p) && padPointer[p] != -1) {
                padPointer[p] = -1;
                DDDeck.setPad(p == 1, false, 0f, 0f);
            }
        }
        for (int b = 0; b < BUTTONS; b++) {
            if (!btnVisible(b) && (btnPointer[b] != -1 || btnDown[b])) {
                btnPointer[b] = -1;
                sendButton(b, false);
            }
        }
        invalidate();
    }

    public void resetLayout() {
        ignoreSaved = true;
        selected = -1;
        relayout();
    }

    /** Gabungkan posisi elemen Deck ke layout tersimpan (panggil SETELAH DDOnScreenControls.saveLayout). */
    public void saveLayout() {
        int w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0) return;
        Map<String, float[]> all = new HashMap<>(DDPrefs.layout(getContext(), w, h));
        for (String k : KEYS) all.remove(k);
        if (!ignoreSaved) {
            for (int id = 0; id < KEYS.length; id++) {
                RectF r = rect(id);
                all.put(KEYS[id], new float[]{r.centerX() / w, r.centerY() / h});
            }
        }
        if (all.isEmpty()) DDPrefs.resetLayout(getContext(), w, h);
        else DDPrefs.setLayout(getContext(), w, h, all);
    }

    /** Dipanggil saat elemen Deck dipilih, supaya pilihan di view kontrol biasa dilepas. */
    public void setOnSelectListener(Runnable r) { onSelect = r; }

    public void clearSelection() {
        if (selected != -1) { selected = -1; invalidate(); }
    }

    private int elementAt(float x, float y) {
        int b = btnAt(x, y);
        if (b >= 0) return b + 2;
        return padAt(x, y);
    }

    private boolean onEditTouch(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                int hit = elementAt(e.getX(), e.getY());
                if (hit < 0) { clearSelection(); return false; }  // teruskan ke kontrol biasa di bawah
                selected = hit;
                editPointer = e.getPointerId(0);
                RectF r = rect(hit);
                grabX = r.centerX() - e.getX();
                grabY = r.centerY() - e.getY();
                if (onSelect != null) onSelect.run();
                invalidate();
                return true;
            }
            case MotionEvent.ACTION_MOVE: {
                if (selected < 0 || editPointer < 0) return false;
                int at = e.findPointerIndex(editPointer);
                if (at < 0) return true;
                moveCenter(selected, e.getX(at) + grabX, e.getY(at) + grabY);
                invalidate();
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                editPointer = -1;
                return selected >= 0;
            default:
                return selected >= 0 && editPointer >= 0;
        }
    }

    private int padAt(float x, float y) {
        for (int i = 0; i < 2; i++) if (padVisible(i) && pad[i].contains(x, y)) return i;
        return -1;
    }

    private int btnAt(float x, float y) {
        for (int i = 0; i < BUTTONS; i++) if (btnVisible(i) && btn[i].contains(x, y)) return i;
        return -1;
    }

    private void sendButton(int i, boolean down) {
        btnDown[i] = down;
        switch (i) {
            case 0: DDDeck.setGrip(DDDeck.GRIP_L4, down); break;
            case 1: DDDeck.setGrip(DDDeck.GRIP_L5, down); break;
            case 2: DDDeck.setQam(down); break;
            case 3: DDDeck.setGrip(DDDeck.GRIP_R5, down); break;
            default: DDDeck.setGrip(DDDeck.GRIP_R4, down); break;
        }
        DDController.onDeckExtra(i, down);  // DroidDeck-deckmap: diteruskan juga ke pad evdev sesuai pemetaan panel
        invalidate();
    }

    private void sendPad(int i, MotionEvent e, int index) {
        RectF r = pad[i];
        float x = (e.getX(index) - r.centerX()) / (r.width() / 2f);
        float y = -(e.getY(index) - r.centerY()) / (r.height() / 2f);  // y ke atas
        DDDeck.setPad(i == 1, true, x, y);
    }

    @SuppressLint("ClickableViewAccessibility")
    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (editing) return onEditTouch(e);
        int action = e.getActionMasked();
        int index = e.getActionIndex();
        int id = e.getPointerId(index);
        switch (action) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_POINTER_DOWN: {
                float x = e.getX(index), y = e.getY(index);
                int b = btnAt(x, y);
                if (b >= 0 && btnPointer[b] == -1) { btnPointer[b] = id; sendButton(b, true); return true; }
                int p = padAt(x, y);
                if (p >= 0 && padPointer[p] == -1) {
                    padPointer[p] = id;
                    downX[p] = x; downY[p] = y; downAt[p] = e.getEventTime(); moved[p] = false;
                    sendPad(p, e, index);
                    invalidate();
                    return true;
                }
                return false;  // bukan milik kita: teruskan ke kontrol di bawah
            }
            case MotionEvent.ACTION_MOVE:
                for (int p = 0; p < 2; p++) {
                    if (padPointer[p] == -1) continue;
                    int at = e.findPointerIndex(padPointer[p]);
                    if (at < 0) continue;
                    float dx = e.getX(at) - downX[p], dy = e.getY(at) - downY[p];
                    if (Math.hypot(dx, dy) > dp(TAP_SLOP_DP)) moved[p] = true;
                    sendPad(p, e, at);
                }
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_POINTER_UP:
            case MotionEvent.ACTION_CANCEL: {
                if (action == MotionEvent.ACTION_CANCEL) { releaseAll(); return true; }
                for (int b = 0; b < BUTTONS; b++) if (btnPointer[b] == id) { btnPointer[b] = -1; sendButton(b, false); }
                for (int p = 0; p < 2; p++) {
                    if (padPointer[p] != id) continue;
                    padPointer[p] = -1;
                    boolean tap = !moved[p] && e.getEventTime() - downAt[p] <= TAP_MS;
                    final boolean right = p == 1;
                    DDDeck.setPad(right, false, 0f, 0f);
                    if (tap) {
                        DDDeck.setClick(right, true);
                        handler.postDelayed(() -> DDDeck.setClick(right, false), 40L);
                    }
                    invalidate();
                }
                return true;
            }
            default:
                return false;
        }
    }

    public void releaseAll() {
        for (int p = 0; p < 2; p++) padPointer[p] = -1;
        for (int b = 0; b < BUTTONS; b++) {
            if (btnDown[b]) DDController.onDeckExtra(b, false);
            btnPointer[b] = -1;
            btnDown[b] = false;
        }
        DDDeck.releaseAll();
        invalidate();
    }

    @Override
    protected void onDetachedFromWindow() {
        releaseAll();
        super.onDetachedFromWindow();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        for (int i = 0; i < 2; i++) {
            if (!padVisible(i)) continue;
            boolean on = padPointer[i] != -1;
            fill.setColor(on ? heldFill : padFill);
            canvas.drawRoundRect(pad[i], dp(14f), dp(14f), fill);
            stroke.setColor(on ? heldStroke : idleStroke);
            canvas.drawRoundRect(pad[i], dp(14f), dp(14f), stroke);
            if (editing) {
                text.setTextSize(dp(12f));
                text.setColor(idleText);
                canvas.drawText(i == 0 ? "L PAD" : "R PAD", pad[i].centerX(), pad[i].centerY() + text.getTextSize() * 0.35f, text);
            }
        }
        for (int i = 0; i < BUTTONS; i++) {
            if (!btnVisible(i)) continue;
            boolean held = btnDown[i];
            float corner = btn[i].height() * 0.55f;  // sama dengan tombol lebar di kontrol utama
            fill.setColor(held ? heldFill : idleFill);
            canvas.drawRoundRect(btn[i], corner, corner, fill);
            stroke.setColor(held ? heldStroke : idleStroke);
            canvas.drawRoundRect(btn[i], corner, corner, stroke);
            text.setColor(held ? Color.WHITE : idleText);
            text.setTextSize(btn[i].height() * 0.4f);
            canvas.drawText(NAMES[i], btn[i].centerX(), btn[i].centerY() + text.getTextSize() * 0.35f, text);
        }
        if (editing && selected >= 0) {
            stroke.setColor(heldStroke);
            RectF box = new RectF(rect(selected));
            box.inset(-dp(5f), -dp(5f));
            canvas.drawRoundRect(box, dp(16f), dp(16f), stroke);
        }
    }
}

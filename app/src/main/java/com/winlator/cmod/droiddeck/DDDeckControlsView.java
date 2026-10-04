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
    private final int tint;

    public DDDeckControlsView(Context context, int tint) {
        super(context);
        this.tint = tint;
        for (int i = 0; i < BUTTONS; i++) btn[i] = new RectF();
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeWidth(dp(1.5f));
        text.setTextAlign(Paint.Align.CENTER);
        text.setTypeface(Typeface.DEFAULT_BOLD);
    }

    private float dp(float v) { return v * getResources().getDisplayMetrics().density; }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        float side = Math.min(h * 0.30f, w * 0.15f);
        float cy = h - side * 0.5f - dp(10f);
        float gap = side * 0.15f;
        pad[0].set(w / 2f - gap / 2f - side, cy - side / 2f, w / 2f - gap / 2f, cy + side / 2f);
        pad[1].set(w / 2f + gap / 2f, cy - side / 2f, w / 2f + gap / 2f + side, cy + side / 2f);
        float bw = dp(44f), bh = dp(28f), bg = dp(6f);
        float total = BUTTONS * bw + (BUTTONS - 1) * bg;
        float x = w / 2f - total / 2f, top = dp(8f);
        for (int i = 0; i < BUTTONS; i++) {
            btn[i].set(x, top, x + bw, top + bh);
            x += bw + bg;
        }
    }

    private int padAt(float x, float y) {
        for (int i = 0; i < 2; i++) if (pad[i].contains(x, y)) return i;
        return -1;
    }

    private int btnAt(float x, float y) {
        for (int i = 0; i < BUTTONS; i++) if (btn[i].contains(x, y)) return i;
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
        for (int b = 0; b < BUTTONS; b++) { btnPointer[b] = -1; btnDown[b] = false; }
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
        int r = Color.red(tint), g = Color.green(tint), b = Color.blue(tint);
        for (int i = 0; i < 2; i++) {
            boolean on = padPointer[i] != -1;
            fill.setColor(Color.argb(on ? 90 : 28, r, g, b));
            canvas.drawRoundRect(pad[i], dp(14f), dp(14f), fill);
            stroke.setColor(Color.argb(on ? 220 : 120, r, g, b));
            canvas.drawRoundRect(pad[i], dp(14f), dp(14f), stroke);
        }
        text.setTextSize(dp(11f));
        for (int i = 0; i < BUTTONS; i++) {
            fill.setColor(Color.argb(btnDown[i] ? 140 : 40, r, g, b));
            canvas.drawRoundRect(btn[i], dp(8f), dp(8f), fill);
            stroke.setColor(Color.argb(btnDown[i] ? 230 : 130, r, g, b));
            canvas.drawRoundRect(btn[i], dp(8f), dp(8f), stroke);
            text.setColor(Color.WHITE);
            canvas.drawText(NAMES[i], btn[i].centerX(), btn[i].centerY() + text.getTextSize() * 0.35f, text);
        }
    }
}

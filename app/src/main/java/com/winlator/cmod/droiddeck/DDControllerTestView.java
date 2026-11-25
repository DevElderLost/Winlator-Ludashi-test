package com.winlator.cmod.droiddeck;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.view.MotionEvent;
import android.view.View;

/**
 * Layar "Controller Test" bergaya Steam Big Picture: tab Test / Report / Motion / Status.
 * Hanya menggambar keadaan pad Deck dari DDDeck dan penghitung libfakeinput; sentuhan di luar
 * tombol header diteruskan ke view di bawahnya (return false), jadi aman dipakai sebagai overlay.
 */
@SuppressLint("ViewConstructor")
public class DDControllerTestView extends View {

    public interface Host {
        void close();
        void toggleControls();
        boolean controlsVisible();
        boolean standalone();
    }

    // Bit laporan Deck (SDL_hidapi_steamdeck.c).
    private static final int L_R2 = 0x1, L_L2 = 0x2, L_R1 = 0x4, L_L1 = 0x8, L_Y = 0x10, L_B = 0x20, L_X = 0x40,
        L_A = 0x80, L_UP = 0x100, L_RIGHT = 0x200, L_LEFT = 0x400, L_DOWN = 0x800, L_VIEW = 0x1000,
        L_STEAM = 0x2000, L_MENU = 0x4000, L_L5 = 0x8000, L_R5 = 0x10000, L_LPAD = 0x20000, L_RPAD = 0x40000,
        L_LTOUCH = 0x80000, L_RTOUCH = 0x100000, L_L3 = 0x400000, L_R3 = 0x4000000;
    private static final int H_L4 = 0x200, H_R4 = 0x400, H_QAM = 0x40000;

    private static final int[] LOW_MASKS = {L_R2, L_L2, L_R1, L_L1, L_Y, L_B, L_X, L_A, L_UP, L_RIGHT, L_LEFT, L_DOWN,
        L_VIEW, L_STEAM, L_MENU, L_L5, L_R5, L_LPAD, L_RPAD, L_LTOUCH, L_RTOUCH, L_L3, L_R3};
    private static final String[] LOW_NAMES = {"R2", "L2", "R1", "L1", "Y", "B", "X", "A", "Up", "Right", "Left", "Down",
        "View", "Steam", "Menu", "L5", "R5", "LPad click", "RPad click", "LPad touch", "RPad touch", "L3", "R3"};
    private static final int[] HIGH_MASKS = {H_L4, H_R4, H_QAM};
    private static final String[] HIGH_NAMES = {"L4", "R4", "QAM"};

    private static final String[] TABS = {"Test", "Report", "Motion", "Status"};
    private static final int HIT_TAB = 0, HIT_CLOSE = 10, HIT_CONTROLS = 11, HIT_RESET = 12;
    private static final int HIST = 160;

    private static final int COL_BG_TOP = 0xFF0E141B, COL_BG_BOTTOM = 0xFF1B2838, COL_ACCENT = 0xFF1A9FFF,
        COL_TEXT = 0xFFE5EEF7, COL_DIM = 0xFF8FA3B5, COL_LINE = 0xFF4B6176, COL_FILL = 0xFF1E2C3A;

    private final Host host;
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), line = new Paint(Paint.ANTI_ALIAS_FLAG),
        text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF[] tabRect = new RectF[TABS.length];
    private final RectF closeRect = new RectF(), controlsRect = new RectF(), resetRect = new RectF(), tmp = new RectF();
    private int tab = 0;
    private int downHit = -1, downPointer = -1;
    private int packet = 0;
    private byte[] prevReport = new byte[64];
    private final float[][] history = new float[6][HIST];
    private int histPos = 0;
    private float ox, oy, sc;
    private Shader bgShader;
    private int bgHeight = -1;

    public DDControllerTestView(Context context, Host host) {
        super(context);
        this.host = host;
        for (int i = 0; i < tabRect.length; i++) tabRect[i] = new RectF();
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeWidth(dp(1.5f));
        text.setTypeface(Typeface.DEFAULT_BOLD);
    }

    private float dp(float v) { return v * getResources().getDisplayMetrics().density; }

    // ------------------------------------------------------------------ touch

    private int hitTest(float x, float y) {
        if (closeRect.contains(x, y)) return HIT_CLOSE;
        if (host.standalone() && controlsRect.contains(x, y)) return HIT_CONTROLS;
        for (int i = 0; i < tabRect.length; i++) if (tabRect[i].contains(x, y)) return HIT_TAB + i;
        if (tab == 3 && resetRect.contains(x, y)) return HIT_RESET;
        return -1;
    }

    @SuppressLint("ClickableViewAccessibility")
    @Override
    public boolean onTouchEvent(MotionEvent e) {
        int action = e.getActionMasked();
        int index = e.getActionIndex();
        if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_POINTER_DOWN) {
            int hit = hitTest(e.getX(index), e.getY(index));
            if (hit < 0) return false;  // bukan milik kita: teruskan ke view di bawah
            downHit = hit;
            downPointer = e.getPointerId(index);
            return true;
        }
        if (downPointer == -1) return false;
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_POINTER_UP || action == MotionEvent.ACTION_CANCEL) {
            if (e.getPointerId(index) != downPointer) return true;
            int hit = action == MotionEvent.ACTION_CANCEL ? -1 : hitTest(e.getX(index), e.getY(index));
            int started = downHit;
            downHit = -1;
            downPointer = -1;
            if (hit == started) perform(hit);
        }
        return true;
    }

    private void perform(int hit) {
        if (hit == HIT_CLOSE) host.close();
        else if (hit == HIT_CONTROLS) host.toggleControls();
        else if (hit == HIT_RESET) DDDeck.resetStatus();
        else if (hit >= HIT_TAB && hit < HIT_TAB + TABS.length) tab = hit - HIT_TAB;
        invalidate();
    }

    // ------------------------------------------------------------------- draw

    @Override
    protected void onDraw(Canvas c) {
        int w = getWidth(), h = getHeight();
        if (host.standalone()) {
            if (bgShader == null || bgHeight != h) {
                bgShader = new LinearGradient(0, 0, 0, h, COL_BG_TOP, COL_BG_BOTTOM, Shader.TileMode.CLAMP);
                bgHeight = h;
            }
            fill.setShader(bgShader);
            c.drawRect(0, 0, w, h, fill);
            fill.setShader(null);
        } else {
            c.drawColor(0xD00E141B);
        }
        drawHeader(c, w);
        float top = dp(88), bottom = h - dp(8), left = dp(16), right = w - dp(16);
        float contentTop = top;
        if (!DDDeck.isSessionActive() && !host.standalone()) {
            text.setColor(0xFFFFC107);
            text.setTextSize(dp(12));
            text.setTextAlign(Paint.Align.LEFT);
            c.drawText("Pad Deck tidak aktif di sesi ini (Setting Container/Shortcut -> Steam Deck Pad = On, lalu jalankan ulang).",
                left, contentTop + dp(12), text);
            contentTop += dp(22);
        }
        DDDeck.Snapshot s = DDDeck.snapshot();
        switch (tab) {
            case 0: drawTest(c, new RectF(left, contentTop, right, bottom), s, w, h); break;
            case 1: drawReport(c, new RectF(left, contentTop, right, bottom), s); break;
            case 2: drawMotion(c, new RectF(left, contentTop, right, bottom), s); break;
            default: drawStatus(c, new RectF(left, contentTop, right, bottom)); break;
        }
        postInvalidateOnAnimation();
    }

    private void drawHeader(Canvas c, int w) {
        float m = dp(16);
        text.setTextAlign(Paint.Align.LEFT);
        text.setColor(COL_TEXT);
        text.setTextSize(dp(18));
        c.drawText("Controller Test", m, dp(26), text);
        text.setColor(COL_DIM);
        text.setTextSize(dp(11));
        text.setTypeface(Typeface.DEFAULT);
        c.drawText("Steam Deck Controller  28DE:1205", m, dp(41), text);
        text.setTypeface(Typeface.DEFAULT_BOLD);

        float cx = w - dp(28), cy = dp(26), r = dp(16);
        closeRect.set(cx - r, cy - r, cx + r, cy + r);
        fill.setColor(downHit == HIT_CLOSE ? COL_ACCENT : COL_FILL);
        c.drawCircle(cx, cy, r, fill);
        line.setColor(COL_LINE);
        c.drawCircle(cx, cy, r, line);
        line.setColor(COL_TEXT);
        float k = r * 0.38f;
        c.drawLine(cx - k, cy - k, cx + k, cy + k, line);
        c.drawLine(cx - k, cy + k, cx + k, cy - k, line);

        if (host.standalone()) {
            controlsRect.set(closeRect.left - dp(12) - dp(130), dp(10), closeRect.left - dp(12), dp(42));
            pill(c, controlsRect, "Controls: " + (host.controlsVisible() ? "ON" : "OFF"), downHit == HIT_CONTROLS, false);
        } else {
            controlsRect.setEmpty();
        }

        float x = m, top = dp(50), bottom = dp(78);
        text.setTextSize(dp(13));
        for (int i = 0; i < TABS.length; i++) {
            float width = text.measureText(TABS[i]) + dp(28);
            tabRect[i].set(x, top, x + width, bottom);
            pill(c, tabRect[i], TABS[i], downHit == HIT_TAB + i, i == tab);
            x += width + dp(8);
        }
    }

    private void pill(Canvas c, RectF r, String label, boolean pressed, boolean selected) {
        fill.setColor(selected ? COL_ACCENT : (pressed ? 0xFF2D4257 : COL_FILL));
        c.drawRoundRect(r, r.height() / 2f, r.height() / 2f, fill);
        line.setColor(selected ? COL_ACCENT : COL_LINE);
        c.drawRoundRect(r, r.height() / 2f, r.height() / 2f, line);
        text.setColor(selected ? Color.WHITE : COL_TEXT);
        text.setTextSize(dp(13));
        text.setTextAlign(Paint.Align.CENTER);
        c.drawText(label, r.centerX(), r.centerY() + dp(4.5f), text);
        text.setTextAlign(Paint.Align.LEFT);
    }

    // ----------------------------------------------------------------- tab: Test

    private float X(float n) { return ox + n * sc; }
    private float Y(float n) { return oy + n * sc; }

    private void drawTest(Canvas c, RectF area, DDDeck.Snapshot s, int w, int h) {
        // sisakan ruang untuk dua trackpad di layar (bawah tengah) agar tidak tertimpa
        float padSide = Math.min(h * 0.30f, w * 0.15f);
        area.bottom = Math.max(area.top + dp(100), area.bottom - padSide - dp(18));
        area.left += Math.min(dp(120), w * 0.12f);
        area.right -= Math.min(dp(120), w * 0.12f);
        sc = Math.min(area.width() / 100f, area.height() / 50f);
        ox = area.centerX() - 50 * sc;
        oy = area.centerY() - 25 * sc;

        // bumper & trigger
        rectBtn(c, 8, 3, 30, 8, (s.low & L_L1) != 0, COL_ACCENT, "L1");
        rectBtn(c, 70, 3, 92, 8, (s.low & L_R1) != 0, COL_ACCENT, "R1");
        bar(c, 1, 11, 5, 28, s.trig[0] / 32767f, "L2");
        bar(c, 95, 11, 99, 28, s.trig[1] / 32767f, "R2");
        // grip belakang
        rectBtn(c, 0, 31, 4, 37, (s.high & H_L4) != 0, 0xFFB388FF, "L4");
        rectBtn(c, 0, 39, 4, 45, (s.low & L_L5) != 0, 0xFFB388FF, "L5");
        rectBtn(c, 96, 31, 100, 37, (s.high & H_R4) != 0, 0xFFB388FF, "R4");
        rectBtn(c, 96, 39, 100, 45, (s.low & L_R5) != 0, 0xFFB388FF, "R5");
        // stik
        stick(c, 18, 20, 7, s.sticks[0], s.sticks[1], (s.low & L_L3) != 0);
        stick(c, 82, 38, 7, s.sticks[2], s.sticks[3], (s.low & L_R3) != 0);
        // d-pad
        dpad(c, 18, 38, 4.5f, s.low);
        // ABXY
        round(c, 82, 13, 3.4f, (s.low & L_Y) != 0, 0xFFFFC107, "Y");
        round(c, 82, 27, 3.4f, (s.low & L_A) != 0, 0xFF4CAF50, "A");
        round(c, 75, 20, 3.4f, (s.low & L_X) != 0, 0xFF2196F3, "X");
        round(c, 89, 20, 3.4f, (s.low & L_B) != 0, 0xFFE53935, "B");
        // sistem
        round(c, 44, 14, 2.6f, (s.low & L_VIEW) != 0, COL_ACCENT, "");
        round(c, 56, 14, 2.6f, (s.low & L_MENU) != 0, COL_ACCENT, "");
        round(c, 45, 25, 3.2f, (s.low & L_STEAM) != 0, COL_ACCENT, "S");
        round(c, 55, 25, 3.2f, (s.high & H_QAM) != 0, COL_ACCENT, "Q");
        // trackpad
        trackpad(c, 27, 31, 14, s.pads[0], s.pads[1], (s.low & L_LTOUCH) != 0, (s.low & L_LPAD) != 0, s.pressure[0]);
        trackpad(c, 59, 31, 14, s.pads[2], s.pads[3], (s.low & L_RTOUCH) != 0, (s.low & L_RPAD) != 0, s.pressure[1]);
    }

    private void setLit(boolean lit, int color) {
        fill.setColor(lit ? color : COL_FILL);
        line.setColor(lit ? Color.WHITE : COL_LINE);
    }

    private void label(Canvas c, String t, float cx, float cy, float size, boolean lit) {
        if (t == null || t.isEmpty()) return;
        text.setColor(lit ? Color.WHITE : COL_DIM);
        text.setTextSize(Math.max(dp(8), size));
        text.setTextAlign(Paint.Align.CENTER);
        c.drawText(t, cx, cy + text.getTextSize() * 0.35f, text);
        text.setTextAlign(Paint.Align.LEFT);
    }

    private void round(Canvas c, float cx, float cy, float r, boolean lit, int color, String t) {
        setLit(lit, color);
        c.drawCircle(X(cx), Y(cy), r * sc, fill);
        c.drawCircle(X(cx), Y(cy), r * sc, line);
        label(c, t, X(cx), Y(cy), r * sc * 0.95f, lit);
    }

    private void rectBtn(Canvas c, float l, float t, float r, float b, boolean lit, int color, String name) {
        setLit(lit, color);
        tmp.set(X(l), Y(t), X(r), Y(b));
        c.drawRoundRect(tmp, sc, sc, fill);
        c.drawRoundRect(tmp, sc, sc, line);
        label(c, name, tmp.centerX(), tmp.centerY(), Math.min(tmp.height() * 0.6f, sc * 2.4f), lit);
    }

    private void bar(Canvas c, float l, float t, float r, float b, float v, String name) {
        v = Math.max(0f, Math.min(1f, v));
        tmp.set(X(l), Y(t), X(r), Y(b));
        fill.setColor(COL_FILL);
        c.drawRoundRect(tmp, sc, sc, fill);
        fill.setColor(COL_ACCENT);
        float top = tmp.bottom - tmp.height() * v;
        RectF part = new RectF(tmp.left, top, tmp.right, tmp.bottom);
        c.drawRoundRect(part, sc, sc, fill);
        line.setColor(COL_LINE);
        c.drawRoundRect(tmp, sc, sc, line);
        label(c, name, tmp.centerX(), tmp.top - sc * 1.6f, sc * 2.2f, v > 0.02f);
    }

    private void stick(Canvas c, float cx, float cy, float r, int xs, int ys, boolean click) {
        fill.setColor(COL_FILL);
        c.drawCircle(X(cx), Y(cy), r * sc, fill);
        line.setColor(click ? Color.WHITE : COL_LINE);
        c.drawCircle(X(cx), Y(cy), r * sc, line);
        float dx = xs / 32767f, dy = -ys / 32767f;  // Y laporan: atas positif
        fill.setColor(click ? Color.WHITE : COL_ACCENT);
        c.drawCircle(X(cx) + dx * r * sc * 0.7f, Y(cy) + dy * r * sc * 0.7f, r * sc * 0.38f, fill);
    }

    private void dpad(Canvas c, float cx, float cy, float arm, int low) {
        float t = arm * 0.55f;
        arrowCell(c, cx, cy - arm, t, (low & L_UP) != 0);
        arrowCell(c, cx, cy + arm, t, (low & L_DOWN) != 0);
        arrowCell(c, cx - arm, cy, t, (low & L_LEFT) != 0);
        arrowCell(c, cx + arm, cy, t, (low & L_RIGHT) != 0);
    }

    private void arrowCell(Canvas c, float cx, float cy, float half, boolean lit) {
        setLit(lit, COL_ACCENT);
        tmp.set(X(cx - half), Y(cy - half), X(cx + half), Y(cy + half));
        c.drawRoundRect(tmp, sc * 0.6f, sc * 0.6f, fill);
        c.drawRoundRect(tmp, sc * 0.6f, sc * 0.6f, line);
    }

    private void trackpad(Canvas c, float l, float t, float size, int xs, int ys, boolean touch, boolean click, int pressure) {
        tmp.set(X(l), Y(t), X(l + size), Y(t + size));
        fill.setColor(click ? 0xFF2D5A80 : COL_FILL);
        c.drawRoundRect(tmp, sc * 1.6f, sc * 1.6f, fill);
        line.setColor(touch ? COL_ACCENT : COL_LINE);
        c.drawRoundRect(tmp, sc * 1.6f, sc * 1.6f, line);
        if (touch) {
            float px = tmp.centerX() + (xs / 32767f) * tmp.width() / 2f;
            float py = tmp.centerY() - (ys / 32767f) * tmp.height() / 2f;
            fill.setColor(Color.WHITE);
            c.drawCircle(px, py, sc * 1.1f, fill);
        }
        if (pressure > 0) {
            fill.setColor(COL_ACCENT);
            c.drawRect(tmp.left, tmp.bottom + sc * 0.6f, tmp.left + tmp.width() * (pressure / 32767f), tmp.bottom + sc * 1.2f, fill);
        }
    }

    // --------------------------------------------------------------- tab: Report

    private String hex(int v) { return String.format("%02X", v & 0xFF); }

    private String pressedNames(DDDeck.Snapshot s) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < LOW_MASKS.length; i++) if ((s.low & LOW_MASKS[i]) != 0) sb.append(sb.length() > 0 ? ", " : "").append(LOW_NAMES[i]);
        for (int i = 0; i < HIGH_MASKS.length; i++) if ((s.high & HIGH_MASKS[i]) != 0) sb.append(sb.length() > 0 ? ", " : "").append(HIGH_NAMES[i]);
        return sb.length() == 0 ? "(tidak ada)" : sb.toString();
    }

    private void drawReport(Canvas c, RectF a, DDDeck.Snapshot s) {
        byte[] report = DDDeck.buildReport(++packet);
        float cell = Math.min(dp(30), (a.width() * 0.52f) / 8f);
        float rowH = Math.min(dp(24), (a.height() - dp(4)) / 8f);
        text.setTextAlign(Paint.Align.CENTER);
        text.setTextSize(Math.min(dp(12), cell * 0.5f));
        for (int i = 0; i < 64; i++) {
            if (i >= 4 && i < 8) {  // nomor paket selalu berubah: jangan disorot
                prevReport[i] = report[i];
            }
            boolean changed = report[i] != prevReport[i];
            float x = a.left + (i % 8) * cell, y = a.top + (i / 8) * rowH;
            tmp.set(x + 1, y + 1, x + cell - 1, y + rowH - 1);
            fill.setColor(changed ? COL_ACCENT : (report[i] != 0 ? 0xFF2A3F54 : COL_FILL));
            c.drawRoundRect(tmp, dp(3), dp(3), fill);
            text.setColor(changed ? Color.WHITE : (report[i] != 0 ? COL_TEXT : COL_DIM));
            c.drawText(hex(report[i]), tmp.centerX(), tmp.centerY() + text.getTextSize() * 0.35f, text);
        }
        prevReport = report;

        float x = a.left + cell * 8 + dp(18);
        float y = a.top + dp(12);
        text.setTextAlign(Paint.Align.LEFT);
        text.setTextSize(dp(12));
        float lineH = dp(18);
        y = row(c, x, y, lineH, "Paket", String.valueOf(packet));
        y = row(c, x, y, lineH, "Tombol (rendah/tinggi)", String.format("%08X / %08X", s.low, s.high));
        y = row(c, x, y, lineH, "Stik kiri", String.format("%.2f , %.2f", s.sticks[0] / 32767f, s.sticks[1] / 32767f));
        y = row(c, x, y, lineH, "Stik kanan", String.format("%.2f , %.2f", s.sticks[2] / 32767f, s.sticks[3] / 32767f));
        y = row(c, x, y, lineH, "Trigger L2 / R2", String.format("%.2f / %.2f", s.trig[0] / 32767f, s.trig[1] / 32767f));
        y = row(c, x, y, lineH, "Trackpad kiri", String.format("%.2f , %.2f", s.pads[0] / 32767f, s.pads[1] / 32767f));
        y = row(c, x, y, lineH, "Trackpad kanan", String.format("%.2f , %.2f", s.pads[2] / 32767f, s.pads[3] / 32767f));
        y = row(c, x, y, lineH, "Accel (g)", String.format("%.2f %.2f %.2f", s.accel[0] / 16384f, s.accel[1] / 16384f, s.accel[2] / 16384f));
        y = row(c, x, y, lineH, "Gyro (deg/s)", String.format("%.0f %.0f %.0f", s.gyro[0] / 16.384f, s.gyro[1] / 16.384f, s.gyro[2] / 16.384f));
        text.setColor(COL_DIM);
        y += dp(4);
        wrapped(c, "Ditekan: " + pressedNames(s), x, y, a.right - x, lineH, COL_TEXT);
    }

    private float row(Canvas c, float x, float y, float lineH, String name, String value) {
        text.setColor(COL_DIM);
        c.drawText(name, x, y, text);
        text.setColor(COL_TEXT);
        text.setTextAlign(Paint.Align.RIGHT);
        c.drawText(value, getWidth() - dp(16), y, text);
        text.setTextAlign(Paint.Align.LEFT);
        return y + lineH;
    }

    private float wrapped(Canvas c, String t, float x, float y, float maxWidth, float lineH, int color) {
        text.setColor(color);
        int start = 0;
        while (start < t.length()) {
            int n = text.breakText(t, start, t.length(), true, maxWidth, null);
            if (n <= 0) break;
            int end = start + n;
            if (end < t.length()) {
                int space = t.lastIndexOf(' ', end);
                if (space > start) end = space + 1;
            }
            c.drawText(t.substring(start, end).trim(), x, y, text);
            y += lineH;
            start = end;
        }
        return y;
    }

    // --------------------------------------------------------------- tab: Motion

    private void drawMotion(Canvas c, RectF a, DDDeck.Snapshot s) {
        float[] now = {s.accel[0] / 16384f, s.accel[1] / 16384f, s.accel[2] / 16384f,
            s.gyro[0] / 16.384f, s.gyro[1] / 16.384f, s.gyro[2] / 16.384f};
        for (int i = 0; i < 6; i++) history[i][histPos] = now[i];
        histPos = (histPos + 1) % HIST;
        String[] names = {"Accel X (g)", "Accel Y (g)", "Accel Z (g)", "Gyro X (deg/s)", "Gyro Y (deg/s)", "Gyro Z (deg/s)"};
        float[] range = {2f, 2f, 2f, 2000f, 2000f, 2000f};
        float gap = dp(10), noteH = dp(20);
        float cw = (a.width() - gap) / 2f, ch = (a.height() - noteH - gap * 2) / 3f;
        for (int i = 0; i < 6; i++) {
            int col = i / 3, rowI = i % 3;
            float l = a.left + col * (cw + gap), t = a.top + rowI * (ch + gap);
            tmp.set(l, t, l + cw, t + ch);
            fill.setColor(COL_FILL);
            c.drawRoundRect(tmp, dp(8), dp(8), fill);
            line.setColor(COL_LINE);
            c.drawRoundRect(tmp, dp(8), dp(8), line);
            float mid = tmp.centerY();
            line.setColor(0xFF2D4257);
            c.drawLine(tmp.left, mid, tmp.right, mid, line);
            line.setColor(i < 3 ? 0xFF4CAF50 : COL_ACCENT);
            float step = tmp.width() / (HIST - 1);
            float px = 0, py = 0;
            for (int k = 0; k < HIST; k++) {
                float v = history[i][(histPos + k) % HIST] / range[i];
                v = Math.max(-1f, Math.min(1f, v));
                float x = tmp.left + k * step, y = mid - v * (tmp.height() / 2f - dp(3));
                if (k > 0) c.drawLine(px, py, x, y, line);
                px = x;
                py = y;
            }
            text.setTextSize(dp(11));
            text.setColor(COL_DIM);
            c.drawText(names[i], tmp.left + dp(8), tmp.top + dp(14), text);
            text.setColor(COL_TEXT);
            text.setTextAlign(Paint.Align.RIGHT);
            c.drawText(String.format(i < 3 ? "%.2f" : "%.0f", now[i]), tmp.right - dp(8), tmp.top + dp(14), text);
            text.setTextAlign(Paint.Align.LEFT);
        }
        text.setTextSize(dp(11));
        text.setColor(DDDeck.motionRunning() ? 0xFF4CAF50 : 0xFFFFC107);
        c.drawText(DDDeck.motionRunning() ? "Sensor HP aktif: miringkan atau putar perangkat."
            : "Sensor belum berjalan (tidak ada gyro/accelerometer atau belum dimulai).", a.left, a.bottom - dp(4), text);
    }

    // --------------------------------------------------------------- tab: Status

    private void drawStatus(Canvas c, RectF a) {
        float x = a.left, y = a.top + dp(12), lineH = dp(19);
        text.setTextSize(dp(12));
        text.setTextAlign(Paint.Align.LEFT);
        y = row(c, x, y, lineH, "Sesi memakai pad Deck", DDDeck.isSessionActive() ? "ya" : "tidak (mode mandiri)");
        y = row(c, x, y, lineH, "File state (mmap)", DDDeck.stateReady() ? "siap" : "belum dibuat");
        y = row(c, x, y, lineH, "Pohon sysfs /dev/hidraw16", DDDeck.sysfsReady() ? "siap" : "belum dibuat");
        if (!DDDeck.hasStatus()) {
            y += dp(6);
            wrapped(c, "Belum ada file status. Jalankan game dengan Steam Deck Pad = On di Setting Container/Shortcut, lalu buka layar ini dari sidebar (overlay) agar penghitung dari guest terisi.",
                x, y, a.width(), lineH, COL_TEXT);
            resetRect.setEmpty();
            return;
        }
        int sysfs = DDDeck.status(DDDeck.ST_SYSFS), open = DDDeck.status(DDDeck.ST_OPEN), close = DDDeck.status(DDDeck.ST_CLOSE),
            fset = DDDeck.status(DDDeck.ST_FSET), fget = DDDeck.status(DDDeck.ST_FGET), unh = DDDeck.status(DDDeck.ST_UNHANDLED),
            rep = DDDeck.status(DDDeck.ST_REPORTS), last = DDDeck.status(DDDeck.ST_LASTFEATURE);
        y = row(c, x, y, lineH, "Pencarian sysfs oleh guest", String.valueOf(sysfs));
        y = row(c, x, y, lineH, "/dev/hidraw16 dibuka / ditutup", open + " / " + close);
        y = row(c, x, y, lineH, "Feature report set / get", fset + " / " + fget);
        y = row(c, x, y, lineH, "Feature terakhir", last > 0 ? String.format("0x%02X", last) : "-");
        y = row(c, x, y, lineH, "ioctl hidraw tak dikenal", String.valueOf(unh));
        y = row(c, x, y, lineH, "Laporan terkirim (kelipatan 64)", String.valueOf(rep));
        int udev = DDDeck.udevState();  // DroidDeck-fix
        y = row(c, x, y, lineH, "Wine UDEV (hidraw terlihat)", udev < 0 ? "belum diketahui" : (udev == 1 ? "ada" : "tidak ada"));

        String verdict;
        int color;
        if (udev == 0) {
            verdict = DDDeck.isAlsoEvdev()
                ? "Runtime Wine ini dibangun tanpa UDEV, jadi hidraw tidak akan pernah ditemukan. Game membaca pad lewat evdev; fitur khas Deck (trackpad, grip, gyro) belum bisa dibaca game."
                : "Runtime Wine ini dibangun tanpa UDEV, jadi hidraw tidak akan pernah ditemukan, dan evdev di-Off (tidak dipaksa). Game tidak menerima input dari pad ini.";
            color = 0xFFFFC107;
        } else if (sysfs <= 0 && open <= 0) {
            verdict = "Belum ada proses guest yang mencari hidraw. Pastikan Steam Deck Pad = On dan klien Steam sudah berjalan. Jika sudah dan tetap 0, Wine tidak mengenumerasi hidraw (butuh libudev/patch winebus).";
            color = 0xFFFFC107;
        } else if (open <= 0) {
            verdict = "Enumerasi sampai ke pohon sysfs, tetapi /dev/hidraw16 belum dibuka. Lihat FAKE_EVDEV_LOG=1 untuk baris \"deck:\".";
            color = 0xFFFFC107;
        } else if (fset + fget <= 0) {
            verdict = "Perangkat dibuka, tetapi klien belum bertukar feature report dengan pad.";
            color = 0xFFFFC107;
        } else {
            verdict = "Klien Steam berbicara dengan pad Deck (feature report diterima): pad terdeteksi.";
            color = 0xFF4CAF50;
        }
        y += dp(6);
        y = wrapped(c, verdict, x, y, a.width(), lineH, color);
        text.setColor(COL_TEXT);

        float bw = dp(150), bh = dp(30);
        resetRect.set(a.left, Math.min(y + dp(6), a.bottom - bh), a.left + bw, Math.min(y + dp(6), a.bottom - bh) + bh);
        pill(c, resetRect, "Reset counters", downHit == HIT_RESET, false);
    }
}

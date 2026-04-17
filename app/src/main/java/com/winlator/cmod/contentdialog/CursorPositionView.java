package com.winlator.cmod.contentdialog;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import android.widget.ImageButton;
import android.widget.RelativeLayout;
import android.widget.SeekBar;

import androidx.core.content.ContextCompat;
import androidx.preference.PreferenceManager;

import com.winlator.cmod.R;

public class CursorPositionView extends RelativeLayout {

    // Drawing components
    private Paint crosshairPaint;
    private Paint circlePaint;
    private final float circleRadius = 30f;

    private float circleX, circleY;
    private float offsetRelativeX = 0.5f;
    private float offsetRelativeY = 0.5f;

    // Controls
    private SeekBar sbScale;
    private ImageButton btReset;

    // Listeners
    private OnOffsetChangedListener offsetListener;
    private OnScaleChangedListener scaleListener;
    private Runnable onResetCallback;

    private int lineColor = Color.BLACK;

    // Tinggi area kontrol (dalam dp, dikonversi ke px saat runtime)
    private int controlPanelHeightPx;

    public interface OnOffsetChangedListener {
        void onOffsetChanged(float relativeX, float relativeY);
    }

    public interface OnScaleChangedListener {
        void onScaleChanged(float scale);
    }

    public CursorPositionView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init(context);
    }

    private void init(Context context) {
        // Baca tema
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        boolean isDarkMode = prefs.getBoolean("dark_mode", false);
        lineColor = isDarkMode ? Color.WHITE : Color.BLACK;

        // Inisialisasi Paint
        crosshairPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        crosshairPaint.setColor(lineColor);
        crosshairPaint.setStyle(Paint.Style.STROKE);
        crosshairPaint.setStrokeWidth(2f);
        crosshairPaint.setPathEffect(new DashPathEffect(new float[]{10, 10}, 0));

        circlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        circlePaint.setColor(lineColor);
        circlePaint.setStyle(Paint.Style.STROKE);
        circlePaint.setStrokeWidth(4f);
        circlePaint.setPathEffect(new DashPathEffect(new float[]{8, 8}, 0));

        // Konversi tinggi kontrol dari dp ke px (perkiraan: SeekBar + margin)
        controlPanelHeightPx = dpToPx(context, 56); // 48dp tombol + margin

        // Membuat dan menambahkan kontrol ke layout
        createControls(context);

        // Agar onDraw() dipanggil
        setWillNotDraw(false);
    }

    private int dpToPx(Context context, int dp) {
        float density = context.getResources().getDisplayMetrics().density;
        return Math.round(dp * density);
    }

    private void createControls(Context context) {
        // FIX: Buat btReset DULU sebelum sbScale, agar rule START_OF bisa resolve dengan benar

        // 1. ImageButton Reset
        btReset = new ImageButton(context);
        btReset.setId(R.id.btResetInternal);
        btReset.setImageResource(R.drawable.icon_reset_24dp);
        btReset.setBackgroundColor(Color.TRANSPARENT);
        btReset.setColorFilter(
                ContextCompat.getColor(context, R.color.colorPrimary),
                PorterDuff.Mode.SRC_IN);
        RelativeLayout.LayoutParams btnParams = new RelativeLayout.LayoutParams(
                dpToPx(context, 48), dpToPx(context, 48));
        btnParams.addRule(RelativeLayout.ALIGN_PARENT_BOTTOM);
        btnParams.addRule(RelativeLayout.ALIGN_PARENT_END);
        btnParams.setMargins(0, 0, dpToPx(context, 16), dpToPx(context, 16));
        btReset.setLayoutParams(btnParams);
        addView(btReset); // ← tambah btReset ke layout DULU

        // 2. SeekBar — btReset.getId() sekarang sudah valid
        sbScale = new SeekBar(context);
        sbScale.setId(View.generateViewId());
        sbScale.setMax(100);
        sbScale.setProgress(33); // default scale 1.0
        RelativeLayout.LayoutParams seekParams = new RelativeLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, // FIX: bukan 0
                LayoutParams.WRAP_CONTENT);
        seekParams.addRule(RelativeLayout.ALIGN_PARENT_BOTTOM);
        seekParams.addRule(RelativeLayout.ALIGN_PARENT_START);
        seekParams.addRule(RelativeLayout.START_OF, btReset.getId()); // ← sekarang valid
        seekParams.setMargins(dpToPx(context, 16), 0, dpToPx(context, 8), dpToPx(context, 16));
        sbScale.setLayoutParams(seekParams);
        addView(sbScale);

        // Listeners
        btReset.setOnClickListener(v -> {
            resetToCenter();
            sbScale.setProgress(33);
            if (onResetCallback != null) onResetCallback.run();
        });

        sbScale.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (scaleListener != null && fromUser) {
                    float scale = scaleFromProgress(progress);
                    scaleListener.onScaleChanged(scale);
                }
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        updateCirclePositionFromOffset();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        // Area yang dapat digambar (di atas kontrol)
        int drawableHeight = getHeight() - controlPanelHeightPx;
        if (drawableHeight <= 0) return;

        int centerX = getWidth() / 2;
        int centerY = drawableHeight / 2;

        // Crosshair di tengah area gambar
        canvas.drawLine(centerX, 0, centerX, drawableHeight, crosshairPaint);
        canvas.drawLine(0, centerY, getWidth(), centerY, crosshairPaint);

        // Lingkaran
        canvas.drawCircle(circleX, circleY, circleRadius, circlePaint);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        float x = event.getX();
        float y = event.getY();

        int drawableHeight = getHeight() - controlPanelHeightPx;
        if (y > drawableHeight) {
            // Sentuhan di area kontrol, biarkan kontrol menanganinya
            return super.onTouchEvent(event);
        }

        switch (event.getAction()) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_MOVE:
                x = Math.max(circleRadius, Math.min(getWidth() - circleRadius, x));
                y = Math.max(circleRadius, Math.min(drawableHeight - circleRadius, y));

                circleX = x;
                circleY = y;
                updateOffsetFromPosition(drawableHeight);
                invalidate();

                if (offsetListener != null) {
                    offsetListener.onOffsetChanged(offsetRelativeX, offsetRelativeY);
                }
                return true;
        }
        return super.onTouchEvent(event);
    }

    private void updateOffsetFromPosition(int drawableHeight) {
        float w = getWidth() - 2 * circleRadius;
        float h = drawableHeight - 2 * circleRadius;
        offsetRelativeX = (circleX - circleRadius) / w;
        offsetRelativeY = (circleY - circleRadius) / h;
        offsetRelativeX = Math.max(0f, Math.min(1f, offsetRelativeX));
        offsetRelativeY = Math.max(0f, Math.min(1f, offsetRelativeY));
    }

    private void updateCirclePositionFromOffset() {
        int drawableHeight = getHeight() - controlPanelHeightPx;
        float w = getWidth() - 2 * circleRadius;
        float h = drawableHeight - 2 * circleRadius;
        circleX = circleRadius + offsetRelativeX * w;
        circleY = circleRadius + offsetRelativeY * h;
        invalidate();
    }

    public void setOffsetRelative(float relX, float relY) {
        offsetRelativeX = Math.max(0f, Math.min(1f, relX));
        offsetRelativeY = Math.max(0f, Math.min(1f, relY));
        updateCirclePositionFromOffset();
    }

    public void resetToCenter() {
        setOffsetRelative(0.5f, 0.5f);
    }

    public float getOffsetRelativeX() {
        return offsetRelativeX;
    }

    public float getOffsetRelativeY() {
        return offsetRelativeY;
    }

    public void setOnOffsetChangedListener(OnOffsetChangedListener listener) {
        this.offsetListener = listener;
    }

    public void setOnScaleChangedListener(OnScaleChangedListener listener) {
        this.scaleListener = listener;
    }

    public void setOnResetCallback(Runnable callback) {
        this.onResetCallback = callback;
    }

    public void setScaleProgress(int progress) {
        sbScale.setProgress(progress);
    }

    public int getScaleProgress() {
        return sbScale.getProgress();
    }

    private float scaleFromProgress(int progress) {
        float t = progress / 100f;
        return 0.5f + t * (4.0f - 0.5f); // rentang 0.5 .. 4.0
    }

    /**
     * Memperbarui warna berdasarkan tema
     */
    public void updateTheme(boolean isDarkMode) {
        lineColor = isDarkMode ? Color.WHITE : Color.BLACK;
        crosshairPaint.setColor(lineColor);
        circlePaint.setColor(lineColor);
        invalidate();
    }
}

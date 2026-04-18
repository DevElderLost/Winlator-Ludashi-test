package com.winlator.cmod.contentdialog;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import android.widget.RelativeLayout;
import android.widget.SeekBar;

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

    // Listeners
    private OnOffsetChangedListener offsetListener;
    private OnScaleChangedListener scaleListener;

    private int lineColor = Color.BLACK;

    // Tinggi area SeekBar di bagian bawah view (dp -> px)
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
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        boolean isDarkMode = prefs.getBoolean("dark_mode", false);
        lineColor = isDarkMode ? Color.WHITE : Color.BLACK;

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

        controlPanelHeightPx = dpToPx(context, 48);

        createControls(context);
        setWillNotDraw(false);
    }

    private int dpToPx(Context context, int dp) {
        float density = context.getResources().getDisplayMetrics().density;
        return Math.round(dp * density);
    }

    private void createControls(Context context) {
        sbScale = new SeekBar(context);
        sbScale.setId(View.generateViewId());
        sbScale.setMax(100);
        sbScale.setProgress(33);

        RelativeLayout.LayoutParams seekParams = new RelativeLayout.LayoutParams(
                LayoutParams.MATCH_PARENT,
                LayoutParams.WRAP_CONTENT);
        seekParams.addRule(RelativeLayout.ALIGN_PARENT_BOTTOM);
        seekParams.addRule(RelativeLayout.ALIGN_PARENT_START);
        seekParams.setMargins(dpToPx(context, 16), 0, dpToPx(context, 16), dpToPx(context, 8));
        sbScale.setLayoutParams(seekParams);
        addView(sbScale);

        sbScale.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (scaleListener != null && fromUser) {
                    scaleListener.onScaleChanged(scaleFromProgress(progress));
                }
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });
    }

    // -------------------------------------------------------------------------
    // Square enforcement
    // Ukuran square dihitung dari sisi terpendek, lalu di-pass ulang ke children
    // agar SeekBar (MATCH_PARENT) ikut terbatas pada ukuran square, bukan width asli.
    // -------------------------------------------------------------------------

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        // Ukur dulu dengan spec asli
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);

        // Ambil sisi terpendek sebagai ukuran square
        int size = Math.min(getMeasuredWidth(), getMeasuredHeight());

        // Buat MeasureSpec baru yang EXACTLY = size
        int squareSpec = MeasureSpec.makeMeasureSpec(size, MeasureSpec.EXACTLY);

        // Ukur ulang semua children dengan spec square agar MATCH_PARENT ikut size ini
        super.onMeasure(squareSpec, squareSpec);

        // Tetapkan dimensi final sebagai square
        setMeasuredDimension(size, size);
    }

    // -------------------------------------------------------------------------
    // Draw & Touch
    // -------------------------------------------------------------------------

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        updateCirclePositionFromOffset();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        int drawableHeight = getHeight() - controlPanelHeightPx;
        if (drawableHeight <= 0) return;

        int centerX = getWidth() / 2;
        int centerY = drawableHeight / 2;

        canvas.drawLine(centerX, 0, centerX, drawableHeight, crosshairPaint);
        canvas.drawLine(0, centerY, getWidth(), centerY, crosshairPaint);
        canvas.drawCircle(circleX, circleY, circleRadius, circlePaint);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        float x = event.getX();
        float y = event.getY();

        int drawableHeight = getHeight() - controlPanelHeightPx;
        if (y > drawableHeight) return super.onTouchEvent(event);

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

    // -------------------------------------------------------------------------
    // Offset helpers
    // -------------------------------------------------------------------------

    private void updateOffsetFromPosition(int drawableHeight) {
        float w = getWidth() - 2 * circleRadius;
        float h = drawableHeight - 2 * circleRadius;
        offsetRelativeX = Math.max(0f, Math.min(1f, (circleX - circleRadius) / w));
        offsetRelativeY = Math.max(0f, Math.min(1f, (circleY - circleRadius) / h));
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

    public float getOffsetRelativeX() { return offsetRelativeX; }
    public float getOffsetRelativeY() { return offsetRelativeY; }

    // -------------------------------------------------------------------------
    // Listeners & SeekBar API
    // -------------------------------------------------------------------------

    public void setOnOffsetChangedListener(OnOffsetChangedListener listener) {
        this.offsetListener = listener;
    }

    public void setOnScaleChangedListener(OnScaleChangedListener listener) {
        this.scaleListener = listener;
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

    public void updateTheme(boolean isDarkMode) {
        lineColor = isDarkMode ? Color.WHITE : Color.BLACK;
        crosshairPaint.setColor(lineColor);
        circlePaint.setColor(lineColor);
        invalidate();
    }
}

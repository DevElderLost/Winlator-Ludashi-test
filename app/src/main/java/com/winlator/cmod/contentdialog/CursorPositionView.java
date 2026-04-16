package com.winlator.cmod.contentdialog;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

public class CursorPositionView extends View {
    private final Paint crosshairPaint;
    private final Paint circlePaint;
    private final float circleRadius;

    private float circleX, circleY;
    private float offsetRelativeX = 0.5f;
    private float offsetRelativeY = 0.5f;
    private OnOffsetChangedListener listener;

    public interface OnOffsetChangedListener {
        void onOffsetChanged(float relativeX, float relativeY);
    }

    public CursorPositionView(Context context, AttributeSet attrs) {
        super(context, attrs);
        circleRadius = 30f;

        crosshairPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        crosshairPaint.setColor(Color.WHITE);
        crosshairPaint.setStyle(Paint.Style.STROKE);
        crosshairPaint.setStrokeWidth(2f);
        crosshairPaint.setPathEffect(new DashPathEffect(new float[]{10, 10}, 0));

        circlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        circlePaint.setColor(Color.GREEN);
        circlePaint.setStyle(Paint.Style.FILL);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        updateCirclePositionFromOffset();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int width = getWidth();
        int height = getHeight();

        // Crosshair putus-putus
        canvas.drawLine(width / 2f, 0, width / 2f, height, crosshairPaint);
        canvas.drawLine(0, height / 2f, width, height / 2f, crosshairPaint);

        // Lingkaran hijau
        canvas.drawCircle(circleX, circleY, circleRadius, circlePaint);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        float x = event.getX();
        float y = event.getY();

        switch (event.getAction()) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_MOVE:
                // Batasi agar lingkaran tidak keluar view
                x = Math.max(circleRadius, Math.min(getWidth() - circleRadius, x));
                y = Math.max(circleRadius, Math.min(getHeight() - circleRadius, y));

                circleX = x;
                circleY = y;
                updateOffsetFromPosition();
                invalidate();

                if (listener != null) {
                    listener.onOffsetChanged(offsetRelativeX, offsetRelativeY);
                }
                return true;
        }
        return super.onTouchEvent(event);
    }

    private void updateOffsetFromPosition() {
        float w = getWidth() - 2 * circleRadius;
        float h = getHeight() - 2 * circleRadius;
        offsetRelativeX = (circleX - circleRadius) / w;
        offsetRelativeY = (circleY - circleRadius) / h;
        offsetRelativeX = Math.max(0f, Math.min(1f, offsetRelativeX));
        offsetRelativeY = Math.max(0f, Math.min(1f, offsetRelativeY));
    }

    private void updateCirclePositionFromOffset() {
        float w = getWidth() - 2 * circleRadius;
        float h = getHeight() - 2 * circleRadius;
        circleX = circleRadius + offsetRelativeX * w;
        circleY = circleRadius + offsetRelativeY * h;
        invalidate();
    }

    public void setOffsetRelative(float relX, float relY) {
        offsetRelativeX = Math.max(0f, Math.min(1f, relX));
        offsetRelativeY = Math.max(0f, Math.min(1f, relY));
        updateCirclePositionFromOffset();
    }

    public void setOnOffsetChangedListener(OnOffsetChangedListener listener) {
        this.listener = listener;
    }

    public void resetToCenter() {
        setOffsetRelative(0.5f, 0.5f);
    }
}
package com.winlator.cmod.widget;

import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.os.BatteryManager;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.text.SpannableStringBuilder;
import android.text.style.ForegroundColorSpan;
import android.util.AttributeSet;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.winlator.cmod.R;
import com.winlator.cmod.core.CPUStatus;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.HashMap;
import java.util.Locale;

public class FrameRating extends LinearLayout implements Runnable {

    private static final String TAG = "FrameRating";

    // -------------------------------------------------------------------------
    // UI colors (initialized in constructor)
    // -------------------------------------------------------------------------
    private final int C_VALUE;
    private final int C_CPU;
    private final int C_RAM;
    private final int C_BAT;
    private final int C_TEMP;
    private final int C_GPU;
    private final int C_FPS_OK;
    private final int C_DIVISOR;

    // -------------------------------------------------------------------------
    // Views
    // -------------------------------------------------------------------------
    private final TextView tvRenderer;
    private final TextView tvGpuLoad;
    private final TextView tvHardwareStats;
    private final TextView tvWattsTemp;
    private final TextView tvFpsBig;
    private final FrameLayout graphContainer;
    private final View sep0;
    private final View sep1;
    private final View sep2;
    private final View sep3;
    private FrametimeGraphView graphView;

    // -------------------------------------------------------------------------
    // Enable flags
    // -------------------------------------------------------------------------
    private boolean enableFps       = true;
    private boolean enableGraph     = true;
    private boolean enableGpu       = true;
    private boolean enableCpuRam    = true;
    private boolean enableBattTemp  = true;
    private boolean enableRenderer  = true;

    // -------------------------------------------------------------------------
    // FPS / frame timing
    // -------------------------------------------------------------------------
    private long  lastTime       = 0;
    private long  lastGraphRedraw = 0;
    private long  lastFrameNano  = 0;
    private int   frameCount     = 0;
    private float lastFPS        = 0.0f;
    private float currentMs      = 0.0f;

    // -------------------------------------------------------------------------
    // Hardware stats (written by background thread, read on UI thread via post)
    // -------------------------------------------------------------------------
    private volatile int    cpuPercent   = -1;
    private volatile int    gpuLoad      = -1;
    private volatile float  batteryWatts = -1.0f;
    private volatile int    cpuTemp      = -1;
    private volatile String ramText      = "N/A";

    // -------------------------------------------------------------------------
    // Failure counters (stop polling after repeated failures)
    // -------------------------------------------------------------------------
    private boolean canReadGpu  = true;
    private boolean canReadCpu  = true;
    private boolean canReadBatt = true;
    private int gpuFailCount    = 0;
    private int cpuFailCount    = 0;
    private int battFailCount   = 0;

    // -------------------------------------------------------------------------
    // State
    // -------------------------------------------------------------------------
    private String  rendererName    = "OpenGL";
    private boolean isNativeActive  = false;
    private boolean isStatsRunning  = false;

    // -------------------------------------------------------------------------
    // Background stats thread
    // -------------------------------------------------------------------------
    private HandlerThread statsThread;
    private Handler       statsHandler;
    private Runnable      statsRunnable;

    // -------------------------------------------------------------------------
    // Dependencies
    // -------------------------------------------------------------------------
    private final Context        context;
    private final BatteryManager batteryManager;
    private final HashMap<?, ?>  graphicsDriverConfig;

    // =========================================================================
    // Constructors
    // =========================================================================

    public FrameRating(Context context, HashMap<?, ?> graphicsDriverConfig) {
        this(context, graphicsDriverConfig, null);
    }

    public FrameRating(Context context, HashMap<?, ?> graphicsDriverConfig, AttributeSet attrs) {
        this(context, graphicsDriverConfig, attrs, 0);
    }

    public FrameRating(Context context, HashMap<?, ?> graphicsDriverConfig, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);

        this.context             = context;
        this.graphicsDriverConfig = graphicsDriverConfig;
        this.batteryManager      = (BatteryManager) context.getSystemService(Context.BATTERY_SERVICE);

        // Layout setup
        setOrientation(LinearLayout.HORIZONTAL);
        setLayoutParams(new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT));
        setBackgroundColor(Color.TRANSPARENT);

        // Colors
        C_VALUE   = Color.parseColor("#FFFFFF");
        C_CPU     = Color.parseColor("#FFAB91");
        C_RAM     = Color.parseColor("#90CAF9");
        C_BAT     = Color.parseColor("#EF5350");
        C_TEMP    = Color.parseColor("#EF5350");
        C_GPU     = Color.parseColor("#E040FB");
        C_FPS_OK  = Color.parseColor("#76FF03");
        C_DIVISOR = Color.parseColor("#616161");

        // Inflate layout
        View view = LayoutInflater.from(context).inflate(R.layout.frame_rating, this, true);
        tvRenderer      = view.findViewById(R.id.TVRenderer);
        tvGpuLoad       = view.findViewById(R.id.TVGpuLoad);
        tvHardwareStats = view.findViewById(R.id.TVHardwareStats);
        tvWattsTemp     = view.findViewById(R.id.TVWattsTemp);
        tvFpsBig        = view.findViewById(R.id.TVFpsBig);
        graphContainer  = view.findViewById(R.id.FLGraphContainer);
        sep0 = view.findViewById(R.id.Sep0);
        sep1 = view.findViewById(R.id.Sep1);
        sep2 = view.findViewById(R.id.Sep2);
        sep3 = view.findViewById(R.id.Sep3);

        // Initial text
        if (tvRenderer != null) tvRenderer.setText(rendererName);
        if (tvFpsBig   != null) tvFpsBig.setText("0");

        // Frametime graph — inner class, accesses C_FPS_OK directly
        graphView = new FrametimeGraphView(context);
        if (graphContainer != null) graphContainer.addView(graphView);

        setupDragListener();
        initStatsThread();
    }

    // =========================================================================
    // Lifecycle
    // =========================================================================

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        bringToFront();
        setElevation(1000.0f);
        removeCallbacks(this);
        post(this);
        startStatsUpdate();
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        removeCallbacks(this);
        stopStatsUpdate();
    }

    // =========================================================================
    // Runnable — UI update (runs on main thread via post())
    // =========================================================================

    @Override
    public void run() {
        if (getVisibility() != View.VISIBLE) {
            postDelayed(this, 1000);
            return;
        }

        String na = "N/A";

        // GPU load
        if (enableGpu && tvGpuLoad != null) {
            SpannableStringBuilder sb = new SpannableStringBuilder();
            append(sb, "GPU ", C_GPU);
            append(sb, gpuLoad >= 0 ? gpuLoad + "%" : na, C_VALUE);
            tvGpuLoad.setText(sb);
        }

        // CPU + RAM
        if (enableCpuRam && tvHardwareStats != null) {
            SpannableStringBuilder sb = new SpannableStringBuilder();
            append(sb, "CPU ", C_CPU);
            append(sb, cpuPercent >= 0 ? cpuPercent + "% " : "N/A ", C_VALUE);
            appendDiv(sb);
            append(sb, "RAM ", C_RAM);
            append(sb, ramText, C_VALUE);
            tvHardwareStats.setText(sb);
        }

        // Battery + temperature
        if (enableBattTemp && tvWattsTemp != null) {
            SpannableStringBuilder sb = new SpannableStringBuilder();
            append(sb, "BAT ", C_BAT);
            String wattsStr = batteryWatts >= 0.0f
                    ? String.format(Locale.US, "%.1fW ", batteryWatts)
                    : "N/A ";
            append(sb, wattsStr, C_VALUE);
            appendDiv(sb);
            append(sb, "TMP ", C_TEMP);
            append(sb, cpuTemp >= 0 ? cpuTemp + "°C" : na, C_VALUE);
            tvWattsTemp.setText(sb);
        }

        // FPS
        if (enableFps && tvFpsBig != null) {
            tvFpsBig.setText(String.format(Locale.US, "%.0f", lastFPS));
            tvFpsBig.setTextColor(C_FPS_OK);
        }
    }

    // =========================================================================
    // Frame update — called from GL thread on every drawn frame
    // =========================================================================

    public void update() {
        if (getVisibility() != View.VISIBLE) return;

        if (lastTime == 0) lastTime = SystemClock.elapsedRealtime();

        long time = SystemClock.elapsedRealtime();
        if (time >= lastTime + 500) {
            lastFPS   = (frameCount * 1000.0f) / (time - lastTime);
            post(this);
            lastTime   = time;
            frameCount = 0;
        }
        frameCount++;

        // Frame timing for graph
        long nowNano = System.nanoTime();
        if (lastFrameNano == 0) lastFrameNano = nowNano;
        float ms = (nowNano - lastFrameNano) / 1_000_000.0f;
        lastFrameNano = nowNano;

        if (ms > 0.0f && ms < 500.0f) {
            currentMs = ms;
            if (enableGraph && time - lastGraphRedraw >= 50) {
                if (graphView != null) {
                    graphView.addFrame(ms);
                    graphView.postInvalidate();
                }
                lastGraphRedraw = time;
            }
        }
    }

    // =========================================================================
    // Background hardware stats thread
    // =========================================================================

    private void initStatsThread() {
        statsRunnable = () -> {
            calculateStats();
            if (statsHandler != null && isStatsRunning) {
                statsHandler.postDelayed(statsRunnable, 1000);
            }
        };
    }

    private void startStatsUpdate() {
        if (isStatsRunning) return;
        isStatsRunning = true;
        statsThread = new HandlerThread("HardwareStatsThread");
        statsThread.start();
        statsHandler = new Handler(statsThread.getLooper());
        statsHandler.post(statsRunnable);
    }

    private void stopStatsUpdate() {
        isStatsRunning = false;
        if (statsHandler != null) statsHandler.removeCallbacks(statsRunnable);
        if (statsThread != null) {
            statsThread.quitSafely();
            statsThread  = null;
            statsHandler = null;
        }
    }

    /**
     * Calculates all hardware stats on the background thread.
     * Uses volatile fields so UI thread reads are always fresh.
     */
    private void calculateStats() {
        // --- GPU load ---
        if (enableGpu && canReadGpu) {
            try {
                gpuLoad = calculateGPULoad();
                gpuFailCount = 0;
            } catch (Exception e) {
                gpuLoad = -1;
                gpuFailCount++;
                if (gpuFailCount > 5) {
                    canReadGpu = false;
                    Log.w(TAG, "OEM denied GPU read permission or file missing. Displaying N/A.");
                }
            }
        }

        // --- CPU % + RAM ---
        if (enableCpuRam) {
            if (canReadCpu) {
                try {
                    short[] clocks = CPUStatus.getCurrentClockSpeeds();
                    if (clocks == null || clocks.length == 0) throw new Exception("Clocks unavailable");

                    long sumCurrent = 0, sumMax = 0;
                    for (int i = 0; i < clocks.length; i++) {
                        sumCurrent += clocks[i];
                        sumMax     += CPUStatus.getMaxClockSpeed(i);
                    }
                    if (sumMax > 0) {
                        cpuPercent   = (int) ((sumCurrent * 100) / sumMax);
                        cpuFailCount = 0;
                    } else {
                        throw new Exception("Max clock is 0");
                    }
                } catch (Exception e) {
                    cpuPercent = -1;
                    cpuFailCount++;
                    if (cpuFailCount > 5) {
                        canReadCpu = false;
                        Log.w(TAG, "OEM denied CPU read permission. Displaying N/A.");
                    }
                }
            }

            // RAM usage %
            try {
                ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
                ((ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE)).getMemoryInfo(mi);
                long usedPct = (100 * (mi.totalMem - mi.availMem)) / mi.totalMem;
                ramText = usedPct + "%";
            } catch (Exception e) {
                ramText = "N/A";
            }
        }

        // --- Battery watts + temperature ---
        if (enableBattTemp && canReadBatt) {
            try {
                float amps = getBatteryCurrentAmps();
                if (amps < 0.0f) throw new Exception("No battery access");

                Intent battery = context.registerReceiver(
                        null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));

                int voltageMilliVolts = battery != null ? battery.getIntExtra("voltage", 0) : 0;
                batteryWatts = (voltageMilliVolts > 0 && amps > 0.0f)
                        ? (voltageMilliVolts / 1000.0f) * amps
                        : -1.0f;

                if (battery != null) {
                    int rawTemp = battery.getIntExtra("temperature", 0);
                    cpuTemp = rawTemp > 0 ? rawTemp / 10 : -1;
                }

                battFailCount = 0;
            } catch (Exception e) {
                batteryWatts = -1.0f;
                cpuTemp      = -1;
                battFailCount++;
                if (battFailCount > 5) {
                    canReadBatt = false;
                    Log.w(TAG, "OEM denied Battery/Temperature read permission. Displaying N/A.");
                }
            }
        }
    }

    // =========================================================================
    // GPU load reading (multiple sysfs paths for device compatibility)
    // =========================================================================

    private int calculateGPULoad() throws Exception {
        final String stripNonDigits = "[^0-9]";

        // Qualcomm Adreno — busy percentage
        int val = tryReadSysFsInt("/sys/class/kgsl/kgsl-3d0/gpu_busy_percentage", stripNonDigits);
        if (val >= 0) return val;

        // Qualcomm Adreno — devfreq gpu_load
        val = tryReadSysFsInt("/sys/class/kgsl/kgsl-3d0/devfreq/gpu_load", stripNonDigits);
        if (val >= 0) return val;

        // ARM Mali — utilisation
        val = tryReadSysFsInt("/sys/class/misc/mali0/device/utilisation", stripNonDigits);
        if (val >= 0) return val;

        // Qualcomm Adreno — gpubusy (ratio: active/total)
        val = tryReadGpuBusy("/sys/class/kgsl/kgsl-3d0/gpubusy");
        if (val >= 0) return val;

        throw new Exception("Failed to read GPU usage.");
    }

    /** Reads a sysfs file, strips non-digit characters, returns int or -1 on failure. */
    private int tryReadSysFsInt(String path, String stripPattern) {
        File f = new File(path);
        if (!f.exists() || !f.canRead()) return -1;
        try (BufferedReader br = new BufferedReader(new FileReader(f))) {
            String line = br.readLine();
            if (line != null) return Integer.parseInt(line.trim().replaceAll(stripPattern, ""));
        } catch (Exception ignored) {}
        return -1;
    }

    /** Reads gpubusy sysfs file formatted as "active total", returns percentage or -1. */
    private int tryReadGpuBusy(String path) {
        File f = new File(path);
        if (!f.exists() || !f.canRead()) return -1;
        try (BufferedReader br = new BufferedReader(new FileReader(f))) {
            String line = br.readLine();
            if (line != null) {
                String[] parts = line.trim().split("\\s+");
                if (parts.length >= 2) {
                    long active = Long.parseLong(parts[0]);
                    long total  = Long.parseLong(parts[1]);
                    if (total != 0) return (int) ((100 * active) / total);
                }
            }
        } catch (Exception ignored) {}
        return -1;
    }

    // =========================================================================
    // Battery current reading
    // =========================================================================

    private float getBatteryCurrentAmps() {
        long raw = 0;
        if (batteryManager != null) raw = batteryManager.getLongProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW);
        if (raw == 0 || raw == Long.MIN_VALUE) raw = readSysFs("/sys/class/power_supply/battery/current_now");
        if (raw == 0 || raw == Long.MIN_VALUE) raw = readSysFs("/sys/class/power_supply/bms/current_now");
        if (raw == 0 || raw == Long.MIN_VALUE) return -1.0f;

        raw = Math.abs(raw);
        // Values < 20000 are in mA; larger values are in µA
        return raw < 20_000 ? raw / 1_000.0f : raw / 1_000_000.0f;
    }

    private long readSysFs(String path) {
        File f = new File(path);
        if (!f.exists() || !f.canRead()) return 0;
        try (BufferedReader br = new BufferedReader(new FileReader(f))) {
            String line = br.readLine();
            if (line != null) return Long.parseLong(line.trim());
        } catch (Exception ignored) {}
        return 0;
    }

    // =========================================================================
    // SpannableString helpers
    // =========================================================================

    private void append(SpannableStringBuilder sb, String text, int color) {
        int start = sb.length();
        sb.append(text);
        sb.setSpan(new ForegroundColorSpan(color), start, sb.length(),
                SpannableStringBuilder.SPAN_EXCLUSIVE_EXCLUSIVE);
    }

    private void appendDiv(SpannableStringBuilder sb) {
        int start = sb.length();
        sb.append(" | ");
        sb.setSpan(new ForegroundColorSpan(C_DIVISOR), start, sb.length(),
                SpannableStringBuilder.SPAN_EXCLUSIVE_EXCLUSIVE);
    }

    // =========================================================================
    // Separator visibility logic
    // =========================================================================

    private void updateSeparators(boolean horizontal) {
        if (horizontal) {
            boolean vFps = tvFpsBig != null && tvFpsBig.getVisibility() == View.VISIBLE;
            boolean vRen = tvRenderer != null && tvRenderer.getVisibility() == View.VISIBLE;
            boolean vGpu = tvGpuLoad != null && tvGpuLoad.getVisibility() == View.VISIBLE;
            boolean vCpu = tvHardwareStats != null && tvHardwareStats.getVisibility() == View.VISIBLE;
            boolean vBat = tvWattsTemp != null && tvWattsTemp.getVisibility() == View.VISIBLE;

            setVisibility(sep0, vRen && (vGpu || vCpu || vBat || vFps));
            setVisibility(sep1, vGpu && (vCpu || vBat || vFps));
            setVisibility(sep2, vCpu && (vBat || vFps));
            setVisibility(sep3, vBat && vFps);
        } else {
            // Vertical layout — hide all separators
            setVisibility(sep0, false);
            setVisibility(sep1, false);
            setVisibility(sep2, false);
            setVisibility(sep3, false);
        }
    }

    private void setVisibility(View view, boolean visible) {
        if (view != null) view.setVisibility(visible ? View.VISIBLE : View.GONE);
    }

    // =========================================================================
    // Drag to reposition
    // =========================================================================

    private void setupDragListener() {
        final float[] lastTouch = new float[2];
        setOnTouchListener((v, event) -> {
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    lastTouch[0] = event.getRawX();
                    lastTouch[1] = event.getRawY();
                    break;
                case MotionEvent.ACTION_MOVE:
                    float dx = event.getRawX() - lastTouch[0];
                    float dy = event.getRawY() - lastTouch[1];
                    setX(getX() + dx);
                    setY(getY() + dy);
                    lastTouch[0] = event.getRawX();
                    lastTouch[1] = event.getRawY();
                    break;
            }
            return true;
        });
    }

    // =========================================================================
    // Renderer name parsing
    // =========================================================================

    private void updateRendererText() {
        if (tvRenderer == null) return;
        tvRenderer.setText((isNativeActive ? "+" : "") + rendererName);
        tvRenderer.setVisibility(enableRenderer ? View.VISIBLE : View.GONE);
        updateSeparators(getOrientation() == LinearLayout.HORIZONTAL);
    }

    // =========================================================================
    // Public API
    // =========================================================================

    /**
     * Parses the renderer string and abbreviates it to a short label.
     * Matches the decompiled logic: DXVK > Turnip > VirGL > llvmpipe > fallback.
     */
    public void setRenderer(String renderer) {
        if (renderer == null) return;
        if (renderer.contains("DXVK"))         rendererName = "DXVK";
        else if (renderer.contains("Turnip"))   rendererName = "Turnip";
        else if (renderer.contains("VirGL"))    rendererName = "VirGL";
        else if (renderer.contains("llvmpipe")) rendererName = "Software";
        else                                    rendererName = renderer.replaceAll(".*Wrapper ", "").trim();
        updateRendererText();
    }

    /** Called by GLRenderer to show/hide the Direct Rendering+ indicator ('+' prefix). */
    public void setIsNative(boolean active) {
        if (isNativeActive != active) {
            isNativeActive = active;
            post(this::updateRendererText);
        }
    }

    public void reset() {
        setRenderer("OpenGL");
        frameCount = 0;
        lastTime   = 0;
    }

    /** Externally override GPU load (e.g. from a driver that exposes its own counter). */
    public void setGpuLoad(int load) {
        this.gpuLoad = load;
    }

    public void setGpuName(String gpuName) {
        // Reserved for external GPU name display; currently renderer label is used instead.
    }

    /**
     * Toggle individual HUD elements.
     *
     * @param element 0=FPS, 1=Renderer, 2=GPU, 3=CPU/RAM, 4=Battery/Temp, 5=Graph
     * @param visible true to show, false to hide
     */
    public void toggleElement(int element, boolean visible) {
        int v = visible ? View.VISIBLE : View.GONE;
        switch (element) {
            case 0:
                enableFps = visible;
                if (tvFpsBig != null) tvFpsBig.setVisibility(v);
                break;
            case 1:
                enableRenderer = visible;
                if (tvRenderer != null) tvRenderer.setVisibility(v);
                break;
            case 2:
                enableGpu = visible;
                if (tvGpuLoad != null) tvGpuLoad.setVisibility(v);
                break;
            case 3:
                enableCpuRam = visible;
                if (tvHardwareStats != null) tvHardwareStats.setVisibility(v);
                break;
            case 4:
                enableBattTemp = visible;
                if (tvWattsTemp != null) tvWattsTemp.setVisibility(v);
                break;
            case 5:
                enableGraph = visible;
                if (graphContainer != null) graphContainer.setVisibility(v);
                break;
            default:
                break;
        }
        updateSeparators(getOrientation() == LinearLayout.HORIZONTAL);
    }

    public void setLayoutOrientation(boolean horizontal) {
        setOrientation(horizontal ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
        updateSeparators(horizontal);
        requestLayout();
    }

    public void setHudScale(float scale) {
        setScaleX(scale);
        setScaleY(scale);
    }

    public void setHudAlpha(float alpha) {
        setAlpha(alpha);
    }

    // =========================================================================
    // Inner class — FrametimeGraphView
    // =========================================================================

    /**
     * A lightweight graph view that renders frame-time history as a line chart.
     *
     * <p>The X-axis represents the last {@value #MAX_SAMPLES} frames in
     * chronological order; the Y-axis represents frame time in milliseconds,
     * capped at {@value #MAX_MS} ms (~25 FPS ceiling). Frame times above
     * {@value #CAP_MS} ms (~15 FPS) are clamped before being stored so that
     * extreme spikes don't distort the graph scale.</p>
     */
    private class FrametimeGraphView extends View {

        private static final int   MAX_SAMPLES  = 60;
        private static final float MAX_MS       = 40.0f;  // graph Y ceiling (~25 FPS)
        private static final float CAP_MS       = 66.6f;  // hard cap on stored values (~15 FPS)
        private static final float STROKE_WIDTH = 1.5f;

        // Ring-buffer that holds the last MAX_SAMPLES frame times (ms)
        private final float[] history      = new float[MAX_SAMPLES];
        private int           historyIndex = 0;  // next write position
        private int           historySize  = 0;  // number of valid samples (0..MAX_SAMPLES)

        private final Paint paintLine = new Paint();
        private final Path  path      = new Path();

        FrametimeGraphView(Context context) {
            super(context);
            // C_FPS_OK is accessed directly from the outer FrameRating instance
            paintLine.setColor(C_FPS_OK);
            paintLine.setStrokeWidth(STROKE_WIDTH);
            paintLine.setStyle(Paint.Style.STROKE);
            paintLine.setAntiAlias(true);
            setBackgroundColor(0); // transparent
        }

        /**
         * Records a new frame-time sample.
         * Values above {@value #CAP_MS} ms are clamped so spikes don't distort the scale.
         *
         * @param ms frame time in milliseconds
         */
        public void addFrame(float ms) {
            history[historyIndex] = Math.min(ms, CAP_MS);
            historyIndex = (historyIndex + 1) % MAX_SAMPLES;
            if (historySize < MAX_SAMPLES) historySize++;
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            if (historySize < 2) return;

            float viewHeight = getHeight();
            float xStep      = (float) getWidth() / (MAX_SAMPLES - 1);

            // Oldest sample index in the ring buffer
            int start = ((historyIndex - historySize) + MAX_SAMPLES) % MAX_SAMPLES;

            path.reset();
            path.moveTo(0.0f, yForMs(history[start], viewHeight));

            for (int i = 1; i < historySize; i++) {
                float x  = i * xStep;
                float ms = history[(start + i) % MAX_SAMPLES];
                path.lineTo(x, yForMs(ms, viewHeight));
            }

            canvas.drawPath(path, paintLine);
        }

        /**
         * Converts a frame-time value to a Y coordinate.
         * Higher ms → closer to the top (lower Y value).
         */
        private float yForMs(float ms, float viewHeight) {
            return Math.max(0.0f, viewHeight - (ms / MAX_MS) * viewHeight);
        }
    }
}

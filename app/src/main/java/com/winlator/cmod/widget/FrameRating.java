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
    // TVHardwareStats dan TVWattsTemp tidak lagi digunakan (dihapus dari frame_rating.xml)
    private final TextView tvCpu;
    private final TextView tvRam;
    private final TextView tvBatt;
    private final TextView tvTemp;
    private final TextView tvFpsBig;
    private final FrameLayout graphContainer;
    private final View sep0;
    private final View sep1;
    private final View sep2;
    private final View sep3;
    private final View sep4;
    private final View sep5;
    private FrametimeGraphView graphView;

    // -------------------------------------------------------------------------
    // Enable flags
    // -------------------------------------------------------------------------
    private boolean enableFps       = true;
    private boolean enableGraph     = true;
    private boolean enableGpu       = true;
    private boolean enableCpu     = true;
    private boolean enableRam     = true;
    private boolean enableBatt    = true;
    private boolean enableTemp    = true;
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
        tvRenderer     = view.findViewById(R.id.TVRenderer);
        tvGpuLoad      = view.findViewById(R.id.TVGpuLoad);
        tvCpu          = view.findViewById(R.id.TVCpu);
        tvRam          = view.findViewById(R.id.TVRam);
        tvBatt         = view.findViewById(R.id.TVBatt);
        tvTemp         = view.findViewById(R.id.TVTemp);
        tvFpsBig       = view.findViewById(R.id.TVFpsBig);
        graphContainer = view.findViewById(R.id.FLGraphContainer);
        sep0 = view.findViewById(R.id.Sep0);
        sep1 = view.findViewById(R.id.Sep1);
        sep2 = view.findViewById(R.id.Sep2);
        sep3 = view.findViewById(R.id.Sep3);
        sep4 = view.findViewById(R.id.Sep4);
        sep5 = view.findViewById(R.id.Sep5);

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
        // Reset failure counters agar GPU/CPU/battery polling retry saat
        // view di-attach ulang (misalnya setelah activity recreate).
        canReadGpu  = true;
        canReadCpu  = true;
        canReadBatt = true;
        gpuFailCount  = 0;
        cpuFailCount  = 0;
        battFailCount = 0;
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
        // CPU
        if (tvCpu != null) {
            if (enableCpu) {
                SpannableStringBuilder sb = new SpannableStringBuilder();
                append(sb, "CPU ", C_CPU);
                append(sb, cpuPercent >= 0 ? cpuPercent + "%" : "N/A", C_VALUE);
                tvCpu.setText(sb);
            }
            tvCpu.setVisibility(enableCpu ? View.VISIBLE : View.GONE);
        }

        // RAM
        if (tvRam != null) {
            if (enableRam) {
                SpannableStringBuilder sb = new SpannableStringBuilder();
                append(sb, "RAM ", C_RAM);
                append(sb, ramText, C_VALUE);
                tvRam.setText(sb);
            }
            tvRam.setVisibility(enableRam ? View.VISIBLE : View.GONE);
        }

        // Battery
        if (tvBatt != null) {
            if (enableBatt) {
                SpannableStringBuilder sb = new SpannableStringBuilder();
                append(sb, "BAT ", C_BAT);
                String wattsStr = batteryWatts >= 0.0f
                        ? String.format(Locale.US, "%.1fW", batteryWatts)
                        : "N/A";
                append(sb, wattsStr, C_VALUE);
                tvBatt.setText(sb);
            }
            tvBatt.setVisibility(enableBatt ? View.VISIBLE : View.GONE);
        }

        // Temperature
        if (tvTemp != null) {
            if (enableTemp) {
                SpannableStringBuilder sb = new SpannableStringBuilder();
                append(sb, "TMP ", C_TEMP);
                append(sb, cpuTemp >= 0 ? cpuTemp + "°C" : na, C_VALUE);
                tvTemp.setText(sb);
            }
            tvTemp.setVisibility(enableTemp ? View.VISIBLE : View.GONE);
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
                int newLoad = calculateGPULoad();
                if (gpuLoad < 0 && newLoad >= 0) {
                    // Pertama kali berhasil baca — log path yang sukses untuk debugging
                    Log.d(TAG, "GPU load reading succeeded, value=" + newLoad + "%");
                }
                gpuLoad = newLoad;
                gpuFailCount = 0;
            } catch (Exception e) {
                gpuLoad = -1;
                gpuFailCount++;
                if (gpuFailCount == 1) {
                    // Log sekali saja saat pertama gagal, bukan setiap detik
                    Log.w(TAG, "GPU sysfs read failed (attempt " + gpuFailCount + "): " + e.getMessage());
                }
                if (gpuFailCount > 5) {
                    canReadGpu = false;
                    Log.w(TAG, "GPU read permanently disabled after 5 failures. "
                            + "Device may require root/SELinux permissive for sysfs GPU access.");
                }
            }
        }

        // --- CPU % + RAM ---
        if (enableCpu || enableRam) {
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
        if ((enableBatt || enableTemp) && canReadBatt) {
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
        int val;

        // ── Qualcomm Adreno ──────────────────────────────────────────────────
        // gpu_busy_percentage: format "42 %" — strip non-digit sudah handle
        val = tryReadSysFsInt("/sys/class/kgsl/kgsl-3d0/gpu_busy_percentage", stripNonDigits);
        if (val >= 0) return val;

        // devfreq gpu_load (0-100, beberapa OEM Snapdragon)
        val = tryReadSysFsInt("/sys/class/kgsl/kgsl-3d0/devfreq/gpu_load", stripNonDigits);
        if (val >= 0) return val;

        // gpubusy: format "active total" -> hitung persentase
        val = tryReadGpuBusy("/sys/class/kgsl/kgsl-3d0/gpubusy");
        if (val >= 0) return val;

        // kernel/gpu/gpu_busy — path alternatif ROM kustom Adreno
        val = tryReadSysFsInt("/sys/kernel/gpu/gpu_busy", stripNonDigits);
        if (val >= 0) return val;

        // ── ARM Mali ─────────────────────────────────────────────────────────
        // Mali Midgard/Bifrost/Valhall — /sys/class/misc/mali0
        val = tryReadSysFsInt("/sys/class/misc/mali0/device/utilisation", stripNonDigits);
        if (val >= 0) return val;

        // Beberapa OEM Mali: tanpa 's' di ujung
        val = tryReadSysFsInt("/sys/class/misc/mali0/device/utilization", stripNonDigits);
        if (val >= 0) return val;

        // Mali via platform device (Exynos, MediaTek Mali)
        val = tryReadGlobSysFsInt("/sys/devices/platform/", "gpu/misc/mali0/device/utilisation", stripNonDigits);
        if (val >= 0) return val;

        // ── MediaTek GPU (MFG) ───────────────────────────────────────────────
        val = tryReadSysFsInt("/sys/kernel/debug/ged/hal/gpu_utilization", stripNonDigits);
        if (val >= 0) return val;

        throw new Exception("Failed to read GPU usage from all known sysfs paths.");
    }

    /** Reads a sysfs file, strips non-digit characters, returns int or -1 on failure. */
    /**
     * Membaca sysfs file integer.
     * BUG FIX: setelah strip non-digit, string bisa kosong (misal nilai "0 %")
     * yang menyebabkan parseInt("") throw NumberFormatException dan return -1
     * meski GPU sebenarnya idle (0%). Cek isEmpty() dulu lalu default ke "0".
     */
    private int tryReadSysFsInt(String path, String stripPattern) {
        File f = new File(path);
        if (!f.exists() || !f.canRead()) return -1;
        try (BufferedReader br = new BufferedReader(new FileReader(f))) {
            String line = br.readLine();
            if (line != null) {
                String digits = line.trim().replaceAll(stripPattern, "");
                if (digits.isEmpty()) return 0; // kosong setelah strip = nilai "0 %"
                int v = Integer.parseInt(digits);
                return Math.max(0, Math.min(100, v)); // clamp 0-100
            }
        } catch (Exception ignored) {}
        return -1;
    }

    /**
     * Membaca gpubusy "active total" dan hitung persentase.
     * BUG FIX: active==0 (GPU idle) sebelumnya mengembalikan 0 yang valid,
     * tapi total==0 return -1. Ditambahkan pengecekan total<active untuk
     * menghindari persentase > 100 pada beberapa OEM.
     */
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
                    if (total > 0) return (int) Math.min(100, (100 * active) / total);
                    if (active == 0) return 0; // GPU idle, total belum diupdate
                }
            }
        } catch (Exception ignored) {}
        return -1;
    }

    /**
     * Glob sederhana: scan direktori parent untuk subdir yang mengandung suffix path tertentu.
     * Digunakan untuk Mali via platform device yang nama subdirnya bervariasi per OEM
     * (mis. "/sys/devices/platform/13000000.gpu/misc/mali0/...").
     */
    private int tryReadGlobSysFsInt(String parentDir, String suffix, String stripPattern) {
        try {
            File parent = new File(parentDir);
            if (!parent.exists() || !parent.isDirectory()) return -1;
            File[] children = parent.listFiles();
            if (children == null) return -1;
            for (File child : children) {
                if (!child.isDirectory()) continue;
                File target = new File(child, suffix);
                int v = tryReadSysFsInt(target.getAbsolutePath(), stripPattern);
                if (v >= 0) return v;
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
            boolean vFps  = tvFpsBig  != null && tvFpsBig.getVisibility()  == View.VISIBLE;
            boolean vRen  = tvRenderer != null && tvRenderer.getVisibility() == View.VISIBLE;
            boolean vGpu  = tvGpuLoad  != null && tvGpuLoad.getVisibility()  == View.VISIBLE;
            boolean vCpu  = tvCpu      != null && tvCpu.getVisibility()       == View.VISIBLE;
            boolean vRam  = tvRam      != null && tvRam.getVisibility()       == View.VISIBLE;
            boolean vBatt = tvBatt     != null && tvBatt.getVisibility()      == View.VISIBLE;
            boolean vTemp = tvTemp     != null && tvTemp.getVisibility()      == View.VISIBLE;
            // sep0: Renderer | GPU | CPU | RAM | BAT | TMP | FPS
            // sep1: GPU | CPU | ...
            // sep2: CPU | RAM | ...
            // sep3: RAM | BAT | ...
            // sep4: BAT | TMP | ...
            // sep5: TMP | FPS | Graph
            setVisibility(sep0, vRen  && (vGpu || vCpu || vRam || vBatt || vTemp || vFps));
            setVisibility(sep1, vGpu  && (vCpu || vRam || vBatt || vTemp || vFps));
            setVisibility(sep2, vCpu  && (vRam || vBatt || vTemp || vFps));
            setVisibility(sep3, vRam  && (vBatt || vTemp || vFps));
            setVisibility(sep4, vBatt && (vTemp || vFps));
            setVisibility(sep5, vTemp && vFps);
        } else {
            // Vertical layout — sembunyikan semua separator
            setVisibility(sep0, false);
            setVisibility(sep1, false);
            setVisibility(sep2, false);
            setVisibility(sep3, false);
            setVisibility(sep4, false);
            setVisibility(sep5, false);
        }
    }

    private void setVisibility(View view, boolean visible) {
        if (view != null) view.setVisibility(visible ? View.VISIBLE : View.GONE);
    }

    // =========================================================================
    // Drag to reposition
    // =========================================================================

    // -------------------------------------------------------------------------
    // Double-tap state (digunakan di setupDragListener)
    // -------------------------------------------------------------------------
    private long  lastTapTime    = 0;
    private float lastTapX       = 0;
    private float lastTapY       = 0;
    /** Threshold gerakan (px) agar tap tidak dianggap drag. */
    private static final float TAP_SLOP_PX      = 10f;
    /** Interval maksimum antar dua tap agar dianggap double-tap (ms). */
    private static final long  DOUBLE_TAP_MS    = 300L;

    private void setupDragListener() {
        final float[] lastTouch  = new float[2];
        final boolean[] isDragging = {false};

        setOnTouchListener((v, event) -> {
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    lastTouch[0] = event.getRawX();
                    lastTouch[1] = event.getRawY();
                    isDragging[0] = false;
                    break;

                case MotionEvent.ACTION_MOVE:
                    float dx = event.getRawX() - lastTouch[0];
                    float dy = event.getRawY() - lastTouch[1];
                    // Tandai sebagai drag jika melewati slop
                    if (!isDragging[0] &&
                            (Math.abs(dx) > TAP_SLOP_PX || Math.abs(dy) > TAP_SLOP_PX)) {
                        isDragging[0] = true;
                    }
                    if (isDragging[0]) {
                        setX(getX() + dx);
                        setY(getY() + dy);
                        lastTouch[0] = event.getRawX();
                        lastTouch[1] = event.getRawY();
                    }
                    break;

                case MotionEvent.ACTION_UP:
                    if (!isDragging[0]) {
                        // Ini adalah tap (bukan drag) — cek double-tap
                        long now = SystemClock.uptimeMillis();
                        float upX = event.getRawX();
                        float upY = event.getRawY();
                        boolean withinTime = (now - lastTapTime) <= DOUBLE_TAP_MS;
                        boolean withinPos  = Math.abs(upX - lastTapX) <= TAP_SLOP_PX * 3
                                          && Math.abs(upY - lastTapY) <= TAP_SLOP_PX * 3;
                        if (withinTime && withinPos) {
                            // Double-tap terdeteksi — toggle orientasi horizontal/vertikal
                            boolean nowHorizontal = getOrientation() == LinearLayout.HORIZONTAL;
                            setLayoutOrientation(!nowHorizontal);
                            lastTapTime = 0; // reset agar triple-tap tidak langsung toggle lagi
                        } else {
                            lastTapTime = now;
                            lastTapX    = upX;
                            lastTapY    = upY;
                        }
                    }
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
                enableCpu = visible;
                if (tvCpu != null) tvCpu.setVisibility(v);
                break;
            case 4:
                enableRam = visible;
                if (tvRam != null) tvRam.setVisibility(v);
                break;
            case 5:
                enableBatt = visible;
                if (tvBatt != null) tvBatt.setVisibility(v);
                break;
            case 6:
                enableTemp = visible;
                if (tvTemp != null) tvTemp.setVisibility(v);
                break;
            case 7:
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

        // Sesuaikan ukuran graphContainer agar proporsional di kedua orientasi.
        // Horizontal: graph lebar (120dp) x tinggi penuh (MATCH_PARENT)
        // Vertikal  : graph lebar penuh (MATCH_PARENT) x tinggi tetap (40dp)
        if (graphContainer != null) {
            float dp = getContext().getResources().getDisplayMetrics().density;
            LinearLayout.LayoutParams lp;
            if (horizontal) {
                lp = new LinearLayout.LayoutParams(
                        (int)(120 * dp), LinearLayout.LayoutParams.MATCH_PARENT);
            } else {
                lp = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, (int)(40 * dp));
            }
            graphContainer.setLayoutParams(lp);
        }

        // Visual feedback singkat: scale bounce kecil
        animate().scaleX(0.95f).scaleY(0.95f).setDuration(80).withEndAction(() ->
                animate().scaleX(1f).scaleY(1f).setDuration(80).start()
        ).start();

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

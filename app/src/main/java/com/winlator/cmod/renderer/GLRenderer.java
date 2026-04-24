package com.winlator.cmod.renderer;

import android.content.Context;
import android.content.SharedPreferences;
import androidx.preference.PreferenceManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.util.Log;
import android.widget.Toast;

import com.winlator.cmod.R;
import com.winlator.cmod.XrActivity;
import com.winlator.cmod.core.Callback;
import com.winlator.cmod.math.Mathf;
import com.winlator.cmod.math.XForm;
import com.winlator.cmod.renderer.material.CursorMaterial;
import com.winlator.cmod.renderer.material.ScreenMaterial;
import com.winlator.cmod.renderer.material.ShaderMaterial;
import com.winlator.cmod.renderer.material.WindowMaterial;
import com.winlator.cmod.widget.FrameRating;
import com.winlator.cmod.widget.XServerView;
import com.winlator.cmod.xserver.Atom;
import com.winlator.cmod.xserver.Bitmask;
import com.winlator.cmod.xserver.Cursor;
import com.winlator.cmod.xserver.Drawable;
import com.winlator.cmod.renderer.FullscreenTransformation;
import com.winlator.cmod.xserver.Pointer;
import com.winlator.cmod.xserver.Property;
import com.winlator.cmod.xserver.Window;
import com.winlator.cmod.xserver.WindowAttributes;
import com.winlator.cmod.xserver.WindowManager;
import com.winlator.cmod.xserver.XLock;
import com.winlator.cmod.xserver.XServer;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.concurrent.LinkedBlockingQueue;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

public class GLRenderer implements GLSurfaceView.Renderer, WindowManager.OnWindowModificationListener, Pointer.OnPointerMotionListener {
    public final XServerView xServerView;
    private final XServer xServer;
    public final VertexAttribute quadVertices = new VertexAttribute("position", 2);
    private final float[] tmpXForm1 = XForm.getInstance();
    private final float[] tmpXForm2 = XForm.getInstance();
    private final CursorMaterial cursorMaterial = new CursorMaterial();
    private final WindowMaterial windowMaterial = new WindowMaterial();
    public final ViewTransformation viewTransformation = new ViewTransformation();
    private final Drawable rootCursorDrawable;
    private final ArrayList<RenderableWindow> renderableWindows = new ArrayList<>();
    private final FullscreenTransformation tmpFullscreenTransformation = new FullscreenTransformation(null);
    private String forceFullscreenWMClass = null;
    private boolean fullscreen = false;
    private boolean toggleFullscreen = false;
    public boolean viewportNeedsUpdate = true;
    private boolean cursorVisible = true;
    private boolean screenOffsetYRelativeToCursor = false;
    private String[] unviewableWMClasses = null;
    private float magnifierZoom = 1.0f;
    private boolean magnifierEnabled = true;
    public int surfaceWidth;
    public int surfaceHeight;
    private final EffectComposer effectComposer;

    // --- CPU Saver ---
    private boolean cpuSaverMode = false;
    private FrameRating frameRating;

    // --- Screenshot subsystem ---
    // Antrian permintaan screenshot yang dieksekusi di GL thread saat onDrawFrame,
    // sehingga tidak pernah menyebabkan stall di tengah frame render.
    private static final int SCREENSHOT_MAX_SIZE = 256; // max sisi terpanjang thumbnail (px)
    private final LinkedBlockingQueue<ScreenshotRequest> screenshotQueue = new LinkedBlockingQueue<>();
    // RenderTarget dan ScreenMaterial di-reuse antar panggilan — tidak ada alokasi per permintaan.
    // Ukuran disimpan manual karena RenderTarget tidak menyediakan getWidth()/getHeight().
    // Cleanup: glDeleteFramebuffers manual + Texture.destroy() untuk texture-nya.
    private RenderTarget screenshotRenderTarget = null;
    private int screenshotRenderTargetW = 0;
    private int screenshotRenderTargetH = 0;
    private ScreenMaterial screenshotMaterial = null;

// Dalam class GLRenderer
private int cursorHotspotOffsetX = 0;
private int cursorHotspotOffsetY = 0;

/** Kunci SharedPreferences untuk menyimpan offset hotspot kursor. */
public static final String PREF_CURSOR_HOTSPOT_X = "cursor_hotspot_offset_x";
public static final String PREF_CURSOR_HOTSPOT_Y = "cursor_hotspot_offset_y";

public void setCursorHotspotOffset(int offsetX, int offsetY) {
    this.cursorHotspotOffsetX = offsetX;
    this.cursorHotspotOffsetY = offsetY;
    xServerView.requestRender();
}

/**
 * Simpan offset hotspot saat ini ke SharedPreferences.
 * Dipanggil hanya saat pengguna menekan BTConfirm.
 */
public void saveCursorHotspotToPrefs() {
    SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(xServerView.getContext());
    prefs.edit()
         .putInt(PREF_CURSOR_HOTSPOT_X, cursorHotspotOffsetX)
         .putInt(PREF_CURSOR_HOTSPOT_Y, cursorHotspotOffsetY)
         .apply();
}

/**
 * Muat offset hotspot dari SharedPreferences.
 * Dipanggil saat GLRenderer pertama kali dibuat agar posisi tetap konsisten.
 */
public void loadCursorHotspotFromPrefs() {
    SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(xServerView.getContext());
    cursorHotspotOffsetX = prefs.getInt(PREF_CURSOR_HOTSPOT_X, 0);
    cursorHotspotOffsetY = prefs.getInt(PREF_CURSOR_HOTSPOT_Y, 0);
}

public int getCursorHotspotOffsetX() { return cursorHotspotOffsetX; }
public int getCursorHotspotOffsetY() { return cursorHotspotOffsetY; }

    /** Satu slot permintaan screenshot. */
    private static final class ScreenshotRequest {
        final Drawable drawable;
        final Callback<Bitmap> callback;
        ScreenshotRequest(Drawable drawable, Callback<Bitmap> callback) {
            this.drawable = drawable;
            this.callback = callback;
        }
    }

    public GLRenderer(XServerView xServerView, XServer xServer) {
        this.xServerView = xServerView;
        this.xServer = xServer;
        this.effectComposer = new EffectComposer(this);
        rootCursorDrawable = createRootCursorDrawable();

        // Muat offset hotspot kursor yang terakhir disimpan pengguna
        loadCursorHotspotFromPrefs();

        quadVertices.put(new float[]{
            0.0f, 0.0f,
            0.0f, 1.0f,
            1.0f, 0.0f,
            1.0f, 1.0f
        });

        xServer.windowManager.addOnWindowModificationListener(this);
        xServer.pointer.addOnPointerMotionListener(this);
    }

    @Override
    public void onSurfaceCreated(GL10 gl, EGLConfig config) {
        GPUImage.checkIsSupported();

        GLES20.glFrontFace(GLES20.GL_CCW);
        GLES20.glDisable(GLES20.GL_CULL_FACE);

        GLES20.glDisable(GLES20.GL_DEPTH_TEST);
        GLES20.glDepthMask(false);

        GLES20.glEnable(GLES20.GL_BLEND);
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA);
        GLES20.glClearColor(0.0f, 0.0f, 0.0f, 0.0f);
    }

    @Override
    public void onSurfaceChanged(GL10 gl, int width, int height) {
        if (XrActivity.isEnabled(null)) {
            XrActivity activity = XrActivity.getInstance();
            activity.init();
            width = activity.getWidth();
            height = activity.getHeight();
            GLES20.glViewport(0, 0, width, height);
            magnifierEnabled = false;
        }

        surfaceWidth = width;
        surfaceHeight = height;
        viewTransformation.update(width, height, xServer.screenInfo.width, xServer.screenInfo.height);
        viewportNeedsUpdate = true;
    }

    @Override
    public void onDrawFrame(GL10 gl) {
        if (toggleFullscreen) {
            fullscreen = !fullscreen;
            toggleFullscreen = false;
            viewportNeedsUpdate = true;
        }

        if (cpuSaverMode) {
            // CPU Saver mode: skip effectComposer, render langsung.
            if (frameRating != null) frameRating.setIsNative(false);
            drawFrame();
        } else {
            if (frameRating != null) frameRating.setIsNative(false);

            boolean hasEffects = effectComposer != null
                    && effectComposer.hasEffects()
                    && surfaceWidth > 0
                    && surfaceHeight > 0;

            if (!hasEffects) {
                drawFrame();
            } else {
                try {
                    effectComposer.render();
                } catch (Exception e) {
                    drawFrame();
                }
            }
        }

        // Proses antrian screenshot SETELAH frame selesai dirender —
        // menghindari stall di tengah frame dan tidak mengganggu rendering normal.
        if (!screenshotQueue.isEmpty()) {
            processScreenshotQueue();
        }
    }

    public void drawFrame() {
        boolean xrFrame = false;
        boolean xrImmersive = false;
        if (XrActivity.isEnabled(null)) {
            xrImmersive = XrActivity.getImmersive();
            xrFrame = XrActivity.getInstance().beginFrame(xrImmersive, XrActivity.getSBS());
        }

        // Update viewport — selalu gunakan viewTransformation (normal),
        // baik di mode biasa maupun CPU Saver. fullscreen murni ditangani di applySceneTransform.
        if (viewportNeedsUpdate) {
            if (magnifierEnabled) {
                if (fullscreen) {
                    GLES20.glViewport(0, 0, surfaceWidth, surfaceHeight);
                } else {
                    GLES20.glViewport(viewTransformation.viewOffsetX, viewTransformation.viewOffsetY,
                            viewTransformation.viewWidth, viewTransformation.viewHeight);
                }
            }
            viewportNeedsUpdate = false;
        }

        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
        applySceneTransform();

        // CPU Saver hanya skip effectComposer — logika rendering windows identik dengan path normal.
        // forceFullscreen per-window (dari collectRenderableWindows) yang menangani background besar.
        renderWindows(xrImmersive);

        if (cursorVisible) renderCursor();

        if (!magnifierEnabled && !fullscreen) {
            GLES20.glDisable(GLES20.GL_SCISSOR_TEST);
        }

        // Finalize XR frame jika didukung
        if (xrFrame) {
            XrActivity.getInstance().endFrame();
            XrActivity.updateControllers();
            xServerView.requestRender();
        }
    }


    /**
     * Shared helper: computes and applies tmpXForm2 based on magnifier / scroll / fullscreen state.
     */
    private void applySceneTransform() {
        if (magnifierEnabled) {
            float pointerX = 0;
            float pointerY = 0;
            float zoom = screenOffsetYRelativeToCursor ? 1.0f : magnifierZoom;

            if (zoom != 1.0f) {
                pointerX = Mathf.clamp(
                        xServer.pointer.getX() * zoom - xServer.screenInfo.width * 0.5f,
                        0, xServer.screenInfo.width * Math.abs(1.0f - zoom));
            }

            if (screenOffsetYRelativeToCursor || zoom != 1.0f) {
                float scaleY = zoom != 1.0f ? Math.abs(1.0f - zoom) : 0.5f;
                float offsetY = xServer.screenInfo.height * (screenOffsetYRelativeToCursor ? 0.25f : 0.5f);
                pointerY = Mathf.clamp(
                        xServer.pointer.getY() * zoom - offsetY,
                        0, xServer.screenInfo.height * scaleY);
            }

            XForm.makeTransform(tmpXForm2, -pointerX, -pointerY, zoom, zoom, 0);
        } else if (fullscreen) {
            XForm.identity(tmpXForm2);
        } else {
            int pointerY = 0;
            if (screenOffsetYRelativeToCursor) {
                short halfScreenHeight = (short) (xServer.screenInfo.height / 2);
                pointerY = Mathf.clamp(xServer.pointer.getY() - halfScreenHeight / 2, 0, halfScreenHeight);
            }

            XForm.makeTransform(tmpXForm2, viewTransformation.sceneOffsetX,
                    viewTransformation.sceneOffsetY - pointerY,
                    viewTransformation.sceneScaleX, viewTransformation.sceneScaleY, 0);

            GLES20.glEnable(GLES20.GL_SCISSOR_TEST);
            GLES20.glScissor(viewTransformation.viewOffsetX, viewTransformation.viewOffsetY,
                    viewTransformation.viewWidth, viewTransformation.viewHeight);
        }
    }


    @Override
    public void onMapWindow(Window window) {
        xServerView.queueEvent(this::updateScene);
        xServerView.requestRender();
    }

    @Override
    public void onUnmapWindow(Window window) {
        xServerView.queueEvent(this::updateScene);
        xServerView.requestRender();
    }

    @Override
    public void onChangeWindowZOrder(Window window) {
        xServerView.queueEvent(this::updateScene);
        xServerView.requestRender();
    }

    @Override
    public void onUpdateWindowContent(Window window) {
        xServerView.requestRender();
    }

    @Override
    public void onUpdateWindowGeometry(final Window window, boolean resized) {
        if (resized) {
            xServerView.queueEvent(this::updateScene);
        }
        else {
        	xServerView.queueEvent(() -> updateWindowPosition(window));
        	xServerView.queueEvent(this::updateScene);
        }
        xServerView.requestRender();
    }

    @Override
    public void onUpdateWindowAttributes(Window window, Bitmask mask) {
        if (mask.isSet(WindowAttributes.FLAG_CURSOR)) xServerView.requestRender();
    }

    /**
     * Dipanggil saat property window berubah via ChangeProperty atau ClientMessage.
     * Jika _NET_WM_STATE berubah (maximize/fullscreen), trigger updateScene
     * agar collectRenderableWindows mengevaluasi ulang forceFullscreen.
     */
    @Override
    public void onModifyWindowProperty(Window window, Property property) {
        if (property != null && "_NET_WM_STATE".equals(Atom.getName(property.name))) {
            xServerView.queueEvent(this::updateScene);
            xServerView.requestRender();
        }
    }

    @Override
    public void onPointerMove(short x, short y) {
        xServerView.requestRender();
    }


    private void renderDrawable(Drawable drawable, int x, int y, ShaderMaterial material) {
        renderDrawable(drawable, x, y, material, false);
    }

    /**
     * Render drawable ke GPU.
     * Jika {@code forceFullscreen} true, posisi dan ukuran dihitung via
     * {@link FullscreenTransformation#update} (aspect-ratio-preserving, terpusat di layar).
     * Jika false, render normal di koordinat (x, y) dengan ukuran asli drawable.
     */
    private void renderDrawable(Drawable drawable, int x, int y, ShaderMaterial material, boolean forceFullscreen) {
        if (drawable == null) return;
        synchronized (drawable.renderLock) {
            Texture texture = drawable.getTexture();
            texture.updateFromDrawable(drawable);

            if (forceFullscreen) {
                // Gunakan field reusable — tidak alokasi objek baru per frame.
                tmpFullscreenTransformation.update(xServer.screenInfo,
                        (short) drawable.width, (short) drawable.height);
                XForm.set(tmpXForm1,
                        tmpFullscreenTransformation.x,
                        tmpFullscreenTransformation.y,
                        tmpFullscreenTransformation.width,
                        tmpFullscreenTransformation.height);
            } else {
                XForm.set(tmpXForm1, x, y, drawable.width, drawable.height);
            }

            XForm.multiply(tmpXForm1, tmpXForm1, tmpXForm2);

            GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture.getTextureId());
            GLES20.glUniform1i(material.getUniformLocation("texture"), 0);
            GLES20.glUniform1fv(material.getUniformLocation("xform"), tmpXForm1.length, tmpXForm1, 0);
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, quadVertices.count());
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0);
        }
    }

    private void renderWindows(boolean xrForceFullscreen) {
        windowMaterial.use();
        GLES20.glUniform2f(windowMaterial.getUniformLocation("viewSize"),
                xServer.screenInfo.width, xServer.screenInfo.height);
        quadVertices.bind(windowMaterial.programId);

        try (XLock lock = xServer.lock(XServer.Lockable.DRAWABLE_MANAGER)) {
            if (renderableWindows.isEmpty()) {
                quadVertices.disable();
                return;
            }

            boolean blendEnabled = true; // blend aktif secara default dari onSurfaceCreated

            for (RenderableWindow rw : renderableWindows) {
                boolean fs = xrForceFullscreen || rw.forceFullscreen;

                if (fs) {
                    // Window besar forceFullscreen: render opaque tanpa blend
                    // agar tidak ada artefak alpha di background.
                    if (blendEnabled) {
                        GLES20.glDisable(GLES20.GL_BLEND);
                        blendEnabled = false;
                    }
                } else {
                    // Window kecil (overlay/dialog): render dengan blend alpha.
                    if (!blendEnabled) {
                        GLES20.glEnable(GLES20.GL_BLEND);
                        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA);
                        blendEnabled = true;
                    }
                }

                renderDrawable(rw.content, rw.rootX, rw.rootY, windowMaterial, fs);
            }

            // Pastikan blend selalu aktif kembali setelah renderWindows selesai
            // agar renderCursor dan pass berikutnya tidak terpengaruh.
            if (!blendEnabled) {
                GLES20.glEnable(GLES20.GL_BLEND);
                GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA);
            }
        }

        quadVertices.disable();

        int error = GLES20.glGetError();
        if (error != GLES20.GL_NO_ERROR) {
            Log.e("GLRenderer", "OpenGL Error: " + error);
        }
    }

    
private void renderCursor() {
    cursorMaterial.use();
    GLES20.glUniform2f(cursorMaterial.getUniformLocation("viewSize"),
        xServer.screenInfo.width, xServer.screenInfo.height);
    quadVertices.bind(cursorMaterial.programId);

    try (XLock lock = xServer.lock(XServer.Lockable.DRAWABLE_MANAGER)) {
        Window pointWindow = xServer.inputDeviceManager.getPointWindow();
        Cursor cursor = pointWindow != null ? pointWindow.attributes.getCursor() : null;
        short x = xServer.pointer.getClampedX();
        short y = xServer.pointer.getClampedY();

        if (cursor == null) {
            // Tidak ada cursor yang di-assign ke window → render cursor default Winlator.
            renderDrawable(rootCursorDrawable,
                x - cursorHotspotOffsetX, y - cursorHotspotOffsetY, cursorMaterial);
        } else if (cursor.isForceHidden()) {
            // BUG FIX: Game secara eksplisit menyembunyikan cursor (XFixesHideCursor,
            // XDefineCursor None, dsb.) → jangan render apa pun, termasuk rootCursorDrawable.
            // Ini yang menyebabkan cursor Winlator tetap muncul di atas cursor bawaan game.
        } else if (cursor.isVisible()) {
            // Cursor normal, mask tidak kosong → render cursor X11 milik window.
            int renderX = x - cursor.hotSpotX - cursorHotspotOffsetX;
            int renderY = y - cursor.hotSpotY - cursorHotspotOffsetY;
            renderDrawable(cursor.cursorImage, renderX, renderY, cursorMaterial);
        }
        // else: cursor ada tapi mask-nya kosong/transparan (visible == false) → tidak render.
    }

    quadVertices.disable();
}


    public void toggleFullscreen() {
        toggleFullscreen = true;
        xServerView.requestRender();
    }

    private Drawable createRootCursorDrawable() {
        Context context = xServerView.getContext();
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inScaled = false;
        Bitmap bitmap = BitmapFactory.decodeResource(context.getResources(), R.drawable.cursor, options);
        return Drawable.fromBitmap(bitmap);
    }

    public void updateScene() {
        try (XLock lock = xServer.lock(XServer.Lockable.WINDOW_MANAGER, XServer.Lockable.DRAWABLE_MANAGER)) {
            renderableWindows.clear();
            collectRenderableWindows(xServer.windowManager.rootWindow, xServer.windowManager.rootWindow.getX(), xServer.windowManager.rootWindow.getY());
        }
    }

    private void collectRenderableWindows(Window window, int x, int y) {
        if (!window.attributes.isMapped()) return;

        if (window != xServer.windowManager.rootWindow) {
            boolean viewable = true;

            if (unviewableWMClasses != null) {
                String wmClass = window.getClassName();
                for (String unviewableWMClass : unviewableWMClasses) {
                    if (wmClass.contains(unviewableWMClass)) {
                        if (window.attributes.isEnabled()) window.disableAllDescendants();
                        viewable = false;
                        break;
                    }
                }
            }

            if (viewable) {
                boolean forceFullscreen = false;

                if (forceFullscreenWMClass != null) {
                    // ForceFullscreen aktif — evaluasi apakah window ini perlu FullscreenTransformation.
                    short width = window.getWidth();
                    short height = window.getHeight();
                    float screenW = xServer.screenInfo.width;
                    float screenH = xServer.screenInfo.height;

                    // Deteksi maximize via _NET_WM_STATE (hanya leaf window).
                    boolean isMaximized = window.isMaximized() && window.getChildCount() == 0;

                    // Deteksi window besar: ≥75% lebar DAN tinggi layar.
                    boolean isLargeWindow = (width >= screenW * 0.75f) && (height >= screenH * 0.75f);

                    if (isMaximized || isLargeWindow) {
                        Window parent = window.getParent();
                        boolean hasWMClass = window.getClassName().contains(forceFullscreenWMClass);
                        boolean parentHasWMClass = parent.getClassName().contains(forceFullscreenWMClass);

                        if (hasWMClass) {
                            // WMClass cocok — fullscreen jika parent tidak cocok dan ini leaf window.
                            forceFullscreen = !parentHasWMClass && window.getChildCount() == 0;
                        } else if (isMaximized) {
                            // Maximize via _NET_WM_STATE, WMClass tidak cocok.
                            // Sembunyikan parent (title bar) dan render fullscreen.
                            forceFullscreen = true;
                            removeRenderableWindow(parent);
                        } else {
                            // Fallback: deteksi frame dekorasi tipis (borderX ≤ 12px).
                            short borderX = (short) (parent.getWidth() - width);
                            short borderY = (short) (parent.getHeight() - height);
                            if (parent.getChildCount() == 1
                                    && borderX > 0 && borderY > 0 && borderX <= 12) {
                                forceFullscreen = true;
                                removeRenderableWindow(parent);
                            }
                        }
                    }
                }
                // forceFullscreenWMClass == null → forceFullscreen tetap false,
                // semua window dirender normal tanpa FullscreenTransformation.
                // forceFullscreen sudah dievaluasi — lanjut render.

                renderableWindows.add(new RenderableWindow(window.getContent(), x, y, forceFullscreen));
            }
        }

        for (Window child : window.getChildren()) {
            collectRenderableWindows(child, child.getX() + x, child.getY() + y);
        }
    }

    private void removeRenderableWindow(Window window) {
        for (int i = 0; i < renderableWindows.size(); i++) {
            if (renderableWindows.get(i).content == window.getContent()) {
                renderableWindows.remove(i);
                break;
            }
        }
    }

    private void updateWindowPosition(Window window) {
        for (RenderableWindow renderableWindow : renderableWindows) {
            if (renderableWindow.content == window.getContent()) {
                renderableWindow.rootX = window.getRootX();
                renderableWindow.rootY = window.getRootY();
                break;
            }
        }
    }

    public void setCursorVisible(boolean cursorVisible) {
        this.cursorVisible = cursorVisible;
        xServerView.requestRender();
    }

    public boolean isCursorVisible() {
        return cursorVisible;
    }

    public boolean isScreenOffsetYRelativeToCursor() {
        return screenOffsetYRelativeToCursor;
    }

    public void setScreenOffsetYRelativeToCursor(boolean screenOffsetYRelativeToCursor) {
        this.screenOffsetYRelativeToCursor = screenOffsetYRelativeToCursor;
        xServerView.requestRender();
    }

    public boolean isFullscreen() {
        return fullscreen;
    }

    public String getForceFullscreenWMClass() {
        return forceFullscreenWMClass;
    }

    public void setForceFullscreenWMClass(String forceFullscreenWMClass) {
        this.forceFullscreenWMClass = forceFullscreenWMClass;
    }

    public String[] getUnviewableWMClasses() {
        return unviewableWMClasses;
    }

    public void setUnviewableWMClasses(String... unviewableWMNames) {
        this.unviewableWMClasses = unviewableWMNames;
    }

    public float getMagnifierZoom() {
        return magnifierZoom;
    }

    public void setMagnifierZoom(float magnifierZoom) {
        this.magnifierZoom = magnifierZoom;
        xServerView.requestRender();
    }

    public int getSurfaceWidth() {
        return surfaceWidth;
    }

    public int getSurfaceHeight() {
        return surfaceHeight;
    }

    public boolean isViewportNeedsUpdate() {
        return viewportNeedsUpdate;
    }

    public void setViewportNeedsUpdate(boolean viewportNeedsUpdate) {
        this.viewportNeedsUpdate = viewportNeedsUpdate;
    }

    public VertexAttribute getQuadVertices() {
        return quadVertices;
    }

    public EffectComposer getEffectComposer (){
        return effectComposer;
    }

    // --- CPU Saver / Direct Rendering public API ---

    /**
     * Enables or disables CPU Saver mode.
     * Saat aktif, effectComposer dilewati sepenuhnya — rendering windows
     * tetap identik dengan path normal, termasuk forceFullscreen per-window
     * yang ditentukan oleh {@code collectRenderableWindows}.
     */
    public void setNativeMode(boolean enabled) {
        if (cpuSaverMode == enabled) return;
        cpuSaverMode = enabled;
        viewportNeedsUpdate = true;

        String message = enabled ? "Direct Rendering+ Enabled" : "Direct Rendering+ Disabled";
        xServerView.post(() -> Toast.makeText(xServerView.getContext(), message, Toast.LENGTH_SHORT).show());
        xServerView.setRenderMode(GLSurfaceView.RENDERMODE_WHEN_DIRTY);
        xServerView.requestRender();
    }

    public boolean isNativeMode() {
        return cpuSaverMode;
    }

    public FrameRating getFrameRating() {
        return frameRating;
    }

    public void setFrameRating(FrameRating frameRating) {
        this.frameRating = frameRating;
    }

    private void renderWindowEffect(Drawable drawable, int x, int y, ShaderMaterial material) {
        // Implement the rendering effect logic here
        synchronized (drawable.renderLock) {
            Texture texture = drawable.getTexture();
            texture.updateFromDrawable(drawable);

            XForm.set(tmpXForm1, x, y, drawable.width, drawable.height);
            XForm.multiply(tmpXForm1, tmpXForm1, tmpXForm2);

            GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture.getTextureId());
            if (GLES20.glIsTexture(texture.getTextureId()) == false) {
                Log.e("GLRenderer", "Invalid texture binding!");
            }

            GLES20.glUniform1i(material.getUniformLocation("texture"), 0);
            GLES20.glUniform1fv(material.getUniformLocation("xform"), tmpXForm1.length, tmpXForm1, 0);
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, quadVertices.count());
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0);
        }
    }

    // ===================== SCREENSHOT =====================

    /**
     * Meminta screenshot async dari drawable window.
     * Permintaan dimasukkan ke antrian dan diproses di GL thread setelah
     * frame berikutnya selesai — tidak ada stall pada render loop utama.
     * Callback dipanggil di GL thread; jika perlu update UI, post ke main thread sendiri.
     *
     * @param drawable  Drawable (content) dari window target
     * @param callback  Dipanggil dengan Bitmap thumbnail, atau null jika gagal
     */
    public void takeWindowScreenshot(Drawable drawable, Callback<Bitmap> callback) {
        if (drawable == null || callback == null) return;
        screenshotQueue.offer(new ScreenshotRequest(drawable, callback));
        xServerView.requestRender();
    }

    /**
     * Diproses di GL thread (dari onDrawFrame) — aman menggunakan OpenGL.
     * Menguras seluruh antrian dalam satu pass agar beberapa window
     * dalam ActiveWindowsDialog tidak butuh banyak frame terpisah.
     *
     * Optimasi:
     * - ScreenMaterial dan RenderTarget di-reuse antar permintaan (lazy init, destroy hanya saat perlu resize)
     * - glReadPixels dijaga seminimal mungkin (resolusi thumbnail kecil, max 256px)
     * - synchronized(renderLock) hanya pada bagian upload texture, bukan seluruh pipeline
     * - Viewport dan FBO di-restore setelah selesai agar rendering normal tidak terganggu
     */
    private void processScreenshotQueue() {
        // Lazy-init material (di-reuse, tidak dialokasi ulang tiap frame)
        if (screenshotMaterial == null) {
            screenshotMaterial = new ScreenMaterial();
        }

        // Simpan viewport saat ini agar bisa di-restore
        int[] savedViewport = new int[4];
        GLES20.glGetIntegerv(GLES20.GL_VIEWPORT, savedViewport, 0);

        // Pastikan blend dinonaktifkan saat render ke FBO (screenshot tidak butuh alpha compositing)
        GLES20.glDisable(GLES20.GL_BLEND);

        ScreenshotRequest req;
        while ((req = screenshotQueue.poll()) != null) {
            processSingleScreenshot(req);
        }

        // Restore state
        GLES20.glEnable(GLES20.GL_BLEND);
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA);
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
        GLES20.glViewport(savedViewport[0], savedViewport[1], savedViewport[2], savedViewport[3]);
        viewportNeedsUpdate = true; // sinyal agar viewport di-recalc di frame berikutnya
    }

    private void processSingleScreenshot(ScreenshotRequest req) {
        try {
            doProcessSingleScreenshot(req);
        } catch (Exception e) {
            // Tangkap semua exception (NPE, GL error, dll) agar satu window
            // yang bermasalah tidak crash seluruh antrian / GL thread.
            Log.e("GLRenderer", "Screenshot failed, skipping: " + e.getMessage());
            req.callback.call(null);
        }
    }

    private void doProcessSingleScreenshot(ScreenshotRequest req) {
        Drawable drawable = req.drawable;

        // Guard 1: drawable null atau ukuran tidak valid
        if (drawable == null || drawable.width <= 0 || drawable.height <= 0) {
            req.callback.call(null);
            return;
        }

        // Guard 2: getData() == null → window belum punya pixel data sama sekali.
        // Tidak crash — callback dengan null agar UI tampilkan placeholder dashed frame.
        // Ini normal untuk window yang baru dibuat tapi belum di-paint (bukan error).
        if (drawable.getData() == null) {
            req.callback.call(null);
            return;
        }

        // Hitung ukuran thumbnail aspect-ratio-preserving secara inline
        int srcW = drawable.width;
        int srcH = drawable.height;
        int w, h;
        if (srcW >= srcH) {
            w = SCREENSHOT_MAX_SIZE;
            h = Math.max(1, (int) ((float) srcH / srcW * SCREENSHOT_MAX_SIZE));
        } else {
            h = SCREENSHOT_MAX_SIZE;
            w = Math.max(1, (int) ((float) srcW / srcH * SCREENSHOT_MAX_SIZE));
        }

        // Reuse atau re-alokasi RenderTarget hanya jika ukuran berubah
        if (screenshotRenderTarget == null
                || screenshotRenderTargetW != w
                || screenshotRenderTargetH != h) {
            if (screenshotRenderTarget != null) {
                int fbo = screenshotRenderTarget.getFramebuffer();
                if (fbo != 0) GLES20.glDeleteFramebuffers(1, new int[]{fbo}, 0);
                screenshotRenderTarget.destroy();
            }
            screenshotRenderTarget = new RenderTarget();
            screenshotRenderTarget.allocateFramebuffer(w, h);
            screenshotRenderTargetW = w;
            screenshotRenderTargetH = h;
        }

        // Guard 3: FBO gagal diinisialisasi
        int fboId = screenshotRenderTarget.getFramebuffer();
        if (fboId == 0) {
            Log.e("GLRenderer", "Screenshot skipped: FBO allocation failed");
            req.callback.call(null);
            return;
        }

        // Bind FBO
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fboId);
        GLES20.glViewport(0, 0, w, h);
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);

        // Upload texture dari drawable — lock seminimal mungkin
        Texture texture;
        synchronized (drawable.renderLock) {
            texture = drawable.getTexture();
            texture.updateFromDrawable(drawable);
        }

        // Guard 4: texture tidak berhasil dialokasi setelah updateFromDrawable
        if (!texture.isAllocated()) {
            Log.e("GLRenderer", "Screenshot skipped: texture not allocated after updateFromDrawable");
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
            req.callback.call(null);
            return;
        }

        // Render texture ke FBO menggunakan ScreenMaterial (full-quad blit)
        screenshotMaterial.use();
        quadVertices.bind(screenshotMaterial.programId);

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture.getTextureId());
        screenshotMaterial.setUniformInt("screenTexture", 0);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, quadVertices.count());
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0);
        quadVertices.disable();

        // Baca pixel — operasi mahal, ukuran dijaga kecil
        Bitmap bitmap = null;
        try {
            int[] pixels = getPixelsARGB(0, 0, w, h, false);
            bitmap = Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888);
        } catch (Exception e) {
            Log.e("GLRenderer", "Screenshot readback failed: " + e.getMessage());
        }

        req.callback.call(bitmap);
    }

    /**
     * Membaca pixel dari FBO yang sedang terikat dan mengkonversi RGBA → ARGB.
     * Dipanggil hanya dari GL thread.
     *
     * @param x, y      Origin pembacaan (biasanya 0,0)
     * @param width, height  Dimensi area baca
     * @param flipY     true = balik vertikal (untuk pembacaan dari default framebuffer)
     */
    public int[] getPixelsARGB(int x, int y, int width, int height, boolean flipY) {
        ByteBuffer pixelBuffer = ByteBuffer.allocateDirect(width * height * 4)
                .order(ByteOrder.nativeOrder());
        GLES20.glReadPixels(x, y, width, height, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, pixelBuffer);

        IntBuffer colors = pixelBuffer.asIntBuffer();
        int[] result = new int[width * height];

        if (flipY) {
            for (int row = 0; row < height; row++) {
                colors.position((height - row - 1) * width);
                colors.get(result, row * width, width);
            }
        } else {
            colors.get(result);
        }

        // Konversi RGBA → ARGB (swap R dan B channel)
        for (int i = 0; i < result.length; i++) {
            int rgba = result[i];
            result[i] = (rgba & 0xFF00FF00)           // G dan A tetap
                    | ((rgba & 0x000000FF) << 16)      // R → pindah ke posisi merah ARGB
                    | ((rgba & 0x00FF0000) >> 16);     // B → pindah ke posisi biru ARGB
        }
        return result;
    }
}

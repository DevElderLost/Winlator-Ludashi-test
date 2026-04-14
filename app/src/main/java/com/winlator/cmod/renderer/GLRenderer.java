package com.winlator.cmod.renderer;

import android.content.Context;
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
    private static final int SCREENSHOT_MAX_SIZE = 256;
    public GLRenderer(XServerView xServerView, XServer xServer) {
        this.xServerView = xServerView;
        this.xServer = xServer;
        this.effectComposer = new EffectComposer(this);
        rootCursorDrawable = createRootCursorDrawable();

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
        GLES20.glUniform2f(cursorMaterial.getUniformLocation("viewSize"), xServer.screenInfo.width, xServer.screenInfo.height);
        quadVertices.bind(cursorMaterial.programId);

        try (XLock lock = xServer.lock(XServer.Lockable.DRAWABLE_MANAGER)) {
            Window pointWindow = xServer.inputDeviceManager.getPointWindow();
            Cursor cursor = pointWindow != null ? pointWindow.attributes.getCursor() : null;
            short x = xServer.pointer.getClampedX();
            short y = xServer.pointer.getClampedY();

            if (cursor != null) {
                if (cursor.isVisible()) renderDrawable(cursor.cursorImage, x - cursor.hotSpotX, y - cursor.hotSpotY, cursorMaterial);
            }
            else renderDrawable(rootCursorDrawable, x, y, cursorMaterial);
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
                    short width = window.getWidth();
                    short height = window.getHeight();
                    float screenW = xServer.screenInfo.width;
                    float screenH = xServer.screenInfo.height;

                    // Deteksi maximize via _NET_WM_STATE — hanya untuk leaf window
                    // (childCount == 0) agar tidak tumpang tindih dengan parent decoration.
                    boolean isMaximized = window.isMaximized() && window.getChildCount() == 0;

                    // FullscreenTransformation aktif untuk window BESAR (≥75% layar)
                    // ATAU window yang sedang maximize via _NET_WM_STATE.
                    boolean isLargeWindow = (width >= screenW * 0.75f) && (height >= screenH * 0.75f);

                    if (isMaximized || isLargeWindow) {
                        Window parent = window.getParent();
                        boolean hasWMClass = window.getClassName().contains(forceFullscreenWMClass);
                        boolean parentHasWMClass = parent.getClassName().contains(forceFullscreenWMClass);

                        if (hasWMClass) {
                            // Path lama: WMClass cocok — aktifkan FullscreenTransformation
                            // jika parent tidak punya WMClass yang sama dan window adalah daun.
                            forceFullscreen = !parentHasWMClass && window.getChildCount() == 0;
                        } else if (isMaximized) {
                            // Path baru: maximize via _NET_WM_STATE, WMClass tidak cocok.
                            // Sembunyikan parent (title bar/decoration) dan render window ini fullscreen.
                            forceFullscreen = true;
                            removeRenderableWindow(parent);
                        } else {
                            // Path lama: fallback deteksi frame dekorasi tipis (borderX ≤ 12px).
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
                // forceFullscreenWMClass == null: forceFullscreen tetap false,
                // semua window dirender normal tanpa FullscreenTransformation.

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
     * Dipanggil dari UI thread — mengikuti persis pola referensi asli:
     * queueEvent + requestRender agar eksekusi terjadi di GL thread.
     * Seluruh operasi GL terbungkus synchronized(drawable.renderLock).
     */
    public void takeWindowScreenshot(Drawable drawable, Callback<Bitmap> callback) {
        if (drawable == null || callback == null) return;
        xServerView.queueEvent(() -> takeWindowScreenshotGL(drawable, callback));
        xServerView.requestRender();
    }

    /**
     * Dieksekusi di GL thread via queueEvent — persis seperti referensi asli.
     * Seluruh operasi GL di dalam synchronized(drawable.renderLock).
     */
    private void takeWindowScreenshotGL(Drawable drawable, Callback<Bitmap> callback) {
        synchronized (drawable.renderLock) {
            try {
                if (drawable.width <= 0 || drawable.height <= 0) {
                    callback.call(null);
                    return;
                }

                Texture texture = drawable.getTexture();
                texture.updateFromDrawable(drawable);

                if (!texture.isAllocated()) {
                    callback.call(null);
                    return;
                }

                // Hitung ukuran thumbnail aspect-ratio-preserving
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

                // Buat FBO baru tiap panggilan — sama seperti referensi
                RenderTarget renderTarget = new RenderTarget();
                renderTarget.allocateFramebuffer(w, h);

                int fboId = renderTarget.getFramebuffer();
                if (fboId == 0) {
                    callback.call(null);
                    return;
                }

                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fboId);
                GLES20.glViewport(0, 0, w, h);
                viewportNeedsUpdate = true;
                GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);

                ScreenMaterial screenMaterial = new ScreenMaterial();
                screenMaterial.use();
                quadVertices.bind(screenMaterial.programId);

                GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture.getTextureId());
                screenMaterial.setUniformInt("screenTexture", 0);
                GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, quadVertices.count());
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0);
                quadVertices.disable();

                Bitmap bitmap = null;
                try {
                    int[] pixels = getPixelsARGB(0, 0, w, h, false);
                    bitmap = Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888);
                } catch (Exception e) {
                    Log.e("GLRenderer", "Screenshot readback failed: " + e.getMessage());
                }

                // Cleanup — destroy FBO dan material setelah pakai
                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
                int fbo = renderTarget.getFramebuffer();
                if (fbo != 0) GLES20.glDeleteFramebuffers(1, new int[]{fbo}, 0);
                renderTarget.destroy();
                screenMaterial.destroy();

                callback.call(bitmap);

            } catch (Exception e) {
                Log.e("GLRenderer", "takeWindowScreenshot failed: " + e.getMessage());
                callback.call(null);
            }
        }
    }

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
            result[i] = (rgba & 0xFF00FF00)
                    | ((rgba & 0x000000FF) << 16)
                    | ((rgba & 0x00FF0000) >> 16);
        }
        return result;
    }
}

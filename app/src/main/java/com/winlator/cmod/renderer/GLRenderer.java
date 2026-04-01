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
import com.winlator.cmod.math.Mathf;
import com.winlator.cmod.math.XForm;
import com.winlator.cmod.renderer.material.CursorMaterial;
import com.winlator.cmod.renderer.material.ShaderMaterial;
import com.winlator.cmod.renderer.material.WindowMaterial;
import com.winlator.cmod.widget.FrameRating;
import com.winlator.cmod.widget.XServerView;
import com.winlator.cmod.xserver.Bitmask;
import com.winlator.cmod.xserver.Cursor;
import com.winlator.cmod.xserver.Drawable;
import com.winlator.cmod.xserver.Pointer;
import com.winlator.cmod.xserver.Window;
import com.winlator.cmod.xserver.WindowAttributes;
import com.winlator.cmod.xserver.WindowManager;
import com.winlator.cmod.xserver.XLock;
import com.winlator.cmod.xserver.XServer;

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
    private String forceFullscreenWMClass = null;
    private boolean fullscreen = false;
    private boolean toggleFullscreen = false;
    public boolean viewportNeedsUpdate = true;
    private boolean cursorVisible = true;
    private boolean rootWindowDownsized = false;
    private boolean screenOffsetYRelativeToCursor = false;
    private String[] unviewableWMClasses = null;
    private float magnifierZoom = 1.0f;
    private boolean magnifierEnabled = true;
    public int surfaceWidth;
    public int surfaceHeight;
    private final EffectComposer effectComposer;

    // --- CPU Saver / Direct Rendering ---
    private static final float DIRECT_MODE_COVERAGE_THRESHOLD = 0.95f;
    private boolean cpuSaverMode = false;
    private boolean wasDirectMode = false;
    private FrameRating frameRating;

    // --- ForceFullscreen coordinate transform state ---
    // Stores the visual offset and scale applied when a window is force-fullscreened,
    // so that pointer/touch coordinates can be inverse-transformed to match the
    // actual (smaller) window coordinate space.
    private float forceFullscreenOffsetX = 0f;
    private float forceFullscreenOffsetY = 0f;
    private float forceFullscreenScaleX = 1f;
    private float forceFullscreenScaleY = 1f;
    private boolean isForceFullscreenActive = false;

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
            // Effects are disabled in CPU saver mode (see setNativeMode).
            // drawFrameOptimized renders directly without any effectComposer processing.
            drawFrameOptimized();
            return;
        }

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

    public void drawFrame() {
        boolean xrFrame = false;
        boolean xrImmersive = false;
        if (XrActivity.isEnabled(null)) {
            xrImmersive = XrActivity.getImmersive();
            xrFrame = XrActivity.getInstance().beginFrame(xrImmersive, XrActivity.getSBS());
        }

        // Update the viewport if necessary
        if (viewportNeedsUpdate && magnifierEnabled) {
            if (fullscreen) {
                GLES20.glViewport(0, 0, surfaceWidth, surfaceHeight);
            }
            else {
                GLES20.glViewport(viewTransformation.viewOffsetX, viewTransformation.viewOffsetY, viewTransformation.viewWidth, viewTransformation.viewHeight);
            }
            viewportNeedsUpdate = false;
        }

        // Clear the screen before drawing
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);

        // Apply scene transform (magnifier / scroll / fullscreen)
        applySceneTransform();

        renderWindows(xrImmersive);

        // Render cursor if enabled
        if (cursorVisible && !rootWindowDownsized) renderCursor();

        // Disable scissor test if magnifier is disabled and not in fullscreen mode
        if (!magnifierEnabled && !fullscreen) {
            GLES20.glDisable(GLES20.GL_SCISSOR_TEST);
        }

        // Finalize XR frame if supported
        if (xrFrame) {
            XrActivity.getInstance().endFrame();
            XrActivity.updateControllers();
            xServerView.requestRender();
        }
    }

    // -------------------------------------------------------------------------
    // CPU Saver / Direct Rendering optimized path
    // -------------------------------------------------------------------------

    /**
     * Optimized frame path used when CPU saver mode is active.
     * Tries to render a single large window directly (native/direct mode),
     * bypassing full compositing when possible.
     */
    private void drawFrameOptimized() {
        RenderableWindow directCandidate = findDirectRenderCandidate();
        boolean isDirectMode = (directCandidate != null);

        if (isDirectMode != wasDirectMode) {
            viewportNeedsUpdate = true;
            wasDirectMode = isDirectMode;
        }

        if (isDirectMode) {
            drawFrameDirect(directCandidate);
        } else {
            drawFrameComposited();
        }
    }

    /**
     * Finds the topmost window covering >= 95% of the screen for direct rendering.
     * Returns null if no candidate exists.
     */
    private RenderableWindow findDirectRenderCandidate() {
        int screenW = xServer.screenInfo.width;
        int screenH = xServer.screenInfo.height;

        try (XLock lock = xServer.lock(XServer.Lockable.DRAWABLE_MANAGER)) {
            for (int i = renderableWindows.size() - 1; i >= 0; i--) {
                RenderableWindow rw = renderableWindows.get(i);
                if (rw.content != null
                        && rw.content.width  >= screenW * DIRECT_MODE_COVERAGE_THRESHOLD
                        && rw.content.height >= screenH * DIRECT_MODE_COVERAGE_THRESHOLD) {
                    return rw;
                }
            }
        }
        return null;
    }

    /**
     * Direct rendering path: draws a single full-screen candidate without compositing.
     * Blending is disabled for maximum throughput.
     */
    private void drawFrameDirect(RenderableWindow directCandidate) {
        if (frameRating != null) frameRating.setIsNative(true);

        if (viewportNeedsUpdate) {
            if (fullscreen) {
                GLES20.glViewport(0, 0, surfaceWidth, surfaceHeight);
            } else {
                GLES20.glViewport(viewTransformation.viewOffsetX, viewTransformation.viewOffsetY,
                        viewTransformation.viewWidth, viewTransformation.viewHeight);
            }
            viewportNeedsUpdate = false;
        }

        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
        GLES20.glDisable(GLES20.GL_BLEND);

        applySceneTransform();

        windowMaterial.use();
        GLES20.glUniform2f(windowMaterial.getUniformLocation("viewSize"),
                xServer.screenInfo.width, xServer.screenInfo.height);
        quadVertices.bind(windowMaterial.programId);

        // Large windows are rendered normally (forceFullscreen=false) — original behavior.
        // Only small windows with the forceFullscreen flag that have grown to >= threshold
        // will be scaled to the GPU surface by renderDrawable.
        renderDrawable(directCandidate.content, directCandidate.rootX, directCandidate.rootY,
                windowMaterial, directCandidate.forceFullscreen);

        // Render overlay windows (dialogs, popups, new windows) on top of the direct candidate
        GLES20.glEnable(GLES20.GL_BLEND);
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA);
        try (XLock lock = xServer.lock(XServer.Lockable.DRAWABLE_MANAGER)) {
            for (RenderableWindow rw : renderableWindows) {
                if (rw == directCandidate) continue;
                renderDrawable(rw.content, rw.rootX, rw.rootY, windowMaterial, rw.forceFullscreen);
            }
        }
        GLES20.glDisable(GLES20.GL_BLEND);

        if (cursorVisible) {
            GLES20.glEnable(GLES20.GL_BLEND);
            GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA);
            renderCursor();
        }

        if (!magnifierEnabled && !fullscreen) {
            GLES20.glDisable(GLES20.GL_SCISSOR_TEST);
        }

        quadVertices.disable();
    }

    /**
     * Composited rendering path for CPU saver mode when no direct candidate is found.
     * Renders all windows layered with blending enabled.
     */
    private void drawFrameComposited() {
        if (frameRating != null) frameRating.setIsNative(false);

        if (viewportNeedsUpdate && magnifierEnabled) {
            if (fullscreen) {
                GLES20.glViewport(0, 0, surfaceWidth, surfaceHeight);
            } else {
                GLES20.glViewport(viewTransformation.viewOffsetX, viewTransformation.viewOffsetY,
                        viewTransformation.viewWidth, viewTransformation.viewHeight);
            }
            viewportNeedsUpdate = false;
        }

        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
        GLES20.glEnable(GLES20.GL_BLEND);
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA);

        applySceneTransform();

        windowMaterial.use();
        GLES20.glUniform2f(windowMaterial.getUniformLocation("viewSize"),
                xServer.screenInfo.width, xServer.screenInfo.height);
        quadVertices.bind(windowMaterial.programId);

        try (XLock lock = xServer.lock(XServer.Lockable.DRAWABLE_MANAGER)) {
            for (RenderableWindow rw : renderableWindows) {
                // renderDrawable only applies GPU scaling when the window has
                // actually grown to cover >= DIRECT_MODE_COVERAGE_THRESHOLD.
                renderDrawable(rw.content, rw.rootX, rw.rootY, windowMaterial, rw.forceFullscreen);
            }
        }

        if (cursorVisible && !rootWindowDownsized) renderCursor();

        if (!magnifierEnabled && !fullscreen) {
            GLES20.glDisable(GLES20.GL_SCISSOR_TEST);
        }

        quadVertices.disable();
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

    @Override
    public void onPointerMove(short x, short y) {
        xServerView.requestRender();
    }


    private void renderDrawable(Drawable drawable, int x, int y, ShaderMaterial material) {
        renderDrawable(drawable, x, y, material, false);
    }

    private void renderDrawable(Drawable drawable, int x, int y, ShaderMaterial material, boolean forceFullscreen) {
        if (drawable == null) return;
        synchronized (drawable.renderLock) {
            Texture texture = drawable.getTexture();
            texture.updateFromDrawable(drawable);

            // GPU scaling is applied ONLY to windows that:
            //   1. Have the forceFullscreen flag set, AND
            //   2. Have physically grown to >= DIRECT_MODE_COVERAGE_THRESHOLD.
            // Large windows (>= threshold) without the forceFullscreen flag are
            // always rendered normally at their actual position/size (original behavior).
            int screenW = xServer.screenInfo.width;
            int screenH = xServer.screenInfo.height;
            boolean isActuallyLarge = drawable.width  >= screenW * DIRECT_MODE_COVERAGE_THRESHOLD
                                   && drawable.height >= screenH * DIRECT_MODE_COVERAGE_THRESHOLD;

            if (forceFullscreen && isActuallyLarge) {
                // Window has grown large enough — scale to GPU surface
                short newHeight = (short)Math.min(xServer.screenInfo.height, ((float)xServer.screenInfo.width / drawable.width) * drawable.height);
                short newWidth = (short)(((float)newHeight / drawable.height) * drawable.width);
                float offsetX = (xServer.screenInfo.width - newWidth) * 0.5f;
                float offsetY = (xServer.screenInfo.height - newHeight) * 0.5f;
                XForm.set(tmpXForm1, offsetX, offsetY, newWidth, newHeight);

                // Save transform so pointer coordinates can be inverse-mapped
                forceFullscreenOffsetX = offsetX;
                forceFullscreenOffsetY = offsetY;
                forceFullscreenScaleX  = (float) newWidth  / drawable.width;
                forceFullscreenScaleY  = (float) newHeight / drawable.height;
                isForceFullscreenActive = true;
            }
            else {
                // Large windows without forceFullscreen flag, or small windows not
                // yet at threshold — render at actual position/size (original behavior).
                XForm.set(tmpXForm1, x, y, drawable.width, drawable.height);
                isForceFullscreenActive = false;
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

    private void renderWindows(boolean forceFullscreen) {
        windowMaterial.use();
        GLES20.glUniform2f(windowMaterial.getUniformLocation("viewSize"), xServer.screenInfo.width, xServer.screenInfo.height);
        quadVertices.bind(windowMaterial.programId);

        boolean singleWindow = forceFullscreen;
        try (XLock lock = xServer.lock(XServer.Lockable.DRAWABLE_MANAGER)) {
            rootWindowDownsized = false;
            if (fullscreen && !renderableWindows.isEmpty()) {
                RenderableWindow root = renderableWindows.get(0);
                if ((root.content.width < xServer.screenInfo.width) || (root.content.height < xServer.screenInfo.height)) {
                    rootWindowDownsized = true;
                    singleWindow = true;
                }
            }

            if (singleWindow && !renderableWindows.isEmpty()) {
                // Render the bottom-most (background) window as fullscreen
                RenderableWindow root = renderableWindows.get(renderableWindows.size() - 1);
                renderDrawable(root.content, root.rootX, root.rootY, windowMaterial, true);
                // Render remaining windows (dialogs, popups, new windows) on top
                for (int i = 0; i < renderableWindows.size() - 1; i++) {
                    RenderableWindow window = renderableWindows.get(i);
                    renderDrawable(window.content, window.rootX, window.rootY, windowMaterial, window.forceFullscreen);
                }
            } else {
                for (RenderableWindow window : renderableWindows) {
                    // renderDrawable only applies GPU scaling when the window has
                    // actually grown to cover >= DIRECT_MODE_COVERAGE_THRESHOLD.
                    renderDrawable(window.content, window.rootX, window.rootY, windowMaterial, window.forceFullscreen);
                }
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

    private void updateScene() {
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
                if (forceFullscreenWMClass != null) {
                    short width = window.getWidth();
                    short height = window.getHeight();
                    boolean forceFullscreen= false;

                    if (width >= 320 && height >= 200 && width < xServer.screenInfo.width && height < xServer.screenInfo.height) {
                        Window parent = window.getParent();
                        boolean parentHasWMClass = parent.getClassName().contains(forceFullscreenWMClass);
                        boolean hasWMClass = window.getClassName().contains(forceFullscreenWMClass);
                        if (hasWMClass) {
                            forceFullscreen = !parentHasWMClass && window.getChildCount() == 0;
                        }
                        else {
                            short borderX = (short)(parent.getWidth() - width);
                            short borderY = (short)(parent.getHeight() - height);
                            if (parent.getChildCount() == 1 && borderX > 0 && borderY > 0 && borderX <= 12) {
                                forceFullscreen = true;
                                removeRenderableWindow(parent);
                            }
                        }
                    }

                    renderableWindows.add(new RenderableWindow(window.getContent(), x, y, forceFullscreen));
                }
                else renderableWindows.add(new RenderableWindow(window.getContent(), x, y));
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
     * Enables or disables CPU saver (Direct Rendering+) mode.
     * When enabled, the renderer bypasses full compositing for large windows,
     * significantly reducing GPU and CPU overhead.
     */
    public void setNativeMode(boolean enabled) {
        if (cpuSaverMode == enabled) return;
        cpuSaverMode = enabled;
        viewportNeedsUpdate = true;

        // Explicitly pause/resume effects so the effectComposer is not
        // processing shaders in the background while direct rendering is active.
//        if (enabled) {
//            effectComposer.setEnabled(false);
//        } else {
//            effectComposer.setEnabled(true);
//        }

        String message = enabled ? "Direct Rendering+ Enabled" : "Direct Rendering+ Disabled";
        xServerView.post(() -> Toast.makeText(xServerView.getContext(), message, Toast.LENGTH_SHORT).show());
        xServerView.setRenderMode(GLSurfaceView.RENDERMODE_WHEN_DIRTY);
        xServerView.requestRender();
    }

    public boolean isNativeMode() {
        return cpuSaverMode;
    }

    /**
     * Remaps a screen-space pointer coordinate (from touch/mouse input) to
     * the actual window coordinate space when forceFullscreen is active.
     *
     * When a window is rendered force-fullscreen it is visually scaled up and
     * centered on screen. Without this remapping the X server receives raw screen
     * coordinates that don't match the smaller logical window, causing the cursor
     * hit area to be misaligned.
     *
     * Call this before forwarding pointer events to the X server:
     *   float[] mapped = renderer.mapPointerCoords(rawX, rawY);
     *   xServer.pointer.setPosition((short) mapped[0], (short) mapped[1]);
     *
     * @param screenX Raw screen X from touch/mouse input
     * @param screenY Raw screen Y from touch/mouse input
     * @return float[2] with the remapped {windowX, windowY}
     */
    public float[] mapPointerCoords(float screenX, float screenY) {
        if (!isForceFullscreenActive || forceFullscreenScaleX == 0 || forceFullscreenScaleY == 0) {
            return new float[]{screenX, screenY};
        }
        float windowX = (screenX - forceFullscreenOffsetX) / forceFullscreenScaleX;
        float windowY = (screenY - forceFullscreenOffsetY) / forceFullscreenScaleY;
        return new float[]{windowX, windowY};
    }

    public boolean isForceFullscreenActive() {
        return isForceFullscreenActive;
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
}

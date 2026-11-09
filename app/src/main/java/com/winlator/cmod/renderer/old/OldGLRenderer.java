package com.winlator.cmod.renderer.old;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.opengl.GLES20;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Surface;
import android.widget.Toast;

import com.winlator.cmod.R;
import com.winlator.cmod.math.Mathf;
import com.winlator.cmod.math.XForm;
import com.winlator.cmod.renderer.ViewTransformation;
import com.winlator.cmod.xserver.Atom;
import com.winlator.cmod.xserver.Bitmask;
import com.winlator.cmod.xserver.Cursor;
import com.winlator.cmod.xserver.Drawable;
import com.winlator.cmod.xserver.Pointer;
import com.winlator.cmod.xserver.Property;
import com.winlator.cmod.xserver.Window;
import com.winlator.cmod.xserver.WindowAttributes;
import com.winlator.cmod.xserver.WindowManager;
import com.winlator.cmod.xserver.XLock;
import com.winlator.cmod.xserver.XServer;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Renderer "Old Display": port logika render display GLRenderer dari Winlator-Ludashi experimental-v2
 * ke 4.1. Alur render (updateScene -> collectRenderableWindows -> renderWindows -> renderCursor),
 * perilaku scissor/viewport, magnifier, serta Force Fullscreen per-window dipertahankan sama.
 * Perbedaan terhadap v2 hanya pada sumber piksel (Drawable 4.1 berbasis AHardwareBuffer) dan
 * penanganan direct content DRI3.
 */
public class OldGLRenderer implements OldGLThread.Client,
        WindowManager.OnWindowModificationListener, Pointer.OnPointerMotionListener {
    private static final String TAG = "OldGLRenderer";

    /** Dipanggil saat sebuah window mendapat frame baru (untuk HUD FPS). */
    public interface FrameListener {
        void onWindowFrame(int windowId);
    }

    private static final class RenderableWindow {
        final Window window;
        final Drawable content;
        short rootX;
        short rootY;
        final boolean forceFullscreen;

        RenderableWindow(Window window, int rootX, int rootY, boolean forceFullscreen) {
            this.window = window;
            this.content = window.getContent();
            this.rootX = (short) rootX;
            this.rootY = (short) rootY;
            this.forceFullscreen = forceFullscreen;
        }
    }

    private static final class DirectEntry {
        Drawable current;
        final ArrayList<Drawable> known = new ArrayList<>();
    }

    private final Context context;
    private final XServer xServer;
    private final OldGLThread glThread;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    public final ViewTransformation viewTransformation = new ViewTransformation();
    private final OldVertexAttribute quadVertices = new OldVertexAttribute("position", 2);
    private final float[] tmpXForm1 = XForm.getInstance();
    private final float[] tmpXForm2 = XForm.getInstance();
    private final OldShaderMaterial.CursorMaterial cursorMaterial = new OldShaderMaterial.CursorMaterial();
    private final OldShaderMaterial.WindowMaterial windowMaterial = new OldShaderMaterial.WindowMaterial();
    private final OldFullscreenTransformation tmpFullscreenTransformation = new OldFullscreenTransformation();
    private final ArrayList<RenderableWindow> renderableWindows = new ArrayList<>();

    // Tekstur: hanya disentuh dari GL thread.
    private final Map<Drawable, OldTexture> contentTextures = new IdentityHashMap<>();
    private final Map<Drawable, OldTexture> directTextures = new IdentityHashMap<>();
    private final Set<Drawable> dirtyContent =
            Collections.synchronizedSet(Collections.newSetFromMap(new IdentityHashMap<Drawable, Boolean>()));

    // Direct content (DRI3): ditulis dari thread X server, dibaca dari GL thread.
    private final Object directLock = new Object();
    private final HashMap<Integer, DirectEntry> directByWindow = new HashMap<>();

    private Drawable rootCursorDrawable;

    private volatile String forceFullscreenWMClass = null;
    private volatile boolean fullscreen = false;
    private volatile boolean toggleFullscreen = false;
    private volatile boolean viewportNeedsUpdate = true;
    private volatile boolean cursorVisible = true;
    private volatile boolean screenOffsetYRelativeToCursor = false;
    private volatile String[] unviewableWMClasses = null;
    private volatile float magnifierZoom = 1.0f;
    private final boolean magnifierEnabled = true;
    private volatile boolean nativeMode = false;
    private volatile FrameListener frameListener;
    private volatile int fpsWindowId = -1;

    private int surfaceWidth;
    private int surfaceHeight;

    public OldGLRenderer(Context context, XServer xServer) {
        this.context = context;
        this.xServer = xServer;
        this.glThread = new OldGLThread(this);

        quadVertices.put(new float[]{
                0.0f, 0.0f,
                0.0f, 1.0f,
                1.0f, 0.0f,
                1.0f, 1.0f
        });

        xServer.windowManager.addOnWindowModificationListener(this);
        xServer.pointer.addOnPointerMotionListener(this);
    }

    // ---------------------------------------------------------------- lifecycle

    public void start() {
        if (!glThread.isAlive()) glThread.start();
    }

    public void stop() {
        xServer.windowManager.removeOnWindowModificationListener(this);
        xServer.pointer.removeOnPointerMotionListener(this);
        glThread.requestExit();
    }

    public void onPause() {
        glThread.onPause();
    }

    public void onResume() {
        glThread.onResume();
    }

    public void onSurfaceCreated(Surface surface, int width, int height) {
        glThread.setSurface(surface, width, height);
    }

    public void onSurfaceSizeChanged(int width, int height) {
        glThread.updateSurfaceSize(width, height);
    }

    public void onSurfaceDestroyed() {
        glThread.clearSurface();
    }

    public void requestRender() {
        glThread.requestRender();
    }

    public void queueEvent(Runnable r) {
        glThread.queueEvent(r);
    }

    // ---------------------------------------------------------------- GL callbacks

    @Override
    public void onGLContextCreated() {
        GLES20.glFrontFace(GLES20.GL_CCW);
        GLES20.glDisable(GLES20.GL_CULL_FACE);

        GLES20.glDisable(GLES20.GL_DEPTH_TEST);
        GLES20.glDepthMask(false);

        GLES20.glEnable(GLES20.GL_BLEND);
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA);
        GLES20.glClearColor(0.0f, 0.0f, 0.0f, 0.0f);

        if (rootCursorDrawable == null) rootCursorDrawable = createRootCursorDrawable();
    }

    @Override
    public void onGLSurfaceChanged(int width, int height) {
        // Surface baru berarti state GL (blend, clear color) harus dipastikan ulang.
        GLES20.glEnable(GLES20.GL_BLEND);
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA);
        GLES20.glClearColor(0.0f, 0.0f, 0.0f, 0.0f);

        surfaceWidth = width;
        surfaceHeight = height;
        viewTransformation.update(width, height, xServer.screenInfo.width, xServer.screenInfo.height);
        viewportNeedsUpdate = true;
        updateScene();
    }

    @Override
    public void onGLDrawFrame() {
        if (toggleFullscreen) {
            fullscreen = !fullscreen;
            toggleFullscreen = false;
            viewportNeedsUpdate = true;
        }
        drawFrame();
    }

    @Override
    public void onGLContextDestroyed() {
        for (OldTexture texture : contentTextures.values()) texture.destroy();
        for (OldTexture texture : directTextures.values()) texture.destroy();
        contentTextures.clear();
        directTextures.clear();
        cursorMaterial.destroy();
        windowMaterial.destroy();
        quadVertices.destroy();
    }

    // ---------------------------------------------------------------- frame

    private void drawFrame() {
        // Update viewport — selalu gunakan viewTransformation (normal),
        // fullscreen murni ditangani di applySceneTransform.
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

        renderWindows();

        if (cursorVisible) renderCursor();

        if (!magnifierEnabled && !fullscreen) {
            GLES20.glDisable(GLES20.GL_SCISSOR_TEST);
        }
    }

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

    /**
     * Gambar satu drawable dengan texture yang sudah siap.
     */
    private void drawQuad(OldTexture texture, Drawable drawable, int x, int y,
                          OldShaderMaterial material, boolean forceFullscreen) {
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

    /** Render drawable konten biasa (jalur upload CPU). */
    private void renderContent(Drawable drawable, int x, int y, OldShaderMaterial material, boolean forceFullscreen) {
        if (drawable == null) return;
        synchronized (drawable.renderLock) {
            OldTexture texture = contentTextures.get(drawable);
            if (texture == null) {
                texture = new OldTexture();
                contentTextures.put(drawable, texture);
            }

            if (dirtyContent.remove(drawable)) texture.setNeedsUpdate(true);

            if (!texture.updateFromDrawable(drawable)) {
                // Buffer sedang dikunci penulis lain; coba lagi di frame berikutnya.
                dirtyContent.add(drawable);
                if (!texture.isAllocated()) return;
            }
            if (!texture.isAllocated()) return;

            drawQuad(texture, drawable, x, y, material, forceFullscreen);
        }
    }

    /** Render drawable direct content (DRI3, impor AHardwareBuffer sebagai EGLImage). */
    private boolean renderDirect(Drawable drawable, int x, int y, OldShaderMaterial material, boolean forceFullscreen) {
        OldTexture texture = directTextures.get(drawable);
        if (texture == null) {
            texture = new OldTexture();
            if (!texture.importHardwareBuffer(drawable.backingAHB)) return false;
            directTextures.put(drawable, texture);
        }
        drawQuad(texture, drawable, x, y, material, forceFullscreen);
        return true;
    }

    private Drawable getDirectDrawable(int windowId) {
        synchronized (directLock) {
            DirectEntry entry = directByWindow.get(windowId);
            return entry != null ? entry.current : null;
        }
    }

    private void renderWindows() {
        windowMaterial.use();
        GLES20.glUniform2f(windowMaterial.getUniformLocation("viewSize"),
                xServer.screenInfo.width, xServer.screenInfo.height);
        quadVertices.bind(windowMaterial.programId);

        try (XLock lock = xServer.lock(XServer.Lockable.DRAWABLE_MANAGER)) {
            if (renderableWindows.isEmpty()) {
                quadVertices.disable();
                return;
            }

            boolean blendEnabled = true; // blend aktif secara default dari onGLContextCreated

            for (RenderableWindow rw : renderableWindows) {
                boolean fs = rw.forceFullscreen;

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

                Drawable direct = getDirectDrawable(rw.window.id);
                if (direct != null && renderDirect(direct, rw.rootX, rw.rootY, windowMaterial, fs)) continue;

                renderContent(rw.content, rw.rootX, rw.rootY, windowMaterial, fs);
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
            Log.e(TAG, "OpenGL Error: " + error);
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
                renderContent(rootCursorDrawable, x, y, cursorMaterial, false);
            } else if (cursor.isVisible()) {
                // Cursor normal → render cursor X11 milik window.
                renderContent(cursor.cursorImage, x - cursor.hotSpotX, y - cursor.hotSpotY, cursorMaterial, false);
            }
            // else: cursor ada tapi mask-nya kosong/transparan (visible == false) → tidak render.
        }

        quadVertices.disable();
    }

    private Drawable createRootCursorDrawable() {
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inScaled = false;
        Bitmap bitmap = BitmapFactory.decodeResource(context.getResources(), R.drawable.cursor, options);
        return Drawable.fromBitmap(bitmap);
    }

    // ---------------------------------------------------------------- scene

    public void updateScene() {
        try (XLock lock = xServer.lock(XServer.Lockable.WINDOW_MANAGER, XServer.Lockable.DRAWABLE_MANAGER)) {
            renderableWindows.clear();
            collectRenderableWindows(xServer.windowManager.rootWindow,
                    xServer.windowManager.rootWindow.getX(), xServer.windowManager.rootWindow.getY());
        }
    }

    private boolean isMaximized(Window window) {
        Property property = window.getProperty(Atom.getId("_NET_WM_STATE"));
        if (property == null || property.data == null) return false;

        int maxVert = Atom.getId("_NET_WM_STATE_MAXIMIZED_VERT");
        int maxHorz = Atom.getId("_NET_WM_STATE_MAXIMIZED_HORZ");
        int fullscr = Atom.getId("_NET_WM_STATE_FULLSCREEN");

        int count = property.data.capacity() / 4;
        for (int i = 0; i < count; i++) {
            int atomId = property.getInt(i);
            if (atomId == maxVert || atomId == maxHorz || atomId == fullscr) return true;
        }
        return false;
    }

    private void collectRenderableWindows(Window window, int x, int y) {
        if (!window.attributes.isMapped()) return;

        if (window != xServer.windowManager.rootWindow) {
            boolean viewable = true;

            String[] unviewable = unviewableWMClasses;
            if (unviewable != null) {
                String wmClass = window.getClassName();
                for (String unviewableWMClass : unviewable) {
                    if (wmClass.contains(unviewableWMClass)) {
                        if (window.attributes.isEnabled()) window.disableAllDescendants();
                        viewable = false;
                        break;
                    }
                }
            }

            if (viewable) {
                boolean forceFullscreen = false;
                String forceClass = forceFullscreenWMClass;

                if (forceClass != null) {
                    // ForceFullscreen aktif — evaluasi apakah window ini perlu FullscreenTransformation.
                    short width = window.getWidth();
                    short height = window.getHeight();
                    float screenW = xServer.screenInfo.width;
                    float screenH = xServer.screenInfo.height;

                    // Deteksi maximize via _NET_WM_STATE (hanya leaf window).
                    boolean isMaximized = window.getChildCount() == 0 && isMaximized(window);

                    // Deteksi window besar: ≥75% lebar DAN tinggi layar.
                    boolean isLargeWindow = (width >= screenW * 0.75f) && (height >= screenH * 0.75f);

                    if (isMaximized || isLargeWindow) {
                        Window parent = window.getParent();
                        boolean hasWMClass = window.getClassName().contains(forceClass);
                        boolean parentHasWMClass = parent != null && parent.getClassName().contains(forceClass);

                        if (hasWMClass) {
                            // WMClass cocok — fullscreen jika parent tidak cocok dan ini leaf window.
                            forceFullscreen = !parentHasWMClass && window.getChildCount() == 0;
                        } else if (isMaximized) {
                            // Maximize via _NET_WM_STATE, WMClass tidak cocok.
                            // Sembunyikan parent (title bar) dan render fullscreen.
                            forceFullscreen = true;
                            removeRenderableWindow(parent);
                        } else if (parent != null) {
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

                renderableWindows.add(new RenderableWindow(window, x, y, forceFullscreen));
            }
        }

        for (Window child : window.getChildren()) {
            collectRenderableWindows(child, child.getX() + x, child.getY() + y);
        }
    }

    private void removeRenderableWindow(Window window) {
        if (window == null) return;
        for (int i = 0; i < renderableWindows.size(); i++) {
            if (renderableWindows.get(i).window == window) {
                renderableWindows.remove(i);
                break;
            }
        }
    }

    private void updateWindowPosition(Window window) {
        for (RenderableWindow renderableWindow : renderableWindows) {
            if (renderableWindow.window == window) {
                renderableWindow.rootX = window.getRootX();
                renderableWindow.rootY = window.getRootY();
                break;
            }
        }
    }

    // ---------------------------------------------------------------- window listener

    @Override
    public void onMapWindow(Window window) {
        queueEvent(this::updateScene);
        requestRender();
    }

    @Override
    public void onUnmapWindow(Window window) {
        queueEvent(this::updateScene);
        requestRender();
    }

    @Override
    public void onDestroyWindow(Window window) {
        final Drawable content = window.getContent();
        final int windowId = window.id;
        queueEvent(() -> {
            if (content != null) {
                OldTexture texture = contentTextures.remove(content);
                if (texture != null) texture.destroy();
                dirtyContent.remove(content);
            }
            DirectEntry entry;
            synchronized (directLock) {
                entry = directByWindow.remove(windowId);
            }
            if (entry != null) {
                for (Drawable known : entry.known) {
                    OldTexture texture = directTextures.remove(known);
                    if (texture != null) texture.destroy();
                }
            }
            updateScene();
        });
        requestRender();
    }

    @Override
    public void onChangeWindowZOrder(Window.StackMode stackMode, Window window, Window sibling) {
        queueEvent(this::updateScene);
        requestRender();
    }

    @Override
    public void onReparentWindow(Window window, Window newParent, short x, short y) {
        queueEvent(this::updateScene);
        requestRender();
    }

    @Override
    public void onUpdateWindowContent(Window window) {
        Drawable content = window.getContent();
        if (content != null) dirtyContent.add(content);
        reportFrame(window.id);
        requestRender();
    }

    @Override
    public void onUpdateWindowContentDirect(Window window, Drawable drawable) {
        synchronized (directLock) {
            DirectEntry entry = directByWindow.get(window.id);
            if (entry == null) {
                entry = new DirectEntry();
                directByWindow.put(window.id, entry);
            }
            if (!entry.known.contains(drawable)) entry.known.add(drawable);
            entry.current = drawable;
        }
        reportFrame(window.id);
        requestRender();
    }

    @Override
    public void onUpdateWindowGeometry(final Window window, boolean resized) {
        if (resized) {
            queueEvent(this::updateScene);
        } else {
            queueEvent(() -> updateWindowPosition(window));
            queueEvent(this::updateScene);
        }
        requestRender();
    }

    @Override
    public void onUpdateWindowAttributes(Window window, Bitmask mask) {
        if (mask.isSet(WindowAttributes.FLAG_CURSOR)) requestRender();
    }

    /**
     * Jika _NET_WM_STATE berubah (maximize/fullscreen), trigger updateScene
     * agar collectRenderableWindows mengevaluasi ulang forceFullscreen.
     */
    @Override
    public void onModifyWindowProperty(Window window, Property property) {
        if (property != null && "_NET_WM_STATE".equals(Atom.getName(property.name))) {
            queueEvent(this::updateScene);
            requestRender();
        }
    }

    @Override
    public void onPointerMove(short x, short y) {
        requestRender();
    }

    private void reportFrame(int windowId) {
        FrameListener listener = frameListener;
        if (listener != null && windowId == fpsWindowId) listener.onWindowFrame(windowId);
    }

    // ---------------------------------------------------------------- direct content API

    public void addDirectContent(int windowId, Drawable drawable) {
        synchronized (directLock) {
            DirectEntry entry = directByWindow.get(windowId);
            if (entry == null) {
                entry = new DirectEntry();
                directByWindow.put(windowId, entry);
            }
            if (!entry.known.contains(drawable)) entry.known.add(drawable);
            if (entry.current == null) entry.current = drawable;
        }
        requestRender();
    }

    public void removeDirectContent(final int windowId, final int pixmapId) {
        queueEvent(() -> {
            synchronized (directLock) {
                DirectEntry entry = directByWindow.get(windowId);
                if (entry != null) {
                    for (int i = entry.known.size() - 1; i >= 0; i--) {
                        Drawable known = entry.known.get(i);
                        if (known.id == pixmapId) {
                            OldTexture texture = directTextures.remove(known);
                            if (texture != null) texture.destroy();
                            entry.known.remove(i);
                            if (entry.current == known) entry.current = null;
                        }
                    }
                    if (entry.known.isEmpty()) directByWindow.remove(windowId);
                }
            }
        });
        requestRender();
    }

    // ---------------------------------------------------------------- public api

    public void toggleFullscreen() {
        toggleFullscreen = true;
        requestRender();
    }

    public boolean isFullscreen() {
        return fullscreen;
    }

    public void setCursorVisible(boolean cursorVisible) {
        this.cursorVisible = cursorVisible;
        requestRender();
    }

    public boolean isCursorVisible() {
        return cursorVisible;
    }

    public void setScreenOffsetYRelativeToCursor(boolean screenOffsetYRelativeToCursor) {
        this.screenOffsetYRelativeToCursor = screenOffsetYRelativeToCursor;
        requestRender();
    }

    public float getMagnifierZoom() {
        return magnifierZoom;
    }

    public void setMagnifierZoom(float magnifierZoom) {
        this.magnifierZoom = magnifierZoom;
        requestRender();
    }

    public String getForceFullscreenWMClass() {
        return forceFullscreenWMClass;
    }

    public void setForceFullscreenWMClass(String forceFullscreenWMClass) {
        this.forceFullscreenWMClass = forceFullscreenWMClass;
        queueEvent(this::updateScene);
        requestRender();
    }

    public void setUnviewableWMClasses(String... unviewableWMNames) {
        this.unviewableWMClasses = unviewableWMNames;
    }

    public void setFpsWindowId(int windowId) {
        this.fpsWindowId = windowId;
    }

    public void setFrameListener(FrameListener listener) {
        this.frameListener = listener;
    }

    /**
     * Mengaktifkan/menonaktifkan Direct Rendering(+) seperti setNativeMode() pada v2.
     * Window selalu dirender langsung (tanpa EffectComposer), dan forceFullscreen per-window
     * tetap ditentukan oleh collectRenderableWindows.
     */
    public void setNativeMode(boolean enabled) {
        if (nativeMode == enabled) return;
        nativeMode = enabled;
        viewportNeedsUpdate = true;

        final String message = enabled ? "Direct Rendering+ Enabled" : "Direct Rendering+ Disabled";
        mainHandler.post(() -> Toast.makeText(context, message, Toast.LENGTH_SHORT).show());
        requestRender();
    }

    public boolean isNativeMode() {
        return nativeMode;
    }

    public int getSurfaceWidth() {
        return surfaceWidth;
    }

    public int getSurfaceHeight() {
        return surfaceHeight;
    }
}

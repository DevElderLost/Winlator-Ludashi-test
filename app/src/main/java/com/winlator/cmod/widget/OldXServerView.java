package com.winlator.cmod.widget;

import android.annotation.SuppressLint;
import android.content.Context;
import android.util.Log;
import android.view.SurfaceHolder;

import com.winlator.cmod.renderer.GPUImage;
import com.winlator.cmod.renderer.old.OldGLRenderer;
import com.winlator.cmod.xserver.Drawable;
import com.winlator.cmod.xserver.Window;
import com.winlator.cmod.xserver.XServer;

/**
 * Renderer "Old Display": memakai logika render display experimental-v2 (GLES20 di Java,
 * upload tekstur per window) sebagai pilihan renderer tambahan di samping Vulkan, EGL, dan DisplayX.
 */
@SuppressLint("ViewConstructor")
public class OldXServerView extends XServerRendererView implements SurfaceHolder.Callback {
    private static final String TAG = "OldXServerView";

    private final OldGLRenderer renderer;
    private WinlatorHUD hudRef;
    private FrameRating classicHudRef;
    private volatile int fpsLimit;
    private volatile boolean destroyed;
    private boolean warnedComposite;

    public OldXServerView(Context context, XServer xServer) {
        super(context, xServer);
        xServer.setXServerView(this);

        renderer = new OldGLRenderer(context, xServer);
        renderer.setFrameListener(windowId -> {
            if (hudRef != null) hudRef.onFrame();
            if (classicHudRef != null) classicHudRef.update();
        });
        getHolder().addCallback(this);
        renderer.start();
    }

    public OldGLRenderer getRenderer() {
        return renderer;
    }

    // ---------------------------------------------------------------- SurfaceHolder.Callback

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        // Ukuran baru diketahui di surfaceChanged().
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        if (destroyed) return;
        renderer.onSurfaceCreated(holder.getSurface(), width, height);
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        if (destroyed) return;
        renderer.onSurfaceDestroyed();
    }

    // ---------------------------------------------------------------- XServerRendererView

    @Override
    public void onPause() {
        if (!destroyed) renderer.onPause();
    }

    @Override
    public void onResume() {
        if (!destroyed) renderer.onResume();
    }

    @Override
    public void onDestroy() {
        if (destroyed) return;
        destroyed = true;
        getHolder().removeCallback(this);
        renderer.stop();
    }

    @Override
    public void toggleFullscreen() {
        renderer.toggleFullscreen();
    }

    @Override
    public boolean isFullscreen() {
        return renderer.isFullscreen();
    }

    @Override
    public void setCursorVisible(boolean visible) {
        renderer.setCursorVisible(visible);
    }

    @Override
    public void setScreenOffsetYRelativeToCursor(boolean relative) {
        renderer.setScreenOffsetYRelativeToCursor(relative);
    }

    @Override
    public void setMagnifierZoom(float zoom) {
        renderer.setMagnifierZoom(zoom);
    }

    @Override
    public float getMagnifierZoom() {
        return renderer.getMagnifierZoom();
    }

    @Override
    public void setUnviewableWMClasses(String... classes) {
        renderer.setUnviewableWMClasses(classes);
    }

    @Override
    public void addDirectContent(int windowId, Drawable drawable, GPUImage gpuImage) {
        renderer.addDirectContent(windowId, drawable);
    }

    @Override
    public void nativeRemoveDirectContent(int windowId, int pixmapId) {
        renderer.removeDirectContent(windowId, pixmapId);
    }

    @Override
    public void nativeSetCompositeRedirected(int windowId, boolean redirected) {
        // Composite redirect tidak didukung renderer lama; window tetap dirender langsung.
        if (!warnedComposite) {
            warnedComposite = true;
            Log.w(TAG, "Composite redirect is not supported by Old Display renderer");
        }
    }

    @Override
    public void nativeCompositeRedirect(int srcDrawableId, int dstDrawableId, short dstX, short dstY) {
        if (!warnedComposite) {
            warnedComposite = true;
            Log.w(TAG, "Composite redirect is not supported by Old Display renderer");
        }
    }

    @Override
    public void onUpdateWindowContentDirect(Window window, Drawable drawable) {
        renderer.onUpdateWindowContentDirect(window, drawable);
    }

    @Override
    public void onUpdateWindowContentDirect(Window window, Drawable drawable, short xOff, short yOff) {
        renderer.onUpdateWindowContentDirect(window, drawable);
    }

    @Override
    public void onPointerMove(short x, short y) {
        renderer.requestRender();
    }

    @Override
    public void requestRender() {
        renderer.requestRender();
    }

    @Override
    public void queueEvent(Runnable action) {
        if (action != null) renderer.queueEvent(action);
    }

    @Override
    public void onSurfaceChanged(int width, int height) {
        renderer.onSurfaceSizeChanged(width, height);
    }

    @Override
    public void forceCleanup() {
        onDestroy();
    }

    @Override
    public void setFpsWindowId(int windowId) {
        renderer.setFpsWindowId(windowId);
    }

    @Override
    public void setPipMode(boolean pipMode) {
        // Tidak ada perilaku khusus pada renderer lama.
    }

    @Override
    public void setFrameRating(Object frameRating) {
        hudRef = frameRating instanceof WinlatorHUD ? (WinlatorHUD) frameRating : null;
        classicHudRef = frameRating instanceof FrameRating ? (FrameRating) frameRating : null;
    }

    @Override
    public void setFpsLimit(int fps) {
        fpsLimit = Math.max(0, Math.min(1000, fps));
    }

    @Override
    public int getFpsLimit() {
        return fpsLimit;
    }
}

package com.winlator.cmod.renderer.old;

import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLSurface;
import android.util.Log;
import android.view.Surface;

import java.util.ArrayList;

/**
 * Thread GL sederhana (EGL14) yang meniru perilaku GLSurfaceView dengan
 * RENDERMODE_WHEN_DIRTY + setPreserveEGLContextOnPause(true) seperti yang dipakai
 * XServerView pada experimental-v2. Dipakai oleh OldXServerView karena base class
 * XServerRendererView di versi 4.1 adalah SurfaceView biasa (bukan GLSurfaceView).
 */
public final class OldGLThread extends Thread {
    private static final String TAG = "OldGLThread";
    private static final int EGL_OPENGL_ES3_BIT_KHR = 0x0040;

    public interface Client {
        /** Dipanggil di GL thread sekali setelah context pertama kali dibuat. */
        void onGLContextCreated();

        /** Dipanggil di GL thread setiap EGLSurface (re)create / ukuran berubah. */
        void onGLSurfaceChanged(int width, int height);

        /** Dipanggil di GL thread setiap ada frame yang diminta. */
        void onGLDrawFrame();

        /** Dipanggil di GL thread tepat sebelum context dihancurkan. */
        void onGLContextDestroyed();
    }

    private final Object lock = new Object();
    private final Client client;
    private final ArrayList<Runnable> events = new ArrayList<>();

    // State yang dilindungi lock
    private Surface surface;
    private int width;
    private int height;
    private boolean surfaceDirty;
    private boolean surfaceLost;
    private boolean paused;
    private boolean quit;
    private boolean renderRequested;

    // State milik GL thread
    private EGLDisplay eglDisplay = EGL14.EGL_NO_DISPLAY;
    private EGLContext eglContext = EGL14.EGL_NO_CONTEXT;
    private EGLSurface eglSurface = EGL14.EGL_NO_SURFACE;
    private EGLConfig eglConfig;
    private boolean contextCreatedNotified;

    public OldGLThread(Client client) {
        super("OldGLThread");
        this.client = client;
    }

    public void setSurface(Surface surface, int width, int height) {
        synchronized (lock) {
            this.surface = surface;
            this.width = width;
            this.height = height;
            this.surfaceDirty = true;
            this.surfaceLost = false;
            lock.notifyAll();
        }
    }

    public void updateSurfaceSize(int width, int height) {
        synchronized (lock) {
            if (this.width == width && this.height == height) return;
            this.width = width;
            this.height = height;
            this.surfaceDirty = true;
            lock.notifyAll();
        }
    }

    public void clearSurface() {
        synchronized (lock) {
            this.surface = null;
            this.surfaceLost = true;
            lock.notifyAll();
            // Tunggu sampai GL thread selesai melepas EGLSurface agar Surface aman dihancurkan.
            while (!quit && eglSurface != EGL14.EGL_NO_SURFACE && isAlive()) {
                try {
                    lock.wait(200);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
    }

    public void requestRender() {
        synchronized (lock) {
            renderRequested = true;
            lock.notifyAll();
        }
    }

    public void queueEvent(Runnable r) {
        if (r == null) return;
        synchronized (lock) {
            events.add(r);
            lock.notifyAll();
        }
    }

    public void onPause() {
        synchronized (lock) {
            paused = true;
            lock.notifyAll();
        }
    }

    public void onResume() {
        synchronized (lock) {
            paused = false;
            renderRequested = true;
            lock.notifyAll();
        }
    }

    public void requestExit() {
        synchronized (lock) {
            quit = true;
            lock.notifyAll();
        }
        if (Thread.currentThread() != this) {
            try {
                join(1500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    @Override
    public void run() {
        try {
            loop();
        } catch (Throwable t) {
            Log.e(TAG, "GL thread crashed", t);
        } finally {
            synchronized (lock) {
                quit = true;
                lock.notifyAll();
            }
            teardownEgl();
        }
    }

    private void loop() throws InterruptedException {
        if (!initEgl()) {
            Log.e(TAG, "EGL init failed");
            return;
        }

        while (true) {
            Runnable event = null;
            boolean destroySurface = false;
            boolean createSurface = false;
            boolean draw = false;
            Surface localSurface = null;
            int localW = 0;
            int localH = 0;

            synchronized (lock) {
                while (true) {
                    if (quit) return;

                    if (!events.isEmpty()) {
                        event = events.remove(0);
                        break;
                    }

                    if (surfaceLost && eglSurface != EGL14.EGL_NO_SURFACE) {
                        destroySurface = true;
                        break;
                    }

                    boolean ready = surface != null && surface.isValid() && !paused && width > 0 && height > 0;

                    if (ready && (eglSurface == EGL14.EGL_NO_SURFACE || surfaceDirty)) {
                        createSurface = true;
                        localSurface = surface;
                        localW = width;
                        localH = height;
                        surfaceDirty = false;
                        break;
                    }

                    if (ready && eglSurface != EGL14.EGL_NO_SURFACE && renderRequested) {
                        renderRequested = false;
                        draw = true;
                        break;
                    }

                    lock.wait();
                }
            }

            if (event != null) {
                makeCurrentIfPossible();
                try {
                    event.run();
                } catch (Throwable t) {
                    Log.e(TAG, "Queued event failed", t);
                }
                continue;
            }

            if (destroySurface) {
                destroyEglSurface();
                synchronized (lock) {
                    surfaceLost = false;
                    lock.notifyAll();
                }
                continue;
            }

            if (createSurface) {
                destroyEglSurface();
                int[] attribs = {EGL14.EGL_NONE};
                try {
                    eglSurface = EGL14.eglCreateWindowSurface(eglDisplay, eglConfig, localSurface, attribs, 0);
                } catch (Throwable t) {
                    Log.e(TAG, "eglCreateWindowSurface failed", t);
                    eglSurface = EGL14.EGL_NO_SURFACE;
                }
                if (eglSurface == null || eglSurface == EGL14.EGL_NO_SURFACE) {
                    eglSurface = EGL14.EGL_NO_SURFACE;
                    continue;
                }
                if (!EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) {
                    Log.e(TAG, "eglMakeCurrent failed: 0x" + Integer.toHexString(EGL14.eglGetError()));
                    destroyEglSurface();
                    continue;
                }
                EGL14.eglSwapInterval(eglDisplay, 1);

                if (!contextCreatedNotified) {
                    contextCreatedNotified = true;
                    client.onGLContextCreated();
                }
                client.onGLSurfaceChanged(localW, localH);
                synchronized (lock) {
                    renderRequested = true;
                }
                continue;
            }

            if (draw) {
                if (!EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) {
                    Log.e(TAG, "eglMakeCurrent (draw) failed: 0x" + Integer.toHexString(EGL14.eglGetError()));
                    continue;
                }
                try {
                    client.onGLDrawFrame();
                } catch (Throwable t) {
                    Log.e(TAG, "onGLDrawFrame failed", t);
                }
                if (!EGL14.eglSwapBuffers(eglDisplay, eglSurface)) {
                    int err = EGL14.eglGetError();
                    if (err == EGL14.EGL_BAD_SURFACE || err == EGL14.EGL_BAD_NATIVE_WINDOW
                            || err == EGL14.EGL_CONTEXT_LOST) {
                        Log.w(TAG, "Surface lost during swap: 0x" + Integer.toHexString(err));
                        destroyEglSurface();
                        synchronized (lock) {
                            surfaceDirty = true;
                        }
                    }
                }
            }
        }
    }

    private void makeCurrentIfPossible() {
        if (eglSurface != EGL14.EGL_NO_SURFACE) {
            EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext);
        } else if (eglContext != EGL14.EGL_NO_CONTEXT) {
            // Tanpa surface: pakai context tanpa draw surface agar resource GL tetap bisa dibersihkan.
            EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, eglContext);
        }
    }

    private boolean initEgl() {
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
        if (eglDisplay == EGL14.EGL_NO_DISPLAY) return false;

        int[] version = new int[2];
        if (!EGL14.eglInitialize(eglDisplay, version, 0, version, 1)) return false;

        // Sama seperti experimental-v2: RGBA8888 tanpa depth/stencil, GLES 3 bila tersedia.
        int[] configAttribs = {
                EGL14.EGL_RED_SIZE, 8,
                EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_DEPTH_SIZE, 0,
                EGL14.EGL_STENCIL_SIZE, 0,
                EGL14.EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT_KHR,
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT,
                EGL14.EGL_NONE
        };
        EGLConfig[] configs = new EGLConfig[1];
        int[] numConfigs = new int[1];
        int clientVersion = 3;
        if (!EGL14.eglChooseConfig(eglDisplay, configAttribs, 0, configs, 0, 1, numConfigs, 0)
                || numConfigs[0] <= 0) {
            configAttribs[13] = EGL14.EGL_OPENGL_ES2_BIT;
            clientVersion = 2;
            if (!EGL14.eglChooseConfig(eglDisplay, configAttribs, 0, configs, 0, 1, numConfigs, 0)
                    || numConfigs[0] <= 0) {
                return false;
            }
        }
        eglConfig = configs[0];

        int[] contextAttribs = {EGL14.EGL_CONTEXT_CLIENT_VERSION, clientVersion, EGL14.EGL_NONE};
        eglContext = EGL14.eglCreateContext(eglDisplay, eglConfig, EGL14.EGL_NO_CONTEXT, contextAttribs, 0);
        if ((eglContext == null || eglContext == EGL14.EGL_NO_CONTEXT) && clientVersion == 3) {
            contextAttribs[1] = 2;
            eglContext = EGL14.eglCreateContext(eglDisplay, eglConfig, EGL14.EGL_NO_CONTEXT, contextAttribs, 0);
        }
        return eglContext != null && eglContext != EGL14.EGL_NO_CONTEXT;
    }

    private void destroyEglSurface() {
        if (eglSurface != EGL14.EGL_NO_SURFACE) {
            EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, eglContext);
            EGL14.eglDestroySurface(eglDisplay, eglSurface);
            eglSurface = EGL14.EGL_NO_SURFACE;
        }
    }

    private void teardownEgl() {
        try {
            if (eglContext != EGL14.EGL_NO_CONTEXT) {
                makeCurrentIfPossible();
                try {
                    client.onGLContextDestroyed();
                } catch (Throwable t) {
                    Log.e(TAG, "onGLContextDestroyed failed", t);
                }
            }
            destroyEglSurface();
            if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
                EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
                if (eglContext != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(eglDisplay, eglContext);
                // Display default sengaja tidak di-terminate: dipakai bersama oleh komponen EGL lain.
            }
        } catch (Throwable t) {
            Log.e(TAG, "teardownEgl failed", t);
        } finally {
            eglContext = EGL14.EGL_NO_CONTEXT;
            eglDisplay = EGL14.EGL_NO_DISPLAY;
            synchronized (lock) {
                lock.notifyAll();
            }
        }
    }
}

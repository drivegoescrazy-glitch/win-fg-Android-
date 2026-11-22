package com.winfg.launcher;

import android.hardware.HardwareBuffer;
import android.util.Log;
import android.view.Surface;

/** JNI bridge to the no-root Vulkan capture/overlay renderer. */
public final class OverlayRenderer {
    private static final String TAG = "win-fg-overlay";

    /** A failed load must not throw out of a static initializer (that kills the app). */
    private static final boolean LOADED = loadLibrary();

    private static boolean loadLibrary() {
        try {
            System.loadLibrary("winfg_overlay");
            return true;
        } catch (Throwable t) {
            Log.e(TAG, "libwinfg_overlay.so could not be loaded", t);
            return false;
        }
    }

    public static boolean available() {
        return LOADED;
    }

    private long handle;

    public boolean start(Surface surface, int width, int height, AppSettings s) {
        stop();
        if (!LOADED) return false;
        try {
            handle = nativeCreate(surface, width, height,
                    s.model, s.multiplier, s.perfPreset, s.flowScale, s.gmMode, s.frMode,
                    s.frIters, s.frLambda, s.frDt, s.frEdge, s.frEps, s.extraImages, s.pacing);
        } catch (Throwable t) {
            Log.e(TAG, "nativeCreate failed", t);
            handle = 0;
        }
        return handle != 0;
    }

    public void submit(HardwareBuffer buffer, long timestampNs) {
        long h = handle;
        if (!LOADED || h == 0 || buffer == null) return;
        try {
            nativeSubmit(h, buffer, timestampNs);
        } catch (Throwable t) {
            Log.w(TAG, "nativeSubmit failed", t);
        }
    }

    public void stop() {
        long h = handle;
        handle = 0;
        if (!LOADED || h == 0) return;
        try {
            nativeDestroy(h);
        } catch (Throwable t) {
            Log.w(TAG, "nativeDestroy failed", t);
        }
    }

    private static native long nativeCreate(Surface surface, int width, int height,
                                            int model, int multiplier, int perfPreset,
                                            float flowScale, int gmMode, int frMode,
                                            int frIters, float frLambda, float frDt,
                                            float frEdge, float frEps, int extraImages,
                                            boolean pacing);

    private static native void nativeSubmit(long handle, HardwareBuffer buffer, long timestampNs);

    private static native void nativeDestroy(long handle);
}

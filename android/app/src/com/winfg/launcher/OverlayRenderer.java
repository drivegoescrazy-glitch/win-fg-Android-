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

    public boolean start(Surface surface, int width, int height, float refreshHz,
                         boolean frameGen, String crashDir, AppSettings s) {
        stop();
        if (!LOADED) return false;
        try {
            handle = nativeCreate(surface, width, height, refreshHz, frameGen, crashDir,
                    s.model, s.multiplier, s.perfPreset, s.flowScale, s.gmMode, s.frMode,
                    s.frIters, s.frLambda, s.frDt, s.frEdge, s.frEps, s.extraImages, s.pacing,
                    s.synthMode);
        } catch (Throwable t) {
            Log.e(TAG, "nativeCreate failed", t);
            handle = 0;
        }
        return handle != 0;
    }

    /** @return 1 when the frame was presented, 0 when skipped, -1 when the swapchain died. */
    public int submit(HardwareBuffer buffer, long timestampNs) {
        long h = handle;
        if (!LOADED || h == 0 || buffer == null) return 0;
        try {
            return nativeSubmit(h, buffer, timestampNs);
        } catch (Throwable t) {
            Log.w(TAG, "nativeSubmit failed", t);
            return 0;
        }
    }

    /**
     * Why generated frames are (or are not) being inserted, straight from the engine:
     * {generated, refusedFgOff, refusedFirstFrame, refusedNoSpareImage,
     *  refusedNoInterval, refusedSpareAcquire, passthrough, lastDeltaMs, lastVsyncMs}.
     * lastDeltaMs is negative when the interval was below the insert threshold.
     */
    public int[] stats() {
        long h = handle;
        if (!LOADED || h == 0) return null;
        int[] out = new int[9];
        try {
            nativeStats(h, out);
            return out;
        } catch (Throwable t) {
            return null;
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
                                            float refreshHz, boolean frameGen, String crashDir,
                                            int model, int multiplier, int perfPreset,
                                            float flowScale, int gmMode, int frMode,
                                            int frIters, float frLambda, float frDt,
                                            float frEdge, float frEps, int extraImages,
                                            boolean pacing, int synthMode);

    private static native int nativeSubmit(long handle, HardwareBuffer buffer, long timestampNs);

    private static native void nativeStats(long handle, int[] out);

    private static native void nativeDestroy(long handle);
}

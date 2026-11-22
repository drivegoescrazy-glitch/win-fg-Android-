package com.winfg.launcher;

import android.hardware.HardwareBuffer;
import android.view.Surface;

/** JNI bridge to the no-root Vulkan capture/overlay renderer. */
public final class OverlayRenderer {
    static {
        System.loadLibrary("winfg_overlay");
    }

    private long handle;

    public boolean start(Surface surface, int width, int height, AppSettings s) {
        stop();
        handle = nativeCreate(surface, width, height,
                s.model, s.multiplier, s.perfPreset, s.flowScale, s.gmMode, s.frMode,
                s.frIters, s.frLambda, s.frDt, s.frEdge, s.frEps, s.extraImages, s.pacing);
        return handle != 0;
    }

    public void submit(HardwareBuffer buffer, long timestampNs) {
        long h = handle;
        if (h != 0) nativeSubmit(h, buffer, timestampNs);
    }

    public void stop() {
        long h = handle;
        handle = 0;
        if (h != 0) nativeDestroy(h);
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

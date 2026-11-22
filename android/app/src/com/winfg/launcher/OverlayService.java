package com.winfg.launcher;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Region;
import android.graphics.SurfaceTexture;
import android.hardware.HardwareBuffer;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Choreographer;
import android.view.Gravity;
import android.view.Surface;
import android.view.SurfaceControl;
import android.view.TextureView;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.Toast;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * No-root frame-generation overlay.
 *
 * <p>Android does not let a normal app inject a Vulkan layer into another
 * process. This service uses the supported alternative: capture the display
 * with MediaProjection, run our Vulkan FrameGen on the captured frames, and
 * present the result in a full-screen overlay. The game keeps receiving input
 * because the overlay's touchable region is empty.</p>
 *
 * <p>The overlay must not appear in its own capture. A secure window is rendered
 * black by MediaProjection, so this uses the SurfaceFlinger skip-screenshot flag
 * instead (the same approach used by Android FG overlays), applied through the
 * hidden framework API when the OEM permits it.</p>
 */
public final class OverlayService extends Service {
    private static final String TAG = "win-fg-overlay";
    public static final String ACTION_START = "com.winfg.launcher.OVERLAY_START";
    public static final String ACTION_STOP = "com.winfg.launcher.OVERLAY_STOP";
    public static final String EXTRA_RESULT_CODE = "result_code";
    public static final String EXTRA_RESULT_DATA = "result_data";

    private static final String CHANNEL = "winfg_overlay";
    private static final int NOTIFICATION_ID = 73;
    private static volatile boolean running;

    private HandlerThread captureThread;
    private Handler captureHandler;
    private final Handler main = new Handler(android.os.Looper.getMainLooper());

    private WindowManager windowManager;
    private FrameLayout overlayView;
    private TextureView textureView;
    private Surface outputSurface;
    private WindowManager.LayoutParams overlayParams;
    private final Object rendererLock = new Object();
    private OverlayRenderer renderer;
    private int rendererWidth;
    private int rendererHeight;
    private AppSettings settings;

    private int resultCode;
    private Intent resultData;
    private MediaProjection projection;
    private VirtualDisplay virtualDisplay;
    private ImageReader reader;
    private boolean surfaceReady;
    private int surfaceWidth;
    private int surfaceHeight;
    private int captureWidth;
    private int captureHeight;
    private volatile boolean captureExclusionReady;
    private boolean frameLoopRunning;
    private Choreographer.FrameCallback frameCallback;

    public static boolean isRunning() {
        return running;
    }

    public static void start(Context context, int resultCode, Intent data) {
        Intent i = new Intent(context, OverlayService.class)
                .setAction(ACTION_START)
                .putExtra(EXTRA_RESULT_CODE, resultCode)
                .putExtra(EXTRA_RESULT_DATA, data);
        if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(i);
        else context.startService(i);
    }

    public static void stop(Context context) {
        context.stopService(new Intent(context, OverlayService.class));
    }

    @Override
    public void onCreate() {
        super.onCreate();
        running = true;
        settings = AppSettings.load(this);
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        captureThread = new HandlerThread("winfg-overlay-capture");
        captureThread.start();
        captureHandler = new Handler(captureThread.getLooper());
        createChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForegroundCompat();

        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }

        if (Build.VERSION.SDK_INT < 29) {
            toast("Overlay mode requires Android 10 or newer");
            stopSelf();
            return START_NOT_STICKY;
        }
        if (!Settings.canDrawOverlays(this)) {
            toast("Display-over-other-apps permission is missing");
            stopSelf();
            return START_NOT_STICKY;
        }

        if (intent != null && intent.hasExtra(EXTRA_RESULT_DATA)) {
            resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0);
            resultData = intent.getParcelableExtra(EXTRA_RESULT_DATA);
        }
        if (resultData == null) {
            toast("No screen-capture permission");
            stopSelf();
            return START_NOT_STICKY;
        }

        settings = AppSettings.load(this);
        showOverlay();
        if (surfaceReady) {
            startProjection();
        }
        return START_NOT_STICKY;
    }

    private void startForegroundCompat() {
        Notification n = notification();
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
        } else {
            startForeground(NOTIFICATION_ID, n);
        }
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm == null) return;
        NotificationChannel ch = new NotificationChannel(CHANNEL, "Win-FG overlay",
                NotificationManager.IMPORTANCE_LOW);
        ch.setDescription("Screen capture and frame generation overlay");
        nm.createNotificationChannel(ch);
    }

    private Notification notification() {
        Intent stop = new Intent(this, OverlayService.class).setAction(ACTION_STOP);
        PendingIntent pi = PendingIntent.getService(this, 2, stop,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL)
                : new Notification.Builder(this);
        return b.setSmallIcon(R.drawable.ic_foreground)
                .setContentTitle("Win-FG overlay active")
                .setContentText("Screen capture + frame generation · tap STOP to end")
                .setOngoing(true)
                .addAction(android.R.drawable.ic_menu_close_clear_cancel, "STOP", pi)
                .build();
    }

    private void showOverlay() {
        if (overlayView != null) return;

        overlayView = new FrameLayout(this);
        overlayView.setBackgroundColor(Color.TRANSPARENT);

        textureView = new TextureView(this);
        textureView.setOpaque(false);
        textureView.setKeepScreenOn(true);
        textureView.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override public void onSurfaceTextureAvailable(SurfaceTexture st, int width, int height) {
                st.setDefaultBufferSize(screenWidth(), screenHeight());
                openOutputSurface(st, width, height);
            }

            @Override public void onSurfaceTextureSizeChanged(SurfaceTexture st, int width, int height) {
                st.setDefaultBufferSize(screenWidth(), screenHeight());
                surfaceWidth = width;
                surfaceHeight = height;
                ensureRenderer(width, height);
                resizeCapture(width, height);
                installSkipScreenshot(overlayView);
            }

            @Override public boolean onSurfaceTextureDestroyed(SurfaceTexture st) {
                surfaceReady = false;
                stopFrameLoop();
                synchronized (rendererLock) {
                    if (renderer != null) {
                        renderer.stop();
                        renderer = null;
                    }
                }
                if (outputSurface != null) {
                    try { outputSurface.release(); } catch (Throwable ignored) {}
                    outputSurface = null;
                }
                return true;
            }

            @Override public void onSurfaceTextureUpdated(SurfaceTexture st) {}
        });
        overlayView.addView(textureView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        int type = Build.VERSION.SDK_INT >= 26
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;
        // Do NOT use FLAG_SECURE. Secure windows are captured as an opaque
        // black rectangle by MediaProjection, which would hide the game.
        // SurfaceControl.setSkipScreenshot() removes the layer from capture
        // entirely instead.
        int flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
                | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON;
        overlayParams = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                type, flags, PixelFormat.TRANSLUCENT);
        overlayParams.gravity = Gravity.TOP | Gravity.START;
        overlayParams.x = 0;
        overlayParams.y = 0;
        overlayParams.alpha = 1.0f;
        if (Build.VERSION.SDK_INT >= 28) {
            overlayParams.layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        }
        overlayParams.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY;
        applyExcludeFromCaptureFlag(overlayParams);

        try {
            windowManager.addView(overlayView, overlayParams);
        } catch (Throwable t) {
            Log.e(TAG, "add overlay failed", t);
            toast("Could not add overlay: " + t.getMessage());
            stopSelf();
            return;
        }
        installEmptyTouchableRegion(overlayView);
        installSkipScreenshot(overlayView);
    }

    private void openOutputSurface(SurfaceTexture st, int width, int height) {
        if (outputSurface != null) {
            try { outputSurface.release(); } catch (Throwable ignored) {}
        }
        outputSurface = new Surface(st);
        surfaceReady = outputSurface.isValid();
        surfaceWidth = width;
        surfaceHeight = height;
        ensureRenderer(width, height);
        installSkipScreenshot(overlayView);
        startFrameLoop();
        startProjection();
    }

    private int screenWidth() {
        DisplayMetrics dm = new DisplayMetrics();
        //noinspection deprecation
        windowManager.getDefaultDisplay().getRealMetrics(dm);
        return dm.widthPixels;
    }

    private int screenHeight() {
        DisplayMetrics dm = new DisplayMetrics();
        //noinspection deprecation
        windowManager.getDefaultDisplay().getRealMetrics(dm);
        return dm.heightPixels;
    }

    private void ensureRenderer(int width, int height) {
        if (width <= 0 || height <= 0 || outputSurface == null) return;
        synchronized (rendererLock) {
            if (renderer != null && width == rendererWidth && height == rendererHeight) return;
            if (renderer != null) {
                renderer.stop();
                renderer = null;
            }
            AppSettings s = AppSettings.load(this);
            OverlayRenderer r = new OverlayRenderer();
            if (!r.start(outputSurface, width, height, s)) {
                Log.e(TAG, "renderer start failed");
                toast("Vulkan overlay failed to start");
                stopSelf();
                return;
            }
            renderer = r;
            rendererWidth = width;
            rendererHeight = height;
        }
    }

    private void startProjection() {
        if (projection != null || resultData == null || !surfaceReady
                || surfaceWidth <= 0 || surfaceHeight <= 0) {
            return;
        }
        synchronized (rendererLock) {
            if (renderer == null) return;
        }
        if (!captureExclusionReady) {
            Log.e(TAG, "capture exclusion unavailable; refusing to mirror own overlay");
            toast("This device blocks overlay capture exclusion; use root layer mode instead");
            stopSelf();
            return;
        }
        try {
            MediaProjectionManager mpm =
                    (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
            if (mpm == null) {
                toast("MediaProjection unavailable");
                stopSelf();
                return;
            }
            projection = mpm.getMediaProjection(resultCode, resultData);
            if (projection == null) {
                toast("Screen-capture permission expired");
                stopSelf();
                return;
            }
            projection.registerCallback(new MediaProjection.Callback() {
                @Override public void onStop() {
                    main.post(OverlayService.this::stopSelf);
                }
            }, captureHandler);

            createReader(surfaceWidth, surfaceHeight);
            int density = getResources().getDisplayMetrics().densityDpi;
            // flags=0 is the screen-capture default. AUTO_MIRROR creates a
            // secondary logical display and, on some OEM compositors, feeds the
            // overlay back into capture instead of the target app.
            virtualDisplay = projection.createVirtualDisplay(
                    "winfg-overlay", surfaceWidth, surfaceHeight, density, 0,
                    reader.getSurface(), null, captureHandler);
            if (virtualDisplay == null) {
                toast("Could not create capture display");
                stopSelf();
            }
        } catch (Throwable t) {
            Log.e(TAG, "projection start failed", t);
            toast("Screen capture failed: " + t.getMessage());
            stopSelf();
        }
    }

    private void createReader(int width, int height) {
        ImageReader old = reader;
        if (Build.VERSION.SDK_INT >= 29) {
            reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 5,
                    HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE
                            | HardwareBuffer.USAGE_GPU_COLOR_OUTPUT);
        } else {
            reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 5);
        }
        reader.setOnImageAvailableListener(this::onImageAvailable, captureHandler);
        captureWidth = width;
        captureHeight = height;
        if (old != null) old.close();
    }

    private void onImageAvailable(ImageReader source) {
        Image image = null;
        try {
            // Preserve consecutive captures for optical flow; acquireLatestImage
            // collapses motion and makes interpolation warp.
            image = source.acquireNextImage();
            if (image == null) return;
            HardwareBuffer buffer = image.getHardwareBuffer();
            if (buffer == null) return;
            synchronized (rendererLock) {
                if (renderer != null) renderer.submit(buffer, image.getTimestamp());
            }
        } catch (Throwable t) {
            Log.w(TAG, "capture frame dropped", t);
        } finally {
            if (image != null) image.close();
        }
    }

    private void resizeCapture(int width, int height) {
        if (projection == null || width <= 0 || height <= 0) return;
        if (width == captureWidth && height == captureHeight) return;
        try {
            createReader(width, height);
            if (virtualDisplay != null) {
                virtualDisplay.setSurface(reader.getSurface());
                virtualDisplay.resize(width, height, getResources().getDisplayMetrics().densityDpi);
            }
        } catch (Throwable t) {
            Log.e(TAG, "capture resize failed", t);
        }
    }

    private void stopCapture() {
        if (virtualDisplay != null) {
            try { virtualDisplay.release(); } catch (Throwable ignored) {}
            virtualDisplay = null;
        }
        if (projection != null) {
            try { projection.stop(); } catch (Throwable ignored) {}
            projection = null;
        }
        if (reader != null) {
            try { reader.close(); } catch (Throwable ignored) {}
            reader = null;
        }
    }

    private void startFrameLoop() {
        if (frameLoopRunning || textureView == null) return;
        frameLoopRunning = true;
        frameCallback = new Choreographer.FrameCallback() {
            @Override public void doFrame(long frameTimeNanos) {
                if (!frameLoopRunning) return;
                textureView.invalidate();
                Choreographer.getInstance().postFrameCallback(this);
            }
        };
        Choreographer.getInstance().postFrameCallback(frameCallback);
    }

    private void stopFrameLoop() {
        frameLoopRunning = false;
        if (frameCallback != null) {
            Choreographer.getInstance().removeFrameCallback(frameCallback);
            frameCallback = null;
        }
    }

    /** Empty touchable region: events fall through without FLAG_NOT_TOUCHABLE's alpha clamp. */
    private void installEmptyTouchableRegion(View host) {
        try {
            Method getAsc = findMethod(View.class, "getRootSurfaceControl");
            if (getAsc != null) {
                getAsc.setAccessible(true);
                Object asc = getAsc.invoke(host);
                if (asc != null) {
                    Method m = findMethod(asc.getClass(), "setTouchableRegion", Region.class);
                    if (m != null) {
                        m.setAccessible(true);
                        m.invoke(asc, new Region());
                        Log.i(TAG, "empty touchable region installed");
                        return;
                    }
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "root SurfaceControl touchable region failed", t);
        }
        try {
            // Legacy path used by many OEMs: internal-insets touchable region.
            Class<?> listenerCls = Class.forName("android.view.ViewTreeObserver$OnComputeInternalInsetsListener");
            Class<?> infoCls = Class.forName("android.view.ViewTreeObserver$InternalInsetsInfo");
            Object proxy = java.lang.reflect.Proxy.newProxyInstance(
                    listenerCls.getClassLoader(), new Class<?>[]{listenerCls},
                    (p, method, args) -> {
                        if ("onComputeInternalInsets".equals(method.getName()) && args != null && args.length > 0) {
                            try {
                                Method mode = infoCls.getMethod("setTouchableInsets", int.class);
                                Field region = infoCls.getField("touchableRegion");
                                region.set(args[0], new Region());
                                mode.invoke(args[0], 3); // TOUCHABLE_INSETS_REGION
                            } catch (Throwable ignored) {}
                        }
                        return null;
                    });
            Method add = host.getViewTreeObserver().getClass()
                    .getMethod("addOnComputeInternalInsetsListener", listenerCls);
            add.invoke(host.getViewTreeObserver(), proxy);
            Log.i(TAG, "internal-insets touchable region installed");
            return;
        } catch (Throwable t) {
            Log.w(TAG, "empty touchable region unavailable; using FLAG_NOT_TOUCHABLE", t);
        }
        if (overlayParams != null) {
            overlayParams.flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
            try { windowManager.updateViewLayout(overlayView, overlayParams); } catch (Throwable ignored) {}
        }
    }

    /**
     * Marks the overlay's window as excluded from screenshot/VirtualDisplay
     * composition through SurfaceFlinger's eSkipScreenshot layer flag. This is
     * the supported "visible on the panel, absent from capture" mechanism;
     * FLAG_SECURE would instead compose black over the captured game.
     */
    private void installSkipScreenshot(View host) {
        Runnable apply = () -> {
            try {
                Method getVri = View.class.getDeclaredMethod("getViewRootImpl");
                getVri.setAccessible(true);
                Object vri = getVri.invoke(host);
                if (vri == null) throw new IllegalStateException("view not attached");
                SurfaceControl sc = null;
                try {
                    Method getSc = findMethod(vri.getClass(), "getSurfaceControl");
                    if (getSc != null) {
                        getSc.setAccessible(true);
                        sc = (SurfaceControl) getSc.invoke(vri);
                    }
                } catch (Throwable ignored) {}
                if (sc == null) {
                    Class<?> c = vri.getClass();
                    while (c != null && sc == null) {
                        for (Field f : c.getDeclaredFields()) {
                            if (SurfaceControl.class.isAssignableFrom(f.getType())) {
                                try {
                                    f.setAccessible(true);
                                    sc = (SurfaceControl) f.get(vri);
                                    if (sc != null) break;
                                } catch (Throwable ignored) {}
                            }
                        }
                        c = c.getSuperclass();
                    }
                }
                if (sc == null) throw new IllegalStateException("no SurfaceControl");
                SurfaceControl.Transaction txn = new SurfaceControl.Transaction();
                Method m = findMethod(txn.getClass(), "setSkipScreenshot",
                        SurfaceControl.class, boolean.class);
                if (m == null) throw new IllegalStateException("setSkipScreenshot missing");
                m.setAccessible(true);
                m.invoke(txn, sc, true);
                txn.apply();
                captureExclusionReady = true;
                Log.i(TAG, "overlay excluded from MediaProjection (skipScreenshot)");
            } catch (Throwable t) {
                Log.w(TAG, "skipScreenshot failed; overlay may feed back into capture", t);
            }
        };
        if (host.isAttachedToWindow()) apply.run();
        else host.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override public void onViewAttachedToWindow(View v) { apply.run(); }
            @Override public void onViewDetachedFromWindow(View v) {}
        });
    }

    private void applyExcludeFromCaptureFlag(WindowManager.LayoutParams params) {
        if (Build.VERSION.SDK_INT < 31) return;
        try {
            Field f = WindowManager.LayoutParams.class.getDeclaredField("privateFlags");
            f.setAccessible(true);
            int value = f.getInt(params);
            f.setInt(params, value | 0x00080000); // PRIVATE_FLAG_EXCLUDE_FROM_SCREEN_CAPTURE
            captureExclusionReady = true;
            Log.i(TAG, "privateFlags EXCLUDE_FROM_SCREEN_CAPTURE applied");
        } catch (Throwable t) {
            Log.d(TAG, "privateFlags EXCLUDE_FROM_SCREEN_CAPTURE unavailable: " + t.getMessage());
        }
    }

    private static Method findMethod(Class<?> type, String name, Class<?>... params) {
        Class<?> c = type;
        while (c != null) {
            try {
                return c.getDeclaredMethod(name, params);
            } catch (NoSuchMethodException ignored) {
                c = c.getSuperclass();
            }
        }
        return null;
    }

    private void toast(String text) {
        main.post(() -> Toast.makeText(this, text, Toast.LENGTH_LONG).show());
    }

    @Override
    public void onDestroy() {
        stopFrameLoop();
        stopCapture();
        synchronized (rendererLock) {
            if (renderer != null) {
                renderer.stop();
                renderer = null;
            }
        }
        if (outputSurface != null) {
            try { outputSurface.release(); } catch (Throwable ignored) {}
            outputSurface = null;
        }
        if (overlayView != null) {
            try { windowManager.removeView(overlayView); } catch (Throwable ignored) {}
            overlayView = null;
        }
        textureView = null;
        if (captureThread != null) {
            captureThread.quitSafely();
            captureThread = null;
        }
        running = false;
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}

package com.winfg.launcher;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ComponentName;
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
import android.view.Display;
import android.view.Gravity;
import android.view.Surface;
import android.view.SurfaceControl;
import android.view.TextureView;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.lsposed.hiddenapibypass.HiddenApiBypass;

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
    public static final String EXTRA_APP_ONLY = "app_only";
    public static final String EXTRA_TARGET_PACKAGE = "target_package";
    public static final String EXTRA_TARGET_ACTIVITY = "target_activity";

    private static final String CHANNEL = "winfg_overlay";
    private static final String FAILURE_CHANNEL = "winfg_failure";
    private static final int NOTIFICATION_ID = 73;
    private static final int FAILURE_NOTIFICATION_ID = 75;
    private static volatile boolean running;

    private HandlerThread captureThread;
    private Handler captureHandler;
    private final Handler main = new Handler(android.os.Looper.getMainLooper());

    private WindowManager windowManager;
    /** Display-bound context the overlay window is created from. */
    private Context windowContext;
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
    /** Usage bits the capture buffers must carry to be importable by Vulkan. */
    private static final long GPU_BUFFER_USAGE =
            HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE | HardwareBuffer.USAGE_GPU_COLOR_OUTPUT;
    private long skippedFrames;
    private int reattachAttempts;
    private String targetPackage;
    private String targetActivity;
    private boolean targetLaunched;
    private static volatile String lastFailure;
    private final Object projectionLock = new Object();
    /** Set once we are deliberately tearing down, so onStop is not reported as a failure. */
    private volatile boolean stopping;
    private volatile boolean captureExclusionReady;
    private static volatile boolean hiddenApiExempted;
    private int exclusionRetries;
    private boolean appOnlyCapture;
    private long capturedFrames;
    /** Freshness of each side of the pipeline; the watchdog acts on these. */
    private long lastCaptureMs;
    private long lastPresentMs;
    private long lastStatusMs;
    private int lastFps;
    private int lastBaseFps;
    private long captureWindowStart;
    private int captureWindowFrames;
    private int rendererRecoveries;
    private long lastRendererRecoveryMs;
    private int captureRestarts;
    private long lastCaptureRestartMs;
    private boolean watchdogRunning;
    private boolean frameLoopRunning;
    private Choreographer.FrameCallback frameCallback;
    /**
     * On-overlay performance badge. Persistent (it used to be hidden after seven
     * seconds and never shown again, which is why it looked like it never appeared),
     * styled like the Steam in-game FPS counter: a small translucent panel with the
     * presented rate large and the source rate / frame time underneath.
     */
    private TextView hud;
    private TextView hudDetail;
    private long hudWindowStart;
    private int hudFrames;
    // Per-window proof of the insert path: captures in, presents out, how many were
    // generated, and why each skipped capture was skipped.
    private long capCount, presentsCount, genCount;
    private final long[] skipReasons = new long[6];
    private boolean rendererRetried;
    private boolean rendererLost;
    private final Runnable hideHud = () -> { /* the badge is intentionally permanent */ };

    public static boolean isRunning() {
        return running;
    }

    /** Why the last overlay session ended, for the in-app status line. */
    public static String lastFailure() {
        return lastFailure;
    }

    public static void start(Context context, int resultCode, Intent data) {
        start(context, resultCode, data, false);
    }

    public static void start(Context context, int resultCode, Intent data, boolean appOnly) {
        start(context, resultCode, data, appOnly, null, null);
    }

    /**
     * Starts the overlay and lets the service launch {@code targetPackage} once capture is
     * live. Launching the game before the capture surface exists raced the target's first
     * paint and left the overlay showing nothing.
     */
    public static void start(Context context, int resultCode, Intent data, boolean appOnly,
                             String targetPackage, String targetActivity) {
        Intent i = new Intent(context, OverlayService.class)
                .setAction(ACTION_START)
                .putExtra(EXTRA_RESULT_CODE, resultCode)
                .putExtra(EXTRA_RESULT_DATA, data)
                .putExtra(EXTRA_APP_ONLY, appOnly);
        if (targetPackage != null) {
            i.putExtra(EXTRA_TARGET_PACKAGE, targetPackage)
                    .putExtra(EXTRA_TARGET_ACTIVITY, targetActivity);
        }
        if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(i);
        else context.startService(i);
    }

    public static void stop(Context context) {
        context.stopService(new Intent(context, OverlayService.class));
    }

    @Override
    public void onCreate() {
        super.onCreate();
        CrashLog.install(getApplicationContext());
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
        try {
            return handleStartCommand(intent);
        } catch (Throwable t) {
            // Nothing in the overlay pipeline may take the app down with it.
            Log.e(TAG, "overlay start failed", t);
            reportFailure("Overlay failed to start: " + t);
            stopSelf();
            return START_NOT_STICKY;
        }
    }

    private int handleStartCommand(Intent intent) {
        startForegroundCompat();

        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }

        if (Build.VERSION.SDK_INT < 31) {
            toast("Overlay mode requires Android 12 or newer");
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
            appOnlyCapture = intent.getBooleanExtra(EXTRA_APP_ONLY, false);
            targetPackage = intent.getStringExtra(EXTRA_TARGET_PACKAGE);
            targetActivity = intent.getStringExtra(EXTRA_TARGET_ACTIVITY);
        }
        if (resultData == null) {
            reportFailure("No screen-capture permission");
            stopSelf();
            return START_NOT_STICKY;
        }

        settings = AppSettings.load(this);
        // Overlay first, capture second, game last — the order the working no-root
        // frame-gen implementations use. The token is consumed immediately (Android 13
        // checks the foreground service when getMediaProjection is called), the capture
        // display is created from the surface callback, and the game is launched only
        // after a frame has been presented. Starting capture before the window made the
        // game rotate the display while the overlay surface and swapchain were still
        // being created.
        startProjection();
        showOverlay();
        // Safety net: the overlay surface normally arrives first and starts capture.
        // If it never does (the ROM blocks the window), capture and the game must
        // still start instead of leaving the user in this app.
        main.postDelayed(this::ensureStarted, 3000);
        return START_NOT_STICKY;
    }

    /**
     * Starts the capture display once the overlay window exists. Capture used to start
     * before the window, and the game was launched on the first captured frame; the
     * game then rotated and resized the display while the overlay surface and the
     * Vulkan swapchain were still being created, which left the overlay dead on top of
     * a running game.
     */
    private void ensureStarted() {
        if (!running || projection == null) return;
        startCapture();
        launchTarget();
    }

    private void startCapture() {
        synchronized (projectionLock) {
            if (projection == null) return;
            startVirtualDisplay();
        }
    }

    /** Launches the game once capture is live, then keeps the overlay on top of it. */
    private void launchTarget() {
        if (targetLaunched || targetPackage == null || targetActivity == null) return;
        targetLaunched = true;
        try {
            Intent i = new Intent(Intent.ACTION_MAIN)
                    .addCategory(Intent.CATEGORY_LAUNCHER)
                    .setComponent(new ComponentName(targetPackage, targetActivity))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                            | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            startActivity(i);
            Log.i(TAG, "launched " + targetPackage + "/" + targetActivity);
            main.postDelayed(this::bringToFront, 150);
            main.postDelayed(this::bringToFront, 600);
            main.postDelayed(this::bringToFront, 1500);
        } catch (Throwable t) {
            Log.w(TAG, "target launch failed", t);
        }
    }

    /** Re-asserts the overlay above the freshly launched game's window. */
    private void bringToFront() {
        if (overlayView == null || overlayParams == null) return;
        try {
            if (overlayView.isAttachedToWindow()) {
                windowManager.updateViewLayout(overlayView, overlayParams);
            }
        } catch (Throwable ignored) {}
    }

    private void startForegroundCompat() {
        Notification n = notification();
        if (Build.VERSION.SDK_INT >= 29) {
            try {
                startForeground(NOTIFICATION_ID, n,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
                return;
            } catch (Throwable t) {
                Log.w(TAG, "mediaProjection foreground type rejected; retrying plain", t);
            }
        }
        try {
            startForeground(NOTIFICATION_ID, n);
        } catch (Throwable t) {
            Log.e(TAG, "startForeground failed", t);
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
        return notification("Screen capture + frame generation · tap STOP to end");
    }

    private Notification notification(String text) {
        Intent stop = new Intent(this, OverlayService.class).setAction(ACTION_STOP);
        PendingIntent pi = PendingIntent.getService(this, 2, stop,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL)
                : new Notification.Builder(this);
        return b.setSmallIcon(R.drawable.ic_foreground)
                .setContentTitle("Win-FG overlay active")
                .setContentText(text)
                .setOngoing(true)
                .addAction(android.R.drawable.ic_menu_close_clear_cancel, "STOP", pi)
                .build();
    }

    /**
     * A Service is a non-visual context: its WindowManager has no guaranteed display, and on a
     * multi-display device like the AYN Thor that can mean metrics of 0x0 or a window that is
     * added to the wrong display. API 30+ can create a window context bound to a real display;
     * that is what every view and the window itself is built from.
     */
    private Context windowContext() {
        if (windowContext != null) return windowContext;
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                DisplayManager dm = (DisplayManager) getSystemService(DISPLAY_SERVICE);
                Display display = dm == null ? null : dm.getDisplay(Display.DEFAULT_DISPLAY);
                if (display != null) {
                    windowContext = createWindowContext(
                            display, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null);
                    Log.i(TAG, "window context bound to display " + display.getDisplayId()
                            + " " + display.getMode().getPhysicalWidth() + "x"
                            + display.getMode().getPhysicalHeight());
                }
            } catch (Throwable t) {
                Log.w(TAG, "could not create a display window context", t);
            }
        }
        if (windowContext == null) windowContext = this;
        return windowContext;
    }

    private void showOverlay() {
        if (overlayView != null) return;

        Context host = windowContext();
        try {
            windowManager = (WindowManager) host.getSystemService(WINDOW_SERVICE);
        } catch (Throwable t) {
            Log.w(TAG, "window manager from window context failed", t);
        }
        if (windowManager == null) {
            reportFailure("Display service unavailable");
            stopSelf();
            return;
        }

        overlayView = new FrameLayout(host);
        overlayView.setBackgroundColor(Color.TRANSPARENT);

        textureView = new TextureView(host);
        textureView.setOpaque(false);
        textureView.setKeepScreenOn(true);
        textureView.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override public void onSurfaceTextureAvailable(SurfaceTexture st, int width, int height) {
                try {
                    reattachAttempts = 0;
                    st.setDefaultBufferSize(screenWidth(), screenHeight());
                    openOutputSurface(st, width, height);
                    installSkipScreenshot(overlayView);
                    verifyExclusion();
                } catch (Throwable t) {
                    // The overlay window can be re-created under us; keep the
                    // capture alive and try to get a surface back.
                    Log.e(TAG, "overlay surface failed", t);
                    scheduleOverlayReattach();
                }
            }

            @Override public void onSurfaceTextureSizeChanged(SurfaceTexture st, int width, int height) {
                try {
                    st.setDefaultBufferSize(screenWidth(), screenHeight());
                    surfaceWidth = width;
                    surfaceHeight = height;
                    // A rotation or immersive change gives the overlay a new surface
                    // size. The Vulkan swapchain has to follow it, or every acquire is
                    // out of date and the overlay stops presenting.
                    ensureRenderer(width, height);
                    // Capture follows the DISPLAY, not the overlay view. Resizing the
                    // VirtualDisplay to the view's transient layout size (a game's
                    // immersive/rotation pass does this a few seconds in) made the
                    // projection stop producing buffers while still reporting itself as
                    // running: frame generation died while the cast stayed "active".
                    resizeCapture(screenWidth(), screenHeight());
                    installSkipScreenshot(overlayView);
                    note("overlay surface resized to " + width + "x" + height);
                } catch (Throwable t) {
                    Log.w(TAG, "overlay resize failed", t);
                }
            }

            @Override public boolean onSurfaceTextureDestroyed(SurfaceTexture st) {
                try {
                    note("overlay surface destroyed");
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
                    // Do NOT tear the projection down here. The overlay window's
                    // surface is destroyed transiently (game launch, display
                    // reconfiguration), and stopping the projection from this
                    // callback made the system end the cast and the service die a
                    // moment after it started. Keep capturing and re-attach.
                    if (projection != null) scheduleOverlayReattach();
                } catch (Throwable t) {
                    Log.w(TAG, "overlay teardown failed", t);
                }
                return true;
            }

            @Override public void onSurfaceTextureUpdated(SurfaceTexture st) {}
        });
        overlayView.addView(textureView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        // Small status badge over the game. When the overlay dies there is otherwise no
        // way to see why on the device itself: the foreground notification is removed
        // with the service and logcat needs adb.
        // Steam-style counter: translucent rounded panel, monospace, presented rate
        // large, source rate and frame time small underneath.
        LinearLayout badge = new LinearLayout(host);
        badge.setOrientation(LinearLayout.VERTICAL);
        android.graphics.drawable.GradientDrawable bg =
                new android.graphics.drawable.GradientDrawable();
        bg.setColor(0xB4000000);
        bg.setCornerRadius(10f);
        bg.setStroke(1, 0x33FFFFFF);
        badge.setBackground(bg);
        badge.setPadding(30, 18, 30, 18);

        hud = new TextView(host);
        hud.setTextColor(0xFFFFFFFF);
        hud.setTextSize(19f);
        hud.setTypeface(android.graphics.Typeface.MONOSPACE, android.graphics.Typeface.BOLD);
        hud.setIncludeFontPadding(false);
        hud.setText(settings != null && settings.mirrorOnly
                ? "-- MIRROR" : "-- FPS");

        hudDetail = new TextView(host);
        hudDetail.setTextColor(0xB3FFFFFF);
        hudDetail.setTextSize(11f);
        hudDetail.setTypeface(android.graphics.Typeface.MONOSPACE);
        hudDetail.setIncludeFontPadding(false);
        hudDetail.setText(settings != null && settings.mirrorOnly
                ? "frame generation off" : "starting engine");

        badge.addView(hud);
        badge.addView(hudDetail, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        FrameLayout.LayoutParams hudParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.START);
        hudParams.setMargins(28, 28, 0, 0);
        overlayView.addView(badge, hudParams);
        main.removeCallbacks(hideHud);

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
        // PRIVATE_FLAG_EXCLUDE_FROM_SCREEN_CAPTURE (0x00080000, API 31+): WMS tells
        // SurfaceFlinger to leave this window out of VirtualDisplay/screenshot
        // composition when the window is created, so there is no SurfaceControl
        // timing race with the later setSkipScreenshot transaction.
        try {
            java.lang.reflect.Field f = WindowManager.LayoutParams.class
                    .getDeclaredField("privateFlags");
            f.setAccessible(true);
            f.setInt(overlayParams, f.getInt(overlayParams) | 0x00080000);
            Log.i(TAG, "window marked exclude-from-screen-capture");
        } catch (Throwable t) {
            Log.w(TAG, "privateFlags exclude-from-capture unavailable", t);
        }
        overlayParams.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY;
        try {
            windowManager.addView(overlayView, overlayParams);
        } catch (Throwable t) {
            Log.e(TAG, "add overlay failed", t);
            reportFailure("Could not add the overlay window: " + t.getClass().getSimpleName()
                    + (t.getMessage() == null ? "" : " (" + t.getMessage() + ")"));
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
        startCapture();
    }

    private DisplayMetrics realMetrics() {
        DisplayMetrics dm = new DisplayMetrics();
        try {
            DisplayManager dmgr = (DisplayManager) getSystemService(DISPLAY_SERVICE);
            Display display = dmgr == null ? null : dmgr.getDisplay(Display.DEFAULT_DISPLAY);
            if (display != null) {
                display.getRealMetrics(dm);
                if (dm.widthPixels > 0 && dm.heightPixels > 0) return dm;
            }
        } catch (Throwable t) {
            Log.w(TAG, "display metrics from DisplayManager failed", t);
        }
        try {
            if (windowManager == null) {
                windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
            }
            //noinspection deprecation
            windowManager.getDefaultDisplay().getRealMetrics(dm);
        } catch (Throwable t) {
            Log.w(TAG, "display metrics from WindowManager failed", t);
        }
        return dm;
    }

    private int screenWidth() {
        return realMetrics().widthPixels;
    }

    private int screenHeight() {
        return realMetrics().heightPixels;
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
            OverlayRenderer r;
            try {
                r = new OverlayRenderer();
            } catch (Throwable t) {
                // A missing/mismatched libwinfg_overlay.so used to throw out of
                // the SurfaceTexture callback and kill the process.
                Log.e(TAG, "overlay renderer library unavailable", t);
                reportFailure("Frame generation library could not be loaded");
                failSessionSoon();
                return;
            }
            boolean started;
            try {
                started = r.start(outputSurface, width, height, displayRefreshRate(),
                        !s.mirrorOnly, crashDir(), s);
            } catch (Throwable t) {
                Log.e(TAG, "renderer start threw", t);
                started = false;
            }
            if (!started) {
                Log.e(TAG, "Vulkan overlay failed to start (" + width + "x" + height
                        + " @" + displayRefreshRate() + "Hz)");
                if (!rendererRetried) {
                    // The first swapchain after a display mode change occasionally
                    // fails on Adreno; one fresh attempt is cheap and fixes it.
                    rendererRetried = true;
                    main.postDelayed(() -> ensureRenderer(width, height), 700);
                    return;
                }
                reportFailure("Frame generation could not start (Vulkan " + width + "x"
                        + height + ")");
                failSessionSoon();
                return;
            }
            rendererRetried = false;
            rendererLost = false;
            renderer = r;
            rendererWidth = width;
            rendererHeight = height;
            // Start the "nothing presented" clock here, not at zero: a renderer that
            // never presents once must still be caught by the watchdog.
            lastPresentMs = android.os.SystemClock.elapsedRealtime();
            note("renderer started " + width + "x" + height
                    + (s.mirrorOnly ? " mirror-only" : " 2x"));
            Log.i(TAG, "overlay renderer started " + width + "x" + height
                    + " @" + displayRefreshRate() + "Hz"
                    + (s.mirrorOnly ? " mirror-only" : ""));
        }
    }

    private float displayRefreshRate() {
        try {
            DisplayManager dm = (DisplayManager) getSystemService(DISPLAY_SERVICE);
            Display display = dm == null ? null : dm.getDisplay(Display.DEFAULT_DISPLAY);
            if (display != null) {
                float hz = display.getRefreshRate();
                if (hz > 1f && hz < 1000f) return hz;
            }
        } catch (Throwable t) {
            Log.w(TAG, "display refresh rate unavailable", t);
        }
        return 60f;
    }

    private String crashDir() {
        java.io.File dir = new java.io.File(getFilesDir(), EngineManager.DIR);
        if (!dir.exists()) dir.mkdirs();
        return dir.getAbsolutePath();
    }

    /**
     * Per-capture proof for the session log: how many frames reached the swapchain
     * and, when only one did, the engine's own reason code for the skip. The first
     * 60 captures are written one per line so a two-second session is enough to see
     * a 2-per-capture cadence; after that a 5 s summary keeps the log bounded.
     */
    private void logCapture(int presented) {
        capCount++;
        presentsCount += Math.max(1, presented);
        int reason = 0;
        int[] st = null;
        synchronized (rendererLock) {
            if (renderer != null) st = renderer.stats();
        }
        if (st != null && st.length > 9) reason = st[9];
        if (presented >= 2) {
            genCount++;
        } else if (reason >= 0 && reason < skipReasons.length) {
            skipReasons[reason]++;
        }
        if (capturedFrames <= 60) {
            note("capture " + capturedFrames + ": presents=" + Math.max(1, presented)
                    + (presented >= 2 ? " (generated)" : " (passthrough, " + reasonName(reason) + ")"));
        }
    }

    private static String reasonName(int reason) {
        switch (reason) {
            case 1: return "frame generation off";
            case 2: return "first frame";
            case 3: return "no spare image";
            case 4: return "interval below threshold";
            case 5: return "spare acquire failed";
            default: return "ok";
        }
    }

    /**
     * @param frames frames that reached the swapchain for this capture: 2 when a
     *               generated frame was inserted, 1 for passthrough. Counting one
     *               per capture made the output rate unable to ever exceed the
     *               source rate, which is why the badge always read 30 -> 30.
     */
    private void countPresentedFrame(int frames) {
        long now = android.os.SystemClock.elapsedRealtime();
        if (hudWindowStart == 0) hudWindowStart = now;
        hudFrames += Math.max(1, frames);
        long elapsed = now - hudWindowStart;
        if (elapsed < 500) return;
        int fps = (int) (hudFrames * 1000 / Math.max(1, elapsed));
        lastFps = fps;
        long capElapsed = now - captureWindowStart;
        if (captureWindowStart != 0 && capElapsed >= 500) {
            lastBaseFps = (int) (captureWindowFrames * 1000 / Math.max(1, capElapsed));
            captureWindowFrames = 0;
            captureWindowStart = now;
        }
        final int base = lastBaseFps;
        final int out = fps;
        hudFrames = 0;
        hudWindowStart = now;
        final boolean mirror = settings != null && settings.mirrorOnly;
        main.post(() -> {
            if (hud != null) {
                hud.setVisibility(View.VISIBLE);
                // Left = what the game itself renders, right = what the panel gets
                // after frame generation. Mirror mode has no right-hand number.
                hud.setText(mirror || base <= 0
                        ? out + " FPS"
                        : base + "  \u2502  " + out + " FPS");
            }
            if (hudDetail != null) {
                hudDetail.setVisibility(View.VISIBLE);
                String detail = mirror || base <= 0
                        ? "output"
                        : "source \u2502 framegen";
                detail += out > 0
                        ? String.format(java.util.Locale.US, "  ·  %.1f ms", 1000f / out)
                        : "  ·  -- ms";
                if (!mirror && out > 0 && base > 0 && out > base + 3) {
                    detail += "  ·  +" + (out - base) + " generated";
                }
                hudDetail.setText(detail);
            }
        });
    }

    /**
     * One line in files/win-fg/overlay.log, which the crash reporter ships with a fault.
     * With no adb, the sequence of transitions is the only way to tell a dead renderer
     * from a stalled capture after the fact.
     */
    private void note(String line) {
        Log.i(TAG, line);
        try {
            java.io.File dir = new java.io.File(getFilesDir(), EngineManager.DIR);
            if (dir.exists() || dir.mkdirs()) {
                String stamp = new java.text.SimpleDateFormat("HH:mm:ss.SSS",
                        java.util.Locale.US).format(new java.util.Date());
                try (java.io.FileOutputStream out = new java.io.FileOutputStream(
                        new java.io.File(dir, "overlay.log"), true)) {
                    out.write((stamp + "  " + line + "\n").getBytes("UTF-8"));
                }
            }
        } catch (Throwable ignored) {}
    }

    private void startWatchdog() {
        if (watchdogRunning) return;
        watchdogRunning = true;
        lastCaptureMs = android.os.SystemClock.elapsedRealtime();
        main.postDelayed(watchdog, 1500);
    }

    private void stopWatchdog() {
        watchdogRunning = false;
        main.removeCallbacks(watchdog);
    }

    private final Runnable watchdog = new Runnable() {
        @Override public void run() {
            if (!watchdogRunning) return;
            try {
                checkPipeline();
            } catch (Throwable t) {
                Log.w(TAG, "watchdog failed", t);
            }
            if (watchdogRunning) main.postDelayed(this, 1000);
        }
    };

    /**
     * Recovers the two ways a live session silently stops generating frames while the
     * notification still says it is capturing: the capture stops delivering buffers, or
     * the renderer stops presenting them (a display-mode change wedges the swapchain).
     * Both were reported as "works for a few seconds, then nothing".
     */
    private void checkPipeline() {
        if (!running || stopping || projection == null) return;
        long now = android.os.SystemClock.elapsedRealtime();
        boolean haveWindow = surfaceReady && outputSurface != null && overlayView != null;
        long sinceCapture = lastCaptureMs == 0 ? 0 : now - lastCaptureMs;
        long sincePresent = lastPresentMs == 0 ? 0 : now - lastPresentMs;

        // Capture is delivering but nothing is presented: the swapchain or renderer is
        // wedged. This is unambiguous, so rebuild it without waiting.
        if (haveWindow && renderer != null && sinceCapture < 2000 && sincePresent > 2500) {
            if (rendererRecoveries < 6 && now - lastRendererRecoveryMs > 4000) {
                rendererRecoveries++;
                lastRendererRecoveryMs = now;
                note("watchdog: nothing presented for " + sincePresent + " ms while capture"
                        + " is flowing; rebuilding the renderer (attempt " + rendererRecoveries + ")");
                restartRenderer();
            }
        }

        // Nothing arriving at all for a long time: the projection display itself has
        // stalled. A paused game makes no frames either, so this is rate-limited hard.
        if (sinceCapture > 15000 && captureRestarts < 2 && now - lastCaptureRestartMs > 20000) {
            captureRestarts++;
            lastCaptureRestartMs = now;
            note("watchdog: no capture frame for " + sinceCapture + " ms; rebuilding the"
                    + " capture display (attempt " + captureRestarts + ")");
            restartCapture();
        }

        if (now - lastStatusMs > 5000) {
            lastStatusMs = now;
            String status;
            if (lastCaptureMs == 0) {
                status = "waiting for the first captured frame";
            } else if (sinceCapture < 3000) {
                // base = what the game actually renders, out = what the panel gets.
                // Seeing both is the only way to tell frame generation apart from a
                // game that happens to run at the display rate.
                status = lastBaseFps + " → " + lastFps + " fps"
                        + (settings != null && settings.mirrorOnly ? " · mirror only" : "");
            } else {
                status = "no new frames for " + (sinceCapture / 1000) + " s";
            }
            String why = insertReason();
            if (why != null) status = status + " · " + why;
            updateNotification("Screen capture + frame generation · " + status);
            note("fps " + status);
            if (capCount > 0) {
                note(String.format(java.util.Locale.US,
                        "presents %d captures → %d presents, %d generated (%.2fx)"
                        + " · skipped fgOff=%d first=%d noSpare=%d interval=%d acquire=%d",
                        capCount, presentsCount, genCount,
                        presentsCount / (float) Math.max(1, capCount),
                        skipReasons[1], skipReasons[2], skipReasons[3],
                        skipReasons[4], skipReasons[5]));
                capCount = 0; presentsCount = 0; genCount = 0;
                java.util.Arrays.fill(skipReasons, 0);
            }
        }
    }

    /**
     * Names the exact reason the engine is not inserting generated frames, so a
     * 30 -> 30 session is diagnosable on the device. Null when generation is working
     * or when there is nothing to say yet.
     */
    private String insertReason() {
        int[] st = null;
        synchronized (rendererLock) {
            if (renderer != null) st = renderer.stats();
        }
        if (st == null) {
            return settings != null && settings.mirrorOnly ? "frame generation off (mirror only)" : null;
        }
        if (st[0] > 0) return null;                        // something was generated
        if (st[1] > 0) return "frame generation off";      // fgReady_ false
        if (st[2] > 0 && st[3] == 0 && st[4] == 0) return "waiting for a second frame";
        if (st[3] > 0) return "no spare swapchain image";
        if (st[4] > 0) return "interval " + Math.abs(st[7]) + " ms < "
                + (st[8] * 5 / 4) + " ms (source already at panel rate)";
        if (st[5] > 0) return "spare image acquire failed";
        return null;
    }

    /** Rebuilds the Vulkan renderer against the surface that is already in the window. */
    private void restartRenderer() {
        synchronized (rendererLock) {
            if (renderer != null) {
                renderer.stop();
                renderer = null;
            }
        }
        rendererRetried = false;
        rendererLost = false;
        rendererWidth = 0;
        rendererHeight = 0;
        if (surfaceReady && outputSurface != null) {
            ensureRenderer(surfaceWidth, surfaceHeight);
            if (renderer != null) note("renderer rebuilt");
        } else {
            scheduleOverlayReattach();
        }
    }

    /** Recreates the projected display against the same MediaProjection consent. */
    private void restartCapture() {
        synchronized (projectionLock) {
            if (projection == null) return;
            int w = screenWidth();
            int h = screenHeight();
            if (w <= 0 || h <= 0) return;
            try {
                if (virtualDisplay != null) {
                    virtualDisplay.release();
                    virtualDisplay = null;
                }
            } catch (Throwable t) {
                Log.w(TAG, "release of the stalled capture display failed", t);
            }
            createReader(w, h);
            startVirtualDisplay();
            lastCaptureMs = android.os.SystemClock.elapsedRealtime();
            capturedFrames = 0;
        }
    }

    private void showHudError(String text) {
        main.post(() -> {
            if (hud == null) return;
            main.removeCallbacks(hideHud);
            hud.setText(text);
            hud.setVisibility(View.VISIBLE);
            if (hudDetail != null) hudDetail.setVisibility(View.GONE);
        });
    }

    /** Leaves the failure on screen for a few seconds, then ends the session. */
    private void failSessionSoon() {
        showHudError(lastFailure);
        main.postDelayed(() -> {
            if (running && renderer == null) stopSelf();
        }, 6000);
    }

    private static void exemptViewHiddenApis() {
        if (hiddenApiExempted) return;
        synchronized (OverlayService.class) {
            if (hiddenApiExempted) return;
            try {
                boolean ok = HiddenApiBypass.addHiddenApiExemptions("Landroid/view/");
                Log.i(TAG, "hidden API exemption for android.view: " + ok);
            } catch (Throwable t) {
                Log.e(TAG, "hidden API exemption failed", t);
            }
            hiddenApiExempted = true;
        }
    }

    private void startProjection() {
        // Called from the main thread (surface callbacks) and from the capture
        // thread (retries). Two getMediaProjection() calls with the same consent
        // token make the system reject the second one, so serialise them.
        synchronized (projectionLock) {
            startProjectionLocked();
        }
    }

    private void startProjectionLocked() {
        if (projection != null || resultData == null) return;
        int width = screenWidth();
        int height = screenHeight();
        if (width <= 0 || height <= 0) {
            reportFailure("Could not read the display size");
            stopSelf();
            return;
        }
        Log.i(TAG, "starting projection: sdk=" + Build.VERSION.SDK_INT
                + " targetSdk=" + getApplicationInfo().targetSdkVersion
                + " size=" + width + "x" + height
                + " appOnly=" + appOnlyCapture);
        surfaceWidth = width;
        surfaceHeight = height;
        try {
            MediaProjectionManager mpm =
                    (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
            if (mpm == null) {
                reportFailure("MediaProjection unavailable");
                stopSelf();
                return;
            }
            projection = mpm.getMediaProjection(resultCode, resultData);
            if (projection == null) {
                reportFailure("Screen-capture permission expired — start the overlay again");
                stopSelf();
                return;
            }
            projection.registerCallback(new MediaProjection.Callback() {
                // Capture is created from the overlay's surface callback, so the token
                // is held for a few hundred ms without a VirtualDisplay. That is valid
                // on AOSP; only reusing the consent intent is rejected.

                @Override public void onStop() {
                    if (stopping) {
                        main.post(OverlayService.this::stopSelf);
                        return;
                    }
                    // The system ended the cast (user stop, OEM policy, or the
                    // projection losing its foreground service).
                    Log.w(TAG, "projection stopped by the system");
                    main.post(() -> {
                        reportFailure("Screen capture was stopped by the system");
                        stopSelf();
                    });
                }
            }, captureHandler);

            Log.i(TAG, "projection token acquired; capture starts with the overlay surface");
        } catch (Throwable t) {
            Log.e(TAG, "projection start failed", t);
            reportFailure("Screen capture failed: " + t.getMessage());
            stopSelf();
        }
    }

    /** Creates the one capture display this token is allowed. */
    private void startVirtualDisplay() {
        if (projection == null || virtualDisplay != null) return;
        if (reader == null) {
            int w = screenWidth();
            int h = screenHeight();
            if (w <= 0 || h <= 0) {
                reportFailure("Could not read the display size");
                stopSelf();
                return;
            }
            createReader(w, h);
        }
        try {
            int density = getResources().getDisplayMetrics().densityDpi;
            // flags=0 is what scrcpy and screen-share apps use: MediaProjection already
            // mirrors the physical display. AUTO_MIRROR/PRESENTATION create a second
            // logical display and make some OEM compositors capture the overlay itself.
            virtualDisplay = projection.createVirtualDisplay(
                    "winfg-overlay", captureWidth, captureHeight, density, 0,
                    reader.getSurface(), null, captureHandler);
        } catch (Throwable t) {
            Log.e(TAG, "createVirtualDisplay failed", t);
            virtualDisplay = null;
        }
        if (virtualDisplay == null) {
            reportFailure("Could not create the capture display");
            stopSelf();
            return;
        }
        Log.i(TAG, "projection started " + captureWidth + "x" + captureHeight
                + " appOnly=" + appOnlyCapture);
        note("capture display started " + captureWidth + "x" + captureHeight);
        startWatchdog();
        updateNotification("Screen capture + frame generation · tap STOP to end");
    }

    /**
     * Confirms the overlay is excluded from its own capture once the window exists. The
     * capture is already running at this point, so a device that blocks exclusion has to
     * be told about rather than silently recording the overlay.
     */
    private void verifyExclusion() {
        if (appOnlyCapture || captureExclusionReady) {
            exclusionRetries = 0;
            return;
        }
        if (exclusionRetries++ < 8) {
            main.postDelayed(() -> {
                if (!running) return;
                installSkipScreenshot(overlayView);
                verifyExclusion();
            }, 250);
            return;
        }
        Log.e(TAG, "capture exclusion unavailable; refusing to mirror own overlay");
        reportFailure("Capture exclusion failed on this device");
        stopSelf();
    }

    private void updateNotification(String text) {
        try {
            NotificationManager nm =
                    (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm != null) nm.notify(NOTIFICATION_ID, notification(text));
        } catch (Throwable ignored) {}
    }

    private void createReader(int width, int height) {
        ImageReader old = reader;
        // Frame generation reads the captured image on the GPU. A plain
        // ImageReader allocates CPU-usage buffers, and importing one of those
        // into Vulkan is undefined behaviour (the driver may fault), so the
        // reader must request GPU usage explicitly.
        if (Build.VERSION.SDK_INT >= 29) {
            reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 4,
                    HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE
                            | HardwareBuffer.USAGE_GPU_COLOR_OUTPUT);
        } else {
            reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 4);
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
            long usage = buffer.getUsage();
            if ((usage & GPU_BUFFER_USAGE) == 0) {
                // Never hand a CPU-only buffer to Vulkan: import of an AHB
                // without AHARDWAREBUFFER_USAGE_GPU_* is undefined in the spec
                // and crashes Adreno drivers.
                if (skippedFrames++ == 0) {
                    Log.e(TAG, "capture buffer is not GPU accessible (usage=0x"
                            + Long.toHexString(usage) + "); frame generation needs "
                            + "GPU_SAMPLED_IMAGE|GPU_COLOR_OUTPUT");
                }
                return;
            }
            if (capturedFrames == 0) {
                Log.i(TAG, "first capture frame " + captureWidth + "x" + captureHeight
                        + " usage=0x" + Long.toHexString(usage));
            }
            lastCaptureMs = android.os.SystemClock.elapsedRealtime();
            if (captureWindowStart == 0) captureWindowStart = lastCaptureMs;
            captureWindowFrames++;
            int presented;
            synchronized (rendererLock) {
                presented = renderer == null ? 0 : renderer.submit(buffer, image.getTimestamp());
            }
            if (presented > 0) {
                lastPresentMs = android.os.SystemClock.elapsedRealtime();
                // The pipeline is proven healthy again: allow the window to be
                // re-attached as often as it takes instead of giving up forever.
                reattachAttempts = 0;
                rendererRecoveries = 0;
                // The overlay is actually presenting frames. Only now is it safe to let
                // the game take the display: launching earlier rotated and resized the
                // overlay surface while Vulkan was still initialising.
                if (targetPackage != null) main.post(this::launchTarget);
                countPresentedFrame(presented);
                logCapture(presented);
            } else if (presented < 0 && !stopping && !rendererLost) {
                // The driver reported the swapchain gone; rebuild the window and the
                // renderer instead of silently capturing into nothing.
                rendererLost = true;
                Log.w(TAG, "renderer lost its swapchain");
                main.post(this::scheduleOverlayReattach);
            }
            if ((++capturedFrames % 30) == 0) {
                Log.i(TAG, "captured " + capturedFrames + " frames ("
                        + captureWidth + "x" + captureHeight + ")");
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
            Log.w(TAG, "window SurfaceControl touchable region failed", t);
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
                exemptViewHiddenApis();
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
                if (sc == null) {
                    // Android 12+ BLAST pipeline: ViewRootImpl keeps the window's
                    // SurfaceControl inside a BLASTBufferQueue, which some OEM builds
                    // leave intact after stripping the direct SurfaceControl fields.
                    try {
                        Class<?> bbqCls = Class.forName("android.graphics.BLASTBufferQueue");
                        Class<?> c = vri.getClass();
                        while (c != null && sc == null) {
                            for (Field f : c.getDeclaredFields()) {
                                if (!bbqCls.isAssignableFrom(f.getType())) continue;
                                try {
                                    f.setAccessible(true);
                                    Object bbq = f.get(vri);
                                    if (bbq == null) continue;
                                    for (String name : new String[]{
                                            "getSyncedSurfaceControl", "getSurfaceControl"}) {
                                        Method m = findMethod(bbq.getClass(), name);
                                        if (m == null) continue;
                                        m.setAccessible(true);
                                        sc = (SurfaceControl) m.invoke(bbq);
                                        if (sc != null) {
                                            Log.i(TAG, "SurfaceControl via BLASTBufferQueue." + name);
                                            break;
                                        }
                                    }
                                } catch (Throwable ignored) {}
                                if (sc != null) break;
                            }
                            c = c.getSuperclass();
                        }
                    } catch (Throwable ignored) {}
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

    /** Logs, tells the user, and takes only the overlay down. */
    /**
     * Puts the overlay window back after its surface was destroyed. Capture keeps
     * running across the gap; only the renderer and the view are rebuilt.
     */
    private void scheduleOverlayReattach() {
        // No permanent give-up: a game can destroy the overlay surface on every activity
        // change, and stopping after five left the user with a live capture and no
        // overlay at all. reattachAttempts is reset on every presented frame, so this
        // ceiling only bounds a storm of attempts between two healthy frames.
        if (!running || reattachAttempts >= 20) {
            if (reattachAttempts >= 20) {
                Log.w(TAG, "overlay surface keeps disappearing; capture left running");
            }
            return;
        }
        reattachAttempts++;
        note("re-attaching overlay window (attempt " + reattachAttempts + ")");
        main.postDelayed(() -> {
            if (!running) return;
            try {
                if (overlayView != null) windowManager.removeView(overlayView);
            } catch (Throwable ignored) {}
            overlayView = null;
            textureView = null;
            showOverlay();
            if (projection != null && surfaceReady) installSkipScreenshot(overlayView);
        }, 700);
    }

    /**
     * Records why the overlay ended. The reason is logged, appended to
     * files/win-fg/overlay.log, shown in the notification and in the app, so a
     * device-specific failure is diagnosable without adb.
     */
    private void reportFailure(String reason) {
        lastFailure = reason;
        showHudError(reason);
        // A notification owned by the NotificationManager, not the service: this one survives
        // stopSelf(), so the reason is still readable in the shade after the overlay closes.
        postFailureNotification(reason);
        Log.e(TAG, reason);
        try {
            java.io.File dir = new java.io.File(getFilesDir(), EngineManager.DIR);
            if (dir.exists() || dir.mkdirs()) {
                String line = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss",
                        java.util.Locale.US).format(new java.util.Date()) + "  " + reason + "\n";
                try (java.io.FileOutputStream out =
                             new java.io.FileOutputStream(
                                     new java.io.File(dir, "overlay.log"), true)) {
                    out.write(line.getBytes("UTF-8"));
                }
            }
        } catch (Throwable ignored) {}
        try {
            NotificationManager nm =
                    (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm != null) nm.notify(NOTIFICATION_ID, notification("Stopped · " + reason));
        } catch (Throwable ignored) {}
        toast(reason);
    }

    private void postFailureNotification(String reason) {
        try {
            NotificationManager nm =
                    (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm == null) return;
            if (Build.VERSION.SDK_INT >= 26) {
                NotificationChannel ch = new NotificationChannel(FAILURE_CHANNEL,
                        "Win-FG stopped", NotificationManager.IMPORTANCE_HIGH);
                ch.setDescription("Why the overlay ended");
                nm.createNotificationChannel(ch);
            }
            Notification.Builder b = Build.VERSION.SDK_INT >= 26
                    ? new Notification.Builder(this, FAILURE_CHANNEL)
                    : new Notification.Builder(this);
            b.setSmallIcon(R.drawable.ic_foreground)
                    .setContentTitle("Win-FG overlay stopped")
                    .setContentText(reason)
                    .setStyle(new Notification.BigTextStyle().bigText(reason))
                    .setAutoCancel(true);
            nm.notify(FAILURE_NOTIFICATION_ID, b.build());
        } catch (Throwable ignored) {}
    }

    @Override
    public void onDestroy() {
        running = false;
        stopping = true;
        stopWatchdog();
        note("overlay session ended after " + capturedFrames + " captured frames");
        main.removeCallbacks(hideHud);
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

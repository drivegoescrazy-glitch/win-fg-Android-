package com.winfg.launcher;

import android.app.Activity;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.media.projection.MediaProjectionConfig;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.TextView;
import android.widget.Toast;

/**
 * Android 14+ capture consent.
 *
 * <p>Android 14 can capture one application window instead of the whole
 * display. That is the reliable way to keep this overlay out of its own
 * capture without the hidden SurfaceFlinger skip-screenshot API. The game is
 * launched first by {@link MainActivity}, so it is in the app selector; this
 * transparent activity only explains the choice and requests the projection.</p>
 */
public final class CaptureActivity extends Activity {
    private static final int REQ_CAPTURE = 201;
    private static final String EXTRA_PACKAGE = "pkg";
    private static final String EXTRA_ACTIVITY = "activity";
    private static final String EXTRA_LABEL = "label";

    private final Handler ui = new Handler(Looper.getMainLooper());
    private boolean asked;
    private boolean resultHandled;
    private String label;

    public static Intent intent(Context context, GameEntry game) {
        return new Intent(context, CaptureActivity.class)
                .putExtra(EXTRA_PACKAGE, game.packageName)
                .putExtra(EXTRA_ACTIVITY, game.activityName)
                .putExtra(EXTRA_LABEL, game.label);
    }

    public static void start(Context context, GameEntry game) {
        Intent i = intent(context, game)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                        | Intent.FLAG_ACTIVITY_NO_ANIMATION);
        context.startActivity(i);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (Build.VERSION.SDK_INT < 34) {
            finish();
            return;
        }
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null) nm.cancel(74);
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Grant display-over-other-apps first", Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        label = getIntent().getStringExtra(EXTRA_LABEL);
        if (label == null) label = "the game";
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS);

        TextView hint = new TextView(this);
        hint.setText("Choose SINGLE APP → " + label + "\n\n"
                + "Do not choose Entire screen.\n"
                + "Single-app capture keeps the Win-FG overlay out of its own input.");
        hint.setTextColor(Color.WHITE);
        hint.setTextSize(15);
        hint.setGravity(Gravity.CENTER);
        hint.setPadding(36, 48, 36, 48);
        hint.setBackgroundColor(0xE6101418);
        setContentView(hint, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        // The game has just been launched; give its task a moment to appear in
        // the system's recent-app selector before opening the consent dialog.
        ui.postDelayed(this::requestCapture, 900);
    }

    private void requestCapture() {
        if (asked || isFinishing()) return;
        asked = true;
        try {
            MediaProjectionManager mpm =
                    (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
            if (mpm == null) throw new IllegalStateException("no MediaProjectionManager");
            startActivityForResult(Api34.captureUserChoice(mpm), REQ_CAPTURE);
        } catch (Throwable t) {
            Toast.makeText(this, "Screen-capture permission unavailable", Toast.LENGTH_LONG).show();
            finish();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_CAPTURE || resultHandled) return;
        resultHandled = true;
        if (resultCode == RESULT_OK && data != null) {
            OverlayService.start(this, resultCode, data, true,
                    getIntent().getStringExtra(EXTRA_PACKAGE),
                    getIntent().getStringExtra(EXTRA_ACTIVITY));
            Toast.makeText(this, "Win-FG overlay active", Toast.LENGTH_SHORT).show();
        } else {
            Toast.makeText(this, "Screen capture not started", Toast.LENGTH_SHORT).show();
        }
        finish();
    }

    @Override
    protected void onDestroy() {
        ui.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    /** Kept separate so API 34 types are not resolved on older devices. */
    private static final class Api34 {
        static Intent captureUserChoice(MediaProjectionManager mpm) {
            return mpm.createScreenCaptureIntent(
                    MediaProjectionConfig.createConfigForUserChoice());
        }
    }
}

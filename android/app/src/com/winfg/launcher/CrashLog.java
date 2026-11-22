package com.winfg.launcher;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;

/**
 * Persists the stack trace of an uncaught exception before the process dies.
 *
 * <p>The overlay touches MediaProjection, the window compositor and a Vulkan
 * driver; when one of them fails on a specific device the only evidence is the
 * crash itself. This keeps the default handler (the app still crashes as before,
 * so Android's own report stays valid) but mirrors the trace to
 * {@code files/win-fg/crash.log} and logcat under the {@code win-fg-overlay} tag.</p>
 */
final class CrashLog {
    private static volatile boolean installed;

    private CrashLog() {}

    static synchronized void install(final Context context) {
        if (installed) return;
        installed = true;
        final Thread.UncaughtExceptionHandler previous =
                Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> {
            try {
                StringWriter text = new StringWriter();
                PrintWriter w = new PrintWriter(text);
                w.println("thread: " + thread.getName());
                error.printStackTrace(w);
                w.flush();
                Log.e("win-fg-overlay", "uncaught exception\n" + text, error);
                File dir = new File(context.getFilesDir(), EngineManager.DIR);
                if (dir.exists() || dir.mkdirs()) {
                    try (OutputStream out = new FileOutputStream(new File(dir, "crash.log"))) {
                        out.write(text.toString().getBytes("UTF-8"));
                    }
                }
            } catch (Throwable ignored) {
                // Never replace the real crash with a logging failure.
            }
            if (previous != null) previous.uncaughtException(thread, error);
        });
    }
}

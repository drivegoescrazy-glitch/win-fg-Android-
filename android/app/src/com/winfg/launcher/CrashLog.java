package com.winfg.launcher;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
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

    /**
     * Returns (and clears) the report a native renderer fault left behind. A Vulkan
     * driver crash kills the whole process, taking the notification with it, so this
     * file is the only way the failure reaches the user without adb.
     */
    static String takeNativeReport(Context context) {
        File file = new File(new File(context.getFilesDir(), EngineManager.DIR),
                "native_crash.log");
        if (!file.isFile()) return null;
        try {
            int size = (int) Math.min(file.length(), 8192);
            if (size <= 0) {
                file.delete();
                return null;
            }
            byte[] data = new byte[size];
            int off = 0;
            try (FileInputStream in = new FileInputStream(file)) {
                int n;
                while (off < size && (n = in.read(data, off, size - off)) > 0) off += n;
            }
            file.delete();
            return new String(data, 0, off, "UTF-8");
        } catch (Throwable t) {
            return null;
        }
    }
}

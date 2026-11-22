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
 * {@code files/win-fg/crash.log}, logcat under the {@code win-fg-overlay} tag, and the
 * crash-report queue that files an issue on GitHub (see {@link CrashReports}).</p>
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
                // Queue it for the issue tracker before the process is gone. This runs in a
                // dying thread, so it keeps to a small file write.
                String title = "[crash] " + error.getClass().getSimpleName()
                        + (error.getMessage() == null ? "" : ": " + clip(error.getMessage(), 70))
                        + " · v" + AppInfo.VERSION_NAME;
                CrashReports.enqueue(context, "java", title,
                        CrashReports.describe(context) + "\n### Java trace\n\n```\n"
                                + text.toString().trim() + "\n```\n"
                                + "\n### Session log (tail)\n\n```\n"
                                + sessionLogTail(context, 40) + "\n```\n");
            } catch (Throwable ignored) {
                // Never replace the real crash with a logging failure.
            }
            if (previous != null) previous.uncaughtException(thread, error);
        });
    }

    private static String clip(String s, int max) {
        String flat = s.replace('\n', ' ').trim();
        return flat.length() <= max ? flat : flat.substring(0, max) + "…";
    }

    /**
     * Returns (and clears) the report a native renderer fault left behind, queueing it for
     * the tracker on the way. A Vulkan driver crash kills the whole process, taking the
     * notification with it, so this file is the only way the failure reaches the user
     * without adb.
     */
    static String collectNativeReport(Context context) {
        String text = takeNativeReport(context);
        if (text != null && !text.trim().isEmpty()) {
            CrashReports.enqueueNative(context, withSessionLog(context, text));
        }
        return text;
    }

    /**
     * Appends the tail of the overlay session log. A native fault only says where it
     * died; the log says which transition the pipeline was in (surface destroyed,
     * renderer rebuilt, capture restarted), which is what makes the next report
     * actionable without a device in hand.
     */
    static String withSessionLog(Context context, String report) {
        String log = sessionLogTail(context, 40);
        return log.isEmpty() ? report
                : report.trim() + "\n\n### Session log (tail)\n\n```\n" + log + "\n```\n";
    }

    static String sessionLogTail(Context context, int lines) {
        java.io.File file = new java.io.File(new java.io.File(context.getFilesDir(),
                EngineManager.DIR), "overlay.log");
        if (!file.isFile()) return "";
        try {
            int size = (int) Math.min(file.length(), 16384);
            byte[] data = new byte[size];
            int off = 0;
            try (FileInputStream in = new FileInputStream(file)) {
                in.skip(file.length() - size);
                int n;
                while (off < size && (n = in.read(data, off, size - off)) > 0) off += n;
            }
            String[] all = new String(data, 0, off, "UTF-8").split("\n");
            int start = Math.max(0, all.length - lines);
            StringBuilder b = new StringBuilder();
            for (int i = start; i < all.length; i++) b.append(all[i]).append('\n');
            return b.toString().trim();
        } catch (Throwable t) {
            return "";
        }
    }

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

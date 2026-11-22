package com.winfg.launcher;

import android.content.Context;
import android.os.Build;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.net.URLEncoder;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Crash reports that end up in the project's GitHub issue tracker.
 *
 * <p>A crash on the device is invisible to the developer: there is no adb, and the
 * notification that could have carried the details dies with the process. Every fault
 * (an uncaught Java exception, or a native signal the renderer's own handler caught) is
 * therefore reduced to a report that survives the process, is deduplicated by a
 * fingerprint of its stack, and can be filed on GitHub in one tap from the app. The
 * report carries everything needed to act on it: launcher version, device, the frame-gen
 * profile in use and the symbolicated trace (module + offset + renderer stage).</p>
 *
 * <p>Filing goes through the user's own signed-in browser session, so the app never ships
 * or stores a GitHub token.</p>
 */
final class CrashReports {
    /** Queue of undelivered reports, under files/win-fg/. */
    private static final String DIRNAME = "crash-reports";
    /** GitHub truncates absurdly long query strings; the full text stays in the app. */
    private static final int URL_BODY_LIMIT = 12000;
    /** Oldest reports are dropped beyond this many distinct faults. */
    private static final int MAX_FILES = 12;

    private static final Pattern SIGNAL = Pattern.compile("native signal (\\d+)");
    private static final Pattern MODULE = Pattern.compile("([A-Za-z0-9_.\\-]+\\.so)\\+0x([0-9a-fA-F]+)");
    private static final Pattern STAGE = Pattern.compile("stage: *([A-Za-z0-9_. >-]+)");

    static final class Report {
        String fingerprint = "";
        String kind = "crash";
        String title = "";
        String body = "";
        int count = 1;
        long first;
        long last;
        /** Issue URL once it has been opened for this report. */
        String url;
        File file;

        String label() {
            return count > 1 ? title + "  (×" + count + ")" : title;
        }

        boolean reported() {
            return url != null && !url.isEmpty();
        }
    }

    private CrashReports() {}

    static File dir(Context c) {
        return new File(new File(c.getFilesDir(), EngineManager.DIR), DIRNAME);
    }

    /**
     * The block every report starts with. Captured when the fault happens: a report is
     * useless if it only says what the stack looked like and not which device, build and
     * frame-generation profile produced it.
     */
    static String describe(Context c) {
        String mode;
        String profile;
        try {
            AppSettings s = AppSettings.load(c);
            mode = s.mirrorOnly ? "mirror only (no generated frames)" : "frame generation";
            profile = s.multiplier + "× · " + s.modelName() + " · " + s.presetName();
        } catch (Throwable t) {
            mode = "unknown";
            profile = "unknown";
        }
        String abi = Build.SUPPORTED_ABIS.length > 0 ? Build.SUPPORTED_ABIS[0] : "unknown";
        return "### Environment\n"
                + "- Win-FG launcher: " + AppInfo.VERSION_NAME + " (versionCode "
                + AppInfo.VERSION_CODE + ")\n"
                + "- Device: " + Build.MANUFACTURER + " " + Build.MODEL + "\n"
                + "- Android: " + Build.VERSION.RELEASE + " (SDK " + Build.VERSION.SDK_INT
                + ") · ABI " + abi + "\n"
                + "- Overlay mode: " + mode + "\n"
                + "- Profile: " + profile + "\n"
                + "- Display: " + displayLine(c) + "\n";
    }

    private static String displayLine(Context c) {
        try {
            android.view.WindowManager wm =
                    (android.view.WindowManager) c.getSystemService(Context.WINDOW_SERVICE);
            if (wm == null) return "unknown";
            android.util.DisplayMetrics dm = new android.util.DisplayMetrics();
            //noinspection deprecation
            wm.getDefaultDisplay().getRealMetrics(dm);
            return dm.widthPixels + "x" + dm.heightPixels + " @ "
                    + String.format(Locale.US, "%.0f", wm.getDefaultDisplay().getRefreshRate())
                    + "Hz";
        } catch (Throwable t) {
            return "unknown";
        }
    }

    /** Records a fault. Repeats of the same stack bump a counter instead of piling up. */
    static synchronized String enqueue(Context c, String kind, String title, String body) {
        try {
            File dir = dir(c);
            if (!dir.exists() && !dir.mkdirs()) return null;
            String fp = fingerprint(kind, body);
            File file = new File(dir, fp + ".json");
            Report r = read(file);
            long now = System.currentTimeMillis();
            if (r == null) {
                r = new Report();
                r.first = now;
                r.count = 0;
            }
            r.fingerprint = fp;
            r.kind = kind;
            r.title = title;
            r.body = body;
            r.count++;
            r.last = now;
            r.file = file;
            write(r, file);
            prune(dir);
            return fp;
        } catch (Throwable t) {
            return null;
        }
    }

    static String enqueueNative(Context c, String trace) {
        String title = nativeTitle(trace);
        String body = describe(c) + "\n### Renderer trace\n\n```\n" + trace.trim() + "\n```\n";
        return enqueue(c, "native", title, body);
    }

    /**
     * The renderer already symbolicates itself (module + offset + stage). Those two facts
     * make a useful issue title; the raw addresses alone do not.
     */
    private static String nativeTitle(String trace) {
        String signal = group(SIGNAL, trace);
        String where = null;
        Matcher m = MODULE.matcher(trace);
        while (m.find()) {
            // Skip the launcher's own frames first only when a driver frame exists later;
            // the first module in the report is the faulting one.
            where = m.group(1) + "+0x" + m.group(2);
            break;
        }
        String stage = group(STAGE, trace);
        StringBuilder b = new StringBuilder("[crash] ");
        b.append(signal == null ? "native fault" : "native signal " + signal);
        if (where != null) b.append(" in ").append(where);
        if (stage != null) b.append(" (stage: ").append(stage.trim()).append(')');
        return b.toString();
    }

    private static String group(Pattern p, String text) {
        Matcher m = p.matcher(text);
        return m.find() ? (m.groupCount() >= 1 ? m.group(1) : m.group()) : null;
    }

    /** Newest first. */
    static List<Report> pending(Context c) {
        List<Report> out = new ArrayList<>();
        File dir = dir(c);
        File[] files = dir.listFiles();
        if (files == null) return out;
        for (File f : files) {
            if (!f.isFile() || !f.getName().endsWith(".json")) continue;
            Report r = read(f);
            if (r == null || r.title.isEmpty()) continue;
            r.file = f;
            out.add(r);
        }
        Collections.sort(out, (a, b) -> Long.compare(b.last, a.last));
        return out;
    }

    static int count(Context c) {
        return pending(c).size();
    }

    static Report latest(Context c) {
        List<Report> all = pending(c);
        return all.isEmpty() ? null : all.get(0);
    }

    /** Prefilled "new issue" URL: the report becomes an issue in the project tracker. */
    static String issueUrl(Report r) {
        String body = r.body;
        if (body.length() > URL_BODY_LIMIT) {
            body = body.substring(0, URL_BODY_LIMIT)
                    + "\n\n_(truncated — the full report is in Win-FG → Engine → CRASH REPORTS)_\n";
        }
        return AppInfo.REPO_URL + "/issues/new?title=" + encode(r.title) + "&body=" + encode(body);
    }

    static void markReported(Context c, Report r, String url) {
        if (r == null || r.file == null) return;
        r.url = url;
        try {
            write(r, r.file);
        } catch (Throwable ignored) {}
    }

    static void clear(Context c) {
        File[] files = dir(c).listFiles();
        if (files == null) return;
        for (File f : files) f.delete();
    }

    private static String encode(String s) {
        try {
            return URLEncoder.encode(s, "UTF-8").replace("+", "%20");
        } catch (Throwable t) {
            return "";
        }
    }

    /**
     * Identifies a fault by the structure of its trace, not by its addresses: a repeated
     * crash on the same code path must land in the same issue even when ASLR and the
     * thread clock differ.
     */
    private static String fingerprint(String kind, String body) {
        StringBuilder sig = new StringBuilder(kind).append('\n');
        for (String line : body.split("\n")) {
            String t = line.trim();
            if (t.startsWith("at ") || t.startsWith("native ") || t.startsWith("pc ")
                    || t.startsWith("lr ") || t.startsWith("stage:") || t.startsWith("Caused by")
                    || t.startsWith("lib") || t.contains(".so+0x") || t.startsWith("backtrace")) {
                sig.append(t).append('\n');
            }
            if (sig.length() > 2048) break;
        }
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(sig.toString().getBytes("UTF-8"));
            StringBuilder hex = new StringBuilder();
            for (int i = 0; i < 8; i++) hex.append(String.format(Locale.US, "%02x", d[i]));
            return hex.toString();
        } catch (Throwable t) {
            return Integer.toHexString(sig.toString().hashCode());
        }
    }

    private static void prune(File dir) {
        List<Report> all = new ArrayList<>();
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File f : files) {
            Report r = read(f);
            if (r == null) {
                f.delete();
                continue;
            }
            r.file = f;
            all.add(r);
        }
        if (all.size() <= MAX_FILES) return;
        Collections.sort(all, (a, b) -> Long.compare(b.last, a.last));
        for (int i = MAX_FILES; i < all.size(); i++) all.get(i).file.delete();
    }

    private static Report read(File file) {
        try {
            if (!file.isFile() || file.length() <= 0) return null;
            int size = (int) Math.min(file.length(), 64 * 1024);
            byte[] data = new byte[size];
            int off = 0;
            try (FileInputStream in = new FileInputStream(file)) {
                int n;
                while (off < size && (n = in.read(data, off, size - off)) > 0) off += n;
            }
            JSONObject o = new JSONObject(new String(data, 0, off, "UTF-8"));
            Report r = new Report();
            r.fingerprint = o.optString("fingerprint", "");
            r.kind = o.optString("kind", "crash");
            r.title = o.optString("title", "");
            r.body = o.optString("body", "");
            r.count = o.optInt("count", 1);
            r.first = o.optLong("first", 0L);
            r.last = o.optLong("last", 0L);
            String url = o.optString("url", "");
            r.url = url.isEmpty() ? null : url;
            r.file = file;
            return r;
        } catch (Throwable t) {
            return null;
        }
    }

    private static void write(Report r, File file) throws Exception {
        JSONObject o = new JSONObject();
        o.put("fingerprint", r.fingerprint);
        o.put("kind", r.kind);
        o.put("title", r.title);
        o.put("body", r.body);
        o.put("count", r.count);
        o.put("first", r.first);
        o.put("last", r.last);
        o.put("url", r.url == null ? "" : r.url);
        try (OutputStream out = new FileOutputStream(file)) {
            out.write(o.toString().getBytes("UTF-8"));
        }
    }
}

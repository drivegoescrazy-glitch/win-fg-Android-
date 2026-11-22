package com.winfg.launcher;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.content.pm.SigningInfo;
import android.content.res.AssetManager;
import android.net.Uri;
import android.os.Build;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Everything that touches the bundled win-fg engine: asset extraction, profile
 * writing, the Magisk module installer, and the release signing fingerprint.
 */
public final class EngineManager {
    public static final String DIR = "win-fg";
    public static final String PREFS = "winfg_engine";
    private static final String MODULE = "/data/adb/modules/winfg";
    private static final ExecutorService EXEC = Executors.newSingleThreadExecutor();

    private EngineManager() {}

    public static File engineDir(Context c) {
        return new File(c.getFilesDir(), DIR);
    }

    public static File confFile(Context c) {
        return new File(engineDir(c), "conf.toml");
    }

    public static File externalConf(Context c) {
        File ext = c.getExternalFilesDir(null);
        return ext == null ? confFile(c) : new File(new File(ext, DIR), "conf.toml");
    }

    public static boolean layerInstalled(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("installed", false);
    }

    static void setLayerInstalled(Context c, boolean installed) {
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putBoolean("installed", installed).apply();
    }

    /** Copies the layer .so + manifest + version stamp out of the APK once per build. */
    public static synchronized void ensureExtracted(Context c) throws IOException {
        File dir = engineDir(c);
        if (!dir.exists() && !dir.mkdirs()) throw new IOException("Cannot create " + dir);
        AssetManager am = c.getAssets();
        File stamp = new File(dir, ".stamp");
        String wanted = AppInfo.VERSION_NAME + "|" + assetText(c, DIR + "/VERSION").trim();
        if (stamp.exists() && wanted.equals(readText(stamp).trim())) return;

        String[] names = am.list(DIR);
        if (names == null || names.length == 0) throw new IOException("Bundled win-fg assets are missing");
        for (String name : names) {
            try (InputStream in = am.open(DIR + "/" + name);
                 OutputStream out = new FileOutputStream(new File(dir, name))) {
                copy(in, out);
            }
        }
        writeText(stamp, wanted);
    }

    public static String engineVersion(Context c) {
        String v = assetText(c, DIR + "/VERSION").trim();
        return v.isEmpty() ? ("Win-FG " + AppInfo.VERSION_NAME) : v;
    }

    /** Writes the profile everywhere the layer or a host can pick it up. */
    public static void writeProfile(Context c, AppSettings s) {
        String toml = s.toToml();
        try {
            writeText(confFile(c), toml);
        } catch (IOException ignored) {}
        try {
            File ext = externalConf(c);
            File parent = ext.getParentFile();
            if (parent != null && (parent.exists() || parent.mkdirs())) writeText(ext, toml);
        } catch (IOException ignored) {}

        SharedPreferences p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String tree = p.getString("host_tree", null);
        if (tree != null) {
            try {
                Uri uri = Uri.parse(tree);
                Uri folder = SafHelper.ensureDir(c, uri, DIR);
                SafHelper.writeFile(c, uri, folder, "conf.toml",
                        toml.getBytes("UTF-8"), "text/plain");
            } catch (Exception ignored) {}
        }
        pushProps(c, s);
    }

    /** Mirrors the profile into winfg.* props read by the installed system layer. */
    public static void pushProps(Context c, AppSettings s) {
        if (!layerInstalled(c)) return;
        final String script = profilePropScript(s);
        EXEC.execute(() -> runSu(script, 15));
    }

    public static boolean hasRoot() {
        Su r = runSu("id", 5);
        return r.ok() && r.output.contains("uid=0");
    }

    public static boolean systemLayerInstalled() {
        Su r = runSu("[ -d " + MODULE + " ] && echo WINFG_YES || echo WINFG_NO", 5);
        return r.ok() && r.output.contains("WINFG_YES");
    }

    /** Installs a Magisk module with the layer as a system implicit layer. */
    public static String installSystemLayer(Context c, AppSettings s) throws IOException {
        ensureExtracted(c);
        File dir = engineDir(c);
        File sysManifest = new File(dir, "VkLayer_win_framegen.system.json");
        writeText(sysManifest, systemManifest());

        String e = shq(dir.getAbsolutePath());
        String script =
                "MOD=" + MODULE + "\n" +
                "rm -rf \"$MOD\"\n" +
                "mkdir -p \"$MOD/system/etc/vulkan/implicit_layer.d\" || exit 11\n" +
                "cp " + e + "/libwin_fg.so \"$MOD/system/etc/vulkan/implicit_layer.d/libwin_fg.so\" || exit 12\n" +
                "cp " + e + "/VkLayer_win_framegen.system.json \"$MOD/system/etc/vulkan/implicit_layer.d/VkLayer_win_framegen.json\" || exit 13\n" +
                "chmod 755 \"$MOD\" \"$MOD/system\" \"$MOD/system/etc\" \"$MOD/system/etc/vulkan\" \"$MOD/system/etc/vulkan/implicit_layer.d\"\n" +
                "chmod 644 \"$MOD/system/etc/vulkan/implicit_layer.d/\"* || exit 14\n" +
                "cat > \"$MOD/module.prop\" <<'WINFGEOF'\n" +
                "id=winfg\n" +
                "name=Win-FG Frame Generation\n" +
                "version=v" + AppInfo.VERSION_NAME + "\n" +
                "versionCode=" + AppInfo.VERSION_CODE + "\n" +
                "author=Win-FG\n" +
                "description=System Vulkan implicit layer for Win-FG frame generation.\n" +
                "WINFGEOF\n" +
                "chmod 644 \"$MOD/module.prop\"\n" +
                "echo WINFG_INSTALLED\n";

        Su r = runSu(script, 30);
        if (!r.ok() || !r.output.contains("WINFG_INSTALLED")) {
            throw new IOException("Root install failed: " + summarise(r));
        }
        setLayerInstalled(c, true);
        pushProps(c, s);
        return "System layer installed. Reboot to activate it.";
    }

    public static String removeSystemLayer(Context c) throws IOException {
        StringBuilder script = new StringBuilder();
        script.append("rm -rf ").append(MODULE).append('\n');
        String[] props = {"enabled", "debug", "pacing", "model", "mult", "preset", "extra",
                "flow", "gm", "reg", "iters", "lambda", "dt", "edge", "eps", "rev"};
        for (String key : props) {
            script.append("(resetprop -d winfg.").append(key)
                    .append(" 2>/dev/null || setprop winfg.").append(key).append(" \"\")\n");
        }
        script.append("echo WINFG_REMOVED\n");
        Su r = runSu(script.toString(), 20);
        if (!r.ok()) throw new IOException("Remove failed: " + summarise(r));
        setLayerInstalled(c, false);
        return "System layer removed. Reboot to unload it.";
    }

    /** Copies the layer + profile into a user-picked folder for a Winlator/Bannerlator host. */
    public static int syncHostFolder(Context c, Uri tree, AppSettings s) throws Exception {
        ensureExtracted(c);
        Uri folder = SafHelper.ensureDir(c, tree, DIR);
        File dir = engineDir(c);
        byte[] so = readAll(new File(dir, "libwin_fg.so"));
        byte[] manifest = readAll(new File(dir, "VkLayer_win_framegen.json"));
        byte[] conf = s.toToml().getBytes("UTF-8");
        SafHelper.writeFile(c, tree, folder, "libwin_fg.so", so, "application/octet-stream");
        SafHelper.writeFile(c, tree, folder, "VkLayer_win_framegen.json", manifest, "application/json");
        SafHelper.writeFile(c, tree, folder, "conf.toml", conf, "text/plain");
        return 3;
    }

    public static String signingSha256(Context c) {
        try {
            PackageManager pm = c.getPackageManager();
            Signature[] sigs;
            if (Build.VERSION.SDK_INT >= 28) {
                SigningInfo si = pm.getPackageInfo(c.getPackageName(),
                        PackageManager.GET_SIGNING_CERTIFICATES).signingInfo;
                sigs = si != null && si.hasMultipleSigners()
                        ? si.getApkContentsSigners() : si.getSigningCertificateHistory();
            } else {
                sigs = pm.getPackageInfo(c.getPackageName(),
                        PackageManager.GET_SIGNATURES).signatures;
            }
            if (sigs == null || sigs.length == 0) return "unavailable";
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(sigs[0].toByteArray());
            StringBuilder sb = new StringBuilder();
            for (byte b : d) {
                if (sb.length() > 0) sb.append(':');
                sb.append(String.format(Locale.US, "%02X", b & 0xFF));
            }
            return sb.toString();
        } catch (Exception e) {
            return "unavailable";
        }
    }

    private static String profilePropScript(AppSettings s) {
        StringBuilder b = new StringBuilder();
        b.append("if [ -x /data/adb/magisk/resetprop ]; then CMD=/data/adb/magisk/resetprop; ")
                .append("elif command -v resetprop >/dev/null 2>&1; then CMD=resetprop; ")
                .append("else CMD=setprop; fi\n");
        prop(b, "winfg.enabled", s.enabled ? "1" : "0");
        prop(b, "winfg.debug", s.debug ? "1" : "0");
        prop(b, "winfg.pacing", s.pacing ? "1" : "0");
        prop(b, "winfg.model", Integer.toString(s.model));
        prop(b, "winfg.mult", Integer.toString(s.multiplier));
        prop(b, "winfg.preset", Integer.toString(s.perfPreset));
        prop(b, "winfg.extra", Integer.toString(s.extraImages));
        prop(b, "winfg.flow", String.format(Locale.US, "%.2f", s.flowScale));
        prop(b, "winfg.gm", Integer.toString(s.gmMode));
        prop(b, "winfg.reg", Integer.toString(s.frMode));
        prop(b, "winfg.iters", Integer.toString(s.frIters));
        prop(b, "winfg.lambda", String.format(Locale.US, "%.2f", s.frLambda));
        prop(b, "winfg.dt", String.format(Locale.US, "%.2f", s.frDt));
        prop(b, "winfg.edge", String.format(Locale.US, "%.1f", s.frEdge));
        prop(b, "winfg.eps", String.format(Locale.US, "%.3f", s.frEps));
        // Bump the revision last so the layer re-reads a complete profile.
        prop(b, "winfg.rev", Long.toString(System.currentTimeMillis()));
        return b.toString();
    }

    private static void prop(StringBuilder b, String key, String value) {
        b.append("$CMD ").append(key).append(' ').append(value).append('\n');
    }

    private static String systemManifest() {
        return "{\n" +
                "  \"file_format_version\": \"1.2.0\",\n" +
                "  \"layer\": {\n" +
                "    \"name\": \"VK_LAYER_WIN_framegen\",\n" +
                "    \"type\": \"GLOBAL\",\n" +
                "    \"library_path\": \"/system/etc/vulkan/implicit_layer.d/libwin_fg.so\",\n" +
                "    \"api_version\": \"1.1.0\",\n" +
                "    \"implementation_version\": \"1\",\n" +
                "    \"description\": \"Win-FG — clean-room frame generation (FSR3 optical flow + own synthesis)\",\n" +
                "    \"library_arch\": \"64\",\n" +
                "    \"functions\": {\n" +
                "      \"vkNegotiateLoaderLayerInterfaceVersion\": \"vkNegotiateLoaderLayerInterfaceVersion\"\n" +
                "    },\n" +
                "    \"disable_environment\": { \"WIN_FG_DISABLE\": \"1\" }\n" +
                "  }\n" +
                "}\n";
    }

    private static String summarise(Su r) {
        if (r.timedOut) return "su timed out";
        String out = r.output == null ? "" : r.output.trim();
        if (out.length() > 220) out = out.substring(out.length() - 220);
        return "exit " + r.code + (out.isEmpty() ? "" : " — " + out);
    }

    private static String shq(String s) {
        return "'" + s.replace("'", "'\\''") + "'";
    }

    private static final class Su {
        final int code;
        final String output;
        final boolean timedOut;

        Su(int code, String output, boolean timedOut) {
            this.code = code;
            this.output = output == null ? "" : output;
            this.timedOut = timedOut;
        }

        boolean ok() { return code == 0 && !timedOut; }
    }

    private static Su runSu(String script, int timeoutSec) {
        Process proc = null;
        try {
            proc = new ProcessBuilder("su", "-c", script)
                    .redirectErrorStream(true).start();
            final Process p = proc;
            final ByteArrayOutputStream buf = new ByteArrayOutputStream();
            Thread reader = new Thread(() -> {
                try (InputStream in = p.getInputStream()) {
                    byte[] b = new byte[4096];
                    int n;
                    while ((n = in.read(b)) != -1) buf.write(b, 0, n);
                } catch (IOException ignored) {}
            });
            reader.start();
            boolean done = proc.waitFor(timeoutSec, TimeUnit.SECONDS);
            if (!done) {
                proc.destroy();
                return new Su(-1, "timed out", true);
            }
            reader.join(1200);
            return new Su(proc.exitValue(), buf.toString("UTF-8"), false);
        } catch (Exception e) {
            if (proc != null) proc.destroy();
            return new Su(-1, e.getMessage(), false);
        }
    }

    static String assetText(Context c, String path) {
        try (InputStream in = c.getAssets().open(path)) {
            return new String(readAll(in), "UTF-8");
        } catch (Exception e) {
            return "";
        }
    }

    private static byte[] readAll(File f) throws IOException {
        try (InputStream in = new FileInputStream(f)) {
            return readAll(in);
        }
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        copy(in, out);
        return out.toByteArray();
    }

    private static void copy(InputStream in, OutputStream out) throws IOException {
        byte[] b = new byte[8192];
        int n;
        while ((n = in.read(b)) != -1) out.write(b, 0, n);
    }

    private static String readText(File f) {
        try (InputStream in = new FileInputStream(f)) {
            return new String(readAll(in), "UTF-8");
        } catch (IOException e) {
            return "";
        }
    }

    private static void writeText(File f, String text) throws IOException {
        File parent = f.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IOException("Cannot create " + parent);
        }
        try (OutputStream out = new FileOutputStream(f)) {
            out.write(text.getBytes("UTF-8"));
        }
    }
}

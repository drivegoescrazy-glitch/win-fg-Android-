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

/**
 * Everything that touches the bundled win-fg engine: asset extraction, profile
 * writing, host-folder export, and the release signing fingerprint.
 */
public final class EngineManager {
    public static final String DIR = "win-fg";
    public static final String PREFS = "winfg_engine";

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

    /** Writes the profile everywhere a host export can pick it up. */
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
